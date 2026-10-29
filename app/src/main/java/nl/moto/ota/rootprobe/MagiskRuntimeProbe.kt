package nl.moto.ota.rootprobe

import org.json.JSONObject
import kotlin.concurrent.thread

/** Read-only pinned-format runtime association, never sources a config or installer script. */
object MagiskRuntimeProbe {
    @JvmStatic fun main(args:Array<String>) {
        val watchdog=thread(isDaemon=true){try{Thread.sleep(6_000);Runtime.getRuntime().halt(70)}catch(_:InterruptedException){}}
        try {
            require(args.isEmpty() && android.os.Process.myUid()==0)
            val process=ProcessBuilder("/system/bin/sh","-c","magisk -V").start()
            val version=process.inputStream.use{it.readNBytes(32).toString(Charsets.US_ASCII).trim()}
            require(process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS) && process.exitValue()==0 && version=="31100")
            println(JSONObject().put("schema",1).put("stockSha1",StockRestoreProbe.runtimeStockSha1()))
        }catch(_:Exception){println("{\"error\":\"MAGISK_RUNTIME_UNPROVEN\"}")}
        System.out.flush();watchdog.interrupt();kotlin.system.exitProcess(0)
    }
}
