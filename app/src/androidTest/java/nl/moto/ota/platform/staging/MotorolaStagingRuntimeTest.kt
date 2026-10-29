package nl.moto.ota.platform.staging

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.FirmwareReport
import nl.moto.ota.core.staging.*
import nl.moto.ota.core.update.UpdateRun
import nl.moto.ota.rootprobe.staging.MotorolaStageProbe
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** App-cache native syscall tests only. No root, Motorola folder, partition or network use. */
@RunWith(AndroidJUnit4::class)
class MotorolaStagingRuntimeTest {
    private lateinit var context: Context
    @Before fun setUp() {
        assumeTrue("Staging tests require a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    }
    private fun rejected(code: StageError, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: StageFailure) { assertEquals(code, error.code) }
    }

    @Test fun realAppUidCannotRunDedicatedRootProbe() {
        assertTrue(android.os.Process.myUid() >= 10_000)
        rejected(StageError.STAGING_RUNTIME_UNSUPPORTED) { MotorolaStageProbe.requireRuntime(android.os.Process.myUid(), 37) }
        rejected(StageError.STAGING_RUNTIME_UNSUPPORTED) { MotorolaStageProbe.requireRuntime(0, 35) }
    }

    @Test fun absentConfirmationCannotCreateRequestOrStartRoot() = runBlocking {
        try { MotorolaPackageStager(context).stage(File(context.cacheDir, "absent.zip"), FirmwareReport(), UpdateRun(), false, false); fail() }
        catch (error: StageFailure) { assertEquals(StageError.STAGING_CONFIRMATION_REQUIRED, error.code) }
    }

    @Test fun emulatorSdkCannotStageEvenWhenCheckboxesAreConfirmed() = runBlocking {
        assumeTrue("Source profile requires real SDK37", Build.VERSION.SDK_INT != 37)
        try { MotorolaPackageStager(context).stage(File(context.cacheDir, "absent.zip"), FirmwareReport(), UpdateRun(), true, true); fail() }
        catch (error: StageFailure) { assertEquals(StageError.STAGING_RUNTIME_UNSUPPORTED, error.code) }
    }

    @Test fun productionNativeEntryRejectsNonRootBeforeOpeningAnyDirectory() {
        AtomicStagePublisher.loadForSandboxTests()
        val method = AtomicStagePublisher::class.java.getDeclaredMethod("publishNative", String::class.java, String::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }
        assertEquals(4, method.invoke(null, ".moto-stage-${UUID.randomUUID()}.part", "moto-ota-${"a".repeat(64)}.zip", 3L))
    }

    @Test fun realRenameat2SandboxPublishesAtomicallyAndRefusesExistingDestination() {
        AtomicStagePublisher.loadForSandboxTests()
        val method = AtomicStagePublisher::class.java.getDeclaredMethod("sandboxPublishNative", String::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }
        val directory = File(context.cacheDir, "staging-native-test-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        Os.chmod(directory.path, 0x1c0) // 0700: only this emulator app can create entries.
        val source = File(directory, "source.bin"); val target = File(directory, "target.bin")
        try {
            source.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(0, method.invoke(null, directory.path, 3L))
            assertFalse(source.exists()); assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
            source.writeBytes(byteArrayOf(9, 9, 9))
            assertEquals(1, method.invoke(null, directory.path, 3L))
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
            assertArrayEquals(byteArrayOf(9, 9, 9), source.readBytes())
            assertEquals(4, method.invoke(null, context.cacheDir.canonicalPath, 3L))
        } finally { source.delete(); target.delete(); directory.delete() }
    }
}
