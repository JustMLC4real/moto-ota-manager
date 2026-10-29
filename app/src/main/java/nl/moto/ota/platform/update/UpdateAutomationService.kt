package nl.moto.ota.platform.update

import android.app.*
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.moto.ota.MainActivity
import nl.moto.ota.R
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.*
import nl.moto.ota.platform.discovery.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class AutomationPhase { IDLE,CHECKING,DOWNLOADING,VERIFYING,PREPARING,RESTORING,APPLYING,PATCHING,REBOOTING,AWAITING_NEW_BOOT,STOPPED }
data class AutomationState(val active:Boolean=false,val phase:AutomationPhase=AutomationPhase.IDLE,
    val downloadedBytes:Long=0,val totalBytes:Long=0,val run:UpdateRun?=null,val errorCode:String?=null,
    val device:DeviceState?=null,val report:FirmwareReport?=null)

/** Volatile dispatch only. Android restarting a service/process never re-creates user consent. */
object UpdateAutomation {
    private val pending=AtomicBoolean(false)
    private val mutableState=MutableStateFlow(AutomationState())
    val state:StateFlow<AutomationState> = mutableState
    fun start(context:Context) {
        check(!mutableState.value.active && pending.compareAndSet(false,true)) {"AUTOMATION_ALREADY_RUNNING"}
        mutableState.value=AutomationState(active=true,phase=AutomationPhase.CHECKING)
        try {context.startForegroundService(Intent(context,UpdateAutomationService::class.java))}
        catch(error:Exception){pending.set(false);mutableState.value=AutomationState(phase=AutomationPhase.STOPPED,errorCode="AUTOMATION_SERVICE_UNAVAILABLE");throw error}
    }
    internal fun consumeStart()=pending.compareAndSet(true,false)
    internal fun hasPending()=pending.get()
    internal fun publish(state:AutomationState){mutableState.value=state}
}

/** One explicit start covers the complete exact-source/exact-target run. There are no
 * per-phase prompts, exported write intents, restart replay or unattended future updates. */
class UpdateAutomationService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job:Job?=null
    private var ownedRunId:String?=null
    private var state=AutomationState()
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(job?.isActive==true){
            if(UpdateAutomation.hasPending())job!!.invokeOnCompletion {
                Handler(Looper.getMainLooper()).post {onStartCommand(null,0,startId)}
            }
            return START_NOT_STICKY
        }
        if(!UpdateAutomation.consumeStart()){stopSelf(startId);return START_NOT_STICKY}
        state=AutomationState(active=true,phase=AutomationPhase.CHECKING)
        ownedRunId=null
        startForeground(NOTICE_ID,notification())
        job=scope.launch {
            val wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MotoOta:completeUpdate")
            wake.acquire(6*60*60*1000L)
            try {withTimeout(6*60*60*1000L){perform()}}
            catch(cancelled:CancellationException){stopWith("AUTOMATION_INTERRUPTED");throw cancelled}
            catch(error:Exception){
                val code=(error as? UpdateSafetyException)?.code ?: error.message?.takeIf {it.matches(Regex("[A-Z][A-Z0-9_]{1,79}"))} ?: "AUTOMATION_STOPPED"
                stopWith(code)
            }finally{
                if(wake.isHeld)wake.release()
                state=state.copy(active=false);UpdateAutomation.publish(state)
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun perform() {
        val updates=UpdateController(this)
        require(updates.load().stage in setOf(UpdateStage.IDLE,UpdateStage.PRECHECK_BLOCKED,UpdateStage.COMPLETE,UpdateStage.COMPLETE_REVIEW,UpdateStage.ROLLBACK)) {"ACTIVE_UPDATE_RUN"}
        val online=CdsController(this)
        // The start button's immediately visible disclosure includes these network fields.
        online.setConsent(true)
        require(getSharedPreferences("user_choices",0).edit().putBoolean("root_reads_allowed",true).commit()) {"READ_CONSENT_STORAGE_FAILED"}
        val inspector=DeviceInspector()
        var inspection=inspector.inspect(this,true)
        var source=inspection.toDeviceState()
        require(source.rootChecked && source.rootGranted) {"ROOT_DENIED"}
        require(source.manufacturer.equals("motorola",true) && source.identityConflicts.isEmpty() && DeviceCapabilities.knownRestoreRuntime(source)) {"AUTOMATION_PROFILE_UNREVIEWED"}
        require(source.engine==EngineState.IDLE && DeviceCapabilities.snapshotSafe(source)) {"ENGINE_OR_SNAPSHOT_BUSY"}
        publish(AutomationPhase.CHECKING,device=source)
        val offer=online.check(inspection,resources.configuration.locales[0].language)
        val reference=offer.packageReference?:throw UpdateSafetyException("CDS_${offer.status.name}")
        val selection=online.selectionProof()?:throw UpdateSafetyException("MOTOROLA_LIVE_SELECTION_REQUIRED")
        val authority=RunAuthorizationStore(this)
        val runId=UUID.randomUUID().toString()
        // Start the six-hour scope before any network download, not after waiting hours.
        authority.create(runId,source,ObservedUpdateTarget(offer.targetBuild,offer.targetFingerprint),true)
        publish(AutomationPhase.DOWNLOADING,device=source)
        val live=VerifiedLivePackage.download(this,source,reference,selection,online::consentGranted){bytes,total->
            state=state.copy(downloadedBytes=bytes,totalBytes=total);UpdateAutomation.publish(state)
        }
        publish(AutomationPhase.VERIFYING,report=live.report)
        inspection=inspector.inspect(this,true);source=inspection.toDeviceState()
        require(live.boundTo(source)) {"RUN_SOURCE_CHANGED"}
        FirmwareVault(this).recordVerified(live.file,live.report,source)
        inspection=inspector.inspect(this,true);source=inspection.toDeviceState()
        require(live.boundTo(source)) {"RUN_SOURCE_CHANGED"}
        val association=inspection.backupAssociation
        val backup=inspection.backupInventory.firstOrNull {it.provenanceVerified && it.sha1==association?.runtimeSha1}?.validation
        val manifest=PayloadManifest.inspect(live.file)
        val blockers=AutomaticUpdatePolicy.blockers(source,live.report,manifest,backup,live.boundTo(source),selection.targetFingerprint)
        if(blockers.isNotEmpty())throw UpdateSafetyException(blockers.first().code)
        var run=updates.startOfficial(source,backup,ObservedUpdateTarget(live.report.targetBuild,live.report.targetFingerprint,live.report.sha256,true),runId)
        require(run.id==runId && run.stage==UpdateStage.STOCK_PREPARATION) {"AUTOMATION_PRECHECK_FAILED"}
        ownedRunId=runId
        authority.pinPackage(live.report.sha256,live.report.targetBuild,live.report.targetFingerprint,true)
        publish(AutomationPhase.PREPARING,run=run,device=source)
        val ota=DirectOtaController(this)
        val magisk=MagiskInactiveController(this)
        // All unavailable-tool, signature, payload extraction and binder-read failures
        // must be discovered before changing the running source image.
        ota.prepare(run,live.report,live.file)
        magisk.prepare(run,live.report,live.file)
        inspection=inspector.inspect(this,true);source=inspection.toDeviceState()
        require(live.boundTo(source)) {"RUN_SOURCE_CHANGED"}
        val currentBackup=inspection.backupInventory.firstOrNull {it.provenanceVerified && it.sha256==backup?.sha256}?.validation
        val changed=AutomaticUpdatePolicy.blockers(source,live.report,manifest,currentBackup,live.boundTo(source),selection.targetFingerprint)
        if(changed.isNotEmpty())throw UpdateSafetyException(changed.first().code)
        run=updates.reconcile(source,currentBackup,UpdateObservations.activeImageSha256(inspection,run.stockPartitionType))
        val restore=StockRestoreController(this)
        val plan=restore.prepare(run,inspection)
        run=updates.reconcile(ota.observe(updates.load()))
        currentCoroutineContext().ensureActive()
        publish(AutomationPhase.RESTORING,run=run)
        val restored=restore.confirmAndExecute(plan,true,true,authority.claim(RunAction.RESTORE_STOCK).second)
        require(restored.stockRestored && restored.checkpoint==RestoreCheckpoint.STOCK_VERIFIED) {restored.errorCode?:"RESTORE_NOT_PROVEN"}
        run=updates.reconcile(ota.observe(updates.load()),activeImageSha256=restored.observedSha256)
        run=updates.openOfficialOta()
        require(run.stage==UpdateStage.AWAITING_OFFICIAL_OTA) {"AUTOMATION_SOURCE_UNPROVEN"}
        currentCoroutineContext().ensureActive()
        val apply=authority.claim(RunAction.APPLY_OTA).second
        run=updates.automaticOtaDispatching()
        publish(AutomationPhase.APPLYING,run=run)
        val applied=ota.apply(run,apply)
        run=updates.reconcile(applied)
        require(run.otaAppliedObserved && run.stage==UpdateStage.MAGISK_REQUIRED) {"OTA_APPLICATION_NOT_PROVEN"}
        publish(AutomationPhase.PATCHING,run=run)
        magisk.prepareTarget(run)
        run=updates.reconcile(ota.observe(updates.load()))
        currentCoroutineContext().ensureActive()
        val result=magisk.install(run,authority.claim(RunAction.PATCH_INACTIVE_MAGISK).second)
        run=updates.reconcile(ota.observe(updates.load()))
        run=updates.recordMagiskResult(result)
        require(run.stage==UpdateStage.REBOOT_BY_SYSTEM_UPDATER && run.magiskEvidence==StepEvidence.CRYPTOGRAPHIC_IMAGE) {"MAGISK_TARGET_NOT_PROVEN"}
        run=updates.reconcile(ota.observe(run))
        currentCoroutineContext().ensureActive()
        publish(AutomationPhase.REBOOTING,run=run)
        ControlledRebootController(this).reboot(run,authority.claim(RunAction.REBOOT).second)
        publish(AutomationPhase.AWAITING_NEW_BOOT,run=updates.load())
    }
    private fun publish(phase:AutomationPhase,run:UpdateRun?=state.run,device:DeviceState?=state.device,report:FirmwareReport?=state.report) {
        state=state.copy(phase=phase,run=run,device=device,report=report)
        UpdateAutomation.publish(state)
        getSystemService(NotificationManager::class.java).notify(NOTICE_ID,notification())
    }
    private fun stopWith(code:String) {
        val updates=UpdateController(this)
        val before=updates.load()
        if(ownedRunId==before.id && before.stage==UpdateStage.STOCK_PREPARATION && before.restoreTransactionId.isBlank())
            runCatching {DirectOtaController(this).discardPrepared(before)}
        val run=runCatching {ownedRunId?.let {updates.automationStopped(it,code)}?:updates.load()}.getOrElse {updates.load()}
        state=state.copy(phase=AutomationPhase.STOPPED,errorCode=code,run=run)
        UpdateAutomation.publish(state)
    }
    private fun notification():Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.ui_automation_channel),NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,NOTICE_ID,Intent(this,MainActivity::class.java).putExtra("moto_open_tab","update"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_moto_monitor).setContentTitle(getString(R.string.ui_app_name))
            .setContentText(getString(phaseResource(state.phase))).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_PROGRESS).build()
    }
    override fun onTimeout(startId:Int,fgsType:Int){job?.cancel();stopSelf(startId)}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object {
        private const val CHANNEL="moto_complete_update"
        private const val NOTICE_ID=0x4d4f5404
        fun phaseResource(phase:AutomationPhase)=when(phase){
            AutomationPhase.CHECKING->R.string.ui_automation_checking;AutomationPhase.DOWNLOADING->R.string.ui_automation_downloading
            AutomationPhase.VERIFYING->R.string.ui_automation_verifying;AutomationPhase.PREPARING->R.string.ui_automation_preparing
            AutomationPhase.RESTORING->R.string.ui_automation_restoring;AutomationPhase.APPLYING->R.string.ui_automation_applying
            AutomationPhase.PATCHING->R.string.ui_automation_patching;AutomationPhase.REBOOTING->R.string.ui_automation_rebooting
            AutomationPhase.AWAITING_NEW_BOOT->R.string.ui_automation_awaiting;AutomationPhase.STOPPED->R.string.ui_automation_stopped
            AutomationPhase.IDLE->R.string.ui_automation_idle
        }
    }
}
