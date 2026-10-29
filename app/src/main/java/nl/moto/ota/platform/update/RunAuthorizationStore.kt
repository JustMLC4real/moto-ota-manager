package nl.moto.ota.platform.update

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.update.*
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Explicit-user-run authority. load is inspection only; a new object/process cannot reactivate
 * an old consent. App dispatch markers and root used/nonce markers are intentionally separate.
 * All methods perform file I/O and belong on the caller's I/O dispatcher.
 */
class RunAuthorizationStore(private val context: Context) {
    private val update = File(context.filesDir, "update")
    private val automation = File(update, "automation")
    private val authority = File(update, "authority.key")
    private val authorization = AtomicFile(File(automation, "authorization.properties"))
    private var activeConsentNonce: String? = null
    private var activeRunId: String? = null
    private var packageVerifiedInThisSession = false

    fun create(runId: String, device: DeviceState, target: ObservedUpdateTarget, userStarted: Boolean): RunAuthorization = synchronized(LOCK) {
        requireSafety(userStarted, "RUN_START_REQUIRED")
        val current = journal()
        requireSafety(current.stage in TERMINAL, "RUN_AUTHORIZATION_ACTIVE")
        ensureDirectories()
        val secret = key(create = true)
        val now = System.currentTimeMillis()
        val auth = RunAuthorizationPolicy.authorize(runId, device, target, secret, true, now, SystemClock.elapsedRealtime())
        // Never reuse a run UUID, even if an old authorization expires or an app restart lost its object.
        val seen = privateDirectory(automation, "seen")
        val marker = File(seen, runId)
        exclusiveWrite(marker, "run-authorized\n".toByteArray(), "RUN_ID_ALREADY_USED")
        writeAtomic(authorization, RunAuthorizationCodec.encode(auth))
        activeConsentNonce = auth.consentNonce
        activeRunId = auth.runId
        packageVerifiedInThisSession = target.packageVerified && target.packageSha256.isNotEmpty()
        auth
    }

    /** No key generation, signing, mutation dispatch or in-memory activation occurs here. */
    fun load(): RunAuthorization? = synchronized(LOCK) {
        try {
            if (!exists(authorization.baseFile) && !exists(File(authorization.baseFile.path + ".bak"))) return@synchronized null
            requireDirectories()
            readAuthorization(key(false))
        } catch (_: Exception) { null }
    }

    fun pinPackage(reportSha: String, targetBuild: String, declaredFP: String, signatureVerified: Boolean): RunAuthorization = synchronized(LOCK) {
        requireDirectories()
        val secret = key(false)
        val auth = active(readAuthorization(secret))
        val run = requireJournal(auth, requirePackage = false)
        requireSafety(run.packageVerified && run.packageSha256 == reportSha && run.targetBuild == targetBuild &&
            run.declaredTargetFingerprint == declaredFP, "RUN_PACKAGE_CHANGED")
        val pinned = RunAuthorizationPolicy.pinPackageHash(auth, reportSha, targetBuild, declaredFP, signatureVerified,
            secret, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        // Scope cannot be narrowed after any action was dispatched: that would invalidate its capability.
        val dispatched = File(automation, "dispatched/${auth.runId}")
        if (pinned.scopeDigest() != auth.scopeDigest())
            requireSafety(!exists(dispatched) || privateEntries(dispatched).isEmpty(), "RUN_SCOPE_ALREADY_DISPATCHED")
        writeAtomic(authorization, RunAuthorizationCodec.encode(pinned))
        packageVerifiedInThisSession = true
        pinned
    }

    /** Durably consumes the app action before returning a new short-lived root operation. */
    fun claim(action: RunAction): Pair<RunAuthorization, AuthorizedRunOperation> = synchronized(LOCK) {
        requireDirectories()
        val secret = key(false)
        val auth = active(readAuthorization(secret))
        requireSafety(packageVerifiedInThisSession, "RUN_VERIFIED_PACKAGE_REQUIRED")
        requireJournal(auth, requirePackage = true)
        val operation = RunAuthorizationPolicy.issueOperation(auth, action, secret, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        val dispatched = privateDirectory(privateDirectory(automation, "dispatched"), auth.runId)
        val text = "dispatched-v1\n${auth.scopeDigest()}\n${digest(RunAuthorizationCodec.encodeOperation(operation))}\n"
        exclusiveWrite(File(dispatched, "${action.name}.claim"), text.toByteArray(Charsets.UTF_8))
        auth to operation
    }

    private fun active(auth: RunAuthorization): RunAuthorization {
        requireSafety(activeConsentNonce == auth.consentNonce && activeRunId == auth.runId, "RUN_EXPLICIT_SESSION_REQUIRED")
        return auth
    }

    private fun requireJournal(auth: RunAuthorization, requirePackage: Boolean): UpdateRun {
        val run = journal()
        val now = System.currentTimeMillis()
        requireSafety(!run.simulated && run.id == auth.runId && run.stage !in TERMINAL &&
            run.sourceModel==auth.model && run.sourceDevice==auth.device && run.sourceChannel==auth.channel &&
            run.stage !in setOf(UpdateStage.FAILED, UpdateStage.RECOVERY_REQUIRED) && run.sourceBootId == auth.sourceBootId &&
            run.sourceBuild == auth.sourceBuild && run.sourceFingerprint == auth.sourceFingerprint && run.sourceSlot == auth.sourceSlot &&
            run.targetBuild == auth.targetBuild && run.targetSlot == auth.targetSlot &&
            run.lastObservedBootId == auth.sourceBootId && run.lastObservedBuild == auth.sourceBuild && run.lastObservedSlot == auth.sourceSlot &&
            run.lastObservationMillis > 0 && now - run.lastObservationMillis in 0..30_000, "RUN_SOURCE_CHANGED")
        requireSafety(!requirePackage || (run.packageVerified && run.packageSha256 == auth.packageSha256 &&
            run.declaredTargetFingerprint == auth.targetFingerprint), "RUN_PACKAGE_CHANGED")
        if (auth.targetFingerprint.isNotBlank()) requireSafety(run.declaredTargetFingerprint == auth.targetFingerprint, "RUN_PACKAGE_CHANGED")
        return run
    }

    private fun readAuthorization(secret: ByteArray): RunAuthorization =
        RunAuthorizationCodec.decode(readAtomic(authorization, RunAuthorizationCodec.MAX_AUTHORIZATION_BYTES)).also {
            RunAuthorizationPolicy.verify(it, secret, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }

    private fun journal(): UpdateRun {
        val file = File(context.filesDir, "guided-update-run.properties")
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).filter(::exists).forEach(::requirePrivate)
        return UpdateRunStore(context).load()
    }

    private fun ensureDirectories() {
        requireParent(context.filesDir)
        privateDirectory(context.filesDir, "update")
        privateDirectory(update, "automation")
    }

    private fun requireDirectories() { requireParent(context.filesDir); requireParent(update); requireParent(automation) }

    private fun privateDirectory(parent: File, name: String): File {
        requireParent(parent)
        requireSafety(name.matches(Regex("[A-Za-z0-9_-]{1,80}")), "RUN_STORE_PATH_INVALID")
        val child = File(parent, name)
        if (!exists(child)) {
            try { Os.mkdir(child.absolutePath, 0x1c0); syncDirectory(parent) }
            catch (error: Exception) { if (!exists(child)) throw UpdateSafetyException("RUN_STORE_DIRECTORY_UNAVAILABLE") }
        }
        requireParent(child)
        requireSafety(child.canonicalFile.parentFile == parent.canonicalFile, "RUN_STORE_PATH_INVALID")
        return child
    }

    private fun requireParent(file: File) {
        val stat = try {Os.lstat(file.absolutePath)}catch(_:Exception){throw UpdateSafetyException("RUN_STORE_DIRECTORY_INVALID")}
        requireSafety(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == context.applicationInfo.uid && stat.st_mode and 0x12 == 0,
            "RUN_STORE_DIRECTORY_INVALID")
    }

    private fun key(create: Boolean): ByteArray {
        requireParent(update)
        if (!exists(authority)) {
            requireSafety(create, "RUN_AUTHORITY_KEY_MISSING")
            exclusiveWrite(authority, ByteArray(32).also { SecureRandom().nextBytes(it) })
        }
        return readPrivate(authority, 32).also { requireSafety(it.size == 32, "RUN_AUTHORITY_KEY_INVALID") }
    }

    private fun readAtomic(file: AtomicFile, maximum: Int): String {
        listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).filter(::exists).forEach(::requirePrivate)
        return file.openRead().use { input ->
            requirePrivateDescriptor(input.fd, maximum)
            val bytes = input.readNBytes(maximum + 1)
            requireSafety(bytes.size <= maximum, "RUN_STORE_BOUNDS")
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun readPrivate(file: File, maximum: Int): ByteArray {
        requirePrivate(file)
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        val input = try { FileInputStream(descriptor) } catch (error: Exception) { Os.close(descriptor); throw error }
        return input.use {
            val before = requirePrivateDescriptor(descriptor, maximum)
            val bytes = it.readNBytes(maximum + 1)
            val after = requirePrivateDescriptor(descriptor, maximum)
            requireSafety(bytes.size <= maximum && before == after && sameIdentity(file, before), "RUN_STORE_CHANGED")
            bytes
        }
    }

    private fun writeAtomic(file: AtomicFile, text: String) {
        requireParent(requireNotNull(file.baseFile.parentFile))
        listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).filter(::exists).forEach(::requirePrivate)
        val out = file.startWrite()
        try {
            Os.fchmod(out.fd, 0x180)
            out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out)
            syncDirectory(requireNotNull(file.baseFile.parentFile))
        } catch (error: Exception) { file.failWrite(out); throw error }
    }

    private fun exclusiveWrite(file: File, bytes: ByteArray, existsCode: String = "RUN_ACTION_ALREADY_CLAIMED") {
        requireParent(requireNotNull(file.parentFile))
        requireSafety(!exists(file), existsCode)
        val descriptor = try { Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180) } catch (_: Exception) { throw UpdateSafetyException("RUN_STORE_EXCLUSIVE_CREATE_FAILED") }
        try {
            var offset = 0
            while (offset < bytes.size) {
                val n = Os.write(descriptor, bytes, offset, bytes.size - offset)
                requireSafety(n > 0, "RUN_STORE_WRITE_FAILED"); offset += n
            }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
        syncDirectory(requireNotNull(file.parentFile))
    }

    private data class Identity(val dev: Long, val ino: Long, val size: Long, val modified: Long, val changed: Long)
    private fun requirePrivate(file: File) {
        val stat = Os.lstat(file.absolutePath)
        requireSafety(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == context.applicationInfo.uid && stat.st_mode and 0x3f == 0,
            "RUN_STORE_FILE_INVALID")
    }
    private fun requirePrivateDescriptor(fd: FileDescriptor, maximum: Int): Identity {
        val s = Os.fstat(fd)
        requireSafety(OsConstants.S_ISREG(s.st_mode) && s.st_uid == context.applicationInfo.uid && s.st_mode and 0x3f == 0 &&
            s.st_size in 0..maximum.toLong(), "RUN_STORE_FILE_INVALID")
        return Identity(s.st_dev, s.st_ino, s.st_size, s.st_mtime, s.st_ctime)
    }
    private fun sameIdentity(file: File, expected: Identity): Boolean {
        val s = Os.lstat(file.absolutePath)
        return Identity(s.st_dev, s.st_ino, s.st_size, s.st_mtime, s.st_ctime) == expected && OsConstants.S_ISREG(s.st_mode)
    }
    private fun syncDirectory(file: File) {
        requireParent(file)
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { requireSafety(OsConstants.S_ISDIR(Os.fstat(fd).st_mode), "RUN_STORE_DIRECTORY_INVALID"); Os.fsync(fd) }
        finally { Os.close(fd) }
    }
    private fun privateEntries(directory: File): List<File> { requireParent(directory); return directory.listFiles()?.toList() ?: throw UpdateSafetyException("RUN_STORE_DIRECTORY_UNAVAILABLE") }
    private fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun requireSafety(value: Boolean, code: String) { if (!value) throw UpdateSafetyException(code) }
    companion object {
        private val LOCK = Any()
        private val TERMINAL = setOf(UpdateStage.IDLE, UpdateStage.PRECHECK_BLOCKED, UpdateStage.COMPLETE, UpdateStage.COMPLETE_REVIEW, UpdateStage.ROLLBACK)
    }
}
