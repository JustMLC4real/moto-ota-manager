package nl.moto.ota.core

data class ReadinessResult(val blockers: List<CheckResult>, val warnings: List<CheckResult> = emptyList(), val checkedDevice: DeviceState? = null) {
    val ready: Boolean get() = blockers.isEmpty()
}

object Readiness {
    fun evaluate(
        device: DeviceState,
        backup: BackupValidation?,
        packageBytes: Long = 0,
        nowMillis: Long = System.currentTimeMillis()
    ): ReadinessResult {
        val blockers = mutableListOf<CheckResult>()
        fun check(ok: Boolean, code: String, text: String,args:List<String> = emptyList()) { if (!ok) blockers += CheckResult(code, text,"readiness.$code",args) }
        check(device.manufacturer.equals("motorola",true) && device.model.isNotBlank() && device.device.isNotBlank() &&
            device.identityConflicts.isEmpty() && (backup?.validatedIdentity?.matches(device)!=false), "DEVICE_PROFILE", "A consistent measured Motorola identity and matching image identity are required.")
        check(DeviceCapabilities.knownRestoreRuntime(device), "ANDROID_UNREVIEWED", "The write adapter requires a reviewed Android API 33–37 runtime.")
        check(device.build.isNotBlank() && device.fingerprint.isNotBlank(), "FIRMWARE_UNKNOWN", "Huidige build en exacte systeemfingerprint moeten bekend zijn.")
        check(device.rootGranted && device.rootChecked, "ROOT_DENIED", "Root moet actueel zijn gecontroleerd. Verleen Moto OTA Manager vooraf root in Magisk → Superuser; Magisk 31.1 toont geen reactieve rootprompt.")
        check(device.magiskCode == 31100, "MAGISK_UNREVIEWED", "Alleen Magisk 31.1 (31100) is onderzocht; andere versies vereisen herbeoordeling.")
        check((device.abUpdate==true || device.virtualAb==true) && device.abUpdate!=false && device.virtualAb!=null, "VIRTUAL_AB_UNKNOWN", "A/B and the presence or absence of Virtual A/B must be measured.")
        check(device.slot == "_a" || device.slot == "_b", "SLOT_UNKNOWN", "Actieve A/B-slot is onbekend.")
        val type=backup?.partitionType?.takeIf {it in setOf("boot","init_boot")}?:"init_boot"
        check(device.partitionInventoryKnown && device.partitions.containsAll(listOf(type+"_a",type+"_b")), "PARTITIONS_UNKNOWN", "Both banks of the authenticated image partition must be observed.")
        check(DeviceCapabilities.snapshotSafe(device), "SNAPSHOT_BLOCKED", "The snapshot state must be idle or positively not applicable.")
        check(device.engine == EngineState.IDLE, "ENGINE_NOT_IDLE", "UpdateEngine moet aantoonbaar idle zijn; actuele status is ${device.engine}.")
        check(device.batteryPercent in 50..100, "BATTERY_LOW", "Minimaal 50% accu is vereist; onbekende accustatus blokkeert.")
        val required = if (packageBytes > 0 && packageBytes <= (Long.MAX_VALUE - 2L * 1024 * 1024 * 1024) / 2) packageBytes * 2 + 2L * 1024 * 1024 * 1024 else 10L * 1024 * 1024 * 1024
        check(device.freeBytes >= required, "STORAGE_LOW", "Onvoldoende of onbekende vrije opslag: minimaal $required bytes vereist (schatting; updater beslist definitief).",listOf(required.toString()))
        check(backup?.usableFor(device) == true, "BACKUP_UNVERIFIED", "Authenticated original bytes for the measured current identity are required.")
        check(device.capturedAtMillis > 0 && nowMillis >= device.capturedAtMillis && nowMillis - device.capturedAtMillis <= 120_000, "OBSERVATIONS_STALE", "Diagnostiek is onbekend of ouder dan twee minuten; voer de controle opnieuw uit.")
        check(!device.simulated, "SIMULATED", "Gesimuleerde diagnostiek geeft nooit toestemming voor een echte update.")
        check(device.bootId.isNotBlank(), "BOOT_ID_UNKNOWN", "Opstartsessie is onbekend; herstel na een herstart kan niet betrouwbaar worden gevolgd.")
        check(device.bootSuccessful == true, "BOOT_NOT_CONFIRMED", "De actieve slot moet aantoonbaar succesvol zijn gemarkeerd via de service of CRC-gecontroleerde bootmetadata.")
        check(device.bootloaderUnlocked == true && device.verifiedBootState == "orange", "BOOT_SECURITY_UNKNOWN", "Het onderzochte profiel vereist een ontgrendelde bootloader met Verified Boot-status orange; afwijkingen vereisen handmatige beoordeling.")
        check(device.moduleInventoryKnown, "MODULE_INVENTORY_UNKNOWN", "Magisk-module-inventaris ontbreekt; behoud kan na de update niet betrouwbaar worden getoetst.")
        return ReadinessResult(blockers, emptyList(), device)
    }
}
