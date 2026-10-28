package nl.moto.ota.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class ObservationParsersTest {
    private fun ok(text: String) = ProbeResult(0, text, "")

    @Test fun freshNoneFromBothSourcesIsIdleSnapshotEvidence() {
        assertEquals("NONE", ObservationParsers.snapshot(ok("Update state: 0\n"), ok("none\n")).state)
    }

    @Test fun perPartitionStateDoesNotMasqueradeAsGlobalState() {
        assertEquals("UNKNOWN", ObservationParsers.snapshot(ok("SnapshotState: 0\nstate: 0\n"), null).state)
    }

    @Test fun mergingUsesOnlyGlobalEnumAndBoundsPercentage() {
        val value = ObservationParsers.snapshot(ok("Update state: 3\nMerge completion: 27.5%\n"), ok("merging"))
        assertEquals("MERGING", value.state)
        assertEquals(27.5, value.mergePercent!!, 0.001)
        assertNull(ObservationParsers.snapshot(ok("Update state: 3\nMerge completion: 101%\n"), null).mergePercent)
    }

    @Test fun conflictingIdleAndMergeEvidenceFailsClosed() {
        assertEquals("UNKNOWN", ObservationParsers.snapshot(ok("Update state: 3"), ok("none")).state)
    }

    @Test fun unsupportedOrIncompleteSnapshotEvidenceDoesNotBecomeIdle() {
        assertEquals("UNKNOWN", ObservationParsers.snapshot(ProbeResult(1, "Update state: 0", "denied"), null).state)
        assertEquals("UNKNOWN", ObservationParsers.snapshot(ok("Update state: 99"), ok("none")).state)
        assertEquals("UNKNOWN", ObservationParsers.snapshot(ok("Update state: 0\nUpdate state: 3"), null).state)
        assertEquals("UNKNOWN", ObservationParsers.snapshot(ProbeResult(0, "Update state: 0", "", truncated = true), null).state)
    }

    @Test fun malformedOrDuplicateSuccessfulDumpCannotBeHiddenByHalNone() {
        listOf("Update state: 0\nUpdate state: 3", "Update state: 0\nUpdate state: 0", "Update state: 0\nUpdate state: invalid extra",
            "Update state: invalid extra", "Future dump format without global update state").forEach {
            assertEquals("UNKNOWN", ObservationParsers.snapshot(ok(it), ok("none")).state)
        }
    }

    @Test fun failedSnapshotToolCanStillUseKnownHalValue() {
        assertEquals("NONE", ObservationParsers.snapshot(ProbeResult(127, "", "not found"), ok("none")).state)
    }

    @Test fun duplicateMergePercentagesAreNotReportedAsOneCurrentMeasurement() {
        assertNull(ObservationParsers.snapshot(ok("Update state: 3\nMerge completion: 20%\nMerge completion: 21%"), ok("merging")).mergePercent)
    }

    @Test fun mergeCompletedRemainsDifferentFromNoUpdate() {
        assertEquals("COMPLETE", ObservationParsers.snapshot(ok("Update state: 5"), null).state)
        assertEquals("MERGE_NEEDS_REBOOT", ObservationParsers.snapshot(ok("Update state: 4"), null).state)
        assertEquals("FAILED", ObservationParsers.snapshot(ok("Update state: 6"), null).state)
    }

    @Test fun boundedEngineFollowReadsLastValidCallback() {
        val observation = ProbeResult(124, "", "I onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.2)\nI onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)")
        assertEquals("IDLE", ObservationParsers.engine(observation))
    }

    @Test fun engineCallbackMismatchOrMissingToolRemainsUnknown() {
        assertEquals("UNKNOWN", ObservationParsers.engine(ok("onStatusUpdate(UPDATE_STATUS_IDLE (3), 0)")))
        assertEquals("UNKNOWN", ObservationParsers.engine(ProbeResult(127, "", "not found")))
        assertEquals("UNKNOWN", ObservationParsers.engine(ProbeResult(null, "onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)", "", timedOut = true)))
    }

    @Test fun errorCallbackStaysError() {
        assertEquals("ERROR", ObservationParsers.engine(ok("onStatusUpdate(UPDATE_STATUS_REPORTING_ERROR_EVENT (7), 0)")))
    }

    @Test fun payloadFailureCannotBeHiddenByFollowingIdleStatus() {
        assertEquals("ERROR", ObservationParsers.engine(ok("onPayloadApplicationComplete(ErrorCode::kError (1))\nonStatusUpdate(UPDATE_STATUS_IDLE (0), 0)")))
    }

    @Test fun preferencesDecodeXmlEscapedJsonWithoutChangingCounters() {
        val xml = """<map><string name="downloadRequest">{&quot;contentResource&quot;:{&quot;wifiUrl&quot;:&quot;https://example.invalid/?a=1&amp;b=2&quot;}}</string><int name="failure_count" value="0"/></map>"""
        val parsed = ObservationParsers.preferences(xml)
        assertTrue(parsed.getValue("downloadRequest").contains("?a=1&b=2"))
        assertEquals("0", parsed["failure_count"])
    }

    @Test(expected = IllegalArgumentException::class) fun preferencesRejectExternalEntities() {
        ObservationParsers.preferences("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><map><string name='x'>&x;</string></map>")
    }

    @Test(expected = IllegalArgumentException::class) fun preferencesRejectDuplicateKeys() {
        ObservationParsers.preferences("<map><string name='x'>a</string><string name='x'>b</string></map>")
    }

    @Test fun rootCommandsCannotWriteOrApplyAnything() {
        val destructive = Regex("(?:^|[ ;])(?:dd|rm|reboot|fastboot|setprop|chmod|chown|mount|umount)(?:[ ;]|$)|--(?:update|merge|cancel|reset_status)|set-active|mark-boot| >")
        ReadOnlyProbe.entries.forEach { assertFalse("Unexpected mutating probe ${it.name}", destructive.containsMatchIn(it.command)) }
        assertEquals("/system/bin/toybox timeout 3 /system/bin/update_engine_client --follow", ReadOnlyProbe.ENGINE_FOLLOW.command)
        assertFalse(ReadOnlyShell.PROPERTY_NAMES.any { Regex("serial|imei|account|token", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }

    @Test(expected = IllegalArgumentException::class) fun shellRejectsArbitraryPropertyBeforeLaunchingProcess() {
        ReadOnlyShell().property("ro.product.model; reboot")
    }

    @Test(expected = IllegalArgumentException::class) fun shellRejectsBackupPathInjectionBeforeLaunchingProcess() {
        ReadOnlyShell().readBackup("magisk_backup_../../etc/passwd")
    }

    @Test fun gzipBackupHasIndependentDigestAndNeverVerifiedProvenance() {
        val raw = "ANDROID!".toByteArray() + ByteArray(4096)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        val result = DeviceInspector().inspectBackup("magisk_backup_$sha1", output.toByteArray())
        assertEquals(raw.size.toLong(), result.uncompressedBytes)
        assertEquals(sha1, result.sha1)
        assertTrue(result.androidBootHeader)
        assertFalse(result.provenanceVerified)
        assertTrue(result.errors.any { "niet bewezen" in it })
    }

    @Test fun corruptGzipIsRejected() {
        val result = DeviceInspector().inspectBackup("magisk_backup_" + "0".repeat(40), byteArrayOf(1, 2, 3))
        assertNull(result.sha256)
        assertFalse(result.provenanceVerified)
        assertTrue(result.errors.isNotEmpty())
    }
}
