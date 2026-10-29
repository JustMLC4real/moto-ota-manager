package nl.moto.ota.rootprobe

import android.os.*
import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Read-only facts shared by the constrained update helpers. No bootctl initializers. */
internal object RootUpdateFacts {
    val FILES = File("/data/user/0/nl.moto.ota/files").canonicalFile
    fun owner(): Int {
        require(android.os.Process.myUid()==0 && Build.VERSION.SDK_INT in DeviceCapabilities.supportedRestoreSdks)
        require(FILES.path in setOf("/data/user/0/nl.moto.ota/files","/data/data/nl.moto.ota/files"))
        val s=Os.lstat(FILES.path)
        require(OsConstants.S_ISDIR(s.st_mode) && s.st_uid>=10_000 && s.st_mode and 0x12==0)
        return s.st_uid
    }
    fun journal(owner:Int):UpdateRun {
        val file=privateFile(File(FILES,"guided-update-run.properties"),owner,131_072)
        return UpdateRun.decode(file.inputStream().use {it.readNBytes(131_073).toString(Charsets.UTF_8)}).also {
            require(!it.simulated && it.id.matches(Regex("[a-f0-9-]{36}")) && it.sourceModel.isNotBlank() &&
                it.sourceDevice.isNotBlank() && it.sourceChannel.isNotBlank() && it.packageVerified)
        }
    }
    fun observe(run:UpdateRun,pendingOta:Boolean):DeviceState {
        val start=System.currentTimeMillis()
        owner()
        fun p(key:String)=command(listOf("/system/bin/getprop",key)).trim()
        val kernel=File("/proc/bootconfig").readText().lineSequence().mapNotNull {
            val pair=it.split('=',limit=2)
            if(pair.size==2)pair[0].trim().removePrefix("androidboot.") to pair[1].trim().trim('"') else null
        }.toMap()
        val slot=p("ro.boot.slot_suffix")
        require(slot==run.sourceSlot && kernel["slot_suffix"]==slot &&
            kernel["verifiedbootstate"]=="orange" && kernel["vbmeta.device_state"]=="unlocked" && kernel["flash.locked"]=="0")
        val control=bootControl()
        val index=if(slot=="_a")0 else 1
        require(control.slotCount==2 && control.recordedSlotSuffix in setOf("",slot) &&
            control.slots[index].markedSuccessful && !control.slots[index].verityCorrupted)
        val virtualAb=when(p("ro.virtual_ab.enabled")){"true"->true;"false"->false;else->error("VIRTUAL_AB_UNKNOWN")}
        require(p("ro.build.ab_update")!="false")
        val misc=block("misc")
        val vab=RandomAccessFile(misc,"r").use {f->f.seek(32768);ByteArray(64).also {f.readFully(it)}}.let(ReadOnlyMetadata::parseVirtualAb)
        val snapshot:SnapshotState
        if(virtualAb){
            require(vab!=null)
            if(pendingOta)require(vab.sourceSlot==index && vab.mergeStatus in setOf(VirtualAbMergeStatus.NONE,VirtualAbMergeStatus.SNAPSHOTTED))
            else require(vab.mergeStatus==VirtualAbMergeStatus.NONE)
            val state=File("/metadata/ota/state")
            require(state.isFile && state.length()<=65_536)
            val data=state.inputStream().use {it.readNBytes(65_537)}
            require(data.size<=65_536 && state.length()==data.size.toLong())
            snapshot=ReadOnlyMetadata.parseSnapshotState(data)?.state ?: error("SNAPSHOT_UNKNOWN")
            require(snapshot in if(pendingOta)setOf(SnapshotState.NONE,SnapshotState.UNVERIFIED) else setOf(SnapshotState.NONE))
            val directory=File("/metadata/ota/snapshots")
            require(directory.isDirectory && directory.list()!=null)
            if(!pendingOta)require(directory.list()!!.isEmpty())
        }else{
            require(vab==null || vab.mergeStatus==VirtualAbMergeStatus.NONE)
            val state=File("/metadata/ota/state")
            if(state.exists()) {
                require(state.isFile && state.length()<=65_536)
                val data=state.inputStream().use {it.readNBytes(65_537)}
                require(data.size<=65_536 && state.length()==data.size.toLong() && ReadOnlyMetadata.parseSnapshotState(data)?.state==SnapshotState.NONE)
            }
            val snapshots=File("/metadata/ota/snapshots")
            if(snapshots.exists())require(snapshots.isDirectory && snapshots.list()?.isEmpty()==true)
            snapshot=SnapshotState.NOT_APPLICABLE
        }
        val expected=if(pendingOta)6 else 0
        require(engineStatus()==expected)
        val models=listOf(p("ro.boot.hardware.sku"),p("ro.boot.product.hardware.sku"),p("ro.product.model"))
            .filter {it.matches(Regex("XT[0-9]+(?:-[0-9]+)?",RegexOption.IGNORE_CASE))}.distinct()
        require(models.size<=1)
        val model=models.singleOrNull()?:p("ro.product.model")
        val channels=listOf(p("ro.boot.carrier"),p("ro.carrier")).filter(String::isNotBlank).distinct()
        require(channels.size==1)
        val modules=File("/data/adb/modules").also {require(it.isDirectory)}.listFiles()?.filter {it.isDirectory}?.map {
            require(it.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
            it.name+"|"+(if(File(it,"disable").exists())"disabled"else"enabled")+(if(File(it,"remove").exists())"|pending-removal"else"")
        }?.sorted() ?: error("MODULES_UNKNOWN")
        val magisk=command(listOf("/system/bin/sh","-c","magisk -V")).trim().toInt()
        require(magisk==31100 && modules.containsAll(run.modulesBaseline))
        require(command(listOf("/system/bin/settings","get","global","ota_disable_automatic_update")).trim()=="1")
        val battery=command(listOf("/system/bin/dumpsys","battery")).lineSequence().firstOrNull {it.trim().startsWith("level:")}
            ?.substringAfter(':')?.trim()?.toIntOrNull()?:-1
        require(battery>=50)
        val type=run.stockPartitionType
        require(type in setOf("boot","init_boot"))
        val partitions=listOf(type+"_a",type+"_b").onEach {require(blockBytes(block(it)) in 4096..BootImageStreams.MAX_IMAGE_BYTES)}
        require(Os.stat(block(partitions[0]).path).st_rdev!=Os.stat(block(partitions[1]).path).st_rdev)
        val d=DeviceState(manufacturer=p("ro.product.manufacturer"),model=model,device=p("ro.product.device"),channel=channels.single(),
            androidVersion=p("ro.build.version.release"),build=p("ro.build.id"),fingerprint=p("ro.build.fingerprint"),
            vendorFingerprint=p("ro.vendor.build.fingerprint"),securityPatch=p("ro.build.version.security_patch"),slot=slot,
            rootGranted=true,rootChecked=true,magiskCode=magisk,magiskVersion="31.1",virtualAb=virtualAb,snapshot=snapshot,
            engine=if(pendingOta)EngineState.UPDATED_NEED_REBOOT else EngineState.IDLE,batteryPercent=battery,
            freeBytes=StatFs(FILES.path).availableBytes,bootId=File("/proc/sys/kernel/random/boot_id").readText().trim(),
            partitions=partitions,modules=modules,moduleInventoryKnown=true,capturedAtMillis=start,
            bootSuccessful=true,bootloaderUnlocked=true,verifiedBootState="orange",securityEvidenceSource="KERNEL_BOOTCONFIG",
            sdkInt=Build.VERSION.SDK_INT,abUpdate=true,partitionInventoryKnown=true)
        require(DeviceCapabilities.knownRestoreRuntime(d) && d.manufacturer.equals("motorola",true) && d.model==run.sourceModel &&
            d.device==run.sourceDevice && d.channel==run.sourceChannel && d.build==run.sourceBuild &&
            d.fingerprint==run.sourceFingerprint && d.bootId==run.sourceBootId && d.slot==run.sourceSlot)
        return d
    }
    fun bootControl():BootControlMetadata=RandomAccessFile(block("misc"),"r").use {f->
        f.seek(2048);ByteArray(32).also {f.readFully(it)}
    }.let(ReadOnlyMetadata::parseBootControl)?:error("BOOT_METADATA_UNKNOWN")
    fun requireNextSlot(run:UpdateRun) {
        val c=bootControl();require(c.slotCount==2)
        val source=c.slots[if(run.sourceSlot=="_a")0 else 1]
        val target=c.slots[if(run.targetSlot=="_a")0 else 1]
        require(source.markedSuccessful && !source.verityCorrupted && target.priority>source.priority &&
            target.priority>0 && target.triesRemaining>0 && !target.verityCorrupted)
    }
    fun engine():IUpdateEngine {
        require(IUpdateEngine::class.java.classLoader==Build::class.java.classLoader &&
            IUpdateEngineCallback::class.java.classLoader==Build::class.java.classLoader)
        val binder=Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java)
            .invoke(null,"android.os.UpdateEngineService") as? IBinder ?:error("ENGINE_UNAVAILABLE")
        require(binder.interfaceDescriptor=="android.os.IUpdateEngine")
        return IUpdateEngine.Stub.asInterface(binder)
    }
    fun engineStatus():Int {
        val latch=CountDownLatch(1);var status=-1;var error=false
        val callback=object:IUpdateEngineCallback.Stub(){
            @Synchronized override fun onStatusUpdate(code:Int,progress:Float){if(!progress.isFinite() || progress !in 0f..1f)error=true;status=code;latch.countDown()}
            @Synchronized override fun onPayloadApplicationComplete(code:Int){if(code!=0)error=true;latch.countDown()}
        }
        val engine=engine();var bound=false
        val received=try{bound=engine.bind(callback);bound && latch.await(3,TimeUnit.SECONDS)}finally{if(bound)require(engine.unbind(callback))}
        return synchronized(callback){if(received && !error)status else -1}
    }
    fun block(name:String):File {
        require(name in setOf("misc","boot_a","boot_b","init_boot_a","init_boot_b"))
        val f=File("/dev/block/by-name",name).canonicalFile
        require(f.path.startsWith("/dev/block/") && OsConstants.S_ISBLK(Os.lstat(f.path).st_mode))
        return f
    }
    fun blockBytes(file:File):Long=command(listOf("/system/bin/blockdev","--getsize64",file.path)).trim().toLong()
    fun digest(file:File,expectedBytes:Long,block:Boolean=false):String {
        require(expectedBytes in 1..FirmwareValidator.MAX_PACKAGE_BYTES)
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try{
            val stat=Os.fstat(fd)
            require(if(block)OsConstants.S_ISBLK(stat.st_mode) && blockBytes(file)==expectedBytes else OsConstants.S_ISREG(stat.st_mode) && stat.st_size==expectedBytes)
            val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(65_536);var remaining=expectedBytes
            while(remaining>0){val n=Os.read(fd,b,0,minOf(remaining,b.size.toLong()).toInt());require(n>0);md.update(b,0,n);remaining-=n}
            require(Os.read(fd,b,0,1)==0)
            val after=Os.fstat(fd);require(after.st_size==stat.st_size && after.st_mtime==stat.st_mtime && after.st_ctime==stat.st_ctime)
            val named=Os.lstat(file.path);require(named.st_dev==stat.st_dev && named.st_ino==stat.st_ino)
            return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }finally{Os.close(fd)}
    }
    fun digestDescriptor(fd:FileDescriptor,expectedBytes:Long,owner:Int,privateFile:Boolean=true):String {
        val before=Os.fstat(fd)
        require(OsConstants.S_ISREG(before.st_mode) && before.st_uid==owner && before.st_size==expectedBytes &&
            (if(privateFile)before.st_mode and 0x3f==0 else before.st_mode and 0xdb==0))
        Os.lseek(fd,0,OsConstants.SEEK_SET)
        val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65_536);var remaining=expectedBytes
        while(remaining>0){val n=Os.read(fd,buffer,0,minOf(buffer.size.toLong(),remaining).toInt());require(n>0);md.update(buffer,0,n);remaining-=n}
        require(Os.read(fd,buffer,0,1)==0)
        val after=Os.fstat(fd);require(after.st_dev==before.st_dev && after.st_ino==before.st_ino &&
            after.st_size==before.st_size && after.st_mtime==before.st_mtime && after.st_ctime==before.st_ctime)
        Os.lseek(fd,0,OsConstants.SEEK_SET)
        return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun privateFile(file:File,owner:Int,maxBytes:Long):File {
        require(file.canonicalPath==file.path)
        val s=Os.lstat(file.path)
        require(OsConstants.S_ISREG(s.st_mode) && s.st_uid==owner && s.st_mode and 0x3f==0 && s.st_size in 1..maxBytes)
        return file
    }
    fun command(args:List<String>,timeoutSeconds:Long=3,workingDir:File?=null,environment:Map<String,String> = emptyMap()):String {
        val builder=ProcessBuilder(args).redirectErrorStream(false);if(workingDir!=null)builder.directory(workingDir)
        builder.environment().putAll(environment)
        val process=builder.start();val output=ByteArrayOutputStream();var tooLong=false
        val reader=thread(isDaemon=true){process.inputStream.use {input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;synchronized(output){if(output.size()+n<=65_536)output.write(b,0,n)else tooLong=true}}}}
        val errors=thread(isDaemon=true){process.errorStream.use {input->val b=ByteArray(4096);while(input.read(b)>=0){}}}
        val complete=process.waitFor(timeoutSeconds,TimeUnit.SECONDS);if(!complete)process.destroyForcibly()
        reader.join(500);errors.join(500)
        require(complete && !reader.isAlive && !errors.isAlive && !tooLong && process.exitValue()==0)
        return synchronized(output){output.toString("UTF-8")}
    }
}
