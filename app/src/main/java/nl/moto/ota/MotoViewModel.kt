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
import nl.moto.ota.platform.staging.MotorolaPackageStager
import nl.moto.ota.platform.importer.MotorolaPackageImporter
import nl.moto.ota.platform.importer.ExistingMotorolaPackage
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
    var message by mutableStateOf("Start een uitlezende controle."); private set
    var demo by mutableStateOf(false); private set
    var verificationProgress by mutableStateOf<Int?>(null); private set
    var stagingReceipt by mutableStateOf<MotorolaStageReceipt?>(null); private set
    var existingMotorolaPackages by mutableStateOf<List<ExistingMotorolaPackage>>(emptyList()); private set
    val readiness get() = Readiness.evaluate(device, backup, firmware?.packageBytes ?: 0)
    val discovery get() = inspection?.otaMetadata.let { m -> FirmwareDiscovery.assess(DiscoveryEvidence(
        localDownloadUrl=m?.downloadUrl, localTargetBuild=m?.targetBuild.orEmpty(), localContentKind=m?.contentKind ?: LocalContentKind.UNKNOWN,
        localMetadataObserved=m?.observed == true), device.build) }
    val candidateAvailable get() = discovery.canDownloadCandidate
    val observedTargetBuild get() = firmware?.targetBuild?.takeIf { it != device.build }
        ?: inspection?.otaMetadata?.targetBuild?.takeIf { it.isNotBlank() && it != device.build }.orEmpty()
    val report get() = Privacy.diagnosticReport(device, inspection?.probes.orEmpty().mapValues { (_, p) ->
        when { p.timedOut -> "TIMEOUT"; p.truncated -> "TRUNCATED"; p.successful -> "OK"; else -> "UNAVAILABLE" }
    }, inspection?.backupInventory?.size ?: 0, workflow.stage.name) +
        "\nstock_restore_evidence=${workflow.stockRestoreEvidence}\nmagisk_evidence=${workflow.magiskEvidence}\n" +
        "download_status=${downloadJob?.status ?: "NONE"}\nmonitor_outcome=${monitorState.lastOutcome}\n"

    init {
        viewModelScope.launch { downloads.observe().collect { downloadJob = it } }
        viewModelScope.launch { monitoring.state.collect { monitorState = it } }
        viewModelScope.launch { runCatching { monitoring.restoreSchedule() } }
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
                    message = "SIMULATIE · de teststap is gestopt. Je echte workflow is niet aangepast. Start desgewenst een nieuwe simulatie."
                } else {
                    workflow = withContext(Dispatchers.IO) { updates.load() }
                    val code = when (error) {
                        is StageFailure -> error.code.name
                        is UpdateSafetyException -> error.code
                        else -> error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,79}")) }
                    }
                    message = (code?.let { "Actie gestopt ($it). " }.orEmpty()) +
                        (if (workflow.criticalSession) workflow.message + " " else "Controleer de diagnose en lees opnieuw uit. ") +
                        "Een onderbroken schrijfactie wordt nooit automatisch herhaald."
                }
            } finally { busy = false; verificationProgress = null }
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
        message = if (root && !device.rootGranted) "Geen roottoegang. Verleen deze app toegang in Magisk → Superuser en probeer opnieuw."
            else "Controle voltooid. Alleen actuele waarnemingen kunnen voortgang bevestigen."
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
            if (root) updates.reconcile(device, backup, UpdateObservations.activeImageSha256(observed)) else updates.load()
        }
        stagingReceipt = nl.moto.ota.platform.staging.MotorolaPackageStager(app).lastReceipt(workflow)
    }
    private fun validateSavedBackup(): BackupValidation? {
        val image = File(app.filesDir, "selected-backup.bin").takeIf { it.isFile } ?: return null
        val anchors = DeviceTrustAnchors.spkiSha256()
        val ref = VerifiedStockCatalog.referenceFor(device, anchors) ?: vault.referenceFor(device, anchors)
        val bytes = inspection?.probes?.get(ReadOnlyProbe.PARTITIONS.name)?.takeIf { it.successful }?.stdout.orEmpty().lineSequence()
            .singleOrNull { it.startsWith("init_boot${device.slot}=") }?.substringAfter('=')?.trim()?.toLongOrNull() ?: -1L
        return image.inputStream().buffered().use { input ->
            input.mark(2); val gzip = input.read() == 0x1f && input.read() == 0x8b; input.reset()
            BackupValidator.inspect(input, gzip, bytes, device, ref)
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
        message = "SIMULATIE · deze toestelwaarden zijn verzonnen. Je echte workflow wordt niet gewijzigd."
    }
    fun startFullSimulation() = work {
        val session = withContext(Dispatchers.IO) {
            SandboxUpdateSession.create(java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "moto-ota-sandbox-").toFile())
        }
        sandbox = session; sandboxActive = true; demo = true; inspection = null; firmware = null; backup = null; restorePlan = null
        device = session.device; workflow = session.run
        message = "SIMULATIE · herstel werkt uitsluitend op kleine bestanden in de appcache. OTA, Magisk, herstart en merge worden gesimuleerd."
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
        message = "SIMULATIE · ${workflow.message}"
    }
    fun importOta(uri: Uri) = work {
        require(!demo); firmware = null
        message = "Pakket lokaal kopiëren en volledig hashen… Dit kan enkele minuten duren."
        downloads.withPausedSelection { files.import(uri) }; downloads.clearCompleted(); inspectPinned()
    }
    fun inspectSaved() = work { require(!demo); downloads.pause(); inspectPinned() }
    fun discoverExistingPackages() = work {
        require(!demo)
        refreshInspection(true)
        require(device.exactProfileMatch && device.rootChecked && device.rootGranted)
        existingMotorolaPackages = MotorolaPackageImporter(app).discover()
        message = if (existingMotorolaPackages.isEmpty()) "Geen volledige leesbare ZIP aangetroffen in Motorola's eigen bestandsmap. Dit bewijst geen ontbreken van een streaming-OTA of nieuw aanbod."
            else "Bestaande Motorola-bestanden gevonden. Selecteer een kandidaat om lokaal te kopiëren en te onderzoeken; de bron blijft ongewijzigd."
    }
    fun importExistingPackage(id: String) = work {
        require(!demo && existingMotorolaPackages.any { it.id == id })
        firmware = null
        message = "Bestaand Motorola-pakket uitsluitend lezen en lokaal kopiëren…"
        MotorolaPackageImporter(app).import(id) // Internally serializes its final publication with downloads.
        downloads.clearCompleted(); inspectPinned()
    }
    private suspend fun inspectPinned() {
        val pinned = withContext(Dispatchers.IO) { vault.pinSelected() }; selectedPackage = pinned
        val fps = inspection?.properties.orEmpty().filterKeys { it.endsWith(".fingerprint") }.values.toSet()
        firmware = withContext(Dispatchers.IO) { FirmwareValidator.inspect(pinned, device, fps) }
        message = "Pakket onderzocht. Verifieer daarnaast de ondertekening. Motorola moet de installatievoorwaarden zelf controleren."
    }
    fun download(wifiOnly: Boolean = true) = work {
        require(!demo && candidateAvailable)
        val m = requireNotNull(inspection?.otaMetadata)
        downloads.enqueue(requireNotNull(m.downloadUrl), device, requireNotNull(m.targetBuild), wifiOnly=wifiOnly)
        message = "Download ingepland. Voortgang blijft bewaard; onderbreken en hervatten kan hieronder."
    }
    fun pauseDownload() = work { require(!demo); downloads.pause() }
    fun resumeDownload() = work { require(!demo); downloads.resume() }
    fun cancelDownload() = work { require(!demo); downloads.cancel() }
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
            vault.recordVerified(result, PayloadManifest.inspect(pinned), anchors); true
        }.getOrDefault(false) }
        if (verified && workflow.id.isNotBlank()) {
            refreshInspection(true)
            workflow = withContext(Dispatchers.IO) { updates.attachVerifiedPackage(result) }
        }
        message = if (verified) "Whole-file OTA-ondertekening en stabiele pakketbytes bevestigd tegen de toestelcertificaten. " +
            (if (proofSaved) "Doel-init_boot-bewijs lokaal bewaard voor controle na de echte doelboot. " else "Geen bruikbaar doel-init_boot-bewijs beschikbaar. ") +
            "Installatiegeschiktheid blijft afzonderlijk door Motorola te beoordelen."
            else "Ondertekening of stabiele pakketidentiteit NIET bewezen. Pakketvoorbereiding blijft geblokkeerd."
    }
    fun importBackup(uri: Uri) = work {
        require(!demo); files.import(uri, backup=true)
        backup = withContext(Dispatchers.IO) { validateSavedBackup() }
        message = if (backup?.usableAsStock == true) "Exacte actuele stock-image bevestigd tegen onafhankelijke firmwaregegevens."
            else "Image lokaal bewaard. Zonder bewezen actuele stock-herkomst wordt herstel geblokkeerd."
    }
    fun startGuided(targetBuild: String) = work {
        require(!demo && targetBuild.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        preferences.edit().putBoolean("root_reads_allowed", true).apply()
        val f = firmware?.takeIf { it.targetBuild == targetBuild }
        refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.startOfficial(device, backup, ObservedUpdateTarget(targetBuild,
            f?.targetFingerprint.orEmpty(), f?.sha256.orEmpty(), f?.signatureVerified == true)) }
        message = workflow.message
    }
    fun prepareRestore() = work {
        require(!demo); refreshInspection(true)
        val observed = requireNotNull(inspection)
        val imported = File(app.filesDir, "selected-backup.bin").takeIf {
            it.isFile && backup?.usableAsStock == true && observed.backupInventory.none { c -> c.provenanceVerified && c.sha256 == backup?.sha256 }
        }
        restorePlan = restores.prepare(workflow, observed, imported)
        workflow = withContext(Dispatchers.IO) { updates.load() }
        message = "Herstelplan gereed. Er is nog geen partitie geschreven. Lees het plan en bevestig afzonderlijk."
    }
    fun dismissRestorePlan() { restorePlan = null }
    fun confirmRestore(noRebootAcknowledged: Boolean) = work {
        require(!demo); val plan = requireNotNull(restorePlan); restorePlan = null
        restores.confirmAndExecute(plan, explicitConfirmation=true, noRebootAcknowledged=noRebootAcknowledged)
        workflow = withContext(Dispatchers.IO) { updates.load() }; message = workflow.message
    }
    fun reportStock() = work {
        require(!demo); withContext(Dispatchers.IO) { updates.reportManualRestore() }; refreshInspection(true)
        val hash = inspection?.let(UpdateObservations::activeImageSha256)
        if (hash != null) workflow = withContext(Dispatchers.IO) { updates.observeStock(device.bootId, device.slot, hash) }
        message = workflow.message
    }
    fun recoverRestore() = work {
        require(!demo)
        val plan = withContext(Dispatchers.IO) { restores.pendingPlan(updates.load()) }
            ?: throw UpdateSafetyException("NO_PENDING_RESTORE_PLAN")
        restores.recover(plan) // Strictly observation-only; never refreshes or replays consent.
        refreshInspection(true); message = workflow.message
    }
    fun releasePreparation() = work {
        require(!demo)
        val plan = withContext(Dispatchers.IO) { restores.pendingPlan(updates.load()) }
            ?: throw UpdateSafetyException("NO_PENDING_RESTORE_PLAN")
        workflow = restores.releasePreparation(plan); restorePlan = null; message = workflow.message
    }
    fun stagePackage(automaticRecognitionAcknowledged: Boolean) = work {
        require(!demo)
        val report = requireNotNull(firmware); val pinned = requireNotNull(selectedPackage)
        refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.attachVerifiedPackage(report) }
        message = "Ondertekend pakket opnieuw verifiëren en gecontroleerd voor Motorola klaarzetten…"
        stagingReceipt = MotorolaPackageStager(app).stage(pinned, report, workflow, true, automaticRecognitionAcknowledged)
        message = "Pakket volledig en hashgelijk klaargezet. Dit bewijst geen herkenning of installatie door Motorola. Ga verder in de officiële updater; herstart nog niet."
    }
    fun cancelPreparation() = work {
        require(!demo); restorePlan = null
        workflow = withContext(Dispatchers.IO) { updates.cancelBeforeRestore() }; message = workflow.message
    }
    fun retryFinalChecks() = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.retryFinalChecks(device, backup, inspection?.let(UpdateObservations::activeImageSha256)) }
        message = workflow.message
    }
    fun markOfficialOtaHandoff(afterSaved: () -> Unit) = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.openOfficialOta() }
        if (workflow.stage == UpdateStage.AWAITING_OFFICIAL_OTA) afterSaved()
        message = workflow.message
    }
    fun reportMagisk() = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.reportMagiskInstalled() }; message = workflow.message
    }
    fun reportMagiskFailure() = work {
        require(!demo); refreshInspection(true)
        require(workflow.stage in setOf(UpdateStage.MAGISK_REQUIRED, UpdateStage.MAGISK_REPORTED_UNVERIFIED) && device.engine == EngineState.UPDATED_NEED_REBOOT)
        workflow = withContext(Dispatchers.IO) { updates.fail("MAGISK_INACTIVE_FAILED", "Je hebt gemeld dat Magisk op de doelslot niet is afgerond. Herstart niet. Controleer de officiële Magisk-log en exporteer de diagnose. Er wordt niets automatisch herhaald.") }
        message = workflow.message
    }
    fun retryMagiskStep() = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.retryMagiskStep(device) }; message = workflow.message
    }
    fun acknowledgeReboot(afterSaved: () -> Unit) = work {
        require(!demo); refreshInspection(true)
        workflow = withContext(Dispatchers.IO) { updates.acknowledgeReboot(true) }
        if (workflow.stage == UpdateStage.AWAITING_NEW_BOOT) afterSaved()
        message = workflow.message
    }
    fun setMonitoring(enabled: Boolean) = work {
        require(!demo)
        if (enabled) monitoring.enable() else monitoring.disable()
        notificationsAllowed = monitoring.notificationsAllowed()
        message = if (enabled) "Lokale metadatacontrole ingeschakeld. Android bepaalt het tijdstip; dit vraagt geen nieuwe OTA aan bij Motorola."
            else "Achtergrondcontrole uitgeschakeld."
    }
    fun checkMonitoringNow() = work { require(!demo); monitoring.checkNow() }
    fun export(uri: Uri) = work {
        val text = report
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) } }
        message = "Geanonimiseerd rapport lokaal opgeslagen. Er is niets geüpload."
    }
    fun exportRecoveryBackup(uri: Uri) = work {
        require(!demo)
        withContext(Dispatchers.IO) {
            val run = updates.load()
            val plan = requireNotNull(restores.pendingPlan(run))
            require(plan.transactionId.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
            val file = File(app.filesDir, "update/transactions/${plan.transactionId}/patched-backup.img")
            require(file.canonicalPath == file.absolutePath && java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val bytes = file.inputStream().use { it.readNBytes(8_388_609) }
            require(bytes.size.toLong() == plan.imageBytes && plan.imageBytes == 8_388_608L)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == plan.previousImageSha256 && hash == run.patchedBackupSha256)
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes); it.flush() }
        }
        message = "De volledige gepatchte image van vóór stockherstel is geëxporteerd, met gecontroleerde hash en omvang. Dit is geen nieuwe stock-image en niets is teruggeschreven."
    }
    private fun fileSha256(file: File): String = file.inputStream().buffered().use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024*1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
