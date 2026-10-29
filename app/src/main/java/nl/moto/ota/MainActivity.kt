package nl.moto.ota

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.OfficialApps
import nl.moto.ota.platform.UpdateObservations
import nl.moto.ota.ui.DownloadProgressPanel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var firmwareRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (intent.getStringExtra("moto_open_tab") == "firmware") firmwareRequest++
        setContent { MotoApp(openFirmwareRequest = firmwareRequest) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("moto_open_tab") == "firmware") firmwareRequest++
    }
}

private val colors = darkColorScheme(
    primary = Color(0xFF71DDC6), onPrimary = Color(0xFF00382E),
    secondary = Color(0xFFA8C7D7), background = Color(0xFF0B161C),
    surface = Color(0xFF14232B), surfaceVariant = Color(0xFF263A42),
    onSurface = Color(0xFFE0EDF1), onSurfaceVariant = Color(0xFFB5C8CE), error = Color(0xFFFFB4A9)
)

@Composable
fun MotoApp(vm: MotoViewModel = viewModel(), openFirmwareRequest: Int = 0) {
    MaterialTheme(colorScheme = colors) {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var rootDialog by remember { mutableStateOf(false) }
        var launchFailure by remember { mutableStateOf<String?>(null) }
        var notificationAction by rememberSaveable { mutableStateOf("") }
        var rebootDialog by remember { mutableStateOf(false) }
        var stageDialog by remember { mutableStateOf(false) }
        var automaticRecognitionAcknowledged by remember(stageDialog) { mutableStateOf(false) }
        var noRebootAcknowledged by remember(vm.restorePlan?.transactionId) { mutableStateOf(false) }
        var targetBuild by rememberSaveable { mutableStateOf("") }
        val lifecycle = LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.onResume() }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(openFirmwareRequest) { if (openFirmwareRequest > 0) tab = 1 }
        LaunchedEffect(vm.observedTargetBuild) { if (vm.observedTargetBuild.isNotBlank()) targetBuild = vm.observedTargetBuild }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            when (notificationAction) { "monitor" -> vm.setMonitoring(true); "download" -> vm.download() }
            notificationAction = ""
        }
        fun withNotifications(action: String) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                if (action == "monitor") vm.setMonitoring(true) else vm.download()
            } else { notificationAction = action; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        val otaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importOta) }
        val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importBackup) }
        val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
        val recoveryPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(vm::exportRecoveryBackup) }
        fun openOfficial(magisk: Boolean) {
            val intent = if (magisk) OfficialApps.magiskIntent(context.packageManager)
            else OfficialApps.systemUpdaterIntent(context.packageManager)
            if (intent == null) { launchFailure = if (magisk) "Magisk-app niet gevonden. Open Magisk zelf vanuit je startscherm." else "Geen toegankelijke officiële Motorola-updateactiviteit gevonden. Open Instellingen → Systeemupdates zelf."; return }
            try { context.startActivity(intent) }
            catch (_: ActivityNotFoundException) { launchFailure = "Geen toegankelijke updateactiviteit. Open Instellingen → Systeem → Systeemupdates handmatig." }
            catch (_: SecurityException) { launchFailure = "Deze activiteit is niet toegankelijk; gebruik de officiële app via je startscherm." }
        }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.safeDrawingPadding()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("MOTO / TOESTELBEHEER", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text("OTA Manager", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("merc  ·  XT2607-1  ·  reteu", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Overzicht", "Firmware", "Workflow", "Diagnose").forEachIndexed { index, title ->
                        FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) })
                    }
                }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (vm.demo) InfoCard("SIMULATIE", vm.message, warning = true)
                            else InfoCard(if (vm.workflow.stage == UpdateStage.IDLE) "Lokaal · uitsluitend uitlezend" else "Begeleide update · ${stageTitle(vm.workflow.stage)}", vm.message)
                            if (!vm.demo && vm.workflow.criticalSession) {
                                if (vm.device.bootId.isBlank() || vm.device.bootId == vm.workflow.sourceBootId) {
                                    InfoCard("Niet herstarten", "Stockherstel of de OTA-begeleiding is nog niet afgerond. De draaiende rootsessie kan nodig zijn voor Magisk. Open Workflow en controleer eerst de werkelijke voortgang; een onderbroken herstel wordt niet automatisch herhaald.", warning = true)
                                } else {
                                    InfoCard("Update nog niet afgerond", "Controleer de nieuwe boot, root en snapshot-merge onder Workflow. Start geen volgende OTA voordat alle eindcontroles zijn bevestigd.", warning = true)
                                }
                            }
                            when (tab) {
                                0 -> {
                                    Button(onClick = { rootDialog = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Controleer en bereid update voor") }
                                    Panel {
                                        Text("Jouw toestel", style = MaterialTheme.typography.titleLarge)
                                        Text(vm.device.model.ifBlank { "Model nog onbekend" }, style = MaterialTheme.typography.headlineSmall, color = colors.primary)
                                        Detail("Android / build", "${vm.device.androidVersion.ifBlank { "?" }} / ${vm.device.build.ifBlank { "onbekend" }}")
                                        Detail("Beveiligingspatch", vm.device.securityPatch)
                                        Detail("Kanaal", vm.device.channel)
                                        Detail("Actieve → inactieve slot", "${vm.device.slot.ifBlank { "?" }} → ${vm.device.inactiveSlot.ifBlank { "?" }}")
                                        Detail("Root / Magisk", "${if (vm.device.rootGranted) "toegang bevestigd" else "niet bevestigd"} / ${vm.device.magiskVersion.ifBlank { "onbekend" }}")
                                        Detail("Accu / vrije ruimte", "${vm.device.batteryPercent.takeIf { it >= 0 } ?: "?"}% / ${size(vm.device.freeBytes)}")
                                    }
                                    Panel {
                                        Text("Updategereedheid", style = MaterialTheme.typography.titleLarge)
                                        Text(if (vm.readiness.ready) "Klaar voor officiële beoordeling" else "Voorbereiding geblokkeerd", color = if (vm.readiness.ready) colors.primary else Color(0xFFFFD08A), fontWeight = FontWeight.Bold)
                                        Detail("Snapshot-merge", vm.device.snapshot.name + (vm.inspection?.snapshot?.mergePercent?.let { " · $it%" } ?: ""))
                                        Detail("UpdateEngine", vm.device.engine.name)
                                        Detail("Originele back-up", if (vm.backup?.usableAsStock == true) "herkomst geverifieerd" else "herkomst niet bewezen")
                                        Detail("OTA-aanbod", vm.discovery.explanation)
                                        Text("Deze knop leest het toestel uit. Ga daarna naar Workflow. Stockherstel vraagt een eigen bevestiging; OTA, Magisk en herstart blijven bij de officiële apps.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Panel {
                                        Text("Wat houdt voorbereiding tegen?", style = MaterialTheme.typography.titleMedium)
                                        vm.readiness.blockers.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodyMedium) }
                                        if (vm.readiness.ready) Text("De toestelcontroles zijn geslaagd. Een nieuwe geschikte OTA moet nog afzonderlijk worden vastgesteld.")
                                        if (vm.device.securityPropertyMismatch) Text("Android-properties verschillen van de kernelbootgegevens. De app gebruikt de kernelgegevens voor de boottoestand en wijzigt je configuratie niet.", color = Color(0xFFFFD08A))
                                    }
                                }
                                1 -> {
                                    Panel {
                                        Text("Officiële updatebronnen", style = MaterialTheme.typography.titleLarge)
                                        Text("Motorola's eigen updater heeft voorrang. Er is geen bewezen publieke firmwarecatalogus voor dit toestel. Lokale metadata is een aanwijzing, geen actuele serveraanbieding.")
                                        Text(vm.discovery.explanation, color = colors.primary)
                                        Button(onClick = { openOfficial(false) }, enabled = !vm.demo && !vm.busy) { Text("Open systeemupdates") }
                                        OutlinedButton(onClick = { withNotifications("download") }, enabled = vm.candidateAvailable && !vm.busy && !vm.demo) { Text("Download gevonden Motorola-pakket") }
                                        Text("Downloads starten alleen op jouw verzoek. Verlopen links, omleidingen en onbekende hosts worden geweigerd.", style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(onClick = vm::discoverExistingPackages, enabled = !vm.busy && !vm.demo && vm.device.rootGranted) { Text("Zoek bestaande Motorola-OTA op toestel") }
                                        Text("Met root kan de app ook een volledige ZIP onderzoeken die Motorola zelf al heeft bewaard. Dit wijzigt het Motorola-bestand niet en start geen installatie.", style = MaterialTheme.typography.bodySmall)
                                        vm.existingMotorolaPackages.forEachIndexed { index, candidate ->
                                            TextButton(onClick = { vm.importExistingPackage(candidate.id) }, enabled = !vm.busy && !vm.demo) { Text("Onderzoek Motorola-bestand ${index + 1} · ${size(candidate.bytes)}") }
                                        }
                                    }
                                    if (!vm.demo) DownloadProgressPanel(vm.downloadJob, vm.busy, true, vm::pauseDownload, vm::resumeDownload, vm::cancelDownload, vm::inspectSaved)
                                    Panel {
                                        Text("Update-informatie volgen", style = MaterialTheme.typography.titleLarge)
                                        Text("Optioneel elke zes uur de bestaande Motorola-metadata uitlezen. Roottoegang is nodig. Dit voert geen servercheck, download of OTA-installatie uit; open Motorola om nieuw aanbod op te vragen.")
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Achtergrondcontrole", Modifier.weight(1f))
                                            Switch(checked = vm.monitorState.enabled, enabled = !vm.busy && !vm.demo,
                                                onCheckedChange = { if (it) withNotifications("monitor") else vm.setMonitoring(false) })
                                        }
                                        if (!vm.notificationsAllowed) Text("Meldingen zijn niet toegestaan. Je kunt uitslagen hier blijven bekijken.", color = Color(0xFFFFD08A))
                                        Detail("Laatste resultaat", monitorTitle(vm.monitorState.lastOutcome))
                                        if (vm.monitorState.lastCheckedAtMillis > 0) Detail("Laatste controle", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(vm.monitorState.lastCheckedAtMillis)))
                                        OutlinedButton(onClick = vm::checkMonitoringNow, enabled = vm.monitorState.enabled && !vm.busy && !vm.demo) { Text("Controleer lokale metadata nu") }
                                        vm.monitorState.history.asReversed().take(3).forEach { entry ->
                                            Text("${monitorTitle(entry.outcome)}${entry.targetBuild.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Panel {
                                        Text("OTA-ZIP onderzoeken", style = MaterialTheme.typography.titleLarge)
                                        Text("Selecteer een officieel A/B-pakket. De app kopieert het lokaal, controleert ZIP/metadata, bronfirmware, payload-header en SHA-256. Een bestandsnaam bewijst geen regio.")
                                        Button(onClick = { otaPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !vm.busy && !vm.demo) { Text("Kies officiële OTA-ZIP") }
                                        TextButton(onClick = vm::inspectSaved, enabled = !vm.busy && !vm.demo) { Text("Onderzoek eerder opgeslagen pakket") }
                                        vm.firmware?.let { f ->
                                            Detail("Huidig → doel", "${vm.device.build} → ${f.targetBuild.ifBlank { "onbekend" }}")
                                            Detail("Doel-Android", f.targetAndroidVersion)
                                            Detail("Bron", "Lokaal onderzocht officieel kandidaatpakket; ondertekening afzonderlijk controleren")
                                            Detail("Release-informatie", "Geen onafhankelijk opgehaalde releasebeschrijving beschikbaar; raadpleeg Motorola-systeemupdates")
                                            Detail("Grootte", size(f.packageBytes))
                                            Detail("Pakketstructuur", if (f.structureValid) "geldig" else "ongeldig")
                                            Detail("Payload-hash", if (f.payloadHashValid) "komt overeen" else "niet bewezen")
                                            Detail("Broncompatibiliteit", if (f.compatible) "lokale controles geslaagd" else "geblokkeerd")
                                            Detail("Regio/kanaal", if (f.regionVerified) "metadata komt overeen; signature apart controleren" else "niet bewezen")
                                            Detail("OTA-ondertekening", if (f.signatureVerified) "geverifieerd met toestelcertificaten" else "niet bewezen")
                                            Text("SHA-256: ${f.sha256.ifBlank { "onbekend" }}", style = MaterialTheme.typography.bodySmall)
                                            f.issues.forEach { Text("• ${it.message}", color = Color(0xFFFFD08A)) }
                                            OutlinedButton(onClick = vm::verify, enabled = !vm.busy && f.structureValid && f.payloadHashValid) { Text("Verifieer officiële ondertekening") }
                                            vm.verificationProgress?.let { Text("Signaturecontrole: $it%") }
                                            Text("De officiële updater moet later ook payload-signatures, antirollback, productvoorwaarden en partitiehashes verifiëren.", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Panel {
                                        Text("Imagekluis", style = MaterialTheme.typography.titleLarge)
                                        Text("Bewaar en inspecteer een init_boot-image of Magisk-gzip. Een header, SHA-1-directory of hash alleen bewijst geen actuele stock-herkomst.")
                                        OutlinedButton(onClick = { backupPicker.launch(arrayOf("*/*")) }, enabled = !vm.busy && !vm.demo) { Text("Bewaar en onderzoek image") }
                                        vm.backup?.let { b ->
                                            Detail("Imagegrootte", size(b.bytes)); Detail("Partitietype", b.partitionType)
                                            Text("SHA-256: ${b.sha256}", style = MaterialTheme.typography.bodySmall)
                                            b.issues.forEach { Text("• ${it.message}") }
                                        }
                                    }
                                }
                                2 -> {
                                    if (vm.demo) Panel {
                                        Text("Volledige simulatie op bestanden", style = MaterialTheme.typography.titleLarge)
                                        Text("Alle stappen gebruiken synthetische toestelgegevens. Alleen kleine testbestanden in de appcache worden geschreven. Je echte workflow en partities blijven buiten deze simulatie.")
                                        OutlinedButton(onClick = vm::startFullSimulation, enabled = !vm.busy) { Text("Start volledige updatesimulatie") }
                                        if (vm.sandboxActive && vm.workflow.stage != UpdateStage.COMPLETE) Button(onClick = vm::advanceSimulation, enabled = !vm.busy) { Text("Volgende simulatiestap") }
                                        if (vm.sandboxActive && vm.workflow.stage == UpdateStage.COMPLETE) Text("Simulatie afgerond: stockherstel, OTA, Magisk, nieuwe boot en merge doorlopen.", color = colors.primary)
                                    }
                                    Panel {
                                        Text("Begeleide update", style = MaterialTheme.typography.titleLarge)
                                        Text(stageTitle(vm.workflow.stage), color = colors.primary, style = MaterialTheme.typography.titleMedium)
                                        Text(vm.workflow.message)
                                        if (vm.workflow.stage in setOf(UpdateStage.IDLE, UpdateStage.PRECHECK_BLOCKED, UpdateStage.COMPLETE, UpdateStage.COMPLETE_REVIEW, UpdateStage.ROLLBACK)) {
                                            OutlinedTextField(value = targetBuild, onValueChange = { targetBuild = it.take(100) }, singleLine = true,
                                                label = { Text("Doelbuild uit Motorola-systeemupdates") }, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy && !vm.demo)
                                            Text("Neem de build over die Motorola werkelijk aanbiedt. Een ingevulde build bewijst geen beschikbaarheid. Motorola controleert de firmware en ondertekening vóór installatie.", style = MaterialTheme.typography.bodySmall)
                                            Button(onClick = { vm.startGuided(targetBuild.trim()) }, enabled = !vm.busy && !vm.demo && targetBuild.isNotBlank()) { Text("Start begeleide updatecontrole") }
                                        } else {
                                            Detail("Huidige → doelbuild", "${vm.workflow.sourceBuild} → ${vm.workflow.targetBuild}")
                                            Detail("Bron- → doelslot", "${vm.workflow.sourceSlot} → ${vm.workflow.targetSlot}")
                                            Detail("Stockherstel", evidenceTitle(vm.workflow.stockRestoreEvidence))
                                            Detail("Magisk op doelslot", evidenceTitle(vm.workflow.magiskEvidence))
                                            Detail("OTA toegepast", if (vm.workflow.otaAppliedObserved) "Door UpdateEngine of doelboot waargenomen" else "Nog niet bevestigd")
                                        }
                                        Text("De workflow bewaart verwachtingen. Alleen nieuwe toestelobservaties bevestigen toepassing, doelslot, root en afgeronde merge. Na opnieuw openen wordt nooit automatisch geschreven.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Panel {
                                        Text("1 · Controle en originele image", fontWeight = FontWeight.Bold)
                                        Text("Controleer de aangeboden doelbuild, root, slots, accu en opslag. Voor herstel moet de originele init_boot exact bij je huidige build horen. Een ontbrekende of afwijkende image blokkeert.")
                                        Detail("Automatische systeemupdates", when (vm.inspection?.let(UpdateObservations::automaticUpdatesDisabled)) { true -> "Uitgeschakeld, zoals Magisk vereist"; false -> "Nog ingeschakeld: zet Automatische systeemupdates uit in Ontwikkelaarsopties"; null -> "Instelling niet vastgesteld; herstel blijft geblokkeerd" })
                                        Text("2 · Stock voorbereiden", fontWeight = FontWeight.Bold)
                                        Text("De app kan een volledig gecontroleerd herstelplan maken en alleen de actieve init_boot terugzetten. Vooraf wordt de gepatchte image veilig bewaard. Herstart daarna niet: de huidige rootsessie is nog nodig.")
                                        Text("De nieuwe schrijffunctie is met bestandsmocks getest en nog niet op jouw telefoon gevalideerd.", color = Color(0xFFFFD08A), style = MaterialTheme.typography.bodySmall)
                                        if (vm.workflow.stage in setOf(UpdateStage.STOCK_PREPARATION, UpdateStage.STOCK_CONFIRMATION)) {
                                            Button(onClick = vm::prepareRestore, enabled = !vm.busy && !vm.demo) { Text("Maak stockherstelplan ter beoordeling") }
                                            OutlinedButton(onClick = { openOfficial(true) }, enabled = !vm.busy && !vm.demo) { Text("Of open Magisk voor Images herstellen") }
                                            TextButton(onClick = vm::reportStock, enabled = !vm.busy && !vm.demo) { Text("Controleer handmatig stockherstel") }
                                            if (vm.workflow.stage == UpdateStage.STOCK_PREPARATION) TextButton(onClick = vm::cancelPreparation, enabled = !vm.busy && !vm.demo) { Text("Annuleer voorbereiding vóór herstel") }
                                        }
                                        if (vm.workflow.stage in setOf(UpdateStage.STOCK_WRITE_RUNNING, UpdateStage.RECOVERY_REQUIRED)) {
                                            TextButton(onClick = vm::recoverRestore, enabled = !vm.busy && !vm.demo && vm.workflow.restoreTransactionId.isNotBlank()) { Text("Lees onderbroken herstel uitsluitend terug") }
                                            if (vm.workflow.stage == UpdateStage.RECOVERY_REQUIRED) TextButton(onClick = vm::releasePreparation, enabled = !vm.busy && !vm.demo && vm.workflow.restoreTransactionId.isNotBlank()) { Text("Controleer ongewijzigde bron voor een nieuw plan") }
                                        }
                                        if (vm.workflow.stage == UpdateStage.FAILED && vm.workflow.errorCode in setOf("ROOT_MISSING", "MODULE_INVENTORY_CHANGED", "MAGISK_VERSION")) {
                                            TextButton(onClick = vm::retryFinalChecks, enabled = !vm.busy && !vm.demo) { Text("Voer eindcontrole opnieuw uit na beoordeling") }
                                        }
                                        if (vm.workflow.stage == UpdateStage.FAILED && vm.workflow.errorCode == "MAGISK_INACTIVE_FAILED") {
                                            TextButton(onClick = vm::retryMagiskStep, enabled = !vm.busy && !vm.demo) { Text("Controleer voorwaarden voor nieuwe Magisk-poging") }
                                        }
                                        Text("3 · Officiële Motorola-OTA", fontWeight = FontWeight.Bold)
                                        Text("Laat Motorola zijn aangeboden update installeren. Wacht totdat de installatie en optimalisatie klaar zijn. Druk nog niet op Herstarten. De app wijzigt geen Motorola-appdata en omzeilt geen verificatie.")
                                        if (vm.workflow.stage in setOf(UpdateStage.STOCK_RESTORED, UpdateStage.AWAITING_OFFICIAL_OTA, UpdateStage.OTA_APPLYING)) {
                                            Button(onClick = { vm.markOfficialOtaHandoff { openOfficial(false) } }, enabled = !vm.busy && !vm.demo) { Text("Verder in Motorola-systeemupdates") }
                                        }
                                        if (vm.workflow.stage in setOf(UpdateStage.STOCK_RESTORED, UpdateStage.AWAITING_OFFICIAL_OTA)) {
                                            Text("Geen normaal aanbod? Lokaal klaarzetten vereist een geverifieerde ZIP met ondertekend reteu-kanaalbewijs. Standaard Motorola-metadata bevat dat niet altijd. Zonder bewijs blijft de normale Motorola-aanbodroute over. Herkenning van de interne map is niet gegarandeerd.", style = MaterialTheme.typography.bodySmall)
                                            OutlinedButton(onClick = { stageDialog = true }, enabled = !vm.busy && !vm.demo && vm.firmware?.signatureVerified == true && vm.firmware?.regionVerified == true) { Text("Beoordeel lokaal pakket klaarzetten") }
                                            if (vm.firmware?.signatureVerified == true && vm.firmware?.regionVerified != true) Text("Lokaal klaarzetten geblokkeerd: ondertekend reteu-kanaalbewijs ontbreekt (STAGING_CHANNEL_UNPROVEN).", color = Color(0xFFFFD08A), style = MaterialTheme.typography.bodySmall)
                                        }
                                        vm.stagingReceipt?.takeIf { it.runId == vm.workflow.id && it.packageSha256 == vm.workflow.packageSha256 &&
                                            it.sourceBootId == vm.workflow.sourceBootId && (vm.firmware == null || vm.firmware?.sha256 == it.packageSha256) }?.let { receipt ->
                                            Detail("Historisch kopieerbewijs", "${size(receipt.packageBytes)} volledig gekopieerd en gehasht op ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(receipt.stagedAtMillis))}. Huidige aanwezigheid en OTA-installatie zijn hiermee niet bevestigd.")
                                            if (!receipt.signedChannelProven) Text("De standaard pakketmetadata bewijst het kanaal niet. Motorola moet het pakket zelf beoordelen.", color = Color(0xFFFFD08A), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text("4 · Magisk vóór herstart", fontWeight = FontWeight.Bold)
                                        Text("Pas na waargenomen OTA-voltooiing: Magisk → Installeren → Install to Inactive Slot (After OTA). Controleer doelslot, init_boot en All done in de log. Deze app gebruikt geen interne Magisk-installercommando's.")
                                        if (vm.workflow.stage in setOf(UpdateStage.MAGISK_REQUIRED, UpdateStage.MAGISK_REPORTED_UNVERIFIED, UpdateStage.REBOOT_BY_SYSTEM_UPDATER)) {
                                            OutlinedButton(onClick = { openOfficial(true) }, enabled = !vm.busy && !vm.demo) { Text("Open Magisk voor de doelslot") }
                                            OutlinedButton(onClick = vm::reportMagisk, enabled = !vm.busy && !vm.demo) { Text("Magisk-log gecontroleerd: installatie voltooid") }
                                            TextButton(onClick = vm::reportMagiskFailure, enabled = !vm.busy && !vm.demo) { Text("Meld mislukte Magisk-installatie") }
                                            Text("Een handmatige bevestiging blijft als onbewezen vermeld tot root op de nieuwe slot werkelijk is gecontroleerd.", style = MaterialTheme.typography.bodySmall)
                                            Button(onClick = { rebootDialog = true }, enabled = !vm.busy && !vm.demo && vm.workflow.magiskEvidence != StepEvidence.NONE) { Text("Beoordeel herstart via Motorola") }
                                        }
                                        Text("5 · Nieuwe boot en eindcontrole", fontWeight = FontWeight.Bold)
                                        Text("Herstart zelf via Motorola. Open daarna deze app. De app controleert de juiste nieuwe slot en build, root, succesvolle boot, modules, bootbeveiliging en snapshot NONE. Nieuwe stockherkomst blijft een afzonderlijke controle. Wallet en modulewerking controleer je zelf.")
                                        TextButton(onClick = { rootDialog = true }, enabled = !vm.busy) { Text("Lees actuele voortgang opnieuw uit") }
                                    }
                                }
                                else -> {
                                    Panel {
                                        Text("Geavanceerde diagnose", style = MaterialTheme.typography.titleLarge)
                                        Detail("Werkelijk gemeten model", vm.inspection?.properties?.get("ro.product.model").orEmpty())
                                        Detail("Systeemfingerprint", vm.device.fingerprint)
                                        Detail("Vendor-fingerprint", vm.device.vendorFingerprint)
                                        Detail("Virtual A/B", vm.device.virtualAb?.toString().orEmpty())
                                        Detail("Boot succesvol gemarkeerd", vm.device.bootSuccessful?.toString().orEmpty())
                                        Detail("Bootloader / Verified Boot", "${vm.device.bootloaderUnlocked?.let { if (it) "ontgrendeld" else "vergrendeld" } ?: "onbekend"} / ${vm.device.verifiedBootState}")
                                        Detail("Beveiligingsbron", vm.device.securityEvidenceSource)
                                        Detail("Gerapporteerde Android-property", vm.inspection?.properties?.get("ro.boot.verifiedbootstate").orEmpty())
                                        if (vm.device.securityPropertyMismatch) Text("Kernelbootgegevens en Android-properties verschillen; dit bronverschil wordt niet als een nieuwe beveiligingswijziging verborgen.", color = Color(0xFFFFD08A))
                                        vm.inspection?.snapshot?.evidence?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        Detail("Partities", vm.device.partitions.joinToString(", "))
                                        Detail("Modules", "${vm.device.modules.size} waargenomen; compatibiliteit niet bewezen")
                                        vm.inspection?.counters?.forEach { (key, value) -> Detail(key.substringAfterLast('.'), value) }
                                        Text("Fouttellers worden uitsluitend gelezen. Nul fouten bewijst geen toekomstig OTA-aanbod.", style = MaterialTheme.typography.bodySmall)
                                        vm.inspection?.probes?.forEach { (name, p) -> Detail(name, when { p.timedOut -> "timeout"; p.truncated -> "uitvoer afgekapt"; p.successful -> "gelezen"; else -> "niet beschikbaar (${p.exitCode ?: "?"})" }) }
                                    }
                                    Panel {
                                        Text("Magisk-back-ups", style = MaterialTheme.typography.titleLarge)
                                        if (vm.inspection?.backupInventory.isNullOrEmpty()) Text("Geen leesbare kandidaten waargenomen; dit bewijst niet dat er geen back-up bestaat.")
                                        vm.inspection?.backupInventory?.forEachIndexed { index, b ->
                                            Text("Kandidaat ${index + 1}", fontWeight = FontWeight.Bold)
                                            Detail("Gzip → image", "${size(b.gzipBytes ?: -1)} → ${size(b.uncompressedBytes ?: -1)}")
                                            Text("SHA-256: ${b.sha256 ?: "onbekend"}", style = MaterialTheme.typography.bodySmall)
                                            Detail("Stock-herkomst", if (b.provenanceVerified) "Exacte init_boot van de ondertekende OTA voor deze build; toesteltrustanchor gecontroleerd" else "niet bewezen")
                                            b.errors.forEach { Text("• $it") }
                                        }
                                    }
                                    Panel {
                                        Text("Privacy en lokale controle", style = MaterialTheme.typography.titleLarge)
                                        Text("Het rapport bevat alleen toegestane diagnosevelden. Geen ruwe logs, IMEI, serienummer, boot-id, accounts, module-ID's of ondertekende URL's. Er wordt niets automatisch geüpload.")
                                        Button(onClick = { reportPicker.launch("moto-ota-diagnose.txt") }, enabled = !vm.busy) { Text("Exporteer diagnostisch rapport") }
                                        if (vm.workflow.restoreTransactionId.isNotBlank()) OutlinedButton(onClick = { recoveryPicker.launch("moto-init_boot-magisk-backup.img") }, enabled = !vm.busy && !vm.demo) { Text("Exporteer gepatchte herstelback-up") }
                                        OutlinedButton(onClick = { vm.scan(false) }, enabled = !vm.busy) { Text("Controle zonder root") }
                                        OutlinedButton(onClick = vm::showDemo, enabled = !vm.busy) { Text("Bekijk veilige simulatie") }
                                        Text("${BuildConfig.VERSION_NAME} · lokaal beheer en gecontroleerde updatebegeleiding", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (rootDialog) AlertDialog(onDismissRequest = { rootDialog = false }, title = { Text("Uitlezende rootdiagnose") },
            text = { Text("Verleen Moto OTA Manager toegang in Magisk → Superuser. De app leest eigenschappen, snapshots, bootstatus, OTA-metadata, back-ups en module-inventaris. Na terugkeer worden deze metingen herhaald. Deze controle herstelt, installeert of herstart niets.") },
            confirmButton = { TextButton(onClick = { rootDialog = false; vm.scan(true) }) { Text("Start uitlezen") } },
            dismissButton = { TextButton(onClick = { rootDialog = false }) { Text("Annuleren") } })
        vm.restorePlan?.let { plan ->
            AlertDialog(onDismissRequest = vm::dismissRestorePlan, title = { Text("Bevestig schrijven van stock-init_boot") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(plan.confirmationSummary)
                    Text("Deze functie is nog niet op je echte toestel getest. Bij een fout: niet herstarten; lees de status opnieuw uit. Bevestiging vervalt na 60 seconden.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = noRebootAcknowledged, onCheckedChange = { noRebootAcknowledged = it })
                        Text("Ik begrijp dat ik na herstel niet mag herstarten voordat OTA en Magisk zijn afgerond.")
                    }
                } },
                confirmButton = { TextButton(onClick = { vm.confirmRestore(noRebootAcknowledged) }, enabled = noRebootAcknowledged && !vm.busy) { Text("Schrijf uitsluitend deze stock-image") } },
                dismissButton = { TextButton(onClick = vm::dismissRestorePlan) { Text("Annuleren") } })
        }
        if (rebootDialog) AlertDialog(onDismissRequest = { rebootDialog = false }, title = { Text("Herstart zelf via Motorola") },
            text = { Text("Controleer dat Motorola de OTA heeft voltooid en de Magisk-log installatie op ${vm.workflow.targetSlot} bevestigt. De app heeft de Magisk-installatie vóór de nieuwe boot niet onafhankelijk bewezen. Je bevestigt alleen deze begeleiding; de app herstart je telefoon niet. Open Motorola en kies daar zelf Herstarten.") },
            confirmButton = { TextButton(onClick = { rebootDialog = false; vm.acknowledgeReboot { openOfficial(false) } }) { Text("Begrepen, open Motorola") } },
            dismissButton = { TextButton(onClick = { rebootDialog = false }) { Text("Terug") } })
        if (stageDialog) AlertDialog(onDismissRequest = { stageDialog = false }, title = { Text("Klaarzetten voor Motorola-beoordeling") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${vm.device.build} → ${vm.firmware?.targetBuild.orEmpty()} · ${size(vm.firmware?.packageBytes ?: -1)}")
                Text("SHA-256: ${vm.firmware?.sha256.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Text("De app kopieert uitsluitend deze opnieuw geverifieerde ZIP met ondertekend reteu-kanaalbewijs naar de interne Motorola-bestandsmap, na nieuwe toestel- en stockcontroles. Dit is geen ondersteunde publieke Motorola-API en is nog niet op jouw telefoon getest. De officiële updater blijft verantwoordelijk voor geschiktheid en installatie.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = automaticRecognitionAcknowledged, onCheckedChange = { automaticRecognitionAcknowledged = it })
                    Text("Ik begrijp dat Motorola het pakket meteen kan herkennen. Ik start geen andere update en herstart nog niet.")
                }
            } },
            confirmButton = { TextButton(onClick = { stageDialog = false; vm.stagePackage(automaticRecognitionAcknowledged) }, enabled = automaticRecognitionAcknowledged && !vm.busy) { Text("Zet uitsluitend dit pakket klaar") } },
            dismissButton = { TextButton(onClick = { stageDialog = false }) { Text("Annuleren") } })
        launchFailure?.let { error -> AlertDialog(onDismissRequest = { launchFailure = null }, title = { Text("Officiële app openen") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { launchFailure = null }) { Text("Sluiten") } }) }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = colors.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun InfoCard(title: String, body: String, warning: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(if (warning) Color(0xFF3D301A) else Color(0xFF17352F), RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = if (warning) Color(0xFFFFD08A) else colors.primary, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(value.ifBlank { "onbekend" }, style = MaterialTheme.typography.bodyMedium)
    }
}
private fun size(bytes: Long): String = if (bytes < 0) "onbekend" else String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0)

private fun stageTitle(stage: UpdateStage): String = when (stage) {
    UpdateStage.IDLE -> "Nog geen update gestart"
    UpdateStage.PRECHECK_BLOCKED -> "Controles blokkeren voorbereiding"
    UpdateStage.STOCK_PREPARATION -> "Originele image voorbereiden"
    UpdateStage.STOCK_CONFIRMATION -> "Herstelplan wacht op bevestiging"
    UpdateStage.STOCK_WRITE_RUNNING -> "Stockherstel in uitvoering"
    UpdateStage.STOCK_RESTORED -> "Stock-init_boot bevestigd"
    UpdateStage.AWAITING_OFFICIAL_OTA -> "Verder in Motorola"
    UpdateStage.OTA_APPLYING -> "Motorola past update toe"
    UpdateStage.MAGISK_REQUIRED -> "OTA toegepast; Magisk nog nodig"
    UpdateStage.MAGISK_REPORTED_UNVERIFIED -> "Magisk handmatig gemeld"
    UpdateStage.REBOOT_BY_SYSTEM_UPDATER -> "Herstart beoordelen"
    UpdateStage.AWAITING_NEW_BOOT -> "Wacht op nieuwe boot"
    UpdateStage.TARGET_BOOTED -> "Nieuwe slot opgestart"
    UpdateStage.MERGING -> "Snapshot-merge loopt"
    UpdateStage.FINAL_CHECKS -> "Eindcontrole loopt"
    UpdateStage.COMPLETE -> "Update en eindcontrole bevestigd"
    UpdateStage.COMPLETE_REVIEW -> "Update bevestigd; nieuwe stockherkomst ontbreekt"
    UpdateStage.FAILED -> "Updatecontrole meldt een fout"
    UpdateStage.ROLLBACK -> "Terugkeer naar eerdere slot waargenomen"
    UpdateStage.RECOVERY_REQUIRED -> "Handmatige beoordeling nodig"
}
private fun evidenceTitle(evidence: StepEvidence): String = when (evidence) {
    StepEvidence.NONE -> "Nog niet bevestigd"
    StepEvidence.USER_REPORTED -> "Handmatig gemeld; niet onafhankelijk bewezen"
    StepEvidence.OBSERVED -> "Werkelijk uitgelezen"
    StepEvidence.CRYPTOGRAPHIC_IMAGE -> "Volledige imagehash gecontroleerd"
    StepEvidence.OBSERVED_AFTER_BOOT -> "Na de nieuwe boot werkelijk gecontroleerd"
}
private fun monitorTitle(outcome: MonitorOutcome): String = when (outcome) {
    MonitorOutcome.NEVER -> "Nog niet gecontroleerd"
    MonitorOutcome.DISABLED -> "Uitgeschakeld"
    MonitorOutcome.UNSUPPORTED_DEVICE -> "Toestelprofiel niet ondersteund"
    MonitorOutcome.ROOT_REQUIRED -> "Roottoegang ontbreekt"
    MonitorOutcome.STALE_OBSERVATION -> "Meting niet meer actueel"
    MonitorOutcome.DEVICE_BUSY -> "Toestel is bezig met OTA of snapshots"
    MonitorOutcome.NO_NEW_RELEASE -> "Geen andere doelbuild aangetroffen"
    MonitorOutcome.METADATA_UNAVAILABLE -> "Geen herkenbare Motorola-metadata"
    MonitorOutcome.NEW_METADATA -> "Nieuwe update-informatie gevonden"
    MonitorOutcome.NEW_PACKAGE_REFERENCE -> "Nieuwe pakketverwijzing gevonden"
    MonitorOutcome.ALREADY_NOTIFIED -> "Deze informatie is al gemeld"
    MonitorOutcome.NOTIFICATIONS_DISABLED -> "Informatie gevonden; meldingen uit"
    MonitorOutcome.CHECK_FAILED -> "Controle kon niet worden voltooid"
    MonitorOutcome.STORE_CORRUPT -> "Lokale opslag ongeldig; controle uitgeschakeld"
}
