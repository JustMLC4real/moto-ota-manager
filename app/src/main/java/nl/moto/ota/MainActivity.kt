package nl.moto.ota

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.moto.ota.core.WorkflowPhase
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MotoApp() }
    }
}

private val colors = darkColorScheme(
    primary = Color(0xFF71DDC6), onPrimary = Color(0xFF00382E),
    secondary = Color(0xFFA8C7D7), background = Color(0xFF0B161C),
    surface = Color(0xFF14232B), surfaceVariant = Color(0xFF263A42),
    onSurface = Color(0xFFE0EDF1), onSurfaceVariant = Color(0xFFB5C8CE), error = Color(0xFFFFB4A9)
)

@Composable
fun MotoApp(vm: MotoViewModel = viewModel()) {
    MaterialTheme(colorScheme = colors) {
        val context = LocalContext.current
        var tab by remember { mutableIntStateOf(0) }
        var rootDialog by remember { mutableStateOf(false) }
        var launchFailure by remember { mutableStateOf<String?>(null) }
        val otaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importOta) }
        val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importBackup) }
        val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
        fun openOfficial(magisk: Boolean) {
            val intent = if (magisk) context.packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")
            else Intent("android.settings.SYSTEM_UPDATE_SETTINGS")
            if (intent == null) { launchFailure = "Magisk-app niet gevonden. Open Magisk zelf vanuit je startscherm."; return }
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
                            else InfoCard("Lokaal · uitsluitend uitlezend", vm.message)
                            when (tab) {
                                0 -> {
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
                                        Detail("OTA-aanbod", if (vm.candidateAvailable) "lokale Motorola-downloadverwijzing gevonden; mogelijk verouderd" else "niet vastgesteld; controleer officiële updater")
                                        Button(onClick = { rootDialog = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Controleer en bereid update voor") }
                                        Text("Deze knop leest diagnostiek. Schrijven naar partities, installeren en herstarten zijn niet geïmplementeerd.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Panel {
                                        Text("Wat houdt voorbereiding tegen?", style = MaterialTheme.typography.titleMedium)
                                        vm.readiness.blockers.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodyMedium) }
                                    }
                                }
                                1 -> {
                                    Panel {
                                        Text("Officiële updatebronnen", style = MaterialTheme.typography.titleLarge)
                                        Text("Motorola's eigen updater heeft voorrang. Er is geen bewezen publieke firmwarecatalogus voor dit toestel. Lokale metadata is een aanwijzing, geen actuele serveraanbieding.")
                                        Button(onClick = { openOfficial(false) }, enabled = !vm.demo && !vm.busy) { Text("Open systeemupdates") }
                                        OutlinedButton(onClick = vm::download, enabled = vm.candidateAvailable && !vm.busy && !vm.demo) { Text("Download gevonden Motorola-pakket") }
                                        Text("Downloads starten alleen op jouw verzoek. Verlopen links, omleidingen en onbekende hosts worden geweigerd.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Panel {
                                        Text("OTA-ZIP onderzoeken", style = MaterialTheme.typography.titleLarge)
                                        Text("Selecteer een officieel A/B-pakket. De app kopieert het lokaal, controleert ZIP/metadata, bronfirmware, payload-header en SHA-256. Een bestandsnaam bewijst geen regio.")
                                        Button(onClick = { otaPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !vm.busy && !vm.demo) { Text("Kies officiële OTA-ZIP") }
                                        TextButton(onClick = vm::inspectSaved, enabled = !vm.busy && !vm.demo) { Text("Onderzoek eerder opgeslagen pakket") }
                                        vm.firmware?.let { f ->
                                            Detail("Huidig → doel", "${vm.device.build} → ${f.targetBuild.ifBlank { "onbekend" }}")
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
                                    Panel {
                                        Text("Begeleide update", style = MaterialTheme.typography.titleLarge)
                                        Text(vm.workflow.phase.name, color = colors.primary, style = MaterialTheme.typography.titleMedium)
                                        Text(vm.workflow.message)
                                        Button(onClick = vm::startJournal, enabled = !vm.busy && !vm.demo && vm.readiness.ready && vm.firmware?.readyForOfficialReview == true) { Text("Start gecontroleerd workflowjournaal") }
                                        Text("Het journaal bewaart verwachtingen. Alleen nieuwe toestelobservaties kunnen OTA-toepassing, doelslot, root en afgeronde merge bevestigen.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Panel {
                                        val steps = listOf(
                                            "1 · Controle" to "Lees model, kanaal, bronfirmware, root, slots, accu en opslag uit. Actieve of onbekende snapshotstatus blokkeert.",
                                            "2 · Back-up en pakket" to "Bewijs de originele init_boot voor de huidige build. Onderzoek het officiële pakket en verifieer de ondertekening.",
                                            "3 · Stock-herstel via Magisk" to "Alleen na bewezen actuele stock-herkomst: gebruik Magisk's officiële Restore Images. Geen herstart tussen stock-herstel en OTA/Magisk, omdat root dan kan verdwijnen. Deze app voert geen restore uit.",
                                            "4 · Motorola OTA" to "Gebruik de officiële systeemupdater. De historische kopie naar Android/data is een fragiele, niet-geautomatiseerde observatie. Controleer bronhash-/signaturefouten; herstart nog niet.",
                                            "5 · Magisk op inactieve slot" to "Pas nadat de OTA aantoonbaar is toegepast: gebruik Magisk → Install to Inactive Slot (After OTA). Controleer de doelslot en init_boot-partitie in de Magisk-log. Een handmatige melding blijft onbewezen.",
                                            "6 · Herstart en eindcontrole" to "Herstart uitsluitend zelf via Motorola na alle controles. Lees daarna opnieuw uit: doelfingerprint, juiste slot, root, bootstatus, modules en voltooide snapshot-merge."
                                        )
                                        steps.forEach { (title, body) -> Text(title, fontWeight = FontWeight.Bold); Text(body) }
                                        OutlinedButton(onClick = { openOfficial(true) }, enabled = !vm.busy && !vm.demo) { Text("Open Magisk") }
                                        OutlinedButton(onClick = { openOfficial(false) }, enabled = !vm.busy && !vm.demo) { Text("Open Motorola-systeemupdates") }
                                        if (vm.workflow.phase == WorkflowPhase.AWAITING_STOCK_RESTORE) OutlinedButton(onClick = vm::reportStock, enabled = !vm.busy) { Text("Meld handmatig stock-herstel") }
                                        if (vm.workflow.otaAppliedObserved) OutlinedButton(onClick = vm::reportMagisk, enabled = !vm.busy) { Text("Meld handmatige Magisk-installatie") }
                                        TextButton(onClick = { rootDialog = true }, enabled = !vm.busy) { Text("Lees actuele voortgang opnieuw uit") }
                                        TextButton(onClick = vm::resetJournal, enabled = !vm.busy && !vm.demo) { Text("Wis alleen lokaal workflowjournaal") }
                                    }
                                }
                                else -> {
                                    Panel {
                                        Text("Geavanceerde diagnose", style = MaterialTheme.typography.titleLarge)
                                        Detail("Werkelijk gemeten model", vm.inspection?.properties?.get("ro.product.model").orEmpty())
                                        Detail("Systeemfingerprint", vm.device.fingerprint)
                                        Detail("Vendor-fingerprint", vm.device.vendorFingerprint)
                                        Detail("Virtual A/B", vm.device.virtualAb?.toString().orEmpty())
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
                                            b.errors.forEach { Text("• $it") }
                                        }
                                    }
                                    Panel {
                                        Text("Privacy en lokale controle", style = MaterialTheme.typography.titleLarge)
                                        Text("Het rapport bevat alleen toegestane diagnosevelden. Geen ruwe logs, IMEI, serienummer, boot-id, accounts, module-ID's of ondertekende URL's. Er wordt niets automatisch geüpload.")
                                        Button(onClick = { reportPicker.launch("moto-ota-diagnose.txt") }, enabled = !vm.busy) { Text("Exporteer diagnostisch rapport") }
                                        OutlinedButton(onClick = { vm.scan(false) }, enabled = !vm.busy) { Text("Controle zonder root") }
                                        OutlinedButton(onClick = vm::showDemo, enabled = !vm.busy) { Text("Bekijk veilige simulatie") }
                                        Text("0.1.0-safe · handmatige toestelvalidatie vereist", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (rootDialog) AlertDialog(onDismissRequest = { rootDialog = false }, title = { Text("Uitlezende rootdiagnose") },
            text = { Text("Verleen Moto OTA Manager eerst toegang in Magisk → Superuser. Magisk 31.1 toont geen reactieve rootprompt. De app leest eigenschappen, snapshots, bootstatus, OTA-metadata, back-ups en module-inventaris. Er wordt niets hersteld, geïnstalleerd of herstart.") },
            confirmButton = { TextButton(onClick = { rootDialog = false; vm.scan(true) }) { Text("Start uitlezen") } },
            dismissButton = { TextButton(onClick = { rootDialog = false }) { Text("Annuleren") } })
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
