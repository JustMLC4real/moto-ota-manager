package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class StockEvidenceStoragePolicyTest {
    private val fp = "motorola/merc_g_sys/merc:17/NEXT.17/synthetic:user/release-keys"
    private fun proof() = SignedStockEvidence("a".repeat(64), "NEXT.17", "17", "synthetic", fp,
        "b".repeat(64), 8192, setOf("c".repeat(64)), 1000)

    @Test fun storedProofRequiresConsistentHashesSizesFingerprintsAndTrustAnchors() {
        val e = proof()
        assertTrue(StockEvidencePolicy.acceptsStored(e))
        listOf(e.copy(imageSha256 = "invalid"), e.copy(imageBytes = 0), e.copy(imageBytes = 128L * 1024 * 1024 + 1),
            e.copy(targetBuild = "OTHER"), e.copy(targetAndroid = "18"), e.copy(targetIncremental = "OTHER"),
            e.copy(trustedSpki = emptySet()), e.copy(measuredSystemFingerprint = "https://private.example"),
            e.copy(measuredSystemFingerprint = fp.replace("NEXT.17", "OTHER"))).forEach {
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
            targetBuild = "NEXT.17", targetFingerprint = fp, sha256 = "a".repeat(64), metadata = mapOf("pre-device" to "merc"))
        val manifest = PayloadManifestInfo(4096, null, PartitionDigest("init_boot", 8192, "b".repeat(64)), listOf("init_boot"), 100)
        val created = requireNotNull(StockEvidencePolicy.create(report, manifest, anchors, 1000))
        anchors.clear()
        assertEquals(setOf("c".repeat(64)), created.trustedSpki)
        assertTrue(StockEvidencePolicy.acceptsStored(created))
    }
}
