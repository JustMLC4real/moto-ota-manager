package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic ZIPs exercise the real compatibility parser, never firmware or an installer. */
class AutomaticUpdatePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 10_000_000L
    private val systemSource = "motorola/merc_g_sys/merc:16/SOURCE_16/source-system:user/release-keys"
    private val systemTarget = "motorola/merc_g_sys/merc:17/TARGET_17/target-system:user/release-keys"
    private val vendorSource = "motorola/merc_g_vext/merc:15/SOURCE_16/source-vendor:user/release-keys"
    private val vendorTarget = "motorola/merc_g_vext/merc:15/TARGET_17/target-vendor:user/release-keys"
    private val current get() = safetyDevice().copy(androidVersion = "16", sdkInt = 36, build = "SOURCE_16",
        fingerprint = systemSource, vendorFingerprint = vendorSource, incrementalBuild = "source-system",
        buildTimestamp = 1000, capturedAtMillis = now)
    private fun manifest(backup: BackupValidation = trustedBackup(current)) = PayloadManifestInfo(4096,
        PartitionDigest("init_boot", backup.bytes, backup.sha256),
        PartitionDigest("init_boot", 8192, "d".repeat(64)), listOf("init_boot"), 100)

    @Test fun exactAndroidSourceAndLiveOemSelectionCanPassWithoutGuessingAProductAlias() {
        val report = report(vendorNamespace = false)
        assertTrue(report.compatible)
        assertTrue(StockEvidencePolicy.sourceIdentityProven(report, current))
        assertTrue(codes(report).isEmpty())
    }

    @Test fun observedSignedVendorSourceBridgesTheSystemNamespaceButDoesNotBecomeSignedChannelMetadata() {
        val report = report(remove = setOf("pre-channel"))
        assertFalse(report.regionVerified)
        assertFalse(report.compatible)
        assertEquals(setOf("SOURCE_BUILD", "SOURCE_FINGERPRINT", "OEM_METADATA_REVIEW_REQUIRED", "REGION_UNPROVEN"),
            report.issues.map { it.code }.toSet())
        assertTrue(StockEvidencePolicy.sourceIdentityProven(report, current))
        assertTrue(codes(report).isEmpty())
        assertTrue("The offline report must retain its missing signed-channel evidence", report.issues.any { it.code == "REGION_UNPROVEN" })
    }

    @Test fun absentDifferentOrMerelySimilarVendorObservationCannotBridgeSignedAndroidPreconditions() {
        val report = report()
        listOf("", vendorSource.replace("source-vendor", "other"),
            vendorSource.replace("merc_g_vext", "merc_g_vendor"), vendorSource.replace("SOURCE_16", "OLD_16")).forEach { vendor ->
            val observed = current.copy(vendorFingerprint = vendor)
            assertFalse("Vendor aliases are not proof: $vendor", StockEvidencePolicy.sourceIdentityProven(report, observed))
            assertTrue(codes(report, observed).contains("AUTOMATION_SOURCE_UNPROVEN"))
        }
    }

    @Test fun matchingPayloadSourceCannotWaiveAnExplicitlyWrongSignedAndroidSource() {
        val report = report(metadataChanges = mapOf("pre-build" to vendorSource.replace("SOURCE_16", "ANOTHER_BUILD")))
        assertEquals(systemSource, report.payloadSourceFingerprint)
        assertTrue(report.issues.any { it.code == "SOURCE_FINGERPRINT" })
        assertFalse(StockEvidencePolicy.sourceIdentityProven(report, current))
        assertTrue(codes(report).containsAll(setOf("AUTOMATION_SOURCE_UNPROVEN", "SOURCE_FINGERPRINT", "SOURCE_BUILD")))
    }

    @Test fun wrongSystemSourceBuildIncrementalOrCodenameCannotBorrowTheVendorBridge() {
        listOf(systemSource.replace("SOURCE_16", "OLD_16"), systemSource.replace("source-system", "old-system"),
            systemSource.replace("/merc:", "/other:")).forEach { source ->
            val report = report(propertyChanges = mapOf("SOURCE_FP" to source))
            assertFalse(StockEvidencePolicy.sourceIdentityProven(report, current))
            assertTrue(codes(report).contains("AUTOMATION_SOURCE_UNPROVEN"))
        }
    }

    @Test fun inconsistentSignedSourceIncrementalOrDuplicateSourceFingerprintsCannotBeBridged() {
        listOf(mapOf("pre-build-incremental" to "different"),
            mapOf("pre-build" to "$vendorSource|$vendorSource"),
            mapOf("pre-build" to "$vendorSource|${vendorSource.replace("source-vendor", "other")}")).forEach { changes ->
            val report = report(metadataChanges = changes)
            assertFalse(StockEvidencePolicy.sourceIdentityProven(report, current))
            assertTrue(codes(report).contains("AUTOMATION_SOURCE_UNPROVEN"))
        }
    }

    @Test fun opaqueLiveSelectionIsRequiredEvenForCorrectlySignedCompatibleMetadata() {
        val report = report(vendorNamespace = false)
        assertTrue(codes(report, liveSelection = false).contains("MOTOROLA_LIVE_SELECTION_REQUIRED"))
        listOf("", systemTarget.replace("TARGET_17", "OTHER_17"), systemTarget.replace("/merc:", "/other:")).forEach { target ->
            assertTrue(codes(report, selectedTarget = target).contains("MOTOROLA_LIVE_SELECTION_REQUIRED"))
        }
    }

    @Test fun liveSelectionCannotReplaceWholeZipSignaturePayloadHashOrStructureVerification() {
        val verified = report()
        listOf(verified.copy(signatureVerified = false), verified.copy(payloadHashValid = false),
            verified.copy(structureValid = false), verified.copy(sha256 = "not-a-sha256")).forEach { report ->
            assertTrue(codes(report).contains("AUTOMATION_PACKAGE_UNVERIFIED"))
        }
    }

    @Test fun explicitWrongModelDeviceChannelAndConflictingRegionAnnotationsAreNeverWaived() {
        listOf(
            mapOf("pre-model" to "XT2607-2") to "WRONG_MODEL",
            mapOf("motorola-model" to "XT9999-1") to "WRONG_MODEL",
            mapOf("pre-device" to "other") to "WRONG_DEVICE",
            mapOf("pre-channel" to "retus") to "WRONG_REGION",
            mapOf("motorola-channel" to "retus") to "WRONG_REGION"
        ).forEach { (changes, expected) ->
            val report = report(metadataChanges = changes)
            assertTrue("Fixture must diagnose $expected", report.issues.any { it.code == expected })
            assertTrue("A live response must not waive $expected", codes(report).contains(expected))
        }
    }

    @Test fun explicitDowngradeWipeAndUnknownInstructionsRemainBlockedWithAValidBridge() {
        listOf("ota-downgrade", "ota-wipe").forEach { key ->
            listOf("yes", "unknown").forEach { value ->
                val report = report(metadataChanges = mapOf(key to value))
                assertTrue(StockEvidencePolicy.sourceIdentityProven(report, current))
                assertTrue(codes(report).contains("DOWNGRADE_OR_WIPE"))
            }
        }
    }

    @Test fun timestampSecurityPatchAndAndroidRollbackChecksAreIndependentOfLiveSelection() {
        listOf(mapOf("post-timestamp" to "999") to "TIMESTAMP_ROLLBACK",
            mapOf("post-timestamp" to "1000") to "TIMESTAMP_ROLLBACK",
            mapOf("post-timestamp" to "unknown") to "TIMESTAMP_UNKNOWN",
            mapOf("post-security-patch-level" to "2026-09-01") to "PATCH_ROLLBACK").forEach { (changes, code) ->
            assertTrue(codes(report(metadataChanges = changes)).contains(code))
        }
        val rollback = report(propertyChanges = mapOf("TARGET_FP" to systemTarget.replace(":17/", ":15/")))
        assertTrue(codes(rollback).contains("ANDROID_ROLLBACK"))
        assertTrue(codes(rollback).contains("TARGET_RUNTIME_UNREVIEWED"))
        val future = report(propertyChanges = mapOf("TARGET_FP" to systemTarget.replace(":17/", ":18/")))
        assertTrue(codes(future).contains("TARGET_RUNTIME_UNREVIEWED"))
    }

    @Test fun missingOrOutOfBoundsTargetImageDigestBlocksBeforeAnyRootOperation() {
        val report = report()
        val known = manifest()
        listOf(known.copy(initBootTarget = null), known.copy(initBootTarget = known.initBootTarget!!.copy(name = "boot")),
            known.copy(initBootTarget = known.initBootTarget!!.copy(bytes = 4095)),
            known.copy(initBootTarget = known.initBootTarget!!.copy(bytes = BootImageStreams.MAX_IMAGE_BYTES + 1))).forEach { manifest ->
            assertTrue(codes(report, manifest = manifest).contains("TARGET_BOOT_DIGEST_MISSING"))
        }
    }

    @Test fun incrementalSourceDigestMustMatchTheWholeAuthenticatedCurrentBackupNotJustItsName() {
        val report = report()
        val known = manifest()
        listOf(known.copy(initBootSource = known.initBootSource!!.copy(sha256 = "f".repeat(64))),
            known.copy(initBootSource = known.initBootSource!!.copy(bytes = 16_384))).forEach { manifest ->
            assertTrue(codes(report, manifest = manifest).contains("SOURCE_BOOT_DIGEST_MISMATCH"))
        }
    }

    @Test fun bootAndInitBootDigestBanksAreNotInterchangeable() {
        val device = current.copy(partitions = listOf("boot_a", "boot_b", "init_boot_a", "init_boot_b"))
        val image = initBootFixture(16_384).apply { putLe32(this, 8, 512) }
        val backup = BackupValidator.inspect(image.inputStream(), false, image.size.toLong(), device,
            stockReference(image, device).copy(partition = "boot"), "boot")
        assertTrue(backup.usableFor(device))
        val both = manifest().copy(bootSource = PartitionDigest("boot", backup.bytes, backup.sha256),
            bootTarget = PartitionDigest("boot", backup.bytes, "e".repeat(64)))
        val report = report(device = device)
        assertTrue(codes(report, device, both, backup).isEmpty())
        assertTrue(codes(report, device, both.copy(bootTarget = null), backup).contains("TARGET_BOOT_DIGEST_MISSING"))
        assertTrue(codes(report, device, both.copy(bootSource = both.bootSource!!.copy(sha256 = "a".repeat(64))), backup)
            .contains("SOURCE_BOOT_DIGEST_MISMATCH"))
    }

    @Test fun fullOtaStillRequiresAProvenCurrentBackupAndAnExplicitLiveTarget() {
        val full = report(vendorNamespace = false, remove = setOf("pre-build", "pre-build-incremental"), noPayloadFingerprints = true)
        assertTrue(full.compatible)
        assertFalse(StockEvidencePolicy.sourceIdentityProven(full, current))
        assertTrue(codes(full, manifest = manifest().copy(initBootSource = null)).isEmpty())
        assertTrue(codes(full, manifest = manifest().copy(initBootSource = null), backup = null).contains("BACKUP_UNVERIFIED"))
        assertTrue(codes(full, liveSelection = false).contains("MOTOROLA_LIVE_SELECTION_REQUIRED"))
    }

    @Test fun liveSelectionDoesNotWaiveFreshRootMergeOrIdentityReadiness() {
        val report = report()
        listOf(current.copy(rootGranted = false) to "ROOT_DENIED",
            current.copy(capturedAtMillis = now - 120_001) to "OBSERVATIONS_STALE",
            current.copy(snapshot = SnapshotState.MERGING) to "SNAPSHOT_BLOCKED",
            current.copy(snapshot = SnapshotState.UNKNOWN) to "SNAPSHOT_BLOCKED",
            current.copy(identityConflicts = listOf("MODEL")) to "DEVICE_PROFILE",
            current.copy(sdkInt = 38) to "ANDROID_UNREVIEWED",
            current.copy(batteryPercent = 49) to "BATTERY_LOW",
            current.copy(freeBytes = 0) to "STORAGE_LOW").forEach { (device, expected) ->
            assertTrue(codes(report, device).contains(expected))
        }
    }

    private fun codes(report: FirmwareReport, device: DeviceState = current, manifest: PayloadManifestInfo = manifest(),
                      backup: BackupValidation? = trustedBackup(device), liveSelection: Boolean = true,
                      selectedTarget: String = report.targetFingerprint): Set<String> =
        AutomaticUpdatePolicy.blockers(device, report, manifest, backup, liveSelection, selectedTarget, now).map { it.code }.toSet()

    private fun report(metadataChanges: Map<String, String> = emptyMap(), remove: Set<String> = emptySet(),
                       propertyChanges: Map<String, String> = emptyMap(), vendorNamespace: Boolean = true,
                       device: DeviceState = current, noPayloadFingerprints: Boolean = false): FirmwareReport {
        val metadata = linkedMapOf("ota-type" to "AB", "pre-device" to "merc", "pre-model" to "XT2607-1", "pre-channel" to "reteu",
            "pre-build" to if (vendorNamespace) vendorSource else systemSource,
            "pre-build-incremental" to if (vendorNamespace) "source-vendor" else "source-system",
            "post-build" to if (vendorNamespace) vendorTarget else systemTarget,
            "post-build-incremental" to if (vendorNamespace) "target-vendor" else "target-system",
            "post-security-patch-level" to "2026-11-01", "post-timestamp" to "2000").apply {
            putAll(metadataChanges); remove.forEach { remove(it) }
        }
        val payload = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN).put("CrAU".toByteArray())
            .putLong(2).putLong(4).putInt(0).put(byteArrayOf(1, 2, 3, 4)).put(ByteArray(16) { 7 }).array()
        fun digest(bytes: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        val properties = linkedMapOf("FILE_SIZE" to payload.size.toString(), "FILE_HASH" to digest(payload),
            "METADATA_SIZE" to "28", "METADATA_HASH" to digest(payload.copyOfRange(0, 28))).apply {
            if (!noPayloadFingerprints) { put("SOURCE_FP", systemSource); put("TARGET_FP", systemTarget) }
            putAll(propertyChanges)
        }
        val file = temporary.newFile("automatic-${java.util.UUID.randomUUID()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf("META-INF/com/android/metadata" to metadata.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(),
                "payload_properties.txt" to properties.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(),
                "payload.bin" to payload).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        // Signature verification belongs to the Android verifier. This unit-test boundary
        // explicitly injects that result; these synthetic ZIPs are never claimed signed.
        return FirmwareValidator.inspect(file, device).also {
            assertTrue("Synthetic fixture structure must parse", it.structureValid)
            assertTrue("Synthetic fixture payload hashes must match", it.payloadHashValid)
        }.copy(signatureVerified = true)
    }
}
