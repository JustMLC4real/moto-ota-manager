package nl.moto.ota.core

import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class ReadOnlyMetadataTest {
    // Actual read-only XT2607-1 metadata captured 2026-10-28, without identifiers.
    private val phoneBootControl = hex("00000000424341420102000007009f000000000000000000000000006fc8849e")
    private val phoneVirtualAb = hex("02b00a74560001") + ByteArray(57)

    @Test fun existingEmptyPhoneStateIsProto3DefaultNoneRatherThanMissingEvidence() {
        val actual = ReadOnlyMetadata.parseSnapshotState(byteArrayOf())!!
        assertEquals(SnapshotState.NONE, actual.state)
        assertEquals(SnapshotMetadataFormat.PROTOBUF, actual.format)
        assertFalse(actual.explicitState)
    }

    @Test fun allKnownProtobufGlobalEnumsAreDecoded() {
        val expected = listOf(SnapshotState.NONE, SnapshotState.INITIATED, SnapshotState.UNVERIFIED,
            SnapshotState.MERGING, SnapshotState.MERGE_NEEDS_REBOOT, SnapshotState.COMPLETE,
            SnapshotState.FAILED, SnapshotState.CANCELLED)
        expected.forEachIndexed { value, state ->
            val result = ReadOnlyMetadata.parseSnapshotState(byteArrayOf(8, value.toByte()))!!
            assertEquals(state, result.state)
            assertTrue(result.explicitState)
        }
    }

    @Test fun legacyStateFormatIsExactAndDoesNotTrimMalformedTextIntoNone() {
        assertEquals(SnapshotState.MERGE_NEEDS_REBOOT,
            ReadOnlyMetadata.parseSnapshotState("merge-needs-reboot".toByteArray())!!.state)
        listOf("None", "none\n", "none garbage", "future-state").forEach {
            assertNull(it, ReadOnlyMetadata.parseSnapshotState(it.toByteArray()))
        }
    }

    @Test fun completeMessageIsValidatedAfterTheStateField() {
        // state=Merging, sectors_allocated=150, fingerprint="test", userspace_snapshots=true.
        val full = hex("08031096014204746573744801")
        assertEquals(SnapshotState.MERGING, ReadOnlyMetadata.parseSnapshotState(full)!!.state)
        assertNull(ReadOnlyMetadata.parseSnapshotState(full.copyOf(full.size - 1)))
        assertNull(ReadOnlyMetadata.parseSnapshotState(hex("0803420574657374")))
    }

    @Test fun unknownAdditionalFieldsCanBeSkippedWithoutChangingKnownState() {
        // Additional varint/fixed64/length-delimited/fixed32 fields 20..23.
        val bytes = hex("0803a00101a9010000000000000000b201020001bd0100000000")
        assertEquals(SnapshotState.MERGING, ReadOnlyMetadata.parseSnapshotState(bytes)!!.state)
    }

    @Test fun malformedAmbiguousAndUnknownStateEvidenceNeverBecomesNone() {
        listOf("0808", "08000803", "08000800", "0a00", "00", "08", "0880",
            "08034080", "0b", "0803a90100", "08ffffffffffffffffff02").forEach {
            assertNull(it, ReadOnlyMetadata.parseSnapshotState(hex(it)))
        }
        assertNull(ReadOnlyMetadata.parseSnapshotState(ByteArray(65_537)))
    }

    @Test fun phoneBootControlCrcAndSlotBSuccessfulFlagAreDecodedWithUnusedSuffix() {
        val result = ReadOnlyMetadata.parseBootControl(phoneBootControl)!!
        assertEquals("", result.recordedSlotSuffix)
        assertEquals(2, result.slotCount)
        assertEquals(7, result.slots[0].priority)
        assertFalse(result.slots[0].markedSuccessful)
        assertEquals(15, result.slots[1].priority)
        assertEquals(1, result.slots[1].triesRemaining)
        assertTrue(result.slots[1].successfulBoot)
        assertTrue(result.slots[1].markedSuccessful)
        assertFalse(result.slots[1].verityCorrupted)
    }

    @Test fun invalidBootControlBytesAreNeverRepairedOrAccepted() {
        val original = phoneBootControl.copyOf()
        assertNull(ReadOnlyMetadata.parseBootControl(phoneBootControl.copyOf(31)))
        assertNull(ReadOnlyMetadata.parseBootControl(phoneBootControl + 0))
        assertNull(ReadOnlyMetadata.parseBootControl(phoneBootControl.copyOf().apply { this[4] = 0 }))
        assertNull(ReadOnlyMetadata.parseBootControl(phoneBootControl.copyOf().apply { this[14] = 0 }))
        assertNull(ReadOnlyMetadata.parseBootControl(withCrc(phoneBootControl.copyOf().apply { this[8] = 2 })))
        assertNull(ReadOnlyMetadata.parseBootControl(withCrc(phoneBootControl.copyOf().apply { this[9] = 0 })))
        assertNull(ReadOnlyMetadata.parseBootControl(withCrc(phoneBootControl.copyOf().apply { this[0] = 'x'.code.toByte() })))
        assertArrayEquals(original, phoneBootControl)
    }

    @Test fun recordedSuffixIsOptionalAndNeverOverridesCurrentSlot() {
        val bytes = withCrc(phoneBootControl.copyOf().apply { this[0] = '_'.code.toByte(); this[1] = 'a'.code.toByte() })
        assertEquals("_a", ReadOnlyMetadata.parseBootControl(bytes)!!.recordedSlotSuffix)
        assertTrue(ReadOnlyMetadata.parseBootControl(bytes)!!.slots[1].markedSuccessful)
    }

    @Test fun successfulBitWithoutRemainingTriesDoesNotMatchAospBootSuccess() {
        val bytes = withCrc(phoneBootControl.copyOf().apply { this[14] = 0x8f.toByte() })
        val slot = ReadOnlyMetadata.parseBootControl(bytes)!!.slots[1]
        assertTrue(slot.successfulBoot)
        assertEquals(0, slot.triesRemaining)
        assertFalse(slot.markedSuccessful)
    }

    @Test fun phoneVirtualAbMessageHasKnownVersionMagicAndNoMerge() {
        val result = ReadOnlyMetadata.parseVirtualAb(phoneVirtualAb)!!
        assertEquals(VirtualAbMergeStatus.NONE, result.mergeStatus)
        assertEquals(1, result.sourceSlot)
        assertEquals(VirtualAbMergeStatus.NONE, result.effectiveMergeStatus(1))
    }

    @Test fun virtualAbSnapshotSourceRuleMatchesTheAospHal() {
        val bytes = phoneVirtualAb.copyOf().apply { this[5] = 2 }
        val result = ReadOnlyMetadata.parseVirtualAb(bytes)!!
        assertEquals(VirtualAbMergeStatus.SNAPSHOTTED, result.mergeStatus)
        assertEquals(VirtualAbMergeStatus.NONE, result.effectiveMergeStatus(1))
        assertEquals(VirtualAbMergeStatus.SNAPSHOTTED, result.effectiveMergeStatus(0))
        assertEquals(VirtualAbMergeStatus.UNKNOWN, result.effectiveMergeStatus(2))
    }

    @Test fun invalidVirtualAbVersionMagicSizeAndEnumStayUnknown() {
        assertNull(ReadOnlyMetadata.parseVirtualAb(byteArrayOf()))
        assertNull(ReadOnlyMetadata.parseVirtualAb(phoneVirtualAb.copyOf(63)))
        assertNull(ReadOnlyMetadata.parseVirtualAb(phoneVirtualAb.copyOf().apply { this[0] = 1 }))
        assertNull(ReadOnlyMetadata.parseVirtualAb(phoneVirtualAb.copyOf().apply { this[1] = 0 }))
        assertNull(ReadOnlyMetadata.parseVirtualAb(phoneVirtualAb.copyOf().apply { this[5] = 5 }))
        assertNull(ReadOnlyMetadata.parseVirtualAb(phoneVirtualAb.copyOf().apply { this[6] = 2 }))
    }

    private fun withCrc(bytes: ByteArray): ByteArray {
        val crc = CRC32().apply { update(bytes, 0, 28) }.value
        for (index in 0..3) bytes[28 + index] = (crc ushr (index * 8)).toByte()
        return bytes
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
