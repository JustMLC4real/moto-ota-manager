package nl.moto.ota.platform.download

import android.content.Context
import android.os.FileObserver
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One explicit user download; WorkManager input contains an opaque job ID only. */
class DownloadController(context: Context) {
    private val app = context.applicationContext
    private val store = DownloadStore(app)
    private val secrets: DownloadSecretStore = KeystoreDownloadSecrets(store)
    private val manager = WorkManager.getInstance(app)

    fun current(): DownloadJob? = try {
        store.record()?.job?.let { job ->
            if (job.status == DownloadStatus.COMPLETED &&
                (!store.destination.isFile || store.destination.length() != job.expectedBytes))
                job.copy(status = DownloadStatus.FAILED, errorCode = TransferError.STATE_CORRUPT)
            else job
        }
    } catch (_: Exception) {
        DownloadJob(CORRUPT_ID, DownloadStatus.FAILED, errorCode = TransferError.STATE_CORRUPT)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<DownloadJob?> = callbackFlow {
        val events = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.CREATE
        val observer = object : FileObserver(store.directory, events) {
            override fun onEvent(event: Int, path: String?) { trySend(current()) }
        }
        observer.startWatching()
        trySend(current())
        awaitClose { observer.stopWatching() }
    }.distinctUntilChanged()

    suspend fun enqueue(
        url: String,
        device: DeviceState,
        targetBuild: String,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        wifiOnly: Boolean = false
    ): String = action {
        require(device.exactProfileMatch && !device.simulated && device.build.matches(BUILD) && device.fingerprint.isNotBlank()) {
            "Download requires the observed target device profile"
        }
        require(targetBuild.matches(BUILD) && targetBuild != device.build) { "A new explicit target build is required" }
        require(expectedBytes == null || expectedBytes in 1..FirmwareValidator.MAX_PACKAGE_BYTES)
        require(expectedSha256 == null || expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
        if (!OfficialDownloadPolicy.accepts(url)) throw TransferFailure(TransferError.URL_NOT_ALLOWED)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val old = runCatching { store.record() }.getOrNull()
        secrets.put(id, url)
        val manifest = DownloadManifest(
            DownloadJob(id, DownloadStatus.QUEUED, expectedBytes = expectedBytes ?: 0,
                sourceBuild = device.build, targetBuild = targetBuild,
                createdAtMillis = now, updatedAtMillis = now, wifiOnly = wifiOnly),
            device.fingerprint, device.model, device.device, device.channel, expectedSha256?.lowercase()
        )
        try { store.begin(manifest) }
        catch (_: Exception) { secrets.remove(id); throw TransferFailure(TransferError.IO) }
        try { schedule(manifest.job) }
        catch (_: Exception) {
            // A scheduler timeout may follow a successful enqueue. Persisting FAILED
            // also prevents that work from publishing; credentials remain resumable.
            store.failed(id, TransferError.IO)
            throw TransferFailure(TransferError.IO)
        } finally {
            if (old != null && old.job.id != id) {
                store.partial(old.job.id).delete()
                secrets.remove(old.job.id)
            }
        }
        id
    }

    /** Await this before replacing selected-ota.zip through a manual SAF import. */
    suspend fun pause() { pauseExpected(null) }

    internal suspend fun pauseExpected(expectedId: String?) = action { pauseLocked(expectedId) }

    /** Serialize selection replacement with enqueue/resume over all controller instances.
     * Root imports call this only around their final app-side commit. SAF callers may
     * wrap FirmwareFiles.import directly. Never nest or call another controller action
     * from the block: the shared mutex is deliberately not reentrant.
     */
    suspend fun <T> withPausedSelection(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        ACTIONS.withLock {
            pauseLocked(null)
            block()
        }
    }

    private fun pauseLocked(expectedId: String?) {
        val job = recordForAction(expectedId)?.job ?: return
        if (expectedId != null && expectedId != job.id) return
        val paused = store.update(job.id) { manifest ->
            if (manifest.job.status in ACTIVE) manifest.copy(job = manifest.job.copy(
                status = DownloadStatus.PAUSED, errorCode = TransferError.NONE,
                downloadedBytes = manifest.checkpoint?.durableBytes ?: 0,
                updatedAtMillis = System.currentTimeMillis()))
            else manifest
        }
        if (paused?.job?.status == DownloadStatus.PAUSED) stopScheduledWork()
    }

    suspend fun resume(): Unit = action {
        val manifest = store.record() ?: return@action
        require(manifest.job.canResume) { "Download cannot be resumed in this state" }
        require(manifest.job.errorCode != TransferError.SOURCE_CHANGED) {
            "The selected package changed; start a new explicit download"
        }
        secrets.read(manifest.job.id)
        val reset = manifest.job.errorCode in setOf(TransferError.HASH_MISMATCH, TransferError.ENTITY_CHANGED, TransferError.INVALID_RANGE)
        if (reset) store.partial(manifest.job.id).delete()
        val queued = store.update(manifest.job.id) { current -> current.copy(
            checkpoint = if (reset) null else current.checkpoint,
            job = current.job.copy(status = DownloadStatus.QUEUED,
                downloadedBytes = if (reset) 0 else current.checkpoint?.durableBytes ?: 0,
                packageSha256 = if (reset) "" else current.job.packageSha256,
                errorCode = TransferError.NONE, updatedAtMillis = System.currentTimeMillis()))
        } ?: return@action
        try { schedule(queued.job) }
        catch (_: Exception) { store.failed(queued.job.id, TransferError.IO); throw TransferFailure(TransferError.IO) }
    }

    suspend fun cancel() { cancelExpected(null) }

    internal suspend fun cancelExpected(expectedId: String?) = action {
        val job = recordForAction(expectedId)?.job ?: return@action
        if ((expectedId != null && expectedId != job.id) || job.status == DownloadStatus.COMPLETED) return@action
        store.update(job.id) { manifest -> manifest.copy(job = manifest.job.copy(
            status = DownloadStatus.CANCELLED, errorCode = TransferError.CANCELLED,
            downloadedBytes = manifest.checkpoint?.durableBytes ?: 0,
            updatedAtMillis = System.currentTimeMillis())) }
        // Persist cancellation first: a delayed WorkManager cancellation cannot commit.
        try { stopScheduledWork() }
        finally { store.partial(job.id).delete(); secrets.remove(job.id) }
    }

    suspend fun clearCompleted(): Unit = action {
        val job = store.record()?.job ?: return@action
        if (job.finished) {
            secrets.remove(job.id)
            store.partial(job.id).delete()
            store.removeCurrentIfFinished()
        }
    }

    private suspend fun <T> action(block: () -> T): T = withContext(Dispatchers.IO) {
        // Shared by all controller instances, including the notification receiver.
        ACTIONS.withLock { block() }
    }

    private fun stopScheduledWork() {
        try { manager.cancelUniqueWork(UNIQUE_WORK).result.get(10, TimeUnit.SECONDS) }
        catch (_: Exception) { throw TransferFailure(TransferError.IO) }
    }

    /** No recovery on reads or resume: only explicit pause/selection/cancel may
     * quarantine a corrupt journal. An old notification cannot reset an unknown job.
     */
    private fun recordForAction(expectedId: String?): DownloadManifest? = try {
        store.record()
    } catch (failure: TransferFailure) {
        if (failure.code != TransferError.STATE_CORRUPT) throw failure
        if (expectedId != null) null
        else {
            stopScheduledWork()
            store.quarantineIfCorrupt()
            store.record()
        }
    }

    private fun schedule(job: DownloadJob) {
        val constraints = Constraints.Builder().setRequiredNetworkType(
            if (job.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<OtaDownloadWorker>()
            .setInputData(workDataOf(OtaDownloadWorker.KEY_JOB_ID to job.id))
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG).build()
        manager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, work).result.get(10, TimeUnit.SECONDS)
    }

    companion object {
        const val UNIQUE_WORK = "moto-ota-explicit-download"
        const val TAG = "moto-ota-download"
        private val ACTIONS = Mutex()
        private val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.COMMITTING)
        private val BUILD = Regex("[A-Za-z0-9_.-]{1,100}")
        private const val CORRUPT_ID = "00000000-0000-0000-0000-000000000000"
    }
}
