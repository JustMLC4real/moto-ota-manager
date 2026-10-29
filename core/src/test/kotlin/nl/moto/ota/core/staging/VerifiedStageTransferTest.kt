package nl.moto.ota.core.staging

import nl.moto.ota.core.PackageTransfer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class VerifiedStageTransferTest {
    private val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun workspace(block: (File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("moto-staging-").toFile()
        try {
            val source = File(directory, "pinned.zip").apply { writeBytes(bytes) }
            val temporary = File(directory, "outside-watch").apply { check(mkdir()) }
            val target = File(File(directory, "motorola").apply { check(mkdir()) }, MotorolaStagePolicy.destinationName(hash))
            block(source, temporary, target)
        } finally { directory.deleteRecursively() }
    }
    private fun publish(source: File, target: File, expected: Long) {
        assertEquals(expected, source.length())
        try { Files.createLink(target.toPath(), source.toPath()) }
        catch (_: FileAlreadyExistsException) { throw StageFailure(StageError.STAGING_DESTINATION_EXISTS) }
    }
    private fun rejected(code: StageError, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: StageFailure) { assertEquals(code, error.code) }
    }

    @Test fun fsyncedCopyIsPublishedOnlyAfterFullHashAndReadback() = workspace { source, temporary, target ->
        var publications = 0
        val reused = VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash, publish = { temp, destination, expected ->
            assertFalse(target.exists()); assertEquals(hash, VerifiedStageTransfer.digest(temp, expected)); publications++
            publish(temp, destination, expected)
        })
        assertFalse(reused); assertEquals(1, publications); assertArrayEquals(bytes, target.readBytes()); assertTrue(temporary.list()!!.isEmpty())
    }

    @Test fun existingDifferentFileIsNeverReplaced() = workspace { source, temporary, target ->
        target.writeText("existing unrelated bytes")
        rejected(StageError.STAGING_DESTINATION_EXISTS) { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            publish = { _, _, _ -> fail("Existing target must not publish") }) }
        assertEquals("existing unrelated bytes", target.readText())
    }

    @Test fun existingExactFileCanReturnReadbackReceiptWithoutAnotherWrite() = workspace { source, temporary, target ->
        target.writeBytes(bytes)
        assertTrue(VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            publish = { _, _, _ -> fail("No second publication") }))
    }

    @Test fun concurrentDestinationAppearingAtPublicationIsPreserved() = workspace { source, temporary, target ->
        rejected(StageError.STAGING_DESTINATION_EXISTS) { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            publish = { temp, destination, expected -> target.writeText("other writer"); publish(temp, destination, expected) }) }
        assertEquals("other writer", target.readText()); assertTrue(temporary.list()!!.isEmpty())
    }

    @Test fun corruptedSourceOrInsufficientSpaceCannotPublish() = workspace { source, temporary, target ->
        rejected(StageError.STAGING_SPACE_LOW) { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            availableBytes = { PackageTransfer.MIN_RESERVE_BYTES }, publish = ::publish) }
        source.writeBytes(ByteArray(bytes.size))
        rejected(StageError.STAGING_HASH_MISMATCH) { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash, publish = ::publish) }
        assertFalse(target.exists()); assertTrue(temporary.list()!!.isEmpty())
    }

    @Test fun interruptionAndUnsupportedAtomicPrimitiveLeaveNoPublishedZip() = workspace { source, temporary, target ->
        var checks = 0
        try { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            check = { if (++checks > 2) throw CancellationException() }, publish = ::publish); fail("Cancellation ignored") }
        catch (_: CancellationException) { }
        rejected(StageError.STAGING_ATOMIC_PUBLISH_UNSUPPORTED) { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            publish = { _, _, _ -> throw StageFailure(StageError.STAGING_ATOMIC_PUBLISH_UNSUPPORTED) }) }
        assertFalse(target.exists()); assertTrue(temporary.list()!!.isEmpty())
    }

    @Test fun temporaryCopyCannotBePlacedInWatchedDirectory() = workspace { source, _, target ->
        rejected(StageError.STAGING_PATH_REJECTED) { VerifiedStageTransfer.copy(source, target.parentFile, target, bytes.size.toLong(), hash, publish = ::publish) }
        assertFalse(target.exists())
    }
    @Test fun uncertainPostPublishDurabilityNeverBecomesAReceiptOrAutomaticDeletion() = workspace { source, temporary, target ->
        rejected(StageError.STAGING_DURABILITY_UNPROVEN) { VerifiedStageTransfer.copy(source, temporary, target, bytes.size.toLong(), hash,
            publish = { temp, destination, expected -> publish(temp, destination, expected); throw StageFailure(StageError.STAGING_DURABILITY_UNPROVEN) }) }
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(temporary.list()!!.isEmpty())
    }
}
