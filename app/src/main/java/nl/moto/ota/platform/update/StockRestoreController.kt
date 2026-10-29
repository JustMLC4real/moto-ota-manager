package nl.moto.ota.platform.update

import android.content.Context
import android.os.SystemClock
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.*
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Live stock restore only. No OTA, slot switch, Magisk installer or reboot entry point exists. */
class StockRestoreController(private val context:Context) {
    private val directory=File(context.filesDir,"update").apply { mkdirs() }.canonicalFile
    private val transport=RestoreRootTransport(context.applicationInfo.sourceDir)
    private val updates=UpdateController(context)

    suspend fun prepare(run:UpdateRun,inspection:Inspection,importedStock:File?=null):StockRestorePlan=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        require(!run.simulated && run.id.isNotBlank() && run.stage in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.STOCK_CONFIRMATION)) { "RESTORE_RUN_INVALID" }
        val device=inspection.toDeviceState()
        require(device.bootId==run.sourceBootId && device.slot==run.sourceSlot && device.build==run.sourceBuild) { "SOURCE_CHANGED" }
        val anchors=DeviceTrustAnchors.spkiSha256()
        val reference=FirmwareVault(context).referenceFor(device,anchors) ?: VerifiedStockCatalog.referenceFor(device,anchors)
        require(reference?.authenticityVerified==true) { "CURRENT_STOCK_REFERENCE_UNAVAILABLE" }
        val supplied=if(importedStock!=null) {
            require(importedStock.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && importedStock.isFile && !java.nio.file.Files.isSymbolicLink(importedStock.toPath()))
            importedStock.inputStream().use { it.readNBytes(16_777_217) }.also { require(it.size<=16_777_216) }
        } else {
            val candidate=inspection.backupInventory.firstOrNull { it.validation?.usableAsStock==true && it.sha256==run.stockSha256 }
                ?: throw UpdateSafetyException("AUTHENTICATED_BACKUP_UNAVAILABLE")
            val binary=ReadOnlyShell().readBackup(candidate.directoryId)
            require(binary.result.successful && binary.bytes.size.toLong()==candidate.gzipBytes)
            binary.bytes
        }
        val compressed=supplied.size>=2 && supplied[0]==0x1f.toByte() && supplied[1]==0x8b.toByte()
        val envelope=BackupValidator.inspect(supplied.inputStream(),compressed,8_388_608,device,reference)
        require(envelope.usableAsStock && envelope.sha256==run.stockSha256) { "STOCK_ENVELOPE_NOT_AUTHENTICATED" }
        val bytes=if(compressed)GZIPInputStream(supplied.inputStream()).use { it.readNBytes(8_388_609) }else supplied
        val validation=BackupValidator.inspect(bytes.inputStream(),false,8_388_608,device,reference)
        require(validation.usableAsStock && validation.sha256==run.stockSha256) { "STOCK_BYTES_NOT_AUTHENTICATED" }
        val txId=UUID.randomUUID().toString()
        val tx=File(directory,"transactions/$txId").apply { check(mkdirs()) }
        RestoreRootProtocol.writePrivate(File(tx,"stock.img"),bytes)
        secret()
        val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        val draft=StockRestorePlan(txId,run.id,ExecutionMode.LIVE,device.model,device.device,device.channel,device.build,device.fingerprint,device.bootId,device.slot,
            bytes.size.toLong(),validation.sha256,"0".repeat(64),now,now+60_000,elapsed,elapsed+60_000)
        writePlan(tx,draft)
        val environment=RestoreRootProtocol.environment(transport.invoke("observe",txId),validation)
        val plan=StockRestorePolicy.plan(environment,run.id).copy(transactionId=txId)
        require(plan.bootId==run.sourceBootId && plan.slot==run.sourceSlot && plan.stockSha256==run.stockSha256)
        writePlan(tx,plan)
        val prepared=updates.prepareRestore(plan)
        require(prepared.stage==UpdateStage.STOCK_CONFIRMATION && prepared.restoreTransactionId==plan.transactionId) { "RESTORE_PREPARE_RACE" }
        plan
    } }

    /** Called only by the app's explicit review confirmation. No exported Android component invokes it. */
    suspend fun confirmAndExecute(plan:StockRestorePlan,explicitConfirmation:Boolean,noRebootAcknowledged:Boolean):StockRestoreResult=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) { withWriteWakeLock {
        require(plan.mode==ExecutionMode.LIVE)
        val tx=transaction(plan.transactionId)
        val stored=StockRestorePlanCodec.decode(readPrivate(File(tx,"request.properties"),16_384).toString(Charsets.UTF_8)).first
        require(stored.canonical()==plan.canonical()) { "PLAN_CHANGED" }
        val run=updates.load()
        require(run.stage==UpdateStage.STOCK_CONFIRMATION && run.id==plan.runId && run.restoreTransactionId==plan.transactionId) { "RUN_NOT_CONFIRMED" }
        val consent=StockRestorePolicy.confirm(plan,secret(),explicitConfirmation,noRebootAcknowledged,elapsedRealtimeMillis=SystemClock.elapsedRealtime())
        writePlan(tx,plan,consent)
        updates.restoreDispatching(plan.runId,plan.transactionId) // Atomic expected transaction claim before process dispatch.
        val result=try { RestoreRootProtocol.result(transport.invoke("execute",plan.transactionId)) }
        catch(e:Exception){StockRestoreResult(plan.transactionId,RestoreCheckpoint.RECOVERY_REQUIRED,"","",false,(e as? UpdateSafetyException)?.code?:"ROOT_HELPER_INTERRUPTED")}
        updates.recordRestoreResult(result)
        result
    } } }

    suspend fun recover(plan:StockRestorePlan):StockRestoreResult=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        require(plan.mode==ExecutionMode.LIVE)
        RestoreRootProtocol.result(transport.invoke("recover",plan.transactionId)).also { updates.recordRestoreResult(it) }
    } }
    /** Explicit recovery: device reads + private backup/journal/token invalidation only. */
    suspend fun releasePreparation(plan:StockRestorePlan):UpdateRun=withContext(Dispatchers.IO) { synchronized(OPERATION_LOCK) {
        val run=updates.load()
        require(run.stage==UpdateStage.RECOVERY_REQUIRED && run.id==plan.runId && run.restoreTransactionId==plan.transactionId)
        val tx=transaction(plan.transactionId)
        val stored=StockRestorePlanCodec.decode(readPrivate(File(tx,"request.properties"),16_384).toString(Charsets.UTF_8)).first
        require(stored.canonical()==plan.canonical())
        val response=transport.invoke("verify-unchanged",plan.transactionId)
        val result=RestoreRootProtocol.result(response.getJSONObject("receipt"))
        val rawEnvironment=RestoreRootProtocol.environment(response.getJSONObject("observation"),BackupValidation())
        val device=rawEnvironment.device;val anchors=DeviceTrustAnchors.spkiSha256()
        val reference=FirmwareVault(context).referenceFor(device,anchors) ?: VerifiedStockCatalog.referenceFor(device,anchors)
        val stock=readPrivate(File(tx,"stock.img"),8_388_608)
        val validation=BackupValidator.inspect(stock.inputStream(),false,8_388_608,device,reference)
        val released=updates.releasePreparation(plan,result,rawEnvironment.copy(stockValidation=validation))
        require(released.stage==UpdateStage.STOCK_PREPARATION && released.restoreTransactionId.isBlank()) { "REPREPARATION_NOT_PROVEN" }
        released
    } }
    fun pendingPlan(run:UpdateRun):StockRestorePlan?=runCatching {
        if(run.restoreTransactionId.isBlank())return@runCatching null
        StockRestorePlanCodec.decode(readPrivate(File(transaction(run.restoreTransactionId),"request.properties"),16_384).toString(Charsets.UTF_8)).first.takeIf { it.runId==run.id }
    }.getOrNull()
    private fun transaction(id:String):File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory,"transactions/$id").also { require(it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalPath==it.path && it.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) }
    }
    private fun writePlan(tx:File,p:StockRestorePlan,c:RestoreConsent?=null)=RestoreRootProtocol.writePrivate(File(tx,"request.properties"),StockRestorePlanCodec.encode(p,c).toByteArray(Charsets.UTF_8))
    private fun secret():ByteArray=synchronized(KEY_LOCK){
        val file=File(directory,"consent.key")
        if(!file.exists() && !File(file.path+".bak").exists() && !File(file.path+".new").exists())RestoreRootProtocol.writePrivate(file,ByteArray(32).also { SecureRandom().nextBytes(it) })
        readPrivate(file,32).also { require(it.size==32) }
    }
    private fun readPrivate(file:File,limit:Int):ByteArray {
        require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
        listOf(file,File(file.path+".bak")).forEach { require(!java.nio.file.Files.isSymbolicLink(it.toPath())) }
        return android.util.AtomicFile(file).openRead().use { input -> input.readNBytes(limit+1).also { require(it.size<=limit) } }
    }
    private fun<T> withWriteWakeLock(block:()->T):T {
        val manager=context.getSystemService(PowerManager::class.java) ?: throw UpdateSafetyException("WAKE_LOCK_UNAVAILABLE")
        val lock=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"nl.moto.ota:stock-restore").apply { setReferenceCounted(false) }
        lock.acquire(65_000)
        try{return block()}finally{if(lock.isHeld)lock.release()}
    }
    companion object { private val KEY_LOCK=Any();private val OPERATION_LOCK=Any() }
}
