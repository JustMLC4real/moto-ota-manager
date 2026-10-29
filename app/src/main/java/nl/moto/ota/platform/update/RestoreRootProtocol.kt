package nl.moto.ota.platform.update

import android.os.SystemClock
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Fixed root entry point. No caller-supplied shell commands or partition paths. */
internal class RestoreRootTransport(private val apkPath:String) {
    init { require(apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk"))) }
    fun invoke(operation:String,transactionId:String):JSONObject {
        require(operation in setOf("observe","execute","recover","verify-unchanged"))
        require(transactionId.matches(Regex("[a-f0-9-]{36}")))
        val command="CLASSPATH='$apkPath' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.StockRestoreProbe $operation $transactionId"
        val process=ProcessBuilder("su","--target","0","-c",command).redirectErrorStream(false).start()
        val output=java.io.ByteArrayOutputStream()
        var oversized=false
        val reader=thread(isDaemon=true) { process.inputStream.use { input ->
            val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;synchronized(output){if(output.size()+n<=65_536)output.write(buffer,0,n)else oversized=true}}
        } }
        val err=thread(isDaemon=true) { process.errorStream.use { input -> val buffer=ByteArray(4096);while(input.read(buffer)>=0){} } }
        // Timeout is a failure requiring observation, never permission to retry a write.
        val complete=process.waitFor(60,TimeUnit.SECONDS)
        if(!complete)process.destroyForcibly()
        reader.join(1000);err.join(1000)
        if(!complete || reader.isAlive || oversized || process.exitValue()!=0)throw UpdateSafetyException("ROOT_HELPER_UNAVAILABLE_OR_INTERRUPTED")
        val text=synchronized(output){output.toString("UTF-8")}.trim()
        return JSONObject(text).also { if(it.optString("error").isNotBlank())throw UpdateSafetyException(it.getString("error")) }
    }
}

internal object RestoreRootProtocol {
    fun observation(environment:RestoreEnvironment):JSONObject {
        val d=environment.device
        return JSONObject().put("schema",1).put("device",JSONObject()
            .put("manufacturer",d.manufacturer).put("model",d.model).put("device",d.device).put("channel",d.channel)
            .put("android",d.androidVersion).put("build",d.build).put("fingerprint",d.fingerprint).put("bootId",d.bootId).put("slot",d.slot)
            .put("magiskCode",d.magiskCode).put("battery",d.batteryPercent).put("freeBytes",d.freeBytes)
            .put("captured",d.capturedAtMillis).put("modules",JSONArray(d.modules)))
            .put("partitionBytes",environment.partitionBytes).put("activeSha256",environment.activeImageSha256)
            .put("automaticUpdatesDisabled",environment.automaticUpdatesDisabled==true).put("elapsed",environment.elapsedRealtimeMillis)
    }
    fun environment(json:JSONObject,stock:BackupValidation):RestoreEnvironment {
        require(json.getInt("schema")==1)
        val d=json.getJSONObject("device");val m=d.getJSONArray("modules")
        val device=DeviceState(manufacturer=d.getString("manufacturer"),model=d.getString("model"),device=d.getString("device"),channel=d.getString("channel"),
            androidVersion=d.getString("android"),build=d.getString("build"),fingerprint=d.getString("fingerprint"),bootId=d.getString("bootId"),slot=d.getString("slot"),
            magiskCode=d.getInt("magiskCode"),magiskVersion="31.1",batteryPercent=d.getInt("battery"),freeBytes=d.getLong("freeBytes"),capturedAtMillis=d.getLong("captured"),
            modules=(0 until m.length()).map { m.getString(it) },rootChecked=true,rootGranted=true,virtualAb=true,snapshot=SnapshotState.NONE,engine=EngineState.IDLE,
            partitions=listOf("init_boot_a","init_boot_b"),bootSuccessful=true,bootloaderUnlocked=true,verifiedBootState="orange",securityEvidenceSource="KERNEL_BOOTCONFIG",moduleInventoryKnown=true)
        return RestoreEnvironment(device,json.getLong("partitionBytes"),json.getString("activeSha256"),json.getBoolean("automaticUpdatesDisabled"),stock,ExecutionMode.LIVE,json.getLong("elapsed"))
    }
    fun receipt(r:StockRestoreResult):JSONObject=JSONObject().put("schema",1).put("transaction",r.transactionId).put("checkpoint",r.checkpoint.name)
        .put("observed",r.observedSha256).put("backup",r.backupSha256).put("stockRestored",r.stockRestored).put("failure",r.errorCode.orEmpty())
    fun result(j:JSONObject):StockRestoreResult {
        require(j.getInt("schema")==1)
        return StockRestoreResult(j.getString("transaction"),RestoreCheckpoint.valueOf(j.getString("checkpoint")),j.getString("observed"),j.getString("backup"),j.getBoolean("stockRestored"),j.optString("failure").ifBlank { null })
    }
    fun digest(file:File):String=MessageDigest.getInstance("SHA-256").run {
        file.inputStream().use { input -> val b=ByteArray(65_536);while(true){val n=input.read(b);if(n<0)break;update(b,0,n)} }
        digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun writePrivate(file:File,bytes:ByteArray){
        val atomic=android.util.AtomicFile(file);val out=atomic.startWrite()
        try{out.write(bytes);atomic.finishWrite(out);file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true)}
        catch(e:Exception){atomic.failWrite(out);throw e}
    }
}
