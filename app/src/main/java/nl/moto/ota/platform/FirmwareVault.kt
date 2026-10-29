package nl.moto.ota.platform

import android.content.Context
import android.util.AtomicFile
import nl.moto.ota.core.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Properties
import java.util.UUID

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

    fun recordVerified(report: FirmwareReport, manifest: PayloadManifestInfo, anchors: Set<String>, nowMillis: Long = System.currentTimeMillis()) {
        val new = requireNotNull(StockEvidencePolicy.create(report, manifest, anchors, nowMillis)) { "SIGNED_STOCK_EVIDENCE_INVALID" }
        synchronized(LOCK) {
            val values = load().filterNot { it.packageSha256 == new.packageSha256 } + new
            save(values.takeLast(16))
        }
    }

    /** Requires a fresh actual target boot; downloaded target claims alone never bind an identity. */
    fun referenceFor(device: DeviceState, anchors: Set<String>): StockReference? = synchronized(LOCK) {
        runCatching {
            var changed = false
            val values = load().map { original ->
                val bound = StockEvidencePolicy.bind(original, device, System.currentTimeMillis())
                if (bound != null && original.measuredSystemFingerprint.isBlank()) { changed = true; bound } else original
            }
            if (changed) save(values)
            values.asReversed().firstNotNullOfOrNull { StockEvidencePolicy.reference(it, device, anchors) }
        }.getOrNull()
    }

    fun proofCount(): Int = synchronized(LOCK) { runCatching { load().size }.getOrDefault(0) }

    private fun load(): List<SignedStockEvidence> {
        if (!proofs.baseFile.exists() && !File(proofs.baseFile.path + ".bak").exists()) return emptyList()
        val text = readBounded(proofs, 128 * 1024)
        val p = Properties().apply { load(StringReader(text)) }
        require(p.getProperty("schema") == "1")
        val count = p.getProperty("count").toInt()
        require(count in 0..16)
        val fields = setOf("package", "build", "android", "incremental", "targetFp", "image", "bytes", "anchors", "verifiedAt", "measuredFp")
        require(p.stringPropertyNames() == setOf("schema", "count") + (0 until count).flatMap { i -> fields.map { "$i.$it" } })
        return (0 until count).map { i ->
            fun value(key: String) = requireNotNull(p.getProperty("$i.$key"))
            SignedStockEvidence(value("package"), value("build"), value("android"), value("incremental"), value("targetFp"),
                value("image"), value("bytes").toLong(), value("anchors").split(',').toSet(), value("verifiedAt").toLong(), value("measuredFp"))
                .also { require(StockEvidencePolicy.acceptsStored(it)) { "STOCK_EVIDENCE_CORRUPT" } }
        }
    }

    private fun save(values: List<SignedStockEvidence>) {
        require(values.size <= 16 && values.all(StockEvidencePolicy::acceptsStored))
        val p = Properties().apply { setProperty("schema", "1"); setProperty("count", values.size.toString()) }
        values.forEachIndexed { i, e ->
            mapOf("package" to e.packageSha256, "build" to e.targetBuild, "android" to e.targetAndroid, "incremental" to e.targetIncremental,
                "targetFp" to e.payloadTargetFingerprint, "image" to e.imageSha256, "bytes" to e.imageBytes.toString(),
                "anchors" to e.trustedSpki.sorted().joinToString(","), "verifiedAt" to e.verifiedAtMillis.toString(), "measuredFp" to e.measuredSystemFingerprint)
                .forEach { (key, value) -> p.setProperty("$i.$key", value) }
        }
        val writer = StringWriter().also { p.store(it, "Locally authenticated OTA facts; no download URL") }
        val encoded = writer.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "STOCK_EVIDENCE_TOO_LARGE" }
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
