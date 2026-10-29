package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PayloadManifestTest {
    private val hash = ByteArray(32) { it.toByte() }
    private fun number(value: Long): ByteArray = ByteArrayOutputStream().also { out ->
        var v = value
        do { val b = (v and 127).toInt(); v = v ushr 7; out.write(b or if (v > 0) 128 else 0) } while (v > 0)
    }.toByteArray()
    private fun value(field: Int, n: Long) = number((field * 8).toLong()) + number(n)
    private fun blob(field: Int, bytes: ByteArray) = number((field * 8 + 2).toLong()) + number(bytes.size.toLong()) + bytes
    private fun digest(bytes: Long = 8_388_608, sha: ByteArray = hash) = value(1, bytes) + blob(2, sha)
    private fun partition(name: String = "init_boot", info: ByteArray = digest(), old: Boolean = true) =
        blob(13, blob(1, name.toByteArray()) + (if (old) blob(6, digest()) else byteArrayOf()) + blob(7, info))
    private fun rejected(bytes: ByteArray) { assertThrows(IllegalArgumentException::class.java) { PayloadManifest.decode(bytes) } }

    @Test fun projectsSourceAndTargetDigestsWithoutParsingOperationBlobs() {
        val manifest = value(3, 4096) + partition() + partition("vendor", old = false) + blob(100, byteArrayOf(1, 2, 3))
        val parsed = PayloadManifest.decode(manifest)
        assertEquals(4096, parsed.blockSize)
        assertEquals(listOf("init_boot", "vendor"), parsed.partitionNames)
        assertEquals(8_388_608L, parsed.initBootTarget.bytes)
        assertEquals(hash.joinToString("") { "%02x".format(it) }, parsed.initBootTarget.sha256)
        assertEquals(parsed.initBootSource, parsed.initBootTarget)
    }

    @Test fun fullPayloadMayOmitSourceInfo() { assertNull(PayloadManifest.decode(partition(old = false)).initBootSource) }
    @Test fun missingInitBootCannotCreateStockProof() { rejected(partition("boot")) }
    @Test fun duplicatePartitionOrCriticalFieldsFailClosed() {
        rejected(partition() + partition())
        rejected(value(3, 4096) + value(3, 4096) + partition())
        rejected(partition(info = digest() + value(1, 8192)))
        rejected(partition(info = digest() + blob(2, hash)))
    }
    @Test fun malformedSizesHashesWireTypesAndLengthsAreRejected() {
        rejected(partition(info = digest(bytes = 0)))
        rejected(partition(info = digest(bytes = 129L * 1024 * 1024)))
        rejected(partition(info = digest(sha = hash.copyOf(31))))
        rejected(partition().dropLast(1).toByteArray())
        rejected(byteArrayOf(0))
        rejected(byteArrayOf(0x0b)) // protobuf groups are deliberately unsupported
        rejected(value(3, 512) + partition())
        rejected(ByteArray(11) { 0x80.toByte() })
    }
    @Test fun rejectsSlotSuffixedAndPathPartitionNames() {
        rejected(partition("../init_boot"))
        rejected(partition("init_boot_b")) // valid name, but not the unsuffixed signed target
    }
    @Test fun readsOnlyBoundedManifestFromPayloadZip() {
        val file = File.createTempFile("manifest", ".zip")
        val manifest = partition()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("payload.bin"))
                zip.write(ByteBuffer.allocate(24).put("CrAU".toByteArray()).putLong(2).putLong(manifest.size.toLong()).putInt(0).array())
                zip.write(manifest)
                zip.write(ByteArray(1024))
                zip.closeEntry()
            }
            assertEquals(manifest.size, PayloadManifest.inspect(file).manifestBytes)
        } finally { file.delete() }
    }
    @Test fun realHistoricalSignedPackageContainsKnownStock17Target() {
        val path = System.getenv("MOTO_OTA_PATH")
        assumeTrue("Optional private historical OTA fixture", path != null && File(path).isFile)
        val manifest = PayloadManifest.inspect(File(path!!))
        assertEquals(8_388_608L, manifest.initBootTarget.bytes)
        assertEquals("9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea", manifest.initBootTarget.sha256)
        assertEquals("2813c93926eae599bd3bd14c4e9e39b10bc3b2dd30134bff8fe594a3f8a67d29", manifest.initBootSource?.sha256)
    }
}
