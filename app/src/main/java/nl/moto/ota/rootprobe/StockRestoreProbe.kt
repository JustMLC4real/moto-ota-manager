package nl.moto.ota.rootprobe

import android.os.Build
import android.os.IUpdateEngine
import android.os.IUpdateEngineCallback
import android.os.IBinder
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.update.RestoreRootProtocol
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Narrow, explicit-consent live writer for the audited 8 MiB init_boot profile.
 * Never sources Magisk scripts, calls bootctl, changes slots/settings, installs OTA,
 * resets update state, restores a backup automatically or reboots.
 *
 * The app authenticates stock provenance against its signed evidence vault before
 * issuing an HMAC. This helper trusts that app-private key, then independently
 * remeasures the live identity, safety gates, source bytes and complete partition.
 * Root already has authority over the app; this protocol is not a hostile-root defense.
 */
object StockRestoreProbe {
    private const val ROOT="/data/user/0/nl.moto.ota/files/update"
    private const val BY_NAME="/dev/block/by-name/"
    private const val IMAGE_BYTES=8_388_608L
    private var deadlineElapsedMillis=0L

    @JvmStatic fun main(args:Array<String>) {
        deadlineElapsedMillis=SystemClock.elapsedRealtime()+55_000
        // Killing su alone is not a guarantee that its child has exited. Bound this
        // exact dedicated process too, including a hung OEM Binder call.
        val watchdog=thread(isDaemon=true,name="moto-stock-restore-deadline") {
            try { Thread.sleep(55_000) }catch(_:InterruptedException){return@thread}
            System.out.println("{\"error\":\"RESTORE_PROCESS_DEADLINE\"}");System.out.flush()
            Runtime.getRuntime().halt(70)
        }
        try {
            if(android.os.Process.myUid()!=0 || Build.VERSION.SDK_INT!=37 || args.size!=2 || args[0] !in setOf("observe","execute","recover","verify-unchanged"))
                throw UpdateSafetyException("UNSUPPORTED_RESTORE_RUNTIME")
            require(args[1].matches(Regex("[a-f0-9-]{36}")))
            val root=File(ROOT).canonicalFile
            require(root.path in setOf(ROOT,"/data/data/nl.moto.ota/files/update") && root.isDirectory)
            val owner=Os.stat(root.path).st_uid
            require(owner>=10_000 && Os.stat(root.path).st_mode and 0x002==0)
            val tx=File(root,"transactions/${args[1]}")
            require(tx.isDirectory && tx.canonicalPath==tx.path && Os.lstat(tx.path).st_uid==owner)
            val request=privateFile(File(tx,"request.properties"),owner,16_384)
            val requestBytes=request.inputStream().use { it.readNBytes(16_385) };require(requestBytes.size<=16_384)
            val (plan,consent)=StockRestorePlanCodec.decode(requestBytes.toString(Charsets.UTF_8))
            require(plan.transactionId==args[1] && plan.mode==ExecutionMode.LIVE && plan.imageBytes==IMAGE_BYTES)
            val result=when(args[0]) {
                "observe" -> RestoreRootProtocol.observation(observe(plan,tx,owner))
                "recover" -> RestoreRootProtocol.receipt(recover(plan,tx,owner))
                "verify-unchanged" -> verifyUnchanged(plan,tx,owner)
                else -> RestoreRootProtocol.receipt(execute(plan,requireNotNull(consent),root,tx,owner))
            }
            println(result.toString());System.out.flush()
        }catch(error:Throwable){
            val code=(error as? UpdateSafetyException)?.code ?: "ROOT_RESTORE_GUARD_REJECTED"
            println(JSONObject().put("error",code).toString());System.out.flush()
        }
        watchdog.interrupt()
        // Only the dedicated app_process exits. The phone is never restarted.
        kotlin.system.exitProcess(0)
    }

    private fun observe(plan:StockRestorePlan,tx:File,owner:Int):RestoreEnvironment {
        val started=System.currentTimeMillis()
        require(android.os.Process.myUid()==0 && Build.VERSION.SDK_INT==37)
        fun property(name:String)=command(listOf("/system/bin/getprop",name)).trim()
        val slot=property("ro.boot.slot_suffix")
        require(slot in setOf("_a","_b"))
        val kernel=File("/proc/bootconfig").readText().lineSequence().mapNotNull {
            val pair=it.split('=',limit=2);if(pair.size==2)pair[0].trim().removePrefix("androidboot.") to pair[1].trim().trim('"') else null
        }.toMap()
        require(kernel["slot_suffix"]==slot && kernel["verifiedbootstate"]=="orange" && kernel["vbmeta.device_state"]=="unlocked" && kernel["flash.locked"]=="0")
        val misc=block("misc")
        val control=RandomAccessFile(misc,"r").use { f -> f.seek(2048);ByteArray(32).also { f.readFully(it) } }.let(ReadOnlyMetadata::parseBootControl)
            ?: throw UpdateSafetyException("BOOT_METADATA_UNPROVEN")
        val index=if(slot=="_a")0 else 1
        require(control.slotCount==2 && control.slots[index].markedSuccessful && !control.slots[index].verityCorrupted)
        require(control.recordedSlotSuffix.isEmpty() || control.recordedSlotSuffix==slot)
        val vab=RandomAccessFile(misc,"r").use { f -> f.seek(32768);ByteArray(64).also { f.readFully(it) } }.let(ReadOnlyMetadata::parseVirtualAb)
            ?: throw UpdateSafetyException("SNAPSHOT_METADATA_UNPROVEN")
        require(vab.mergeStatus==VirtualAbMergeStatus.NONE)
        val state=File("/metadata/ota/state")
        require(state.isFile && state.length()<=65_536)
        val snapshot=ReadOnlyMetadata.parseSnapshotState(state.readBytes())
        require(snapshot?.state==SnapshotState.NONE)
        val snapshots=File("/metadata/ota/snapshots")
        require(snapshots.isDirectory && snapshots.list()?.isEmpty()==true)
        if(!freshEngineIdle())throw UpdateSafetyException("ENGINE_NOT_PROVEN_IDLE")
        require(property("ro.virtual_ab.enabled")=="true")
        val magisk=command(listOf("/system/bin/sh","-c","magisk -V")).trim().toIntOrNull()
        require(magisk==31100)
        val autoDisabled=command(listOf("/system/bin/settings","get","global","ota_disable_automatic_update")).trim()=="1"
        val battery=command(listOf("/system/bin/dumpsys","battery")).lineSequence()
            .firstOrNull { it.trim().startsWith("level:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1
        val modules=File("/data/adb/modules")
        require(modules.isDirectory)
        val moduleList=modules.listFiles()?.filter { it.isDirectory }?.map {
            require(it.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
            it.name+"|"+(if(File(it,"disable").exists())"disabled" else "enabled")+(if(File(it,"remove").exists())"|pending-removal" else "")
        }?.sorted() ?: throw UpdateSafetyException("MODULES_UNREADABLE")
        val current=block("init_boot$slot");val other=block("init_boot"+if(slot=="_a")"_b"else"_a")
        require(blockBytes(current)==IMAGE_BYTES && blockBytes(other)==IMAGE_BYTES)
        val manufacturer=property("ro.product.manufacturer")
        val technicalModels=listOf(property("ro.boot.hardware.sku"),property("ro.boot.product.hardware.sku"),property("ro.product.model")).filter { it.matches(Regex("XT[0-9]+(?:-[0-9]+)?",RegexOption.IGNORE_CASE)) }.distinct()
        val sku=if(technicalModels==listOf("XT2607-1"))"XT2607-1"else""
        val channels=listOf(property("ro.boot.carrier"),property("ro.carrier")).filter { it.isNotBlank() }.distinct()
        val channel=if(channels==listOf("reteu"))"reteu"else""
        val device=DeviceState(manufacturer=manufacturer,model=sku,device=property("ro.product.device"),channel=channel,
            androidVersion=property("ro.build.version.release"),build=property("ro.build.id"),fingerprint=property("ro.build.fingerprint"),slot=slot,
            rootGranted=true,rootChecked=true,magiskCode=magisk?:0,magiskVersion="31.1",virtualAb=true,snapshot=SnapshotState.NONE,engine=EngineState.IDLE,
            batteryPercent=battery,freeBytes=StatFs(tx.path).availableBytes,bootId=File("/proc/sys/kernel/random/boot_id").readText().trim(),
            partitions=listOf("init_boot_a","init_boot_b"),modules=moduleList,moduleInventoryKnown=true,capturedAtMillis=started,
            bootSuccessful=true,bootloaderUnlocked=true,verifiedBootState="orange",securityEvidenceSource="KERNEL_BOOTCONFIG")
        val source=privateFile(File(tx,"stock.img"),owner,IMAGE_BYTES)
        val reference=StockReference(plan.stockSha256,IMAGE_BYTES,plan.build,plan.fingerprint,plan.device,plan.model,plan.channel,
            authenticityVerified=true,evidence="App-private HMAC-bound stock claim; app verifies authenticated OTA provenance")
        val validation=source.inputStream().use { BackupValidator.inspect(it,false,IMAGE_BYTES,device,reference) }
        if(!validation.usableAsStock)throw UpdateSafetyException("STOCK_PROVENANCE")
        // Independent association with the image that actually booted Magisk:
        // getprop identity can legitimately be spoofed by a root module.
        if(!MagiskRuntimeConfig.matchesStock(runtimeStockSha1(),validation))throw UpdateSafetyException("MAGISK_BACKUP_ASSOCIATION_MISMATCH")
        val environment=RestoreEnvironment(device,IMAGE_BYTES,digestBlock(current),autoDisabled,validation,ExecutionMode.LIVE,SystemClock.elapsedRealtime())
        val blockers=StockRestorePolicy.blockers(environment)
        if(blockers.isNotEmpty())throw UpdateSafetyException(blockers.first().code)
        return environment
    }

    private fun runtimeStockSha1():String {
        val path=MagiskRuntimeConfig.configPath(command(listOf("/system/bin/sh","-c","magisk --path")))
            ?: throw UpdateSafetyException("MAGISK_RUNTIME_PATH_UNSUPPORTED")
        try {
            val file=File(path)
            require(file.canonicalPath==path)
            val configDirectory=requireNotNull(file.parentFile)
            val runtimeRoot=requireNotNull(configDirectory.parentFile)
            for(directory in listOf(runtimeRoot,configDirectory)){
                val attr=Os.lstat(directory.path)
                require(OsConstants.S_ISDIR(attr.st_mode) && attr.st_uid==0)
            }
            val before=Os.lstat(path)
            require(OsConstants.S_ISREG(before.st_mode) && before.st_uid==0 && before.st_size in 1..MagiskRuntimeConfig.MAX_BYTES.toLong())
            val fd=Os.open(path,OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,0)
            val bytes=try {
                val opened=Os.fstat(fd)
                require(OsConstants.S_ISREG(opened.st_mode) && opened.st_uid==0 && opened.st_dev==before.st_dev && opened.st_ino==before.st_ino && opened.st_size==before.st_size)
                val buffer=ByteArray(MagiskRuntimeConfig.MAX_BYTES+1);var count=0
                while(count<buffer.size){val n=Os.read(fd,buffer,count,buffer.size-count);if(n==0)break;require(n>0);count+=n}
                val after=Os.fstat(fd);val current=Os.lstat(path)
                require(count.toLong()==before.st_size && count<=MagiskRuntimeConfig.MAX_BYTES &&
                    after.st_dev==before.st_dev && after.st_ino==before.st_ino && after.st_size==before.st_size && after.st_mtime==before.st_mtime && after.st_ctime==before.st_ctime &&
                    current.st_dev==before.st_dev && current.st_ino==before.st_ino && current.st_size==before.st_size && current.st_mtime==before.st_mtime && current.st_ctime==before.st_ctime)
                buffer.copyOf(count)
            }finally{Os.close(fd)}
            return MagiskRuntimeConfig.stockSha1(bytes) ?: throw UpdateSafetyException("MAGISK_RUNTIME_CONFIG_INVALID")
        }catch(error:UpdateSafetyException){throw error}
        catch(_:Exception){throw UpdateSafetyException("MAGISK_RUNTIME_CONFIG_UNAVAILABLE")}
    }

    private fun execute(plan:StockRestorePlan,consent:RestoreConsent,root:File,tx:File,owner:Int):StockRestoreResult {
        val secret=privateFile(File(root,"consent.key"),owner,32).inputStream().use { it.readNBytes(33) }.also { require(it.size==32) }
        var backupHash=""
        var writeStarted=false
        try {
            RandomAccessFile(File(root,"stock-restore.lock"),"rw").channel.use { channel ->
                val lock=channel.tryLock() ?: throw UpdateSafetyException("RESTORE_ALREADY_RUNNING")
                lock.use {
                    StockRestorePolicy.verify(plan,consent,secret,observe(plan,tx,owner))
                    val source=privateFile(File(tx,"stock.img"),owner,IMAGE_BYTES).inputStream().use { it.readNBytes(IMAGE_BYTES.toInt()+1) }
                    require(source.size.toLong()==IMAGE_BYTES && digest(source)==plan.stockSha256)
                    checkpoint(tx,owner,RestoreCheckpoint.PREPARED,plan,"")
                    val target=block(plan.partition)
                    val backup=File(tx,"patched-backup.img")
                    if(!backup.exists()) {
                        val current=readBlock(target)
                        require(digest(current)==plan.previousImageSha256)
                        writeOwned(backup,current,owner)
                    }
                    backupHash=RestoreRootProtocol.digest(privateFile(backup,owner,IMAGE_BYTES))
                    if(backupHash!=plan.previousImageSha256)throw UpdateSafetyException("BACKUP_MISMATCH")
                    checkpoint(tx,owner,RestoreCheckpoint.BACKUP_VERIFIED,plan,backupHash)
                    val marker=File(tx,"consent.consumed")
                    val markerFd=try { Os.open(marker.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180) }
                    catch(_:Exception){throw UpdateSafetyException("CONSENT_ALREADY_CONSUMED")}
                    try { val b=consent.macSha256.toByteArray(Charsets.US_ASCII);Os.write(markerFd,b,0,b.size);Os.fsync(markerFd) }finally { Os.close(markerFd) }
                    syncDirectory(tx)
                    checkpoint(tx,owner,RestoreCheckpoint.TOKEN_CONSUMED,plan,backupHash)
                    // Every guard and the complete current partition hash are measured again
                    // after durable backup and immediately before opening the write descriptor.
                    val immediatelyBeforeWrite=observe(plan,tx,owner)
                    StockRestorePolicy.verify(plan,consent,secret,immediatelyBeforeWrite)
                    require(digest(source)==plan.stockSha256)
                    if(SystemClock.elapsedRealtime()>deadlineElapsedMillis-10_000)throw UpdateSafetyException("INSUFFICIENT_WRITE_TIME_RESERVE")
                    checkpoint(tx,owner,RestoreCheckpoint.WRITE_STARTED,plan,backupHash)
                    StockRestorePolicy.verify(plan,consent,secret,immediatelyBeforeWrite.copy(elapsedRealtimeMillis=SystemClock.elapsedRealtime()))
                    if(SystemClock.elapsedRealtime()>deadlineElapsedMillis-10_000)throw UpdateSafetyException("INSUFFICIENT_WRITE_TIME_RESERVE")
                    val fd=Os.open(target.path,OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,0)
                    try {
                        val stat=Os.fstat(fd)
                        require(OsConstants.S_ISBLK(stat.st_mode) && stat.st_rdev==Os.stat(target.path).st_rdev)
                        require(blockBytes(target)==IMAGE_BYTES)
                        Os.lseek(fd,0,OsConstants.SEEK_SET)
                        StockRestorePolicy.verify(plan,consent,secret,immediatelyBeforeWrite.copy(elapsedRealtimeMillis=SystemClock.elapsedRealtime()))
                        if(SystemClock.elapsedRealtime()>deadlineElapsedMillis-10_000)throw UpdateSafetyException("INSUFFICIENT_WRITE_TIME_RESERVE")
                        writeStarted=true
                        var position=0
                        while(position<source.size){val count=Os.write(fd,source,position,minOf(65_536,source.size-position));require(count>0);position+=count}
                        Os.fsync(fd)
                    }finally { Os.close(fd) }
                    val observed=digestBlock(target)
                    if(observed!=plan.stockSha256)throw UpdateSafetyException("READBACK_MISMATCH")
                    checkpoint(tx,owner,RestoreCheckpoint.STOCK_VERIFIED,plan,backupHash)
                    return StockRestoreResult(plan.transactionId,RestoreCheckpoint.STOCK_VERIFIED,observed,backupHash,true)
                }
            }
        }catch(error:Exception){
            val code=(error as? UpdateSafetyException)?.code ?: if(writeStarted)"WRITE_INTERRUPTED_REQUIRES_REVIEW"else"RESTORE_GUARD_REJECTED"
            checkpoint(tx,owner,RestoreCheckpoint.RECOVERY_REQUIRED,plan,backupHash)
            return StockRestoreResult(plan.transactionId,RestoreCheckpoint.RECOVERY_REQUIRED,runCatching { digestBlock(block(plan.partition)) }.getOrDefault(""),backupHash,false,code)
        }
    }

    /** Observation-only recovery; no token is refreshed, no image is written. */
    private fun recover(plan:StockRestorePlan,tx:File,owner:Int):StockRestoreResult {
        RandomAccessFile(File(ROOT,"stock-restore.lock"),"rw").channel.use { channel ->
            val lock=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
                ?: throw UpdateSafetyException("RESTORE_ALREADY_RUNNING")
            lock.use { return recoverLocked(plan,tx,owner) }
        }
    }
    private fun recoverLocked(plan:StockRestorePlan,tx:File,owner:Int):StockRestoreResult {
        val currentBoot=File("/proc/sys/kernel/random/boot_id").readText().trim()
        val currentSlot=command(listOf("/system/bin/getprop","ro.boot.slot_suffix")).trim()
        val currentBuild=command(listOf("/system/bin/getprop","ro.build.id")).trim()
        val currentFingerprint=command(listOf("/system/bin/getprop","ro.build.fingerprint")).trim()
        val backup=File(tx,"patched-backup.img")
        val backupHash=runCatching { RestoreRootProtocol.digest(privateFile(backup,owner,IMAGE_BYTES)) }.getOrDefault("")
        val observed=runCatching { digestBlock(block(plan.partition)) }.getOrDefault("")
        val restored=currentBoot==plan.bootId && currentSlot==plan.slot && currentBuild==plan.build && currentFingerprint==plan.fingerprint && observed==plan.stockSha256 && backupHash==plan.previousImageSha256
        val unchanged=currentBoot==plan.bootId && currentSlot==plan.slot && currentBuild==plan.build && currentFingerprint==plan.fingerprint && observed==plan.previousImageSha256 && backupHash==plan.previousImageSha256
        if(unchanged && !restored)return StockRestoreResult(plan.transactionId,RestoreCheckpoint.SOURCE_UNCHANGED_VERIFIED,observed,backupHash,false,"PREVIOUS_PATCHED_IMAGE_UNCHANGED")
        return StockRestoreResult(plan.transactionId,if(restored)RestoreCheckpoint.STOCK_VERIFIED else RestoreCheckpoint.RECOVERY_REQUIRED,observed,backupHash,restored,
            if(restored)null else "OBSERVATION_ONLY_NO_AUTOMATIC_REPLAY")
    }
    private fun verifyUnchanged(plan:StockRestorePlan,tx:File,owner:Int):JSONObject {
        RandomAccessFile(File(ROOT,"stock-restore.lock"),"rw").channel.use { channel ->
            val lock=channel.tryLock() ?: throw UpdateSafetyException("RESTORE_ALREADY_RUNNING")
            lock.use {
                val env=observe(plan,tx,owner);val d=env.device
                require(d.bootId==plan.bootId && d.slot==plan.slot && d.build==plan.build && d.fingerprint==plan.fingerprint && env.activeImageSha256==plan.previousImageSha256)
                val backup=File(tx,"patched-backup.img")
                if(!backup.exists()){
                    val current=readBlock(block(plan.partition));require(digest(current)==plan.previousImageSha256)
                    writeOwned(backup,current,owner);syncDirectory(tx)
                }
                val result=recoverLocked(plan,tx,owner)
                if(result.checkpoint!=RestoreCheckpoint.SOURCE_UNCHANGED_VERIFIED)throw UpdateSafetyException("SOURCE_NOT_UNCHANGED")
                // Explicitly invalidate the old capability even if a process died
                // before consuming it. No partition is written by this operation.
                val marker=File(tx,"consent.consumed")
                if(!marker.exists()){
                    val fd=Os.open(marker.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180)
                    try{val bytes="INVALIDATED_FOR_NEW_PREPARATION".toByteArray();Os.write(fd,bytes,0,bytes.size);Os.fsync(fd)}finally{Os.close(fd)}
                    syncDirectory(tx)
                }
                val ready=result.copy(checkpoint=RestoreCheckpoint.REPREPARATION_READY,errorCode=null)
                checkpoint(tx,owner,RestoreCheckpoint.REPREPARATION_READY,plan,result.backupSha256)
                return JSONObject().put("receipt",RestoreRootProtocol.receipt(ready)).put("observation",RestoreRootProtocol.observation(env))
            }
        }
    }

    private fun block(name:String):File {
        require(name in setOf("misc","init_boot_a","init_boot_b"))
        val file=File(BY_NAME+name).canonicalFile
        require(file.path.startsWith("/dev/block/") && OsConstants.S_ISBLK(Os.stat(file.path).st_mode))
        return file
    }
    private fun blockBytes(file:File):Long=command(listOf("/system/bin/blockdev","--getsize64",file.path)).trim().toLong()
    private fun readBlock(file:File):ByteArray=ByteArray(IMAGE_BYTES.toInt()).also { bytes ->
        require(blockBytes(file)==IMAGE_BYTES)
        file.inputStream().use { input -> var p=0;while(p<bytes.size){val n=input.read(bytes,p,bytes.size-p);require(n>0);p+=n};require(input.read()==-1) }
    }
    private fun digestBlock(file:File):String=digest(readBlock(file))
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun privateFile(file:File,owner:Int,maxBytes:Long):File {
        val stat=Os.lstat(file.path)
        require(file.canonicalPath==file.path && OsConstants.S_ISREG(stat.st_mode) && stat.st_uid==owner && stat.st_mode and 0x03f==0 && stat.st_size in 1..maxBytes)
        return file
    }
    private fun writeOwned(file:File,bytes:ByteArray,owner:Int){
        RestoreRootProtocol.writePrivate(file,bytes);Os.chown(file.path,owner,owner);Os.chmod(file.path,0x180)
    }
    private fun checkpoint(tx:File,owner:Int,stage:RestoreCheckpoint,plan:StockRestorePlan,backup:String){
        writeOwned(File(tx,"checkpoint.json"),JSONObject().put("transaction",plan.transactionId).put("checkpoint",stage.name)
            .put("stockSha256",plan.stockSha256).put("patchedBackupSha256",backup).toString().toByteArray(Charsets.UTF_8),owner)
        syncDirectory(tx)
    }
    private fun syncDirectory(directory:File){
        require(directory.canonicalPath==directory.path && OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode))
        val fd=Os.open(directory.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try{require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}
    }
    private fun command(args:List<String>):String {
        val process=ProcessBuilder(args).redirectErrorStream(false).start()
        val out=java.io.ByteArrayOutputStream();var tooLong=false
        val reader=thread(isDaemon=true){process.inputStream.use { input -> val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;synchronized(out){if(out.size()+n<=65_536)out.write(b,0,n)else tooLong=true}} }}
        val errors=thread(isDaemon=true){process.errorStream.use { input -> val b=ByteArray(4096);while(input.read(b)>=0){} }}
        val complete=process.waitFor(3,TimeUnit.SECONDS);if(!complete)process.destroyForcibly()
        reader.join(500);errors.join(500)
        require(complete && !reader.isAlive && !tooLong && process.exitValue()==0)
        return synchronized(out){out.toString("UTF-8")}
    }

    /** Read-only bind/unbind using the actual boot framework interfaces, never guessed transactions. */
    private fun freshEngineIdle():Boolean {
        if (IUpdateEngine::class.java.classLoader != Build::class.java.classLoader ||
            IUpdateEngineCallback::class.java.classLoader != Build::class.java.classLoader) {
            return false
        }
        val latch = CountDownLatch(1)
        var status = -1
        var malformed = false
        val callback = object : IUpdateEngineCallback.Stub() {
            @Synchronized override fun onStatusUpdate(code:Int,progress:Float) {
                if (!progress.isFinite() || progress !in 0f..1f) {
                    malformed = true
                }
                status = code
                latch.countDown()
            }
            @Synchronized override fun onPayloadApplicationComplete(code:Int) {
                if (code != 0) {
                    malformed = true
                }
                latch.countDown()
            }
        }
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java)
            .invoke(null,"android.os.UpdateEngineService") as? IBinder ?: return false
        if (binder.interfaceDescriptor != "android.os.IUpdateEngine") {
            return false
        }
        val engine = IUpdateEngine.Stub.asInterface(binder)
        var bound = false
        val received:Boolean
        try {
            bound = engine.bind(callback)
            received = bound && latch.await(3,TimeUnit.SECONDS)
        } finally {
            if (bound && !engine.unbind(callback)) {
                throw UpdateSafetyException("ENGINE_UNBIND_UNCONFIRMED")
            }
        }
        // Evaluate after unbind: a late error/status callback during that call
        // must still invalidate IDLE, and the monitor publishes callback writes.
        return synchronized(callback) { received && !malformed && status == 0 }
    }
}
