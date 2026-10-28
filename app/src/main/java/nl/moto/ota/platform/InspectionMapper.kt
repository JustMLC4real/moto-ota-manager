package nl.moto.ota.platform

import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.EngineState
import nl.moto.ota.core.SnapshotState

/** Converts observed evidence without promoting a missing, failed or contradictory probe to success. */
fun Inspection.toDeviceState(): DeviceState {
    fun property(key: String) = properties[key]?.trim().orEmpty()
    fun successful(key: ReadOnlyProbe) = probes[key.name]?.takeIf { it.successful }
    fun probe(key: ReadOnlyProbe) = successful(key)?.stdout?.trim().orEmpty()

    val rootResult = probes[ReadOnlyProbe.ROOT_ID.name]
    val rootIds = rootResult?.takeIf { it.successful }?.let {
        Regex("(?:^|\\s)uid=(\\d+)(?:\\([^\\r\\n)]*\\))?(?=\\s|$)").findAll(it.stdout).map { match -> match.groupValues[1] }.toList()
    }.orEmpty()
    val rootGranted = rootRequested && rootResult?.successful == true && rootIds == listOf("0")
    val rootChecked = rootRequested && rootResult != null && !rootResult.timedOut && !rootResult.truncated &&
        rootResult.failure == null && rootResult.exitCode != null && (rootResult.exitCode != 0 || rootIds.size == 1)
    fun rootProbe(key: ReadOnlyProbe) = if (rootGranted) probe(key) else ""

    val model = property("ro.product.model")
    val skuValues = listOf(property("ro.boot.hardware.sku"), property("ro.boot.product.hardware.sku"))
    val technicalModels = (skuValues + model).filter { it.matches(Regex("XT[0-9]+(?:-[0-9]+)?", RegexOption.IGNORE_CASE)) }
    val compatibleModel = "XT2607-1" in technicalModels && technicalModels.all { it == "XT2607-1" }
    val channels = listOf(property("ro.boot.carrier"), property("ro.carrier")).filter { it.isNotBlank() }.distinct()

    val suffixProperty = property("ro.boot.slot_suffix")
    val slotProperty = property("ro.boot.slot")
    val slotSuffix = suffixProperty.takeIf { it in setOf("_a", "_b") }.orEmpty()
    val slotFromName = when (slotProperty) { "a" -> "_a"; "b" -> "_b"; else -> "" }
    val kernelSlot = if (rootGranted) kernelBootConfig["slot_suffix"].orEmpty() else ""
    val sourceSlots = listOf(slotSuffix, slotFromName, kernelSlot).filter { it.isNotEmpty() }.distinct()
    val suffixInvalid = suffixProperty.isNotBlank() && slotSuffix.isBlank()
    val nameInvalid = slotProperty.isNotBlank() && slotFromName.isBlank()
    val candidateSlot = if (!suffixInvalid && !nameInvalid && sourceSlots.size == 1 && sourceSlots.single() in setOf("_a", "_b")) sourceSlots.single() else ""
    val expectedIndex = when (candidateSlot) { "_a" -> "0"; "_b" -> "1"; else -> "" }
    val observedIndex = rootProbe(ReadOnlyProbe.CURRENT_SLOT)
    val observedCount = rootProbe(ReadOnlyProbe.SLOT_COUNT)
    val malformedIndex = rootGranted && successful(ReadOnlyProbe.CURRENT_SLOT) != null && observedIndex !in setOf("0", "1")
    val malformedCount = rootGranted && successful(ReadOnlyProbe.SLOT_COUNT) != null && observedCount != "2"
    val slot = if (!malformedIndex && !malformedCount && (observedIndex.isBlank() || observedIndex == expectedIndex) && (observedCount.isBlank() || observedCount == "2")) candidateSlot else ""

    val version = rootProbe(ReadOnlyProbe.MAGISK_VERSION)
    val versionName = version.substringBefore(':')
    val stableVersionFormat = Regex("[1-9][0-9]?\\.[0-9](?::MAGISK:R)?").matches(version)
    val versionParts = versionName.split('.').mapNotNull { it.toIntOrNull() }
    val code = rootProbe(ReadOnlyProbe.MAGISK_CODE).takeIf { it.matches(Regex("[1-9][0-9]{3,5}")) }?.toIntOrNull() ?: 0
    val consistentCode = stableVersionFormat && versionParts.size == 2 && code == versionParts[0] * 1000 + versionParts[1] * 100

    val partitionLines = rootProbe(ReadOnlyProbe.PARTITIONS).lineSequence().filter { it.isNotBlank() }.toList()
    val partitionPattern = Regex("(?:init_boot|vendor_boot|boot)_[ab]=[0-9]+")
    val partitionNames = partitionLines.map { it.substringBefore('=') }
    val partitionsValid = partitionLines.all { line -> partitionPattern.matches(line) && line.substringAfter('=').toLongOrNull()?.let { it in 1..536_870_912L } == true } &&
        partitionNames.distinct().size == partitionNames.size
    val modulesProbe = probes[ReadOnlyProbe.MODULES.name]
    val modules = rootProbe(ReadOnlyProbe.MODULES).lineSequence().filter { it.isNotBlank() }.toList()
    val modulesValid = modules.all { it.matches(Regex("[A-Za-z0-9._-]{1,128}\\|(?:enabled|disabled)(?:\\|pending-removal)?")) } &&
        modules.map { it.substringBefore('|') }.distinct().size == modules.size

    val success = probes[(if (slot == "_a") ReadOnlyProbe.SLOT_A_SUCCESS else ReadOnlyProbe.SLOT_B_SUCCESS).name]
    val toolBootSuccessful = if (!rootGranted || slot.isBlank() || success == null || success.timedOut || success.truncated || success.failure != null) null
        else when {
            success.exitCode == 0 && success.stdout.trim() == "1" -> true
            success.exitCode == 70 && success.stdout.trim() == "0" -> false
            else -> null
        }
    val vbmetaUnlocked = when (property("ro.boot.vbmeta.device_state")) { "unlocked" -> true; "locked" -> false; else -> null }
    val flashUnlocked = when (property("ro.boot.flash.locked")) { "0" -> true; "1" -> false; else -> null }
    val lockObservations = listOfNotNull(vbmetaUnlocked, flashUnlocked).distinct()
    val directBoot = if (rootGranted && bootControl?.slotCount == 2 && slot in setOf("_a", "_b") && bootControl.recordedSlotSuffix in setOf("", slot)) {
        bootControl.slots[if (slot == "_a") 0 else 1].let { it.markedSuccessful && !it.verityCorrupted }
    } else null
    val bootSuccessful = if (toolBootSuccessful != null && directBoot != null && toolBootSuccessful != directBoot) null else toolBootSuccessful ?: directBoot
    val kernelUnlocked = if (rootGranted) when (kernelBootConfig["vbmeta.device_state"]) { "unlocked" -> true; "locked" -> false; else -> null } else null
    val kernelVerifiedBoot = if (rootGranted) kernelBootConfig["verifiedbootstate"]?.takeIf { it in setOf("orange", "green", "yellow", "red") }.orEmpty() else ""
    val securityMismatch = (kernelUnlocked != null && lockObservations.any { it != kernelUnlocked }) ||
        (kernelVerifiedBoot.isNotBlank() && property("ro.boot.verifiedbootstate").let { it.isNotBlank() && it != kernelVerifiedBoot })
    return DeviceState(
        manufacturer = property("ro.product.manufacturer"), model = if (compatibleModel) "XT2607-1" else model.takeUnless { it == "XT2607-1" }.orEmpty(),
        device = property("ro.product.device"), channel = channels.singleOrNull().orEmpty(),
        androidVersion = property("ro.build.version.release"), build = property("ro.build.id"),
        fingerprint = property("ro.build.fingerprint"), vendorFingerprint = property("ro.vendor.build.fingerprint"),
        securityPatch = property("ro.build.version.security_patch"), slot = slot,
        rootGranted = rootGranted, rootChecked = rootChecked,
        magiskVersion = version.takeIf { stableVersionFormat }.orEmpty(), magiskCode = code.takeIf { consistentCode } ?: 0,
        virtualAb = when (property("ro.virtual_ab.enabled")) { "true" -> true; "false" -> false; else -> null },
        snapshot = if (rootGranted) runCatching { SnapshotState.valueOf(snapshot.state) }.getOrDefault(SnapshotState.UNKNOWN) else SnapshotState.UNKNOWN,
        engine = if (rootGranted) runCatching { EngineState.valueOf(engine) }.getOrDefault(EngineState.UNKNOWN) else EngineState.UNKNOWN,
        batteryPercent = property("batteryPercent").toIntOrNull()?.takeIf { it in 0..100 } ?: -1,
        freeBytes = property("freeBytes").toLongOrNull()?.takeIf { it >= 0 } ?: -1,
        bootId = probe(ReadOnlyProbe.BOOT_ID).takeIf { it.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) }.orEmpty(),
        partitions = if (partitionsValid) partitionNames else emptyList(),
        modules = if (modulesValid) modules else emptyList(), capturedAtMillis = inspectedAtMillis,
        bootSuccessful = bootSuccessful, bootloaderUnlocked = kernelUnlocked ?: lockObservations.singleOrNull(),
        verifiedBootState = kernelVerifiedBoot.ifBlank { property("ro.boot.verifiedbootstate") }, incrementalBuild = property("ro.build.version.incremental"),
        buildTimestamp = property("ro.build.date.utc").toLongOrNull()?.takeIf { it > 0 } ?: 0,
        moduleInventoryKnown = rootGranted && modulesProbe?.successful == true && modulesValid,
        securityPropertyMismatch = securityMismatch,
        securityEvidenceSource = if (kernelVerifiedBoot.isNotBlank() && kernelUnlocked != null) "KERNEL_BOOTCONFIG" else "ANDROID_PROPERTIES"
    )
}
