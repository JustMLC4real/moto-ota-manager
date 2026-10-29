package nl.moto.ota.platform.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/** Only a fixed entry point and generated transaction UUID reach su. Source names stay in a private request. */
internal class ImporterTransport(private val apkPath:String) {
    init { require(apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk"))) }
    suspend fun invoke(operation:String,transactionId:String?=null):JSONObject=withContext(Dispatchers.IO) {
        require(operation in setOf("discover","copy"))
        require(if(operation=="discover")transactionId==null else transactionId?.matches(Regex("[a-f0-9-]{36}"))==true)
        val suffix=transactionId?.let { " $it" }.orEmpty()
        val command="CLASSPATH='$apkPath' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.importer.MotorolaPackageImportProbe $operation$suffix"
        val process=ProcessBuilder("su","--target","0","-c",command).redirectErrorStream(false).start()
        val out=ByteArrayOutputStream();var overflow=false
        val reader=thread(isDaemon=true){process.inputStream.use { input -> val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;synchronized(out){if(out.size()+n<=65_536)out.write(b,0,n)else overflow=true}} }}
        val errors=thread(isDaemon=true){process.errorStream.use { input ->val b=ByteArray(4096);while(input.read(b)>=0){} }}
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(if(operation=="copy")1_850_000 else 35_000)
        try {
            while(!process.waitFor(500,TimeUnit.MILLISECONDS)){
                coroutineContext.ensureActive()
                check(System.nanoTime()<deadline){"MOTOROLA_IMPORT_TIMEOUT"}
            }
            coroutineContext.ensureActive();reader.join(1000);errors.join(1000)
            check(process.exitValue()==0 && !reader.isAlive && !overflow){"MOTOROLA_IMPORT_HELPER_UNAVAILABLE"}
            val result=JSONObject(synchronized(out){out.toString("UTF-8")}.trim())
            check(result.optString("error").isBlank()){result.optString("error")}
            require(result.getInt("schema")==1)
            result
        }finally{
            // An orphan helper can finish only its unique app-private staging file;
            // it never owns the selected-package publication operation.
            if(process.isAlive)process.destroyForcibly()
            runCatching { process.inputStream.close() };runCatching { process.errorStream.close() }
        }
    }
}
