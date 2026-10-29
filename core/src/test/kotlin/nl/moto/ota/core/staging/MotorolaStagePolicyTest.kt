package nl.moto.ota.core.staging

import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import org.junit.Assert.*
import org.junit.Test

class MotorolaStagePolicyTest {
    private val source = "motorola/merc_g_syseq/merc:17/SOURCE/123:user/release-keys"
    private val target = "motorola/merc_g_sys/merc:17/TARGET/124:user/release-keys"
    private val stockHash = "a".repeat(64)
    private val packageHash = "b".repeat(64)
    private val device = DeviceState(manufacturer = "motorola", model = "XT2607-1", device = "merc", channel = "reteu", androidVersion = "17",
        build = "SOURCE", fingerprint = source, incrementalBuild = "123", buildTimestamp = 100,
        securityPatch = "2026-10-01", slot = "_a", bootId = "12345678-1234-1234-1234-123456789abc",
        rootChecked = true, rootGranted = true, magiskCode = 31100, virtualAb = true, snapshot = SnapshotState.NONE,
        engine = EngineState.IDLE, batteryPercent = 80, capturedAtMillis = 1_000, bootSuccessful = true, bootloaderUnlocked = true, verifiedBootState = "orange")
    private val run = UpdateRun(id = "87654321-1234-1234-1234-123456789abc", stage = UpdateStage.STOCK_RESTORED,
        sourceBuild = device.build, sourceFingerprint = source, sourceSlot = "_a", targetSlot = "_b", sourceBootId = device.bootId,
        targetBuild = "TARGET", declaredTargetFingerprint = target, packageSha256 = packageHash, packageVerified = true,
        stockSha256 = stockHash, stockRestoreEvidence = StepEvidence.OBSERVED)
    private val report = FirmwareReport(structureValid = true, payloadHashValid = true, signatureVerified = true, compatible = false, regionVerified = true,
        sourceBuild = "123", targetBuild = "TARGET", targetFingerprint = target, packageBytes = 4096, sha256 = packageHash,
        metadata = mapOf("ota-type" to "AB", "pre-device" to "merc", "pre-build" to source, "pre-build-incremental" to "123",
            "post-build" to target, "post-build-incremental" to "124", "post-timestamp" to "200", "post-security-patch-level" to "2026-11-01",
            "motorola-channel" to "reteu"), payloadProperties = mapOf("SOURCE_FP" to source, "TARGET_FP" to target))
    private fun environment() = StageEnvironment(device, 37, true, stockHash, 1_001)
    private fun rejected(code: StageError, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: StageFailure) { assertEquals(code, error.code); assertEquals(code.name, error.message) }
    }

    @Test fun copyOnlyOfficialReviewKeepsCompatibilityBlockedDespiteProvenChannel() {
        MotorolaStagePolicy.requireSafe(report, run, environment())
        assertFalse(report.compatible); assertTrue(report.regionVerified)
        assertEquals(source, report.payloadSourceFingerprint)
    }

    @Test fun bothConcreteConfirmationsAreRequired() {
        for ((explicit, recognized) in listOf(false to false, false to true, true to false))
            rejected(StageError.STAGING_CONFIRMATION_REQUIRED) { MotorolaStagePolicy.requireConfirmed(explicit, recognized) }
        MotorolaStagePolicy.requireConfirmed(true, true)
    }

    @Test fun wrongProfileRuntimeBootAndChangedSourceAreBlocked() {
        rejected(StageError.STAGING_RUNTIME_UNSUPPORTED) { MotorolaStagePolicy.requireSafe(report, run, environment().copy(sdk = 35)) }
        rejected(StageError.STAGING_DEVICE_UNSAFE) { MotorolaStagePolicy.requireSafe(report, run, environment().copy(device = device.copy(model = "other"))) }
        for (changed in listOf(run.copy(sourceBuild = "OTHER"), run.copy(sourceSlot = "_b"), run.copy(sourceFingerprint = "other"), run.copy(sourceBootId = "other")))
            rejected(StageError.STAGING_SOURCE_CHANGED) { MotorolaStagePolicy.requireSafe(report, changed, environment()) }
    }

    @Test fun staleMergeEngineAutomaticUpdateAndUnknownSafetyFailClosed() {
        val environments = listOf(environment().copy(nowMillis = 40_000), environment().copy(automaticUpdatesDisabled = false),
            environment().copy(automaticUpdatesDisabled = null), environment().copy(device = device.copy(snapshot = SnapshotState.MERGING)),
            environment().copy(device = device.copy(engine = EngineState.UNKNOWN)), environment().copy(device = device.copy(magiskCode = 31000)),
            environment().copy(device = device.copy(bootSuccessful = null)), environment().copy(device = device.copy(batteryPercent = 49)))
        environments.forEach { rejected(StageError.STAGING_DEVICE_UNSAFE) { MotorolaStagePolicy.requireSafe(report, run, it) } }
    }

    @Test fun userReportedStockAndMismatchedCurrentPartitionCannotStage() {
        rejected(StageError.STAGING_STOCK_UNPROVEN) { MotorolaStagePolicy.requireSafe(report, run.copy(stockRestoreEvidence = StepEvidence.USER_REPORTED), environment()) }
        rejected(StageError.STAGING_STOCK_UNPROVEN) { MotorolaStagePolicy.requireSafe(report, run, environment().copy(activeStockSha256 = "c".repeat(64))) }
    }

    @Test fun unsignedUnpinnedOrWrongTargetPackagesAreBlocked() {
        rejected(StageError.STAGING_PACKAGE_UNVERIFIED) { MotorolaStagePolicy.requireSafe(report.copy(signatureVerified = false), run, environment()) }
        rejected(StageError.STAGING_PACKAGE_UNVERIFIED) { MotorolaStagePolicy.requireSafe(report, run.copy(packageSha256 = "c".repeat(64)), environment()) }
        rejected(StageError.STAGING_METADATA_MISMATCH) { MotorolaStagePolicy.requireSafe(report.copy(targetBuild = "OTHER"), run, environment()) }
        rejected(StageError.STAGING_METADATA_MISMATCH) { MotorolaStagePolicy.requireSafe(report.copy(metadata = report.metadata + ("pre-device" to "another")), run, environment()) }
    }

    @Test fun oemNamespacesRemainSeparateAndNeedIndependentLivePropertyEvidence() {
        val payloadSource = source.replace("merc_g_syseq", "merc_g_sys")
        val vendor = "motorola/merc/merc:15/VENDOR/77:user/release-keys"
        val oem = report.copy(sourceBuild = "77", metadata = report.metadata + ("pre-build" to vendor) + ("pre-build-incremental" to "77"),
            payloadProperties = report.payloadProperties + ("SOURCE_FP" to payloadSource),
            issues = report.issues + CheckResult("SOURCE_BUILD", "vendor namespace") + CheckResult("SOURCE_FINGERPRINT", "vendor namespace") +
                CheckResult("OEM_METADATA_REVIEW_REQUIRED", "separate declarations"))
        rejected(StageError.STAGING_METADATA_MISMATCH) { MotorolaStagePolicy.requireSafe(oem, run, environment()) }
        MotorolaStagePolicy.requireSafe(oem, run, environment().copy(device = device.copy(vendorFingerprint = vendor)))
        assertEquals(vendor, oem.androidMetadataSourceFingerprint)
        assertEquals(payloadSource, oem.payloadSourceFingerprint)
        assertFalse(oem.compatible)
        rejected(StageError.STAGING_PACKAGE_UNVERIFIED) { MotorolaStagePolicy.requireSafe(oem, run.copy(route = UpdateRoute.AUTHENTICATED_PACKAGE), environment().copy(device = device.copy(vendorFingerprint = vendor))) }
    }

    @Test fun matchingProductSuffixDoesNotMakeWrongBuildOrIncrementalAcceptable() {
        for (wrong in listOf(source.replace("/SOURCE/", "/OTHER/"), source.replace("/123:", "/999:"), source.replace(":17/", ":16/")))
            rejected(StageError.STAGING_METADATA_MISMATCH) {
                MotorolaStagePolicy.requireSafe(report.copy(payloadProperties = report.payloadProperties + ("SOURCE_FP" to wrong)), run, environment())
            }
    }

    @Test fun signedWrongOrConflictingChannelCannotBeOverriddenByRegionFlag() {
        for (metadata in listOf(report.metadata + ("motorola-channel" to "retus"), report.metadata + ("pre-channel" to "retus"),
            report.metadata + ("motorola-channel" to "") + ("pre-channel" to "reteu")))
            rejected(StageError.STAGING_METADATA_MISMATCH) { MotorolaStagePolicy.requireSafe(report.copy(metadata = metadata), run, environment()) }
    }

    @Test fun missingSignedChannelBlocksEvenOfficialRouteCorrectDeviceAndExplicitWarningConsent() {
        MotorolaStagePolicy.requireConfirmed(true, true)
        assertEquals("reteu", environment().device.channel)
        assertEquals(UpdateRoute.OFFICIAL_MOTOROLA, run.route)
        val noChannel = report.copy(metadata = report.metadata - "motorola-channel", regionVerified = false,
            issues = listOf(CheckResult("REGION_UNPROVEN", "No signed package channel")))
        rejected(StageError.STAGING_CHANNEL_UNPROVEN) { MotorolaStagePolicy.requireSafe(noChannel, run, environment()) }
        rejected(StageError.STAGING_CHANNEL_UNPROVEN) {
            MotorolaStagePolicy.requireSafe(noChannel.copy(regionVerified = true), run, environment())
        }
    }

    @Test fun channelTextOrRegionFlagAloneCannotProveSignedRegion() {
        for (unproven in listOf(report.copy(regionVerified = false),
            report.copy(metadata = report.metadata + ("motorola-channel" to "")),
            report.copy(metadata = (report.metadata - "motorola-channel") + ("carrier" to "reteu")),
            report.copy(issues = listOf(CheckResult("REGION_UNPROVEN", "Contradictory proof")))))
            rejected(StageError.STAGING_CHANNEL_UNPROVEN) { MotorolaStagePolicy.requireSafe(unproven, run, environment()) }
        rejected(StageError.STAGING_METADATA_MISMATCH) {
            MotorolaStagePolicy.requireSafe(report.copy(issues = listOf(CheckResult("WRONG_REGION", "Signed conflict"))), run, environment())
        }
    }

    @Test fun signedReteuPreChannelOrConsistentDualDeclarationsAreAcceptedAfterVerification() {
        for (metadata in listOf((report.metadata - "motorola-channel") + ("pre-channel" to "reteu"),
            report.metadata + ("pre-channel" to "reteu")))
            MotorolaStagePolicy.requireSafe(report.copy(metadata = metadata), run, environment())
        rejected(StageError.STAGING_PACKAGE_UNVERIFIED) {
            MotorolaStagePolicy.requireSafe(report.copy(signatureVerified = false), run, environment())
        }
    }

    @Test fun wipeDowngradeRollbackAndUnknownTimesOrPatchAreBlocked() {
        val modified = listOf(report.metadata + ("ota-wipe" to "yes"), report.metadata + ("ota-downgrade" to "yes"),
            report.metadata + ("post-timestamp" to "100"), report.metadata - "post-timestamp", report.metadata + ("post-security-patch-level" to "2026-09-01"),
            report.metadata - "post-security-patch-level")
        modified.forEach { rejected(StageError.STAGING_ROLLBACK_OR_WIPE) { MotorolaStagePolicy.requireSafe(report.copy(metadata = it), run, environment()) } }
    }
    @Test fun changedDurableRunCannotPublishUsingAnEarlierSnapshot() {
        MotorolaStagePolicy.requireSameRun(run, run.copy(lastObservationMillis = 2_000))
        for (changed in listOf(run.copy(id = "other"), run.copy(stage = UpdateStage.OTA_APPLYING), run.copy(packageSha256 = "c".repeat(64)),
            run.copy(sourceSlot = "_b"), run.copy(stockRestoreEvidence = StepEvidence.USER_REPORTED), run.copy(otaAppliedObserved = true)))
            rejected(StageError.STAGING_SOURCE_CHANGED) { MotorolaStagePolicy.requireSameRun(run, changed) }
    }
}
