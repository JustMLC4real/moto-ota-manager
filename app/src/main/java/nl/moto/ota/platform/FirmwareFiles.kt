package nl.moto.ota.platform

import android.content.Context
import android.net.Uri
import android.os.RecoverySystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.moto.ota.core.FirmwareValidator
import nl.moto.ota.core.OfficialDownloadPolicy
import nl.moto.ota.core.PackageTransfer
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class FirmwareFiles(private val context: Context) {
    suspend fun import(uri: Uri, backup: Boolean = false): File = withContext(Dispatchers.IO) {
        val limit = if (backup) 128L * 1024 * 1024 else FirmwareValidator.MAX_PACKAGE_BYTES
        val destination = File(context.filesDir, if (backup) "selected-backup.bin" else "selected-ota.zip")
        requireNotNull(context.contentResolver.openInputStream(uri)).use { copyAtomic(it, destination, limit, null) }
        destination
    }

    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        require(OfficialDownloadPolicy.accepts(url)) { "Downloadbron niet toegestaan; kies een officieel pakket via Bestanden." }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            require(connection.responseCode == 200) { "Download niet beschikbaar, verlopen of omgeleid; controleer de officiële updater opnieuw." }
            val size = connection.contentLengthLong
            require(size in 1..FirmwareValidator.MAX_PACKAGE_BYTES) { "Downloadgrootte ontbreekt of overschrijdt de veilige limiet." }
            val destination = File(context.filesDir, "selected-ota.zip")
            connection.inputStream.use { copyAtomic(it, destination, FirmwareValidator.MAX_PACKAGE_BYTES, size) }
            destination
        } finally { connection.disconnect() }
    }

    /** Verifies against the actual device's OTA cert store; never installs or reboots. */
    suspend fun verify(file: File, progress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            RecoverySystem.verifyPackage(file, RecoverySystem.ProgressListener { progress(it) }, null)
            true
        } catch (_: Exception) { false }
    }

    private suspend fun copyAtomic(input: InputStream, destination: File, limit: Long, expected: Long?) {
        val ctx = coroutineContext
        PackageTransfer.copyAtomically(input, destination, limit, expected,
            availableBytes = { context.filesDir.usableSpace }, checkCancelled = { ctx.ensureActive() })
    }
}
