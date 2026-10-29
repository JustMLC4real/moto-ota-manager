package nl.moto.ota.platform.monitoring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

/** No Android runtime, shell, server or physical phone is involved in these tests. */
class MonitorRunnerTest {
    private val now = 1_000_000L
    private fun observation() = MonitorObservation(DeviceState(manufacturer = "motorola", model = "XT2607-1", device = "merc",
        channel = "reteu", androidVersion = "17", build = "CURRENT", rootGranted = true, rootChecked = true,
        snapshot = SnapshotState.NONE, engine = EngineState.IDLE, capturedAtMillis = now),
        DiscoveryEvidence(localTargetBuild = "NEXT", localDownloadUrl = "https://dlmgredg-vz.gtm.svcmot.com/metadata.json?temporary=synthetic",
            localContentKind = LocalContentKind.METADATA_ONLY, localMetadataObserved = true), recognizedMetadata = true)
    private class MemoryRepository(var value: MonitorState = MonitorState(enabled = true, generation = 1)) : MonitorRepository {
        var commits = 0
        override fun current() = value
        override fun recordIfCurrent(generation: Long, decision: MonitorDecision, checkedAtMillis: Long,
                                     publish: (MonitorNotice) -> Boolean): MonitorState? =
            MonitorTransition.recordIfCurrent(value, generation, decision, checkedAtMillis, publish)?.also { value = it; commits++ }
    }

    @Test fun defaultOffDoesNotInspectOrNotify() = runBlocking {
        val repository = MemoryRepository(MonitorState())
        val runner = MonitorRunner(repository, { fail("disabled monitor must not inspect"); observation() },
            { fail("disabled monitor must not notify"); false }, { now })
        assertEquals(MonitorRunResult.DISABLED, runner.check())
        assertEquals(0, repository.commits)
    }

    @Test fun newReferenceNotifiesOnceAndNextRunStaysQuiet() = runBlocking {
        val repository = MemoryRepository()
        var calls = 0
        val runner = MonitorRunner(repository, { observation() }, { calls++; true }, { now })
        assertEquals(MonitorRunResult.COMPLETED, runner.check())
        assertEquals(MonitorOutcome.NEW_METADATA, repository.value.lastOutcome)
        assertEquals(MonitorRunResult.COMPLETED, runner.check())
        assertEquals(MonitorOutcome.ALREADY_NOTIFIED, repository.value.lastOutcome)
        assertEquals(1, calls)
        assertEquals(2, repository.commits)
    }

    @Test fun revokedRootRecordsActionableStatusWithoutNotification() = runBlocking {
        val repository = MemoryRepository()
        val observed = observation()
        val runner = MonitorRunner(repository, { observed.copy(device = observed.device.copy(rootGranted = false)) },
            { fail("revoked root must not produce update notice"); false }, { now })
        assertEquals(MonitorRunResult.COMPLETED, runner.check())
        assertEquals(MonitorOutcome.ROOT_REQUIRED, repository.value.lastOutcome)
        assertEquals("", repository.value.lastNoticeIdentity)
    }

    @Test fun deniedNotificationCanBeDeliveredAfterPermissionIsGranted() = runBlocking {
        val repository = MemoryRepository()
        var allowed = false
        var calls = 0
        val runner = MonitorRunner(repository, { observation() }, { calls++; allowed }, { now })
        runner.check()
        assertEquals(MonitorOutcome.NOTIFICATIONS_DISABLED, repository.value.lastOutcome)
        assertEquals("", repository.value.lastNoticeIdentity)
        allowed = true
        runner.check()
        assertEquals(MonitorOutcome.NEW_METADATA, repository.value.lastOutcome)
        assertFalse(repository.value.lastNoticeIdentity.isEmpty())
        assertEquals(2, calls)
    }

    @Test fun disablingDuringInspectionDiscardsResultAndPreventsNotification() = runBlocking {
        val repository = MemoryRepository()
        val runner = MonitorRunner(repository, {
            repository.value = repository.value.copy(enabled = false, generation = 2)
            observation()
        }, { fail("superseded result must not publish"); false }, { now })
        assertEquals(MonitorRunResult.SUPERSEDED, runner.check())
        assertEquals(0, repository.commits)
        assertFalse(repository.value.enabled)
    }

    @Test fun changedIntervalGenerationAlsoDiscardsAnAlreadyRunningCheck() = runBlocking {
        val repository = MemoryRepository()
        val runner = MonitorRunner(repository, {
            repository.value = repository.value.copy(intervalHours = 12, generation = 2)
            observation()
        }, { fail("old generation must not publish"); false }, { now })
        assertEquals(MonitorRunResult.SUPERSEDED, runner.check())
        assertEquals(0, repository.commits)
    }

    @Test fun sourceFailuresProduceSanitizedHistoryAndStayQuiet() = runBlocking {
        val repository = MemoryRepository()
        val runner = MonitorRunner(repository, { throw IllegalStateException("https://private.example/?token=secret") },
            { fail("failed scan must not notify"); false }, { now })
        assertEquals(MonitorRunResult.COMPLETED, runner.check())
        assertEquals(MonitorOutcome.CHECK_FAILED, repository.value.lastOutcome)
        val stored = repository.value.encode()
        assertFalse(stored.contains("https://"))
        assertFalse(stored.contains("secret"))
        assertEquals(1, repository.value.history.size)
    }

    @Test fun cancellationPropagatesWithoutRecordingFailureOrPublishing() = runBlocking {
        val repository = MemoryRepository()
        val runner = MonitorRunner(repository, { throw CancellationException("cancelled") },
            { fail("cancelled scan must not notify"); false }, { now })
        try { runner.check(); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, repository.commits)
        assertEquals(MonitorOutcome.NEVER, repository.value.lastOutcome)
    }

    @Test fun configurationSupersededBeforeRootStopsQuietlyWithoutCancellingPeriodicWorker() = runBlocking {
        val repository = MemoryRepository()
        val runner = MonitorRunner(repository, { throw MonitorSupersededException() },
            { fail("superseded scan must not notify"); false }, { now })
        assertEquals(MonitorRunResult.SUPERSEDED, runner.check())
        assertEquals(0, repository.commits)
    }

    @Test fun absentMetadataRemainsUnknownInsteadOfRecordingFalseUpToDate() = runBlocking {
        val repository = MemoryRepository()
        val runner = MonitorRunner(repository, { observation().copy(discovery = DiscoveryEvidence(), recognizedMetadata = false) },
            { fail("missing metadata must not notify"); false }, { now })
        runner.check()
        assertEquals(MonitorOutcome.METADATA_UNAVAILABLE, repository.value.lastOutcome)
        assertFalse(repository.value.history.single().notified)
    }
}
