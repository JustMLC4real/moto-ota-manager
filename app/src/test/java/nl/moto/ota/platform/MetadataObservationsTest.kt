package nl.moto.ota.platform

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

class MetadataObservationsTest {
    private fun ok(text: String) = ProbeResult(0, text, "")
    private val none = SnapshotFileMetadata(SnapshotState.NONE, SnapshotMetadataFormat.PROTOBUF, false)
    private val vabNone = VirtualAbMetadata(VirtualAbMergeStatus.NONE, 1)

    @Test fun existingEmptyProtoIsNoneButMissingOrDeniedReadIsUnknown() {
        assertEquals(SnapshotState.NONE, MetadataObservations.snapshotFile(ok("bytes=0\nafter=0\n"))?.state)
        assertNull(MetadataObservations.snapshotFile(null))
        assertNull(MetadataObservations.snapshotFile(ProbeResult(1, "bytes=0\nafter=0", "denied")))
    }
    @Test fun unstableOrPartialSnapshotFileCannotBecomeIdle() {
        listOf("bytes=0\nafter=2", "bytes=2\n08\nafter=2", "bytes=65537\nafter=65537", "bytes=0").forEach {
            assertNull(MetadataObservations.snapshotFile(ok(it)))
        }
    }
    @Test fun hexReaderRejectsCompressionMarkersAndUnexpectedBytes() {
        assertNull(MetadataObservations.hex(ok("00\n*"), 64))
        assertNull(MetadataObservations.hex(ok("00 ff diagnostic"), 2))
        assertArrayEquals(byteArrayOf(0, -1), MetadataObservations.hex(ok("00 ff"), 2))
    }
    @Test fun directIdleRequiresConsistentVabAndEmptyReadableSnapshotDirectory() {
        assertEquals("NONE", MetadataObservations.combineSnapshot(SnapshotObservation("UNKNOWN"), none, vabNone, "_b", ok(""), true).state)
        assertEquals("UNKNOWN", MetadataObservations.combineSnapshot(SnapshotObservation("UNKNOWN"), none, vabNone, "_b", null, true).state)
        assertEquals("UNKNOWN", MetadataObservations.combineSnapshot(SnapshotObservation("UNKNOWN"), none, vabNone, "_b", ok("system_b"), true).state)
    }
    @Test fun currentMergeAndFailureAreNeverHiddenByHalNone() {
        val merging = none.copy(state = SnapshotState.MERGING)
        assertEquals("UNKNOWN", MetadataObservations.combineSnapshot(SnapshotObservation("UNKNOWN"), merging, vabNone, "_b", ok(""), true).state)
        val failed = none.copy(state = SnapshotState.FAILED)
        assertEquals("FAILED", MetadataObservations.combineSnapshot(SnapshotObservation("UNKNOWN"), failed,
            VirtualAbMetadata(VirtualAbMergeStatus.MERGING, 1), "_b", ok(""), true).state)
    }
    @Test fun contradictorySuccessfulToolsCannotBeOverriddenByIdleFiles() {
        assertEquals("UNKNOWN", MetadataObservations.combineSnapshot(SnapshotObservation("UNKNOWN"), none, vabNone, "_b", ok(""), true, true).state)
        assertEquals("UNKNOWN", MetadataObservations.combineSnapshot(SnapshotObservation("MERGING"), none, vabNone, "_b", ok(""), true).state)
    }
    @Test fun kernelFactsAreStrictAndDuplicateOrUnknownKeysAreRejected() {
        val facts = MetadataObservations.kernelConfig(ok("androidboot.vbmeta.device_state = \"unlocked\"\nandroidboot.verifiedbootstate = \"orange\"\nandroidboot.slot_suffix = \"_b\"\n"))
        assertEquals("orange", facts["verifiedbootstate"])
        assertEquals("_b", facts["slot_suffix"])
        assertTrue(MetadataObservations.kernelConfig(ok("androidboot.serialno = \"private\"")).isEmpty())
        assertTrue(MetadataObservations.kernelConfig(ok("androidboot.slot_suffix = \"_a\"\nandroidboot.slot_suffix = \"_b\"")).isEmpty())
    }
    private fun callback(status: String = "IDLE", code: Int = 0, percent: String = "0.0", completion: String = "null", nanos: String = "100") =
        "{\"schema\":1,\"status\":\"$status\",\"statusCode\":$code,\"percent\":$percent,\"completionCode\":$completion,\"source\":\"UpdateEngine.bind(callback)\",\"checkedNanos\":$nanos,\"reason\":\"\"}"
    @Test fun realCallbackSchemaAcceptsOnlyConsistentCurrentStatus() {
        assertEquals("IDLE", MetadataObservations.engineCallback(ok(callback())))
        assertEquals("UNKNOWN", MetadataObservations.engineCallback(ok(callback(code = 6))))
        assertEquals("UNKNOWN", MetadataObservations.engineCallback(ok(callback(nanos = "0"))))
        assertEquals("UNKNOWN", MetadataObservations.engineCallback(ok(callback(percent = "101.0"))))
        assertEquals("UNKNOWN", MetadataObservations.engineCallback(ok(callback() + "\n" + callback())))
    }
    @Test fun callbackErrorCannotBeHiddenByFollowingIdleOrMalformedProgress() {
        assertEquals("ERROR", MetadataObservations.engineCallback(ok(callback(completion = "5"))))
        assertEquals("UNKNOWN", MetadataObservations.engineCallback(ok(callback(percent = "NaN"))))
        assertEquals("UNKNOWN", MetadataObservations.engineCallback(ProbeResult(1, callback(), "")))
    }
    @Test(expected = IllegalArgumentException::class) fun rootHelperRejectsApkPathInjectionBeforeExecution() {
        ReadOnlyShell().engineCallback("/data/app/base.apk'; reboot; '")
    }
    @Test fun onlyTheTwoFixedUpdaterReadsUseTheGlobalNamespace() {
        assertEquals(setOf(ReadOnlyProbe.OTA_PREFS, ReadOnlyProbe.DOWNLOAD_PREFS), ReadOnlyProbe.entries.filter { it.globalMountNamespace }.toSet())
        assertTrue(ReadOnlyProbe.entries.filter { it.globalMountNamespace }.all { it.command.startsWith("cat /data/user/0/com.motorola.ccc.ota/shared_prefs/") })
    }
    @Test(expected = IllegalArgumentException::class) fun privilegedPropertyStillRejectsInjectionBeforeExecution() {
        ReadOnlyShell().property("ro.virtual_ab.enabled; reboot", asRoot = true)
    }
    @Test(expected = IllegalArgumentException::class) fun signatureProbeRejectsTraversalBeforeRootExecution() {
        ReadOnlyShell().firmwareSignature("/data/app/valid/base.apk", "/sdcard/Android/data/com.motorola.ccc.ota/files/../../other.zip")
    }
}
