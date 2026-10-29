package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

class UpdateFailureTest {
    @Test fun currentCoordinatorKeepsEngineAndMergeFailureFatalAfterLaterIdleOrExplicitRetries() {
        val source = safetyDevice()
        val backup = trustedBackup(source)
        val started = UpdateCoordinator.startOfficialGuidedRun(source, backup, ObservedUpdateTarget("A171WMH.44-26"))
        assertEquals(UpdateStage.STOCK_PREPARATION, started.stage)
        listOf(source.copy(engine=EngineState.ERROR), source.copy(snapshot=SnapshotState.FAILED)).forEach { failedDevice ->
            val failed = UpdateCoordinator.reconcile(started, failedDevice)
            assertEquals(UpdateStage.FAILED, failed.stage)
            assertEquals("ENGINE_OR_MERGE_ERROR", failed.errorCode)
            assertEquals(failed, UpdateCoordinator.reconcile(failed, source))
            assertEquals(failed, UpdateCoordinator.retryFinalChecks(failed, source, backup))
            assertEquals(failed, UpdateCoordinator.retryMagiskStep(failed, source))
            assertEquals(failed, UpdateCoordinator.acknowledgeReboot(failed, true))
        }
    }
}
