package nl.moto.ota

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.core.staging.*
import nl.moto.ota.platform.*
import nl.moto.ota.platform.download.DownloadController
import nl.moto.ota.platform.monitoring.MonitorController
import nl.moto.ota.platform.update.StockRestoreController
import nl.moto.ota.platform.update.UpdateController
import nl.moto.ota.platform.update.UpdateAutomation
import nl.moto.ota.platform.update.AutomationState
import nl.moto.ota.platform.update.AutomationPhase
import nl.moto.ota.platform.update.UpdateAutomationService
import nl.moto.ota.platform.update.RunAuthorizationStore
import nl.moto.ota.platform.staging.MotorolaPackageStager
import nl.moto.ota.platform.importer.MotorolaPackageImporter
import nl.moto.ota.platform.importer.ExistingMotorolaPackage
import nl.moto.ota.platform.discovery.CdsController
import nl.moto.ota.platform.discovery.CdsState
import nl.moto.ota.core.discovery.CdsStatus
import nl.moto.ota.ui.UiMessage
import nl.moto.ota.ui.uiMessage
import java.io.File
import java.security.MessageDigest

class MotoViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val updates = UpdateController(app)
    private val restores = StockRestoreController(app)
    private val files = FirmwareFiles(app)
    private val vault = FirmwareVault(app)
    private val downloads = DownloadController(app)
    private val monitoring = MonitorController(app)
    private val onlineUpdates = CdsController(app)
    private val manualAuthority = RunAuthorizationStore(app)
    private val preferences = app.getSharedPreferences("user_choices", 0)
    private var selectedPackage: File? = null
    private var sandbox: SandboxUpdateSession? = null
    var sandboxActive by mutableStateOf(false); private set
    var device by mutableStateOf(DeviceState()); private set
    var inspection by mutableStateOf<Inspection?>(null); private set
    var firmware by mutableStateOf<FirmwareReport?>(null); private set
    var backup by mutableStateOf<BackupValidation?>(null); private set
    var workflow by mutableStateOf(updates.load()); private set
    var restorePlan by mutableStateOf<StockRestorePlan?>(null); private set
    var downloadJob by mutableStateOf<DownloadJob?>(null); private set
    var monitorState by mutableStateOf(MonitorState()); private set
    var notificationsAllowed by mutableStateOf(monitoring.notificationsAllowed()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<UiMessage>(uiMessage(R.string.ui_message_initial)); private set
    var online by mutableStateOf(CdsState(onlineUpdates.consentGranted())); private set
    var automationActive by mutableStateOf(false); private set
    var automation by mutableStateOf(AutomationState()); private set
    var demo by mutableStateOf(false); private set
    var verificationProgress by mutableStateOf<Int?>(null); private set
    var stagingReceipt by mutableStateOf<MotorolaStageReceipt?>(null); private set
    var existingMotorolaPackages by mutableStateOf<List<ExistingMotorolaPackage>>(emptyList()); private set
    val readiness get() = Readiness.evaluate(device, backup, firmware?.packageBytes ?: 0)
    val capabilities get() = DeviceCapabilities.evaluate(device, backup, inspection?.backupAssociation,
        officialUpdaterAvailable = OfficialApps.systemUpdaterIntent(app.packageManager) != null)
    val discovery get() = inspection?.otaMetadata.let { m -> FirmwareDiscovery.assess(DiscoveryEvidence(
        localDownloadUrl=m?.downloadUrl, localTargetBuild=m?.targetBuild.orEmpty(), localContentKind=m?.contentKind ?: LocalContentKind.UNKNOWN,
        localMetadataObserved=m?.observed == true), device.build) }
    val candidateAvailable get() = online.result?.packageReference?.contentKind == LocalContentKind.OTA_ZIP || discovery.canDownloadCandidate
    val observedTargetBuild get() = online.result?.targetBuild?.takeIf { it.isNotBlank() && it != device.build }
        ?: firmware?.targetBuild?.takeIf { it != device.build }
        ?: inspection?.otaMetadata?.targetBuild?.takeIf { it.isNotBlank() && it != device.build }.orEmpty()
    val report get() = Privacy.diagnosticReport(device, inspection?.probes.orEmpty().mapValues { (_, p) ->
        when { p.timedOut -> "TIMEOUT"; p.truncated -> "TRUNCATED"; p.successful -> "OK"; else -> "UNAVAILABLE" }
    }, inspection?.backupInventory?.size ?: 0, workflow.stage.name, app.resources.configuration.locales[0].language) +
        "\nstock_restore_evidence=${workflow.stockRestoreEvidence}\nmagisk_evidence=${workflow.magiskEvidence}\n" +
        "download_status=${downloadJob?.status ?: "NONE"}\nmonitor_outcome=${monitorState.lastOutcome}\n"

    init {
        viewModelScope.launch { downloads.observe().collect { downloadJob = it } }
        viewModelScope.launch { monitoring.state.collect { monitorState = it } }
        viewModelScope.launch { onlineUpdates.state.collect { online = it } }
        viewModelScope.launch { UpdateAutomation.state.collect { state ->
            val wasActive=automationActive
            automation=state;automationActive=state.active
            online=online.copy(consentGranted=onlineUpdates.consentGranted())
            if(state.active)busy=true else if(wasActive)busy=false
            state.run?.let {workflow=it}
            state.device?.let {device=it}
            state.report?.let {firmware=it}
            if(state.errorCode!=null)message=UiMessage.Failure(state.errorCode,workflow.takeIf {it.criticalSession})
            else if(state.phase!=AutomationPhase.IDLE)message=uiMessage(UpdateAutomationService.phaseResource(state.phase))
        } }
        viewModelScope.launch { runCatching { monitoring.restoreSchedule() } }
        viewModelScope.launch {
            while(isActive) {
                delay(30_000)
                if(!busy && !demo && !automationActive && preferences.getBoolean("root_reads_allowed",false) &&
                    workflow.stage in setOf(UpdateStage.AWAITING_NEW_BOOT,UpdateStage.TARGET_BOOTED,UpdateStage.MERGING,UpdateStage.FINAL_CHECKS))scan(true)
            }
        }
        scan(false)
    }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (demo) {
                    workflow = sandbox?.run ?: workflow
                    message = uiMessage(R.string.ui_message_simulation_failed)
                } else {
                    workflow = withContext(Dispatchers.IO) { updates.load() }
                    val code = when (error) {
                        is StageFailure -> error.code.name
                        is UpdateSafetyException -> error.code
                        else -> error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,79}")) }
                    }
                    message = UiMessage.Failure(code, workflow.takeIf { it.criticalSession })
                }
            } finally { busy = automationActive; verificationProgress = null }
        }
    }
    /** Re-entry repeats only authorized reads; a writer is never replayed. */
    fun onResume() {
        notificationsAllowed = monitoring.notificationsAllowed()
        if (!busy && !demo) scan(preferences.getBoolean("root_reads_allowed", false))
    }
    fun scan(root: Boolean) = work {
        if (root) preferences.edit().putBoolean("root_reads_allowed", true).apply()
        demo = false
        sandbox = null; sandboxActive = false
        refreshInspection(root)
        message = uiMessage(if (root && !device.rootGranted) R.string.ui_message_root_denied else R.string.ui_message_checked)
    }
    private suspend fun refreshInspection(root: Boolean) {
        val observed = DeviceInspector().inspect(app, root)
        val previous = device
        inspection = observed; device = observed.toDeviceState()
        if (previous.build != device.build || previous.fingerprint != device.fingerprint || previous.incrementalBuild != device.incrementalBuild ||
            previous.model != device.model || previous.channel != device.channel || previous.bootId != device.bootId) {
            firmware = null; backup = null; restorePlan = null; existingMotorolaPackages = emptyList()
        }
        backup = observed.backupInventory.firstOrNull { it.provenanceVerified }?.validation ?: withContext(Dispatchers.IO) { validateSavedBackup() }
        workflow = withContext(Dispatchers.IO) {
            if (root) updates.reconcile(device, backup, UpdateObservations.activeImageSha256(observed, updates.load().stockPartitionType)) else updates.load()
        }
        stagingReceipt = nl.moto.ota.platform.staging.MotorolaPackageStager(app).lastReceipt(workflow)
    }
    private fun validateSavedBackup(): BackupValidation? {
        val image = File(app.filesDir, "selected-backup.bin").takeIf { it.isFile } ?: return null
        val anchors = DeviceTrustAnchors.spkiSha256()
        val refs = listOfNotNull(VerifiedStockCatalog.referenceFor(device, anchors)) + vault.referencesFor(device, anchors)
        val measured=image.inputStream().buffered().use {input->
            input.mark(2);val gzip=input.read()==0x1f && input.read()==0x8b;input.reset()
            runCatching {BootImageStreams.measure(input,gzip)}.getOrNull()
        } ?: return BackupValidation(issues=listOf(CheckResult("IMAGE_CORRUPT","IMAGE_CORRUPT")))
        val type=measured.partitionType
        val ref=refs.firstOrNull {it.partition==type && it.bytes==measured.bytes && it.sha256==measured.sha256}
        val bytes = inspection?.probes?.get(ReadOnlyProbe.PARTITIONS.name)?.takeIf { it.successful }?.stdout.orEmpty().lineSequence()
            .singleOrNull { it.startsWith("$type${device.slot}=") }?.substringAfter('=')?.trim()?.toLongOrNull() ?: -1L
        return image.inputStream().buffered().use { input ->
            input.mark(2); val gzip = input.read() == 0x1f && input.read() == 0x8b; input.reset()
            BackupValidator.inspect(input, gzip, bytes, device, ref, type)
        }
    }
    fun showDemo() {
        if (busy) return
        demo = true; inspection = null; firmware = null; backup = null; restorePlan = null; existingMotorolaPackages = emptyList()
        device = DeviceState(manufacturer="motorola", model="XT2607-1", device="merc", channel="reteu", androidVersion="17",
            build="DEMO-TARGET", securityPatch="2026-10-01", slot="_b", rootGranted=true, magiskVersion="31.1", magiskCode=31100,
            virtualAb=true, snapshot=SnapshotState.MERGING, engine=EngineState.IDLE, batteryPercent=82, freeBytes=40L*1024*1024*1024,
            capturedAtMillis=System.currentTimeMillis(), simulated=true)
        workflow = UpdateRun(stage=UpdateStage.MERGING, simulated=true, message="Gesimuleerde merge. Geen echte workflow opgeslagen.")
        sandbox = null; sandboxActive = false
        message = uiMessage(R.string.ui_message_simulation_demo)
    }
    fun startFullSimulation() = work {
        val session = withContext(Dispatchers.IO) {
            SandboxUpdateSession.create(java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "moto-ota-sandbox-").toFile())
        }
        sandbox = session; sandboxActive = true; demo = true; inspection = null; firmware = null; backup = null; restorePlan = null
        device = session.device; workflow = session.run
        message = uiMessage(R.string.ui_message_simulation_started)
    }
    fun advanceSimulation() = work {
        val session = requireNotNull(sandbox); require(demo && sandboxActive && session.run.simulated)
        workflow = withContext(Dispatchers.IO) { when (session.run.stage) {
            UpdateStage.STOCK_PREPARATION -> session.restoreStock()
            UpdateStage.STOCK_RESTORED -> session.simulateOfficialOta()
            UpdateStage.MAGISK_REQUIRED -> session.simulateMagiskHandoff()
            UpdateStage.MAGISK_REPORTED_UNVERIFIED -> session.simulateRebootAndMerge()
            UpdateStage.MERGING -> session.simulateMergeFinished()
            else -> session.run
        } }
        device = session.device
        message = UiMessage.Workflow(workflow, simulation = true)
    }
    fun importOta(uri: Uri) = work {
        require(!demo); firmware = null
        message = uiMessage(R.string.ui_message_importing_package)
        downloads.withPausedSelection { files.import(uri) }; downloads.clearCompleted(); inspectPinned()
    }
    fun inspectSaved() = work { require(!demo); downloads.pause(); inspectPinned() }
    fun discoverExistingPackages() = work {
        require(!demo)
        refreshInspection(true)
        require(device.exactProfileMatch && device.rootChecked && device.rootGranted)
        existingMotorolaPackages = MotorolaPackageImporter(app).discover()
        message = uiMessage(if (existingMotorolaPackages.isEmpty()) R.string.ui_message_existing_empty else R.string.ui_message_existing_found)
    }
    fun importExistingPackage(id: String) = work {
        require(!demo && existingMotorolaPackages.any { it.id == id })
        firmware = null
        message = uiMessage(R.string.ui_message_importing_existing)
        MotorolaPackageImporter(app).import(id) // Internally serializes its final publication with downloads.
        downloads.clearCompleted(); inspectPinned()
    }
    private suspend fun inspectPinned() {
        val pinned = withContext(Dispatchers.IO) { vault.pinSelected() }; selectedPackage = pinned
        val fps = inspection?.properties.orEmpty().filterKeys { it.endsWith(".fingerprint") }.values.toSet()
        firmware = withContext(Dispatchers.IO) { FirmwareValidator.inspect(pinned, device, fps) }
        message = uiMessage(R.string.ui_message_package_inspected)
    }
    fun download(wifiOnly: Boolean = true) = work {
        require(!demo && candidateAvailable)
        val offered = online.result?.packageReference?.takeIf { it.contentKind == LocalContentKind.OTA_ZIP }
        if (offered != null) {
            downloads.enqueue(offered.url, device, offered.targetBuild, wifiOnly = wifiOnly,
                expectedBytes = offered.expectedBytes, expectedSha256 = offered.sha256)
        } else {
            val m = requireNotNull(inspection?.otaMetadata)
            downloads.enqueue(requireNotNull(m.downloadUrl), device, requireNotNull(m.targetBuild), wifiOnly=wifiOnly)
        }
        message = uiMessage(R.string.ui_message_download_queued)
    }
    fun pauseDownload() = work { require(!demo); downloads.pause() }
    fun resumeDownload() = work { require(!demo); downloads.resume() }
    fun cancelDownload() = work { require(!demo); downloads.cancel() }
    fun setOnlineConsent(granted: Boolean) = work {
        require(!demo)
        withContext(Dispatchers.IO) { onlineUpdates.setConsent(granted) }
        if (granted) {
            refreshInspection(true)
            onlineUpdates.check(requireNotNull(inspection), app.resources.configuration.locales[0].language)
        }
    }
    fun checkOnlineUpdates() = work {
        require(!demo)
        refreshInspection(true)
        onlineUpdates.check(requireNotNull(inspection), app.resources.configuration.locales[0].language)
    }
    fun simulateOneTap() = work {
        val session = withContext(Dispatchers.IO) {
            SandboxUpdateSession.create(java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "moto-ota-sandbox-").toFile())
        }
        sandbox = session; sandboxActive = true; demo = true
        inspection = null; firmware = null; backup = null; restorePlan = null
        workflow = withContext(Dispatchers.IO) { session.runAll() }
        device = session.device
        message = UiMessage.Workflow(workflow, simulation = true)
    }
    fun startOneTapUpdate() {
        if(busy || demo || automationActive)return
        try {
            require(capabilities.motorola) {"MOTOROLA_REQUIRED"}
            automationActive=true;busy=true
            UpdateAutomation.start(app)
        }catch(error:Exception){
            automationActive=false;busy=false
            message=UiMessage.Failure(error.message?.takeIf {it.matches(Regex("[A-Z][A-Z0-9_]{1,79}"))})
        }
    }
    fun verify() = work {
        val original = requireNotNull(firmware); val pinned = requireNotNull(selectedPackage)
        require(original.structureValid && original.payloadHashValid && !demo)
        verificationProgress = 0
        val anchors = withContext(Dispatchers.IO) { DeviceTrustAnchors.spkiSha256() }
        val signed = files.verify(pinned) { p -> viewModelScope.launch { verificationProgress = p } }
        val verified = signed && withContext(Dispatchers.IO) {
            anchors.isNotEmpty() && anchors == DeviceTrustAnchors.spkiSha256() && fileSha256(pinned) == original.sha256
        }
        val result = original.copy(signatureVerified=verified); firmware = result
        val proofSaved = verified && withContext(Dispatchers.IO) { runCatching {
            vault.recordVerified(pinned, result, device) > 0
        }.getOrDefault(false) }
        if (verified && workflow.id.isNotBlank()) {
            refreshInspection(true)
            workflow = withContext(Dispatchers.IO) { updates.attachVerifiedPackage(result) }
        }
        message = uiMessage(if (!verified) R.string.ui_message_signature_failed else if (proofSaved)
            R.string.ui_message_signature_with_stock else R.string.ui_message_signature_only)
    }
    fun importBackup(uri: Uri) = work {
        require(!demo); files.import(uri, backup=true)
        backup = withContext(Dispatchers.IO) { validateSavedBackup() }
        message = uiMessage(if (backup?.usableAsStock == true) R.string.ui_message_stock_verified else R.string.ui_message_stock_unproven)
    }
    fun startGuided(targetBuild: String) = work {
        require(!demo && targetBuild.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        preferences.edit().putBoolean("root_reads_allowed", true).apply()
        val f = firmware?.takeIf { it.targetBuild == targetBuild }
        refreshInspection(true)
        workflow = withContext(Dispatchers.IO) {
            val target=ObservedUpdateTarget(targetBuild,f?.targetFingerprint.orEmpty(),f?.sha256.orEmpty(),f?.signatureVerified==true)
            val id=java.util.UUID.randomUUID().toString()
            if(target.packageVerified)manualAuthority.create(id,device,target,true)
            updates.startOfficial(device,backup,target,id).also {run->
                if(target.packageVerified && run.id==id)manualAuthority.pinPackage(target.packageSha256,target.build,target.declaredFingerprint,true)
            }
        }
        message = UiMessage.Workflow(workflow)
    }
    fun prepareRestore() = work {
        require(!demo); refreshInspection(true)
        val observed = requireNotNull(inspection)
        val imported = File(app.filesDir, "selected-backup.bin").takeIf {
            it.isFile && backup?.usableAsStock == true && observed.backupInventory.none { c -> c.provenanceVerified && c.sha256 == backup?.sha256 }
        }
        restorePlan = restores.prepare(workflow, observed, imported)
        workflow = withContext(Dispatchers.IO) { updates.load() }
        message = uiMessage(R.string.ui_message_restore_prepared)
    }
    fun dismissRestorePlan() { restorePlan = null }
    fun confirmRestore(noRebootAcknowledged: Boolean) = work {
        require(!demo); val plan = requireNotNull(restorePlan); restorePlan = null
        val operation=withContext(Dispatchers.IO){manualAuthority.claim(RunAction.RESTORE_STOCK).second}
        restores.confirmAndExecute(plan, explicitConfirmation=true, noRebootAcknowledged=noRebootAcknowledged,operation=operation)
        workflow = withContext(Dispatchers.IO) { updates.load() }; message = UiMessage.Workflow(workflow)
    }
    fun reportStock() = work {
        require(!demo); withContext(Dispatchers.IO) { updates.reportManualRestore() }; refreshInspection(true)
        val hash = inspection?.let(UpdateObservations::activeImageSha256)
        if (hash != null) workflow = withContext(Dispatchers.IO) { updates.observeStock(device.bootId, device.slot, hash) }
        message = UiMessage.Workflow(workflow)
    }
    fun recoverRestore() = work {
        require(!demo)
        val plan = withContext(Dispatchers.IO) { restores.pendingPlan(updates.load()) }
            ?: throw UpdateSafetyException("NO_PENDING_RESTORE_PLAN")
        restores.recover(plan) // Strictly observation-only; never refreshes or replays consent.
        refreshInspection(true); message = UiMessage.Workflow(workflow)
    }
    fun releasePreparation() = work {
        require(!demo)
        val plan = withContext(Dispatchers.IO) { restores.pendingPlan(updates.load()) }
            ?: throw UpdateSafetyException("NO_PENDING_RESTORE_PLAN")
        workflow = restores.releasePreparation(plan); restorePlan = null; message = UiMessage.Workflow(workflow)
    }
    fun stagePackage(automaticRecognitionAcknowledged: Boolean) = work {
        require(!demo)
        val report = requireNotNull(firmware); val pinned = requireNotNull(selectedPackage)
        refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.attachVerifiedPackage(report) }
        message = uiMessage(R.string.ui_message_staging_verifying)
        stagingReceipt = MotorolaPackageStager(app).stage(pinned, report, workflow, true, automaticRecognitionAcknowledged)
        message = uiMessage(R.string.ui_message_package_staged)
    }
    fun cancelPreparation() = work {
        require(!demo); restorePlan = null
        workflow = withContext(Dispatchers.IO) { updates.cancelBeforeRestore() }; message = UiMessage.Workflow(workflow)
    }
    fun retryFinalChecks() = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.retryFinalChecks(device, backup, inspection?.let(UpdateObservations::activeImageSha256)) }
        message = UiMessage.Workflow(workflow)
    }
    fun markOfficialOtaHandoff(afterSaved: () -> Unit) = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.openOfficialOta() }
        if (workflow.stage == UpdateStage.AWAITING_OFFICIAL_OTA) afterSaved()
        message = UiMessage.Workflow(workflow)
    }
    fun reportMagisk() = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.reportMagiskInstalled() }; message = UiMessage.Workflow(workflow)
    }
    fun reportMagiskFailure() = work {
        require(!demo); refreshInspection(true)
        require(workflow.stage in setOf(UpdateStage.MAGISK_REQUIRED, UpdateStage.MAGISK_REPORTED_UNVERIFIED) && device.engine == EngineState.UPDATED_NEED_REBOOT)
        workflow = withContext(Dispatchers.IO) { updates.fail("MAGISK_INACTIVE_FAILED", "Je hebt gemeld dat Magisk op de doelslot niet is afgerond. Herstart niet. Controleer de officiële Magisk-log en exporteer de diagnose. Er wordt niets automatisch herhaald.") }
        message = UiMessage.Workflow(workflow)
    }
    fun retryMagiskStep() = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.retryMagiskStep(device) }; message = UiMessage.Workflow(workflow)
    }
    fun acknowledgeReboot(afterSaved: () -> Unit) = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.acknowledgeReboot(true) }
        if (workflow.stage == UpdateStage.AWAITING_NEW_BOOT) afterSaved()
        message = UiMessage.Workflow(workflow)
    }
    fun setMonitoring(enabled: Boolean) = work {
        require(!demo)
        if (enabled) monitoring.enable() else monitoring.disable()
        notificationsAllowed = monitoring.notificationsAllowed()
        message = uiMessage(if (enabled) R.string.ui_message_monitor_on else R.string.ui_message_monitor_off)
    }
    fun checkMonitoringNow() = work { require(!demo); monitoring.checkNow() }
    fun export(uri: Uri) = work {
        val text = report
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) } }
        message = uiMessage(R.string.ui_message_report_exported)
    }
    fun exportRecoveryBackup(uri: Uri) = work {
        require(!demo)
        withContext(Dispatchers.IO) {
            val run = updates.load()
            val plan = requireNotNull(restores.pendingPlan(run))
            require(plan.transactionId.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
            require(plan.previousImageSha256==run.patchedBackupSha256)
            requireNotNull(app.contentResolver.openOutputStream(uri)).use {output->restores.exportPatchedBackup(plan,output);output.flush()}
        }
        message = uiMessage(R.string.ui_message_backup_exported)
    }
    private fun fileSha256(file: File): String = file.inputStream().buffered().use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024*1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
