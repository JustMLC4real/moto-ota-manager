package nl.moto.ota.core

import java.util.zip.CRC32

enum class SnapshotMetadataFormat { PROTOBUF, LEGACY_TEXT }

data class SnapshotFileMetadata(
    val state: SnapshotState,
    val format: SnapshotMetadataFormat,
    val explicitState: Boolean
)

data class BootSlotMetadata(
    val priority: Int,
    val triesRemaining: Int,
    val successfulBoot: Boolean,
    val verityCorrupted: Boolean
) {
    // This is the AOSP BootControl::IsSlotMarkedSuccessful condition.
    val markedSuccessful: Boolean get() = successfulBoot && triesRemaining > 0
}

data class BootControlMetadata(
    /** May be empty on an OEM implementation; it never substitutes for the running slot. */
    val recordedSlotSuffix: String,
    val slots: List<BootSlotMetadata>
) {
    val slotCount: Int get() = slots.size
}

enum class VirtualAbMergeStatus { NONE, UNKNOWN, SNAPSHOTTED, MERGING, CANCELLED }

data class VirtualAbMetadata(val mergeStatus: VirtualAbMergeStatus, val sourceSlot: Int) {
    /** Matches AOSP GetMiscVirtualAbMergeStatus, including its source-slot rule. */
    fun effectiveMergeStatus(currentSlot: Int): VirtualAbMergeStatus = when {
        currentSlot !in 0..1 -> VirtualAbMergeStatus.UNKNOWN
        mergeStatus == VirtualAbMergeStatus.SNAPSHOTTED && currentSlot == sourceSlot -> VirtualAbMergeStatus.NONE
        else -> mergeStatus
    }
}

/**
 * Pure parsers for bytes already read from known metadata locations. They open no files,
 * call no service and never repair or initialize metadata. A failed read must remain a
 * failed read: only an existing, completely read zero-byte state file is a valid empty
 * proto3 SnapshotUpdateStatus (whose default state is None).
 *
 * Snapshot schema: AOSP fs_mgr 157112fcbaa00b6e84c63146cfde249c56cd652c,
 * libsnapshot/android/snapshot/snapshot.proto and SnapshotManager::ReadSnapshotUpdateStatus.
 * Boot layouts/CRC/HAL semantics: AOSP recovery cbb0b54b1253bcf5d29fb0b29441f8f821a061b6,
 * bootloader_message.h and boot_control/libboot_control.cpp. The boot-control layout is
 * optional for OEMs; absent magic, invalid CRC or unsupported version yields null.
 */
object ReadOnlyMetadata {
    private const val MAX_SNAPSHOT_BYTES = 65_536
    private val snapshotStates = listOf(SnapshotState.NONE, SnapshotState.INITIATED, SnapshotState.UNVERIFIED,
        SnapshotState.MERGING, SnapshotState.MERGE_NEEDS_REBOOT, SnapshotState.COMPLETE,
        SnapshotState.FAILED, SnapshotState.CANCELLED)
    private val legacyStates = listOf("none", "initiated", "unverified", "merging", "merge-needs-reboot",
        "merge-completed", "merge-failed", "cancelled").zip(snapshotStates).toMap()

    fun parseSnapshotState(bytes: ByteArray): SnapshotFileMetadata? {
        if (bytes.size > MAX_SNAPSHOT_BYTES) return null
        legacyStates[bytes.toString(Charsets.US_ASCII)]?.let {
            return SnapshotFileMetadata(it, SnapshotMetadataFormat.LEGACY_TEXT, true)
        }
        return runCatching {
            val input = ProtoInput(bytes)
            var state = SnapshotState.NONE
            var explicitState = false
            while (input.remaining > 0) {
                val tag = input.varint()
                require(tag in 1..0xffff_ffffL)
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 7).toInt()
                require(field in 1..0x1fff_ffff)
                // The state field is an enum varint. Other known fields are uint/bool
                // varints, except the source fingerprint (field 8, length-delimited).
                if (field in 1..18 && field != 11) require(wireType == if (field == 8) 2 else 0)
                when (wireType) {
                    0 -> {
                        val value = input.varint()
                        if (field == 1) {
                            require(!explicitState && value in 0L..7L)
                            state = snapshotStates[value.toInt()]
                            explicitState = true
                        }
                    }
                    1 -> input.skip(8)
                    2 -> {
                        val length = input.varint()
                        require(length in 0..input.remaining.toLong())
                        input.skip(length.toInt())
                    }
                    5 -> input.skip(4)
                    else -> error("Unsupported or malformed protobuf wire type")
                }
            }
            SnapshotFileMetadata(state, SnapshotMetadataFormat.PROTOBUF, explicitState)
        }.getOrNull()
    }

    /** Exactly the 32 bytes at the standard misc boot-control offset (2048). */
    fun parseBootControl(bytes: ByteArray): BootControlMetadata? {
        if (bytes.size != 32 || le32(bytes, 4) != 0x42414342L || unsigned(bytes[8]) != 1) return null
        if (CRC32().apply { update(bytes, 0, 28) }.value != le32(bytes, 28)) return null
        val count = unsigned(bytes[9]) and 7
        if (count !in 1..4) return null
        val suffixBytes = bytes.copyOfRange(0, 4)
        val suffix = if (suffixBytes.all { it == 0.toByte() }) "" else {
            if (suffixBytes[0] != '_'.code.toByte() || suffixBytes[1].toInt() !in 'a'.code..'d'.code ||
                suffixBytes[2] != 0.toByte() || suffixBytes[3] != 0.toByte()) return null
            suffixBytes.copyOfRange(0, 2).toString(Charsets.US_ASCII)
        }
        if (suffix.isNotEmpty() && suffix[1] - 'a' >= count) return null
        val slots = (0 until count).map { index ->
            val flags = unsigned(bytes[12 + index * 2])
            BootSlotMetadata(flags and 15, (flags ushr 4) and 7, flags and 128 != 0,
                unsigned(bytes[13 + index * 2]) and 1 != 0)
        }
        return BootControlMetadata(suffix, slots)
    }

    /** Exactly the 64-byte misc_virtual_ab_message at the standard misc offset 32768. */
    fun parseVirtualAb(bytes: ByteArray): VirtualAbMetadata? {
        if (bytes.size != 64 || unsigned(bytes[0]) != 2 || le32(bytes, 1) != 0x56740ab0L) return null
        val status = unsigned(bytes[5])
        val sourceSlot = unsigned(bytes[6])
        if (status !in 0..4 || sourceSlot !in 0..1) return null
        return VirtualAbMetadata(VirtualAbMergeStatus.entries[status], sourceSlot)
    }

    private fun unsigned(value: Byte) = value.toInt() and 255
    private fun le32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { result, index ->
        result or (unsigned(bytes[offset + index]).toLong() shl (index * 8))
    }

    private class ProtoInput(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size - position
        fun skip(count: Int) {
            require(count in 0..remaining)
            position += count
        }
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                require(remaining > 0)
                val next = unsigned(bytes[position++])
                require(index != 9 || next <= 1)
                value = value or ((next and 127).toLong() shl (index * 7))
                if (next and 128 == 0) return value
            }
            error("Varint exceeds 64 bits")
        }
    }
}
