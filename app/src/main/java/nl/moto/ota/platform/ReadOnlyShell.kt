package nl.moto.ota.platform

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class BinaryResult(val bytes: ByteArray, val result: ProbeResult)

/** Only enum commands and strictly validated Magisk backup identifiers can reach the process API. */
class ReadOnlyShell {
    fun run(probe: ReadOnlyProbe): ProbeResult {
        if (!probe.enabled) return ProbeResult(null, "", "", failure = "UNSAFE_INITIALIZER_DISABLED")
        return execute(
        if (probe.requiresRoot) {
            if (probe.globalMountNamespace) listOf("su", "--target", "0", "-c", probe.command)
            else listOf("su", "-c", probe.command)
        } else listOf("/system/bin/sh", "-c", probe.command),
        probe.timeoutMillis, 1_048_576
        ).let { binary -> binary.result.copy(stdout = binary.bytes.toString(Charsets.UTF_8)) }
    }

    fun property(name: String, asRoot: Boolean = false): ProbeResult {
        require(name in PROPERTY_NAMES) { "Property outside allowlist" }
        val arguments = if (asRoot) listOf("su", "-c", "/system/bin/getprop $name") else listOf("/system/bin/getprop", name)
        return execute(arguments, 2_000, 8_192)
            .let { it.result.copy(stdout = it.bytes.toString(Charsets.UTF_8).trim()) }
    }

    internal fun readBackup(directoryId: String): BinaryResult {
        require(Regex("magisk_backup_[a-fA-F0-9]{40}").matches(directoryId)) { "Invalid backup directory" }
        // No shell metacharacters, path separators, spaces or user-selected paths are accepted.
        return execute(listOf("su", "-c", "cat /data/$directoryId/boot.img.gz"), 10_000, 16 * 1_048_576)
    }

    /** Streams bounded bytes; a large boot image never enters a ByteArray capture. */
    internal fun <T> withBackup(directoryId:String,expectedCompressedBytes:Long,consume:(InputStream)->T):T {
        require(Regex("magisk_backup_[a-fA-F0-9]{40}").matches(directoryId))
        require(expectedCompressedBytes in 1..nl.moto.ota.core.BootImageStreams.MAX_PACKED_BYTES)
        val process=ProcessBuilder("su","--target","0","-c","cat /data/$directoryId/boot.img.gz").start()
        val error=Capture(4096)
        val err=thread(isDaemon=true){error.read(process.errorStream)}
        val watchdog=thread(isDaemon=true){try{Thread.sleep(45_000);process.destroyForcibly();process.inputStream.close()}catch(_:InterruptedException){}catch(_:Exception){}}
        try{
            var count=0L
            val bounded=object:java.io.FilterInputStream(process.inputStream){
                override fun read():Int {val n=super.read();if(n>=0){count++;check(count<=expectedCompressedBytes)};return n}
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                    val n=`in`.read(bytes,offset,minOf(length.toLong(),expectedCompressedBytes-count+1).toInt())
                    if(n>0){count+=n;check(count<=expectedCompressedBytes)};return n
                }
            }
            val result=bounded.use(consume)
            check(count==expectedCompressedBytes && process.waitFor(2,TimeUnit.SECONDS) && process.exitValue()==0)
            err.join(500);check(!err.isAlive && error.failure==null && !error.truncated)
            return result
        }finally{watchdog.interrupt();process.destroyForcibly();runCatching{process.inputStream.close()}}
    }

    internal fun magiskRuntime(apkPath:String):ProbeResult {
        require(apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk")))
        val command="CLASSPATH='$apkPath' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.MagiskRuntimeProbe"
        return execute(listOf("su","--target","0","-c",command),8_000,4096).let {it.result.copy(stdout=it.bytes.toString(Charsets.UTF_8))}
    }

    /** The only dynamic value is the OS-supplied path of this installed APK. */
    internal fun engineCallback(apkPath: String): ProbeResult {
        require(apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk"))) { "Unexpected installed APK path" }
        val command = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.EngineProbe"
        return execute(listOf("su", "-c", command), 8_000, 65_536).let {
            it.result.copy(stdout = it.bytes.toString(Charsets.UTF_8))
        }
    }

    /** Explicit diagnostic of an existing updater-owned ZIP. No staging or applying. */
    internal fun firmwareSignature(apkPath: String, packagePath: String): ProbeResult {
        require(apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk"))) { "Unexpected installed APK path" }
        require(packagePath.matches(Regex("/(?:sdcard|storage/emulated/0)/Android/data/com[.]motorola[.]ccc[.]ota/files/[A-Za-z0-9_.-]+[.]zip"))) { "Unexpected updater package path" }
        val command = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin nl.moto.ota.rootprobe.FirmwareSignatureProbe '$packagePath'"
        return execute(listOf("su", "--target", "0", "-c", command), 190_000, 65_536).let {
            it.result.copy(stdout = it.bytes.toString(Charsets.UTF_8))
        }
    }

    private fun execute(arguments: List<String>, timeoutMillis: Long, outputLimit: Int): BinaryResult {
        return try {
            val process = ProcessBuilder(arguments).redirectErrorStream(false).start()
            val stdout = Capture(outputLimit)
            val stderr = Capture(65_536)
            val outThread = thread(isDaemon = true, name = "moto-probe-stdout") { stdout.read(process.inputStream) }
            val errThread = thread(isDaemon = true, name = "moto-probe-stderr") { stderr.read(process.errorStream) }
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) process.destroyForcibly()
            outThread.join(1_000)
            errThread.join(1_000)
            if (outThread.isAlive || errThread.isAlive) {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
            BinaryResult(stdout.bytes(), ProbeResult(
                exitCode = if (completed) process.exitValue() else null,
                stdout = "", stderr = stderr.bytes().toString(Charsets.UTF_8),
                timedOut = !completed,
                truncated = stdout.truncated || stderr.truncated || outThread.isAlive || errThread.isAlive,
                failure = stdout.failure ?: stderr.failure
            ))
        } catch (error: Exception) {
            BinaryResult(byteArrayOf(), ProbeResult(null, "", "", failure = error.javaClass.simpleName))
        }
    }

    private class Capture(private val limit: Int) {
        private val output = ByteArrayOutputStream()
        @Volatile var truncated = false
        @Volatile var failure: String? = null
        fun read(input: InputStream) {
            try {
                input.use {
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val retained = minOf(count, limit - output.size())
                            if (retained > 0) output.write(buffer, 0, retained)
                            if (retained < count) truncated = true
                        }
                    }
                }
            } catch (error: Exception) { failure = error.javaClass.simpleName }
        }
        fun bytes(): ByteArray = synchronized(output) { output.toByteArray() }
    }

    companion object {
        val PROPERTY_NAMES = setOf(
            "ro.product.manufacturer", "ro.product.model", "ro.boot.hardware.sku", "ro.boot.product.hardware.sku",
            "ro.product.device", "ro.product.name", "ro.boot.carrier", "ro.carrier", "ro.boot.cid",
            "ro.build.version.release", "ro.build.version.sdk", "ro.build.id", "ro.build.display.id",
            "ro.build.fingerprint", "ro.vendor.build.fingerprint", "ro.system.build.fingerprint",
            "ro.product.build.fingerprint", "ro.odm.build.fingerprint", "ro.build.version.security_patch",
            "ro.boot.slot_suffix", "ro.boot.slot", "ro.virtual_ab.enabled", "ro.boot.dynamic_partitions",
            "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.verifiedbootstate",
            "ro.boot.veritymode", "ro.build.ab_update", "ro.build.version.incremental", "ro.build.date.utc"
        )
    }
}
