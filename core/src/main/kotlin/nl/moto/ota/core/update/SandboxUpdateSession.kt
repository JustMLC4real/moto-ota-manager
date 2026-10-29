package nl.moto.ota.core.update

import nl.moto.ota.core.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * A complete offline exercise with real regular-file I/O and durable workflow state.
 * OTA, Magisk patching, reboot and merge observations are explicitly simulated.
 * Every DeviceState and UpdateRun retains simulated=true; no Android API is used.
 */
class SandboxUpdateSession private constructor(val directory:File) {
    val sourceStock:ByteArray=getImage(17)
    val targetStock:ByteArray=getImage(29)
    var device:DeviceState=DeviceState(manufacturer="motorola",model="XT2607-1",device="merc",channel="reteu",androidVersion="17",
        build="SIMULATED-SOURCE",fingerprint="SIMULATED/merc:17/source",slot="_b",rootGranted=true,rootChecked=true,magiskVersion="31.1",magiskCode=31100,
        virtualAb=true,snapshot=SnapshotState.NONE,engine=EngineState.IDLE,batteryPercent=80,freeBytes=20L*1024*1024*1024,bootId="SIMULATED-SOURCE-BOOT",
        partitions=listOf("init_boot_a","init_boot_b"),modules=listOf("fixture-module|enabled"),moduleInventoryKnown=true,bootSuccessful=true,
        bootloaderUnlocked=true,verifiedBootState="orange",securityEvidenceSource="SIMULATED",simulated=true,capturedAtMillis=System.currentTimeMillis())
        private set
    var run:UpdateRun=UpdateRun(simulated=true)
        private set
    val backend:SandboxStockRestoreBackend
    init {
        require(directory.name.startsWith("moto-ota-sandbox-") && directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        require(directory.list()?.isEmpty()==true) { "A new simulation needs an empty dedicated directory; journals are never replayed automatically" }
        File(directory,"partitions").mkdir()
        File(directory,"stock.img").writeBytes(sourceStock)
        File(directory,"partitions/init_boot_a").writeBytes(mockPatch(sourceStock))
        File(directory,"partitions/init_boot_b").writeBytes(mockPatch(sourceStock))
        backend=SandboxStockRestoreBackend(directory,{device.copy(capturedAtMillis=System.currentTimeMillis())},reference(sourceStock,device))
        val backup=backend.observe().stockValidation
        run=UpdateCoordinator.startSandboxGuidedRun(device,backup,ObservedUpdateTarget("SIMULATED-TARGET","SIMULATED/vendor:15/unrelated-namespace"))
        persist()
    }
    fun restoreStock():UpdateRun {
        require(run.stage==UpdateStage.STOCK_PREPARATION)
        val plan=backend.prepare(run.id)
        transition(UpdateCoordinator.restorePrepared(run,plan))
        transition(UpdateCoordinator.restoreDispatching(run))
        transition(UpdateCoordinator.restoreFinished(run,backend.execute(plan,backend.confirm(plan,true,true))))
        return run
    }
    fun simulateOfficialOta():UpdateRun {
        require(run.stage==UpdateStage.STOCK_RESTORED)
        transition(UpdateCoordinator.openOfficialOta(run))
        require(run.stage==UpdateStage.AWAITING_OFFICIAL_OTA)
        // Mock OEM installation changes only the inactive regular file.
        val target=backend.partitionFile(run.targetSlot)
        atomicWrite(target,targetStock)
        device=device.copy(engine=EngineState.UPDATED_NEED_REBOOT,snapshot=SnapshotState.UNVERIFIED,capturedAtMillis=System.currentTimeMillis())
        transition(UpdateCoordinator.reconcile(run,device))
        return run
    }
    fun simulateMagiskHandoff():UpdateRun {
        require(run.stage==UpdateStage.MAGISK_REQUIRED)
        atomicWrite(backend.partitionFile(run.targetSlot),mockPatch(targetStock))
        // This is deliberately a reported step. A mock byte transformation is
        // never represented as independently verified production Magisk patching.
        transition(UpdateCoordinator.reportMagiskInstalled(run))
        return run
    }
    fun simulateRebootAndMerge():UpdateRun {
        require(run.stage==UpdateStage.MAGISK_REPORTED_UNVERIFIED)
        transition(UpdateCoordinator.acknowledgeReboot(run,true))
        require(run.stage==UpdateStage.AWAITING_NEW_BOOT)
        device=device.copy(build=run.targetBuild,fingerprint="SIMULATED/merc:17/target",slot=run.targetSlot,bootId="SIMULATED-TARGET-BOOT",
            engine=EngineState.IDLE,snapshot=SnapshotState.MERGING,capturedAtMillis=System.currentTimeMillis())
        transition(UpdateCoordinator.reconcile(run,device))
        return run
    }
    fun simulateMergeFinished():UpdateRun {
        require(run.stage==UpdateStage.MERGING)
        device=device.copy(snapshot=SnapshotState.NONE,capturedAtMillis=System.currentTimeMillis())
        // Simulate recapturing Magisk's original-image backup for the new build.
        atomicWrite(File(directory,"new-stock-backup.img"),targetStock)
        val validation=BackupValidator.inspect(File(directory,"new-stock-backup.img").inputStream(),false,targetStock.size.toLong(),device,reference(targetStock,device))
        transition(UpdateCoordinator.reconcile(run,device,validation))
        return run
    }
    fun runAll():UpdateRun {restoreStock();simulateOfficialOta();simulateMagiskHandoff();simulateRebootAndMerge();return simulateMergeFinished()}
    fun journal():UpdateRun=UpdateRun.decode(File(directory,"update-run.properties").readText())
    fun partitionSha256(slot:String):String=digest(backend.partitionFile(slot).readBytes())
    private fun transition(next:UpdateRun){require(next.simulated);run=next;persist()}
    private fun persist()=atomicWrite(File(directory,"update-run.properties"),run.encode().toByteArray(Charsets.UTF_8))
    private fun atomicWrite(file:File,bytes:ByteArray){
        require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && !Files.isSymbolicLink(file.toPath()))
        val tmp=Files.createTempFile(file.parentFile.toPath(),".simulated-update-",".tmp").toFile()
        try{FileOutputStream(tmp).use {it.write(bytes);it.fd.sync()};Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)}finally{Files.deleteIfExists(tmp.toPath())}
    }
    private fun reference(bytes:ByteArray,d:DeviceState)=StockReference(digest(bytes),bytes.size.toLong(),d.build,d.fingerprint,d.device,d.model,d.channel,
        authenticityVerified=true,evidence="SIMULATED authenticated fixture; never a real firmware trust reference")
    companion object {
        fun create(directory:File):SandboxUpdateSession=SandboxUpdateSession(directory)
        private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        private fun mockPatch(bytes:ByteArray)=bytes.copyOf().apply { for(i in 4096 until size)this[i]=(this[i].toInt() xor 85).toByte() }
        private fun getImage(seed:Int)=ByteArray(8192).apply {
            "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(this)
            fun le(offset:Int,n:Int){for(i in 0..3)this[offset+i]=(n ushr (8*i)).toByte()}
            le(8,0);le(12,512);le(20,1584);le(40,4)
            for(i in 4096 until 4608)this[i]=(i*seed%251).toByte()
        }
    }
}
