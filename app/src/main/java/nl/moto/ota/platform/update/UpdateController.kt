package nl.moto.ota.platform.update

import android.content.Context
import android.util.AtomicFile
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import java.io.File

/** One app-private durable journal. Loading and reconciliation never execute writes. */
class UpdateRunStore(context:Context) {
    private val journal=AtomicFile(File(context.filesDir,"guided-update-run.properties"))
    fun load():UpdateRun = synchronized(LOCK) {
        if(!journal.baseFile.exists() && !File(journal.baseFile.path+".bak").exists() && !File(journal.baseFile.path+".new").exists()) UpdateRun() else runCatching {
            journal.openRead().use { input ->
                val bytes=input.readNBytes(131_073); require(bytes.size<=131_072)
                UpdateRun.decode(bytes.toString(Charsets.UTF_8))
            }
        }.getOrElse { UpdateRun(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="JOURNAL_UNREADABLE",message="Workflowjournaal kan niet worden gelezen. Lees opnieuw uit; herhaal geen schrijfactie automatisch.") }
    }
    fun save(run:UpdateRun):UpdateRun = synchronized(LOCK) {
        val out=journal.startWrite()
        try { out.write(run.encode().toByteArray(Charsets.UTF_8));journal.finishWrite(out) }
        catch(error:Exception){journal.failWrite(out);throw error}
        run
    }
    companion object { private val LOCK=Any() }
}

/** Explicit UI/worker contract. It only advances the journal; handoffs remain separate UI actions. */
class UpdateController(context:Context) {
    private val store=UpdateRunStore(context.applicationContext)
    fun load():UpdateRun=store.load()
    fun startOfficial(device:DeviceState,backup:BackupValidation?,target:ObservedUpdateTarget,runId:String=java.util.UUID.randomUUID().toString()):UpdateRun=change {
        require(it.stage in setOf(UpdateStage.IDLE,UpdateStage.PRECHECK_BLOCKED,UpdateStage.COMPLETE,UpdateStage.COMPLETE_REVIEW,UpdateStage.ROLLBACK)) { "ACTIVE_UPDATE_RUN" }
        UpdateCoordinator.startOfficialGuidedRun(device,backup,target,runId=runId)
    }
    fun reconcile(device:DeviceState,newStockBackup:BackupValidation?=null,activeImageSha256:String?=null):UpdateRun=change {
        UpdateCoordinator.reconcile(it,device,newStockBackup,activeImageSha256)
    }
    fun prepareRestore(plan:StockRestorePlan):UpdateRun=change { UpdateCoordinator.restorePrepared(it,plan) }
    fun restoreDispatching(expectedRunId:String?=null,expectedTransactionId:String?=null):UpdateRun=change {
        require((expectedRunId==null || it.id==expectedRunId) && (expectedTransactionId==null || it.restoreTransactionId==expectedTransactionId) && it.stage==UpdateStage.STOCK_CONFIRMATION) { "RESTORE_DISPATCH_RACE" }
        UpdateCoordinator.restoreDispatching(it)
    }
    fun recordRestoreResult(result:StockRestoreResult):UpdateRun=change { UpdateCoordinator.restoreFinished(it,result) }
    fun reportManualRestore():UpdateRun=change { UpdateCoordinator.reportManualStockRestore(it) }
    fun observeStock(bootId:String,slot:String,sha256:String):UpdateRun=change { UpdateCoordinator.observeStockHash(it,bootId,slot,sha256) }
    fun openOfficialOta():UpdateRun=change { UpdateCoordinator.openOfficialOta(it) }
    fun automaticOtaDispatching():UpdateRun=change {UpdateCoordinator.automaticOtaDispatching(it)}
    fun recordMagiskResult(result:MagiskInstallResult):UpdateRun=change {
        UpdateCoordinator.observeMagiskProof(UpdateCoordinator.authenticatedTargetStock(it,result.runId,
            result.packageSha256,result.proof.targetSlot,result.targetStockSha1),result.proof)
    }
    fun automationStopped(expectedRunId:String,code:String):UpdateRun=change {
        if(it.id!=expectedRunId || it.stage in setOf(UpdateStage.IDLE,UpdateStage.COMPLETE,UpdateStage.COMPLETE_REVIEW,UpdateStage.ROLLBACK))it
        else if(!it.criticalSession && it.restoreTransactionId.isBlank() && !it.otaAppliedObserved)UpdateCoordinator.cancelBeforeRestore(it)
        else if(it.stage==UpdateStage.AWAITING_NEW_BOOT)it
        else it.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode=code,presentationKey=null,presentationArgs=emptyList(),
            message="Automation stopped. Observe the device and saved images; no action is replayed and no restart is attempted.")
    }
    fun attachVerifiedPackage(report:FirmwareReport):UpdateRun=change { UpdateCoordinator.attachVerifiedPackage(it,report) }
    fun reportMagiskInstalled():UpdateRun=change { UpdateCoordinator.reportMagiskInstalled(it) }
    fun observeMagiskProof(proof:TargetMagiskProof):UpdateRun=change { UpdateCoordinator.observeMagiskProof(it,proof) }
    fun acknowledgeReboot(explicitConsent:Boolean):UpdateRun=change { UpdateCoordinator.acknowledgeReboot(it,explicitConsent) }
    fun fail(code:String,message:String):UpdateRun=change { UpdateCoordinator.fail(it,code,message) }
    fun cancelBeforeRestore():UpdateRun=change { UpdateCoordinator.cancelBeforeRestore(it) }
    fun retryFinalChecks(device:DeviceState,newStockBackup:BackupValidation?=null,activeImageSha256:String?=null):UpdateRun=change {
        UpdateCoordinator.retryFinalChecks(it,device,newStockBackup,activeImageSha256)
    }
    fun retryMagiskStep(device:DeviceState):UpdateRun=change { UpdateCoordinator.retryMagiskStep(it,device) }
    fun releasePreparation(plan:StockRestorePlan,result:StockRestoreResult,environment:RestoreEnvironment):UpdateRun=change {
        UpdateCoordinator.releasePreparation(it,plan,result,environment)
    }
    private fun change(reduce:(UpdateRun)->UpdateRun):UpdateRun=synchronized(LOCK){store.save(reduce(store.load()))}
    companion object { private val LOCK=Any() }
}
