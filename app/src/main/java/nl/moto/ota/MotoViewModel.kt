package nl.moto.ota

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.platform.*
import java.io.File

class MotoViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = WorkflowStore(app)
    private val files = FirmwareFiles(app)
    var device by mutableStateOf(DeviceState()); private set
    var inspection by mutableStateOf<Inspection?>(null); private set
    var firmware by mutableStateOf<FirmwareReport?>(null); private set
    var backup by mutableStateOf<BackupValidation?>(null); private set
    var workflow by mutableStateOf(store.load()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("Start een uitlezende controle."); private set
    var demo by mutableStateOf(false); private set
    var verificationProgress by mutableStateOf<Int?>(null); private set
    val readiness get() = Readiness.evaluate(device, backup, firmware?.packageBytes ?: 0)
    val discovery get() = FirmwareDiscovery.assess(DiscoveryEvidence(localDownloadUrl = inspection?.otaWifiUrl), device.build)
    val candidateAvailable get() = discovery.canDownloadCandidate
    val report get() = Privacy.diagnosticReport(device, inspection?.probes.orEmpty().mapValues { (_, value) ->
        when { value.timedOut -> "TIMEOUT"; value.truncated -> "TRUNCATED"; value.successful -> "OK"; else -> "UNAVAILABLE" }
    }, inspection?.backupInventory?.size ?: 0, workflow.phase.name)

    init { scan(false) }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (_: Exception) { message = "Actie mislukt. Bestand, download of toestelprobe is niet beschikbaar. Er is geen systeemwijziging uitgevoerd." }
            finally { busy = false; verificationProgress = null }
        }
    }

    fun scan(root: Boolean) = work {
        demo = false
        val observed = DeviceInspector().inspect(app, root)
        val previous = device
        inspection = observed
        device = observed.toDeviceState()
        if (previous.build != device.build || previous.fingerprint != device.fingerprint || previous.incrementalBuild != device.incrementalBuild ||
            previous.model != device.model || previous.channel != device.channel || previous.bootId != device.bootId) {
            firmware = null
            backup = null
        }
        workflow = Workflow.reconcile(store.load(), device)
        withContext(Dispatchers.IO) { store.save(workflow) }
        message = if (root && !device.rootGranted) "Geen roottoegang. Verleen deze app vooraf toegang in Magisk → Superuser en probeer opnieuw." else "Controle voltooid. Onbekende of onbewezen gegevens blokkeren updatevoorbereiding."
    }

    fun showDemo() {
        if (busy) return
        demo = true
        inspection = null
        firmware = null
        backup = null
        device = DeviceState(manufacturer = "motorola", model = "XT2607-1", device = "merc", channel = "reteu", androidVersion = "17",
            build = "DEMO-TARGET", securityPatch = "2026-10-01", slot = "_b", rootGranted = true, magiskVersion = "31.1", magiskCode = 31100,
            virtualAb = true, snapshot = SnapshotState.MERGING, engine = EngineState.IDLE, batteryPercent = 82, freeBytes = 40L * 1024 * 1024 * 1024,
            capturedAtMillis = System.currentTimeMillis(), simulated = true)
        workflow = Workflow.demo()
        message = "SIMULATIE · alle waarden zijn synthetisch. Er wordt niets op je toestel uitgevoerd of in het echte workflowjournaal opgeslagen."
    }

    fun importOta(uri: Uri) = work {
        require(!demo)
        firmware = null
        message = "Pakket lokaal kopiëren en volledig hashen… Dit kan enkele minuten duren."
        inspect(files.import(uri))
    }
    fun inspectSaved() = work { require(!demo); inspect(File(app.filesDir, "selected-ota.zip")) }
    fun download() = work {
        require(!demo)
        firmware = null
        message = "Download van de toegestane Motorola-host… Bij verlopen link of omleiding stopt de download."
        val url = requireNotNull(inspection?.otaWifiUrl)
        inspect(files.download(url))
    }
    private suspend fun inspect(file: File) {
        val fingerprints = inspection?.properties.orEmpty().filterKeys { it.endsWith(".fingerprint") }.values.toSet()
        firmware = withContext(Dispatchers.IO) { FirmwareValidator.inspect(file, device, fingerprints) }
        message = "Pakket onderzocht. Een correcte hash bewijst geen officiële ondertekening; voer de afzonderlijke signaturecontrole uit."
    }
    fun verify() = work {
        val original = requireNotNull(firmware)
        require(original.structureValid && original.payloadHashValid && !demo)
        verificationProgress = 0
        val verified = files.verify(File(app.filesDir, "selected-ota.zip")) { progress ->
            viewModelScope.launch { verificationProgress = progress }
        }
        firmware = original.copy(signatureVerified = verified)
        message = if (verified) "Whole-file OTA-ondertekening geverifieerd tegen de vertrouwde certificaten op dit toestel. Kanaal, payload-signatures en geschiktheid blijven afzonderlijke controles."
        else "Ondertekening NIET bewezen: signaturefout, onbereikbare toestelcertificaten of niet-ondersteunde verpakking. Voorbereiding blijft geblokkeerd."
    }
    fun importBackup(uri: Uri) = work {
        require(!demo)
        val image = files.import(uri, backup = true)
        val bytes = inspection?.probes?.get(ReadOnlyProbe.PARTITIONS.name)?.stdout.orEmpty().lineSequence()
            .firstOrNull { it.startsWith("init_boot${device.slot}=") }?.substringAfter('=')?.trim()?.toLongOrNull() ?: -1L
        backup = withContext(Dispatchers.IO) {
            image.inputStream().buffered().use { input ->
                input.mark(2)
                val gzip = input.read() == 0x1f && input.read() == 0x8b
                input.reset()
                BackupValidator.inspect(input, gzip, bytes, device)
            }
        }
        message = "Image veilig lokaal bewaard en onderzocht. Zonder onafhankelijke actuele firmware-referentie blijft stock-herkomst onbewezen; herstel wordt niet aangeboden."
    }
    fun startJournal() = work {
        require(!demo)
        val f = requireNotNull(firmware)
        workflow = Workflow.start(device, f.targetBuild, f.targetFingerprint, f.sha256, f.readyForOfficialReview, readiness)
        withContext(Dispatchers.IO) { store.save(workflow) }
        message = workflow.message
    }
    fun reportStock() = work { require(!demo); workflow = Workflow.reportStockRestore(workflow); withContext(Dispatchers.IO) { store.save(workflow) } }
    fun reportMagisk() = work { require(!demo); workflow = Workflow.reportMagisk(workflow); withContext(Dispatchers.IO) { store.save(workflow) } }
    fun resetJournal() = work { require(!demo); workflow = WorkflowState(); withContext(Dispatchers.IO) { store.save(workflow) }; message = "Lokaal workflowjournaal gewist; systeemstatus is niet gewijzigd." }
    fun export(uri: Uri) = work {
        val text = report
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) } }
        message = "Geanonimiseerd rapport lokaal opgeslagen. Er is niets geüpload."
    }
}

