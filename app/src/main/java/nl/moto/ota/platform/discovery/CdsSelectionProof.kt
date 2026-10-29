package nl.moto.ota.platform.discovery

import java.security.MessageDigest
import java.util.UUID
import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.discovery.CdsIdentity
import nl.moto.ota.core.discovery.CdsPackageReference
import nl.moto.ota.core.discovery.CdsCheckResult

/** Opaque live TLS selection evidence. It is not a signed channel statement or install permit.
 * There is deliberately no decoder or public constructor. Raw identifiers and bodies are absent. */
class CdsSelectionProof private constructor(
    val id: String, val manufacturer: String, val model: String, val device: String, val channel: String,
    val sourceBuild: String, val sourceFingerprint: String, val targetBuild: String, val targetFingerprint: String,
    val resourceUrlSha256: String, val requestBindingSha256: String,
    val checkResponseSha256: String, val resourceResponseSha256: String,
    val capturedAtMillis: Long, val expectedBytes: Long?
) {
    fun matches(device: DeviceState, url: String, targetBuild: String, nowMillis: Long): Boolean =
        !device.simulated && device.identityConflicts.isEmpty() && manufacturer.equals(device.manufacturer, true) &&
            model == device.model && this.device == device.device && channel == device.channel &&
            sourceBuild == device.build && sourceFingerprint == device.fingerprint && this.targetBuild == targetBuild &&
            targetFingerprint.isNotBlank() && resourceUrlSha256 == sha256(url.toByteArray(Charsets.UTF_8)) &&
            nowMillis - capturedAtMillis in 0..MAX_ADMISSION_AGE_MILLIS

    override fun toString(): String = "CdsSelectionProof(kind=MOTOROLA_LIVE_SELECTION, identifiers=redacted)"

    internal companion object {
        const val MAX_ADMISSION_AGE_MILLIS = 15L * 60 * 1000
        fun fromVerifiedExchange(identity: CdsIdentity, result: CdsCheckResult, reference: CdsPackageReference,
                                 requestBinding: String, checkResponse: String, resourceResponse: String,
                                 nowMillis: Long): CdsSelectionProof {
            val d = identity.device
            return CdsSelectionProof(UUID.randomUUID().toString(), d.manufacturer, d.model, d.device, d.channel,
                d.build, d.fingerprint, result.targetBuild, result.targetFingerprint,
                sha256(reference.url.toByteArray(Charsets.UTF_8)), requestBinding, checkResponse, resourceResponse,
                nowMillis, reference.expectedBytes)
        }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
