package nl.moto.ota.platform.monitoring

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in app storage/scheduling tests for a disposable emulator only. They never
 * execute an enabled worker, request root, publish a notification or use the network.
 */
@RunWith(AndroidJUnit4::class)
class MonitorRuntimeTest {
    private lateinit var context: Context
    private lateinit var controller: MonitorController
    private lateinit var store: MonitorStore
    private val settingsFile get() = File(context.filesDir, "metadata-monitor.properties")

    @Before fun setUp() = runBlocking {
        assumeTrue("Monitoring runtime tests require a disposable Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        controller = MonitorController(context)
        store = MonitorStore.get(context)
        controller.disable()
        AtomicFile(settingsFile).delete()
        store.reload()
        Unit
    }

    @After fun tearDown() = runBlocking {
        if (::controller.isInitialized) {
            controller.disable()
            AtomicFile(settingsFile).delete()
            store.reload()
        }
        Unit
    }

    @Test fun durableOptInAndResultReloadTogetherWithoutSignedUrls() {
        val configured = store.configure(true, 12)
        val notice = MonitorNotice("a".repeat(64), MonitorNoticeKind.METADATA, "NEXT", "Test metadata", "Niet installeerbaar")
        var publishCalls = 0
        val recorded = store.recordIfCurrent(configured.generation,
            MonitorDecision(MonitorOutcome.NEW_METADATA, "CURRENT", "NEXT", notice), 1_000_000L) { publishCalls++; true }!!
        assertEquals(1, publishCalls)
        assertEquals(recorded, store.reload())
        assertTrue(store.current().enabled)
        assertEquals(12, store.current().intervalHours)
        assertTrue(store.current().history.single().notified)
        val persisted = settingsFile.readText()
        assertFalse(persisted.contains("https://"))
        assertFalse(persisted.contains("token="))
        assertEquals(notice.identity, store.state.value.lastNoticeIdentity)
        val stopped = store.configure(false, 12)
        assertNull(store.recordIfCurrent(recorded.generation,
            MonitorDecision(MonitorOutcome.NEW_METADATA, "CURRENT", "OTHER", notice), 1_000_001L) {
            fail("superseded generation must not publish"); false
        })
        assertEquals(stopped, store.reload())
    }

    @Test fun interruptedAtomicWriteRollsBackAndCorruptConfigurationFailsClosed() {
        val configured = store.configure(true, 6)
        val atomic = AtomicFile(settingsFile)
        val incomplete = atomic.startWrite()
        incomplete.write("partial-settings".toByteArray())
        atomic.failWrite(incomplete)
        assertEquals(configured, store.reload())
        val corrupt = atomic.startWrite()
        corrupt.write("enabled=true\nschema=unsupported\n".toByteArray())
        atomic.finishWrite(corrupt)
        val restored = store.reload()
        assertFalse(restored.enabled)
        assertEquals(MonitorOutcome.STORE_CORRUPT, restored.lastOutcome)
        assertEquals("", restored.lastNoticeIdentity)
    }

    @Test fun disabledProductionWorkerReturnsBeforeAnyDeviceInspection() = runBlocking {
        assertFalse(store.current().enabled)
        val before = store.current()
        val worker = TestListenableWorkerBuilder<MotorolaMonitorWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(before, store.current())
        assertEquals(0L, store.current().lastCheckedAtMillis)
        assertTrue(store.current().history.isEmpty())
    }

    @Test fun realPeriodicScheduleIsUniqueAndDisableCancelsIt() = runBlocking {
        assertFalse(controller.checkNow())
        controller.enable(6)
        val work = WorkManager.getInstance(context)
        fun active() = work.getWorkInfosForUniqueWork(MonitorController.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }
        val initial = active().single()
        assertEquals(WorkInfo.State.ENQUEUED, initial.state)
        assertTrue(initial.tags.contains(MonitorController.WORK_TAG))
        controller.enable(12)
        val updated = active().single()
        assertEquals(initial.id, updated.id)
        assertEquals(12, controller.state.value.intervalHours)
        controller.disable()
        assertFalse(controller.state.value.enabled)
        assertTrue(active().isEmpty())
        assertFalse(controller.checkNow())
        assertFalse(store.reload().enabled)
    }

    @Test fun failedOptOutStorageStillClosesGateAndCancelsScheduledWork() = runBlocking {
        controller.enable(6)
        // An empty .new directory makes AtomicFile.startWrite fail without
        // requiring a full emulator disk or changes outside this app's files.
        val obstruction = File(settingsFile.path + ".new").apply { mkdirs() }
        var rejected = false
        try { controller.disable() } catch (_: Exception) { rejected = true }
        obstruction.delete()
        assertTrue("The storage error remains visible to the caller", rejected)
        assertFalse(store.current().enabled)
        val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork(MonitorController.PERIODIC_NAME)
            .get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }
        assertTrue(active.isEmpty())
        assertFalse(store.reload().enabled)
    }
}
