package nl.moto.ota.platform.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.RecoverySystem
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exact official Magisk 31.1 tools, file patch first, separately consumed inactive write. */
class MagiskInactiveController(private val context:Context) {
    /** Must pass before restoring the source partition; unknown/hidden/replaced manager is a safe fallback. */
    suspend fun prepare(run:UpdateRun,report:FirmwareReport,pinnedZip:File):Unit=withContext(Dispatchers.IO) { synchronized(LOCK) {
        require(!run.simulated && run.stage==UpdateStage.STOCK_PREPARATION && run.packageVerified && report.signatureVerified &&
            run.packageSha256==report.sha256 && report.payloadHashValid && report.structureValid)
        val pins=File(context.filesDir,"verified-packages").canonicalFile
        require(pinnedZip.canonicalFile.parentFile==pins && pinnedZip.canonicalFile.name==pinnedZip.name && pinnedZip.name.matches(Regex("[a-f0-9-]{36}[.]zip")))
        requirePrivateFile(pinnedZip,FirmwareValidator.MAX_PACKAGE_BYTES)
        require(digest(pinnedZip)==run.packageSha256)
        RecoverySystem.verifyPackage(pinnedZip,null,null)
        val target=PayloadManifest.inspect(pinnedZip).targetImages.singleOrNull {it.name==run.stockPartitionType}
            ?: throw UpdateSafetyException("MAGISK_SIGNED_TARGET_IMAGE_MISSING")
        require(target.bytes in 4096..BootImageStreams.MAX_IMAGE_BYTES && target.sha256.matches(Regex("[a-f0-9]{64}")))
        val info=try {context.packageManager.getPackageInfo("com.topjohnwu.magisk",PackageManager.PackageInfoFlags.of(0))}
        catch(_:Exception){throw UpdateSafetyException("MAGISK_OFFICIAL_MANAGER_UNAVAILABLE")}
        requireSafety(info.longVersionCode==31100L,"MAGISK_OFFICIAL_MANAGER_VERSION_UNSUPPORTED")
        val source=File(requireNotNull(info.applicationInfo).sourceDir)
        requireSafety(source.isFile && source.length()==MagiskInactiveProtocol.APK_BYTES && digest(source)==MagiskInactiveProtocol.APK_SHA256,
            "MAGISK_OFFICIAL_APK_HASH_MISMATCH")
        val directory=directory(run.id,create=true)
        val apk=File(directory,"tools.apk")
        requireSafety(!apk.exists(),"MAGISK_PREPARATION_ALREADY_EXISTS")
        val fd=Os.open(apk.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
        FileOutputStream(fd).use {output->source.inputStream().use {it.copyTo(output,65_536)};output.fd.sync()}
        requireSafety(apk.length()==MagiskInactiveProtocol.APK_BYTES && digest(apk)==MagiskInactiveProtocol.APK_SHA256,"MAGISK_OFFICIAL_APK_HASH_MISMATCH")
        RestoreRootProtocol.writePrivate(File(directory,"request.json"),JSONObject().put("schema",1).put("runId",run.id)
            .put("packageName",pinnedZip.name).put("packageSha256",run.packageSha256).put("targetBytes",target.bytes)
            .put("targetSha256",target.sha256).put("partitionType",run.stockPartitionType).toString().toByteArray())
        invoke("prepare",run.id)
        Unit
    } }

    /** The completed OTA target is only read. Patching and the new stock backup touch regular files. */
    suspend fun prepareTarget(run:UpdateRun):Unit=withContext(Dispatchers.IO) { synchronized(LOCK) {
        requireSafety(run.otaAppliedObserved && run.stage in setOf(UpdateStage.MAGISK_REQUIRED,UpdateStage.MAGISK_REPORTED_UNVERIFIED),"MAGISK_OTA_NOT_READY")
        directory(run.id);invoke("patch",run.id);Unit
    } }

    /** This is the only partition-writing operation; it requires a fresh one-use full-run action. */
    suspend fun install(run:UpdateRun,operation:AuthorizedRunOperation):MagiskInstallResult=withContext(Dispatchers.IO) { synchronized(LOCK) {
        requireSafety(operation.runId==run.id && operation.action==RunAction.PATCH_INACTIVE_MAGISK && operation.sourceBootId==run.sourceBootId &&
            operation.packageSha256==run.packageSha256,"RUN_ACTION_AUTHORIZATION_MISMATCH")
        val directory=directory(run.id)
        RestoreRootProtocol.writePrivate(File(directory,"operation.properties"),RunAuthorizationCodec.encodeOperation(operation).toByteArray())
        val response=invoke("install",run.id)
        val keyFile=File(context.filesDir,"update/authority.key").also {requirePrivateFile(it,32)}
        val key=keyFile.readBytes().also {require(it.size==32)}
        val receipt=MagiskInactiveProtocol.verify(response,key,run)
        requireSafety(System.currentTimeMillis()-receipt.getLong("capturedAtMillis") in 0..30_000,"MAGISK_READBACK_STALE")
        val stock=File(directory,"work/target-stock.img").also {requirePrivateFile(it,BootImageStreams.MAX_IMAGE_BYTES)}
        val measured=stock.inputStream().use {BootImageStreams.measure(it,false,receipt.getLong("targetStockBytes"))}
        requireSafety(measured.bytes==receipt.getLong("targetStockBytes") && measured.sha256==receipt.getString("targetStockSha256") &&
            measured.sha1==receipt.getString("targetStockSha1") && measured.partitionType==run.stockPartitionType,"MAGISK_TARGET_STOCK_MISMATCH")
        MagiskInactiveProtocol.result(receipt)
    } }

    private fun directory(runId:String,create:Boolean=false):File {
        requireSafety(runId.matches(Regex("[a-f0-9-]{36}")),"MAGISK_RUN_INVALID")
        var parent=context.filesDir.canonicalFile
        for(name in listOf("update","magisk",runId)) {
            parent=File(parent,name)
            if(create && !parent.exists())Os.mkdir(parent.path,0x1c0)
            val s=Os.lstat(parent.path)
            requireSafety(parent.path==parent.canonicalPath && OsConstants.S_ISDIR(s.st_mode) && s.st_uid==android.os.Process.myUid() &&
                s.st_mode and 0x12==0,"MAGISK_PRIVATE_DIRECTORY_INVALID")
        }
        return parent
    }
    private fun requirePrivateFile(file:File,maximum:Long) {
        val s=Os.lstat(file.path)
        requireSafety(file.path==file.canonicalPath && OsConstants.S_ISREG(s.st_mode) && s.st_uid==android.os.Process.myUid() &&
            s.st_mode and 0x12==0 && s.st_size in 1..maximum,"MAGISK_PRIVATE_FILE_INVALID")
    }
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").run {file.inputStream().use {input->val b=ByteArray(65_536);while(true){val n=input.read(b);if(n<0)break;update(b,0,n)}};digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
    private fun invoke(operation:String,runId:String):JSONObject {
        require(operation in setOf("prepare","patch","install") && runId.matches(Regex("[a-f0-9-]{36}")))
        val path=context.applicationInfo.sourceDir
        require(path.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk")))
        val process=ProcessBuilder("su","--target","0","-c","CLASSPATH='$path' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.MagiskInactiveProbe $operation $runId").start()
        val out=ByteArrayOutputStream();var truncated=false
        val reader=thread(isDaemon=true){process.inputStream.use {input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;synchronized(out){if(out.size()+n<=32_768)out.write(b,0,n)else truncated=true}}}}
        val err=thread(isDaemon=true){process.errorStream.use {input->val b=ByteArray(4096);while(input.read(b)>=0){}}}
        val complete=process.waitFor(if(operation=="install")300 else 600,TimeUnit.SECONDS)
        // No retry on timeout. A partial write requires independent recovery observations.
        // Never terminate a possible block writer because an app-side wait expired.
        // Its durable marker prevents replay; the journal must require observation.
        if(!complete && operation!="install")process.destroyForcibly()
        reader.join(1000);err.join(1000)
        requireSafety(complete && !reader.isAlive && !err.isAlive && !truncated && process.exitValue()==0,"MAGISK_HELPER_INTERRUPTED")
        return JSONObject(synchronized(out){out.toString("UTF-8")}).also {if(it.optString("error").isNotBlank())throw UpdateSafetyException(it.getString("error"))}
    }
    private fun requireSafety(ok:Boolean,code:String){if(!ok)throw UpdateSafetyException(code)}
    companion object {private val LOCK=Any()}
}
