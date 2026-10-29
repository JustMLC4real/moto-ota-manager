package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class StockEvidenceTest {
    private val now = 10_000_000L
    private val anchors = setOf("a".repeat(64))
    private val payloadFp = "motorola/merc_g_sys/merc:17/A171WMH.44-26/next123:user/release-keys"
    private val actualFp = "motorola/merc_g_syseq/merc:17/A171WMH.44-26/next123:user/release-keys"
    private val report = FirmwareReport(structureValid = true, payloadHashValid = true, signatureVerified = true,
        targetBuild = "A171WMH.44-26", targetFingerprint = payloadFp, sha256 = "b".repeat(64), metadata = mapOf("pre-device" to "merc"))
    private val manifest = PayloadManifestInfo(4096, null, PartitionDigest("init_boot", 8192, "c".repeat(64)), listOf("init_boot"), 100)
    private val target get() = safetyDevice().copy(build = report.targetBuild, fingerprint = actualFp,
        incrementalBuild = "next123", capturedAtMillis = now)
    private fun evidence() = requireNotNull(StockEvidencePolicy.create(report, manifest, anchors, now))

    @Test fun signatureAndManifestProveContentButNeverClaimInstallationCompatibility() {
        assertFalse(report.compatible)
        assertFalse(report.regionVerified)
        val e = evidence()
        assertEquals(payloadFp, e.payloadTargetFingerprint)
        assertEquals("", e.measuredSystemFingerprint)
        assertNull(StockEvidencePolicy.reference(e, target, anchors))
    }
    @Test fun onlySuccessfulFreshTargetBootBindsMeasuredSystemNamespace() {
        val e = requireNotNull(StockEvidencePolicy.bind(evidence(), target, now))
        assertEquals(actualFp, e.measuredSystemFingerprint)
        val reference = requireNotNull(StockEvidencePolicy.reference(e, target, anchors))
        assertEquals(actualFp, reference.fingerprint)
        assertEquals(manifest.initBootTarget.sha256, reference.sha256)
        assertTrue(reference.authenticityVerified)
        assertNull(StockEvidencePolicy.reference(e, safetyDevice(), anchors))
    }
    @Test fun unknownMergeBootOrRootCannotBindDownloadedClaims() {
        val unsafe = listOf(target.copy(snapshot = SnapshotState.UNKNOWN), target.copy(snapshot = SnapshotState.MERGING),
            target.copy(engine = EngineState.UPDATED_NEED_REBOOT), target.copy(rootChecked = false), target.copy(rootGranted = false),
            target.copy(bootSuccessful = null), target.copy(bootSuccessful = false), target.copy(moduleInventoryKnown = false),
            target.copy(bootloaderUnlocked = null), target.copy(verifiedBootState = "green"), target.copy(simulated = true),
            target.copy(capturedAtMillis = now - 120_001), target.copy(capturedAtMillis = now + 1))
        unsafe.forEach { assertNull(it.toString(), StockEvidencePolicy.bind(evidence(), it, now)) }
    }
    @Test fun wrongProfileBuildIncrementalAndAndroidNeverReceiveReference() {
        listOf(target.copy(model = "XT2607-2"), target.copy(device = "other"), target.copy(channel = "retus"),
            target.copy(build = "A171WMH.44-25"), target.copy(androidVersion = "18"), target.copy(incrementalBuild = ""),
            target.copy(fingerprint = actualFp.replace("next123", "other"))).forEach {
            assertNull(StockEvidencePolicy.bind(evidence(), it, now))
        }
    }
    @Test fun alreadyBoundSystemFingerprintCannotSilentlyChangeAlias() {
        val bound = requireNotNull(StockEvidencePolicy.bind(evidence(), target, now))
        assertNull(StockEvidencePolicy.bind(bound, target.copy(fingerprint = payloadFp), now))
    }
    @Test fun anyTrustStoreChangeRequiresNewSignatureVerification() {
        val bound = requireNotNull(StockEvidencePolicy.bind(evidence(), target, now))
        assertNull(StockEvidencePolicy.reference(bound, target, emptySet()))
        assertNull(StockEvidencePolicy.reference(bound, target, anchors + "d".repeat(64)))
        assertNull(StockEvidencePolicy.reference(bound, target, setOf("e".repeat(64))))
    }
    @Test fun invalidPersistedProofCannotCreateStockReference() {
        val bound = requireNotNull(StockEvidencePolicy.bind(evidence(), target, now))
        listOf(bound.copy(packageSha256 = ""), bound.copy(verifiedAtMillis = 0), bound.copy(trustedSpki = emptySet()),
            bound.copy(payloadTargetFingerprint = payloadFp.replace("merc:", "other:")), bound.copy(imageBytes = 0),
            bound.copy(imageSha256 = "invalid")).forEach { assertNull(StockEvidencePolicy.reference(it, target, it.trustedSpki)) }
    }
    @Test fun unsignedMalformedAndOtherDevicePackagesCannotCreateProofs() {
        listOf(report.copy(signatureVerified = false), report.copy(payloadHashValid = false), report.copy(structureValid = false),
            report.copy(sha256 = "bad"), report.copy(targetBuild = "other"), report.copy(metadata = mapOf("pre-device" to "other")),
            report.copy(targetFingerprint = payloadFp.replace("motorola", "other"))).forEach {
            assertNull(StockEvidencePolicy.create(it, manifest, anchors, now))
        }
        assertNull(StockEvidencePolicy.create(report, manifest.copy(initBootTarget = manifest.initBootTarget.copy(name = "boot")), anchors, now))
        assertNull(StockEvidencePolicy.create(report, manifest, emptySet(), now))
    }
}
