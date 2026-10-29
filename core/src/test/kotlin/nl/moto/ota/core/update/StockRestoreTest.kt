package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StockRestoreTest {
    private fun environment(mode:ExecutionMode=ExecutionMode.SANDBOX):RestoreEnvironment {
        val d=safetyDevice().copy(simulated=mode==ExecutionMode.SANDBOX,securityEvidenceSource="KERNEL_BOOTCONFIG")
        return RestoreEnvironment(d,8192,"a".repeat(64),true,trustedBackup(d),mode)
    }
    private val secret=ByteArray(32){it.toByte()}
    private fun rejected(code:String?=null,block:()->Unit){
        try{block();fail("Expected safety rejection")}catch(e:UpdateSafetyException){if(code!=null)assertEquals(code,e.code)}
    }
    @Test fun consentAuthenticatesEveryFieldAndRoundTrips(){
        val env=environment();val plan=StockRestorePolicy.plan(env,"test-run")
        val consent=StockRestorePolicy.confirm(plan,secret,true,true)
        StockRestorePolicy.verify(plan,consent,secret,env.copy(elapsedRealtimeMillis=plan.createdElapsedRealtimeMillis))
        val decoded=StockRestorePlanCodec.decode(StockRestorePlanCodec.encode(plan,consent))
        assertEquals(plan,decoded.first);assertEquals(consent,decoded.second)
        rejected { StockRestorePolicy.verify(plan.copy(stockSha256="b".repeat(64)),consent,secret,env) }
        rejected { StockRestorePolicy.verify(plan.copy(slot="_a"),consent,secret,env) }
        rejected { StockRestorePolicy.verify(plan.copy(bootId="another-boot"),consent,secret,env) }
        rejected { StockRestorePolicy.verify(plan.copy(previousImageSha256="b".repeat(64)),consent,secret,env) }
        rejected { StockRestorePolicy.verify(plan.copy(mode=ExecutionMode.LIVE),consent,secret,env) }
    }
    @Test fun monotonicExpiryCannotBeUndoneByChangingWallClock(){
        val env=environment();val plan=StockRestorePolicy.plan(env,"test-run")
        rejected("CONSENT_EXPIRED") { StockRestorePolicy.confirm(plan,secret,true,true,plan.createdAtMillis,plan.expiresElapsedRealtimeMillis+1) }
        val consent=StockRestorePolicy.confirm(plan,secret,true,true)
        rejected { StockRestorePolicy.verify(plan,consent,secret,env.copy(elapsedRealtimeMillis=plan.expiresElapsedRealtimeMillis+1),plan.createdAtMillis) }
        rejected { StockRestorePolicy.verify(plan,consent,secret,env.copy(elapsedRealtimeMillis=plan.createdElapsedRealtimeMillis-1),plan.createdAtMillis) }
    }
    @Test fun explicitConsentAndNoRebootAcknowledgmentAreBothRequired(){
        val plan=StockRestorePolicy.plan(environment(),"test-run")
        rejected("CONSENT_REQUIRED"){StockRestorePolicy.confirm(plan,secret,false,true)}
        rejected("CONSENT_REQUIRED"){StockRestorePolicy.confirm(plan,secret,true,false)}
    }
    @Test fun liveModeRequiresRuntimeAssociationAndRejectsSimulatedObservations(){
        assertTrue(StockRestorePolicy.blockers(environment(ExecutionMode.LIVE)).any { it.code=="MAGISK_BACKUP_ASSOCIATION_MISMATCH" })
        assertTrue(StockRestorePolicy.blockers(environment().copy(mode=ExecutionMode.LIVE)).any { it.code=="EXECUTION_MODE" })
    }
    @Test fun unknownOrEnabledAutomaticUpdatesBlockRestore(){
        listOf<Boolean?>(null,false).forEach { value -> rejected("AUTOMATIC_UPDATES"){StockRestorePolicy.plan(environment().copy(automaticUpdatesDisabled=value),"r")} }
    }
    @Test fun rootSecurityAndFreshnessAreIndependentlyRequired(){
        val e=environment()
        val changed=listOf(e.device.copy(rootChecked=false),e.device.copy(rootGranted=false),e.device.copy(magiskCode=31000),
            e.device.copy(snapshot=SnapshotState.UNKNOWN),e.device.copy(engine=EngineState.UNKNOWN),e.device.copy(bootSuccessful=null),
            e.device.copy(moduleInventoryKnown=false),e.device.copy(bootloaderUnlocked=null),e.device.copy(capturedAtMillis=System.currentTimeMillis()-31_000))
        changed.forEach { d -> assertTrue(StockRestorePolicy.blockers(e.copy(device=d)).isNotEmpty()) }
    }
    @Test fun changedPartitionHashOrBootRequiresANewPlan(){
        val e=environment();val p=StockRestorePolicy.plan(e,"r");val c=StockRestorePolicy.confirm(p,secret,true,true)
        rejected("OBSERVATIONS_CHANGED"){StockRestorePolicy.verify(p,c,secret,e.copy(activeImageSha256="f".repeat(64)))}
        rejected("OBSERVATIONS_CHANGED"){StockRestorePolicy.verify(p,c,secret,e.copy(device=e.device.copy(bootId="new-boot")))}
    }

    private fun fixture(fault:(RestoreCheckpoint)->Unit={},failAfter:Long?=null):Triple<SandboxStockRestoreBackend,ByteArray,ByteArray>{
        val dir=Files.createTempDirectory("moto-ota-sandbox-test-").toFile().apply { deleteOnExit() }
        val stock=initBootFixture();val patched=stock.copyOf().apply { for(i in 4096 until size)this[i]=(this[i].toInt() xor 85).toByte() }
        File(dir,"partitions").mkdir()
        File(dir,"partitions/init_boot_a").writeBytes(patched);File(dir,"partitions/init_boot_b").writeBytes(patched);File(dir,"stock.img").writeBytes(stock)
        val d=safetyDevice().copy(simulated=true,securityEvidenceSource="KERNEL_BOOTCONFIG")
        val backend=SandboxStockRestoreBackend(dir,{d.copy(capturedAtMillis=System.currentTimeMillis())},stockReference(stock,d),fault=fault,failAfterWrittenBytes=failAfter)
        return Triple(backend,stock,patched)
    }
    @Test fun sandboxWritesOnlyActiveRegularFileAfterFullVerifiedBackup(){
        val (b,stock,patched)=fixture();val p=b.prepare("r");val c=b.confirm(p,true,true)
        val r=b.execute(p,c)
        assertTrue(r.stockRestored);assertArrayEquals(stock,b.partitionFile("_b").readBytes())
        assertArrayEquals(patched,b.partitionFile("_a").readBytes());assertArrayEquals(patched,b.backupFile(p).readBytes())
        assertEquals(sha256(stock),r.observedSha256);assertEquals(sha256(patched),r.backupSha256)
    }
    @Test fun consumedConsentCannotBeReplayedEvenWhenFileIsRestoredExternally(){
        val (b,_,patched)=fixture();val p=b.prepare("r");val c=b.confirm(p,true,true)
        assertTrue(b.execute(p,c).stockRestored)
        b.partitionFile("_b").writeBytes(patched)
        val replay=b.execute(p,c)
        assertFalse(replay.stockRestored);assertEquals("CONSENT_ALREADY_CONSUMED",replay.errorCode)
        assertArrayEquals(patched,b.partitionFile("_b").readBytes())
    }
    @Test fun interruptedWriteRetainsBackupAndRecoveryNeverReplays(){
        val (b,_,patched)=fixture(failAfter=5000)
        val p=b.prepare("r");val result=b.execute(p,b.confirm(p,true,true))
        assertFalse(result.stockRestored);assertArrayEquals(patched,b.backupFile(p).readBytes())
        val after=b.partitionFile("_b").readBytes()
        assertFalse(b.recover(p).stockRestored);assertArrayEquals(after,b.partitionFile("_b").readBytes())
    }
    @Test fun processDeathAfterConsentIsDurableAndDoesNotWriteDuringRecovery(){
        val (b,_,patched)=fixture(fault={if(it==RestoreCheckpoint.TOKEN_CONSUMED)throw SimulatedProcessDeath()})
        val p=b.prepare("r");val c=b.confirm(p,true,true)
        try{b.execute(p,c);fail()}catch(_:SimulatedProcessDeath){}
        assertArrayEquals(patched,b.partitionFile("_b").readBytes())
        assertFalse(b.recover(p).stockRestored)
        val restarted=SandboxStockRestoreBackend(b.directory,{safetyDevice().copy(simulated=true)},stockReference(initBootFixture()))
        val result=restarted.execute(p,c)
        assertFalse(result.stockRestored);assertEquals("CONSENT_ALREADY_CONSUMED",result.errorCode)
    }
    @Test fun processDeathAfterReadbackRecoversByObservationWithoutAnotherWrite(){
        val (b,stock,_)=fixture(fault={if(it==RestoreCheckpoint.STOCK_VERIFIED)throw SimulatedProcessDeath()})
        val p=b.prepare("r")
        try{b.execute(p,b.confirm(p,true,true));fail()}catch(_:SimulatedProcessDeath){}
        assertTrue(b.recover(p).stockRestored);assertArrayEquals(stock,b.partitionFile("_b").readBytes())
    }
    @Test fun wrongSourceNeverReachesWriteBoundary(){
        val (b,_,patched)=fixture();val p=b.prepare("r");val c=b.confirm(p,true,true)
        File(b.directory,"stock.img").appendBytes(byteArrayOf(1))
        assertFalse(b.execute(p,c).stockRestored)
        assertArrayEquals(patched,b.partitionFile("_b").readBytes())
        assertFalse(File(b.directory,"transactions/${p.transactionId}/consent.consumed").exists())
    }
    @Test fun sandboxRejectsARealDeviceAndLivePlans(){
        val (b,_,_)=fixture();val p=b.prepare("r")
        try{b.execute(p.copy(mode=ExecutionMode.LIVE),b.confirm(p,true,true));fail()}catch(_:IllegalArgumentException){}
        val real=SandboxStockRestoreBackend(b.directory,{safetyDevice()},stockReference(initBootFixture()))
        try{real.observe();fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun recoveryCannotClaimSuccessWhileAnotherWriterHoldsTheLock(){
        val (b,_,_)=fixture();val p=b.prepare("r")
        java.io.RandomAccessFile(File(b.directory,"restore.lock"),"rw").channel.use { channel -> channel.lock().use {
            rejected("RESTORE_ALREADY_RUNNING"){b.recover(p)}
        } }
    }
    @Test fun explicitRecoveryOfUnchangedSourceRecapturesBackupInvalidatesOldTokenAndNeedsANewPlan(){
        val (interrupted,stock,patched)=fixture(fault={if(it==RestoreCheckpoint.PREPARED)throw SimulatedProcessDeath()})
        val env=interrupted.observe()
        var run=UpdateCoordinator.startSandboxGuidedRun(env.device,env.stockValidation,ObservedUpdateTarget("NEXT-FIXTURE"))
        val plan=interrupted.prepare(run.id);val consent=interrupted.confirm(plan,true,true)
        run=UpdateCoordinator.restoreDispatching(UpdateCoordinator.restorePrepared(run,plan))
        try{interrupted.execute(plan,consent);fail()}catch(_:SimulatedProcessDeath){}
        run=UpdateCoordinator.reconcile(run,env.device)
        assertEquals(UpdateStage.RECOVERY_REQUIRED,run.stage)
        val (receipt,fresh)=interrupted.verifyUnchangedAndInvalidate(plan)
        assertEquals(RestoreCheckpoint.REPREPARATION_READY,receipt.checkpoint)
        assertArrayEquals(patched,interrupted.backupFile(plan).readBytes())
        run=UpdateCoordinator.releasePreparation(run,plan,receipt,fresh)
        assertEquals(UpdateStage.STOCK_PREPARATION,run.stage);assertEquals("",run.restoreTransactionId)
        assertArrayEquals(patched,interrupted.partitionFile("_b").readBytes())
        val normal=SandboxStockRestoreBackend(interrupted.directory,{env.device.copy(capturedAtMillis=System.currentTimeMillis())},stockReference(stock,env.device))
        assertEquals("CONSENT_ALREADY_CONSUMED",normal.execute(plan,consent).errorCode)
        val next=normal.prepare(run.id);assertNotEquals(plan.transactionId,next.transactionId)
        assertTrue(normal.execute(next,normal.confirm(next,true,true)).stockRestored)
    }
    @Test fun partialChangedSourceCannotBeReleasedForAnotherAttempt(){
        val (b,_,_)=fixture(failAfter=5000);val plan=b.prepare("r")
        assertFalse(b.execute(plan,b.confirm(plan,true,true)).stockRestored)
        val before=b.partitionFile("_b").readBytes()
        rejected("SOURCE_NOT_UNCHANGED"){b.verifyUnchangedAndInvalidate(plan)}
        assertArrayEquals(before,b.partitionFile("_b").readBytes())
    }
}
