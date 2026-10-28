package nl.moto.ota

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.WorkflowState
import nl.moto.ota.platform.FirmwareFiles
import nl.moto.ota.platform.WorkflowStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises real Android file/cert APIs on the disposable emulator, without root. */
class PlatformSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unsignedPackageCannotPassActualDeviceCertificateVerification() = runBlocking {
        val file = File(context.cacheDir, "unsigned-platform-test.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/com/android/metadata"))
                zip.write("ota-type=AB\n".toByteArray())
                zip.closeEntry()
            }
            assertFalse(FirmwareFiles(context).verify(file) {})
        } finally { file.delete() }
    }

    @Test fun privateFileImportCommitsExactBytesWithoutShell() = runBlocking {
        val source = File(context.cacheDir, "platform-import-test.bin")
        val data = ByteArray(16384) { (it % 251).toByte() }
        source.writeBytes(data)
        try {
            val imported = FirmwareFiles(context).import(Uri.fromFile(source), backup = true)
            assertArrayEquals(data, imported.readBytes())
            imported.delete()
        } finally { source.delete() }
    }

    @Test fun atomicAndroidJournalRestoresExpectationsWithoutClaimingSuccess() {
        val store = WorkflowStore(context)
        val original = store.load()
        try {
            val state = WorkflowState(message = "Testjournaal; geen toestelactie uitgevoerd.")
            store.save(state)
            assertEquals(state, store.load())
        } finally { store.save(original) }
    }
}
