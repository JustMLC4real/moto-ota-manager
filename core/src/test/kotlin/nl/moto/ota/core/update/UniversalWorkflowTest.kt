package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

class UniversalWorkflowTest {
    private fun otherMotorola() = safetyDevice().copy(model = "XT2347-2", device = "bangkk", channel = "retus",
        androidVersion = "16", sdkInt = 36, build = "SOURCE.TEST.1",
        fingerprint = "motorola/bangkk/bangkk:16/SOURCE.TEST.1/123:user/release-keys")

    @Test fun measuredMotorolaStartsWithItsOwnIdentityAndCallerChosenAuthorizationId() {
        val d = otherMotorola()
        val id = "00000000-0000-0000-0000-000000000101"
        val run = UpdateCoordinator.startOfficialGuidedRun(d, trustedBackup(d), ObservedUpdateTarget("TARGET.TEST.2"), runId = id)
        assertEquals(UpdateStage.STOCK_PREPARATION, run.stage)
        assertEquals(id, run.id)
        assertEquals(d.model, run.sourceModel)
        assertEquals(d.device, run.sourceDevice)
        assertEquals(d.channel, run.sourceChannel)
        assertEquals(run, UpdateRun.decode(run.encode()))
    }

    @Test fun changingModelCodenameOrChannelCannotReuseAnotherMotorolasRun() {
        val d = otherMotorola()
        val run = UpdateCoordinator.startOfficialGuidedRun(d, trustedBackup(d), ObservedUpdateTarget("TARGET.TEST.2"))
        for (changed in listOf(d.copy(model = "XT9999-1"), d.copy(device = "different"), d.copy(channel = "reteu"))) {
            val result = UpdateCoordinator.reconcile(run, changed)
            assertEquals(UpdateStage.RECOVERY_REQUIRED, result.stage)
            assertEquals("OBSERVATION_INVALID", result.errorCode)
        }
    }

    @Test fun traditionalAbBootPartitionCanFinishWithoutAnInventedVirtualAbMerge() {
        val d = otherMotorola().copy(virtualAb = false, abUpdate = true, snapshot = SnapshotState.NOT_APPLICABLE,
            partitions = listOf("boot_a", "boot_b"))
        val source = trustedBackup(d).copy(partitionType = "boot")
        val started = UpdateCoordinator.startOfficialGuidedRun(d, source, ObservedUpdateTarget("TARGET.TEST.2"))
        assertEquals(UpdateStage.STOCK_PREPARATION, started.stage)
        assertEquals("boot", started.stockPartitionType)
        val observed = started.copy(otaAppliedObserved = true, stockRestoreEvidence = StepEvidence.OBSERVED)
        val target = d.copy(build = observed.targetBuild, slot = observed.targetSlot, bootId = "OTHER-NEW-BOOT",
            fingerprint = "motorola/bangkk/bangkk:16/TARGET.TEST.2/124:user/release-keys")
        val done = UpdateCoordinator.reconcile(observed, target, trustedBackup(target).copy(partitionType = "boot"))
        assertEquals(UpdateStage.COMPLETE, done.stage)
        assertTrue(done.newStockBackupVerified)
        assertEquals(UpdateStage.COMPLETE_REVIEW, UpdateCoordinator.reconcile(observed, target, source).stage)
    }
}
