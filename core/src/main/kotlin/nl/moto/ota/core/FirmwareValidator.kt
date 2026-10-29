package nl.moto.ota.core

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipFile

data class FirmwareReport(
    val structureValid: Boolean = false,
    val compatible: Boolean = false,
    val payloadHashValid: Boolean = false,
    val signatureVerified: Boolean = false,
    val regionVerified: Boolean = false,
    val sourceBuild: String = "",
    val targetBuild: String = "",
    val targetFingerprint: String = "",
    val packageBytes: Long = 0,
    val sha256: String = "",
    val issues: List<CheckResult> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val payloadProperties: Map<String, String> = emptyMap()
) {
    val readyForOfficialReview: Boolean get() = structureValid && compatible && payloadHashValid && signatureVerified && regionVerified
    val androidMetadataSourceFingerprint: String get() = metadata["pre-build"].orEmpty()
    val androidMetadataTargetFingerprint: String get() = metadata["post-build"].orEmpty()
    val payloadSourceFingerprint: String get() = payloadProperties["SOURCE_FP"].orEmpty()
    val payloadTargetFingerprint: String get() = payloadProperties["TARGET_FP"].orEmpty()
    /** Informational package target; it never relaxes the Android metadata preconditions. */
    val targetAndroidVersion: String get() = targetFingerprint.split('/').getOrNull(2)?.substringAfter(':', "").orEmpty()
}

/** Local checks are independent of the platform whole-file signature check. No package is applied here. */
object FirmwareValidator {
    const val MAX_PACKAGE_BYTES = 12L * 1024 * 1024 * 1024
    @Suppress("UNUSED_PARAMETER") // Other measured fingerprints remain diagnostic, never compatibility aliases.
    fun inspect(file: File, device: DeviceState, measuredFingerprints: Set<String> = emptySet()): FirmwareReport {
        var result = FirmwareReport(packageBytes = file.length())
        val issues = mutableListOf<CheckResult>()
        try {
            require(file.length() in 1..MAX_PACKAGE_BYTES) { "PACKAGE_SIZE" }
            val fullHash = file.inputStream().use { digest(it).joinToString("") { b -> "%02x".format(b) } }
            ZipFile(file).use { zip ->
                val names = HashSet<String>()
                val all = zip.entries()
                var count = 0
                while (all.hasMoreElements()) {
                    val entry = all.nextElement()
                    require(++count <= 2048 && names.add(entry.name)) { "DUPLICATE_OR_EXCESS_ENTRIES" }
                    require(!entry.name.startsWith("/") && entry.name.split('/').none { it == ".." }) { "ZIP_PATH" }
                }
                fun textEntry(name: String): String {
                    val entry = requireNotNull(zip.getEntry(name)) { "MISSING_ENTRY" }
                    require(entry.size in 1..(1024 * 1024)) { "METADATA_SIZE" }
                    return zip.getInputStream(entry).use { stream ->
                        val bytes = stream.readNBytes(1024 * 1024 + 1)
                        require(bytes.size <= 1024 * 1024) { "METADATA_TOO_LARGE" }
                        String(bytes, Charsets.UTF_8)
                    }
                }
                val metadata = keyValues(textEntry("META-INF/com/android/metadata"))
                val props = keyValues(textEntry("payload_properties.txt"))
                val payload = requireNotNull(zip.getEntry("payload.bin")) { "MISSING_PAYLOAD" }
                require(payload.size in 24..MAX_PACKAGE_BYTES) { "PAYLOAD_SIZE" }
                require(metadata["ota-type"] == "AB") { "NOT_AB" }
                require(!metadata["post-build"].isNullOrBlank() && !metadata["post-build-incremental"].isNullOrBlank()) { "TARGET_METADATA" }
                val header = zip.getInputStream(payload).use { it.readNBytes(24) }
                require(header.size == 24 && String(header, 0, 4, Charsets.US_ASCII) == "CrAU") { "PAYLOAD_MAGIC" }
                val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                require(bb.getLong(4) == 2L) { "UNSUPPORTED_PAYLOAD_VERSION" }
                val manifest = bb.getLong(12)
                val signatures = bb.getInt(20).toLong() and 0xffffffffL
                require(manifest > 0 && manifest <= payload.size - 24 && signatures <= payload.size - 24 - manifest) { "PAYLOAD_HEADER_BOUNDS" }
                val metadataSize = 24 + manifest
                require(props["METADATA_SIZE"]?.toLongOrNull() == metadataSize) { "PAYLOAD_METADATA_SIZE" }
                val expectedMetadataHash = Base64.getDecoder().decode(requireNotNull(props["METADATA_HASH"]))
                require(expectedMetadataHash.size == 32) { "METADATA_HASH_FORMAT" }
                val actualMetadataHash = zip.getInputStream(payload).use { input ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var remaining = metadataSize
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(n > 0) { "TRUNCATED_METADATA" }
                        md.update(buffer, 0, n)
                        remaining -= n
                    }
                    md.digest()
                }
                require(MessageDigest.isEqual(expectedMetadataHash, actualMetadataHash)) { "METADATA_HASH" }
                val expectedSize = props["FILE_SIZE"]?.toLongOrNull()
                require(expectedSize == payload.size) { "PAYLOAD_FILE_SIZE" }
                val expectedHash = Base64.getDecoder().decode(requireNotNull(props["FILE_HASH"]) { "PAYLOAD_HASH_MISSING" })
                require(expectedHash.size == 32) { "PAYLOAD_HASH_FORMAT" }
                val payloadHash = zip.getInputStream(payload).use { digest(it) }
                val hashValid = MessageDigest.isEqual(expectedHash, payloadHash)
                if (!hashValid) issues += CheckResult("PAYLOAD_HASH", "De payload komt niet overeen met FILE_HASH.")
                if (!device.manufacturer.equals("motorola", true) || device.model.isBlank() || device.device.isBlank() ||
                    device.channel.isBlank() || device.identityConflicts.isNotEmpty())
                    issues += CheckResult("DEVICE_PROFILE", "Actuele Motorola-identiteit is onvolledig of bevat tegenstrijdige waarnemingen.")
                val devices = metadata["pre-device"].orEmpty().split('|')
                if (device.device !in devices || device.device.isBlank()) issues += CheckResult("WRONG_DEVICE", "De ondertekende pre-device-lijst bevat de actuele codenaam niet.")
                val source = metadata["pre-build-incremental"].orEmpty()
                if (source.isNotBlank() && device.incrementalBuild.isBlank()) issues += CheckResult("SOURCE_INCREMENTAL_UNKNOWN", "Actuele ro.build.version.incremental ontbreekt; build-id is geen vervanging.")
                if (source.isNotBlank() && source != device.incrementalBuild) issues += CheckResult("SOURCE_BUILD", "Incrementele OTA vereist een andere bronbuild/incremental.")
                // Collected vendor/product fingerprints are diagnostic only. They cannot replace
                // Android's exact system pre-build precondition.
                val fps = setOf(device.fingerprint).filter { it.isNotBlank() }.toSet()
                val sourceFp = metadata["pre-build"].orEmpty()
                if (sourceFp.isNotBlank() && sourceFp.split('|').none { it in fps }) issues += CheckResult("SOURCE_FINGERPRINT", "Bronfingerprint komt niet exact overeen met de actuele systeemfingerprint; vendor-/productaliases worden niet gelijkgesteld.")
                if (source.isBlank() != sourceFp.isBlank()) issues += CheckResult("SOURCE_INCOMPLETE", "Incrementele metadata moet zowel pre-build als pre-build-incremental bevatten.")
                val androidTargetFp = metadata["post-build"].orEmpty()
                val androidTargetParts = androidTargetFp.split('/')
                if (androidTargetParts.size != 6 || androidTargetParts[0] != "motorola" ||
                    androidTargetParts[1].isBlank() || androidTargetFp.any { it.isWhitespace() } ||
                    androidTargetParts[2].substringBefore(':') != device.device ||
                    androidTargetParts[4].substringBefore(':') != metadata["post-build-incremental"] ||
                    androidTargetParts[4].substringAfter(':', "") != "user" || androidTargetParts[5] != "release-keys" || androidTargetParts[3].isBlank()) {
                    issues += CheckResult("TARGET_FINGERPRINT", "Doelfingerprint mist de actuele Motorola-codenaam, user/release-keys-indeling of consistente incremental.")
                }
                // Motorola can describe vendor Android 15 in standard metadata while the payload
                // properties describe system Android 16 -> 17. Keep both namespaces: the payload
                // pair improves target information only, and never becomes a compatibility alias.
                val payloadSource = payloadFingerprint(props["SOURCE_FP"], device.device)
                val payloadTarget = payloadFingerprint(props["TARGET_FP"], device.device)
                val payloadPairPresent = props.containsKey("SOURCE_FP") || props.containsKey("TARGET_FP")
                val payloadPairValid = payloadSource != null && payloadTarget != null
                if (payloadPairPresent && !payloadPairValid) {
                    issues += CheckResult("PAYLOAD_FINGERPRINTS_INVALID", "Payload SOURCE_FP en TARGET_FP moeten beide geldige Motorola-fingerprints met de actuele codenaam zijn.")
                }
                if (payloadPairValid && (payloadSource !in sourceFp.split('|') || payloadTarget != androidTargetFp)) {
                    issues += CheckResult("OEM_METADATA_REVIEW_REQUIRED", "Motorola-payloadproperties en standaard Android-metadata gebruiken verschillende fingerprints. Doelinformatie wordt apart getoond; compatibiliteit vereist OEM-beoordeling.")
                }
                val targetFp = if (payloadPairValid) payloadTarget!! else androidTargetFp
                val targetParts = targetFp.split('/')
                val target = targetParts.getOrNull(3).orEmpty()
                val targetAndroid = targetParts.getOrNull(2)?.substringAfter(':', "").orEmpty()
                val currentAndroidVersion = device.androidVersion.takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toIntOrNull()
                val targetAndroidVersion = targetAndroid.takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toIntOrNull()
                if (currentAndroidVersion == null || targetAndroidVersion == null) {
                    issues += CheckResult("ANDROID_VERSION_UNKNOWN", "Actuele of doel-Androidversie is onbekend of gebruikt een niet-ondersteunde indeling.")
                } else if (targetAndroidVersion < currentAndroidVersion) {
                    issues += CheckResult("ANDROID_ROLLBACK", "De doel-Androidversie is ouder dan de actuele versie.")
                }
                if (target == device.build) issues += CheckResult("ALREADY_INSTALLED", "Doelbuild is al geïnstalleerd.")
                val signedChannel = metadata["motorola-channel"] ?: metadata["pre-channel"]
                val channelConflict = metadata["motorola-channel"] != null && metadata["pre-channel"] != null && metadata["motorola-channel"] != metadata["pre-channel"]
                val regionOk = !channelConflict && signedChannel == device.channel && device.channel.matches(Regex("[A-Za-z0-9_.+-]{1,128}"))
                if (signedChannel != null && !regionOk) issues += CheckResult("WRONG_REGION", "Ondertekend pakketkanaal wijkt af van het gemeten toestelkanaal of is tegenstrijdig.")
                if (signedChannel == null) issues += CheckResult("REGION_UNPROVEN", "Standaard A/B-metadata bewijst het Motorola-kanaal niet; bestandsnaam of cid50 is onvoldoende bewijs.")
                val signedModels = listOfNotNull(metadata["pre-model"], metadata["motorola-model"]).map { it.split('|').toSet() }
                if (signedModels.isNotEmpty() && (signedModels.distinct().size != 1 || device.model !in signedModels.first()))
                    issues += CheckResult("WRONG_MODEL", "Expliciet ondertekend pakketmodel wijkt af van het actuele model of bevat tegenstrijdige velden.")
                if (metadata["ota-downgrade"] !in setOf(null, "no") || metadata["ota-wipe"] !in setOf(null, "no"))
                    issues += CheckResult("DOWNGRADE_OR_WIPE", "Downgrade, userdata-wipe of onbekende instructies daarvoor worden geblokkeerd.")
                val targetTimestamp = metadata["post-timestamp"]?.toLongOrNull()
                if (device.buildTimestamp <= 0 || targetTimestamp == null || targetTimestamp <= 0) {
                    issues += CheckResult("TIMESTAMP_UNKNOWN", "Actuele of doel-buildtijd ontbreekt of is ongeldig; anti-rollback niet aangetoond.")
                } else if (targetTimestamp <= device.buildTimestamp) {
                    issues += CheckResult("TIMESTAMP_ROLLBACK", "Pakketdatum is ouder dan of gelijk aan de actuele build.")
                }
                val currentPatch = patchDate(device.securityPatch)
                val targetPatch = patchDate(metadata["post-security-patch-level"].orEmpty())
                if (currentPatch == null || targetPatch == null) {
                    issues += CheckResult("PATCH_UNKNOWN", "Actueel of doel-beveiligingspatchniveau ontbreekt of is geen geldige ISO-datum.")
                } else if (targetPatch < currentPatch) {
                    issues += CheckResult("PATCH_ROLLBACK", "Het beveiligingspatchniveau zou teruggaan.")
                }
                val reportPayloadProperties = mutableMapOf<String, String>().apply {
                    payloadSource?.let { put("SOURCE_FP", it) }
                    payloadTarget?.let { put("TARGET_FP", it) }
                }
                result = FirmwareReport(true, issues.none { it.code !in setOf("REGION_UNPROVEN", "PAYLOAD_HASH") }, hashValid,
                    false, regionOk, source, target, targetFp, file.length(), fullHash, issues.toList(), metadata, reportPayloadProperties)
            }
        } catch (_: Exception) {
            result = result.copy(issues = issues + CheckResult("INVALID_PACKAGE", "Ongeldig of niet-ondersteund A/B-pakket: ZIP, metadata, header of payload-properties ontbreken of zijn inconsistent."))
        }
        return result
    }

    private fun keyValues(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach {
            val i = it.indexOf('=')
            require(i > 0) { "MALFORMED_METADATA" }
            val key = it.substring(0, i).trim()
            require(!map.containsKey(key)) { "DUPLICATE_METADATA" }
            map[key] = it.substring(i + 1).trim()
        }
        return map
    }

    private fun patchDate(text: String): LocalDate? = text.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Deliberately narrow and safe to include in diagnostics; URLs/tokens never become fingerprint fields. */
    private fun payloadFingerprint(value: String?, device: String): String? = value?.takeIf {
        MotorolaFingerprint.parse(it)?.device == device && device.isNotEmpty()
    }

    private fun digest(input: java.io.InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= MAX_PACKAGE_BYTES) { "EXPANSION_LIMIT" }
            md.update(buffer, 0, n)
        }
        return md.digest()
    }
}

/** Endpoint provenance is a lead, never a substitute for the device's OTA trust anchors. */
object OfficialDownloadPolicy {
    private val hosts = setOf("dlmgredg-vz.gtm.svcmot.com")
    fun accepts(value: String): Boolean = try {
        val u = URI(value)
        value.length <= 16384 && u.scheme == "https" && u.host?.lowercase() in hosts &&
            u.rawUserInfo == null && u.fragment == null && u.port in setOf(-1, 443) && u.rawPath.orEmpty().startsWith("/")
    } catch (_: Exception) { false }
}
