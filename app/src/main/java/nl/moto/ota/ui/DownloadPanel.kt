package nl.moto.ota.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.moto.ota.R
import nl.moto.ota.core.DownloadJob
import nl.moto.ota.core.DownloadStatus
import nl.moto.ota.core.TransferError

/** Only public progress metadata is displayed. The signed URL stays private. */
@Composable
fun DownloadProgressPanel(job: DownloadJob?, busy: Boolean, enabled: Boolean, onPause: () -> Unit,
    onResume: () -> Unit, onCancel: () -> Unit, onInspect: () -> Unit) {
    if (job == null) return
    val active = job.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.COMMITTING)
    Panel {
        Text(stringResource(when (job.status) {
            DownloadStatus.QUEUED -> if (job.wifiOnly) R.string.ui_download_wait_wifi else R.string.ui_download_queued
            DownloadStatus.RUNNING -> R.string.ui_download_running
            DownloadStatus.COMMITTING -> R.string.ui_download_committing
            DownloadStatus.PAUSED -> R.string.ui_download_paused
            DownloadStatus.FAILED -> R.string.ui_download_failed
            DownloadStatus.CANCELLED -> R.string.ui_download_cancelled
            DownloadStatus.COMPLETED -> R.string.ui_download_completed
        }), style = MaterialTheme.typography.titleMedium)
        if (job.targetBuild.isNotBlank()) Detail(R.string.ui_target_build, job.targetBuild)
        if (job.expectedBytes > 0) {
            val fraction = (job.downloadedBytes.toDouble() / job.expectedBytes).coerceIn(0.0, 1.0).toFloat()
            AnimatedProgress(fraction)
            Text(stringResource(R.string.ui_download_progress, displaySize(job.downloadedBytes), displaySize(job.expectedBytes), (fraction * 100).toInt()))
        } else if (active) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.ui_download_getting_size), style = MaterialTheme.typography.bodySmall)
        }
        if (job.wifiOnly && active) Text(stringResource(R.string.ui_download_wifi_only), style = MaterialTheme.typography.bodySmall)
        if (job.status == DownloadStatus.COMPLETED) {
            Text(stringResource(R.string.ui_download_verify_note), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onInspect, enabled = enabled && !busy) { Text(stringResource(R.string.ui_package_inspect)) }
        } else {
            errorText(job.errorCode)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (job.status == DownloadStatus.PAUSED) Text(stringResource(R.string.ui_download_resume_note), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) OutlinedButton(onClick = onPause, enabled = !busy) { Text(stringResource(R.string.ui_pause)) }
                val resumable = job.canResume && job.errorCode !in setOf(TransferError.SOURCE_CHANGED, TransferError.SECRET_UNAVAILABLE, TransferError.STATE_CORRUPT)
                if (resumable) OutlinedButton(onClick = onResume, enabled = enabled && !busy) { Text(stringResource(R.string.ui_resume)) }
                if (!job.finished) TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.ui_cancel)) }
            }
        }
    }
}

@Composable
private fun errorText(code: TransferError): String? {
    if (code == TransferError.NONE) return null
    return stringResource(when (code) {
        TransferError.URL_NOT_ALLOWED -> R.string.ui_download_error_url
        TransferError.NETWORK -> R.string.ui_download_error_network
        TransferError.HTTP_STATUS -> R.string.ui_download_error_http
        TransferError.REDIRECT -> R.string.ui_download_error_redirect
        TransferError.LENGTH_REQUIRED -> R.string.ui_download_error_length
        TransferError.INVALID_RANGE -> R.string.ui_download_error_range
        TransferError.ENTITY_CHANGED -> R.string.ui_download_error_changed
        TransferError.STORAGE_LOW -> R.string.ui_download_error_storage
        TransferError.SIZE_LIMIT -> R.string.ui_download_error_limit
        TransferError.HASH_MISMATCH -> R.string.ui_download_error_hash
        TransferError.STATE_CORRUPT -> R.string.ui_download_error_state
        TransferError.SECRET_UNAVAILABLE -> R.string.ui_download_error_secret
        TransferError.SOURCE_CHANGED -> R.string.ui_download_error_source
        TransferError.IO -> R.string.ui_download_error_io
        TransferError.CANCELLED -> R.string.ui_download_error_cancelled
        TransferError.FOREGROUND_UNAVAILABLE -> R.string.ui_download_error_foreground
        TransferError.NONE -> R.string.ui_unknown
    })
}
