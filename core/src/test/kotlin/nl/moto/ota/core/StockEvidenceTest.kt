package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class StockEvidenceTest {
    private val now = 10_000_000L
    private val anchors = setOf("a".repeat(64))
    private val sourceFp = "motorola/merc_g_sys/merc:17/CURRENT.17/current123:user/release-keys"
    private val targetFp = "motorola/merc_g_sys/merc:17/NEXT.17/next123:user/release-keys"
    private val current get() = safetyDevice().copy(build = "CURRENT.17", fingerprint = sourceFp,
        vendorFingerprint = sourceFp, incrementalBuild = "current123", buildTimestamp = 1000, capturedAtMillis = now)
    private val target get() = current.copy(build = "NEXT.17", fingerprint = targetFp, vendorFingerprint = targetFp,
        incrementalBuild = "next123", buildTimestamp = 2000)
    private val report get() = FirmwareReport(structureValid = true, payloadHashValid = true, signatureVerified = true,
        regionVerified = true, targetBuild = "NEXT.17", targetFingerprint = targetFp, sha256 = "b".repeat(64), metadata = mapOf(
            "ota-type" to "AB", "pre-device" to "merc", "pre-model" to "XT2607-1", "pre-channel" to "reteu",
            "pre-build" to sourceFp, "pre-build-incremental" to "current123", "post-build" to targetFp,
            "post-build-incremental" to "next123", "post-timestamp" to "2000", "post-security-patch-level" to "2026-10-01"),
        payloadProperties = mapOf("SOURCE_FP" to sourceFp, "TARGET_FP" to targetFp))
    private val manifest = PayloadManifestInfo(4096, PartitionDigest("init_boot", 8192, "c".repeat(64)),
        PartitionDigest("init_boot", 8192, "d".repeat(64)), listOf("boot", "init_boot"), 100,
        PartitionDigest("boot", 16384, "e".repeat(64)), PartitionDigest("boot", 16384, "f".repeat(64)))
    private fun evidence() = requireNotNull(StockEvidencePolicy.create(report, manifest, anchors, now))
    private fun reference(e: SignedStockEvidence, d: DeviceState = target, a: Set<String> = anchors) = StockEvidencePolicy.reference(e, d, a, now)
    private fun all(r: FirmwareReport = report, d: DeviceState = current) = StockEvidencePolicy.createAll(r, manifest, anchors, d, now)

    @Test fun propertySpoofingCannotOverrideTrustedKernelSecurityOrReplaceIt() {
        assertEquals(4,all(d=current.copy(securityPropertyMismatch=true,securityEvidenceSource="KERNEL_BOOTCONFIG")).size)
        assertTrue(all(d=current.copy(securityPropertyMismatch=true,securityEvidenceSource="ANDROID_PROPERTIES")).isEmpty())
        assertTrue(all(d=current.copy(securityPropertyMismatch=true,securityEvidenceSource="KERNEL_BOOTCONFIG",bootloaderUnlocked=false)).isEmpty())
        assertTrue(all(d=current.copy(securityPropertyMismatch=true,securityEvidenceSource="KERNEL_BOOTCONFIG",verifiedBootState="green")).isEmpty())
    }

    @Test fun authenticatedOldAndNewBootAndInitBootAreSeparateReferences() {
        val proofs = all()
        assertEquals(4, proofs.size)
        val source = proofs.filter { it.kind == StockEvidenceKind.SOURCE }
        assertEquals(setOf("boot", "init_boot"), source.map { it.partition }.toSet())
        assertEquals(setOf("c".repeat(64), "e".repeat(64)), source.map { requireNotNull(reference(it, current)).sha256 }.toSet())
        proofs.filter { it.kind == StockEvidenceKind.TARGET }.forEach { assertNull(reference(it)) }
    }
    @Test fun targetClaimsBindOnlyAfterSuccessfulFreshActualTargetBoot() {
        val e = evidence()
        assertEquals("", e.measuredSystemFingerprint)
        assertNull(reference(e))
        val bound = requireNotNull(StockEvidencePolicy.bind(e, target, now))
        assertEquals(targetFp, requireNotNull(reference(bound)).fingerprint)
        assertNull(reference(bound, current))
    }
    @Test fun unknownMergeRootBootAndStaleObservationsCannotBindOrReadProof() {
        val bound = requireNotNull(StockEvidencePolicy.bind(evidence(), target, now))
        listOf(target.copy(snapshot = SnapshotState.UNKNOWN), target.copy(snapshot = SnapshotState.MERGING),
            target.copy(engine = EngineState.UPDATED_NEED_REBOOT), target.copy(rootChecked = false), target.copy(rootGranted = false),
            target.copy(bootSuccessful = null), target.copy(moduleInventoryKnown = false), target.copy(bootloaderUnlocked = null),
            target.copy(verifiedBootState = "green"), target.copy(simulated = true), target.copy(identityConflicts = listOf("MODEL")),
            target.copy(capturedAtMillis = now - 120_001), target.copy(capturedAtMillis = now + 1)).forEach {
            assertNull(StockEvidencePolicy.bind(evidence(), it, now)); assertNull(reference(bound, it))
        }
    }
    @Test fun otherMeasuredMotorolaCanUseItsOwnSignedProfileWithoutMercAlias() {
        val d = current.copy(model = "XT9999-2", device = "synthetic", channel = "retus",
            fingerprint = sourceFp.replace("merc", "synthetic"), vendorFingerprint = sourceFp.replace("merc", "synthetic"))
        val r = report.copy(targetFingerprint = targetFp.replace("merc", "synthetic"),
            metadata = report.metadata.mapValues { (_, v) -> v.replace("merc", "synthetic").replace("XT2607-1", "XT9999-2").replace("reteu", "retus") },
            payloadProperties = report.payloadProperties.mapValues { (_, v) -> v.replace("merc", "synthetic") })
        val proofs = all(r, d)
        assertEquals(4, proofs.size)
        val ref = requireNotNull(reference(proofs.first { it.kind == StockEvidenceKind.SOURCE }, d))
        assertEquals("retus", ref.channel); assertEquals("XT9999-2", ref.model)
        assertNull(reference(proofs.first { it.kind == StockEvidenceKind.SOURCE }, current))
    }
    @Test fun missingModelAndChannelAllowOnlyCurrentPartitionByteEvidence() {
        val r = report.copy(regionVerified = false, metadata = report.metadata - "pre-model" - "pre-channel")
        val proofs = all(r)
        assertEquals(4, proofs.size)
        assertFalse(r.readyForOfficialReview)
        assertTrue(proofs.all { it.proofScope == "CURRENT_PARTITION_BYTES" })
        assertTrue(requireNotNull(reference(proofs.first { it.kind == StockEvidenceKind.SOURCE }, current)).evidence.contains("Magisk runtime SHA1"))
    }
    @Test fun contradictorySignedModelChannelAndDeviceNeverAuthoriseWrites() {
        listOf(report.copy(metadata = report.metadata + ("pre-model" to "XT2607-2")),
            report.copy(metadata = report.metadata + ("motorola-model" to "XT2607-2")),
            report.copy(metadata = report.metadata + ("motorola-channel" to "retus")),
            report.copy(metadata = report.metadata + ("pre-device" to "other"))).forEach { assertTrue(all(it).isEmpty()) }
    }
    @Test fun signedSourceNamespaceBridgeNeedsExactIndependentVendorObservation() {
        val observed = current.copy(fingerprint = sourceFp.replace("merc_g_sys", "merc_g_syseq"))
        assertEquals(2, all(d = observed).count { it.kind == StockEvidenceKind.SOURCE })
        assertEquals(0, all(d = observed.copy(vendorFingerprint = "")).count { it.kind == StockEvidenceKind.SOURCE })
        assertEquals(0, all(d = observed.copy(vendorFingerprint = sourceFp.replace("current123", "other"))).count { it.kind == StockEvidenceKind.SOURCE })
        // Missing SOURCE_FP may not promote bare properties through a signed vendor namespace.
        val noPayload = report.copy(payloadProperties = emptyMap())
        assertEquals(0, all(noPayload, observed).count { it.kind == StockEvidenceKind.SOURCE })
    }
    @Test fun downloadedTargetProductNamespaceIsNeverGuessed() {
        val otherNamespace = target.copy(fingerprint = targetFp.replace("merc_g_sys", "merc_g_syseq"), vendorFingerprint = "")
        assertNull(StockEvidencePolicy.bind(evidence(), otherNamespace, now))
        val bound = requireNotNull(StockEvidencePolicy.bind(evidence(), otherNamespace.copy(vendorFingerprint = targetFp), now))
        assertNotNull(reference(bound, otherNamespace.copy(vendorFingerprint = targetFp)))
        assertNull(reference(bound, target))
    }
    @Test fun sourceWrongBuildIncrementalTimestampPatchAndProfileNeverReceiveReference() {
        val e = all().first { it.kind == StockEvidenceKind.SOURCE }
        listOf(current.copy(build = "OTHER"), current.copy(incrementalBuild = "other"), current.copy(androidVersion = "16"),
            current.copy(model = "XT2607-2"), current.copy(channel = "retus"), current.copy(buildTimestamp = 999),
            current.copy(securityPatch = "2026-09-01")).forEach { assertNull(reference(e, it)) }
    }
    @Test fun antiRollbackUnknownPatchTimestampDowngradeOrWipeBlocksAllReferences() {
        listOf(report.copy(metadata = report.metadata - "post-timestamp"), report.copy(metadata = report.metadata - "post-security-patch-level"),
            report.copy(metadata = report.metadata + ("post-timestamp" to "1000")),
            report.copy(metadata = report.metadata + ("post-security-patch-level" to "2026-09-01")),
            report.copy(metadata = report.metadata + ("ota-wipe" to "yes")), report.copy(metadata = report.metadata + ("ota-downgrade" to "unknown")))
            .forEach { assertTrue(all(it).isEmpty()) }
        assertTrue(all(d = current.copy(buildTimestamp = 0)).isEmpty())
    }
    @Test fun unverifiedHashesOrAnyTrustStoreChangeInvalidateProof() {
        listOf(report.copy(signatureVerified = false), report.copy(payloadHashValid = false), report.copy(structureValid = false),
            report.copy(sha256 = "bad")).forEach { assertTrue(all(it).isEmpty()) }
        val e = all().first { it.kind == StockEvidenceKind.SOURCE }
        assertNull(reference(e, current, emptySet())); assertNull(reference(e, current, anchors + "0".repeat(64)))
    }
    @Test fun fullPayloadWithoutOldPartitionDigestCannotProveCurrentStock() {
        val full = manifest.copy(initBootSource = null, bootSource = null)
        val proofs = StockEvidencePolicy.createAll(report, full, anchors, current, now)
        assertEquals(2, proofs.size)
        assertTrue(proofs.all { it.kind == StockEvidenceKind.TARGET })
    }
    @Test fun alreadyInstalledSignedTargetCanProveCurrentPartitionWithoutPretendingAnUpgrade() {
        val currentReport = report.copy(regionVerified = false, metadata = report.metadata - "pre-model" - "pre-channel")
        val proofs = all(currentReport, target)
        assertEquals(2, proofs.size)
        proofs.forEach {
            assertEquals(StockEvidenceKind.TARGET, it.kind)
            assertNotNull(reference(it, target))
        }
        assertFalse(currentReport.readyForOfficialReview)
        val olderObservedBuild = target.copy(build = "CURRENT.17", incrementalBuild = "current123", fingerprint = sourceFp,
            vendorFingerprint = sourceFp, buildTimestamp = 3000)
        assertTrue(all(currentReport, olderObservedBuild).isEmpty())
    }
    @Test fun currentTargetStillNeedsSuccessfulActualBootAndExactIncremental() {
        assertTrue(all(d = target.copy(bootSuccessful = false)).isEmpty())
        assertTrue(all(d = target.copy(incrementalBuild = "another", buildTimestamp = 2000)).isEmpty())
    }
}
