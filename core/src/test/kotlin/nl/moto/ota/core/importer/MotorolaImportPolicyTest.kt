package nl.moto.ota.core.importer

import nl.moto.ota.core.FirmwareValidator
import nl.moto.ota.core.PackageTransfer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Ordinary temporary files only; no Android root adapter or physical device. */
class MotorolaImportPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sourceDirectory() = temporary.newFolder("oem-source").canonicalFile
    private fun destination() = File(temporary.newFolder("app-private").canonicalFile, "package.zip")
    private fun data(size: Int = 160_000, seed: Int = 1) = ByteArray(size) { ((it + seed) % 251).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun reject(code: MotorolaImportError? = null, block: () -> Unit) {
        try { block(); fail("Expected safe import rejection") }
        catch (failure: MotorolaImportFailure) { if (code != null) assertEquals(code, failure.code) }
    }

    @Test fun acceptsOnlyBoundedAsciiZipBasenames() {
        listOf("a.zip", "UPPER.ZIP", "ota-17_1.2.zip", "a".repeat(156) + ".zip").forEach { assertTrue(it, MotorolaImportPolicy.validId(it)) }
        listOf("", ".zip", "a".repeat(157) + ".zip", "../ota.zip", "a..b.zip", "/ota.zip", "a\\ota.zip",
            "a zip.zip", "a.zip?token=secret", "a.zip\n", "é.zip", "a.zip.sh", "a;id.zip").forEach {
            assertFalse(MotorolaImportPolicy.validId(it))
        }
    }

    @Test fun discoveryReturnsAtMostTwelveRegularBoundedCandidates() {
        val source = sourceDirectory()
        repeat(16) { File(source, "ota-%02d.zip".format(it)).writeBytes(data(10, it)) }
        File(source, "empty.zip").writeBytes(byteArrayOf())
        File(source, "not-a-package.txt").writeText("synthetic")
        File(source, "directory.zip").mkdir()
        val found = MotorolaImportPolicy.discover(source)
        assertEquals(12, found.size)
        assertEquals("ota-00.zip", found.first().id)
        assertTrue(found.all { it.bytes == 10L })
        assertFalse(found.first().toString().contains(found.first().id))
    }

    @Test fun discoveryRejectsDirectoryFloodInsteadOfUnboundedListing() {
        val source = sourceDirectory()
        repeat(MotorolaImportPolicy.MAX_DIRECTORY_ENTRIES + 1) { File(source, "ignored-$it.txt").writeText("x") }
        reject(MotorolaImportError.ENTRY_LIMIT) { MotorolaImportPolicy.discover(source) }
    }

    @Test fun fullHashedCopyCommitsExactBytesWithoutTrustingFirmware() {
        val source = sourceDirectory(); val expected = data()
        File(source, "ota.zip").writeBytes(expected)
        val target = destination()
        val receipt = MotorolaImportPolicy.copy(source, "ota.zip", target, { Long.MAX_VALUE })
        assertEquals(expected.size.toLong(), receipt.bytes)
        assertEquals(hash(expected), receipt.sha256)
        assertArrayEquals(expected, target.readBytes())
        assertEquals(setOf(target.name), target.parentFile.listFiles().orEmpty().map { it.name }.toSet())
        assertFalse(receipt.toString().contains(receipt.sha256))
        assertFalse(receipt.toString().contains("ota.zip"))
    }

    @Test fun lowStoragePreservesPreviousCompleteDestination() {
        val source = sourceDirectory(); File(source, "ota.zip").writeBytes(data())
        val target = destination().apply { writeText("previous") }
        reject(MotorolaImportError.STORAGE_LOW) { MotorolaImportPolicy.copy(source, "ota.zip", target, { PackageTransfer.MIN_RESERVE_BYTES }) }
        assertEquals("previous", target.readText())
        assertEquals(1, target.parentFile.listFiles()!!.size)
    }

    @Test fun storageFailureDuringCopyNeverPublishesPartialBytes() {
        val source = sourceDirectory(); File(source, "ota.zip").writeBytes(data())
        val target = destination().apply { writeText("previous") }
        var checks = 0
        reject(MotorolaImportError.STORAGE_LOW) {
            MotorolaImportPolicy.copy(source, "ota.zip", target) { if (++checks <= 2) Long.MAX_VALUE else 0 }
        }
        assertEquals("previous", target.readText())
        assertEquals(1, target.parentFile.listFiles()!!.size)
    }

    @Test fun atomicSourceReplacementDuringCopyRejectsReceiptAndPreservesSelection() {
        val source = sourceDirectory(); val image = File(source, "ota.zip").apply { writeBytes(data()) }
        val replacement = File(source, "replacement.zip").apply { writeBytes(data(seed = 2)) }
        val target = destination().apply { writeText("previous") }
        var checks = 0
        reject(MotorolaImportError.SOURCE_CHANGED) {
            MotorolaImportPolicy.copy(source, image.name, target) {
                // Three 64-KiB-or-smaller reads follow the preflight check. At
                // check five the stream is closed, so Windows also permits the
                // same atomic replacement Android permits with an open fd.
                if (++checks == 5) Files.move(replacement.toPath(), image.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Long.MAX_VALUE
            }
        }
        assertEquals("previous", target.readText())
        assertEquals(1, target.parentFile.listFiles()!!.size)
    }

    @Test fun sameSizeMutationWithRestoredMtimeStillRequiresSecondFullSourceHash() {
        val source = sourceDirectory(); val image = File(source, "ota.zip").apply { writeBytes(data(4096)) }
        val originalTime = Files.getLastModifiedTime(image.toPath())
        val target = destination().apply { writeText("previous") }
        var checks = 0
        reject(MotorolaImportError.SOURCE_CHANGED) {
            MotorolaImportPolicy.copy(source, image.name, target) {
                if (++checks == 2) {
                    RandomAccessFile(image, "rw").use { it.seek(0); it.writeByte(255) }
                    Files.setLastModifiedTime(image.toPath(), originalTime)
                }
                Long.MAX_VALUE
            }
        }
        assertEquals("previous", target.readText())
    }

    @Test fun changedDestinationDuringCopyIsNotOverwritten() {
        val source = sourceDirectory(); File(source, "ota.zip").writeBytes(data())
        val target = destination().apply { writeText("previous") }
        var checks = 0
        reject(MotorolaImportError.DESTINATION_CHANGED) {
            MotorolaImportPolicy.copy(source, "ota.zip", target) {
                if (++checks == 2) target.writeText("newer-manual-selection")
                Long.MAX_VALUE
            }
        }
        assertEquals("newer-manual-selection", target.readText())
    }

    @Test fun pathsAndMissingOrEmptySourceNeverBecomeImportCommands() {
        val source = sourceDirectory(); val target = destination()
        reject(MotorolaImportError.INVALID_ID) { MotorolaImportPolicy.copy(source, "../outside.zip", target) }
        reject(MotorolaImportError.SOURCE_INVALID) { MotorolaImportPolicy.copy(source, "missing.zip", target) }
        File(source, "empty.zip").writeBytes(byteArrayOf())
        reject(MotorolaImportError.SIZE_LIMIT) { MotorolaImportPolicy.copy(source, "empty.zip", target) }
        assertFalse(target.exists())
    }

    @Test fun destinationInsideOemDirectoryIsAlwaysRejected() {
        val source = sourceDirectory(); File(source, "ota.zip").writeBytes(data(100))
        reject(MotorolaImportError.DESTINATION_INVALID) { MotorolaImportPolicy.copy(source, "ota.zip", File(source, "other.zip")) }
        assertFalse(File(source, "other.zip").exists())
    }

    @Test fun oversizedSparseSourceIsRejectedBeforeAnyCopy() {
        val source = sourceDirectory(); val image = File(source, "oversized.zip")
        Files.newByteChannel(image.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use {
            it.position(FirmwareValidator.MAX_PACKAGE_BYTES)
            it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        val target = destination()
        reject(MotorolaImportError.SIZE_LIMIT) { MotorolaImportPolicy.copy(source, image.name, target, { Long.MAX_VALUE }) }
        assertFalse(target.exists())
    }

    @Test fun sourceSymlinksAreNotDiscoveredOrRead() {
        val source = sourceDirectory(); val other = File(temporary.root, "outside.zip").apply { writeText("private fixture") }
        val link = File(source, "link.zip")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), other.toPath()); true }.getOrDefault(false))
        assertTrue(MotorolaImportPolicy.discover(source).isEmpty())
        reject(MotorolaImportError.SOURCE_INVALID) { MotorolaImportPolicy.copy(source, link.name, destination()) }
        assertEquals("private fixture", other.readText())
    }

    @Test fun destinationSymlinkIsRejectedWithoutTouchingItsTarget() {
        val source = sourceDirectory(); File(source, "ota.zip").writeBytes(data(100))
        val other = File(temporary.root, "private.txt").apply { writeText("preserve") }
        val target = destination()
        assumeTrue(runCatching { Files.createSymbolicLink(target.toPath(), other.toPath()); true }.getOrDefault(false))
        reject(MotorolaImportError.DESTINATION_INVALID) { MotorolaImportPolicy.copy(source, "ota.zip", target) }
        assertEquals("preserve", other.readText())
    }
}
