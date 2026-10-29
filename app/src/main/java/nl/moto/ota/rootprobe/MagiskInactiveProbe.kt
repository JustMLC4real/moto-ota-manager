package nl.moto.ota.rootprobe

import android.os.Build
import android.os.RecoverySystem
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.update.MagiskInactiveProtocol
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Uses the exact installed official Magisk 31.1 APK, never a guessed manager CLI.
 * Only its file patcher is sourced. No direct_install, post_ota, bootctl, app-data
 * reset, vbmeta key changes, slot writes, settings changes or reboot are invoked.
 */
object MagiskInactiveProbe {
    @JvmStatic fun main(args:Array<String>) {
        try {
            require(args.size==2 && args[0] in setOf("prepare","patch","install") && args[1].matches(Regex("[a-f0-9-]{36}")))
            val owner=RootUpdateFacts.owner();val run=RootUpdateFacts.journal(owner)
            require(run.id==args[1])
            val directory=directory(run,owner)
            val result=when(args[0]) {
                "prepare"->prepare(run,directory,owner)
                "patch"->patch(run,directory,owner)
                else->install(run,directory,owner)
            }
            println(result);System.out.flush()
        }catch(error:Throwable){
            println(JSONObject().put("error",(error as? UpdateSafetyException)?.code?:"MAGISK_INACTIVE_GUARD_REJECTED"));System.out.flush()
        }
        kotlin.system.exitProcess(0)
    }

    private fun prepare(run:UpdateRun,directory:File,owner:Int):JSONObject {
        require(run.stage==UpdateStage.STOCK_PREPARATION && !File(directory,"preflight.json").exists())
        RootUpdateFacts.observe(run,false)
        val request=json(privateFile(File(directory,"request.json"),owner,16_384))
        require(request.getInt("schema")==1 && request.getString("runId")==run.id && request.getString("packageSha256")==run.packageSha256 &&
            request.getString("partitionType")==run.stockPartitionType)
        val name=request.getString("packageName").also {require(it.matches(Regex("[a-f0-9-]{36}[.]zip")))}
        val zip=privateFile(File(RootUpdateFacts.FILES,"verified-packages/$name"),owner,FirmwareValidator.MAX_PACKAGE_BYTES)
        require(RootUpdateFacts.digest(zip,zip.length())==run.packageSha256)
        RecoverySystem.verifyPackage(zip,null,null)
        val target=PayloadManifest.inspect(zip).targetImages.singleOrNull {it.name==run.stockPartitionType}
            ?: throw UpdateSafetyException("MAGISK_SIGNED_TARGET_IMAGE_MISSING")
        require(target.bytes==request.getLong("targetBytes") && target.sha256==request.getString("targetSha256") &&
            target.bytes in 4096..BootImageStreams.MAX_IMAGE_BYTES)
        val apk=verifiedApk(directory,owner)
        // Reject every already knowable patch/configuration limitation before
        // the source is restored or the OTA is dispatched.
        val config=runtimeConfig()
        patchEnvironment(config)
        val work=childDirectory(directory,"work",owner,true)
        extractTools(apk,work,owner)
        childDirectory(work,"tmp",owner,true)
        val preflight=JSONObject().put("schema",1).put("runId",run.id).put("packageSha256",run.packageSha256)
            .put("sourceBootId",run.sourceBootId).put("partitionType",run.stockPartitionType).put("targetBytes",target.bytes)
            .put("targetSha256",target.sha256).put("toolApkSha256",MagiskInactiveProtocol.APK_SHA256)
            .put("runtimeConfigSha256",configDigest(config))
        preflight.put("macSha256",preflightMac(preflight,key(owner)))
        writeOwned(File(directory,"preflight.json"),preflight.toString().toByteArray(),owner)
        return JSONObject().put("schema",1).put("prepared",true)
    }

    private fun patch(run:UpdateRun,directory:File,owner:Int):JSONObject {
        require(run.otaAppliedObserved && run.stage in setOf(UpdateStage.MAGISK_REQUIRED,UpdateStage.MAGISK_REPORTED_UNVERIFIED))
        require(!File(directory,"prepared.json").exists() && !File(directory,"receipt.json").exists())
        val d=RootUpdateFacts.observe(run,true)
        RootUpdateFacts.requireNextSlot(run)
        val preflight=preflight(run,directory,owner)
        val work=childDirectory(directory,"work",owner)
        verifyTools(verifiedApk(directory,owner),work,owner)
        val bytes=preflight.getLong("targetBytes")
        require(d.freeBytes>=bytes*5+512L*1024*1024)
        val target=RootUpdateFacts.block(run.stockPartitionType+run.targetSlot)
        require(RootUpdateFacts.blockBytes(target)==bytes)
        val stock=File(work,"target-stock.img")
        val measurement=captureTarget(target,stock,bytes,owner)
        require(measurement.bytes==bytes && measurement.partitionType==run.stockPartitionType && measurement.sha256==preflight.getString("targetSha256"))
        val config=runtimeConfig()
        require(configDigest(config)==preflight.getString("runtimeConfigSha256"))
        val environment=patchEnvironment(config)
        // The verified util script assigns /dev/tmp when sourced. This fixed wrapper
        // resets it before the patcher, so its abort path can remove only our tmp.
        val wrapper="""#!/system/bin/sh
export BOOTMODE=true
. ./util_functions.sh
export TMPDIR="${'$'}PWD/tmp" MAGISKBIN="${'$'}PWD" SOURCEDMODE=true
. ./boot_patch.sh ./target-stock.img
"""
        writeExclusive(File(work,"patch-wrapper.sh"),wrapper.replace("\r\n","\n").toByteArray(),owner,0x1c0)
        command(listOf(File(work,"busybox").path,"ash","./patch-wrapper.sh"),work,environment,240).requireSuccess()
        resetPermissions(work,owner)
        val generated=privateFile(File(work,"new-boot.img"),owner,bytes)
        val generatedMeasurement=generated.inputStream().use {BootImageStreams.measure(it,false,bytes)}
        require(generatedMeasurement.partitionType==run.stockPartitionType && generatedMeasurement.bytes in 4096..bytes)
        val patched=File(work,"patched-full.img")
        // Official Magisk flash_image zero-fills the remainder of a block partition.
        // Build those exact bounded bytes as a regular file and hash the complete image.
        copyPadded(generated,patched,bytes,owner)
        val patchedHash=RootUpdateFacts.digest(patched,bytes)
        val embedded=embeddedSha1(patched,work,owner)
        require(embedded==measurement.sha1 && patchedHash!=measurement.sha256)
        Os.chmod(patched.path,0x100)
        preserveStockBackup(stock,measurement,owner)
        RootUpdateFacts.observe(run,true);RootUpdateFacts.requireNextSlot(run)
        require(RootUpdateFacts.digest(target,bytes,true)==measurement.sha256)
        val receipt=JSONObject().put("schema",1).put("checkpoint","PREPARED").put("runId",run.id).put("packageSha256",run.packageSha256)
            .put("sourceBootId",run.sourceBootId).put("targetSlot",run.targetSlot).put("partitionType",run.stockPartitionType)
            .put("targetStockBytes",bytes).put("targetStockSha256",measurement.sha256).put("targetStockSha1",measurement.sha1)
            .put("patchedImageSha256",patchedHash).put("embeddedStockSha1",embedded).put("toolApkSha256",MagiskInactiveProtocol.APK_SHA256)
            .put("capturedAtMillis",System.currentTimeMillis())
        MagiskInactiveProtocol.sign(receipt,key(owner))
        writeOwned(File(directory,"prepared.json"),receipt.toString().toByteArray(),owner)
        return JSONObject().put("schema",1).put("targetPatchedInFile",true)
    }

    private fun install(run:UpdateRun,directory:File,owner:Int):JSONObject {
        require(run.otaAppliedObserved && run.stage in setOf(UpdateStage.MAGISK_REQUIRED,UpdateStage.MAGISK_REPORTED_UNVERIFIED))
        require(!File(directory,"receipt.json").exists() && !File(directory,"write-started.json").exists())
        val lockFile=File(directory,"writer.lock")
        if(!lockFile.exists())writeExclusive(lockFile,byteArrayOf(1),owner)
        val lock=RandomAccessFile(privateFile(lockFile,owner,1),"rw")
        lock.use {raf->raf.channel.use {channel->channel.tryLock()?.use {
            val receipt=MagiskInactiveProtocol.verify(json(privateFile(File(directory,"prepared.json"),owner,16_384)),key(owner),run,false)
            val targetBytes=receipt.getLong("targetStockBytes");val stockHash=receipt.getString("targetStockSha256")
            val work=childDirectory(directory,"work",owner)
            verifyTools(verifiedApk(directory,owner),work,owner)
            val patched=privateFile(File(work,"patched-full.img"),owner,targetBytes)
            require(RootUpdateFacts.digest(patched,targetBytes)==receipt.getString("patchedImageSha256"))
            require(RootUpdateFacts.digest(privateFile(File(work,"target-stock.img"),owner,targetBytes),targetBytes)==stockHash)
            val d=RootUpdateFacts.observe(run,true);RootUpdateFacts.requireNextSlot(run)
            val authority=RootRunAuthority.verify(owner,run.id,File(directory,"operation.properties"),RunAction.PATCH_INACTIVE_MAGISK,d)
            require(authority.authorization.packageSha256==run.packageSha256)
            val source=RootUpdateFacts.block(run.stockPartitionType+run.sourceSlot)
            require(RootUpdateFacts.digest(source,RootUpdateFacts.blockBytes(source),true)==run.stockSha256)
            val target=RootUpdateFacts.block(run.stockPartitionType+run.targetSlot)
            require(RootUpdateFacts.blockBytes(target)==targetBytes && RootUpdateFacts.digest(target,targetBytes,true)==stockHash)
            // Re-open the canonical block once, verify its complete old bytes on this
            // same descriptor, then retain that descriptor through write and readback.
            val fd=Os.open(target.path,OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            try {
                val imageFd=Os.open(patched.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
                FileInputStream(imageFd).use {image->
                val imageStat=Os.fstat(imageFd)
                require(OsConstants.S_ISREG(imageStat.st_mode) && imageStat.st_uid==owner && imageStat.st_size==targetBytes)
                require(digestDescriptor(imageFd,targetBytes)==receipt.getString("patchedImageSha256"))
                Os.lseek(imageFd,0,OsConstants.SEEK_SET)
                val stat=Os.fstat(fd);val targetStat=Os.lstat(target.path);val sourceStat=Os.lstat(source.path)
                require(OsConstants.S_ISBLK(stat.st_mode) && stat.st_dev==targetStat.st_dev && stat.st_ino==targetStat.st_ino &&
                    stat.st_rdev==targetStat.st_rdev && stat.st_rdev!=sourceStat.st_rdev)
                require(digestDescriptor(fd,targetBytes)==stockHash)
                val fresh=RootUpdateFacts.observe(run,true);RootUpdateFacts.requireNextSlot(run)
                RootRunAuthority.verify(owner,run.id,File(directory,"operation.properties"),RunAction.PATCH_INACTIVE_MAGISK,fresh)
                require(RootUpdateFacts.block(run.stockPartitionType+run.targetSlot).path==target.path)
                authority.recheck();RootRunAuthority.consume(authority,owner)
                writeOwned(File(directory,"write-started.json"),JSONObject().put("runId",run.id).put("sourceBootId",run.sourceBootId)
                    .put("targetSlot",run.targetSlot).put("expected",receipt.getString("patchedImageSha256")).toString().toByteArray(),owner)
                Os.lseek(fd,0,OsConstants.SEEK_SET)
                run {
                    val namedImage=Os.lstat(patched.path);val nowImage=Os.fstat(imageFd)
                    require(namedImage.st_dev==imageStat.st_dev && namedImage.st_ino==imageStat.st_ino &&
                        nowImage.st_size==imageStat.st_size && nowImage.st_mtime==imageStat.st_mtime && nowImage.st_ctime==imageStat.st_ctime)
                    val b=ByteArray(65_536);var written=0L
                    while(written<targetBytes){val n=image.read(b,0,minOf(b.size.toLong(),targetBytes-written).toInt());require(n>0)
                        var at=0;while(at<n){val count=Os.write(fd,b,at,n-at);require(count>0);at+=count};written+=n}
                    require(image.read()==-1)
                    val afterImage=Os.fstat(imageFd)
                    require(afterImage.st_size==imageStat.st_size && afterImage.st_mtime==imageStat.st_mtime && afterImage.st_ctime==imageStat.st_ctime)
                }
                Os.fsync(fd);Os.lseek(fd,0,OsConstants.SEEK_SET)
                require(digestDescriptor(fd,targetBytes)==receipt.getString("patchedImageSha256"))
                val named=Os.lstat(target.path);require(named.st_dev==stat.st_dev && named.st_ino==stat.st_ino && named.st_rdev==stat.st_rdev)
                }
            }catch(error:Throwable){
                if(File(directory,"write-started.json").exists())throw UpdateSafetyException("MAGISK_INACTIVE_WRITE_UNCERTAIN")
                throw error
            }finally{Os.close(fd)}
            RootUpdateFacts.requireNextSlot(run)
            receipt.put("checkpoint","INSTALLED").put("capturedAtMillis",System.currentTimeMillis())
            MagiskInactiveProtocol.sign(receipt,key(owner))
            writeOwned(File(directory,"receipt.json"),receipt.toString().toByteArray(),owner)
            return receipt
        } ?: throw UpdateSafetyException("MAGISK_INACTIVE_WRITER_BUSY")}}
    }

    /** Shared with the reboot helper: authenticated scope plus a fresh complete target readback. */
    internal fun verifiedReceipt(run:UpdateRun,owner:Int):JSONObject {
        val directory=directory(run,owner)
        val receipt=MagiskInactiveProtocol.verify(json(privateFile(File(directory,"receipt.json"),owner,16_384)),key(owner),run)
        val preflight=preflight(run,directory,owner)
        require(receipt.getString("targetStockSha256")==preflight.getString("targetSha256") && receipt.getLong("targetStockBytes")==preflight.getLong("targetBytes"))
        val target=RootUpdateFacts.block(run.stockPartitionType+run.targetSlot)
        require(RootUpdateFacts.digest(target,receipt.getLong("targetStockBytes"),true)==receipt.getString("patchedImageSha256"))
        return receipt
    }

    private fun preflight(run:UpdateRun,directory:File,owner:Int):JSONObject {
        val j=json(privateFile(File(directory,"preflight.json"),owner,16_384))
        require(j.getInt("schema")==1 && j.getString("runId")==run.id && j.getString("packageSha256")==run.packageSha256 &&
            j.getString("sourceBootId")==run.sourceBootId && j.getString("partitionType")==run.stockPartitionType &&
            j.getString("toolApkSha256")==MagiskInactiveProtocol.APK_SHA256 && j.getLong("targetBytes") in 4096..BootImageStreams.MAX_IMAGE_BYTES)
        require(MessageDigest.isEqual(j.getString("macSha256").toByteArray(),preflightMac(j,key(owner)).toByteArray()))
        return j
    }
    private fun preflightMac(j:JSONObject,key:ByteArray):String {
        val fields=listOf("runId","packageSha256","sourceBootId","partitionType","targetBytes","targetSha256","toolApkSha256","runtimeConfigSha256")
        val text=(listOf("moto-magisk-preflight-v1")+fields.map {j.getString(it)}).joinToString(""){"${it.toByteArray().size}:$it"}
        return Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(key,"HmacSHA256"));doFinal(text.toByteArray())}.joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun verifiedApk(directory:File,owner:Int):File=privateFile(File(directory,"tools.apk"),owner,MagiskInactiveProtocol.APK_BYTES).also {
        require(it.length()==MagiskInactiveProtocol.APK_BYTES && RootUpdateFacts.digest(it,it.length())==MagiskInactiveProtocol.APK_SHA256)
    }
    private fun tools():Map<String,String> {
        val abi=Build.SUPPORTED_64_BIT_ABIS.firstOrNull {it in setOf("arm64-v8a","x86_64")} ?: throw UpdateSafetyException("MAGISK_ABI_UNSUPPORTED")
        return mapOf("boot_patch.sh" to "assets/boot_patch.sh","util_functions.sh" to "assets/util_functions.sh")+
            listOf("magiskboot","magiskinit","magisk","init-ld","busybox").associateWith {"lib/$abi/lib$it.so"}
    }
    private fun extractTools(apk:File,work:File,owner:Int)=ZipFile(apk).use {zip->tools().forEach {(name,entryName)->
        val entry=requireNotNull(zip.getEntry(entryName));require(entry.size in 1..4_194_304)
        val bytes=zip.getInputStream(entry).use {it.readNBytes(4_194_305)};require(bytes.size.toLong()==entry.size)
        writeExclusive(File(work,name),bytes,owner,if(name.endsWith(".sh"))0x180 else 0x1c0)
    }}
    private fun verifyTools(apk:File,work:File,owner:Int)=ZipFile(apk).use {zip->tools().forEach {(name,entryName)->
        val entry=requireNotNull(zip.getEntry(entryName));val file=privateFile(File(work,name),owner,4_194_304)
        require(file.length()==entry.size)
        val expected=zip.getInputStream(entry).use {input->MessageDigest.getInstance("SHA-256").run {val b=ByteArray(65_536);while(true){val n=input.read(b);if(n<0)break;update(b,0,n)};digest().joinToString(""){"%02x".format(it.toInt() and 255)}}}
        require(RootUpdateFacts.digest(file,file.length())==expected)
    }}
    private fun captureTarget(target:File,file:File,bytes:Long,owner:Int):BootImageMeasurement {
        val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
        return FileOutputStream(fd).use {out->
            val input=Os.open(target.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            FileInputStream(input).use {stream->require(OsConstants.S_ISBLK(Os.fstat(input).st_mode));BootImageStreams.measure(stream,false,bytes,out)}
                .also {out.fd.sync();Os.fchown(fd,owner,owner)}
        }
    }
    private fun copyPadded(source:File,target:File,bytes:Long,owner:Int) {
        val fd=Os.open(target.path,OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
        FileOutputStream(fd).use {out->source.inputStream().use {it.copyTo(out,65_536)};Os.ftruncate(fd,bytes);Os.fchown(fd,owner,owner);out.fd.sync()}
    }
    private fun embeddedSha1(image:File,work:File,owner:Int):String {
        val verify=childDirectory(work,"verify",owner,true);val tool=File(work,"magiskboot").path
        command(listOf(tool,"unpack",image.path),verify).requireSuccess()
        val cpio=File(verify,"ramdisk.cpio");require(cpio.isFile && cpio.length() in 1..BootImageStreams.MAX_IMAGE_BYTES)
        require(command(listOf(tool,"cpio",cpio.path,"test"),verify).exitCode==1)
        command(listOf(tool,"cpio",cpio.path,"exists overlay.d/sbin/magisk.xz"),verify).requireSuccess()
        command(listOf(tool,"cpio",cpio.path,"extract .backup/.magisk embedded.config"),verify).requireSuccess()
        resetPermissions(verify,owner)
        val bytes=privateFile(File(verify,"embedded.config"),owner,MagiskRuntimeConfig.MAX_BYTES.toLong()).readBytes()
        return MagiskRuntimeConfig.stockSha1(bytes) ?: throw UpdateSafetyException("MAGISK_PATCH_PROOF_INVALID")
    }
    private fun preserveStockBackup(stock:File,measurement:BootImageMeasurement,owner:Int) {
        require(measurement.sha1.matches(Regex("[a-f0-9]{40}")))
        val dir=File("/data/magisk_backup_${measurement.sha1}")
        if(!dir.exists())Os.mkdir(dir.path,0x1c0)
        val stat=Os.lstat(dir.path);require(dir.canonicalPath==dir.path && OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid==0 && stat.st_mode and 0x12==0)
        val final=File(dir,"boot.img.gz")
        if(!final.exists()) {
            val tmp=File(dir,"moto-ota-${java.util.UUID.randomUUID()}.pending")
            val fd=Os.open(tmp.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
            FileOutputStream(fd).use {out->GZIPOutputStream(out,65_536).use {gzip->stock.inputStream().use {it.copyTo(gzip,65_536)};gzip.finish();out.fd.sync()}}
            // link creates the final name exclusively; an existing backup is never replaced.
            Os.link(tmp.path,final.path);Os.remove(tmp.path);sync(dir)
        }
        val before=Os.lstat(final.path)
        require(final.canonicalPath==final.path && OsConstants.S_ISREG(before.st_mode) && before.st_uid==0 && before.st_size in 1..BootImageStreams.MAX_PACKED_BYTES)
        val measured=final.inputStream().use {BootImageStreams.measure(it,true,measurement.bytes)}
        require(measured.bytes==measurement.bytes && measured.sha256==measurement.sha256 && measured.sha1==measurement.sha1 && measured.partitionType==measurement.partitionType)
        require(Os.lstat(final.path).let {it.st_dev==before.st_dev && it.st_ino==before.st_ino && it.st_size==before.st_size && it.st_mtime==before.st_mtime})
    }
    private fun runtimeConfig():Map<String,String> {
        val path=MagiskRuntimeConfig.configPath(RootUpdateFacts.command(listOf("/system/bin/sh","-c","magisk --path")))
            ?: throw UpdateSafetyException("MAGISK_RUNTIME_PATH_UNSUPPORTED")
        val file=File(path);val stat=Os.lstat(path)
        require(file.canonicalPath==path && OsConstants.S_ISREG(stat.st_mode) && stat.st_uid==0 && stat.st_size in 1..MagiskRuntimeConfig.MAX_BYTES.toLong())
        val fd=Os.open(path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        val bytes=FileInputStream(fd).use {input->
            fun same(s:android.system.StructStat)=s.st_dev==stat.st_dev && s.st_ino==stat.st_ino && s.st_size==stat.st_size && s.st_mtime==stat.st_mtime && s.st_ctime==stat.st_ctime
            require(same(Os.fstat(fd)))
            input.readNBytes(MagiskRuntimeConfig.MAX_BYTES+1).also {require(same(Os.fstat(fd)) && same(Os.lstat(path)))}
        }
        require(MagiskRuntimeConfig.stockSha1(bytes)!=null && bytes.size.toLong()==stat.st_size)
        return bytes.toString(Charsets.US_ASCII).lineSequence().filter(String::isNotBlank).associate {val p=it.removeSuffix("\r").split('=',limit=2);p[0] to p[1]}
    }
    private fun configDigest(config:Map<String,String>):String=MessageDigest.getInstance("SHA-256").digest(
        config.toSortedMap().entries.joinToString("\n"){"${it.key}=${it.value}"}.toByteArray(Charsets.US_ASCII)
    ).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun patchEnvironment(config:Map<String,String>):Map<String,String> {
        if(config["RECOVERYMODE"]!="false" || config["VENDORBOOT"]!="false" ||
            config["KEEPVERITY"] !in setOf("true","false") || config["KEEPFORCEENCRYPT"] !in setOf("true","false"))
            throw UpdateSafetyException("MAGISK_PATCH_CONFIGURATION_UNSUPPORTED")
        val preinit=config["PREINITDEVICE"]?.takeIf {it.matches(Regex("[A-Za-z0-9_.-]{1,128}"))}
            ?: throw UpdateSafetyException("MAGISK_PREINIT_DEVICE_UNPROVEN")
        val preinitBlock=File("/dev/block/by-name",preinit).canonicalFile
        require(preinitBlock.path.startsWith("/dev/block/") && OsConstants.S_ISBLK(Os.lstat(preinitBlock.path).st_mode))
        if(File("/proc/mounts").readText().lineSequence().any {it.trim().split(' ').take(2)==listOf("/dev/root","/")})
            throw UpdateSafetyException("MAGISK_LEGACY_SAR_UNSUPPORTED")
        return mapOf("BOOTMODE" to "true","KEEPVERITY" to requireNotNull(config["KEEPVERITY"]),
            "KEEPFORCEENCRYPT" to requireNotNull(config["KEEPFORCEENCRYPT"]),"RECOVERYMODE" to "false","VENDORBOOT" to "false",
            "LEGACYSAR" to "false","PATCHVBMETAFLAG" to "false","PREINITDEVICE" to preinit,"ASH_STANDALONE" to "1","PATH" to "/system/bin")
    }
    private fun digestDescriptor(fd:FileDescriptor,bytes:Long):String {
        val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(65_536);var remaining=bytes
        while(remaining>0){val n=Os.read(fd,b,0,minOf(b.size.toLong(),remaining).toInt());require(n>0);md.update(b,0,n);remaining-=n}
        require(Os.read(fd,b,0,1)==0)
        return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun directory(run:UpdateRun,owner:Int):File {
        var parent=RootUpdateFacts.FILES
        for(name in listOf("update","magisk",run.id))parent=childDirectory(parent,name,owner)
        return parent
    }
    private fun childDirectory(parent:File,name:String,owner:Int,create:Boolean=false):File {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val file=File(parent,name)
        if(create){Os.mkdir(file.path,0x1c0);Os.chown(file.path,owner,owner)}
        val s=Os.lstat(file.path)
        require(file.canonicalPath==file.path && OsConstants.S_ISDIR(s.st_mode) && s.st_uid==owner && s.st_mode and 0x12==0)
        return file
    }
    private fun privateFile(file:File,owner:Int,max:Long)=RootUpdateFacts.privateFile(file,owner,max)
    private fun key(owner:Int)=privateFile(File(RootUpdateFacts.FILES,"update/authority.key"),owner,32).readBytes().also {require(it.size==32)}
    private fun json(file:File)=JSONObject(file.inputStream().use {it.readNBytes(16_385).also {bytes->require(bytes.size<=16_384)}.toString(Charsets.UTF_8)})
    private fun resetPermissions(directory:File,owner:Int) {
        require(directory.canonicalPath==directory.path)
        directory.listFiles()?.forEach {file->
            val stat=Os.lstat(file.path);require(file.canonicalPath==file.path && !OsConstants.S_ISLNK(stat.st_mode))
            if(OsConstants.S_ISDIR(stat.st_mode)){resetPermissions(file,owner);Os.chmod(file.path,0x1c0)}
            else {require(OsConstants.S_ISREG(stat.st_mode));Os.chmod(file.path,if(file.name in tools().keys && !file.name.endsWith(".sh"))0x1c0 else 0x180)}
            Os.chown(file.path,owner,owner)
        } ?: error("PRIVATE_WORK_DIRECTORY_UNREADABLE")
        Os.chown(directory.path,owner,owner);Os.chmod(directory.path,0x1c0)
    }
    private fun writeExclusive(file:File,bytes:ByteArray,owner:Int,mode:Int=0x180) {
        val fd=Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,mode)
        FileOutputStream(fd).use {out->out.write(bytes);Os.fchown(fd,owner,owner);out.fd.sync()};sync(requireNotNull(file.parentFile))
    }
    private fun writeOwned(file:File,bytes:ByteArray,owner:Int) {
        val tmp=File(file.parentFile,file.name+".pending")
        writeExclusive(tmp,bytes,owner);Os.rename(tmp.path,file.path);sync(requireNotNull(file.parentFile))
    }
    private fun sync(file:File){val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0);try{Os.fsync(fd)}finally{Os.close(fd)}}
    private data class CommandResult(val exitCode:Int,val output:String){fun requireSuccess(){require(exitCode==0)}}
    private fun command(args:List<String>,cwd:File,environment:Map<String,String> = emptyMap(),timeout:Long=20):CommandResult {
        val builder=ProcessBuilder(args).directory(cwd);builder.environment().clear();builder.environment().putAll(mapOf("PATH" to "/system/bin")+environment)
        val process=builder.start();val output=ByteArrayOutputStream();var oversized=false
        val reader=thread(isDaemon=true){process.inputStream.use {input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;synchronized(output){if(output.size()+n<=32_768)output.write(b,0,n)else oversized=true}}}}
        val errors=thread(isDaemon=true){process.errorStream.use {input->val b=ByteArray(4096);while(input.read(b)>=0){}}}
        val done=process.waitFor(timeout,TimeUnit.SECONDS);if(!done)process.destroyForcibly();reader.join(500);errors.join(500)
        require(done && !reader.isAlive && !errors.isAlive && !oversized)
        return CommandResult(process.exitValue(),synchronized(output){output.toString("UTF-8")})
    }
}
