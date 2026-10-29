package nl.moto.ota.rootprobe.staging

import android.os.Build
import android.os.IBinder
import android.os.IUpdateEngine
import android.os.IUpdateEngineCallback
import android.os.RecoverySystem
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.*
import nl.moto.ota.core.staging.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.DeviceTrustAnchors
import nl.moto.ota.platform.staging.AtomicStagePublisher
import nl.moto.ota.platform.staging.MotorolaPackageStager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Isolated copy-only root entry point. Never invokes installation, settings writes,
 * Magisk scripts, partition writes, boot control commands, rollback or reboot.
 */
object MotorolaStageProbe {
    private const val USER_ROOT = "/data/user/0/nl.moto.ota/files"
    private const val DATA_ROOT = "/data/data/nl.moto.ota/files"
    private const val IMAGE_BYTES = 8_388_608L

    @JvmStatic fun main(args: Array<String>) {
        try {
            requireRuntime(android.os.Process.myUid(), Build.VERSION.SDK_INT)
            if (args.size != 3 || !args[0].matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
                throw StageFailure(StageError.STAGING_PATH_REJECTED)
            val receipt = execute(args[0], args[1], args[2])
            println(receipt.toString()); System.out.flush()
        } catch (error: Throwable) {
            println(JSONObject().put("error", (error as? StageFailure)?.code?.name ?: StageError.STAGING_IO.name))
            System.out.flush()
        }
        // Exits only this dedicated app_process, never the Android system.
        kotlin.system.exitProcess(0)
    }

    internal fun requireRuntime(uid: Int, sdk: Int) {
        if (uid != 0 || sdk != 37) throw StageFailure(StageError.STAGING_RUNTIME_UNSUPPORTED)
    }

    private fun execute(jobId: String, libraryDirectory: String, apkPath: String): JSONObject {
        val appRoot = File(USER_ROOT).canonicalFile
        if (appRoot.path !in setOf(USER_ROOT, DATA_ROOT) || !appRoot.isDirectory) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val rootStat = Os.lstat(appRoot.path)
        val owner = rootStat.st_uid
        if (owner < 10_000 || rootStat.st_mode and 0x002 != 0 || (rootStat.st_mode and 0x010 != 0 && rootStat.st_gid != owner))
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val requests = File(appRoot, "staging-requests")
        if (requests.canonicalFile != requests || !OsConstants.S_ISDIR(Os.lstat(requests.path).st_mode) ||
            Os.lstat(requests.path).st_uid != owner) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val requestFile = privateFile(File(requests, "$jobId.request.json"), owner, 262_144)
        val request = JSONObject(readPrivate(requestFile, owner, 262_144))
        if (request.getInt("schema") != 1 || request.getString("job") != jobId) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        MotorolaStagePolicy.requireConfirmed(request.getBoolean("confirmed"), request.getBoolean("automaticRecognitionAcknowledged"))
        val created = request.getLong("created"); val expires = request.getLong("expires")
        if (expires - created != 3_600_000L || System.currentTimeMillis() - created !in 0..3_600_000)
            throw StageFailure(StageError.STAGING_CONFIRMATION_REQUIRED)
        val elapsedDeadline = SystemClock.elapsedRealtime() + (expires - System.currentTimeMillis())
        val clientPid = request.getInt("clientPid")
        val clientStart = request.getString("clientStartTicks")
        if (clientPid <= 1 || !clientStart.matches(Regex("[0-9]{1,24}"))) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        fun stillActive() {
            if (SystemClock.elapsedRealtime() > elapsedDeadline || System.currentTimeMillis() > expires)
                throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED)
            if (File(requests, "$jobId.cancelled").exists()) throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED)
            val heartbeat = privateFile(File(requests, "$jobId.heartbeat"), owner, 128)
            if (readPrivate(heartbeat, owner, 128) != jobId || System.currentTimeMillis() - heartbeat.lastModified() !in 0..10_000)
                throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED)
            val process = File("/proc/$clientPid")
            if (!process.isDirectory || Os.stat(process.path).st_uid != owner ||
                File(process, "stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19] != clientStart)
                throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED)
        }
        stillActive()
        thread(isDaemon = true, name = "stage-consent-watchdog") {
            while (true) {
                try { Thread.sleep(1_000); stillActive() }
                catch (_: Throwable) { Runtime.getRuntime().halt(23) }
            }
        }
        val run = UpdateRun.decode(request.getString("run"))
        fun requireCurrentRun() {
            val journal = privateFile(File(appRoot, "guided-update-run.properties"), owner, 131_072)
            val before = identity(journal)
            val latest = UpdateRun.decode(readPrivate(journal, owner, 131_072))
            if (identity(journal) != before) throw StageFailure(StageError.STAGING_SOURCE_CHANGED)
            MotorolaStagePolicy.requireSameRun(run, latest)
        }
        requireCurrentRun()
        val pinnedName = request.getString("pinned")
        if (!pinnedName.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}[.]zip")))
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val verified = File(appRoot, "verified-packages")
        if (verified.canonicalFile != verified || !OsConstants.S_ISDIR(Os.lstat(verified.path).st_mode) ||
            Os.lstat(verified.path).st_uid != owner) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val pinned = privateFile(File(verified, pinnedName), owner, FirmwareValidator.MAX_PACKAGE_BYTES)
        val expectedBytes = request.getLong("bytes"); val expectedHash = request.getString("sha256")
        if (!expectedHash.matches(Regex("[a-f0-9]{64}")) || pinned.length() != expectedBytes || expectedBytes !in 1..FirmwareValidator.MAX_PACKAGE_BYTES)
            throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
        val fileIdentity = identity(pinned)
        fun sourceUnchanged() { stillActive(); if (identity(pinned) != fileIdentity) throw StageFailure(StageError.STAGING_SOURCE_CHANGED) }
        val environment = observe()
        val inspected = FirmwareValidator.inspect(pinned, environment.device)
        if (!inspected.structureValid || !inspected.payloadHashValid || inspected.sha256 != expectedHash)
            throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
        stillActive()
        val anchors = DeviceTrustAnchors.spkiSha256()
        if (anchors.isEmpty()) throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
        try { RecoverySystem.verifyPackage(pinned, null, File("/system/etc/security/otacerts.zip")) }
        catch (_: Exception) { throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED) }
        sourceUnchanged()
        if (DeviceTrustAnchors.spkiSha256() != anchors || VerifiedStageTransfer.digest(pinned, expectedBytes, ::sourceUnchanged) != expectedHash)
            throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
        val report = inspected.copy(signatureVerified = true)
        MotorolaStagePolicy.requireSafe(report, run, observe())
        requireCurrentRun()
        AtomicStagePublisher.loadForRoot(libraryDirectory, apkPath)
        val destinationDirectory = exactExternalDirectory(MotorolaStagePolicy.DESTINATION_DIRECTORY)
        val temporaryDirectory = File(MotorolaStagePolicy.TEMPORARY_DIRECTORY)
        val parent = exactExternalDirectory("/storage/emulated/0/Android/data/nl.moto.ota/files")
        if (temporaryDirectory.parentFile != parent || (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdir()))
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        exactExternalDirectory(temporaryDirectory.path)
        val destination = File(destinationDirectory, MotorolaStagePolicy.destinationName(expectedHash))
        // One explicit request cannot replay automatically after a helper crash.
        val consumed = File(requests, "$jobId.consumed")
        val token = try { Os.open(consumed.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) }
            catch (_: Exception) { throw StageFailure(StageError.STAGING_CONFIRMATION_REQUIRED) }
        try { val bytes = expectedHash.toByteArray(Charsets.US_ASCII); Os.write(token, bytes, 0, bytes.size); Os.fsync(token) }
        finally { Os.close(token) }
        Os.chown(consumed.path, owner, owner)
        syncDirectory(requests)
        val alreadyPresent = VerifiedStageTransfer.copy(pinned, temporaryDirectory, destination, expectedBytes, expectedHash,
            check = ::sourceUnchanged, publish = { temporary, target, size ->
                sourceUnchanged()
                if (DeviceTrustAnchors.spkiSha256() != anchors) throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
                MotorolaStagePolicy.requireSafe(report, run, observe())
                requireCurrentRun()
                stillActive()
                AtomicStagePublisher.publish(temporary, target, size)
            })
        sourceUnchanged()
        val receipt = JSONObject().put("schema", 1).put("run", run.id).put("sha256", expectedHash).put("bytes", expectedBytes)
            .put("destination", destination.path).put("boot", run.sourceBootId).put("staged", System.currentTimeMillis()).put("alreadyPresent", alreadyPresent)
            .put("oemReviewRequired", true).put("signedChannelProven", report.regionVerified)
            .put("androidSourceFingerprint", report.androidMetadataSourceFingerprint).put("payloadSourceFingerprint", report.payloadSourceFingerprint)
        MotorolaPackageStager.writePrivate(File(requests, "$jobId.receipt.json"), receipt.toString())
        Os.chown(File(requests, "$jobId.receipt.json").path, owner, owner)
        syncDirectory(requests)
        return receipt
    }

    private fun exactExternalDirectory(path: String): File = File(path).also {
        if (it.canonicalFile != it || !OsConstants.S_ISDIR(Os.lstat(it.path).st_mode)) throw StageFailure(StageError.STAGING_PATH_REJECTED)
    }

    private fun privateFile(file: File, owner: Int, limit: Long): File = file.also {
        val stat = Os.lstat(file.path)
        if (file.canonicalFile != file || !OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != owner ||
            stat.st_mode and 0x03f != 0 || stat.st_size !in 1..limit) throw StageFailure(StageError.STAGING_PATH_REJECTED)
    }

    private fun readPrivate(file: File, owner: Int, limit: Int): String {
        privateFile(file, owner, limit.toLong())
        val bytes = file.inputStream().use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun identity(file: File): String = Os.lstat(file.path).let { "${it.st_dev}|${it.st_ino}|${it.st_size}|${it.st_mtime}|${it.st_ctime}" }

    private fun syncDirectory(directory: File) {
        if (!OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode)) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw StageFailure(StageError.STAGING_PATH_REJECTED)
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }

    /** Independent current source evidence; all block descriptors are read-only. */
    private fun observe(): StageEnvironment {
        val started = System.currentTimeMillis()
        fun property(name: String) = command(listOf("/system/bin/getprop", name)).trim()
        val slot = property("ro.boot.slot_suffix")
        if (slot !in setOf("_a", "_b")) throw StageFailure(StageError.STAGING_DEVICE_UNSAFE)
        val kernel = File("/proc/bootconfig").readText().lineSequence().mapNotNull { line ->
            val pair = line.split('=', limit = 2)
            if (pair.size == 2) pair[0].trim().removePrefix("androidboot.") to pair[1].trim().trim('"') else null
        }.toMap()
        if (kernel["slot_suffix"] != slot || kernel["verifiedbootstate"] != "orange" ||
            kernel["vbmeta.device_state"] != "unlocked" || kernel["flash.locked"] != "0") throw StageFailure(StageError.STAGING_DEVICE_UNSAFE)
        val misc = block("misc")
        val control = RandomAccessFile(misc, "r").use { f -> f.seek(2048); ByteArray(32).also { f.readFully(it) } }.let(ReadOnlyMetadata::parseBootControl)
            ?: throw StageFailure(StageError.STAGING_DEVICE_UNSAFE)
        val index = if (slot == "_a") 0 else 1
        if (control.slotCount != 2 || !control.slots[index].markedSuccessful || control.slots[index].verityCorrupted ||
            (control.recordedSlotSuffix.isNotEmpty() && control.recordedSlotSuffix != slot)) throw StageFailure(StageError.STAGING_DEVICE_UNSAFE)
        val vab = RandomAccessFile(misc, "r").use { f -> f.seek(32768); ByteArray(64).also { f.readFully(it) } }.let(ReadOnlyMetadata::parseVirtualAb)
        val state = File("/metadata/ota/state")
        val snapshots = File("/metadata/ota/snapshots")
        val stateBytes = runCatching {
            if (!state.isFile || state.length() !in 0..65_536) return@runCatching null
            state.inputStream().use { it.readNBytes(65_537) }.takeIf { it.size <= 65_536 }
        }.getOrNull()
        if (vab?.mergeStatus != VirtualAbMergeStatus.NONE || stateBytes == null ||
            ReadOnlyMetadata.parseSnapshotState(stateBytes)?.state != SnapshotState.NONE ||
            !snapshots.isDirectory || snapshots.list()?.isEmpty() != true || !freshEngineIdle()) throw StageFailure(StageError.STAGING_DEVICE_UNSAFE)
        val skus = listOf(property("ro.boot.hardware.sku"), property("ro.boot.product.hardware.sku"), property("ro.product.model"))
            .filter { it.matches(Regex("XT[0-9]+(?:-[0-9]+)?")) }.distinct()
        val channels = listOf(property("ro.boot.carrier"), property("ro.carrier")).filter(String::isNotBlank).distinct()
        val current = block("init_boot$slot")
        if (command(listOf("/system/bin/blockdev", "--getsize64", current.path)).trim().toLongOrNull() != IMAGE_BYTES)
            throw StageFailure(StageError.STAGING_STOCK_UNPROVEN)
        val activeHash = current.inputStream().use { input ->
            val data = input.readNBytes(IMAGE_BYTES.toInt() + 1)
            if (data.size.toLong() != IMAGE_BYTES) throw StageFailure(StageError.STAGING_STOCK_UNPROVEN)
            java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val battery = command(listOf("/system/bin/dumpsys", "battery")).lineSequence()
            .firstOrNull { it.trim().startsWith("level:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1
        val device = DeviceState(manufacturer = property("ro.product.manufacturer"), model = if (skus == listOf("XT2607-1")) "XT2607-1" else "",
            device = property("ro.product.device"), channel = if (channels == listOf("reteu")) "reteu" else "",
            androidVersion = property("ro.build.version.release"), build = property("ro.build.id"), fingerprint = property("ro.build.fingerprint"),
            vendorFingerprint = property("ro.vendor.build.fingerprint"),
            incrementalBuild = property("ro.build.version.incremental"), buildTimestamp = property("ro.build.date.utc").toLongOrNull() ?: 0,
            securityPatch = property("ro.build.version.security_patch"), slot = slot, bootId = File("/proc/sys/kernel/random/boot_id").readText().trim(),
            rootChecked = true, rootGranted = true, magiskCode = command(listOf("/system/bin/sh", "-c", "magisk -V")).trim().toIntOrNull() ?: 0,
            virtualAb = property("ro.virtual_ab.enabled") == "true", snapshot = SnapshotState.NONE, engine = EngineState.IDLE,
            batteryPercent = battery, capturedAtMillis = started, bootSuccessful = true, bootloaderUnlocked = true, verifiedBootState = "orange")
        val automaticUpdatesDisabled = command(listOf("/system/bin/settings", "get", "global", "ota_disable_automatic_update")).trim() == "1"
        val systemFingerprint = property("ro.system.build.fingerprint")
        return StageEnvironment(device, Build.VERSION.SDK_INT, automaticUpdatesDisabled, activeHash, System.currentTimeMillis(), systemFingerprint)
    }

    private fun block(name: String): File {
        if (name !in setOf("misc", "init_boot_a", "init_boot_b")) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        return File("/dev/block/by-name/$name").canonicalFile.also {
            if (!it.path.startsWith("/dev/block/") || !OsConstants.S_ISBLK(Os.stat(it.path).st_mode)) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        }
    }

    private fun command(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val output = ByteArrayOutputStream(); var oversized = false
        val reader = thread(isDaemon = true) { process.inputStream.use { input -> val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break
                synchronized(output) { if (output.size() + n <= 65_536) output.write(buffer, 0, n) else oversized = true }
            }
        } }
        val errors = thread(isDaemon = true) { process.errorStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { } } }
        val complete = process.waitFor(3, TimeUnit.SECONDS)
        if (!complete) process.destroyForcibly()
        reader.join(500); errors.join(500)
        if (!complete || reader.isAlive || errors.isAlive || oversized || process.exitValue() != 0) throw StageFailure(StageError.STAGING_DEVICE_UNSAFE)
        return synchronized(output) { output.toString("UTF-8") }
    }

    private fun freshEngineIdle(): Boolean {
        if (IUpdateEngine::class.java.classLoader != Build::class.java.classLoader ||
            IUpdateEngineCallback::class.java.classLoader != Build::class.java.classLoader) return false
        val latch = CountDownLatch(1); var status = -1; var malformed = false
        val callback = object : IUpdateEngineCallback.Stub() {
            @Synchronized override fun onStatusUpdate(code: Int, progress: Float) { if (!progress.isFinite() || progress !in 0f..1f) malformed = true; status = code; latch.countDown() }
            @Synchronized override fun onPayloadApplicationComplete(code: Int) { if (code != 0) malformed = true; latch.countDown() }
        }
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "android.os.UpdateEngineService") as? IBinder ?: return false
        if (binder.interfaceDescriptor != "android.os.IUpdateEngine") return false
        val engine = IUpdateEngine.Stub.asInterface(binder)
        var bound = false
        val observed: Boolean
        try { bound = engine.bind(callback); observed = bound && latch.await(3, TimeUnit.SECONDS) }
        finally { if (bound && !engine.unbind(callback)) throw StageFailure(StageError.STAGING_DEVICE_UNSAFE) }
        return synchronized(callback) { observed && !malformed && status == 0 }
    }
}
