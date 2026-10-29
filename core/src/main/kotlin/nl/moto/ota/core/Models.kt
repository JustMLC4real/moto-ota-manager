package nl.moto.ota.core

/** UNKNOWN is a safety state, never an alias for idle or completed. */
enum class SnapshotState { UNKNOWN, NONE, INITIATED, UNVERIFIED, MERGING, MERGE_NEEDS_REBOOT, FAILED, CANCELLED, COMPLETE, NOT_APPLICABLE }
enum class EngineState { UNKNOWN, IDLE, DOWNLOADING, VERIFYING, FINALIZING, UPDATED_NEED_REBOOT, ERROR }

/** Immutable observations. Defaults deliberately do not describe a compatible device. */
data class DeviceState(
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val channel: String = "",
    val androidVersion: String = "",
    val build: String = "",
    val fingerprint: String = "",
    val vendorFingerprint: String = "",
    val securityPatch: String = "",
    val slot: String = "",
    val rootGranted: Boolean = false,
    val magiskVersion: String = "",
    val magiskCode: Int = 0,
    val virtualAb: Boolean? = null,
    val snapshot: SnapshotState = SnapshotState.UNKNOWN,
    val engine: EngineState = EngineState.UNKNOWN,
    val batteryPercent: Int = -1,
    val freeBytes: Long = -1,
    val bootId: String = "",
    val partitions: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val capturedAtMillis: Long = 0,
    val simulated: Boolean = false,
    val bootSuccessful: Boolean? = null,
    val bootloaderUnlocked: Boolean? = null,
    val verifiedBootState: String = "",
    val incrementalBuild: String = "",
    val buildTimestamp: Long = 0,
    val moduleInventoryKnown: Boolean = false,
    val rootChecked: Boolean = false,
    val securityPropertyMismatch: Boolean = false,
    val securityEvidenceSource: String = "ANDROID_PROPERTIES",
    val sdkInt: Int = 0,
    val abUpdate: Boolean? = null,
    val partitionInventoryKnown: Boolean = false,
    /** Categorical conflicts only; raw property values remain in private diagnostics. */
    val identityConflicts: List<String> = emptyList()
) {
    val inactiveSlot: String get() = when (slot) { "_a" -> "_b"; "_b" -> "_a"; else -> "" }
    val exactProfileMatch: Boolean get() = manufacturer.equals("motorola", true) &&
        model == "XT2607-1" && device == "merc" && channel == "reteu"
}

data class CheckResult(val code: String, val message: String,
                       val presentationKey: String? = null, val presentationArgs: List<String> = emptyList())
