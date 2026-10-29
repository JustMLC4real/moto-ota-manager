package nl.moto.ota.core

import java.io.InputStream

/** A reference must originate from independently authenticated firmware, not an image filename. */
data class StockReference(
    val sha256: String,
    val bytes: Long,
    val build: String,
    val fingerprint: String,
    val device: String,
    val model: String,
    val channel: String,
    val partition: String = "init_boot",
    val authenticityVerified: Boolean = false,
    val evidence: String = ""
)

data class ImageIdentity(val model:String,val device:String,val channel:String,val build:String,val fingerprint:String) {
    fun matches(device:DeviceState):Boolean=this==from(device)
    companion object { fun from(device:DeviceState)=ImageIdentity(device.model,device.device,device.channel,device.build,device.fingerprint) }
}

data class BackupValidation(
    val structurallyValid: Boolean = false,
    val provenanceVerified: Boolean = false,
    val sha256: String = "",
    val sha1: String = "",
    val bytes: Long = 0,
    val partitionType: String = "unknown",
    val issues: List<CheckResult> = emptyList(),
    val validatedIdentity: ImageIdentity? = null
) {
    val usableAsStock: Boolean get() = structurallyValid && provenanceVerified && issues.isEmpty()
    fun usableFor(device:DeviceState):Boolean=usableAsStock && validatedIdentity?.matches(device)==true
}

object BackupValidator {
    private const val OLD_ANDROID_16_SHA = "2813c93926eae599bd3bd14c4e9e39b10bc3b2dd30134bff8fe594a3f8a67d29"

    /** Reads image bytes only: shell diagnostics/stderr must never be mixed into this input. */
    fun inspect(
        input: InputStream,
        compressed: Boolean,
        expectedPartitionBytes: Long,
        device: DeviceState,
        reference: StockReference? = null,
        partitionType: String = "init_boot"
    ): BackupValidation {
        val issues = mutableListOf<CheckResult>()
        if (expectedPartitionBytes !in 4096..BootImageStreams.MAX_IMAGE_BYTES) {
            return BackupValidation(issues = listOf(CheckResult("PARTITION_SIZE_UNKNOWN", "De actuele init_boot-partitiegrootte is onbekend of ongeldig.")))
        }
        return try {
            val raw = BootImageStreams.measure(input,compressed,expectedPartitionBytes)
            val sha256 = raw.sha256
            val sha1 = raw.sha1
            if (raw.bytes != expectedPartitionBytes) issues += CheckResult("IMAGE_SIZE", "Image length differs from the measured partition.","backup.IMAGE_SIZE",listOf(raw.bytes.toString(),expectedPartitionBytes.toString()))
            val supported = partitionType in setOf("boot","init_boot") && raw.partitionType==partitionType
            if (!supported) issues += CheckResult("IMAGE_HEADER", "Unsupported or different boot image type.","backup.IMAGE_HEADER")
            val structureOk = issues.isEmpty()
            val provenance = reference != null && reference.authenticityVerified && reference.evidence.isNotBlank() &&
                reference.sha256.equals(sha256, true) && reference.bytes == raw.bytes &&
                reference.build == device.build && reference.fingerprint == device.fingerprint &&
                reference.device == device.device && reference.model == device.model && reference.channel == device.channel &&
                reference.partition == partitionType && device.build.isNotBlank() && device.fingerprint.isNotBlank() &&
                !(sha256 == OLD_ANDROID_16_SHA && device.build != "W3WMS36.40-75-5-2")
            if (!provenance) issues += CheckResult("STOCK_PROVENANCE_UNKNOWN", "Header en hash bewijzen geen stock-herkomst of koppeling aan de huidige build. Een onafhankelijk geverifieerde actuele firmware-referentie ontbreekt of wijkt af.")
            BackupValidation(structureOk, provenance, sha256, sha1, raw.bytes, if (supported) partitionType else "unknown", issues,
                if(provenance)ImageIdentity.from(device)else null)
        } catch (error: BootImageReadFailure) {
            if(error.code!="IMAGE_SIZE")return BackupValidation(issues=listOf(CheckResult(error.code,"Invalid or truncated boot image stream.","backup.${error.code}")))
            BackupValidation(issues = listOf(CheckResult("IMAGE_SIZE", "Image of gzip overschrijdt de gemeten partitiegrootte of veilige leeslimiet; mogelijke extra shelluitvoer.")))
        } catch (_: Exception) {
            BackupValidation(issues = listOf(CheckResult("IMAGE_CORRUPT", "Image/gzip is beschadigd, onleesbaar of overschrijdt de veilige limiet.")))
        }
    }

}
