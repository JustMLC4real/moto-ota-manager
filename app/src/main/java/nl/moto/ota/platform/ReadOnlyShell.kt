package nl.moto.ota.platform

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class BinaryResult(val bytes: ByteArray, val result: ProbeResult)

/** Only enum commands and strictly validated Magisk backup identifiers can reach the process API. */
class ReadOnlyShell {
    fun run(probe: ReadOnlyProbe): ProbeResult = execute(
        if (probe.requiresRoot) listOf("su", "-c", probe.command) else listOf("/system/bin/sh", "-c", probe.command),
        probe.timeoutMillis, 1_048_576
    ).let { binary -> binary.result.copy(stdout = binary.bytes.toString(Charsets.UTF_8)) }

    fun property(name: String): ProbeResult {
        require(name in PROPERTY_NAMES) { "Property outside allowlist" }
        return execute(listOf("/system/bin/getprop", name), 2_000, 8_192)
            .let { it.result.copy(stdout = it.bytes.toString(Charsets.UTF_8).trim()) }
    }

    internal fun readBackup(directoryId: String): BinaryResult {
        require(Regex("magisk_backup_[a-fA-F0-9]{40}").matches(directoryId)) { "Invalid backup directory" }
        // No shell metacharacters, path separators, spaces or user-selected paths are accepted.
        return execute(listOf("su", "-c", "cat /data/$directoryId/boot.img.gz"), 10_000, 16 * 1_048_576)
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
