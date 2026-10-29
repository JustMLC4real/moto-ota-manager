package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class StockEvidenceStoragePolicyTest {
    private val fp = "motorola/merc_g_sys/merc:17/NEXT.17/synthetic:user/release-keys"
    private fun proof() = SignedStockEvidence("a".repeat(64), "NEXT.17", "17", "synthetic", fp,
        "b".repeat(64), 8192, setOf("c".repeat(64)), 1000, device = "merc", model = "XT2607-1", channel = "reteu",
        androidFingerprints = setOf(fp), identityTimestamp = 2000, identitySecurityPatch = "2026-10-01")

    @Test fun storedProofRequiresConsistentHashesSizesFingerprintsAndTrustAnchors() {
        val e = proof()
        assertTrue(StockEvidencePolicy.acceptsStored(e))
        listOf(e.copy(imageSha256 = "invalid"), e.copy(imageBytes = 0), e.copy(imageBytes = 512L * 1024 * 1024 + 1),
            e.copy(targetBuild = "OTHER"), e.copy(targetAndroid = "18"), e.copy(targetIncremental = "OTHER"),
            e.copy(trustedSpki = emptySet()), e.copy(measuredSystemFingerprint = "https://private.example"),
            e.copy(measuredSystemFingerprint = fp.replace("NEXT.17", "OTHER")), e.copy(proofScope = "INSTALL_ALLOWED"),
            e.copy(partition = "vendor_boot"), e.copy(model = ""), e.copy(channel = ""), e.copy(androidFingerprints = emptySet()),
            e.copy(identityTimestamp = 0), e.copy(kind = StockEvidenceKind.SOURCE)).forEach {
            assertFalse(StockEvidencePolicy.acceptsStored(it))
        }
    }

    @Test fun excessivelyLargeFingerprintCannotEnterStoredProof() {
        val oversized = fp.replace("merc_g_sys", "x".repeat(513))
        assertFalse(StockEvidencePolicy.acceptsStored(proof().copy(payloadTargetFingerprint = oversized)))
    }

    @Test fun verifiedProofCopiesMutableAnchorCollectionBeforePersistence() {
        val anchors = mutableSetOf("c".repeat(64))
        val report = FirmwareReport(structureValid = true, payloadHashValid = true, signatureVerified = true,
            regionVerified = true, targetBuild = "NEXT.17", targetFingerprint = fp, sha256 = "a".repeat(64), metadata = mapOf(
                "ota-type" to "AB", "pre-device" to "merc", "pre-model" to "XT2607-1", "pre-channel" to "reteu",
                "post-build" to fp, "post-build-incremental" to "synthetic", "post-timestamp" to "2000", "post-security-patch-level" to "2026-10-01"))
        val manifest = PayloadManifestInfo(4096, null, PartitionDigest("init_boot", 8192, "b".repeat(64)), listOf("init_boot"), 100)
        val created = requireNotNull(StockEvidencePolicy.create(report, manifest, anchors, 1000))
        anchors.clear()
        assertEquals(setOf("c".repeat(64)), created.trustedSpki)
        assertTrue(StockEvidencePolicy.acceptsStored(created))
    }
}
