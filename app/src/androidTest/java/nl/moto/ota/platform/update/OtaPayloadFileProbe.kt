package nl.moto.ota.platform.update

import android.os.Build
import android.system.Os
import nl.moto.ota.core.update.UpdateRun
import nl.moto.ota.rootprobe.RootOtaPayloadStore
import nl.moto.ota.rootprobe.RootUpdateFacts
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Explicit emulator-only command-line test, never an instrumentation/UI write path.
 * It creates and removes only a regular file; no OTA, partition or power API is called. */
object OtaPayloadFileProbe {
    @JvmStatic fun main(args:Array<String>) {
        try {
            require(android.os.Process.myUid()==0 && Build.HARDWARE in setOf("ranchu","goldfish","cutf_cvm") &&
                RootUpdateFacts.command(listOf("/system/bin/getprop","ro.kernel.qemu")).trim()=="1")
            require(args.size==2 && UUID.fromString(args[0]).toString()==args[0] && args[1] in setOf("roundtrip","reject"))
            val run=UpdateRun(id=args[0])
            val source=File("/data/local/tmp/moto-ota-staging-proof/${run.id}.bin")
            require(source.canonicalPath==source.path && source.length()==8192L)
            val before=RootUpdateFacts.digest(source,8192)
            var file:File?=null
            try {
                file=RootOtaPayloadStore.prepare(run,source,8192,before,2000)
                val stat=Os.lstat(file.path)
                require(stat.st_uid==0 && stat.st_mode and 0x1ff==0x124 && RootUpdateFacts.digest(file,8192)==before)
                val label=Os.getxattr(file.path,"security.selinux").toString(Charsets.US_ASCII).trimEnd('\u0000')
                require(label=="u:object_r:ota_package_file:s0")
                if(args[1]=="reject") {
                    val duplicate=runCatching {RootOtaPayloadStore.prepare(run,source,8192,before,2000)}
                    require(duplicate.isFailure)
                    val altered=runCatching {RootOtaPayloadStore.existing(run,8192,"0".repeat(64))}
                    require(altered.isFailure && RootUpdateFacts.digest(file,8192)==before)
                }
                RootOtaPayloadStore.discard(run,8192,before)
                require(!file.exists() && !file.parentFile!!.exists() && RootUpdateFacts.digest(source,8192)==before)
                println(JSONObject().put("schema",1).put("case",args[1]).put("passed",true).put("bytes",8192)
                    .put("sourceUnchanged",true).put("normalOtaLabel",true).put("cleanupComplete",true))
            }finally {
                if(file?.exists()==true)runCatching {RootOtaPayloadStore.discard(run,8192,before)}
            }
        }catch(error:Throwable){println(JSONObject().put("passed",false).put("error",error.javaClass.simpleName))}
        System.out.flush();kotlin.system.exitProcess(0)
    }
}
