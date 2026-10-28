package nl.moto.ota.platform

import nl.moto.ota.core.EngineState
import nl.moto.ota.core.Readiness
import nl.moto.ota.core.SnapshotState
import nl.moto.ota.core.ReadOnlyMetadata
import org.junit.Assert.*
import org.junit.Test

class InspectionMapperTest {
    private fun ok(text: String) = ProbeResult(0, text, "")
    private fun observation(
        properties: Map<String, String> = emptyMap(),
        changedProbes: Map<ReadOnlyProbe, ProbeResult> = emptyMap(),
        removedProbes: Set<ReadOnlyProbe> = emptySet(), rootRequested: Boolean = true,
        capturedAt: Long = 1000
    ): Inspection {
        val values = mapOf("ro.product.manufacturer" to "motorola", "ro.product.model" to "motorola edge 70 pro",
            "ro.boot.hardware.sku" to "XT2607-1", "ro.product.device" to "merc", "ro.boot.carrier" to "reteu",
            "ro.boot.slot_suffix" to "_b", "ro.virtual_ab.enabled" to "true", "ro.build.version.release" to "17",
            "ro.build.id" to "A171WMH.44-25", "ro.build.version.incremental" to "0347f0-d290bf",
            "ro.build.date.utc" to "1700000000", "ro.build.version.security_patch" to "2026-10-01",
            "ro.boot.vbmeta.device_state" to "unlocked", "ro.boot.flash.locked" to "0", "ro.boot.verifiedbootstate" to "orange",
            "batteryPercent" to "80", "freeBytes" to "40000000000") + properties
        val probes = mutableMapOf(ReadOnlyProbe.ROOT_ID to ok("uid=0(root) gid=0(root) groups=0(root)"),
            ReadOnlyProbe.CURRENT_SLOT to ok("1"), ReadOnlyProbe.SLOT_COUNT to ok("2"),
            ReadOnlyProbe.SLOT_B_SUCCESS to ok("1"), ReadOnlyProbe.MAGISK_VERSION to ok("31.1:MAGISK:R"),
            ReadOnlyProbe.MAGISK_CODE to ok("31100"), ReadOnlyProbe.PARTITIONS to ok("init_boot_a=8388608\ninit_boot_b=8388608"),
            ReadOnlyProbe.MODULES to ok("fps-module|enabled"), ReadOnlyProbe.BOOT_ID to ok("12345678-1234-1234-1234-123456789abc"))
            .apply { putAll(changedProbes); removedProbes.forEach { remove(it) } }
        return Inspection(values, probes.mapKeys { it.key.name }, emptyList(), SnapshotObservation("NONE"), "IDLE", capturedAt, rootRequested)
    }

    @Test fun exactSkuAndObservedProbesProduceMeasuredCompatibleDevice() {
        val device = observation().toDeviceState()
        assertTrue(device.exactProfileMatch); assertTrue(device.rootChecked); assertTrue(device.rootGranted)
        assertEquals("_b", device.slot); assertEquals("_a", device.inactiveSlot); assertEquals(31100, device.magiskCode)
        assertEquals(SnapshotState.NONE, device.snapshot); assertEquals(EngineState.IDLE, device.engine)
        assertEquals(true, device.bootSuccessful); assertTrue(device.moduleInventoryKnown)
        assertEquals(1700000000L, device.buildTimestamp)
    }

    @Test fun rootNotRequestedNeverMasqueradesAsMeasuredRootLoss() {
        val device = observation(rootRequested = false).toDeviceState()
        assertFalse(device.rootChecked); assertFalse(device.rootGranted); assertFalse(device.moduleInventoryKnown)
        assertEquals(0, device.magiskCode); assertEquals(SnapshotState.UNKNOWN, device.snapshot); assertEquals(EngineState.UNKNOWN, device.engine)
    }

    @Test fun missingRootProbeIsUnknown() {
        val device = observation(removedProbes = setOf(ReadOnlyProbe.ROOT_ID)).toDeviceState()
        assertFalse(device.rootChecked); assertFalse(device.rootGranted)
    }

    @Test fun deniedRootIsRecordedAsCheckedWithoutGrant() {
        val device = observation(changedProbes = mapOf(ReadOnlyProbe.ROOT_ID to ProbeResult(1, "", "Permission denied"))).toDeviceState()
        assertTrue(device.rootChecked); assertFalse(device.rootGranted); assertEquals(SnapshotState.UNKNOWN, device.snapshot)
    }

    @Test fun timedOutRootDoesNotProveRootLoss() {
        val device = observation(changedProbes = mapOf(ReadOnlyProbe.ROOT_ID to ProbeResult(null, "", "", timedOut = true))).toDeviceState()
        assertFalse(device.rootChecked); assertFalse(device.rootGranted)
    }

    @Test fun malformedRootOutputIsUnknown() {
        val device = observation(changedProbes = mapOf(ReadOnlyProbe.ROOT_ID to ok("uid=0root"))).toDeviceState()
        assertFalse(device.rootChecked); assertFalse(device.rootGranted)
    }

    @Test fun matchingMarketingNameCannotOverrideConflictingTechnicalSku() {
        val device = observation(properties = mapOf("ro.product.model" to "XT2607-1", "ro.boot.hardware.sku" to "XT2607-2")).toDeviceState()
        assertFalse(device.exactProfileMatch)
    }

    @Test fun matchingSkuCannotOverrideConflictingTechnicalModel() {
        val device = observation(properties = mapOf("ro.product.model" to "XT2608-1")).toDeviceState()
        assertFalse(device.exactProfileMatch)
    }

    @Test fun secondaryTechnicalSkuConflictBlocksDeviceProfile() {
        val device = observation(properties = mapOf("ro.boot.product.hardware.sku" to "XT2607-2")).toDeviceState()
        assertFalse(device.exactProfileMatch)
    }

    @Test fun conflictingChannelsAreUnknown() {
        assertEquals("", observation(properties = mapOf("ro.carrier" to "retus")).toDeviceState().channel)
    }

    @Test fun bootPropertyAndBootctlSlotDisagreementIsUnknown() {
        assertEquals("", observation(changedProbes = mapOf(ReadOnlyProbe.CURRENT_SLOT to ok("0"))).toDeviceState().slot)
    }

    @Test fun conflictingBootSlotPropertiesAreUnknown() {
        assertEquals("", observation(properties = mapOf("ro.boot.slot" to "a")).toDeviceState().slot)
    }

    @Test fun unsupportedSlotCountIsUnknown() {
        assertEquals("", observation(changedProbes = mapOf(ReadOnlyProbe.SLOT_COUNT to ok("1"))).toDeviceState().slot)
    }

    @Test fun successfulButEmptyBootctlOutputIsNotSlotEvidence() {
        assertEquals("", observation(changedProbes = mapOf(ReadOnlyProbe.CURRENT_SLOT to ok(""))).toDeviceState().slot)
    }

    @Test fun malformedSlotPropertyIsUnknown() {
        assertEquals("", observation(properties = mapOf("ro.boot.slot_suffix" to "b")).toDeviceState().slot)
    }

    @Test fun malformedMagiskOutputCannotPreserveKnownVersionCode() {
        assertEquals(0, observation(changedProbes = mapOf(ReadOnlyProbe.MAGISK_VERSION to ok("31.1\nnoise"))).toDeviceState().magiskCode)
        assertEquals(0, observation(changedProbes = mapOf(ReadOnlyProbe.MAGISK_CODE to ok("31100 garbage"))).toDeviceState().magiskCode)
        assertEquals(0, observation(changedProbes = mapOf(ReadOnlyProbe.MAGISK_VERSION to ok("30.0"))).toDeviceState().magiskCode)
    }

    @Test fun absentModuleInventoryIsDifferentFromMeasuredEmptyInventory() {
        val absent = observation(removedProbes = setOf(ReadOnlyProbe.MODULES)).toDeviceState()
        val empty = observation(changedProbes = mapOf(ReadOnlyProbe.MODULES to ok(""))).toDeviceState()
        assertFalse(absent.moduleInventoryKnown); assertTrue(empty.moduleInventoryKnown); assertTrue(empty.modules.isEmpty())
    }

    @Test fun malformedModuleInventoryCannotBecomeBaseline() {
        val device = observation(changedProbes = mapOf(ReadOnlyProbe.MODULES to ok("../../personal file|enabled"))).toDeviceState()
        assertFalse(device.moduleInventoryKnown); assertTrue(device.modules.isEmpty())
    }

    @Test fun duplicatePartitionEvidenceIsNotAccepted() {
        assertTrue(observation(changedProbes = mapOf(ReadOnlyProbe.PARTITIONS to ok("init_boot_a=8388608\ninit_boot_a=1"))).toDeviceState().partitions.isEmpty())
    }

    @Test fun freshnessIsPreservedRatherThanStampedWithMapperExecutionTime() {
        assertEquals(12L, observation(capturedAt = 12).toDeviceState().capturedAtMillis)
    }

    @Test fun slowInspectionRemainsStaleAfterMappingInsteadOfReceivingCompletionTimestamp() {
        val scanStartedAt = System.currentTimeMillis() - 180_000L
        val device = observation(capturedAt = scanStartedAt).toDeviceState()
        assertEquals(scanStartedAt, device.capturedAtMillis)
        assertTrue(Readiness.evaluate(device, null, 0).blockers.any { it.code == "OBSERVATIONS_STALE" })
    }

    @Test fun bootctlExplicitFalseRemainsFalseAndMissingRemainsUnknown() {
        assertEquals(false, observation(changedProbes = mapOf(ReadOnlyProbe.SLOT_B_SUCCESS to ProbeResult(70, "0", ""))).toDeviceState().bootSuccessful)
        assertNull(observation(removedProbes = setOf(ReadOnlyProbe.SLOT_B_SUCCESS)).toDeviceState().bootSuccessful)
    }

    @Test fun malformedBatteryAndStorageEvidenceRemainUnknown() {
        val device = observation(properties = mapOf("batteryPercent" to "101", "freeBytes" to "-999")).toDeviceState()
        assertEquals(-1, device.batteryPercent); assertEquals(-1L, device.freeBytes)
    }

    @Test fun conflictingBootloaderPropertiesAreUnknown() {
        assertNull(observation(properties = mapOf("ro.boot.flash.locked" to "1")).toDeviceState().bootloaderUnlocked)
    }

    @Test fun unexpectedBootSessionOutputCannotDriveRecoveryReconciliation() {
        assertEquals("", observation(changedProbes = mapOf(ReadOnlyProbe.BOOT_ID to ok("unexpected stdout\n12345678"))).toDeviceState().bootId)
    }

    @Test fun realMotorolaBootMetadataWorksWhenBootctlIsAbsent() {
        val raw = "00000000424341420102000007009f000000000000000000000000006fc8849e".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val observed = observation(removedProbes = setOf(ReadOnlyProbe.CURRENT_SLOT, ReadOnlyProbe.SLOT_COUNT, ReadOnlyProbe.SLOT_B_SUCCESS))
            .copy(bootControl = ReadOnlyMetadata.parseBootControl(raw), kernelBootConfig = mapOf("slot_suffix" to "_b"))
        assertEquals("_b", observed.toDeviceState().slot)
        assertEquals(true, observed.toDeviceState().bootSuccessful)
    }

    @Test fun realKernelBootFactsRemainDistinctFromSpoofableProperties() {
        val observed = observation(properties = mapOf("ro.boot.vbmeta.device_state" to "locked", "ro.boot.flash.locked" to "1", "ro.boot.verifiedbootstate" to "green"))
            .copy(kernelBootConfig = mapOf("vbmeta.device_state" to "unlocked", "verifiedbootstate" to "orange", "slot_suffix" to "_b"))
        val device = observed.toDeviceState()
        assertEquals(true, device.bootloaderUnlocked)
        assertEquals("orange", device.verifiedBootState)
        assertTrue(device.securityPropertyMismatch)
        assertEquals("KERNEL_BOOTCONFIG", device.securityEvidenceSource)
    }

    @Test fun failedRootCannotPromoteKernelFactsAndContradictorySlotsRemainUnknown() {
        val facts = mapOf("vbmeta.device_state" to "unlocked", "verifiedbootstate" to "orange", "slot_suffix" to "_a")
        assertEquals("", observation().copy(kernelBootConfig = facts).toDeviceState().slot)
        val unprivileged = observation(rootRequested = false, properties = mapOf("ro.boot.verifiedbootstate" to "green"))
            .copy(kernelBootConfig = facts).toDeviceState()
        assertEquals("green", unprivileged.verifiedBootState)
        assertFalse(unprivileged.securityPropertyMismatch)
    }
}
