package nl.moto.ota.platform.staging

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.core.staging.*
import nl.moto.ota.core.update.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * Explicit copy-only integration with Motorola's internal external-files directory.
 * Fragile and best effort: this API never calls an installer or claims recognition.
 * Motorola may notice the final ZIP immediately; the UI must confirm that separately.
 */
class MotorolaPackageStager(private val context: Context) {
    /** Historical successful readback only; does not assert the OTA file is still present. */
    suspend fun lastReceipt(run: UpdateRun): MotorolaStageReceipt? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "staging-requests")
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) || root.canonicalFile.parentFile != context.filesDir.canonicalFile)
            return@withContext null
        root.listFiles().orEmpty().filter { file ->
            file.name.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}[.]receipt[.]json")) &&
                Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.canonicalFile.parentFile == root.canonicalFile
        }.sortedByDescending { it.lastModified() }.take(128).firstNotNullOfOrNull { file -> runCatching {
            val json = file.inputStream().use { input -> val bytes = input.readNBytes(32_769)
                require(bytes.size <= 32_768); JSONObject(bytes.toString(Charsets.UTF_8)) }
            val receipt = decodeReceipt(json)
            require(receipt.runId == run.id && receipt.packageSha256 == run.packageSha256 && receipt.sourceBootId == run.sourceBootId &&
                receipt.packageBytes in 1..FirmwareValidator.MAX_PACKAGE_BYTES && receipt.oemReviewRequired && receipt.stagedAtMillis > 0 &&
                receipt.destinationPath == MotorolaStagePolicy.DESTINATION_DIRECTORY + "/" + MotorolaStagePolicy.destinationName(run.packageSha256))
            receipt
        }.getOrNull() }
    }

    suspend fun stage(pinnedFile: File, report: FirmwareReport, run: UpdateRun,
        explicitConfirmation: Boolean, automaticRecognitionAcknowledged: Boolean): MotorolaStageReceipt = withContext(Dispatchers.IO) {
        MotorolaStagePolicy.requireConfirmed(explicitConfirmation, automaticRecognitionAcknowledged)
        if (Build.VERSION.SDK_INT != 37) throw StageFailure(StageError.STAGING_RUNTIME_UNSUPPORTED)
        val expectedDirectory = File(context.filesDir, "verified-packages")
        if (pinnedFile.canonicalFile.parentFile != expectedDirectory.canonicalFile ||
            !pinnedFile.name.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}[.]zip")) ||
            !Files.isRegularFile(pinnedFile.toPath(), NOFOLLOW_LINKS) || !Files.isDirectory(expectedDirectory.toPath(), NOFOLLOW_LINKS))
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        if (!report.signatureVerified || !report.structureValid || !report.payloadHashValid ||
            !run.packageVerified || report.sha256 != run.packageSha256 || report.packageBytes != pinnedFile.length() || run.simulated)
            throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
        val external = context.getExternalFilesDir(null) ?: throw StageFailure(StageError.STAGING_PATH_REJECTED)
        if (external.canonicalPath != "/storage/emulated/0/Android/data/nl.moto.ota/files" ||
            !Files.isDirectory(external.toPath(), NOFOLLOW_LINKS)) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val jobId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "staging-requests").apply { check(isDirectory || mkdirs()) }
        if (root.canonicalFile.parentFile != context.filesDir.canonicalFile || !Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val created = System.currentTimeMillis()
        val request = JSONObject().put("schema", 1).put("job", jobId).put("pinned", pinnedFile.name)
            .put("sha256", report.sha256).put("bytes", report.packageBytes).put("run", run.encode())
            .put("clientPid", android.os.Process.myPid()).put("clientStartTicks", clientStartTicks())
            .put("confirmed", true).put("automaticRecognitionAcknowledged", true).put("created", created).put("expires", created + 3_600_000)
        writePrivate(File(root, "$jobId.request.json"), request.toString())
        writePrivate(File(root, "$jobId.heartbeat"), jobId)
        val result = invoke(context.applicationInfo.sourceDir, context.applicationInfo.nativeLibraryDir, jobId, root)
        decodeReceipt(result).also {
            if (it.runId != run.id || it.packageSha256 != report.sha256 || it.packageBytes != report.packageBytes ||
                it.sourceBootId != run.sourceBootId || it.destinationPath != MotorolaStagePolicy.DESTINATION_DIRECTORY + "/" + MotorolaStagePolicy.destinationName(report.sha256) ||
                !it.oemReviewRequired || it.androidSourceFingerprint != report.androidMetadataSourceFingerprint ||
                it.payloadSourceFingerprint != report.payloadSourceFingerprint)
                throw StageFailure(StageError.STAGING_METADATA_MISMATCH)
        }
    }

    private suspend fun invoke(apkPath: String, nativeLibraryDirectory: String, jobId: String, requests: File): JSONObject {
        if (!apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk")) ||
            !nativeLibraryDirectory.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/lib/(?:arm64|x86_64)")))
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val command = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.staging.MotorolaStageProbe $jobId '$nativeLibraryDirectory' '$apkPath'"
        coroutineContext.ensureActive()
        val process = try { ProcessBuilder("su", "--target", "0", "-c", command).redirectErrorStream(false).start() }
            catch (_: Exception) { throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED) }
        val output = ByteArrayOutputStream()
        var oversized = false
        val reader = thread(isDaemon = true) { process.inputStream.use { input -> val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break
                synchronized(output) { if (output.size() + n <= 32_768) output.write(buffer, 0, n) else oversized = true }
            }
        } }
        val errors = thread(isDaemon = true) { process.errorStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { } } }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3_600_000
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                coroutineContext.ensureActive()
                writePrivate(File(requests, "$jobId.heartbeat"), jobId)
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED)
            }
            reader.join(1000); errors.join(1000)
            if (reader.isAlive || errors.isAlive || oversized || process.exitValue() != 0)
                throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED)
            val value = try { JSONObject(synchronized(output) { output.toString("UTF-8") }) }
                catch (_: Exception) { throw StageFailure(StageError.STAGING_HELPER_INTERRUPTED) }
            if (value.has("error")) throw StageFailure(runCatching { StageError.valueOf(value.getString("error")) }.getOrDefault(StageError.STAGING_IO))
            return value
        } finally {
            if (process.isAlive) {
                // Killing su alone need not kill app_process. The dedicated helper
                // watches this durable marker and the original client's PID/start time.
                runCatching { writePrivate(File(requests, "$jobId.cancelled"), jobId) }
                process.destroyForcibly()
            }
        }
    }

    internal companion object {
        fun decodeReceipt(value: JSONObject): MotorolaStageReceipt {
            require(value.getInt("schema") == 1)
            return MotorolaStageReceipt(value.getString("run"), value.getString("sha256"), value.getLong("bytes"),
                value.getString("destination"), value.getString("boot"), value.getLong("staged"), value.getBoolean("alreadyPresent"),
                value.getBoolean("oemReviewRequired"), value.getBoolean("signedChannelProven"),
                value.getString("androidSourceFingerprint"), value.getString("payloadSourceFingerprint"))
        }
        fun clientStartTicks(): String = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19]
        fun writePrivate(file: File, text: String) {
            val atomic = AtomicFile(file); val out = atomic.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
            file.setReadable(false, false); file.setWritable(false, false)
            file.setReadable(true, true); file.setWritable(true, true)
        }
    }
}
