package nl.moto.ota.core

/** Authenticated manifest evidence is independent of a guessed product-name alias. */
data class SignedStockEvidence(
    val packageSha256: String,
    val targetBuild: String,
    val targetAndroid: String,
    val targetIncremental: String,
    val payloadTargetFingerprint: String,
    val imageSha256: String,
    val imageBytes: Long,
    val trustedSpki: Set<String>,
    val verifiedAtMillis: Long,
    val measuredSystemFingerprint: String = ""
)

object StockEvidencePolicy {
    fun create(report: FirmwareReport, manifest: PayloadManifestInfo, anchors: Set<String>, nowMillis: Long): SignedStockEvidence? {
        if (!report.signatureVerified || !report.structureValid || !report.payloadHashValid ||
            !report.sha256.matches(Regex("[a-f0-9]{64}")) || report.targetBuild.isBlank() || anchors.isEmpty() ||
            anchors.any { !it.matches(Regex("[a-f0-9]{64}")) } || nowMillis <= 0 ||
            anchors.size > 128 || report.metadata["pre-device"].orEmpty().split('|').none { it == "merc" } ||
            manifest.initBootTarget.name != "init_boot" || manifest.initBootTarget.bytes !in 4096..(128L * 1024 * 1024) ||
            !manifest.initBootTarget.sha256.matches(Regex("[a-f0-9]{64}"))) return null
        val fp = report.targetFingerprint
        if (!fp.matches(Regex("motorola/[A-Za-z0-9_.-]+/merc:[1-9][0-9]?/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+:user/release-keys"))) return null
        val pieces = fp.split('/')
        if (pieces[3] != report.targetBuild) return null
        return SignedStockEvidence(report.sha256, pieces[3], pieces[2].substringAfter(':'), pieces[4].substringBefore(':'), fp,
            manifest.initBootTarget.sha256, manifest.initBootTarget.bytes, anchors.toSet(), nowMillis).takeIf(::acceptsStored)
    }

    /** Bind only after actual target boot and all observable end conditions, not on download/consent. */
    fun bind(evidence: SignedStockEvidence, device: DeviceState, nowMillis: Long): SignedStockEvidence? {
        if (!valid(evidence) || !targetIdentityMatches(evidence, device) || !device.rootChecked || !device.rootGranted || device.bootSuccessful != true ||
            device.snapshot != SnapshotState.NONE || device.engine != EngineState.IDLE || !device.moduleInventoryKnown ||
            device.bootloaderUnlocked != true || device.verifiedBootState != "orange" ||
            device.capturedAtMillis <= 0 || nowMillis < device.capturedAtMillis || nowMillis - device.capturedAtMillis > 120_000) return null
        if (evidence.measuredSystemFingerprint.isNotBlank() && evidence.measuredSystemFingerprint != device.fingerprint) return null
        return evidence.copy(measuredSystemFingerprint = device.fingerprint)
    }

    fun reference(evidence: SignedStockEvidence, device: DeviceState, currentAnchors: Set<String>): StockReference? {
        if (!valid(evidence) || !targetIdentityMatches(evidence, device) || evidence.measuredSystemFingerprint.isBlank() ||
            evidence.measuredSystemFingerprint != device.fingerprint || evidence.trustedSpki != currentAnchors) return null
        return StockReference(evidence.imageSha256, evidence.imageBytes, device.build, device.fingerprint, device.device,
            device.model, device.channel, "init_boot", true,
            "Ondertekende OTA ${evidence.packageSha256}; target-init_boot-manifest; actuele doelboot ${device.build} onafhankelijk waargenomen. Volledige imagehash moet nog overeenkomen.")
    }

    private fun targetIdentityMatches(evidence: SignedStockEvidence, device: DeviceState): Boolean =
        device.exactProfileMatch && !device.simulated && device.build == evidence.targetBuild && device.androidVersion == evidence.targetAndroid &&
            device.incrementalBuild == evidence.targetIncremental && actualFingerprintMatches(evidence, device.fingerprint) &&
            device.slot in setOf("_a", "_b") && evidence.imageSha256.matches(Regex("[a-f0-9]{64}")) &&
            evidence.imageBytes in 4096..(128L * 1024 * 1024)

    /** Structural validation of app-private stored facts; it cannot authenticate an imported manifest. */
    fun acceptsStored(evidence: SignedStockEvidence): Boolean =
        evidence.packageSha256.matches(Regex("[a-f0-9]{64}")) && evidence.verifiedAtMillis > 0 &&
            evidence.trustedSpki.size in 1..128 && evidence.trustedSpki.all { it.matches(Regex("[a-f0-9]{64}")) } &&
            evidence.imageSha256.matches(Regex("[a-f0-9]{64}")) && evidence.imageBytes in 4096..(128L * 1024 * 1024) &&
            actualFingerprintMatches(evidence, evidence.payloadTargetFingerprint) &&
            (evidence.measuredSystemFingerprint.isEmpty() || actualFingerprintMatches(evidence, evidence.measuredSystemFingerprint))

    private fun valid(evidence: SignedStockEvidence): Boolean = acceptsStored(evidence)

    private fun actualFingerprintMatches(evidence: SignedStockEvidence, value: String): Boolean {
        if (value.length > 512 || !value.matches(Regex("motorola/[A-Za-z0-9_.-]+/merc:[1-9][0-9]?/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+:user/release-keys"))) return false
        val parts = value.split('/')
        return parts[2].substringAfter(':') == evidence.targetAndroid && parts[3] == evidence.targetBuild &&
            parts[4].substringBefore(':') == evidence.targetIncremental
    }
}
