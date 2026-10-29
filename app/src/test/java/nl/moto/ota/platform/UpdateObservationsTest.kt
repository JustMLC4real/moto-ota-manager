package nl.moto.ota.platform

import org.junit.Assert.*
import org.junit.Test

class UpdateObservationsTest {
    private val hash = "a".repeat(64)
    private fun inspection(output: String, result: ProbeResult = ProbeResult(0, output, ""), slot: String = "_b") =
        Inspection(mapOf("ro.boot.slot_suffix" to slot), mapOf(ReadOnlyProbe.ACTIVE_INIT_BOOT_HASH.name to result,
            ReadOnlyProbe.ROOT_ID.name to ProbeResult(0, "uid=0(root)", "")),
            emptyList(), SnapshotObservation("UNKNOWN"), "UNKNOWN", 1, true)
    @Test fun fullDigestOnlyBelongsToTheExactObservedPartition() {
        assertEquals(hash, UpdateObservations.activeImageSha256(inspection("$hash  /dev/block/by-name/init_boot_b\n")))
        listOf("$hash  /dev/block/by-name/init_boot_a", "$hash  /tmp/image", "$hash", "$hash  /dev/block/by-name/init_boot_b\nextra", "${hash.dropLast(1)} /dev/block/by-name/init_boot_b").forEach {
            assertNull(UpdateObservations.activeImageSha256(inspection(it)))
        }
    }
    @Test fun failedTruncatedTimedOutOrUnknownSlotNeverAuthorizes() {
        val output = "$hash  /dev/block/by-name/init_boot_b"
        listOf(ProbeResult(1, output, ""), ProbeResult(0, output, "", truncated=true), ProbeResult(null, output, "", timedOut=true),
            ProbeResult(0, output, "", failure="READ_ERROR")).forEach { assertNull(UpdateObservations.activeImageSha256(inspection(output, it))) }
        assertNull(UpdateObservations.activeImageSha256(inspection(output, slot="")))
        assertNull(UpdateObservations.activeImageSha256(inspection(output).copy(rootRequested=false)))
    }
    @Test fun automaticUpdateSettingHasThreeStates() {
        fun read(output: String, success: Boolean = true) = UpdateObservations.automaticUpdatesDisabled(inspection("").copy(
            probes=mapOf(ReadOnlyProbe.AUTO_UPDATE_SETTING.name to ProbeResult(if(success)0 else 1, output, ""))))
        assertEquals(true, read("1\n")); assertEquals(false, read("0"))
        listOf("null", "", "1\n0", "true").forEach { assertNull(read(it)) }
        assertNull(read("1", false))
    }
    @Test fun bootControlToolInitializersAreNeverLaunchedEvenForGetterProbes() {
        val disabled = listOf(ReadOnlyProbe.CURRENT_SLOT, ReadOnlyProbe.SLOT_COUNT, ReadOnlyProbe.NEXT_SLOT,
            ReadOnlyProbe.SLOT_A_SUCCESS, ReadOnlyProbe.SLOT_B_SUCCESS, ReadOnlyProbe.SNAPSHOT_HAL)
        disabled.forEach {
            assertFalse(it.enabled); assertEquals("", it.command)
            val result = ReadOnlyShell().run(it)
            assertFalse(result.successful)
            assertEquals("UNSAFE_INITIALIZER_DISABLED", result.failure)
        }
    }
}
