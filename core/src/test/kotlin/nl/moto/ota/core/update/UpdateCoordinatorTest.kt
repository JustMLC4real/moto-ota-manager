package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

class UpdateCoordinatorTest {
    private fun start():UpdateRun {
        val d=safetyDevice()
        return UpdateCoordinator.startOfficialGuidedRun(d,trustedBackup(d),ObservedUpdateTarget("A171WMH.44-99","vendor/other:15/namespace"))
    }
    private fun source()=safetyDevice()
    private fun otaApplied():UpdateRun {
        val run=start().copy(stockRestoreEvidence=StepEvidence.OBSERVED,stage=UpdateStage.AWAITING_OFFICIAL_OTA)
        return UpdateCoordinator.reconcile(run,source().copy(engine=EngineState.UPDATED_NEED_REBOOT))
    }
    private fun target(run:UpdateRun)=source().copy(build=run.targetBuild,fingerprint="measured/system:17/new-build",bootId="new-boot",slot=run.targetSlot)
    @Test fun officialRouteDoesNotRequireGenericPackageMetadataCompatibility(){
        val r=start();assertEquals(UpdateStage.STOCK_PREPARATION,r.stage)
        assertEquals("vendor/other:15/namespace",r.declaredTargetFingerprint);assertFalse(r.packageVerified)
    }
    @Test fun targetBuildIsRequiredButDeclaredFingerprintIsOptional(){
        val d=source();val b=trustedBackup(d)
        assertEquals(UpdateStage.STOCK_PREPARATION,UpdateCoordinator.startOfficialGuidedRun(d,b,ObservedUpdateTarget("next")).stage)
        assertEquals(UpdateStage.PRECHECK_BLOCKED,UpdateCoordinator.startOfficialGuidedRun(d,b,ObservedUpdateTarget("")).stage)
        assertEquals(UpdateStage.PRECHECK_BLOCKED,UpdateCoordinator.startOfficialGuidedRun(d,b,ObservedUpdateTarget(d.build)).stage)
    }
    @Test fun simulationCannotStartLiveRun(){
        val d=source().copy(simulated=true)
        assertEquals(UpdateStage.PRECHECK_BLOCKED,UpdateCoordinator.startOfficialGuidedRun(d,trustedBackup(d),ObservedUpdateTarget("next")).stage)
        assertTrue(UpdateCoordinator.startSandboxGuidedRun(d,trustedBackup(d),ObservedUpdateTarget("next")).simulated)
    }
    @Test fun manualRestoreRemainsUnprovenUntilActualPartitionMeasurement(){
        val r=UpdateCoordinator.reportManualStockRestore(start())
        assertEquals(StepEvidence.USER_REPORTED,r.stockRestoreEvidence)
        assertEquals(r,UpdateCoordinator.openOfficialOta(r))
        assertNotEquals(UpdateStage.STOCK_RESTORED,UpdateCoordinator.observeStockHash(r,r.sourceBootId,r.sourceSlot,"f".repeat(64)).stage)
        val measured=UpdateCoordinator.observeStockHash(r,r.sourceBootId,r.sourceSlot,r.stockSha256)
        assertEquals(UpdateStage.STOCK_RESTORED,measured.stage)
        assertEquals(UpdateStage.AWAITING_OFFICIAL_OTA,UpdateCoordinator.openOfficialOta(measured).stage)
        assertTrue(UpdateCoordinator.openOfficialOta(measured).otaAuthenticityDelegatedToOfficialUpdater)
    }
    @Test fun interruptedWriterIsOnlyReconciledByReading(){
        val r=start().copy(stage=UpdateStage.STOCK_WRITE_RUNNING)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,source()).stage)
        val observed=UpdateCoordinator.reconcile(r,source(),activeImageSha256=r.stockSha256)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,observed.stage);assertEquals(StepEvidence.NONE,observed.stockRestoreEvidence)
    }
    @Test fun idleEngineDoesNotMeanOtaApplied(){
        val r=UpdateCoordinator.reconcile(start(),source())
        assertFalse(r.otaAppliedObserved)
        assertEquals(r,UpdateCoordinator.reportMagiskInstalled(r))
        assertEquals(r,UpdateCoordinator.acknowledgeReboot(r,true))
    }
    @Test fun pendingRebootObservesOtaButNotMagisk(){
        val r=otaApplied();assertTrue(r.otaAppliedObserved);assertEquals(UpdateStage.MAGISK_REQUIRED,r.stage)
        assertEquals(StepEvidence.NONE,r.magiskEvidence)
        assertEquals(r,UpdateCoordinator.acknowledgeReboot(r,true))
    }
    @Test fun manualMagiskReportRemainsUnverifiedBeforeBoot(){
        val r=UpdateCoordinator.reportMagiskInstalled(otaApplied())
        assertEquals(UpdateStage.MAGISK_REPORTED_UNVERIFIED,r.stage);assertEquals(StepEvidence.USER_REPORTED,r.magiskEvidence)
        assertEquals(r,UpdateCoordinator.acknowledgeReboot(r,false))
        val acknowledged=UpdateCoordinator.acknowledgeReboot(r,true)
        assertEquals(UpdateStage.AWAITING_NEW_BOOT,acknowledged.stage);assertTrue(acknowledged.rebootConsentRecorded)
        assertEquals(StepEvidence.USER_REPORTED,acknowledged.magiskEvidence)
    }
    @Test fun forgedMagiskProofCannotConfirmAnImage(){
        val r=otaApplied();val p=TargetMagiskProof(r.targetSlot,"a".repeat(64),"b".repeat(40),"c".repeat(40),true,true,31100,System.currentTimeMillis())
        assertEquals(StepEvidence.NONE,UpdateCoordinator.observeMagiskProof(r,p).magiskEvidence)
        assertEquals(StepEvidence.NONE,UpdateCoordinator.observeMagiskProof(r,p.copy(embeddedStockSha1=p.expectedStockSha1,verifiedMagiskTool=false)).magiskEvidence)
        assertEquals(StepEvidence.NONE,UpdateCoordinator.observeMagiskProof(r,p.copy(embeddedStockSha1=p.expectedStockSha1)).magiskEvidence)
        assertEquals(StepEvidence.CRYPTOGRAPHIC_IMAGE,UpdateCoordinator.observeMagiskProof(r.copy(authenticatedTargetStockSha1=p.expectedStockSha1),p.copy(embeddedStockSha1=p.expectedStockSha1)).magiskEvidence)
    }
    @Test fun actualTargetBuildAndSlotAreRequired(){
        val r=otaApplied()
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,target(r).copy(build="wrong")).stage)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,target(r).copy(slot=r.sourceSlot)).stage)
    }
    @Test fun sourceBuildAfterNewBootIsPossibleRollback(){
        assertEquals(UpdateStage.ROLLBACK,UpdateCoordinator.reconcile(otaApplied(),source().copy(bootId="new-boot")).stage)
    }
    @Test fun targetFingerprintNamespaceDoesNotOverrideMeasuredBoot(){
        val r=otaApplied();val actual=target(r)
        val done=UpdateCoordinator.reconcile(r,actual,trustedBackup(actual))
        assertEquals(UpdateStage.COMPLETE,done.stage)
        assertEquals(actual.fingerprint,done.observedTargetFingerprint)
        assertEquals("vendor/other:15/namespace",done.declaredTargetFingerprint)
        assertEquals(StepEvidence.OBSERVED_AFTER_BOOT,done.magiskEvidence)
    }
    @Test fun rootHasToBeMeasuredAfterBoot(){
        val r=otaApplied()
        assertEquals(UpdateStage.TARGET_BOOTED,UpdateCoordinator.reconcile(r,target(r).copy(rootChecked=false)).stage)
        assertEquals(UpdateStage.FAILED,UpdateCoordinator.reconcile(r,target(r).copy(rootGranted=false)).stage)
    }
    @Test fun mergeAndUnknownStatesCannotBeComplete(){
        val r=otaApplied()
        assertEquals(UpdateStage.MERGING,UpdateCoordinator.reconcile(r,target(r).copy(snapshot=SnapshotState.MERGING)).stage)
        assertEquals(UpdateStage.FINAL_CHECKS,UpdateCoordinator.reconcile(r,target(r).copy(snapshot=SnapshotState.UNKNOWN)).stage)
        assertEquals(UpdateStage.FINAL_CHECKS,UpdateCoordinator.reconcile(r,target(r).copy(bootSuccessful=null)).stage)
        assertEquals(UpdateStage.FINAL_CHECKS,UpdateCoordinator.reconcile(r,target(r).copy(engine=EngineState.UNKNOWN)).stage)
    }
    @Test fun missingNewStockProofDoesNotClaimNextUpdateReadiness(){
        val r=otaApplied();val done=UpdateCoordinator.reconcile(r,target(r))
        assertEquals(UpdateStage.COMPLETE_REVIEW,done.stage);assertFalse(done.newStockBackupVerified)
    }
    @Test fun moduleLossAndSecurityChangesRequireReview(){
        val r=otaApplied()
        assertEquals(UpdateStage.FAILED,UpdateCoordinator.reconcile(r,target(r).copy(modules=emptyList())).stage)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,target(r).copy(bootloaderUnlocked=false)).stage)
    }
    @Test fun unobservedOtaCannotBecomeACompleteGuidedRun(){
        val r=start()
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,target(r),trustedBackup(target(r))).stage)
    }
    @Test fun unknownMetadataAndMixedModesNeverSucceed(){
        val r=otaApplied()
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,target(r).copy(simulated=true)).stage)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,target(r).copy(capturedAtMillis=1)).stage)
        assertEquals(UpdateStage.FAILED,UpdateCoordinator.reconcile(r,target(r).copy(snapshot=SnapshotState.FAILED)).stage)
    }
    @Test fun journalRoundTripPreservesIndependentEvidence(){
        val r=UpdateCoordinator.acknowledgeReboot(UpdateCoordinator.reportMagiskInstalled(otaApplied()),true)
        assertEquals(r,UpdateRun.decode(r.encode()))
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateRun.decode("schema=999").stage)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateRun.decode("").stage)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateRun.decode(" \n\t").stage)
        assertEquals(UpdateStage.IDLE,UpdateRun.decode(null).stage)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateRun.decode(r.encode().replace("otaAppliedObserved=true","otaAppliedObserved=yes")).stage)
    }
    @Test fun lateUiCallbacksCannotRegressTargetBootOrCompletedRun(){
        val before=UpdateCoordinator.acknowledgeReboot(UpdateCoordinator.reportMagiskInstalled(otaApplied()),true)
        val states=listOf(UpdateCoordinator.reconcile(before,target(before).copy(snapshot=SnapshotState.MERGING)),
            UpdateCoordinator.reconcile(before,target(before),trustedBackup(target(before))))
        states.forEach { r ->
            assertEquals(r,UpdateCoordinator.openOfficialOta(r))
            assertEquals(r,UpdateCoordinator.observeStockHash(r,r.sourceBootId,r.sourceSlot,r.stockSha256))
            assertEquals(r,UpdateCoordinator.reportMagiskInstalled(r))
            assertEquals(r,UpdateCoordinator.acknowledgeReboot(r,true))
            assertEquals(r,UpdateCoordinator.reportManualStockRestore(r))
            assertEquals(r,UpdateCoordinator.restoreDispatching(r))
        }
    }
    @Test fun staleSourceObservationCannotAcknowledgeRebootOrLaunchWorkflowStep(){
        val r=UpdateCoordinator.reportMagiskInstalled(otaApplied()).copy(lastObservationMillis=1)
        assertEquals(r,UpdateCoordinator.acknowledgeReboot(r,true))
        val stock=start().copy(stage=UpdateStage.STOCK_RESTORED,stockRestoreEvidence=StepEvidence.OBSERVED,lastObservationMillis=1)
        assertEquals(stock,UpdateCoordinator.openOfficialOta(stock))
    }
    @Test fun changedSourceFingerprintOrStockHashInvalidatesHandoff(){
        val r=start().let { UpdateCoordinator.observeStockHash(it,it.sourceBootId,it.sourceSlot,it.stockSha256) }
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateCoordinator.reconcile(r,source().copy(fingerprint="other-namespace")).stage)
        val changed=UpdateCoordinator.reconcile(r,source(),activeImageSha256="f".repeat(64))
        assertEquals(UpdateStage.RECOVERY_REQUIRED,changed.stage);assertEquals(StepEvidence.NONE,changed.stockRestoreEvidence)
        assertEquals(changed,UpdateCoordinator.openOfficialOta(changed))
    }
    @Test fun rootPermissionFailureCanBeRetriedOnlyByAnExplicitFreshTargetRead(){
        val r=otaApplied();val denied=UpdateCoordinator.reconcile(r,target(r).copy(rootGranted=false))
        assertEquals(UpdateStage.FAILED,denied.stage)
        assertEquals(denied,UpdateCoordinator.reconcile(denied,target(r),trustedBackup(target(r))))
        assertEquals(UpdateStage.COMPLETE,UpdateCoordinator.retryFinalChecks(denied,target(r),trustedBackup(target(r))).stage)
        val fatal=denied.copy(errorCode="ENGINE_OR_MERGE_ERROR")
        assertEquals(fatal,UpdateCoordinator.retryFinalChecks(fatal,target(r),trustedBackup(target(r))))
    }
    @Test fun cancellationIsOnlyPossibleBeforeAnyRestoreOrPendingPlan(){
        assertEquals(UpdateStage.IDLE,UpdateCoordinator.cancelBeforeRestore(start()).stage)
        val reported=UpdateCoordinator.reportManualStockRestore(start())
        assertEquals(reported,UpdateCoordinator.cancelBeforeRestore(reported))
        val pending=start().copy(stage=UpdateStage.STOCK_CONFIRMATION,restoreTransactionId="pending")
        assertEquals(pending,UpdateCoordinator.cancelBeforeRestore(pending))
        val applied=otaApplied();assertEquals(applied,UpdateCoordinator.cancelBeforeRestore(applied))
    }
    @Test fun authenticatedPackageCanBeAttachedWithoutErasingCompatibilityBlockers(){
        val r=start();val report=FirmwareReport(signatureVerified=true,structureValid=true,payloadHashValid=true,compatible=false,regionVerified=false,
            targetBuild=r.targetBuild,targetFingerprint="payload/exact:17/target",sha256="d".repeat(64),payloadProperties=mapOf("TARGET_FP" to "payload/exact:17/target"),issues=listOf(CheckResult("REGION_UNKNOWN","unknown")))
        val attached=UpdateCoordinator.attachVerifiedPackage(r,report)
        assertTrue(attached.packageVerified);assertEquals(report.sha256,attached.packageSha256);assertEquals("payload/exact:17/target",attached.declaredTargetFingerprint)
        assertFalse(report.readyForOfficialReview)
        val standardOnly=report.copy(targetFingerprint="standard/exact:17/target",payloadProperties=emptyMap())
        assertEquals(standardOnly.targetFingerprint,UpdateCoordinator.attachVerifiedPackage(r.copy(declaredTargetFingerprint=""),standardOnly).declaredTargetFingerprint)
        assertEquals(r,UpdateCoordinator.attachVerifiedPackage(r,report.copy(signatureVerified=false)))
        assertEquals(r,UpdateCoordinator.attachVerifiedPackage(r,report.copy(targetBuild="another")))
        listOf(UpdateStage.STOCK_WRITE_RUNNING,UpdateStage.OTA_APPLYING,UpdateStage.MAGISK_REQUIRED,UpdateStage.COMPLETE).forEach { stage ->
            val later=r.copy(stage=stage);assertEquals(later,UpdateCoordinator.attachVerifiedPackage(later,report))
        }
    }
    @Test fun reportedMagiskFailureCannotRebootAndOnlyFreshSameSourceCanReleaseManualRetry(){
        val failed=UpdateCoordinator.fail(UpdateCoordinator.reportMagiskInstalled(otaApplied()),"MAGISK_INACTIVE_FAILED","User reported installer failure")
        assertEquals(failed,UpdateCoordinator.acknowledgeReboot(failed,true))
        val d=source().copy(engine=EngineState.UPDATED_NEED_REBOOT,snapshot=SnapshotState.UNVERIFIED)
        val retry=UpdateCoordinator.retryMagiskStep(failed,d)
        assertEquals(UpdateStage.MAGISK_REQUIRED,retry.stage);assertEquals(StepEvidence.NONE,retry.magiskEvidence);assertFalse(retry.rebootConsentRecorded)
        assertEquals(retry,UpdateCoordinator.acknowledgeReboot(retry,true))
        listOf(d.copy(slot=failed.targetSlot),d.copy(bootId="another-boot"),d.copy(fingerprint="alias"),d.copy(capturedAtMillis=1),
            d.copy(rootGranted=false),d.copy(magiskCode=31200),d.copy(snapshot=SnapshotState.MERGING),d.copy(engine=EngineState.IDLE)).forEach { invalid ->
            assertEquals(failed,UpdateCoordinator.retryMagiskStep(failed,invalid))
        }
        val fatal=failed.copy(errorCode="ENGINE_OR_MERGE_ERROR");assertEquals(fatal,UpdateCoordinator.retryMagiskStep(fatal,d))
    }
    @Test fun unreviewedTargetAndroidCannotBeQualifiedEvenWhenRootAndAllOtherChecksPass(){
        val r=otaApplied()
        listOf("18","").forEach { version ->
            val d=target(r).copy(androidVersion=version)
            val result=UpdateCoordinator.reconcile(r,d,trustedBackup(d))
            assertEquals(UpdateStage.RECOVERY_REQUIRED,result.stage);assertEquals("TARGET_ANDROID_UNREVIEWED",result.errorCode)
            assertEquals(d.fingerprint,result.observedTargetFingerprint)
        }
    }
}
