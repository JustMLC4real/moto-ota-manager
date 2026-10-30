package nl.moto.ota.core.discovery

import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.LocalContentKind
import nl.moto.ota.core.OfficialDownloadPolicy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class CdsStatus { CONSENT_REQUIRED, INPUT_MISSING, UNSUPPORTED_REGION, CHECKING, NO_OFFER, OFFER, METADATA_ONLY, UNKNOWN_RESPONSE, HTTP_ERROR, NETWORK_ERROR, CANCELLED }
enum class CdsInputField { ROOT, SERIAL_NUMBER, CONTEXT_KEY, MANUFACTURER, MODEL, PRODUCT, DEVICE, CARRIER, BUILD_ID, BUILD_FINGERPRINT, IDENTITY, FRESH_OBSERVATION }

/** Transient network identity. Never log, persist, export or include it in Inspection. */
class CdsIdentity(
    val device: DeviceState, val serialNumber: String, val contextKey: String,
    val model: String, val product: String, val buildId: String,
    val brand: String = "", val hardware: String = "", val country: String = "",
    val locale: String = "", val language: String = "en", val operatingSystem: String = "",
    val isPrc: Boolean = false, val buildDisplayId: String = ""
) {
    override fun toString(): String = "CdsIdentity(redacted)"
}

/** A server reference is not a verified package or a signed channel assertion. */
class CdsPackageReference(
    val url: String, val targetBuild: String, val contentKind: LocalContentKind,
    val expectedBytes: Long? = null, val sha256: String? = null
) {
    override fun toString(): String = "CdsPackageReference(contentKind=$contentKind, expectedBytes=$expectedBytes, url=redacted)"
}

data class CdsCheckResult(
    val status: CdsStatus, val targetBuild: String = "", val targetVersion: Long? = null,
    val packageReference: CdsPackageReference? = null, val httpStatus: Int? = null,
    val missingFields: List<CdsInputField> = emptyList(), val targetFingerprint: String = ""
) {
    override fun toString(): String = "CdsCheckResult(status=$status, reference=${packageReference != null}, httpStatus=$httpStatus, missingFields=$missingFields)"
}

/** Only in-memory protocol state; none of these fields belongs in WorkManager input or a journal. */
class CdsOffer internal constructor(
    val result: CdsCheckResult, internal val contextKey: String, internal val timestamp: Long,
    internal val trackingId: String, internal val reportingTags: String, internal val expectedBytes: Long
) {
    override fun toString(): String = "CdsOffer(status=${result.status}, tracking=redacted)"
}

/** Independent wire implementation based on documented source observations, not an OEM SDK. */
object CdsProtocol {
    const val HOST = "moto-cds.appspot.com"
    const val MAX_RESPONSE_BYTES = 262_144
    const val MAX_REQUEST_BYTES = 16_384
    const val MAX_PACKAGE_BYTES = 12L * 1024 * 1024 * 1024
    private val token = Regex("[A-Za-z0-9_. -]{1,160}")
    private val build = Regex("[A-Za-z0-9_.-]{1,120}")
    // Motorola's measured ro.mot.build.guid is commonly 15 hex characters, not
    // necessarily a full SHA-1. Preserve the observed value; never pad or hash it.
    // Primary device report: MotorolaMobilityLLC/kernel-msm issue #752.
    private val contextKey = Regex("(?:[A-Fa-f0-9]{15}|[A-Fa-f0-9]{40})")
    private val fingerprint = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+/([A-Za-z0-9_.-]+)/[A-Za-z0-9_.-]+:[A-Za-z0-9_,.-]+/[A-Za-z0-9_,.-]+")

    fun missing(identity: CdsIdentity, nowMillis: Long): List<CdsInputField> = buildList {
        val d = identity.device
        if (!d.manufacturer.equals("motorola", true)) add(CdsInputField.MANUFACTURER)
        if (!identity.serialNumber.matches(Regex("[A-Za-z0-9_-]{1,64}")) || identity.serialNumber.lowercase() in setOf("unknown", "null", "serial_number_not_available")) add(CdsInputField.SERIAL_NUMBER)
        if (!identity.contextKey.matches(contextKey)) add(CdsInputField.CONTEXT_KEY)
        if (!identity.model.matches(token)) add(CdsInputField.MODEL)
        if (!identity.product.matches(token)) add(CdsInputField.PRODUCT)
        if (!d.device.matches(build)) add(CdsInputField.DEVICE)
        if (!d.channel.matches(build)) add(CdsInputField.CARRIER)
        if (!identity.buildId.matches(build) || identity.buildId != d.build) add(CdsInputField.BUILD_ID)
        val sourceFingerprint = fingerprint.matchEntire(d.fingerprint)
        if (sourceFingerprint == null) add(CdsInputField.BUILD_FINGERPRINT)
        val sourceParts = d.fingerprint.substringBefore(':').split('/')
        val fingerprintConflict = sourceFingerprint != null &&
            (!sourceParts[0].equals(d.manufacturer, true) || sourceParts[2] != d.device || sourceFingerprint.groupValues[1] != d.build)
        if (d.simulated || d.identityConflicts.isNotEmpty() || fingerprintConflict) add(CdsInputField.IDENTITY)
        if (d.capturedAtMillis <= 0 || nowMillis - d.capturedAtMillis !in 0..120_000) add(CdsInputField.FRESH_OBSERVATION)
    }

    fun checkUrl(identity: CdsIdentity): String = "https://$HOST/cds/upgrade/1/check/ctx/ota/key/${segment(identity.contextKey)}"
    fun resourcesUrl(offer: CdsOffer): String = "https://$HOST/cds/upgrade/1/resources/t/${segment(offer.trackingId)}/ctx/ota/key/${segment(offer.contextKey)}"

    /** Minimized app request shape. No IMEI, app secret, policy claims or guessed numeric version. */
    fun checkBody(identity: CdsIdentity): ByteArray = encode(common(identity) + mapOf("triggeredBy" to "user", "idType" to "serialNumber"))
    fun resourcesBody(identity: CdsIdentity, offer: CdsOffer): ByteArray = encode(common(identity) + mapOf(
        "contentTimestamp" to offer.timestamp, "idType" to "serialNumber", "reportingTags" to offer.reportingTags, "reason" to ""))

    private fun common(identity: CdsIdentity): Map<String, Any?> {
        val d = identity.device
        val deviceInfo = linkedMapOf<String, Any?>("manufacturer" to d.manufacturer, "model" to identity.model,
            "product" to identity.product, "osVersion" to d.androidVersion, "userLanguage" to identity.language)
        listOf("brand" to identity.brand, "hardware" to identity.hardware, "os" to identity.operatingSystem,
            "country" to identity.country, "region" to identity.locale.substringAfter('_', ""), "language" to identity.locale.substringBefore('_'))
            .filter { it.second.isNotBlank() }.forEach { deviceInfo[it.first] = it.second.take(160) }
        val extra = linkedMapOf<String, Any?>("clientIdentity" to "motorola-ota-client-app", "carrier" to d.channel,
            "brand" to identity.brand, "model" to identity.model, "fingerprint" to d.fingerprint, "buildDevice" to d.device,
            "buildId" to identity.buildId, "buildIncrementalVersion" to d.incrementalBuild,
            "releaseVersion" to d.androidVersion, "otaSourceSha1" to identity.contextKey)
        if (identity.buildDisplayId.isNotBlank()) extra["buildDisplayId"] = identity.buildDisplayId.take(160)
        return mapOf("id" to identity.serialNumber, "contentTimestamp" to 0L, "deviceInfo" to deviceInfo,
            "extraInfo" to extra, "identityInfo" to mapOf("serialNumber" to identity.serialNumber))
    }

    fun parseCheck(body: String, identity: CdsIdentity): CdsOffer? = runCatching {
        val envelope = BoundedCdsJsonReader(body).readObject()
        val response = if (envelope.containsKey("contentResponse")) when (val nested = envelope["contentResponse"]) {
            is String -> BoundedCdsJsonReader(nested).readObject()
            is Map<*, *> -> nested.stringMap()
            else -> error("INVALID_ENVELOPE")
        } else envelope
        require(response["context"] == "ota" && response["contextKey"] == identity.contextKey)
        val proceed = response["proceed"] as? Boolean ?: error("MISSING_PROCEED")
        val timestamp = response.long("contentTimestamp") ?: error("MISSING_TIMESTAMP")
        require(timestamp >= 0)
        if (!proceed) return@runCatching CdsOffer(CdsCheckResult(CdsStatus.NO_OFFER), identity.contextKey, timestamp, "", "", 0)
        val content = (response["content"] as? Map<*, *>)?.stringMap() ?: error("MISSING_CONTENT")
        val size = content.long("size")?.takeIf { it in 1..MAX_PACKAGE_BYTES } ?: error("INVALID_SIZE")
        val targetFingerprint = (content["fingerprint"] as? String).orEmpty()
        val target = fingerprint.matchEntire(targetFingerprint)?.groupValues?.get(1)
            ?.takeIf { build.matches(it) } ?: error("UNKNOWN_TARGET")
        val source = (content["otaSourceSha1"] as? String).orEmpty()
        require(source.isEmpty() || source.equals(identity.contextKey, true))
        if (target == identity.device.build) return@runCatching CdsOffer(CdsCheckResult(CdsStatus.NO_OFFER, target), identity.contextKey, timestamp, "", "", 0)
        val targetVersion = (content["displayVersion"] as? String)?.toLongOrNull() ?: content.long("displayVersion")
        val tracking = (response["trackingId"] as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,160}")) } ?: error("TRACKING_REQUIRED")
        val tags = (response["reportingTags"] as? String).orEmpty().also { require(it.length <= 2048 && it.none { c -> c < ' ' }) }
        // Size is metadata until a known complete ZIP resource is returned.
        CdsOffer(CdsCheckResult(CdsStatus.OFFER, target, targetVersion, targetFingerprint = targetFingerprint), identity.contextKey, timestamp, tracking, tags, size)
    }.getOrNull()

    fun parseResources(body: String, offer: CdsOffer): CdsCheckResult = runCatching {
        val response = BoundedCdsJsonReader(body).readObject()
        require(response["proceed"] is Boolean && response["trackingId"] == offer.trackingId)
        if (response["proceed"] != true) return@runCatching CdsCheckResult(CdsStatus.NO_OFFER)
        val resources = response["contentResources"] as? List<*> ?: return@runCatching offer.result
        require(resources.size in 1..16)
        val candidates = resources.mapNotNull { value ->
            val item = (value as? Map<*, *>)?.stringMap() ?: error("RESOURCE_SCHEMA")
            val tags = (item["tags"] as? List<*>)?.also { require(it.size in 1..8 && it.all { tag -> tag is String }) } ?: error("RESOURCE_TAGS")
            if ("WIFI" !in tags) return@mapNotNull null
            val url = (item["url"] as? String)?.takeIf { it.length <= 8192 && OfficialDownloadPolicy.accepts(it) } ?: return@mapNotNull null
            val headers = item["headers"]
            // Arbitrary server-supplied authorization/headers are never copied into a transfer.
            if (headers != null && headers != "" && headers != "{}" && headers != emptyMap<String, Any?>()) return@mapNotNull null
            val path = URI(url).path.lowercase()
            val kind = when { path.endsWith(".zip") -> LocalContentKind.OTA_ZIP; path.endsWith("payload_metadata.bin") -> LocalContentKind.METADATA_ONLY; else -> LocalContentKind.UNKNOWN }
            if (kind == LocalContentKind.UNKNOWN) return@mapNotNull null
            CdsPackageReference(url, offer.result.targetBuild, kind,
                if (kind == LocalContentKind.OTA_ZIP) offer.expectedBytes else null)
        }
        val unique = candidates.distinctBy { it.url }
        require(unique.size <= 1)
        val reference = unique.singleOrNull() ?: return@runCatching offer.result
        offer.result.copy(status = if (reference.contentKind == LocalContentKind.METADATA_ONLY) CdsStatus.METADATA_ONLY else CdsStatus.OFFER, packageReference = reference)
    }.getOrElse { CdsCheckResult(CdsStatus.UNKNOWN_RESPONSE) }

    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? CdsJsonNumber)?.text?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull()
    private fun Map<*, *>.stringMap(): Map<String, Any?> { require(keys.all { it is String }); @Suppress("UNCHECKED_CAST") return this as Map<String, Any?> }
    private fun encode(value: Map<String, Any?>): ByteArray = json(value).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_REQUEST_BYTES) }
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is String -> buildString { append('"'); value.forEach { c -> when(c) { '\\' -> append("\\\\"); '"' -> append("\\\""); else -> if(c < ' ') append("\\u%04x".format(c.code)) else append(c) } }; append('"') }
        else -> error("UNSUPPORTED_REQUEST_VALUE")
    }
}
