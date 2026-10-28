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
        fun check(ok: Boolean, code: String, text: String) { if (!ok) blockers += CheckResult(code, text) }
        check(device.exactProfileMatch, "DEVICE_PROFILE", "Alleen Motorola XT2607-1 / merc / reteu is toegestaan; ontbrekende eigenschappen blokkeren.")
        check(device.androidVersion == "17", "ANDROID_UNREVIEWED", "Dit toestelprofiel is alleen voor Android 17 onderzocht. Andere Android-versies vereisen herbeoordeling.")
        check(device.build.isNotBlank() && device.fingerprint.isNotBlank(), "FIRMWARE_UNKNOWN", "Huidige build en exacte systeemfingerprint moeten bekend zijn.")
        check(device.rootGranted && device.rootChecked, "ROOT_DENIED", "Root moet actueel zijn gecontroleerd. Verleen Moto OTA Manager vooraf root in Magisk → Superuser; Magisk 31.1 toont geen reactieve rootprompt.")
        check(device.magiskCode == 31100, "MAGISK_UNREVIEWED", "Alleen Magisk 31.1 (31100) is onderzocht; andere versies vereisen herbeoordeling.")
        check(device.virtualAb == true, "VIRTUAL_AB_UNKNOWN", "Virtual A/B moet aantoonbaar actief zijn.")
        check(device.slot == "_a" || device.slot == "_b", "SLOT_UNKNOWN", "Actieve A/B-slot is onbekend.")
        check(device.partitions.containsAll(listOf("init_boot_a", "init_boot_b")), "PARTITIONS_UNKNOWN", "Beide init_boot-partities moeten zijn waargenomen.")
        check(device.snapshot == SnapshotState.NONE, "SNAPSHOT_BLOCKED", "Snapshotstatus ${device.snapshot}: alleen bewezen NONE laat een volgende voorbereiding toe; merge/cleanup moet voltooid zijn.")
        check(device.engine == EngineState.IDLE, "ENGINE_NOT_IDLE", "UpdateEngine moet aantoonbaar idle zijn; actuele status is ${device.engine}.")
        check(device.batteryPercent in 50..100, "BATTERY_LOW", "Minimaal 50% accu is vereist; onbekende accustatus blokkeert.")
        val required = if (packageBytes > 0 && packageBytes <= (Long.MAX_VALUE - 2L * 1024 * 1024 * 1024) / 2) packageBytes * 2 + 2L * 1024 * 1024 * 1024 else 10L * 1024 * 1024 * 1024
        check(device.freeBytes >= required, "STORAGE_LOW", "Onvoldoende of onbekende vrije opslag: minimaal $required bytes vereist (schatting; updater beslist definitief).")
        check(backup?.usableAsStock == true, "BACKUP_UNVERIFIED", "Een aantoonbaar correcte originele init_boot voor de actuele build ontbreekt.")
        check(device.capturedAtMillis > 0 && nowMillis >= device.capturedAtMillis && nowMillis - device.capturedAtMillis <= 120_000, "OBSERVATIONS_STALE", "Diagnostiek is onbekend of ouder dan twee minuten; voer de controle opnieuw uit.")
        check(!device.simulated, "SIMULATED", "Gesimuleerde diagnostiek geeft nooit toestemming voor een echte update.")
        check(device.bootId.isNotBlank(), "BOOT_ID_UNKNOWN", "Opstartsessie is onbekend; herstel na een herstart kan niet betrouwbaar worden gevolgd.")
        check(device.bootSuccessful == true, "BOOT_NOT_CONFIRMED", "De actieve slot moet aantoonbaar succesvol zijn gemarkeerd via de service of CRC-gecontroleerde bootmetadata.")
        check(device.bootloaderUnlocked == true && device.verifiedBootState == "orange", "BOOT_SECURITY_UNKNOWN", "Het onderzochte profiel vereist een ontgrendelde bootloader met Verified Boot-status orange; afwijkingen vereisen handmatige beoordeling.")
        check(device.moduleInventoryKnown, "MODULE_INVENTORY_UNKNOWN", "Magisk-module-inventaris ontbreekt; behoud kan na de update niet betrouwbaar worden getoetst.")
        return ReadinessResult(blockers, listOf(CheckResult("GUIDED_ONLY", "Deze versie voert geen herstel, OTA-installatie, Magisk-patching of herstart uit. Definitieve signature- en compatibiliteitscontrole blijft bij de officiële updater.")), device)
    }
}
