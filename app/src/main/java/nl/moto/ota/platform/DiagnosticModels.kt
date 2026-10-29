package nl.moto.ota.platform

import nl.moto.ota.core.BackupValidation
import nl.moto.ota.core.BootControlMetadata
import nl.moto.ota.core.SnapshotFileMetadata
import nl.moto.ota.core.VirtualAbMetadata

/** Evidence is an observation, not permission to write partitions. */
data class ProbeResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val failure: String? = null
) {
    val successful: Boolean get() = exitCode == 0 && !timedOut && !truncated && failure == null
}

data class BackupCandidate(
    val directoryId: String,
    val gzipBytes: Long?,
    val uncompressedBytes: Long?,
    val sha256: String?,
    val sha1: String?,
    val androidBootHeader: Boolean,
    val errors: List<String>,
    // Even a matching Magisk SHA1 says nothing about current build or boot/init_boot origin.
    val provenanceVerified: Boolean = false,
    val validation: BackupValidation? = null
)

data class SnapshotObservation(
    val state: String,
    val mergePercent: Double? = null,
    val evidence: List<String> = emptyList()
)

data class Inspection(
    val properties: Map<String, String>,
    val probes: Map<String, ProbeResult>,
    val backupInventory: List<BackupCandidate>,
    val snapshot: SnapshotObservation,
    val engine: String,
    val inspectedAtMillis: Long,
    val rootRequested: Boolean,
    val counters: Map<String, String> = emptyMap(),
    /** Sensitive signed URL. Never include this value in reports or logs. */
    val otaWifiUrl: String? = null,
    val otaMetadata: MotorolaMetadataObservation = MotorolaMetadataObservation(),
    val kernelBootConfig: Map<String, String> = emptyMap(),
    val bootControl: BootControlMetadata? = null,
    val virtualAbMetadata: VirtualAbMetadata? = null,
    val snapshotFileMetadata: SnapshotFileMetadata? = null
)

/** Complete command allowlist. There is deliberately no public raw-shell entry point. */
enum class ReadOnlyProbe(val command: String, val requiresRoot: Boolean, val timeoutMillis: Long = 6_000,
                         val globalMountNamespace: Boolean = false, val enabled: Boolean = true) {
    ROOT_ID("id", true),
    BOOT_CONFIG("grep -E '^androidboot\\.(verifiedbootstate|vbmeta\\.device_state|flash\\.locked|slot_suffix) ' /proc/bootconfig", true),
    MISC_BOOT_CONTROL("od -An -v -tx1 -j 2048 -N 32 /dev/block/by-name/misc", true),
    MISC_VIRTUAL_AB("od -An -v -tx1 -j 32768 -N 64 /dev/block/by-name/misc", true),
    SNAPSHOT_STATE_FILE("if [ -f /metadata/ota/state ]; then n=\$(stat -c %s /metadata/ota/state) || exit; [ \"\$n\" -le 65536 ] || exit 65; printf 'bytes=%s\\n' \"\$n\"; od -An -v -tx1 /metadata/ota/state; stat -c 'after=%s' /metadata/ota/state; else exit 66; fi", true),
    SNAPSHOT_DIRECTORY("if [ -d /metadata/ota/snapshots ]; then find /metadata/ota/snapshots -mindepth 1 -maxdepth 1 -type f -print; else exit 66; fi", true),
    MAGISK_VERSION("magisk -v", true),
    MAGISK_CODE("magisk -V", true),
    AUTO_UPDATE_SETTING("/system/bin/settings get global ota_disable_automatic_update", true),
    ACTIVE_INIT_BOOT_HASH("s=\$(/system/bin/getprop ro.boot.slot_suffix); case \"\$s\" in _a|_b) /system/bin/sha256sum \"/dev/block/by-name/init_boot\$s\";; *) exit 65;; esac", true),
    // AOSP BootControl::Init can repair invalid metadata even for getter commands.
    // Keep evidence keys for decoding old reports/tests, but never launch bootctl.
    // This profile uses read-only CRC-checked misc metadata plus kernel properties.
    CURRENT_SLOT("", true, enabled = false),
    SLOT_COUNT("", true, enabled = false),
    NEXT_SLOT("", true, enabled = false),
    SLOT_A_SUCCESS("", true, enabled = false),
    SLOT_B_SUCCESS("", true, enabled = false),
    SNAPSHOT_HAL("", true, enabled = false),
    SNAPSHOT_DUMP("snapshotctl dump", true),
    // --follow only subscribes to callbacks. Never use --merge, --update or reset flags.
    ENGINE_FOLLOW("/system/bin/toybox timeout 3 /system/bin/update_engine_client --follow", true, 5_000),
    // Magisk 31.1 --target 0 enters the global mount namespace for this read-only
    // child only. It does not change app isolation, root policy or stored settings.
    OTA_PREFS("cat /data/user/0/com.motorola.ccc.ota/shared_prefs/ota_prefs.xml", true, globalMountNamespace = true),
    DOWNLOAD_PREFS("cat /data/user/0/com.motorola.ccc.ota/shared_prefs/dl_prefs.xml", true, globalMountNamespace = true),
    PARTITIONS("for p in /dev/block/by-name/boot_a /dev/block/by-name/boot_b /dev/block/by-name/init_boot_a /dev/block/by-name/init_boot_b /dev/block/by-name/vendor_boot_a /dev/block/by-name/vendor_boot_b; do if [ -b \"\$p\" ]; then printf '%s=' \"\${p##*/}\"; blockdev --getsize64 \"\$p\"; fi; done", true),
    BACKUPS("for d in /data/magisk_backup_*; do if [ -d \"\$d\" ] && [ -f \"\$d/boot.img.gz\" ]; then printf '%s|' \"\${d##*/}\"; wc -c < \"\$d/boot.img.gz\"; fi; done", true),
    MODULES("for d in /data/adb/modules/*; do if [ -d \"\$d\" ]; then printf '%s|' \"\${d##*/}\"; if [ -f \"\$d/disable\" ]; then printf 'disabled'; else printf 'enabled'; fi; if [ -f \"\$d/remove\" ]; then printf '|pending-removal'; fi; printf '\\n'; fi; done", true),
    BOOT_ID("cat /proc/sys/kernel/random/boot_id", false)
}
