package nl.moto.ota.platform.update

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.*
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Authenticated streaming stock restore. Never applies OTA, switches slots or reboots. */
class StockRestoreController(private val context:Context) {
    private val directory=File(context.filesDir,"update").apply { mkdirs() }.canonicalFile
    private val transport=RestoreRootTransport(context.applicationInfo.sourceDir)
    private val updates=UpdateController(context)

    suspend fun prepare(run:UpdateRun,inspection:Inspection,importedStock:File?=null):StockRestorePlan=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        requireSafety(!run.simulated && run.id.isNotBlank() && run.stage in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.STOCK_CONFIRMATION),"RESTORE_RUN_INVALID")
        val device=inspection.toDeviceState()
        requireSafety(device.bootId==run.sourceBootId && device.slot==run.sourceSlot && device.build==run.sourceBuild && device.fingerprint==run.sourceFingerprint &&
            (run.sourceModel.isBlank() || run.sourceModel==device.model) && (run.sourceDevice.isBlank() || run.sourceDevice==device.device) &&
            (run.sourceChannel.isBlank() || run.sourceChannel==device.channel),"SOURCE_CHANGED")
        val type=run.stockPartitionType
        requireSafety(type in setOf("boot","init_boot"),"RESTORE_PARTITION_UNKNOWN")
        val bytes=partitionBytes(inspection,type+device.slot)
        requireSafety(bytes in 4096..BootImageStreams.MAX_IMAGE_BYTES,"PARTITION_SIZE_UNKNOWN")
        val anchors=DeviceTrustAnchors.spkiSha256()
        val reference=(FirmwareVault(context).referencesFor(device,anchors)+listOfNotNull(VerifiedStockCatalog.referenceFor(device,anchors))).firstOrNull {
            it.authenticityVerified && it.partition==type && it.bytes==bytes && it.sha256==run.stockSha256
        } ?: throw UpdateSafetyException("CURRENT_STOCK_REFERENCE_UNAVAILABLE")
        val txId=UUID.randomUUID().toString()
        val tx=createTransaction(txId)
        val stock=File(tx,"stock.img")
        val measured=writeStock(stock,bytes) { output ->
            if(importedStock!=null) withImportedImage(importedStock) { source ->
                val input=PushbackInputStream(source,2)
                val magic=ByteArray(2);val n=input.read(magic)
                requireSafety(n==2,"IMAGE_CORRUPT");input.unread(magic)
                BootImageStreams.measure(input,magic[0]==0x1f.toByte() && magic[1]==0x8b.toByte(),bytes,output)
            } else {
                val candidate=inspection.backupInventory.firstOrNull {
                    it.validation?.usableFor(device)==true && it.validation.partitionType==type && it.sha256==run.stockSha256 &&
                        it.uncompressedBytes==bytes && it.provenanceVerified && it.gzipBytes!=null && it.gzipBytes in 1..BootImageStreams.MAX_PACKED_BYTES
                } ?: throw UpdateSafetyException("AUTHENTICATED_BACKUP_UNAVAILABLE")
                ReadOnlyShell().withBackup(candidate.directoryId,requireNotNull(candidate.gzipBytes)) {
                    BootImageStreams.measure(it,true,bytes,output)
                }.also { requireSafety(it.sha1.equals(candidate.directoryId.removePrefix("magisk_backup_"),true),"BACKUP_SHA1_MISMATCH") }
            }
        }
        requireSafety(measured.bytes==bytes && measured.partitionType==type && measured.sha256==run.stockSha256,"STOCK_ENVELOPE_NOT_AUTHENTICATED")
        val validation=withPrivateFile(stock,bytes) { BackupValidator.inspect(it,false,bytes,device,reference,type) }
        requireSafety(validation.usableFor(device) && validation.sha256==run.stockSha256 && validation.sha1==measured.sha1,"STOCK_BYTES_NOT_AUTHENTICATED")
        val runtimeSha1=inspection.probes["MAGISK_RUNTIME"]?.takeIf {it.successful}?.let {
            runCatching {org.json.JSONObject(it.stdout).takeIf {j->j.getInt("schema")==1}?.getString("stockSha1")}.getOrNull()
        }
        requireSafety(MagiskRuntimeConfig.matchesStock(runtimeSha1,validation),"MAGISK_BACKUP_ASSOCIATION_MISMATCH")
        secret()
        val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        val draft=StockRestorePlan(txId,run.id,ExecutionMode.LIVE,device.model,device.device,device.channel,device.build,device.fingerprint,device.bootId,device.slot,
            bytes,validation.sha256,"0".repeat(64),now,now+60_000,elapsed,elapsed+60_000,type)
        writePlan(tx,draft)
        // Root independently remeasures block sizes, identity, bytes, runtime SHA1 and safety gates.
        val environment=RestoreRootProtocol.environment(transport.invoke("observe",txId),validation)
        val plan=StockRestorePolicy.plan(environment,run.id).copy(transactionId=txId)
        requireSafety(plan.bootId==run.sourceBootId && plan.slot==run.sourceSlot && plan.stockSha256==run.stockSha256 &&
            plan.partitionType==type && plan.imageBytes==bytes,"SOURCE_CHANGED")
        writePlan(tx,plan)
        val prepared=updates.prepareRestore(plan)
        requireSafety(prepared.stage==UpdateStage.STOCK_CONFIRMATION && prepared.restoreTransactionId==plan.transactionId,"RESTORE_PREPARE_RACE")
        plan
    } }

    /** A full-run start supplies this short action capability; no exported component invokes this. */
    suspend fun confirmAndExecute(plan:StockRestorePlan,explicitConfirmation:Boolean,noRebootAcknowledged:Boolean,
                                  operation:AuthorizedRunOperation?=null):StockRestoreResult=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) { withWriteWakeLock {
        requireSafety(plan.mode==ExecutionMode.LIVE,"RESTORE_RUN_INVALID")
        val permit=operation ?: throw UpdateSafetyException("RUN_ACTION_AUTHORIZATION_REQUIRED")
        requireSafety(permit.runId==plan.runId && permit.action==RunAction.RESTORE_STOCK && permit.sourceBootId==plan.bootId,"RUN_ACTION_AUTHORIZATION_MISMATCH")
        val tx=transaction(plan.transactionId)
        requireStoredPlan(tx,plan)
        val run=updates.load()
        requireSafety(run.stage==UpdateStage.STOCK_CONFIRMATION && run.id==plan.runId && run.restoreTransactionId==plan.transactionId,"RUN_NOT_CONFIRMED")
        requireSafety(run.packageVerified && run.packageSha256==permit.packageSha256,"RUN_PACKAGE_CHANGED")
        val consent=StockRestorePolicy.confirm(plan,secret(),explicitConfirmation,noRebootAcknowledged,elapsedRealtimeMillis=SystemClock.elapsedRealtime())
        // Root verifies the HMAC/clocks/source and consumes its own durable action marker.
        RestoreRootProtocol.writePrivate(File(tx,"operation.properties"),RunAuthorizationCodec.encodeOperation(permit).toByteArray(Charsets.UTF_8))
        writePlan(tx,plan,consent)
        updates.restoreDispatching(plan.runId,plan.transactionId)
        val result=try {RestoreRootProtocol.result(transport.invoke("execute",plan.transactionId))}
        catch(error:Exception){StockRestoreResult(plan.transactionId,RestoreCheckpoint.RECOVERY_REQUIRED,"","",false,
            (error as? UpdateSafetyException)?.code ?: "ROOT_HELPER_INTERRUPTED")}
        updates.recordRestoreResult(result)
        result
    } } }

    suspend fun recover(plan:StockRestorePlan):StockRestoreResult=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        requireSafety(plan.mode==ExecutionMode.LIVE,"RESTORE_RUN_INVALID")
        requireStoredPlan(transaction(plan.transactionId),plan)
        RestoreRootProtocol.result(transport.invoke("recover",plan.transactionId)).also {updates.recordRestoreResult(it)}
    } }

    /** Explicit recovery observes and invalidates old tokens; it never replays a write. */
    suspend fun releasePreparation(plan:StockRestorePlan):UpdateRun=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        val run=updates.load()
        requireSafety(run.stage==UpdateStage.RECOVERY_REQUIRED && run.id==plan.runId && run.restoreTransactionId==plan.transactionId,"RESTORE_RUN_INVALID")
        val tx=transaction(plan.transactionId);requireStoredPlan(tx,plan)
        val response=transport.invoke("verify-unchanged",plan.transactionId)
        val result=RestoreRootProtocol.result(response.getJSONObject("receipt"))
        val raw=RestoreRootProtocol.environment(response.getJSONObject("observation"),BackupValidation())
        val device=raw.device;val anchors=DeviceTrustAnchors.spkiSha256()
        val reference=(FirmwareVault(context).referencesFor(device,anchors)+listOfNotNull(VerifiedStockCatalog.referenceFor(device,anchors))).firstOrNull {
            it.partition==plan.partitionType && it.sha256==plan.stockSha256 && it.bytes==plan.imageBytes
        }
        val validation=withPrivateFile(File(tx,"stock.img"),plan.imageBytes) {BackupValidator.inspect(it,false,plan.imageBytes,device,reference,plan.partitionType)}
        val released=updates.releasePreparation(plan,result,raw.copy(stockValidation=validation))
        requireSafety(released.stage==UpdateStage.STOCK_PREPARATION && released.restoreTransactionId.isBlank(),"REPREPARATION_NOT_PROVEN")
        released
    } }

    /** Raw recovery image. A complete preflight digest prevents publishing known corrupt bytes. */
    suspend fun exportPatchedBackup(plan:StockRestorePlan,output:OutputStream):Long=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        val tx=transaction(plan.transactionId);requireStoredPlan(tx,plan)
        requireSafety(plan.imageBytes in 4096..BootImageStreams.MAX_IMAGE_BYTES && plan.previousImageSha256.matches(Regex("[a-f0-9]{64}")),"BACKUP_PLAN_INVALID")
        val file=File(tx,"patched-backup.img")
        requireSafety(withPrivateFile(file,plan.imageBytes) {digestCopy(it,plan.imageBytes)}==plan.previousImageSha256,"BACKUP_MISMATCH")
        requireSafety(withPrivateFile(file,plan.imageBytes) {digestCopy(it,plan.imageBytes,output)}==plan.previousImageSha256,"BACKUP_CHANGED")
        output.flush();plan.imageBytes
    } }

    fun pendingPlan(run:UpdateRun):StockRestorePlan?=runCatching {
        if(run.restoreTransactionId.isBlank())return@runCatching null
        StockRestorePlanCodec.decode(readPrivate(File(transaction(run.restoreTransactionId),"request.properties"),16_384).toString(Charsets.UTF_8)).first.takeIf {it.runId==run.id}
    }.getOrNull()

    private fun partitionBytes(inspection:Inspection,name:String):Long {
        val probe=inspection.probes[ReadOnlyProbe.PARTITIONS.name]?.takeIf {it.successful} ?: throw UpdateSafetyException("PARTITION_SIZE_UNKNOWN")
        val values=probe.stdout.lineSequence().map {it.trim().split('=',limit=2)}.filter {it.size==2 && it[0]==name}.map {it[1].toLongOrNull()}.toList()
        requireSafety(values.size==1 && values.single()!=null,"PARTITION_SIZE_UNKNOWN")
        return requireNotNull(values.single())
    }
    private fun createTransaction(id:String):File {
        requireDirectory(directory)
        val parent=File(directory,"transactions")
        if(!parent.exists())Os.mkdir(parent.path,0x1c0)
        requireDirectory(parent)
        return File(parent,id).also {Os.mkdir(it.path,0x1c0);requireDirectory(it)}
    }
    private fun transaction(id:String):File {
        requireSafety(id.matches(Regex("[a-f0-9-]{36}")),"RESTORE_TRANSACTION_INVALID")
        requireDirectory(directory)
        val parent=File(directory,"transactions").also(::requireDirectory)
        return File(parent,id).also(::requireDirectory)
    }
    private fun requireDirectory(file:File) {
        val stat=Os.lstat(file.path)
        requireSafety(file.canonicalPath==file.path && file.canonicalFile.toPath().startsWith(directory.toPath()) &&
            OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid==android.os.Process.myUid() && stat.st_mode and 0x012==0,"RESTORE_PRIVATE_DIRECTORY_INVALID")
    }
    private fun requireStoredPlan(tx:File,plan:StockRestorePlan) {
        val stored=StockRestorePlanCodec.decode(readPrivate(File(tx,"request.properties"),16_384).toString(Charsets.UTF_8)).first
        requireSafety(stored.canonical()==plan.canonical(),"PLAN_CHANGED")
    }
    private fun writePlan(tx:File,p:StockRestorePlan,c:RestoreConsent?=null)=RestoreRootProtocol.writePrivate(File(tx,"request.properties"),StockRestorePlanCodec.encode(p,c).toByteArray(Charsets.UTF_8))
    private fun secret():ByteArray=synchronized(KEY_LOCK){
        val file=File(directory,"consent.key")
        if(!file.exists() && !File(file.path+".bak").exists() && !File(file.path+".new").exists())RestoreRootProtocol.writePrivate(file,ByteArray(32).also {SecureRandom().nextBytes(it)})
        readPrivate(file,32).also {requireSafety(it.size==32,"RESTORE_KEY_INVALID")}
    }
    private fun readPrivate(file:File,limit:Int):ByteArray {
        requireSafety(file.canonicalFile.toPath().startsWith(directory.toPath()),"RESTORE_PRIVATE_FILE_INVALID")
        listOf(file,File(file.path+".bak"),File(file.path+".new")).filter {it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach {
            val stat=Os.lstat(it.path)
            requireSafety(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid==android.os.Process.myUid(),"RESTORE_PRIVATE_FILE_INVALID")
        }
        return android.util.AtomicFile(file).openRead().use {it.readNBytes(limit+1).also {bytes->requireSafety(bytes.size<=limit,"RESTORE_PRIVATE_FILE_OVERSIZED")}}
    }
    /** Invalid or partial streams have no executable request or authorization. */
    private fun writeStock(file:File,expected:Long,consume:(OutputStream)->BootImageMeasurement):BootImageMeasurement {
        val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
        return FileOutputStream(fd).use {output->
            consume(output).also {requireSafety(it.bytes==expected && Os.fstat(fd).st_size==expected,"IMAGE_SIZE");output.fd.sync()}
        }.also {Os.chmod(file.path,0x100)}
    }
    private fun<T> withImportedImage(file:File,consume:(InputStream)->T):T {
        requireSafety(file.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath()),"STOCK_SOURCE_NOT_PRIVATE")
        return withPrivateFile(file,null,BootImageStreams.MAX_PACKED_BYTES,consume)
    }
    private fun<T> withPrivateFile(file:File,expected:Long?,maximum:Long=expected?:BootImageStreams.MAX_IMAGE_BYTES,consume:(InputStream)->T):T {
        requireSafety(file.canonicalPath==file.path,"RESTORE_PRIVATE_FILE_INVALID")
        val before=Os.lstat(file.path)
        requireSafety(OsConstants.S_ISREG(before.st_mode) && before.st_uid==android.os.Process.myUid() && before.st_mode and 0x012==0 &&
            before.st_size in 1..maximum && (expected==null || before.st_size==expected),"RESTORE_PRIVATE_FILE_INVALID")
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        return FileInputStream(fd).use {input->
            fun same(s:android.system.StructStat)=s.st_dev==before.st_dev && s.st_ino==before.st_ino && s.st_size==before.st_size && s.st_mtime==before.st_mtime && s.st_ctime==before.st_ctime
            requireSafety(same(Os.fstat(fd)),"RESTORE_PRIVATE_FILE_CHANGED")
            consume(input).also {requireSafety(same(Os.fstat(fd)) && same(Os.lstat(file.path)),"RESTORE_PRIVATE_FILE_CHANGED")}
        }
    }
    private fun digestCopy(input:InputStream,expected:Long,output:OutputStream?=null):String {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65_536);var bytes=0L
        while(true){val n=input.read(buffer);if(n<0)break;requireSafety(n>0 && bytes<=expected-n,"BACKUP_SIZE_MISMATCH");bytes+=n;hash.update(buffer,0,n);output?.write(buffer,0,n)}
        requireSafety(bytes==expected,"BACKUP_SIZE_MISMATCH")
        return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun<T> withWriteWakeLock(block:()->T):T {
        val manager=context.getSystemService(PowerManager::class.java) ?: throw UpdateSafetyException("WAKE_LOCK_UNAVAILABLE")
        val lock=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"nl.moto.ota:stock-restore").apply {setReferenceCounted(false)}
        lock.acquire(10*60_000)
        try{return block()}finally{if(lock.isHeld)lock.release()}
    }
    private fun requireSafety(ok:Boolean,code:String){if(!ok)throw UpdateSafetyException(code)}
    companion object {private val KEY_LOCK=Any();private val OPERATION_LOCK=Any()}
}
