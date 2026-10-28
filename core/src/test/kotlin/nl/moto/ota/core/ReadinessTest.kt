package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class ReadinessTest {
    private fun blockers(device: DeviceState = safetyDevice(), backup: BackupValidation? = trustedBackup()): Set<String> =
        Readiness.evaluate(device, backup).blockers.map { it.code }.toSet()

    @Test fun observedCompatibleDeviceAndAuthenticatedBackupPassPreflight() {
        assertTrue(Readiness.evaluate(safetyDevice(), trustedBackup()).ready)
    }

    @Test fun wrongModelAndRegionAreBlockedIndependently() {
        assertTrue("DEVICE_PROFILE" in blockers(safetyDevice().copy(model = "XT2607-2")))
        assertTrue("DEVICE_PROFILE" in blockers(safetyDevice().copy(channel = "retus")))
        assertTrue("DEVICE_PROFILE" in blockers(safetyDevice().copy(device = "other")))
    }

    @Test fun noRootBlocksEvenWithValidBackup() {
        assertTrue("ROOT_DENIED" in blockers(safetyDevice().copy(rootGranted = false)))
        assertTrue("ROOT_DENIED" in blockers(safetyDevice().copy(rootChecked = false)))
    }

    @Test fun unknownModuleInventoryBlocksPreflight() {
        assertTrue("MODULE_INVENTORY_UNKNOWN" in blockers(safetyDevice().copy(moduleInventoryKnown = false)))
    }

    @Test fun futureAndroidAndUnknownBootSecurityRequireProfileReview() {
        assertTrue("ANDROID_UNREVIEWED" in blockers(safetyDevice().copy(androidVersion = "18")))
        assertTrue("BOOT_NOT_CONFIRMED" in blockers(safetyDevice().copy(bootSuccessful = null)))
        assertTrue("BOOT_SECURITY_UNKNOWN" in blockers(safetyDevice().copy(bootloaderUnlocked = null)))
        assertTrue("BOOT_SECURITY_UNKNOWN" in blockers(safetyDevice().copy(verifiedBootState = "")))
        assertTrue("BOOT_SECURITY_UNKNOWN" in blockers(safetyDevice().copy(verifiedBootState = "red")))
        assertTrue("BOOT_SECURITY_UNKNOWN" in blockers(safetyDevice().copy(verifiedBootState = "green")))
    }

    @Test fun missingOrUnprovenBackupBlocks() {
        assertTrue("BACKUP_UNVERIFIED" in blockers(backup = null))
        assertTrue("BACKUP_UNVERIFIED" in blockers(backup = BackupValidation(structurallyValid = true)))
    }

    @Test fun insufficientStorageBlocks() {
        assertTrue("STORAGE_LOW" in blockers(safetyDevice().copy(freeBytes = 1024)))
    }

    @Test fun activeOrUnknownSnapshotStatesAlwaysBlock() {
        SnapshotState.entries.filter { it != SnapshotState.NONE }.forEach {
            assertTrue("state $it", "SNAPSHOT_BLOCKED" in blockers(safetyDevice().copy(snapshot = it)))
        }
    }

    @Test fun engineErrorAndPendingRebootBlockNewUpdate() {
        EngineState.entries.filter { it != EngineState.IDLE }.forEach {
            assertTrue("state $it", "ENGINE_NOT_IDLE" in blockers(safetyDevice().copy(engine = it)))
        }
    }

    @Test fun unknownFutureMagiskVersionFailsClosed() {
        assertTrue("MAGISK_UNREVIEWED" in blockers(safetyDevice().copy(magiskCode = 31200)))
    }

    @Test fun batteryAndUnknownVirtualAbAreBlocked() {
        assertTrue("BATTERY_LOW" in blockers(safetyDevice().copy(batteryPercent = 49)))
        assertTrue("VIRTUAL_AB_UNKNOWN" in blockers(safetyDevice().copy(virtualAb = null)))
    }

    @Test fun staleAndFutureDatedObservationsCannotAuthorizeActions() {
        assertTrue("OBSERVATIONS_STALE" in blockers(safetyDevice().copy(capturedAtMillis = System.currentTimeMillis() - 121_000)))
        assertTrue("OBSERVATIONS_STALE" in blockers(safetyDevice().copy(capturedAtMillis = System.currentTimeMillis() + 121_000)))
    }

    @Test fun simulatedCompatibleDeviceNeverAuthorizesRealFlow() {
        assertTrue("SIMULATED" in blockers(safetyDevice().copy(simulated = true)))
    }

    @Test fun missingSlotOrInitBootPartitionBlocks() {
        assertTrue("SLOT_UNKNOWN" in blockers(safetyDevice().copy(slot = "")))
        assertTrue("PARTITIONS_UNKNOWN" in blockers(safetyDevice().copy(partitions = listOf("boot_a", "boot_b"))))
    }
}
