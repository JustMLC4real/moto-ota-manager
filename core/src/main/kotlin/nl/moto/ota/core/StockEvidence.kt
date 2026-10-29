package nl.moto.ota.core

import java.time.LocalDate

enum class StockEvidenceKind { SOURCE, TARGET }

/** Facts authenticated by the whole-ZIP verifier, never by an image name or a property. */
data class SignedStockEvidence(
    val packageSha256: String, val targetBuild: String, val targetAndroid: String, val targetIncremental: String,
    val payloadTargetFingerprint: String, val imageSha256: String, val imageBytes: Long, val trustedSpki: Set<String>,
    val verifiedAtMillis: Long, val measuredSystemFingerprint: String = "", val partition: String = "init_boot",
    val kind: StockEvidenceKind = StockEvidenceKind.TARGET, val device: String = "", val model: String = "",
    val channel: String = "", val androidFingerprints: Set<String> = emptySet(), val identityTimestamp: Long = 0,
    val identitySecurityPatch: String = "", val measuredVendorFingerprint: String = "",
    /** Scope is original current partition bytes only, never OTA installation/model certification. */
    val proofScope: String = "CURRENT_PARTITION_BYTES"
)

/** Syntax alone does not establish authenticity or installation compatibility. */
data class MotorolaFingerprint(val value: String, val product: String, val device: String, val android: String,
                               val build: String, val incremental: String) {
    companion object {
        fun parse(value: String): MotorolaFingerprint? {
            if (value.length > 512 || !value.matches(Regex(
                    "motorola/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+:[1-9][0-9]?/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+:user/release-keys"))) return null
            val p = value.split('/')
            return MotorolaFingerprint(value, p[1], p[2].substringBefore(':'), p[2].substringAfter(':'), p[3], p[4].substringBefore(':'))
        }
    }
}

object StockEvidencePolicy {
    const val MAX_IMAGE_BYTES = 512L * 1024 * 1024
    private val HASH = Regex("[a-f0-9]{64}")
    private val TOKEN = Regex("[A-Za-z0-9_.+-]{1,128}")
    private val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9_.+ -]{0,79}")

    /** Exact signed source, or a full independently measured vendor namespace bridge.
     * This proves identity only; the online selection and package signature remain separate. */
    fun sourceIdentityProven(report:FirmwareReport,device:DeviceState):Boolean {
        val android=androidFingerprints(report.metadata["pre-build"])?:return false
        val source=MotorolaFingerprint.parse(report.payloadSourceFingerprint.ifBlank {
            android.firstOrNull {it.value==device.fingerprint}?.value.orEmpty()
        })?:return false
        val namespaces=android.map {it.value}.toSet()
        return source.device==device.device && android.all {it.device==device.device &&
            it.incremental==report.metadata["pre-build-incremental"]} &&
            (device.fingerprint in namespaces || (device.vendorFingerprint.isNotBlank() && device.vendorFingerprint in namespaces)) &&
            sourceIdentityMatches(source,namespaces,device)
    }

    /** The platform caller must reverify the immutable ZIP against the live OTA anchors first. */
    fun createAll(report: FirmwareReport, manifest: PayloadManifestInfo, anchors: Set<String>,
                  device: DeviceState, nowMillis: Long): List<SignedStockEvidence> {
        if (!authenticated(report, anchors, nowMillis) || !observationsSafe(device, nowMillis) ||
            !profileConsistent(report, device)) return emptyList()
        val target = MotorolaFingerprint.parse(report.targetFingerprint) ?: return emptyList()
        val targetAndroid = androidFingerprints(report.metadata["post-build"]) ?: return emptyList()
        if (target.device != device.device || target.build != report.targetBuild ||
            targetAndroid.any { it.device != device.device || it.incremental != report.metadata["post-build-incremental"] }) return emptyList()
        val timestamp = report.metadata["post-timestamp"]?.toLongOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val signedPatch = report.metadata["post-security-patch-level"].orEmpty()
        if (patch(signedPatch) == null) return emptyList()
        val forward = antiRollback(report, device)
        val currentTarget = device.buildTimestamp > 0 && patch(device.securityPatch) != null &&
            sourceIdentityMatches(target, targetAndroid.map { it.value }.toSet(), device)
        if (!forward && !currentTarget) return emptyList()
        val result = manifest.targetImages.mapNotNull { image ->
            evidence(report, image, target, targetAndroid, anchors, device, nowMillis,
                StockEvidenceKind.TARGET, if (currentTarget) device.buildTimestamp else timestamp,
                if (currentTarget) device.securityPatch else signedPatch)?.let {
                if (currentTarget) bind(it, device, nowMillis) else it
            }
        }.toMutableList()
        val sourceAndroid = androidFingerprints(report.metadata["pre-build"])
        val source = MotorolaFingerprint.parse(report.payloadSourceFingerprint.ifBlank {
            sourceAndroid?.firstOrNull { it.value == device.fingerprint }?.value.orEmpty()
        })
        if (forward && sourceAndroid != null && source != null && source.device == device.device &&
            sourceAndroid.all { it.device == device.device && it.incremental == report.metadata["pre-build-incremental"] } &&
            sourceIdentityMatches(source, sourceAndroid.map { it.value }.toSet(), device)) {
            manifest.sourceImages.forEach { image ->
                evidence(report, image, source, sourceAndroid, anchors, device, nowMillis,
                    StockEvidenceKind.SOURCE, device.buildTimestamp, device.securityPatch)?.let(result::add)
            }
        }
        return result
    }

    /** Target-only facts without a live device never immediately produce a usable reference. */
    fun create(report: FirmwareReport, manifest: PayloadManifestInfo, anchors: Set<String>, nowMillis: Long): SignedStockEvidence? {
        if (!authenticated(report, anchors, nowMillis)) return null
        val devices = values(report.metadata["pre-device"]) ?: return null
        val models = signedModels(report) ?: return null
        val channel = signedChannel(report) ?: return null
        if (devices.size != 1 || models.size != 1 || !report.regionVerified) return null
        val fp = MotorolaFingerprint.parse(report.targetFingerprint) ?: return null
        val standard = androidFingerprints(report.metadata["post-build"]) ?: return null
        if (fp.device != devices.single() || fp.build != report.targetBuild ||
            standard.any { it.device != fp.device || it.incremental != report.metadata["post-build-incremental"] }) return null
        return manifest.targetImages.firstOrNull()?.let { image ->
            evidence(report, image, fp, standard, anchors,
                DeviceState(device = devices.single(), model = models.single(), channel = channel), nowMillis,
                StockEvidenceKind.TARGET, report.metadata["post-timestamp"]?.toLongOrNull() ?: 0,
                report.metadata["post-security-patch-level"].orEmpty())
        }
    }

    /** An exact identity or a separately measured signed vendor namespace is required at actual boot. */
    fun bind(e: SignedStockEvidence, d: DeviceState, nowMillis: Long): SignedStockEvidence? {
        if (!acceptsStored(e) || !observationsSafe(d, nowMillis) || !identityMatches(e, d) ||
            (e.measuredSystemFingerprint.isNotEmpty() && e.measuredSystemFingerprint != d.fingerprint) ||
            (e.measuredVendorFingerprint.isNotEmpty() && e.measuredVendorFingerprint != d.vendorFingerprint)) return null
        return e.copy(measuredSystemFingerprint = d.fingerprint, measuredVendorFingerprint = d.vendorFingerprint)
    }

    fun reference(e: SignedStockEvidence, d: DeviceState, currentAnchors: Set<String>,
                  nowMillis: Long = System.currentTimeMillis()): StockReference? {
        if (!acceptsStored(e) || !observationsSafe(d, nowMillis) || !identityMatches(e, d) || e.trustedSpki != currentAnchors ||
            e.measuredSystemFingerprint.isBlank() || e.measuredSystemFingerprint != d.fingerprint ||
            e.measuredVendorFingerprint != d.vendorFingerprint) return null
        return StockReference(e.imageSha256, e.imageBytes, d.build, d.fingerprint, d.device, d.model, d.channel, e.partition, true,
            "CURRENT_PARTITION_BYTES: whole-ZIP OTA ${e.packageSha256}; signed ${e.kind.name.lowercase()} ${e.partition} SHA-256; " +
                "observed profile/namespace. Full candidate hash AND current boot-bound Magisk runtime SHA1 remain mandatory. No OTA installation approval.")
    }

    /** Structural validation cannot authenticate an imported manifest. Legacy schema1 is ineligible. */
    fun acceptsStored(e: SignedStockEvidence): Boolean {
        val fp = MotorolaFingerprint.parse(e.payloadTargetFingerprint) ?: return false
        val actual = e.measuredSystemFingerprint.takeIf { it.isNotEmpty() }?.let(MotorolaFingerprint::parse)
        return e.packageSha256.matches(HASH) && e.verifiedAtMillis > 0 && e.trustedSpki.size in 1..128 &&
            e.trustedSpki.all { it.matches(HASH) } && e.imageSha256.matches(HASH) && e.imageBytes in 4096..MAX_IMAGE_BYTES &&
            e.proofScope == "CURRENT_PARTITION_BYTES" && e.partition in setOf("boot", "init_boot") &&
            e.device.matches(TOKEN) && e.model.matches(MODEL) && e.channel.matches(TOKEN) &&
            fp.device == e.device && fp.android == e.targetAndroid && fp.build == e.targetBuild && fp.incremental == e.targetIncremental &&
            e.androidFingerprints.size in 1..32 && e.androidFingerprints.all { MotorolaFingerprint.parse(it)?.device == e.device } &&
            e.identityTimestamp > 0 && patch(e.identitySecurityPatch) != null &&
            (e.measuredSystemFingerprint.isEmpty() || (actual != null && scalarMatches(fp, actual))) &&
            (e.measuredVendorFingerprint.isEmpty() || MotorolaFingerprint.parse(e.measuredVendorFingerprint)?.device == e.device) &&
            (e.kind != StockEvidenceKind.SOURCE || e.measuredSystemFingerprint.isNotEmpty())
    }

    private fun evidence(r: FirmwareReport, image: PartitionDigest, fp: MotorolaFingerprint,
                         android: List<MotorolaFingerprint>, anchors: Set<String>, d: DeviceState, now: Long,
                         kind: StockEvidenceKind, timestamp: Long, patch: String): SignedStockEvidence? =
        SignedStockEvidence(r.sha256, fp.build, fp.android, fp.incremental, fp.value, image.sha256, image.bytes,
            anchors.toSet(), now, if (kind == StockEvidenceKind.SOURCE) d.fingerprint else "", image.name, kind, d.device, d.model,
            d.channel, android.map { it.value }.toSet(), timestamp, patch,
            if (kind == StockEvidenceKind.SOURCE) d.vendorFingerprint else "").takeIf(::acceptsStored)

    private fun authenticated(r: FirmwareReport, anchors: Set<String>, now: Long): Boolean =
        r.signatureVerified && r.structureValid && r.payloadHashValid && r.sha256.matches(HASH) &&
            anchors.size in 1..128 && anchors.all { it.matches(HASH) } && now > 0 && r.metadata["ota-type"] == "AB" &&
            r.metadata["ota-downgrade"] in setOf(null, "no") && r.metadata["ota-wipe"] in setOf(null, "no")

    private fun profileConsistent(r: FirmwareReport, d: DeviceState): Boolean {
        if (d.device !in (values(r.metadata["pre-device"]) ?: emptySet())) return false
        if ((r.metadata.containsKey("pre-model") || r.metadata.containsKey("motorola-model")) &&
            d.model !in (signedModels(r) ?: emptySet())) return false
        if ((r.metadata.containsKey("pre-channel") || r.metadata.containsKey("motorola-channel")) && signedChannel(r) != d.channel) return false
        // A missing model/channel is not silently treated as signed. Only candidate-byte provenance
        // is carried forward; the writer must independently associate those exact bytes with the
        // currently running Magisk installation before any partition can be written.
        return true
    }

    private fun signedModels(r: FirmwareReport): Set<String>? {
        val fields = listOfNotNull(r.metadata["pre-model"], r.metadata["motorola-model"])
        if (fields.isEmpty()) return null
        val parsed = fields.map { values(it, MODEL) ?: return null }
        return parsed.first().takeIf { parsed.distinct().size == 1 }
    }

    private fun signedChannel(r: FirmwareReport): String? =
        listOfNotNull(r.metadata["pre-channel"], r.metadata["motorola-channel"]).distinct().singleOrNull()?.takeIf { it.matches(TOKEN) }

    private fun values(value: String?, regex: Regex = TOKEN): Set<String>? {
        if (value == null || value.length !in 1..4096) return null
        val parts = value.split('|')
        return parts.toSet().takeIf { parts.size in 1..32 && it.size == parts.size && parts.all(regex::matches) }
    }

    private fun androidFingerprints(value: String?): List<MotorolaFingerprint>? {
        if (value == null || value.length !in 1..16384) return null
        val parts = value.split('|')
        if (parts.size !in 1..32 || parts.toSet().size != parts.size) return null
        return parts.map { MotorolaFingerprint.parse(it) ?: return null }
    }

    private fun observationsSafe(d: DeviceState, now: Long): Boolean = d.manufacturer.equals("motorola", true) &&
        !d.simulated && d.identityConflicts.isEmpty() && d.model.matches(MODEL) && d.device.matches(TOKEN) && d.channel.matches(TOKEN) &&
        d.rootChecked && d.rootGranted && d.bootSuccessful == true && DeviceCapabilities.snapshotSafe(d) && d.engine == EngineState.IDLE &&
        d.moduleInventoryKnown && d.bootloaderUnlocked == true && d.verifiedBootState == "orange" &&
        (!d.securityPropertyMismatch || d.securityEvidenceSource=="KERNEL_BOOTCONFIG") &&
        d.slot in setOf("_a", "_b") && d.capturedAtMillis > 0 && now - d.capturedAtMillis in 0..120_000

    private fun identityMatches(e: SignedStockEvidence, d: DeviceState): Boolean {
        if (!d.manufacturer.equals("motorola", true) || d.simulated || d.identityConflicts.isNotEmpty() ||
            d.device != e.device || d.model != e.model || d.channel != e.channel || d.slot !in setOf("_a", "_b") ||
            d.build != e.targetBuild || d.androidVersion != e.targetAndroid || d.incrementalBuild != e.targetIncremental ||
            d.buildTimestamp != e.identityTimestamp || d.securityPatch != e.identitySecurityPatch) return false
        return MotorolaFingerprint.parse(e.payloadTargetFingerprint)?.let { sourceIdentityMatches(it, e.androidFingerprints, d) } == true
    }

    /** No guessed product aliases: a bridge needs the full signed, independently observed vendor FP. */
    private fun sourceIdentityMatches(signed: MotorolaFingerprint, androidFps: Set<String>, d: DeviceState): Boolean {
        val actual = MotorolaFingerprint.parse(d.fingerprint) ?: return false
        if (!scalarMatches(signed, actual) || actual.device != d.device || actual.android != d.androidVersion ||
            actual.build != d.build || actual.incremental != d.incrementalBuild) return false
        return signed.value == d.fingerprint || (d.vendorFingerprint.isNotEmpty() && d.vendorFingerprint in androidFps &&
            MotorolaFingerprint.parse(d.vendorFingerprint)?.device == d.device)
    }

    private fun scalarMatches(a: MotorolaFingerprint, b: MotorolaFingerprint): Boolean = a.device == b.device &&
        a.android == b.android && a.build == b.build && a.incremental == b.incremental

    private fun antiRollback(r: FirmwareReport, d: DeviceState): Boolean {
        val target = MotorolaFingerprint.parse(r.targetFingerprint) ?: return false
        val android = d.androidVersion.toIntOrNull() ?: return false
        val timestamp = r.metadata["post-timestamp"]?.toLongOrNull() ?: return false
        val currentPatch = patch(d.securityPatch) ?: return false
        val targetPatch = patch(r.metadata["post-security-patch-level"].orEmpty()) ?: return false
        return target.android.toInt() >= android && d.buildTimestamp > 0 && timestamp > d.buildTimestamp && targetPatch >= currentPatch
    }

    private fun patch(value: String): LocalDate? = value.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
