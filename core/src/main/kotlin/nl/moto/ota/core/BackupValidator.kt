package nl.moto.ota.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/** A reference must originate from independently authenticated firmware, not an image filename. */
data class StockReference(
    val sha256: String,
    val bytes: Long,
    val build: String,
    val fingerprint: String,
    val device: String,
    val model: String,
    val channel: String,
    val partition: String = "init_boot",
    val authenticityVerified: Boolean = false,
    val evidence: String = ""
)

data class BackupValidation(
    val structurallyValid: Boolean = false,
    val provenanceVerified: Boolean = false,
    val sha256: String = "",
    val sha1: String = "",
    val bytes: Long = 0,
    val partitionType: String = "unknown",
    val issues: List<CheckResult> = emptyList()
) {
    val usableAsStock: Boolean get() = structurallyValid && provenanceVerified && issues.isEmpty()
}

object BackupValidator {
    private const val MAX_IMAGE = 128L * 1024 * 1024
    private const val MAX_COMPRESSED = 64L * 1024 * 1024
    private const val OLD_ANDROID_16_SHA = "2813c93926eae599bd3bd14c4e9e39b10bc3b2dd30134bff8fe594a3f8a67d29"

    /** Reads image bytes only: shell diagnostics/stderr must never be mixed into this input. */
    fun inspect(
        input: InputStream,
        compressed: Boolean,
        expectedPartitionBytes: Long,
        device: DeviceState,
        reference: StockReference? = null
    ): BackupValidation {
        val issues = mutableListOf<CheckResult>()
        if (expectedPartitionBytes !in 4096..MAX_IMAGE) {
            return BackupValidation(issues = listOf(CheckResult("PARTITION_SIZE_UNKNOWN", "De actuele init_boot-partitiegrootte is onbekend of ongeldig.")))
        }
        return try {
            val packed = if (compressed) readBounded(input, MAX_COMPRESSED) else null
            val raw = if (packed != null) {
                val image = GZIPInputStream(ByteArrayInputStream(packed)).use { readBounded(it, expectedPartitionBytes + 1) }
                // Require one complete gzip stream. GZIPInputStream alone may silently accept trailing garbage.
                require(packed.size >= 18 && packed[0] == 0x1f.toByte() && packed[1] == 0x8b.toByte()) { "Ongeldige gzip-header" }
                val crc = CRC32().apply { update(image) }.value
                require(le32(packed, packed.size - 8) == crc && le32(packed, packed.size - 4) == image.size.toLong()) { "Gzip-footer, CRC of lengte is ongeldig; mogelijke extra uitvoer." }
                image
            } else readBounded(input, expectedPartitionBytes + 1)
            val sha256 = digest("SHA-256", raw)
            val sha1 = digest("SHA-1", raw)
            if (raw.size.toLong() != expectedPartitionBytes) issues += CheckResult("IMAGE_SIZE", "Image is ${raw.size} bytes; partitie is $expectedPartitionBytes bytes. Extra shelluitvoer of een afgekorte image wordt geweigerd.")
            val initBoot = raw.size >= 4096 && String(raw, 0, 8, Charsets.US_ASCII) == "ANDROID!" &&
                le32(raw, 8) == 0L && le32(raw, 12) in 1..(raw.size - 4096).toLong() &&
                le32(raw, 20) == 1584L && le32(raw, 40) == 4L
            if (!initBoot) issues += CheckResult("IMAGE_HEADER", "Geen ondersteunde Android v4 init_boot-image met lege kernel en geldige ramdisk.")
            val structureOk = issues.isEmpty()
            val provenance = reference != null && reference.authenticityVerified && reference.evidence.isNotBlank() &&
                reference.sha256.equals(sha256, true) && reference.bytes == raw.size.toLong() &&
                reference.build == device.build && reference.fingerprint == device.fingerprint &&
                reference.device == device.device && reference.model == device.model && reference.channel == device.channel &&
                reference.partition == "init_boot" && device.build.isNotBlank() && device.fingerprint.isNotBlank() &&
                !(sha256 == OLD_ANDROID_16_SHA && device.build != "W3WMS36.40-75-5-2")
            if (!provenance) issues += CheckResult("STOCK_PROVENANCE_UNKNOWN", "Header en hash bewijzen geen stock-herkomst of koppeling aan de huidige build. Een onafhankelijk geverifieerde actuele firmware-referentie ontbreekt of wijkt af.")
            BackupValidation(structureOk, provenance, sha256, sha1, raw.size.toLong(), if (initBoot) "init_boot" else "unknown", issues)
        } catch (_: ImageSizeLimit) {
            BackupValidation(issues = listOf(CheckResult("IMAGE_SIZE", "Image of gzip overschrijdt de gemeten partitiegrootte of veilige leeslimiet; mogelijke extra shelluitvoer.")))
        } catch (_: Exception) {
            BackupValidation(issues = listOf(CheckResult("IMAGE_CORRUPT", "Image/gzip is beschadigd, onleesbaar of overschrijdt de veilige limiet.")))
        }
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw ImageSizeLimit()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { result, index -> result or ((bytes[offset + index].toLong() and 255L) shl (8 * index)) }

    private class ImageSizeLimit : IllegalArgumentException()
}
