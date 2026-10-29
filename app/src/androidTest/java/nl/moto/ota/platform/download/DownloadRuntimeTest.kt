package nl.moto.ota.platform.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.*
import nl.moto.ota.platform.FirmwareFiles
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/** Real Android storage/Keystore and worker recovery, isolated to disposable emulator cache.
 * No HTTP connection, notification, root, partition or update_engine call is made.
 */
@RunWith(AndroidJUnit4::class)
class DownloadRuntimeTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: DownloadStore
    private lateinit var secrets: DownloadSecretStore
    private lateinit var keyAlias: String
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val otherId = "87654321-1234-1234-1234-123456789abc"
    private val url = "https://dlmgredg-vz.gtm.svcmot.com/test.zip?token=private-test-token"

    @Before fun setUp() {
        assumeTrue("Download runtime tests require a disposable emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        root = File(context.cacheDir, "download-runtime-${UUID.randomUUID()}").apply { check(mkdirs()) }
        store = DownloadStore(context, root)
        keyAlias = "MotoOtaManager.DownloadUrl.test.${UUID.randomUUID()}"
        secrets = KeystoreDownloadSecrets(store, keyAlias)
    }

    @After fun tearDown() {
        if (::keyAlias.isInitialized) KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun manifest(status: DownloadStatus = DownloadStatus.RUNNING) = DownloadManifest(
        DownloadJob(id, status, sourceBuild = "SOURCE", targetBuild = "TARGET", createdAtMillis = 1, updatedAtMillis = 1),
        "motorola/merc/merc:17/SOURCE/1:user/release-keys", "XT2607-1", "merc", "reteu")

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expect(code: TransferError, block: () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (error: TransferFailure) { assertEquals(code, error.code); assertEquals(code.name, error.message) }
    }

    private fun worker(jobId: String = id): OtaDownloadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                OtaDownloadWorker(appContext, workerParameters, store, secrets,
                    TransferTransport { throw AssertionError("Recovery/state tests must not request HTTP") })
        }
        return TestListenableWorkerBuilder<OtaDownloadWorker>(context)
            .setWorkerFactory(factory).setInputData(workDataOf(OtaDownloadWorker.KEY_JOB_ID to jobId)).build()
    }

    private fun controllerContext():Context=object:ContextWrapper(context){
        override fun getFilesDir():File=root
        override fun getApplicationContext():Context=this
    }

    @Test fun keystoreEncryptsWithRandomIvAndSurvivesObjectRecreation() {
        secrets.put(id, url)
        val first = store.secret(id).readBytes()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("private-test-token"))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("https://"))
        assertEquals(url, KeystoreDownloadSecrets(DownloadStore(context, root), keyAlias).read(id))
        secrets.put(id, url)
        assertFalse(first.contentEquals(store.secret(id).readBytes()))
        assertEquals(url, secrets.read(id))
    }

    @Test fun changedCiphertextAndSwappedJobSecretsFailAuthentication() {
        secrets.put(id, url)
        store.secret(otherId).writeBytes(store.secret(id).readBytes())
        expect(TransferError.SECRET_UNAVAILABLE) { secrets.read(otherId) }
        val bytes = store.secret(id).readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        store.secret(id).writeBytes(bytes)
        expect(TransferError.SECRET_UNAVAILABLE) { secrets.read(id) }
        expect(TransferError.URL_NOT_ALLOWED) { secrets.put(id, "https://example.com/test.zip?token=secret") }
    }

    @Test fun manifestReloadsProgressAndNeverContainsSignedUrl() {
        secrets.put(id, url)
        store.begin(manifest())
        val checkpoint = TransferCheckpoint(ResumableTransfer.sourceIdentity(url), 2, 4, "\"entity\"")
        store.checkpoint(id, checkpoint)
        val reloaded = DownloadStore(context, root).record()!!
        assertEquals(checkpoint, reloaded.checkpoint)
        assertEquals(2L, reloaded.job.downloadedBytes)
        val text = File(store.directory, "current.manifest").readText()
        assertFalse(text.contains("https://")); assertFalse(text.contains("private-test-token"))
        assertFalse(reloaded.toString().contains("private-test-token"))
    }

    @Test fun interruptedAtomicManifestWriteKeepsPreviousJournalAndCorruptionFailsClosed() {
        store.begin(manifest())
        val previous = store.record()
        val file = AtomicFile(File(store.directory, "current.manifest"))
        val incomplete = file.startWrite()
        incomplete.write("incomplete".toByteArray())
        file.failWrite(incomplete)
        assertEquals(previous, DownloadStore(context, root).record())
        val corrupt = file.startWrite()
        corrupt.write("schema=unsupported\n".toByteArray())
        file.finishWrite(corrupt)
        expect(TransferError.STATE_CORRUPT) { store.record() }
    }

    @Test fun pauseAndCancelBlockCommitWithoutChangingPreviousPackage() {
        val bytes = byteArrayOf(1, 2, 3)
        for (status in listOf(DownloadStatus.PAUSED, DownloadStatus.CANCELLED)) {
            store.destination.writeText("previous")
            store.begin(manifest())
            store.partial(id).writeBytes(bytes)
            store.update(id) { it.copy(job = it.job.copy(status = status)) }
            try { store.commit(id, store.partial(id), CompletedTransfer(3, digest(bytes))); fail("Commit after $status") }
            catch (_: CancellationException) { }
            assertEquals("previous", store.destination.readText())
            assertArrayEquals(bytes, store.partial(id).readBytes())
        }
    }

    @Test fun manuallyReplacedSelectedPackageIsPreserved() {
        store.destination.writeText("previous")
        store.begin(manifest())
        val replacement = File(root, "manual-selection.zip").apply { writeText("manual selection") }
        Files.move(replacement.toPath(), store.destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val bytes = byteArrayOf(1, 2, 3)
        store.partial(id).writeBytes(bytes)
        expect(TransferError.SOURCE_CHANGED) { store.commit(id, store.partial(id), CompletedTransfer(3, digest(bytes))) }
        assertEquals("manual selection", store.destination.readText())
    }

    @Test fun atomicCommitPublishesExactBytesAndDurableCompletion() {
        store.destination.writeText("previous")
        store.begin(manifest())
        val bytes = byteArrayOf(1, 2, 3)
        store.partial(id).writeBytes(bytes)
        store.commit(id, store.partial(id), CompletedTransfer(3, digest(bytes)))
        assertFalse(store.partial(id).exists())
        assertArrayEquals(bytes, store.destination.readBytes())
        val job = DownloadStore(context, root).record()!!.job
        assertEquals(DownloadStatus.COMPLETED, job.status)
        assertEquals(digest(bytes), job.packageSha256)
        assertEquals(3L, job.downloadedBytes)
    }

    @Test fun productionWorkerRecoversCrashAfterRenameWithoutHttpOrForeground() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        secrets.put(id, url)
        val committing = manifest(DownloadStatus.COMMITTING).let { it.copy(job = it.job.copy(
            downloadedBytes = 3, expectedBytes = 3, packageSha256 = digest(bytes))) }
        store.begin(committing)
        // The durable COMMITTING journal was written, rename succeeded, then the process died.
        store.destination.writeBytes(bytes)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(DownloadStatus.COMPLETED, store.record()!!.job.status)
        assertFalse(store.secret(id).exists())
    }

    @Test fun productionWorkerRefusesChangedFileInCommitRecoveryWindow() = runBlocking {
        val original = byteArrayOf(1, 2, 3)
        val committing = manifest(DownloadStatus.COMMITTING).let { it.copy(job = it.job.copy(
            downloadedBytes = 3, expectedBytes = 3, packageSha256 = digest(original))) }
        store.begin(committing)
        store.destination.writeBytes(byteArrayOf(9, 9, 9))
        worker().doWork()
        assertEquals(DownloadStatus.FAILED, store.record()!!.job.status)
        assertEquals(TransferError.SOURCE_CHANGED, store.record()!!.job.errorCode)
        assertArrayEquals(byteArrayOf(9, 9, 9), store.destination.readBytes())
    }

    @Test fun pausedCancelledAndFailedProductionWorkersNeverRunNetwork() = runBlocking {
        for (status in listOf(DownloadStatus.PAUSED, DownloadStatus.CANCELLED, DownloadStatus.FAILED)) {
            store.begin(manifest(status))
            worker().doWork()
            assertEquals(status, store.record()!!.job.status)
        }
        store.begin(manifest(DownloadStatus.FAILED))
        store.failed(id, TransferError.NONE, queued = true)
        assertEquals(DownloadStatus.FAILED, store.record()!!.job.status)
    }

    @Test fun explicitCancelQuarantinesCorruptManifestAndBlocksOldWorkerCommit() = runBlocking {
        store.destination.writeText("previous selected OTA")
        store.begin(manifest())
        val partial=store.partial(id).apply{writeBytes(byteArrayOf(1,2,3))}
        File(store.directory,"current.manifest").writeText("corrupt journal")
        val controller=DownloadController(controllerContext())
        assertEquals(TransferError.STATE_CORRUPT,controller.current()!!.errorCode)
        controller.cancel()
        assertNull(controller.current());assertNull(store.record());assertFalse(store.canRun(id))
        assertEquals("previous selected OTA",store.destination.readText())
        val quarantined=File(store.directory,"quarantine").listFiles()!!.single()
        assertEquals("corrupt journal",quarantined.readText())
        try{store.commit(id,partial,CompletedTransfer(3,digest(byteArrayOf(1,2,3))));fail("Old worker committed")}
        catch(_:CancellationException){}
        assertEquals("previous selected OTA",store.destination.readText())
    }

    @Test fun explicitManualImportRecoversCorruptJournalWithoutResumingAnyDownload() = runBlocking {
        store.destination.writeText("previous selected OTA")
        store.begin(manifest())
        File(store.directory,"current.manifest").writeText("invalid journal")
        val selected=File(root,"user-selected.zip").apply{writeBytes(byteArrayOf(7,8,9))}
        val isolated=controllerContext();val controller=DownloadController(isolated)
        controller.withPausedSelection{FirmwareFiles(isolated).import(Uri.fromFile(selected))}
        assertNull(controller.current());assertFalse(store.canRun(id))
        assertArrayEquals(byteArrayOf(7,8,9),store.destination.readBytes())
        assertTrue(File(store.directory,"quarantine").listFiles()!!.isNotEmpty())
        worker().doWork() // Missing current job returns before the injected HTTP transport.
        assertArrayEquals(byteArrayOf(7,8,9),store.destination.readBytes())
    }
}
