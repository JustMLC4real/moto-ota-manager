package nl.moto.ota.core.update

import nl.moto.ota.core.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

class SimulatedProcessDeath : Error("SIMULATED_PROCESS_DEATH")

/**
 * Executes the same consent/backup/write/readback contract against regular files only.
 * Paths are derived internally, never accepted as partition arguments. A symlink,
 * device node or directory outside this dedicated sandbox is rejected before I/O.
 */
class SandboxStockRestoreBackend(
    val directory: File,
    private val device: () -> DeviceState,
    private val stockReference: StockReference,
    private val automaticUpdatesDisabled: () -> Boolean? = { true },
    private val fault: (RestoreCheckpoint) -> Unit = {},
    private val failAfterWrittenBytes: Long? = null
) : StockRestoreBackend {
    override val mode = ExecutionMode.SANDBOX
    private val root = directory.canonicalFile
    private val secret: ByteArray

    init {
        require(root.name.startsWith("moto-ota-sandbox-")) { "Dedicated sandbox directory required" }
        require(root.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val keyFile = File(root,"consent.key")
        secret = if (keyFile.exists()) checkedFile(keyFile).readBytes().also { require(it.size==32) }
        else ByteArray(32).also { SecureRandom().nextBytes(it); atomicWrite(keyFile,it) }
    }

    fun observe(): RestoreEnvironment {
        val d=device()
        require(d.simulated) { "A sandbox may not consume real-device observations" }
        val image=partition(d.slot)
        val stock=checkedFile(File(root,"stock.img"))
        val validation=stock.inputStream().use { BackupValidator.inspect(it,false,image.length(),d,stockReference) }
        return RestoreEnvironment(d,image.length(),digest(image),automaticUpdatesDisabled(),validation,mode)
    }

    fun prepare(runId:String,nowMillis:Long?=null):StockRestorePlan {
        val environment=observe()
        return StockRestorePolicy.plan(environment,runId,nowMillis?:System.currentTimeMillis())
    }
    fun confirm(plan:StockRestorePlan,explicitConfirmation:Boolean,noRebootAcknowledged:Boolean,nowMillis:Long=System.currentTimeMillis()):RestoreConsent =
        StockRestorePolicy.confirm(plan,secret,explicitConfirmation,noRebootAcknowledged,nowMillis)

    override fun execute(plan:StockRestorePlan,consent:RestoreConsent):StockRestoreResult {
        require(plan.mode==ExecutionMode.SANDBOX) { "A live plan may never reach the sandbox" }
        val tx=transaction(plan)
        val checkpoint=File(tx,"checkpoint.properties")
        var backupDigest=""
        try {
            RandomAccessFile(File(root,"restore.lock"),"rw").channel.use { channel ->
                val lock=channel.tryLock() ?: throw UpdateSafetyException("RESTORE_ALREADY_RUNNING")
                lock.use {
                    StockRestorePolicy.verify(plan,consent,secret,observe())
                    val source=checkedFile(File(root,"stock.img"))
                    if(source.length()!=plan.imageBytes || digest(source)!=plan.stockSha256)throw UpdateSafetyException("STOCK_CHANGED")
                    persist(checkpoint,RestoreCheckpoint.PREPARED,plan,"");fault(RestoreCheckpoint.PREPARED)
                    val backup=File(tx,"patched-backup.img")
                    if(!backup.exists())copyExact(partition(plan.slot),backup,plan.imageBytes)
                    backupDigest=digest(checkedFile(backup))
                    if(backupDigest!=plan.previousImageSha256)throw UpdateSafetyException("BACKUP_MISMATCH")
                    persist(checkpoint,RestoreCheckpoint.BACKUP_VERIFIED,plan,backupDigest);fault(RestoreCheckpoint.BACKUP_VERIFIED)
                    val consumed=File(tx,"consent.consumed")
                    // CREATE_NEW is the durable one-use boundary. An empty marker after
                    // power loss still means consumed, and is never deleted automatically.
                    try { Files.createFile(consumed.toPath()) } catch (_: java.nio.file.FileAlreadyExistsException) { throw UpdateSafetyException("CONSENT_ALREADY_CONSUMED") }
                    FileOutputStream(consumed).use { it.write(consent.macSha256.toByteArray(Charsets.US_ASCII));it.fd.sync() }
                    persist(checkpoint,RestoreCheckpoint.TOKEN_CONSUMED,plan,backupDigest);fault(RestoreCheckpoint.TOKEN_CONSUMED)
                    // Recheck observations after backup, immediately before the write.
                    StockRestorePolicy.verify(plan,consent,secret,observe())
                    persist(checkpoint,RestoreCheckpoint.WRITE_STARTED,plan,backupDigest);fault(RestoreCheckpoint.WRITE_STARTED)
                    var written=0L
                    source.inputStream().use { input -> RandomAccessFile(partition(plan.slot),"rw").use { output ->
                        output.seek(0)
                        val buffer=ByteArray(1024)
                        while(true){
                            val n=input.read(buffer);if(n<0)break
                            if(written+n>plan.imageBytes)throw UpdateSafetyException("STOCK_SIZE_CHANGED")
                            output.write(buffer,0,n);written+=n
                            if(failAfterWrittenBytes!=null && written>=failAfterWrittenBytes)throw java.io.IOException("SIMULATED_WRITE_INTERRUPTION")
                        }
                        if(written!=plan.imageBytes)throw UpdateSafetyException("SHORT_WRITE")
                        output.fd.sync()
                    } }
                    val observed=digest(partition(plan.slot))
                    if(observed!=plan.stockSha256)throw UpdateSafetyException("READBACK_MISMATCH")
                    persist(checkpoint,RestoreCheckpoint.STOCK_VERIFIED,plan,backupDigest);fault(RestoreCheckpoint.STOCK_VERIFIED)
                    return StockRestoreResult(plan.transactionId,RestoreCheckpoint.STOCK_VERIFIED,observed,backupDigest,true)
                }
            }
        } catch(error:Exception){
            val code=if(error is UpdateSafetyException)error.code else "RESTORE_IO_ERROR"
            persist(checkpoint,RestoreCheckpoint.RECOVERY_REQUIRED,plan,backupDigest)
            return StockRestoreResult(plan.transactionId,RestoreCheckpoint.RECOVERY_REQUIRED,runCatching { digest(partition(plan.slot)) }.getOrDefault(""),backupDigest,false,code)
        }
    }

    override fun recover(plan:StockRestorePlan):StockRestoreResult {
        require(plan.mode==ExecutionMode.SANDBOX)
        RandomAccessFile(File(root,"restore.lock"),"rw").channel.use { channel ->
            val lock=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
                ?: throw UpdateSafetyException("RESTORE_ALREADY_RUNNING")
            lock.use { return recoverLocked(plan) }
        }
    }
    private fun recoverLocked(plan:StockRestorePlan):StockRestoreResult {
        val tx=transaction(plan)
        val backup=File(tx,"patched-backup.img")
        val backupHash=if(backup.exists())runCatching { digest(checkedFile(backup)) }.getOrDefault("")else ""
        val observed=runCatching { digest(partition(plan.slot)) }.getOrDefault("")
        val d=device();val sameSource=d.simulated && d.bootId==plan.bootId && d.slot==plan.slot && d.build==plan.build && d.fingerprint==plan.fingerprint
        val verified=sameSource && observed==plan.stockSha256 && backupHash==plan.previousImageSha256
        if(!verified && sameSource && observed==plan.previousImageSha256 && backupHash==plan.previousImageSha256)
            return StockRestoreResult(plan.transactionId,RestoreCheckpoint.SOURCE_UNCHANGED_VERIFIED,observed,backupHash,false,"PREVIOUS_PATCHED_IMAGE_UNCHANGED")
        return StockRestoreResult(plan.transactionId,if(verified)RestoreCheckpoint.STOCK_VERIFIED else RestoreCheckpoint.RECOVERY_REQUIRED,
            observed,backupHash,verified,if(verified)null else "OBSERVATION_ONLY_NO_AUTOMATIC_REPLAY")
    }
    fun verifyUnchangedAndInvalidate(plan:StockRestorePlan):Pair<StockRestoreResult,RestoreEnvironment> {
        require(plan.mode==ExecutionMode.SANDBOX)
        RandomAccessFile(File(root,"restore.lock"),"rw").channel.use { channel ->
            val lock=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
                ?: throw UpdateSafetyException("RESTORE_ALREADY_RUNNING")
            lock.use {
                val env=observe();val d=env.device
                if(StockRestorePolicy.blockers(env).isNotEmpty() || d.bootId!=plan.bootId || d.slot!=plan.slot || d.build!=plan.build || d.fingerprint!=plan.fingerprint ||
                    env.activeImageSha256!=plan.previousImageSha256)throw UpdateSafetyException("SOURCE_NOT_UNCHANGED")
                val tx=transaction(plan);val backup=File(tx,"patched-backup.img")
                if(!backup.exists())copyExact(partition(plan.slot),backup,plan.imageBytes)
                val result=recoverLocked(plan)
                if(result.checkpoint!=RestoreCheckpoint.SOURCE_UNCHANGED_VERIFIED)throw UpdateSafetyException("SOURCE_NOT_UNCHANGED")
                val marker=File(tx,"consent.consumed")
                if(!marker.exists()){
                    Files.createFile(marker.toPath())
                    FileOutputStream(marker).use { it.write("INVALIDATED_FOR_NEW_PREPARATION".toByteArray());it.fd.sync() }
                }
                val ready=result.copy(checkpoint=RestoreCheckpoint.REPREPARATION_READY,errorCode=null)
                persist(File(tx,"checkpoint.properties"),RestoreCheckpoint.REPREPARATION_READY,plan,result.backupSha256)
                return ready to env
            }
        }
    }

    fun partitionFile(slot:String):File=partition(slot)
    fun backupFile(plan:StockRestorePlan):File=checkedFile(File(transaction(plan),"patched-backup.img"))

    private fun partition(slot:String):File {
        require(slot in setOf("_a","_b"))
        return checkedFile(File(root,"partitions/init_boot$slot"))
    }
    private fun checkedFile(file:File):File {
        require(!Files.isSymbolicLink(file.toPath())) { "Symlink forbidden" }
        val canonical=file.canonicalFile
        require(canonical.toPath().startsWith(root.toPath()) && canonical.isFile && Files.isRegularFile(canonical.toPath())) { "Regular sandbox file required" }
        return canonical
    }
    private fun transaction(plan:StockRestorePlan):File {
        require(plan.transactionId.matches(Regex("[a-f0-9-]{36}")))
        val tx=File(root,"transactions/${plan.transactionId}")
        require(tx.canonicalFile.toPath().startsWith(root.toPath()))
        require(tx.mkdirs() || tx.isDirectory)
        require(!Files.isSymbolicLink(tx.toPath()))
        return tx
    }
    private fun copyExact(source:File,destination:File,expected:Long){
        val bytes=checkedFile(source).readBytes()
        if(bytes.size.toLong()!=expected)throw UpdateSafetyException("BACKUP_SIZE_MISMATCH")
        atomicWrite(destination,bytes)
    }
    private fun persist(file:File,stage:RestoreCheckpoint,plan:StockRestorePlan,backupHash:String){
        val p=Properties().apply { setProperty("schema","1");setProperty("stage",stage.name);setProperty("transaction",plan.transactionId);setProperty("stockSha256",plan.stockSha256);setProperty("backupSha256",backupHash) }
        val bytes=java.io.ByteArrayOutputStream().also { p.store(it,"Sandbox transaction; recovery never replays writes") }.toByteArray()
        atomicWrite(file,bytes)
    }
    private fun atomicWrite(file:File,bytes:ByteArray){
        require(file.canonicalFile.toPath().startsWith(root.toPath()))
        require(!file.exists() || !Files.isSymbolicLink(file.toPath()))
        val temp=Files.createTempFile(file.parentFile.toPath(),".stock-restore-",".tmp").toFile()
        try{ FileOutputStream(temp).use { it.write(bytes);it.fd.sync() };Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp.toPath()) }
    }
    private fun digest(file:File):String=MessageDigest.getInstance("SHA-256").run { file.inputStream().use { input ->
        val bytes=ByteArray(64*1024);while(true){val n=input.read(bytes);if(n<0)break;update(bytes,0,n)} };digest().joinToString(""){"%02x".format(it.toInt() and 255)} }
}
