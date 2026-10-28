package nl.moto.ota.platform

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
    val provenanceVerified: Boolean = false
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
    val otaWifiUrl: String? = null
)

/** Complete command allowlist. There is deliberately no public raw-shell entry point. */
enum class ReadOnlyProbe(val command: String, val requiresRoot: Boolean, val timeoutMillis: Long = 6_000) {
    ROOT_ID("id", true),
    MAGISK_VERSION("magisk -v", true),
    MAGISK_CODE("magisk -V", true),
    CURRENT_SLOT("bootctl get-current-slot", true),
    SLOT_COUNT("bootctl get-number-slots", true),
    NEXT_SLOT("bootctl get-active-boot-slot", true),
    SLOT_A_SUCCESS("bootctl is-slot-marked-successful 0", true),
    SLOT_B_SUCCESS("bootctl is-slot-marked-successful 1", true),
    SNAPSHOT_HAL("bootctl get-snapshot-merge-status", true),
    SNAPSHOT_DUMP("snapshotctl dump", true),
    // --follow only subscribes to callbacks. Never use --merge, --update or reset flags.
    ENGINE_FOLLOW("/system/bin/toybox timeout 3 /system/bin/update_engine_client --follow", true, 5_000),
    OTA_PREFS("cat /data/user/0/com.motorola.ccc.ota/shared_prefs/ota_prefs.xml", true),
    DOWNLOAD_PREFS("cat /data/user/0/com.motorola.ccc.ota/shared_prefs/dl_prefs.xml", true),
    PARTITIONS("for p in /dev/block/by-name/boot_a /dev/block/by-name/boot_b /dev/block/by-name/init_boot_a /dev/block/by-name/init_boot_b /dev/block/by-name/vendor_boot_a /dev/block/by-name/vendor_boot_b; do if [ -b \"\$p\" ]; then printf '%s=' \"\${p##*/}\"; blockdev --getsize64 \"\$p\"; fi; done", true),
    BACKUPS("for d in /data/magisk_backup_*; do if [ -d \"\$d\" ] && [ -f \"\$d/boot.img.gz\" ]; then printf '%s|' \"\${d##*/}\"; wc -c < \"\$d/boot.img.gz\"; fi; done", true),
    MODULES("for d in /data/adb/modules/*; do if [ -d \"\$d\" ]; then printf '%s|' \"\${d##*/}\"; if [ -f \"\$d/disable\" ]; then printf 'disabled'; else printf 'enabled'; fi; if [ -f \"\$d/remove\" ]; then printf '|pending-removal'; fi; printf '\\n'; fi; done", true),
    BOOT_ID("cat /proc/sys/kernel/random/boot_id", false)
}
