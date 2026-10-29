package nl.moto.ota.platform

import android.content.Context
import android.util.AtomicFile
import android.os.RecoverySystem
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Properties
import java.util.UUID
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** App-private authenticated facts, not a user-importable trust manifest. */
class FirmwareVault(private val context: Context) {
    private val directory = File(context.filesDir, "verified-packages").apply { mkdirs() }
    private val proofs = AtomicFile(File(context.filesDir, "signed-stock-evidence.properties"))
    private val selection = AtomicFile(File(context.filesDir, "package-selection.properties"))

    /** Pin a committed selected ZIP inode so a later download/import cannot replace the file being verified. */
    fun pinSelected(): File = synchronized(SELECTION_LOCK) {
        requirePrivateDirectory()
        val source = File(context.filesDir, "selected-ota.zip")
        require(source.canonicalFile.parentFile == context.filesDir.canonicalFile && Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) &&
            source.length() in 1..FirmwareValidator.MAX_PACKAGE_BYTES) { "PACKAGE_SOURCE_INVALID" }
        val pinned = File(directory, UUID.randomUUID().toString() + ".zip")
        var created = false
        try {
            try { Files.createLink(pinned.toPath(), source.toPath()); created = true }
            catch (_: Exception) {
                require(!Files.exists(pinned.toPath(), NOFOLLOW_LINKS)) { "PACKAGE_PIN_EXISTS" }
                source.inputStream().use { PackageTransfer.copyAtomically(it, pinned, FirmwareValidator.MAX_PACKAGE_BYTES, source.length()) }
                created = true
            }
            require(Files.isRegularFile(pinned.toPath(), NOFOLLOW_LINKS) && pinned.length() in 1..FirmwareValidator.MAX_PACKAGE_BYTES) { "PACKAGE_PIN_INVALID" }
            // Import/download only publish by atomic replacement. Remove write permission from
            // this inode before exposing it to a verifier; replacing selected-ota.zip remains safe.
            Os.chmod(pinned.absolutePath, 0x100)
            atomicWrite(selection, pinned.name)
            // Selection is already committed. Cleanup must never roll it back;
            // an obsolete in-flight path may fail safely rather than retain a
            // second multi-gigabyte copy indefinitely.
            runCatching { pruneOldPins(pinned.name) }
            pinned
        } catch (error: Exception) {
            if (created) pinned.delete()
            throw error
        }
    }

    fun selected(): File? = synchronized(SELECTION_LOCK) { runCatching {
        requirePrivateDirectory()
        val name = readBounded(selection, 128)
        require(name.matches(PIN_NAME))
        File(directory, name).takeIf { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) &&
            it.length() in 1..FirmwareValidator.MAX_PACKAGE_BYTES && it.canonicalFile.parentFile == directory.canonicalFile }
    }.getOrNull() }

    /**
     * A Boolean supplied by a caller is not signature evidence. This boundary verifies the exact
     * private pin itself, with fresh device anchors and stable bytes before recording any proof.
     */
    suspend fun recordVerified(pinnedFile: File, report: FirmwareReport, device: DeviceState,
                               nowMillis: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        requirePrivateDirectory()
        require(pinnedFile.name.matches(PIN_NAME) && pinnedFile.absoluteFile.parentFile == directory.absoluteFile &&
            pinnedFile.canonicalFile.parentFile == directory.canonicalFile && pinnedFile.canonicalFile.name == pinnedFile.name) { "SIGNED_PACKAGE_PIN_REQUIRED" }
        val before = identity(pinnedFile)
        val anchors = DeviceTrustAnchors.spkiSha256()
        require(anchors.isNotEmpty()) { "SIGNED_PACKAGE_ANCHORS_UNKNOWN" }
        coroutineContext.ensureActive()
        val inspected = FirmwareValidator.inspect(pinnedFile, device)
        require(inspected.structureValid && inspected.payloadHashValid && inspected.sha256 == report.sha256 &&
            inspected.packageBytes == before.size && before == identity(pinnedFile)) { "SIGNED_PACKAGE_CHANGED" }
        coroutineContext.ensureActive()
        RecoverySystem.verifyPackage(pinnedFile, null, null)
        coroutineContext.ensureActive()
        val manifest = PayloadManifest.inspect(pinnedFile)
        val digest = pinnedFile.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= before.size) { "SIGNED_PACKAGE_CHANGED" }
                md.update(buffer, 0, n)
            }
            require(total == before.size) { "SIGNED_PACKAGE_CHANGED" }
            md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        require(before == identity(pinnedFile) && anchors == DeviceTrustAnchors.spkiSha256() &&
            digest == inspected.sha256) { "SIGNED_PACKAGE_CHANGED" }
        coroutineContext.ensureActive()
        val new = StockEvidencePolicy.createAll(inspected.copy(signatureVerified = true), manifest, anchors, device, nowMillis)
        // This records authenticated partition bytes, never OTA installation approval. The restore
        // helper separately requires the full candidate hash and fresh Magisk runtime association.
        if (new.isEmpty()) return@withContext 0
        synchronized(LOCK) {
            val values = load().filterNot { it.packageSha256 == inspected.sha256 } + new
            save(values.takeLast(32))
        }
        new.size
    }

    /** A current-source proof or successful actual target boot is required; downloaded claims alone cannot bind. */
    fun referenceFor(device: DeviceState, anchors: Set<String>, partition: String? = null): StockReference? =
        referencesFor(device, anchors).firstOrNull { partition == null || it.partition == partition }

    fun referencesFor(device: DeviceState, anchors: Set<String>): List<StockReference> = synchronized(LOCK) {
        runCatching {
            var changed = false
            val values = load().map { original ->
                val bound = StockEvidencePolicy.bind(original, device, System.currentTimeMillis())
                if (bound != null && original != bound) { changed = true; bound } else original
            }
            if (changed) save(values)
            values.asReversed().mapNotNull { StockEvidencePolicy.reference(it, device, anchors) }
                .distinctBy { Triple(it.partition, it.sha256, it.bytes) }
        }.getOrDefault(emptyList())
    }

    fun proofCount(): Int = synchronized(LOCK) { runCatching { load().size }.getOrDefault(0) }

    private fun load(): List<SignedStockEvidence> {
        if (!proofs.baseFile.exists() && !File(proofs.baseFile.path + ".bak").exists()) return emptyList()
        val text = readBounded(proofs, 256 * 1024)
        val p = Properties().apply { load(StringReader(text)) }
        // Old records lacked explicit partition/scope/profile context and cannot be promoted using properties.
        if (p.getProperty("schema") == "1") return emptyList()
        require(p.getProperty("schema") == "2")
        val count = p.getProperty("count").toInt()
        require(count in 0..32)
        val fields = setOf("package", "build", "android", "incremental", "targetFp", "image", "bytes", "anchors", "verifiedAt", "measuredFp",
            "partition", "kind", "device", "model", "channel", "androidFps", "timestamp", "patch", "vendorFp", "scope")
        require(p.stringPropertyNames() == setOf("schema", "count") + (0 until count).flatMap { i -> fields.map { "$i.$it" } })
        return (0 until count).map { i ->
            fun value(key: String) = requireNotNull(p.getProperty("$i.$key"))
            SignedStockEvidence(value("package"), value("build"), value("android"), value("incremental"), value("targetFp"),
                value("image"), value("bytes").toLong(), value("anchors").split(',').toSet(), value("verifiedAt").toLong(), value("measuredFp"),
                value("partition"), StockEvidenceKind.valueOf(value("kind")), value("device"), value("model"), value("channel"),
                value("androidFps").split('|').toSet(), value("timestamp").toLong(), value("patch"), value("vendorFp"), value("scope"))
                .also { require(StockEvidencePolicy.acceptsStored(it)) { "STOCK_EVIDENCE_CORRUPT" } }
        }
    }

    private fun save(values: List<SignedStockEvidence>) {
        require(values.size <= 32 && values.all(StockEvidencePolicy::acceptsStored))
        val p = Properties().apply { setProperty("schema", "2"); setProperty("count", values.size.toString()) }
        values.forEachIndexed { i, e ->
            mapOf("package" to e.packageSha256, "build" to e.targetBuild, "android" to e.targetAndroid, "incremental" to e.targetIncremental,
                "targetFp" to e.payloadTargetFingerprint, "image" to e.imageSha256, "bytes" to e.imageBytes.toString(),
                "anchors" to e.trustedSpki.sorted().joinToString(","), "verifiedAt" to e.verifiedAtMillis.toString(), "measuredFp" to e.measuredSystemFingerprint,
                "partition" to e.partition, "kind" to e.kind.name, "device" to e.device, "model" to e.model, "channel" to e.channel,
                "androidFps" to e.androidFingerprints.sorted().joinToString("|"), "timestamp" to e.identityTimestamp.toString(),
                "patch" to e.identitySecurityPatch, "vendorFp" to e.measuredVendorFingerprint, "scope" to e.proofScope)
                .forEach { (key, value) -> p.setProperty("$i.$key", value) }
        }
        val writer = StringWriter().also { p.store(it, "Locally authenticated OTA facts; no download URL") }
        val encoded = writer.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "STOCK_EVIDENCE_TOO_LARGE" }
        atomicWrite(proofs, encoded)
    }

    private fun atomicWrite(file: AtomicFile, value: String) {
        val output = file.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun requirePrivateDirectory() {
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && directory.canonicalFile.parentFile == context.filesDir.canonicalFile) { "PACKAGE_DIRECTORY_INVALID" }
    }

    private data class PinIdentity(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)
    private fun identity(file: File): PinIdentity {
        val stat = Os.lstat(file.absolutePath)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == context.applicationInfo.uid &&
            stat.st_mode and 0x92 == 0 && stat.st_size in 1..FirmwareValidator.MAX_PACKAGE_BYTES) { "SIGNED_PACKAGE_PIN_INVALID" }
        return PinIdentity(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime, stat.st_ctime)
    }

    private fun pruneOldPins(keep: String) {
        requirePrivateDirectory()
        directory.listFiles()?.forEach { candidate ->
            if (candidate.name != keep && candidate.name.matches(PIN_NAME) && Files.isRegularFile(candidate.toPath(), NOFOLLOW_LINKS) &&
                candidate.canonicalFile.parentFile == directory.canonicalFile) runCatching { Files.deleteIfExists(candidate.toPath()) }
        }
    }

    private fun readBounded(file: AtomicFile, limit: Int): String = file.openRead().use { input ->
        val bytes = input.readNBytes(limit + 1)
        require(bytes.size <= limit) { "VAULT_RECORD_TOO_LARGE" }
        bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private val LOCK = Any()
        private val SELECTION_LOCK = Any()
        private val PIN_NAME = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}[.]zip")
    }
}
