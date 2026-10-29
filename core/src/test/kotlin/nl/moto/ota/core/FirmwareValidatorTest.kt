package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FirmwareValidatorTest {
    private val sourceFp = "motorola/merc_g_sys/merc:17/source-build/source-incremental:user/release-keys"
    private val targetFp = "motorola/merc_g_sys/merc:18/NEXT_BUILD/target-incremental:user/release-keys"
    private val device = DeviceState(manufacturer = "motorola", model = "XT2607-1", device = "merc", channel = "reteu",
        build = "source-build", incrementalBuild = "source-incremental", fingerprint = sourceFp,
        securityPatch = "2026-10-01", buildTimestamp = 1_000, androidVersion = "17")

    @Test fun compatiblePackageStillRequiresActualDeviceSignatureVerification() = fixture { report ->
        assertTrue(report.structureValid); assertTrue(report.compatible); assertTrue(report.payloadHashValid)
        assertTrue(report.regionVerified); assertFalse(report.signatureVerified); assertFalse(report.readyForOfficialReview)
        assertEquals("NEXT_BUILD", report.targetBuild); assertEquals(64, report.sha256.length)
    }

    @Test fun explicitlyDifferentSignedModelIsRejected() = fixture(observed = device.copy(model = "XT0000-1")) { assertIssue(it, "WRONG_MODEL") }
    @Test fun differentDeviceCodenameIsRejected() = fixture(changes = mapOf("pre-device" to "other")) { assertIssue(it, "WRONG_DEVICE") }
    @Test fun differentChannelIsRejected() = fixture(changes = mapOf("motorola-channel" to "retus")) { assertIssue(it, "WRONG_REGION") }
    @Test fun conflictingRegionAnnotationsAreRejected() = fixture(changes = mapOf("pre-channel" to "retus")) { assertIssue(it, "WRONG_REGION") }
    @Test fun deviceWrongRegionIsRejected() = fixture(observed = device.copy(channel = "retus")) { assertIssue(it, "WRONG_REGION") }
    @Test fun conflictingMeasuredIdentityRemainsBlocked() = fixture(observed = device.copy(identityConflicts = listOf("MODEL"))) { assertIssue(it, "DEVICE_PROFILE") }
    @Test fun anotherMotorolaModelUsesItsOwnExactSignedDeviceModelAndChannel() {
        val source = sourceFp.replace("merc", "synthetic")
        val target = targetFp.replace("merc", "synthetic")
        fixture(observed = device.copy(model = "XT9999-2", device = "synthetic", channel = "retus", fingerprint = source),
            changes = mapOf("pre-device" to "synthetic", "pre-model" to "XT9999-2", "motorola-channel" to "retus", "pre-build" to source, "post-build" to target),
            propertyChanges = mapOf("SOURCE_FP" to source, "TARGET_FP" to target)) {
            assertTrue(it.structureValid); assertTrue(it.compatible); assertTrue(it.regionVerified)
            assertFalse(it.signatureVerified); assertFalse(it.readyForOfficialReview)
        }
    }

    @Test fun absentRegionMetadataIsExplicitlyUnproven() = fixture(remove = setOf("motorola-channel")) {
        assertFalse(it.regionVerified); assertFalse(it.readyForOfficialReview); assertTrue(it.issues.any { issue -> issue.code == "REGION_UNPROVEN" })
    }

    @Test fun wrongSourceIncrementalIsRejected() = fixture(changes = mapOf("pre-build-incremental" to "older-incremental")) { assertIssue(it, "SOURCE_BUILD") }
    @Test fun wrongSourceFingerprintIsRejected() = fixture(changes = mapOf("pre-build" to sourceFp.replace("merc_g_sys", "merc_alias"))) { assertIssue(it, "SOURCE_FINGERPRINT") }
    @Test fun vendorFingerprintCannotReplaceRequiredSystemFingerprint() = fixture(
        observed = device.copy(vendorFingerprint = sourceFp.replace("merc_g_sys", "merc_alias")),
        changes = mapOf("pre-build" to sourceFp.replace("merc_g_sys", "merc_alias"))) { assertIssue(it, "SOURCE_FINGERPRINT") }
    @Test fun missingSourceIncrementalCannotProveCompatibility() = fixture(remove = setOf("pre-build-incremental")) { assertIssue(it, "SOURCE_INCOMPLETE") }
    @Test fun missingSourceFingerprintCannotProveCompatibility() = fixture(remove = setOf("pre-build")) { assertIssue(it, "SOURCE_INCOMPLETE") }
    @Test fun missingCurrentIncrementalDoesNotFallBackToBuildId() = fixture(observed = device.copy(incrementalBuild = "")) { assertIssue(it, "SOURCE_INCREMENTAL_UNKNOWN") }
    @Test fun targetOfAnotherManufacturerIsRejected() = fixture(changes = mapOf("post-build" to targetFp.replace("motorola/", "other/"))) { assertIssue(it, "TARGET_FINGERPRINT") }
    @Test fun targetOfAnotherCodenameIsRejected() = fixture(changes = mapOf("post-build" to targetFp.replace("/merc:", "/other:"))) { assertIssue(it, "TARGET_FINGERPRINT") }
    @Test fun targetIncrementalMustMatchItsFingerprint() = fixture(changes = mapOf("post-build-incremental" to "wrong")) { assertIssue(it, "TARGET_FINGERPRINT") }
    @Test fun malformedTargetFingerprintIsRejected() = fixture(changes = mapOf("post-build" to "not-a-fingerprint")) { assertIssue(it, "TARGET_FINGERPRINT") }
    @Test fun testFirmwareIsRejected() = fixture(changes = mapOf("post-build" to targetFp.replace("release-keys", "test-keys"))) { assertIssue(it, "TARGET_FINGERPRINT") }
    @Test fun userdebugFirmwareIsRejected() = fixture(changes = mapOf("post-build" to targetFp.replace(":user/", ":userdebug/"))) { assertIssue(it, "TARGET_FINGERPRINT") }
    @Test fun alreadyInstalledBuildIsNotOfferedAgain() = fixture(changes = mapOf("post-build" to targetFp.replace("NEXT_BUILD", "source-build"))) { assertIssue(it, "ALREADY_INSTALLED") }
    @Test fun userdataWipeIsRejected() = fixture(changes = mapOf("ota-wipe" to "yes")) { assertIssue(it, "DOWNGRADE_OR_WIPE") }
    @Test fun explicitDowngradeIsRejected() = fixture(changes = mapOf("ota-downgrade" to "yes")) { assertIssue(it, "DOWNGRADE_OR_WIPE") }
    @Test fun patchRollbackIsRejected() = fixture(changes = mapOf("post-security-patch-level" to "2026-09-01")) { assertIssue(it, "PATCH_ROLLBACK") }
    @Test fun invalidCalendarPatchDateIsRejected() = fixture(changes = mapOf("post-security-patch-level" to "2026-02-30")) { assertIssue(it, "PATCH_UNKNOWN") }
    @Test fun absentTargetPatchDateIsRejected() = fixture(remove = setOf("post-security-patch-level")) { assertIssue(it, "PATCH_UNKNOWN") }
    @Test fun absentCurrentPatchDateIsRejected() = fixture(observed = device.copy(securityPatch = "")) { assertIssue(it, "PATCH_UNKNOWN") }
    @Test fun olderTimestampIsRejected() = fixture(changes = mapOf("post-timestamp" to "999")) { assertIssue(it, "TIMESTAMP_ROLLBACK") }
    @Test fun equalTimestampRequiresReview() = fixture(changes = mapOf("post-timestamp" to "1000")) { assertIssue(it, "TIMESTAMP_ROLLBACK") }
    @Test fun missingTimestampIsRejected() = fixture(remove = setOf("post-timestamp")) { assertIssue(it, "TIMESTAMP_UNKNOWN") }
    @Test fun malformedTimestampIsRejected() = fixture(changes = mapOf("post-timestamp" to "not-a-time")) { assertIssue(it, "TIMESTAMP_UNKNOWN") }
    @Test fun unknownCurrentTimestampIsRejected() = fixture(observed = device.copy(buildTimestamp = 0)) { assertIssue(it, "TIMESTAMP_UNKNOWN") }
    @Test fun androidDowngradeIsRejected() = fixture(changes = mapOf("post-build" to targetFp.replace(":18/", ":16/"))) { assertIssue(it, "ANDROID_ROLLBACK") }
    @Test fun malformedTargetAndroidVersionIsRejected() = fixture(changes = mapOf("post-build" to targetFp.replace(":18/", ":unknown/"))) { assertIssue(it, "ANDROID_VERSION_UNKNOWN") }
    @Test fun unknownCurrentAndroidVersionIsRejected() = fixture(observed = device.copy(androidVersion = "")) { assertIssue(it, "ANDROID_VERSION_UNKNOWN") }
    @Test fun nonAbPackageIsRejected() = fixture(changes = mapOf("ota-type" to "BLOCK")) { assertInvalid(it) }
    @Test fun absentPayloadEntryIsRejected() = fixture(removeEntries = setOf("payload.bin")) { assertInvalid(it) }
    @Test fun absentPropertiesEntryIsRejected() = fixture(removeEntries = setOf("payload_properties.txt")) { assertInvalid(it) }
    @Test fun absentAndroidMetadataIsRejected() = fixture(removeEntries = setOf("META-INF/com/android/metadata")) { assertInvalid(it) }
    @Test fun invalidPayloadMagicIsRejected() = fixture(payload = payload().also { it[0] = 0 }) { assertInvalid(it) }
    @Test fun futurePayloadVersionIsRejected() = fixture(payload = payload(version = 3)) { assertInvalid(it) }
    @Test fun excessiveManifestLengthIsRejected() = fixture(payload = payload(manifestLength = Long.MAX_VALUE)) { assertInvalid(it) }
    @Test fun shortPayloadIsRejected() = fixture(payload = ByteArray(12)) { assertInvalid(it) }
    @Test fun payloadFileSizeMismatchIsRejected() = fixture(propertyChanges = mapOf("FILE_SIZE" to "999")) { assertInvalid(it) }
    @Test fun metadataHashMismatchIsRejected() = fixture(propertyChanges = mapOf("METADATA_HASH" to b64(ByteArray(32)))) { assertInvalid(it) }
    @Test fun metadataSizeMismatchIsRejected() = fixture(propertyChanges = mapOf("METADATA_SIZE" to "29")) { assertInvalid(it) }
    @Test fun malformedHashEncodingIsRejected() = fixture(propertyChanges = mapOf("FILE_HASH" to "not%base64")) { assertInvalid(it) }
    @Test fun payloadHashMismatchNeverCountsAsVerified() = fixture(propertyChanges = mapOf("FILE_HASH" to b64(ByteArray(32)))) {
        assertFalse(it.payloadHashValid); assertFalse(it.readyForOfficialReview); assertTrue(it.issues.any { issue -> issue.code == "PAYLOAD_HASH" })
    }
    @Test fun zipTraversalEntryIsRejectedEvenWithoutExtraction() = fixture(extraEntries = mapOf("../unsafe" to byteArrayOf(0))) { assertInvalid(it) }
    @Test fun absoluteZipEntryIsRejected() = fixture(extraEntries = mapOf("/unsafe" to byteArrayOf(0))) { assertInvalid(it) }
    @Test fun duplicateMetadataKeysAreRejected() = fixture(rawMetadataSuffix = "\npre-device=other\n") { assertInvalid(it) }

    @Test fun oemVendorMetadataAndSystemPayloadRemainSeparateAndBlocked() = oemFixture { report ->
        assertTrue(report.structureValid); assertTrue(report.payloadHashValid)
        assertIssue(report, "OEM_METADATA_REVIEW_REQUIRED")
        assertEquals("TARGET_17", report.targetBuild)
        assertEquals("17", report.targetAndroidVersion)
        assertEquals(oemSystemTarget, report.targetFingerprint)
        assertEquals(oemVendorSource, report.androidMetadataSourceFingerprint)
        assertEquals(oemVendorTarget, report.androidMetadataTargetFingerprint)
        assertEquals(oemSystemSource, report.payloadSourceFingerprint)
        assertEquals(oemSystemTarget, report.payloadTargetFingerprint)
        assertEquals(oemVendorTarget, report.metadata["post-build"])
        assertFalse(report.signatureVerified)
        assertFalse(report.copy(signatureVerified = true).readyForOfficialReview)
    }

    @Test fun installedOemSystemTargetIsNotMisreportedAsAndroid15Downgrade() = oemFixture(
        observed = device.copy(androidVersion = "17", build = "TARGET_17", fingerprint = oemSystemTarget,
            incrementalBuild = "target-system")) { report ->
        assertIssue(report, "ALREADY_INSTALLED")
        assertIssue(report, "OEM_METADATA_REVIEW_REQUIRED")
        assertEquals("17", report.targetAndroidVersion)
        assertFalse(report.issues.any { it.code == "ANDROID_ROLLBACK" })
    }

    @Test fun matchingPayloadPairDoesNotWeakenSourceIncrementalChecks() = fixture(
        changes = mapOf("pre-build-incremental" to "another-source"),
        propertyChanges = mapOf("SOURCE_FP" to sourceFp, "TARGET_FP" to targetFp)) {
        assertIssue(it, "SOURCE_BUILD")
    }

    @Test fun matchingPayloadAndAndroidFingerprintsRetainExistingCompatibilityChecks() = fixture(
        propertyChanges = mapOf("SOURCE_FP" to sourceFp, "TARGET_FP" to targetFp)) { report ->
        assertTrue(report.compatible)
        assertEquals(targetFp, report.targetFingerprint)
        assertEquals(targetFp, report.payloadTargetFingerprint)
        assertFalse(report.issues.any { it.code == "OEM_METADATA_REVIEW_REQUIRED" })
        assertFalse(report.signatureVerified); assertFalse(report.readyForOfficialReview)
    }

    @Test fun payloadSystemSourceCannotAliasVendorMetadataSource() = oemFixture { report ->
        assertIssue(report, "SOURCE_FINGERPRINT")
        assertIssue(report, "SOURCE_BUILD")
    }

    @Test fun partialPayloadFingerprintPairDoesNotReplaceStandardTarget() = fixture(
        propertyChanges = mapOf("SOURCE_FP" to sourceFp)) { report ->
        assertIssue(report, "PAYLOAD_FINGERPRINTS_INVALID")
        assertEquals(targetFp, report.targetFingerprint)
        assertEquals("18", report.targetAndroidVersion)
        assertEquals("", report.payloadTargetFingerprint)
    }

    @Test fun invalidPayloadFingerprintCannotBeExportedOrUsedAsTarget() {
        listOf("https://example.invalid/ota?token=synthetic", targetFp.replace("/merc:", "/other:"),
            targetFp.replace("release-keys", "test-keys"), targetFp.replace("motorola/", "other/"),
            targetFp.replace(":18/", ":unknown/")).forEach { invalid ->
            fixture(propertyChanges = mapOf("SOURCE_FP" to sourceFp, "TARGET_FP" to invalid)) { report ->
                assertIssue(report, "PAYLOAD_FINGERPRINTS_INVALID")
                assertEquals(targetFp, report.targetFingerprint)
                assertEquals("", report.payloadTargetFingerprint)
                assertFalse(report.payloadProperties.values.contains(invalid))
            }
        }
    }

    @Test fun standardMetadataCannotMasqueradeAsPayloadFingerprintProperties() = fixture(
        changes = mapOf("SOURCE_FP" to sourceFp, "TARGET_FP" to targetFp)) { report ->
        assertEquals("", report.payloadSourceFingerprint)
        assertEquals("", report.payloadTargetFingerprint)
        assertTrue(report.payloadProperties.isEmpty())
    }

    /** Explicit opt-in; reads the host file only and does not bundle or modify firmware. */
    @Test fun observedHistoricalPackageIsCorrectlyDiagnosedWhenExplicitlyProvided() {
        val path = System.getenv("MOTO_OTA_PATH").orEmpty()
        assumeTrue("Set MOTO_OTA_PATH to opt in to the retained historical-package check.", path.isNotBlank())
        val file = File(path)
        assertTrue("The explicit historical-package input is not a readable file.", file.isFile && file.canRead())
        val current = device.copy(androidVersion = "17", build = "A171WMH.44-25",
            incrementalBuild = "0347f0-d290bf",
            fingerprint = "motorola/merc_g_syseq/merc:17/A171WMH.44-25/0347f0-d290bf:user/release-keys")
        val report = FirmwareValidator.inspect(file, current)
        assertTrue(report.structureValid); assertTrue(report.payloadHashValid)
        assertEquals(3_417_373_748L, report.packageBytes)
        assertEquals("7b548666bfb8ca2ef8a65026f449af35b87354a8b098424fda52c434d0502c90", report.sha256)
        assertEquals("A171WMH.44-25", report.targetBuild)
        assertEquals("17", report.targetAndroidVersion)
        assertTrue(report.androidMetadataTargetFingerprint.contains("merc_g_vext/merc:15/"))
        assertTrue(report.payloadTargetFingerprint.contains("merc_g_sys/merc:17/"))
        assertEquals(report.payloadTargetFingerprint, report.targetFingerprint)
        assertIssue(report, "OEM_METADATA_REVIEW_REQUIRED")
        assertIssue(report, "ALREADY_INSTALLED")
        assertFalse(report.issues.any { it.code == "ANDROID_ROLLBACK" })
        // Whole-file signature verification is performed separately against the actual device keys.
        assertFalse(report.signatureVerified); assertFalse(report.readyForOfficialReview)
        println("Retained OTA: structure=true; payloadHash=true; targetAndroid=17; alreadyInstalled=true; OEM review required; compatible=false")
    }

    @Test fun nonZipInputIsRejectedWithoutException() {
        val file = File.createTempFile("ota-notzip-", ".zip")
        try { file.writeText("not a ZIP"); assertInvalid(FirmwareValidator.inspect(file, device)) } finally { file.delete() }
    }

    @Test fun emptyInputIsRejectedWithoutException() {
        val file = File.createTempFile("ota-empty-", ".zip")
        try { assertInvalid(FirmwareValidator.inspect(file, device)) } finally { file.delete() }
    }

    @Test fun officialUrlPolicyAllowsOnlyExactTlsHost() {
        val host = "dlmgredg-vz.gtm.svcmot.com"
        assertTrue(OfficialDownloadPolicy.accepts("https://$host/ota.zip?short_lived=sensitive"))
        listOf("http://$host/a", "https://$host.evil.invalid/a", "https://evil-$host/a", "https://$host@evil.invalid/a",
            "https://user@$host/a", "https://$host:444/a", "https://$host/a#x", "https://127.0.0.1/a",
            "file:///data/a", "https://$host\\@evil.invalid/a", "https://$host/a\nInjected").forEach {
            assertFalse("Untrusted endpoint accepted", OfficialDownloadPolicy.accepts(it))
        }
    }

    private fun assertIssue(report: FirmwareReport, code: String) {
        assertFalse(report.compatible)
        assertFalse(report.readyForOfficialReview)
        assertTrue("Missing $code; got ${report.issues.map { it.code }}", report.issues.any { it.code == code })
    }
    private fun assertInvalid(report: FirmwareReport) {
        assertFalse(report.structureValid); assertFalse(report.readyForOfficialReview)
        assertTrue(report.issues.any { it.code == "INVALID_PACKAGE" })
    }

    private fun payload(version: Long = 2, manifestLength: Long = 4): ByteArray = ByteBuffer.allocate(44)
        .order(ByteOrder.BIG_ENDIAN).put("CrAU".toByteArray()).putLong(version).putLong(manifestLength).putInt(0)
        .put(byteArrayOf(1, 2, 3, 4)).put(ByteArray(16) { 7 }).array()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    // Synthetic payload bytes plus the observed vendor-15/system-16-to-17 relationship.
    // No proprietary firmware bytes or signed download links are included in these fixtures.
    private val oemVendorSource = "motorola/merc_g_vext/merc:15/SOURCE_16/source:user/release-keys"
    private val oemVendorTarget = "motorola/merc_g_vext/merc:15/TARGET_17/target:user/release-keys"
    private val oemSystemSource = "motorola/merc_g_sys/merc:16/SOURCE_16/source-system:user/release-keys"
    private val oemSystemTarget = "motorola/merc_g_sys/merc:17/TARGET_17/target-system:user/release-keys"

    private fun oemFixture(
        observed: DeviceState = device.copy(androidVersion = "16", build = "SOURCE_16",
            fingerprint = oemSystemSource, incrementalBuild = "source-system"),
        assertion: (FirmwareReport) -> Unit
    ) = fixture(observed = observed, changes = mapOf("pre-build" to oemVendorSource,
        "pre-build-incremental" to "source", "post-build" to oemVendorTarget,
        "post-build-incremental" to "target", "post-sdk-level" to "35"),
        propertyChanges = mapOf("SOURCE_FP" to oemSystemSource, "TARGET_FP" to oemSystemTarget), assertion = assertion)

    private fun fixture(
        changes: Map<String, String> = emptyMap(), remove: Set<String> = emptySet(), observed: DeviceState = device,
        payload: ByteArray = payload(), propertyChanges: Map<String, String> = emptyMap(),
        removeEntries: Set<String> = emptySet(), extraEntries: Map<String, ByteArray> = emptyMap(),
        rawMetadataSuffix: String = "", assertion: (FirmwareReport) -> Unit
    ) {
        val metadata = linkedMapOf("ota-type" to "AB", "pre-device" to "merc", "pre-model" to "XT2607-1", "pre-build" to sourceFp,
            "pre-build-incremental" to "source-incremental", "post-build" to targetFp,
            "post-build-incremental" to "target-incremental", "motorola-channel" to "reteu",
            "post-security-patch-level" to "2026-11-01", "post-timestamp" to "2000").apply { putAll(changes); remove.forEach { remove(it) } }
        val properties = linkedMapOf("FILE_SIZE" to payload.size.toString(), "FILE_HASH" to b64(sha(payload)),
            "METADATA_SIZE" to "28", "METADATA_HASH" to b64(sha(payload.take(28).toByteArray()))).apply { putAll(propertyChanges) }
        val entries = linkedMapOf("META-INF/com/android/metadata" to (metadata.entries.joinToString("\n") { "${it.key}=${it.value}" } + rawMetadataSuffix).toByteArray(),
            "payload_properties.txt" to properties.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(), "payload.bin" to payload)
            .apply { removeEntries.forEach { remove(it) }; putAll(extraEntries) }
        val file = File.createTempFile("moto-ota-test-", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
            assertion(FirmwareValidator.inspect(file, observed))
        } finally { file.delete() }
    }
}
