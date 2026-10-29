package nl.moto.ota.platform

/** Fixed read-only probe output is evidence only when complete and tied to the observed slot. */
object UpdateObservations {
    fun activeImageSha256(inspection: Inspection, partitionType: String = inspection.backupAssociation?.partitionType ?: "init_boot"): String? {
        val device = inspection.toDeviceState()
        if (!device.rootGranted || !device.rootChecked) return null
        val slot = device.slot.takeIf { it == "_a" || it == "_b" } ?: return null
        if(partitionType !in setOf("boot","init_boot")) return null
        val probe = inspection.probes[(if(partitionType=="boot") ReadOnlyProbe.ACTIVE_BOOT_HASH else ReadOnlyProbe.ACTIVE_INIT_BOOT_HASH).name]?.takeIf { it.successful } ?: return null
        val match = Regex("([a-f0-9]{64})[ \\t]+/dev/block/by-name/" + partitionType + slot)
            .matchEntire(probe.stdout.trim()) ?: return null
        return match.groupValues[1]
    }
    fun automaticUpdatesDisabled(inspection: Inspection): Boolean? =
        inspection.probes[ReadOnlyProbe.AUTO_UPDATE_SETTING.name]?.takeIf { it.successful }?.stdout?.trim()?.let {
            when (it) { "1" -> true; "0" -> false; else -> null }
        }
}
