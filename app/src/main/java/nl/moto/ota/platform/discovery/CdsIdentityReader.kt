package nl.moto.ota.platform.discovery

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.discovery.*
import nl.moto.ota.platform.Inspection

class CdsIdentityRead(val identity: CdsIdentity? = null, val missingFields: List<CdsInputField> = emptyList()) {
    override fun toString(): String = "CdsIdentityRead(available=${identity != null}, missingFields=$missingFields)"
}
fun interface CdsIdentitySource { fun read(inspection: Inspection, language: String): CdsIdentityRead }

/** This reader is called only after online-sharing consent. Identifiers never enter normal probes. */
class CdsIdentityReader : CdsIdentitySource {
    override fun read(inspection: Inspection, language: String): CdsIdentityRead {
        val started = System.currentTimeMillis()
        val values = readFixed() ?: return CdsIdentityRead(missingFields = listOf(CdsInputField.ROOT))
        fun value(key: String) = values[key].orEmpty()
        val mismatch = IDENTITY_KEYS.any { key -> inspection.properties[key]?.takeIf(String::isNotBlank)?.let { it != value(key) } == true }
        if (mismatch) return CdsIdentityRead(missingFields = listOf(CdsInputField.IDENTITY))
        val serials = listOf(value("ro.serialno"), value("ro.boot.serialno")).filter(String::isNotBlank).distinct()
        if (serials.size != 1) return CdsIdentityRead(missingFields = listOf(CdsInputField.SERIAL_NUMBER))
        val channels = listOf(value("ro.boot.carrier"), value("ro.carrier")).filter(String::isNotBlank).distinct()
        val channel = channels.singleOrNull().orEmpty()
        val technicalModels = listOf(value("ro.boot.hardware.sku"), value("ro.boot.product.hardware.sku"), value("ro.product.model"))
            .filter { it.matches(Regex("XT[0-9]+(?:-[0-9]+)?", RegexOption.IGNORE_CASE)) }.distinct()
        val device = DeviceState(manufacturer = value("ro.product.manufacturer"), model = technicalModels.singleOrNull() ?: value("ro.product.model"),
            device = value("ro.product.device"), channel = channel, androidVersion = value("ro.build.version.release"),
            build = value("ro.build.id"), fingerprint = value("ro.build.fingerprint"), incrementalBuild = value("ro.build.version.incremental"),
            capturedAtMillis = started, identityConflicts = buildList {
                if (channels.size > 1) add("CHANNEL_CONFLICT")
                if (technicalModels.size > 1) add("MODEL_CONFLICT")
            })
        val prc = value("ro.product.is_prc").equals("true", true) || value("ro.lenovo.region").equals("prc", true) ||
            value("ro.mot.build.customerid").equals("china", true) || channel.lowercase() in setOf("retcn", "cmcc", "ctcn", "cucn", "cbncn")
        val identity = CdsIdentity(device, serials.single(), value("ro.mot.build.guid"), value("ro.product.model"),
            value("ro.vendor.product.display"), value("ro.build.id"), value("ro.product.brand"), value("ro.hardware"),
            value("ro.product.locale.region"), value("ro.product.locale"), language.takeIf { it.matches(Regex("[A-Za-z_-]{2,35}")) } ?: "en",
            listOf("os.name", "vm.name", "runtime.name").mapNotNull { System.getProperty(it) }.joinToString("/"), prc,
            buildDisplayId = value("ro.build.display.id"))
        val missing = CdsProtocol.missing(identity, System.currentTimeMillis())
        return if (missing.isEmpty()) CdsIdentityRead(identity) else CdsIdentityRead(missingFields = missing)
    }

    private fun readFixed(): Map<String,String>? = runCatching {
        // Every argument is constant. No caller string is interpolated into a shell command.
        val command = "id -u; " + PROPERTIES.joinToString("; ") { "/system/bin/getprop $it" }
        val process = ProcessBuilder("su", "-c", command).start()
        val overflow = AtomicBoolean(false)
        val output = ByteArrayOutputStream()
        val stdout = thread(name = "cds-identity", isDaemon = true) {
            process.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(output) {
                        if (output.size() > MAX_OUTPUT - n) { overflow.set(true); process.destroy(); return@thread }
                        output.write(buffer,0,n)
                    }
                }
            }
        }
        val stderr = thread(name = "cds-identity-errors", isDaemon = true) { runCatching { process.errorStream.use { input -> val b = ByteArray(1024); while(input.read(b) >= 0) { } } } }
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        stdout.join(1000); stderr.join(1000)
        require(finished && process.exitValue() == 0 && !overflow.get() && !stdout.isAlive)
        val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
        val lines = text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }
        require(lines.size == PROPERTIES.size + 1 && lines.first() == "0" && lines.all { it.length <= 512 && it.none { c -> c < ' ' } })
        PROPERTIES.zip(lines.drop(1)).toMap()
    }.getOrNull()

    companion object {
        private const val MAX_OUTPUT = 16_384
        private val IDENTITY_KEYS = setOf("ro.product.manufacturer", "ro.product.model", "ro.product.device", "ro.build.id", "ro.build.fingerprint", "ro.boot.hardware.sku", "ro.boot.product.hardware.sku")
        private val PROPERTIES = listOf("ro.serialno", "ro.boot.serialno", "ro.mot.build.guid", "ro.product.manufacturer", "ro.product.model",
            "ro.vendor.product.display", "ro.product.device", "ro.boot.carrier", "ro.carrier", "ro.build.id", "ro.build.display.id", "ro.build.fingerprint",
            "ro.build.version.release", "ro.build.version.incremental", "ro.product.brand", "ro.hardware", "ro.product.locale.region",
            "ro.product.locale", "ro.product.is_prc", "ro.lenovo.region", "ro.mot.build.customerid", "ro.boot.hardware.sku", "ro.boot.product.hardware.sku")
    }
}
