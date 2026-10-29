package nl.moto.ota.platform.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Production worker performs only app-private HTTPS byte transfer; never root or OTA apply. */
class OtaDownloadWorker internal constructor(context:Context,parameters:WorkerParameters,
    private val store:DownloadStore,private val secrets:DownloadSecretStore,
    private val transport:TransferTransport):CoroutineWorker(context,parameters) {
    constructor(context:Context,parameters:WorkerParameters):this(context,parameters,
        DownloadStore(context.applicationContext),KeystoreDownloadSecrets(DownloadStore(context.applicationContext)),OfficialHttpsTransport())
    override suspend fun getForegroundInfo():ForegroundInfo {
        val id=inputData.getString(KEY_JOB_ID)?:throw TransferFailure(TransferError.STATE_CORRUPT)
        val job=store.record()?.job?.takeIf { it.id==id }?:throw TransferFailure(TransferError.STATE_CORRUPT)
        return DownloadNotifications.info(applicationContext,job)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val id=inputData.getString(KEY_JOB_ID)?:return@withContext Result.failure(workDataOf(KEY_ERROR to TransferError.STATE_CORRUPT.name))
        try { DownloadStore.validId(id) }catch(_:Exception){return@withContext Result.failure(workDataOf(KEY_ERROR to TransferError.STATE_CORRUPT.name))}
        val initial=try { store.record()?.takeIf { it.job.id==id } }catch(_:Exception){null}
            ?:return@withContext Result.failure(workDataOf(KEY_ERROR to TransferError.STATE_CORRUPT.name))
        if(initial.job.status in setOf(DownloadStatus.PAUSED,DownloadStatus.CANCELLED,DownloadStatus.COMPLETED))return@withContext Result.success()
        if(initial.job.status==DownloadStatus.FAILED)return@withContext Result.failure(workDataOf(KEY_ERROR to initial.job.errorCode.name))
        val ctx=coroutineContext
        val lease=try { FileOutputStream(store.lease(id),true).channel }catch(_:IOException){
            runCatching{store.failed(id,TransferError.IO)}
            return@withContext Result.failure(workDataOf(KEY_ERROR to TransferError.IO.name))
        }
        val lock=try { lease.tryLock() }catch(_:OverlappingFileLockException){null}catch(_:IOException){
            lease.close();runCatching{store.failed(id,TransferError.IO)}
            return@withContext Result.failure(workDataOf(KEY_ERROR to TransferError.IO.name))
        }
        if(lock==null){lease.close();return@withContext Result.retry()}
        try {
            fun active(){ctx.ensureActive();if(!store.canRun(id))throw CancellationException()}
            active()
            // Resolve the atomic-rename crash window before requesting more bytes.
            val current=store.record()?.takeIf { it.job.id==id }?:throw CancellationException()
            if(current.job.packageSha256.isNotBlank()&&!store.partial(id).exists()){
                val recovered=hashExisting(store.destination,current.job.expectedBytes,::active)
                if(recovered.sha256==current.job.packageSha256){
                    store.completedAfterRecovery(id,recovered);secrets.remove(id);return@withContext Result.success()
                }
                throw TransferFailure(TransferError.SOURCE_CHANGED)
            }
            store.update(id){m->if(m.job.status !in setOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING,DownloadStatus.COMMITTING))throw CancellationException() else
                m.copy(job=m.job.copy(status=DownloadStatus.RUNNING,errorCode=TransferError.NONE,updatedAtMillis=System.currentTimeMillis()))}
            try { setForeground(getForegroundInfo()) }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){throw TransferFailure(TransferError.FOREGROUND_UNAVAILABLE)}
            val url=secrets.read(id)
            var lastProgress=0L
            val manifest=store.record()?.takeIf { it.job.id==id }?:throw CancellationException()
            ResumableTransfer.copy(url,store.partial(id),store.destination,manifest.checkpoint,transport,
                expectedBytes=manifest.job.expectedBytes.takeIf { it>0 },expectedSha256=manifest.expectedSha256,
                save={checkpoint->active();store.checkpoint(id,checkpoint)},
                availableBytes={store.directory.usableSpace},checkCancelled=::active,
                progress={progress->
                    active()
                    val now=System.currentTimeMillis()
                    if(now-lastProgress>=1_000||progress.downloadedBytes==progress.totalBytes){
                        lastProgress=now
                        store.progress(id,progress){job->DownloadNotifications.update(applicationContext,job)}
                        setProgressAsync(workDataOf(KEY_DOWNLOADED to progress.downloadedBytes,KEY_TOTAL to progress.totalBytes))
                    }
                },commit={partial,_,completed->active();store.commit(id,partial,completed)})
            secrets.remove(id)
            Result.success()
        }catch(cancelled:CancellationException){
            // User actions already stored PAUSED/CANCELLED. A scheduler stop
            // remains queued for WorkManager to resume when constraints allow.
            runCatching{store.failed(id,TransferError.NONE,queued=true)}
            throw cancelled
        }catch(failure:TransferFailure){
            val retry=failure.retryable&&runAttemptCount<3
            runCatching{store.failed(id,failure.code,queued=retry)}
            if(retry)Result.retry()else Result.failure(workDataOf(KEY_ERROR to failure.code.name))
        }catch(_:IOException){
            runCatching{store.failed(id,TransferError.IO)}
            Result.failure(workDataOf(KEY_ERROR to TransferError.IO.name))
        }catch(_:Exception){
            runCatching{store.failed(id,TransferError.STATE_CORRUPT)}
            Result.failure(workDataOf(KEY_ERROR to TransferError.STATE_CORRUPT.name))
        }finally{
            runCatching{lock.release()};runCatching{lease.close()}
            val current=runCatching{store.record()}.getOrNull()
            if(current==null||current.job.id!=id||current.job.status==DownloadStatus.CANCELLED){store.partial(id).delete();runCatching{secrets.remove(id)}}
        }
    }

    private fun hashExisting(file:File,bytes:Long,check:()->Unit):CompletedTransfer {
        if(!file.isFile||bytes !in 1..FirmwareValidator.MAX_PACKAGE_BYTES||file.length()!=bytes)throw TransferFailure(TransferError.SOURCE_CHANGED)
        val digest=MessageDigest.getInstance("SHA-256");var count=0L
        file.inputStream().use { input->val buffer=ByteArray(1024*1024)
            while(true){check();val n=input.read(buffer);if(n<0)break;count+=n;if(count>bytes)throw TransferFailure(TransferError.SOURCE_CHANGED);digest.update(buffer,0,n)} }
        if(count!=bytes)throw TransferFailure(TransferError.SOURCE_CHANGED)
        return CompletedTransfer(count,digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)})
    }
    companion object {
        const val KEY_JOB_ID="downloadJobId"
        const val KEY_DOWNLOADED="downloadedBytes"
        const val KEY_TOTAL="totalBytes"
        const val KEY_ERROR="downloadError"
    }
}
