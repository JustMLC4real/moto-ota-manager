package nl.moto.ota.platform.download

import android.content.Context
import android.util.AtomicFile
import nl.moto.ota.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID

internal class DownloadStore(context: Context, storageRoot: File = context.filesDir) {
    val directory=File(storageRoot,"ota-downloads").apply { check(isDirectory || mkdirs()) }
    val destination=File(storageRoot,"selected-ota.zip")
    private val manifest=AtomicFile(File(directory,"current.manifest"))
    private val lock=LOCK

    fun record():DownloadManifest?=synchronized(lock){ readLocked() }
    private fun readLocked():DownloadManifest? {
        if(!manifest.baseFile.exists()&&!File(manifest.baseFile.path+".bak").exists())return null
        return try { manifest.openRead().use { input ->
            val bytes=input.readNBytes(65_537);require(bytes.size<=65_536)
            DownloadManifestCodec.decode(bytes.toString(Charsets.UTF_8))
        } } catch(_:Exception){ throw TransferFailure(TransferError.STATE_CORRUPT) }
    }
    fun begin(value:DownloadManifest)=synchronized(lock){ writeLocked(value.copy(destinationBaseline=destinationIdentity())) }
    fun update(id:String,change:(DownloadManifest)->DownloadManifest):DownloadManifest?=synchronized(lock){
        val current=readLocked()?.takeIf { it.job.id==id }?:return@synchronized null
        change(current).also { writeLocked(it) }
    }
    fun checkpoint(id:String,value:TransferCheckpoint)=update(id){ current ->
        if(current.job.status !in setOf(DownloadStatus.RUNNING,DownloadStatus.COMMITTING))throw CancellationException()
        current.copy(checkpoint=value,job=current.job.copy(downloadedBytes=value.durableBytes,expectedBytes=value.totalBytes,updatedAtMillis=System.currentTimeMillis()))
    }
    fun progress(id:String,value:TransferProgress,publish:(DownloadJob)->Unit={})=synchronized(lock){
        update(id){ current ->
            if(current.job.status!=DownloadStatus.RUNNING)throw CancellationException()
            current.copy(job=current.job.copy(downloadedBytes=value.downloadedBytes,expectedBytes=value.totalBytes,updatedAtMillis=System.currentTimeMillis()))
        }?.also { publish(it.job) }
    }
    fun canRun(id:String):Boolean=synchronized(lock){ readLocked()?.let { it.job.id==id&&it.job.status in setOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING,DownloadStatus.COMMITTING) }==true }
    fun commit(id:String,partial:File,result:CompletedTransfer)=synchronized(lock){
        val current=readLocked()?.takeIf { it.job.id==id&&it.job.status in setOf(DownloadStatus.RUNNING,DownloadStatus.COMMITTING) }?:throw CancellationException()
        if(destinationIdentity()!=current.destinationBaseline)throw TransferFailure(TransferError.SOURCE_CHANGED)
        val committing=current.copy(job=current.job.copy(status=DownloadStatus.COMMITTING,downloadedBytes=result.bytes,expectedBytes=result.bytes,
            packageSha256=result.sha256,errorCode=TransferError.NONE,updatedAtMillis=System.currentTimeMillis()))
        writeLocked(committing)
        Files.move(partial.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        writeLocked(committing.copy(job=committing.job.copy(status=DownloadStatus.COMPLETED,updatedAtMillis=System.currentTimeMillis())))
    }
    fun completedAfterRecovery(id:String,result:CompletedTransfer)=update(id){ current ->
        if(current.job.status !in setOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING,DownloadStatus.COMMITTING))throw CancellationException()
        current.copy(job=current.job.copy(status=DownloadStatus.COMPLETED,downloadedBytes=result.bytes,expectedBytes=result.bytes,
            packageSha256=result.sha256,errorCode=TransferError.NONE,updatedAtMillis=System.currentTimeMillis()))
    }
    fun failed(id:String,code:TransferError,queued:Boolean=false)=update(id){ current ->
        if(current.job.status in setOf(DownloadStatus.CANCELLED,DownloadStatus.COMPLETED,DownloadStatus.FAILED))current else
        if(current.job.status==DownloadStatus.PAUSED)current.copy(job=current.job.copy(downloadedBytes=current.checkpoint?.durableBytes?:0)) else
            current.copy(job=current.job.copy(status=if(queued)DownloadStatus.QUEUED else DownloadStatus.FAILED,errorCode=code,
                downloadedBytes=current.checkpoint?.durableBytes?:0,updatedAtMillis=System.currentTimeMillis()))
    }
    fun removeCurrentIfFinished()=synchronized(lock){ val value=readLocked();if(value?.job?.finished==true)manifest.delete() }
    /** Only after WorkManager cancellation, during an explicit controller action.
     * Keep unreadable evidence; never guess an ID, delete package bytes or replay work.
     */
    fun quarantineIfCorrupt():Boolean=synchronized(lock){
        try { readLocked();return@synchronized false }
        catch(failure:TransferFailure){if(failure.code!=TransferError.STATE_CORRUPT)throw failure}
        val quarantine=File(directory,"quarantine").apply { check(isDirectory||mkdir()) }
        if(quarantine.canonicalFile.parentFile!=directory.canonicalFile||!Files.isDirectory(quarantine.toPath(),NOFOLLOW_LINKS))
            throw TransferFailure(TransferError.IO)
        val identity=UUID.randomUUID().toString()
        for((suffix,label)in listOf("" to "base",".bak" to "backup",".new" to "interrupted")){
            val source=File(manifest.baseFile.path+suffix)
            if(Files.exists(source.toPath(),NOFOLLOW_LINKS)){
                // Fixed app-private names; moving a symlink moves its entry, never its target.
                Files.move(source.toPath(),File(quarantine,"$identity.$label.manifest").toPath(),StandardCopyOption.ATOMIC_MOVE)
            }
        }
        true
    }
    fun partial(id:String):File { validId(id);return File(directory,"$id.part") }
    fun lease(id:String):File { validId(id);return File(directory,"$id.lock") }
    fun secret(id:String):File { validId(id);return File(directory,"$id.url.enc") }
    fun destinationIdentity():String {
        if(!destination.exists())return "absent"
        val attr=Files.readAttributes(destination.toPath(),BasicFileAttributes::class.java)
        val raw="${attr.size()}|${attr.lastModifiedTime()}|${attr.creationTime()}|${attr.fileKey()}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    private fun writeLocked(value:DownloadManifest) {
        // Round-trip validation prevents this store from publishing an invalid journal.
        val text=DownloadManifestCodec.encode(value);DownloadManifestCodec.decode(text)
        val output=manifest.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8));manifest.finishWrite(output) }
        catch(error:Exception){manifest.failWrite(output);throw error}
    }
    companion object {
        private val LOCK=Any()
        fun validId(id:String){ require(id.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) }
    }
}
