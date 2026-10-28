package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    private val targetBuild = "A171WMH.44-26"
    private val targetFp = "motorola/merc_g_syseq/merc:17/A171WMH.44-26/test:user/release-keys"

    private fun started(device: DeviceState = safetyDevice()): WorkflowState = Workflow.start(
        device, targetBuild, targetFp, "a".repeat(64), true, Readiness.evaluate(device, trustedBackup(device))
    )

    private fun otaPending(device: DeviceState = safetyDevice()): WorkflowState = Workflow.reconcile(
        Workflow.reportStockRestore(started(device)), device.copy(engine = EngineState.UPDATED_NEED_REBOOT)
    )

    private fun targetDevice(source: DeviceState = safetyDevice()): DeviceState = source.copy(
        bootId = "fixture-boot-2", slot = "_a", build = targetBuild, fingerprint = targetFp,
        capturedAtMillis = System.currentTimeMillis()
    )

    @Test fun noUpdateKeepsIdleAndDoesNotClaimNoServerOffer() {
        val idle = Workflow.reconcile(WorkflowState(), safetyDevice())
        assertEquals(WorkflowPhase.IDLE, idle.phase)
        assertFalse(idle.otaAppliedObserved)
    }

    @Test fun compatibleUpdateStartsGuidanceWithoutClaimingStockRestored() {
        val state = started()
        assertEquals(WorkflowPhase.AWAITING_STOCK_RESTORE, state.phase)
        assertFalse(state.manualStockRestoreReported)
        assertFalse(state.otaAppliedObserved)
    }

    @Test fun preflightFromDifferentObservationCannotStartWorkflow() {
        val source = safetyDevice()
        val changed = source.copy(slot = "_a")
        val result = Workflow.start(changed, targetBuild, targetFp, "a".repeat(64), true, Readiness.evaluate(source, trustedBackup(source)))
        assertEquals(WorkflowPhase.BLOCKED, result.phase)
    }

    @Test fun unchangedOrUnknownTargetIsBlocked() {
        val device = safetyDevice()
        val readiness = Readiness.evaluate(device, trustedBackup(device))
        assertEquals(WorkflowPhase.BLOCKED, Workflow.start(device, device.build, device.fingerprint, "a".repeat(64), true, readiness).phase)
        assertEquals(WorkflowPhase.BLOCKED, Workflow.start(device, targetBuild, "", "a".repeat(64), true, readiness).phase)
    }

    @Test fun missingDownloadOrPackageVerificationBlocks() {
        val device = safetyDevice()
        val readiness = Readiness.evaluate(device, trustedBackup(device))
        assertEquals(WorkflowPhase.BLOCKED, Workflow.start(device, targetBuild, targetFp, "a".repeat(64), false, readiness).phase)
        assertEquals(WorkflowPhase.BLOCKED, Workflow.start(device, targetBuild, targetFp, "invalid", true, readiness).phase)
    }

    @Test fun manualStockRestoreNeverCountsAsObservedOta() {
        val reported = Workflow.reportStockRestore(started())
        assertEquals(WorkflowPhase.AWAITING_OTA, reported.phase)
        assertTrue(reported.manualStockRestoreReported)
        assertFalse(reported.otaAppliedObserved)
    }

    @Test fun magiskCannotBeReportedBeforeObservedOta() {
        val original = started()
        assertEquals(original, Workflow.reportMagisk(original))
        assertFalse(original.manualMagiskReported)
    }

    @Test fun enginePendingRebootIsDistinctFromMagiskPreparedAndSuccess() {
        val pending = otaPending()
        assertEquals(WorkflowPhase.MAGISK_PENDING, pending.phase)
        assertTrue(pending.otaAppliedObserved)
        assertFalse(pending.manualMagiskReported)
        val reported = Workflow.reportMagisk(pending)
        assertEquals(WorkflowPhase.MAGISK_REPORTED, reported.phase)
        assertTrue(reported.manualMagiskReported)
        assertNotEquals(WorkflowPhase.COMPLETE, reported.phase)
    }

    @Test fun otaEngineErrorIsTerminalAndNotHiddenByLaterIdle() {
        val device = safetyDevice()
        val failed = Workflow.reconcile(started(device), device.copy(engine = EngineState.ERROR))
        assertEquals(WorkflowPhase.FAILED, failed.phase)
        assertEquals(failed, Workflow.reconcile(failed, device))
    }

    @Test fun failedInactiveSlotMagiskStepIsRecordedAsFailure() {
        val failed = Workflow.fail(otaPending(), "Magisk-installatie op inactief slot mislukt; niet herstarten.")
        assertEquals(WorkflowPhase.FAILED, failed.phase)
        assertFalse(failed.manualMagiskReported)
    }

    @Test fun lostRootAfterRebootCannotBeSuccess() {
        val device = safetyDevice()
        assertEquals(WorkflowPhase.FAILED, Workflow.reconcile(otaPending(device), targetDevice(device).copy(rootGranted = false)).phase)
    }

    @Test fun unmeasuredRootAfterRebootIsNotFalselyReportedAsRootLoss() {
        val source = safetyDevice()
        assertEquals(WorkflowPhase.BOOTED_TARGET, Workflow.reconcile(otaPending(source), targetDevice(source).copy(rootChecked = false, rootGranted = false)).phase)
    }

    @Test fun unknownModuleInventoryAndUnreviewedMagiskDoNotComplete() {
        val source = safetyDevice()
        val state = otaPending(source)
        assertEquals(WorkflowPhase.BOOTED_TARGET, Workflow.reconcile(state, targetDevice(source).copy(moduleInventoryKnown = false)).phase)
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(state, targetDevice(source).copy(magiskCode = 32000)).phase)
    }

    @Test fun processDeathRestoresJournalThenReconcilesLiveObservations() {
        val source = safetyDevice()
        val journal = WorkflowState.decode(otaPending(source).encode())
        assertEquals(WorkflowPhase.MAGISK_PENDING, journal.phase)
        assertEquals(WorkflowPhase.MERGING, Workflow.reconcile(journal, targetDevice(source).copy(snapshot = SnapshotState.MERGING)).phase)
    }

    @Test fun unexpectedRebootBeforeOtaRequiresRecovery() {
        val source = safetyDevice()
        val result = Workflow.reconcile(started(source), source.copy(bootId = "unexpected-boot"))
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, result.phase)
    }

    @Test fun oldSlotAfterPendingOtaIsPossibleRollback() {
        val source = safetyDevice()
        val result = Workflow.reconcile(otaPending(source), source.copy(bootId = "rollback-boot"))
        assertEquals(WorkflowPhase.ROLLBACK, result.phase)
    }

    @Test fun wrongActiveSlotIsNotSuccessEvenOnTargetBuild() {
        val source = safetyDevice()
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(otaPending(source), targetDevice(source).copy(slot = "_b")).phase)
    }

    @Test fun fingerprintAliasAfterRebootNeedsManualInvestigation() {
        val source = safetyDevice()
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(otaPending(source), targetDevice(source).copy(fingerprint = targetFp.replace("syseq", "sys"))).phase)
    }

    @Test fun currentBuildChangeWithoutNewBootIsContradiction() {
        val source = safetyDevice()
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(started(source), source.copy(build = targetBuild)).phase)
    }

    @Test fun targetBootWithoutPriorObservedOtaDoesNotProveWholeWorkflow() {
        val source = safetyDevice()
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(started(source), targetDevice(source)).phase)
    }

    @Test fun activeMergeOrUnknownMergeCannotComplete() {
        val source = safetyDevice()
        val state = otaPending(source)
        assertEquals(WorkflowPhase.MERGING, Workflow.reconcile(state, targetDevice(source).copy(snapshot = SnapshotState.MERGING)).phase)
        assertEquals(WorkflowPhase.BOOTED_TARGET, Workflow.reconcile(state, targetDevice(source).copy(snapshot = SnapshotState.UNKNOWN)).phase)
    }

    @Test fun unsuccessfulOrUnknownBootCannotComplete() {
        val source = safetyDevice()
        val state = otaPending(source)
        assertEquals(WorkflowPhase.BOOTED_TARGET, Workflow.reconcile(state, targetDevice(source).copy(bootSuccessful = false)).phase)
        assertEquals(WorkflowPhase.BOOTED_TARGET, Workflow.reconcile(state, targetDevice(source).copy(bootSuccessful = null)).phase)
    }

    @Test fun missingModulesAndChangedSecurityAreNotFullSuccess() {
        val source = safetyDevice()
        val state = otaPending(source)
        assertEquals(WorkflowPhase.FAILED, Workflow.reconcile(state, targetDevice(source).copy(modules = emptyList())).phase)
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(state, targetDevice(source).copy(bootloaderUnlocked = false)).phase)
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(state, targetDevice(source).copy(verifiedBootState = "green")).phase)
    }

    @Test fun fullObservedOutcomeIncludesSuccessfulBootRootAndMerge() {
        val source = safetyDevice()
        val result = Workflow.reconcile(otaPending(source), targetDevice(source))
        assertEquals(WorkflowPhase.COMPLETE, result.phase)
    }

    @Test fun corruptUnknownVersionOrIncompleteJournalStopsSafely() {
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, WorkflowState.decode("schema=999\nphase=COMPLETE").phase)
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, WorkflowState.decode("schema=1\nphase=COMPLETE").phase)
        assertEquals(WorkflowPhase.IDLE, WorkflowState.decode(null).phase)
    }

    @Test fun persistedManualNotesAndLineBreaksRoundTrip() {
        val state = Workflow.reportMagisk(otaPending()).copy(message = "regel 1\nregel 2 = veilig :é")
        assertEquals(state, WorkflowState.decode(state.encode()))
    }

    @Test fun staleObservationsCannotConfirmPersistedSuccess() {
        val source = safetyDevice()
        val result = Workflow.reconcile(otaPending(source), targetDevice(source).copy(capturedAtMillis = System.currentTimeMillis() - 121_000))
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, result.phase)
    }

    @Test fun simulatedAndRealEvidenceNeverMix() {
        val source = safetyDevice()
        assertEquals(WorkflowPhase.RECOVERY_REQUIRED, Workflow.reconcile(Workflow.demo(), source).phase)
        assertEquals(WorkflowPhase.BLOCKED, started(source.copy(simulated = true)).phase)
        assertTrue(WorkflowState.decode(Workflow.demo().encode()).simulated)
    }
}
