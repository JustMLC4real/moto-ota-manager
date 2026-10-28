package nl.moto.ota.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PackageTransferTest {
    private fun fixture(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("ota-transfer-test").toFile()
        try { block(directory, File(directory, "package.zip")) } finally {
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }
    private val enoughSpace: () -> Long = { 1024L * 1024 * 1024 }
    private fun expectFailure(action: () -> Unit) { try { action(); fail("Expected a failed transfer") } catch (_: IOException) { } catch (_: IllegalArgumentException) { } catch (_: CancellationException) { } }
    private fun assertNoParts(directory: File) = assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })

    @Test fun completedExactLengthTransferCommitsOnlyPackageBytes() = fixture { dir, destination ->
        val payload = ByteArray(150_000) { (it % 251).toByte() }
        PackageTransfer.copyAtomically(payload.inputStream(), destination, 200_000, payload.size.toLong(), enoughSpace)
        assertArrayEquals(payload, destination.readBytes())
        assertNoParts(dir)
    }

    @Test fun networkFailureMidStreamPreservesPreviousPackageAndRemovesPartial() = fixture { dir, destination ->
        destination.writeText("previously verified package")
        val failing = object : InputStream() {
            private var supplied = false
            override fun read(): Int = throw IOException("mock network failure")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (supplied) throw IOException("mock connection reset after first packet")
                supplied = true
                buffer[offset] = 42
                return 1
            }
        }
        expectFailure { PackageTransfer.copyAtomically(failing, destination, 1000, 20, enoughSpace) }
        assertEquals("previously verified package", destination.readText())
        assertNoParts(dir)
    }

    @Test fun truncatedDownloadNeverBecomesCompletedFile() = fixture { dir, destination ->
        expectFailure { PackageTransfer.copyAtomically(byteArrayOf(1, 2).inputStream(), destination, 1000, 10, enoughSpace) }
        assertFalse(destination.exists())
        assertNoParts(dir)
    }

    @Test fun oversizeOrMisdeclaredDownloadIsRejected() = fixture { dir, destination ->
        expectFailure { PackageTransfer.copyAtomically(ByteArray(100).inputStream(), destination, 50, null, enoughSpace) }
        expectFailure { PackageTransfer.copyAtomically(ByteArray(100).inputStream(), destination, 200, 50, enoughSpace) }
        assertFalse(destination.exists())
        assertNoParts(dir)
    }

    @Test fun storageExhaustionPreservesExistingFile() = fixture { dir, destination ->
        destination.writeText("old")
        expectFailure { PackageTransfer.copyAtomically(byteArrayOf(1).inputStream(), destination, 1000, null, { 0L }) }
        assertEquals("old", destination.readText())
        assertNoParts(dir)
    }

    @Test fun coroutineCancellationLeavesNoCompletedDownload() = fixture { dir, destination ->
        expectFailure { PackageTransfer.copyAtomically(byteArrayOf(1).inputStream(), destination, 1000, null, enoughSpace) { throw CancellationException("mock cancellation") } }
        assertFalse(destination.exists())
        assertNoParts(dir)
    }

    @Test fun successfulReplacementIsAtomicAndLeavesNoStalePartial() = fixture { dir, destination ->
        destination.writeText("old")
        PackageTransfer.copyAtomically("new".byteInputStream(), destination, 1000, 3, enoughSpace)
        assertEquals("new", destination.readText())
        assertNoParts(dir)
    }

    @Test fun emptyInputNeverReplacesExistingPackage() = fixture { dir, destination ->
        destination.writeText("old")
        expectFailure { PackageTransfer.copyAtomically(byteArrayOf().inputStream(), destination, 1000, null, enoughSpace) }
        assertEquals("old", destination.readText())
        assertNoParts(dir)
    }
}
