package nl.moto.ota.platform.update

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/** Extracts the already authenticated payload; only Android's actual UpdateEngine applies it. */
class DirectOtaController(private val context:Context) {
    suspend fun prepare(run:UpdateRun,report:FirmwareReport,pinned:File):Unit=withContext(Dispatchers.IO) {
        require(!run.simulated && run.packageVerified && run.packageSha256==report.sha256 && report.signatureVerified)
        require(pinned.canonicalFile.parentFile==File(context.filesDir,"verified-packages").canonicalFile &&
            pinned.name.matches(Regex("[a-f0-9-]{36}[.]zip")))
        require(RestoreRootProtocol.digest(pinned)==report.sha256)
        val directory=directory(run.id)
        val payload=File(directory,"payload.bin")
        require(!payload.exists()) {"OTA_PAYLOAD_ALREADY_PREPARED"}
        val temporary=File(directory,"payload.pending")
        ZipFile(pinned).use {zip->
            val entry=requireNotNull(zip.getEntry("payload.bin"))
            require(entry.size in 24..FirmwareValidator.MAX_PACKAGE_BYTES && context.filesDir.usableSpace>=entry.size+2L*1024*1024*1024)
            val props=readProperties(zip)
            require(props["FILE_SIZE"]?.toLongOrNull()==entry.size)
            val expected=java.util.Base64.getDecoder().decode(requireNotNull(props["FILE_HASH"]))
            val digest=MessageDigest.getInstance("SHA-256")
            val fd=Os.open(temporary.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
            var total=0L
            try {
                FileOutputStream(fd).use {output->zip.getInputStream(entry).use {input->
                    val bytes=ByteArray(65_536)
                    while(true){coroutineContext.ensureActive();val n=input.read(bytes);if(n<0)break;total+=n;require(total<=entry.size);digest.update(bytes,0,n);output.write(bytes,0,n)}
                    output.flush();output.fd.sync()
                }}
            }catch(error:Exception){temporary.delete();throw error}
            require(total==entry.size && MessageDigest.isEqual(expected,digest.digest())) {"OTA_PAYLOAD_CHANGED"}
            Os.chmod(temporary.path,0x100);Os.rename(temporary.path,payload.path)
            val plan=JSONObject().put("schema",1).put("run",run.id).put("packageName",pinned.name)
                .put("packageSha256",report.sha256).put("packageBytes",pinned.length()).put("payloadBytes",entry.size)
            RestoreRootProtocol.writePrivate(File(directory,"plan.json"),plan.toString().toByteArray())
        }
        // Prepare is entirely read-only with respect to the device and update engine.
        AutomationRootTransport(context.applicationInfo.sourceDir).invoke("DirectOtaProbe","prepare",run.id,300)
        // Root has published its separate immutable, normally labelled OTA file.
        require(payload.delete()) {"OTA_PRIVATE_PAYLOAD_CLEANUP_FAILED"}
    }
    suspend fun apply(run:UpdateRun,operation:AuthorizedRunOperation):DeviceState=withContext(Dispatchers.IO) {
        require(operation.runId==run.id && operation.action==RunAction.APPLY_OTA && operation.packageSha256==run.packageSha256)
        RestoreRootProtocol.writePrivate(File(directory(run.id),"operation.properties"),RunAuthorizationCodec.encodeOperation(operation).toByteArray())
        val result=AutomationRootTransport(context.applicationInfo.sourceDir).invoke("DirectOtaProbe","apply",run.id,6*60*60)
        require(result.getInt("schema")==1 && result.getBoolean("applied") && result.getString("run")==run.id &&
            result.getInt("completion")==0) {"OTA_APPLICATION_NOT_PROVEN"}
        AutomationDeviceCodec.decode(result.getJSONObject("device"))
    }
    suspend fun observe(run:UpdateRun):DeviceState=withContext(Dispatchers.IO) {
        AutomationDeviceCodec.decode(AutomationRootTransport(context.applicationInfo.sourceDir).invoke("DirectOtaProbe","observe",run.id,30).getJSONObject("device"))
    }
    internal fun discardPrepared(run:UpdateRun) {
        AutomationRootTransport(context.applicationInfo.sourceDir).invoke("DirectOtaProbe","discard",run.id,300)
    }
    private fun directory(id:String):File {
        require(java.util.UUID.fromString(id).toString()==id)
        val directory=File(context.filesDir,"update/ota/$id").apply {mkdirs()}
        require(directory.canonicalPath==directory.path)
        Os.chmod(directory.path,0x1c0)
        return directory
    }
    companion object {
        internal fun readProperties(zip:ZipFile):Map<String,String> {
            val entry=requireNotNull(zip.getEntry("payload_properties.txt"));require(entry.size in 1..65_536)
            val bytes=zip.getInputStream(entry).use {it.readNBytes(65_537)};require(bytes.size<=65_536 && bytes.size.toLong()==entry.size)
            val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val all=linkedMapOf<String,String>()
            text.lineSequence().filter(String::isNotBlank).forEach {
                val pair=it.split('=',limit=2);require(pair.size==2 && pair[0].matches(Regex("[A-Z_]{1,80}")) && !all.containsKey(pair[0]))
                all[pair[0]]=pair[1].trim()
            }
            val required=setOf("FILE_HASH","FILE_SIZE","METADATA_HASH","METADATA_SIZE")
            require(all.keys.containsAll(required))
            return all.filterKeys {it in required}
        }
    }
}

/** Fixed entry points, UUIDs only, bounded/redacted result; no user shell fragments. */
internal class AutomationRootTransport(private val apk:String) {
    fun invoke(helper:String,action:String,id:String,timeoutSeconds:Long):JSONObject {
        require(apk.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk")))
        require(helper in setOf("DirectOtaProbe","ControlledRebootProbe","MagiskInactiveProbe"))
        require(action in setOf("prepare","apply","install","reboot","observe","discard"))
        require(java.util.UUID.fromString(id).toString()==id && timeoutSeconds in 1..21_600)
        val command="CLASSPATH='$apk' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.$helper $action $id"
        val process=ProcessBuilder("su","--target","0","-c",command).start()
        val output=java.io.ByteArrayOutputStream();var oversized=false
        val reader=thread(isDaemon=true){process.inputStream.use {input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;synchronized(output){if(output.size()+n<=65_536)output.write(b,0,n)else oversized=true}}}}
        val errors=thread(isDaemon=true){process.errorStream.use {input->val b=ByteArray(4096);while(input.read(b)>=0){}}}
        val complete=process.waitFor(timeoutSeconds,TimeUnit.SECONDS)
        // UpdateEngine owns OTA after applyPayloadFd. Losing the callback must never cause
        // cancel/reset/reapply, a Magisk write, or an automatic reboot.
        if(!complete)process.destroyForcibly()
        reader.join(1000);errors.join(1000)
        if(!complete || reader.isAlive || oversized || process.exitValue()!=0)throw UpdateSafetyException("AUTOMATION_HELPER_INTERRUPTED")
        return JSONObject(synchronized(output){output.toString("UTF-8")}.trim()).also {
            if(it.optString("error").isNotBlank())throw UpdateSafetyException(it.getString("error"))
        }
    }
}
