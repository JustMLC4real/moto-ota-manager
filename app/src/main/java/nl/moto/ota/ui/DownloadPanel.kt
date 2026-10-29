package nl.moto.ota.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.moto.ota.core.DownloadJob
import nl.moto.ota.core.DownloadStatus
import nl.moto.ota.core.TransferError
import java.util.Locale

/** UI contains only public job metadata, never the signed package URL. */
@Composable
fun DownloadProgressPanel(
    job: DownloadJob?,
    busy: Boolean,
    enabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onInspect: () -> Unit
) {
    if (job == null) return
    val active = job.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.COMMITTING)
    val title = when (job.status) {
        DownloadStatus.QUEUED -> if (job.wifiOnly) "Wachten op een ongemeten verbinding" else "Download staat klaar"
        DownloadStatus.RUNNING -> "Officiële OTA downloaden"
        DownloadStatus.COMMITTING -> "Download veilig opslaan"
        DownloadStatus.PAUSED -> "Download gepauzeerd"
        DownloadStatus.FAILED -> "Download onderbroken"
        DownloadStatus.CANCELLED -> "Download geannuleerd"
        DownloadStatus.COMPLETED -> "Download voltooid"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (job.targetBuild.isNotBlank()) Text("Doelbuild: ${job.targetBuild}")
            if (job.expectedBytes > 0) {
                val fraction = (job.downloadedBytes.toDouble() / job.expectedBytes).coerceIn(0.0, 1.0).toFloat()
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${size(job.downloadedBytes)} / ${size(job.expectedBytes)} · ${(fraction * 100).toInt()}%")
            } else if (active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Bestandsgrootte wordt bij de server gecontroleerd.")
            }
            if (job.wifiOnly && active) Text("Alleen via een ongemeten verbinding.", style = MaterialTheme.typography.bodySmall)
            if (job.status == DownloadStatus.COMPLETED) {
                Text("Onderzoek eerst het pakket. Een volledige download bewijst nog niet dat de firmware authentiek of geschikt is.")
                OutlinedButton(onClick = onInspect, enabled = enabled && !busy) { Text("Pakket onderzoeken") }
            } else {
                errorText(job.errorCode)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (job.status == DownloadStatus.PAUSED) {
                    Text("Alleen veilig opgeslagen bytes worden hervat. Zonder betrouwbare servervalidator begint de download opnieuw.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (job.errorCode == TransferError.SOURCE_CHANGED) {
                    Text("Het gekozen OTA-bestand blijft behouden. Start desgewenst een nieuwe download met een actuele officiële link.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) OutlinedButton(onClick = onPause, enabled = !busy) { Text("Pauzeren") }
                    val resumable = job.canResume && job.errorCode !in setOf(
                        TransferError.SOURCE_CHANGED, TransferError.SECRET_UNAVAILABLE, TransferError.STATE_CORRUPT)
                    if (resumable) OutlinedButton(onClick = onResume, enabled = enabled && !busy) { Text("Hervatten") }
                    if (!job.finished) TextButton(onClick = onCancel, enabled = !busy) { Text("Annuleren") }
                }
            }
        }
    }
}

private fun size(bytes: Long): String = if (bytes >= 1_073_741_824L)
    String.format(Locale.getDefault(), "%.2f GiB", bytes / 1_073_741_824.0)
else String.format(Locale.getDefault(), "%.1f MiB", bytes / 1_048_576.0)

private fun errorText(code: TransferError): String? = when (code) {
    TransferError.NONE -> null
    TransferError.URL_NOT_ALLOWED -> "Deze link hoort niet bij de toegestane officiële Motorola-downloadserver. Kies een officiële link of een lokaal pakket."
    TransferError.NETWORK -> "De verbinding is onderbroken. Hervat wanneer je weer verbinding hebt."
    TransferError.HTTP_STATUS -> "De server weigert de aanvraag. De link kan verlopen zijn; gebruik zo nodig een nieuwe officiële link."
    TransferError.REDIRECT -> "De server verwijst naar een ander adres. Automatisch doorsturen is uitgeschakeld; kies een actuele officiële link."
    TransferError.LENGTH_REQUIRED -> "De server meldt geen betrouwbare bestandsgrootte. Kies een andere officiële link of importeer het pakket."
    TransferError.INVALID_RANGE -> "Het antwoord past niet bij de opgeslagen download. Hervatten begint veilig opnieuw."
    TransferError.ENTITY_CHANGED -> "Het bestand op de server is gewijzigd. Hervatten begint veilig opnieuw."
    TransferError.STORAGE_LOW -> "Er is te weinig vrije opslag. Maak ruimte voor het resterende bestand plus 256 MiB reserve."
    TransferError.SIZE_LIMIT -> "Dit pakket overschrijdt de veilige downloadlimiet van 12 GiB."
    TransferError.HASH_MISMATCH -> "De SHA-256 wijkt af van de verwachte waarde. Het pakket is niet geselecteerd; hervatten downloadt opnieuw."
    TransferError.STATE_CORRUPT -> "De opgeslagen downloadstatus is onbruikbaar. Annuleer deze taak en start een nieuwe download."
    TransferError.SECRET_UNAVAILABLE -> "De beveiligd opgeslagen link kan niet worden geopend. Annuleer deze taak en start opnieuw met een officiële link."
    TransferError.SOURCE_CHANGED -> "Het gekozen OTA-bestand is tijdens de download gewijzigd en wordt niet overschreven."
    TransferError.IO -> "De app kon het bestand niet veilig lezen of opslaan. Controleer de vrije opslag en probeer opnieuw."
    TransferError.CANCELLED -> "Het tijdelijke downloadbestand is verwijderd. Een eerder gekozen OTA-pakket blijft behouden."
    TransferError.FOREGROUND_UNAVAILABLE -> "Android kon de zichtbare downloadtaak niet starten. Open de app en probeer te hervatten."
}
