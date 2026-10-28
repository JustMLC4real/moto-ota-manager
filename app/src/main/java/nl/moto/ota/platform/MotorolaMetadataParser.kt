package nl.moto.ota.platform

import nl.moto.ota.core.LocalContentKind
import java.net.URI

enum class MetadataDiagnostic {
    INVALID_PREFERENCES, INVALID_JSON, UNSUPPORTED_SCHEMA, METADATA_RESOURCE,
    UNKNOWN_RESOURCE_KIND, INVALID_DOWNLOAD_REFERENCE, INVALID_RESOURCE_RANGE,
    RESOURCE_NOT_APPROVED, TARGET_BUILD_MISSING, INVALID_TARGET_FINGERPRINT
}

/** Only safe enums belong in diagnostics. The private URL is retained in memory only. */
data class MotorolaMetadataObservation(
    val contentKind: LocalContentKind = LocalContentKind.UNKNOWN,
    val targetBuild: String? = null,
    val downloadUrl: String? = null,
    val diagnostics: List<MetadataDiagnostic> = emptyList(),
    val observed: Boolean = false
) {
    override fun toString(): String = "MotorolaMetadataObservation(contentKind=$contentKind, observed=$observed, diagnostics=$diagnostics)"
}

/**
 * Recognizes the locally observed Motorola SharedPreferences schema. Cached metadata
 * and download jobs are historical leads; neither is a current server update offer.
 * Unknown schema versions, duplicate keys and bounded-reader failures fail closed.
 */
object MotorolaMetadataParser {
    private const val METADATA_KEY = "com.motorola.ccc.otafota.metadata_file"
    private val requestKeys = setOf("contentResource", "wifiOnly", "time", "size", "fileName", "hostName", "port",
        "disallowedNetworks", "backOffValues", "maxRetryCount", "upgradeSourceType", "allowOnRoaming", "maxDLSpeed", "startingOffset")
    private val resourceKeys = setOf("proceed", "wifiUrl", "cellUrl", "adminApnUrl", "trackingId")
    private val fingerprint = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+/([A-Za-z0-9_.-]+)/[A-Za-z0-9_.-]+:[A-Za-z0-9_,.-]+/[A-Za-z0-9_,.-]+$")

    fun parse(downloadPreferencesXml: String?, otaPreferencesXml: String?): MotorolaMetadataObservation {
        if (downloadPreferencesXml == null && otaPreferencesXml == null) return MotorolaMetadataObservation()
        return try {
            fromPreferences(downloadPreferencesXml?.let(ObservationParsers::preferences).orEmpty(),
                otaPreferencesXml?.let(ObservationParsers::preferences).orEmpty())
        } catch (_: Exception) {
            MotorolaMetadataObservation(diagnostics = listOf(MetadataDiagnostic.INVALID_PREFERENCES), observed = true)
        }
    }

    fun fromPreferences(download: Map<String, String>, ota: Map<String, String>): MotorolaMetadataObservation {
        val requestText = download["downloadRequest"]
        val metadataText = ota[METADATA_KEY]
        if (requestText == null && metadataText == null) return MotorolaMetadataObservation()
        val diagnostics = mutableListOf<MetadataDiagnostic>()
        // Build identity comes only from the explicit target fingerprint. A filename,
        // display label or the source fingerprint cannot establish a target build.
        var targetBuild: String? = null
        if (metadataText != null) {
            val metadata = readObject(metadataText)
            if (metadata == null) diagnostics += MetadataDiagnostic.INVALID_JSON else {
                val target = metadata["targetFingerPrint"] as? String
                targetBuild = target?.let { fingerprint.matchEntire(it)?.groupValues?.get(1) }
                diagnostics += when {
                    target == null -> MetadataDiagnostic.TARGET_BUILD_MISSING
                    targetBuild == null -> MetadataDiagnostic.INVALID_TARGET_FINGERPRINT
                    else -> null
                }.let { listOfNotNull(it) }
            }
        }
        if (requestText == null) return MotorolaMetadataObservation(targetBuild = targetBuild,
            diagnostics = (diagnostics + MetadataDiagnostic.UNKNOWN_RESOURCE_KIND).distinct(), observed = true)
        val root = readObject(requestText) ?: return unknown(targetBuild, diagnostics + MetadataDiagnostic.INVALID_JSON)
        // The real updater serializes {"downloadRequest":{...}}. Older flat jobs are
        // recognized only when they use the same explicit field schema.
        val request = if (root.keys == setOf("downloadRequest")) root["downloadRequest"] as? Map<*, *> else root
        if (request == null || request.keys.any { it !is String || it !in requestKeys } ||
            !request.keys.containsAll(setOf("contentResource", "size", "fileName", "startingOffset"))) {
            return unknown(targetBuild, diagnostics + MetadataDiagnostic.UNSUPPORTED_SCHEMA)
        }
        val resource = when (val value = request["contentResource"]) {
            is String -> readObject(value)
            is Map<*, *> -> value
            else -> null
        }
        if (resource == null || resource.keys.any { it !is String || it !in resourceKeys } || resource["wifiUrl"] !is String ||
            (resource.containsKey("proceed") && resource["proceed"] !is Boolean)) {
            return unknown(targetBuild, diagnostics + MetadataDiagnostic.UNSUPPORTED_SCHEMA)
        }
        if (resource["proceed"] == false) return unknown(targetBuild, diagnostics + MetadataDiagnostic.RESOURCE_NOT_APPROVED)
        val size = (request["size"] as? JsonNumber)?.text?.toLongOrNull()
        val offset = (request["startingOffset"] as? JsonNumber)?.text?.toLongOrNull()
        val fileName = request["fileName"] as? String
        if (size == null || size !in 1..(32L * 1_073_741_824) || offset == null || offset !in 0..size || fileName == null) {
            return unknown(targetBuild, diagnostics + MetadataDiagnostic.INVALID_RESOURCE_RANGE)
        }
        val basename = fileName.substringAfterLast('/').substringAfterLast('\\')
        val kind = when {
            offset > 0L && (basename.equals("payload_metadata.bin", ignoreCase = true) ||
                (basename.startsWith("metadata", ignoreCase = true) && basename.endsWith(".bin", ignoreCase = true))) -> LocalContentKind.METADATA_ONLY
            // A ZIP-shaped job is still only a candidate for independent package validation.
            basename.matches(Regex("[A-Za-z0-9_.-]+[.]zip", RegexOption.IGNORE_CASE)) && offset == 0L -> LocalContentKind.OTA_ZIP
            else -> LocalContentKind.UNKNOWN
        }
        val url = (resource["wifiUrl"] as String).takeIf { value ->
            value.length in 1..16_384 && value.none(Char::isISOControl) && runCatching {
                val uri = URI(value)
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null
            }.getOrDefault(false)
        }
        if (url == null) diagnostics += MetadataDiagnostic.INVALID_DOWNLOAD_REFERENCE
        if (kind == LocalContentKind.METADATA_ONLY) diagnostics += MetadataDiagnostic.METADATA_RESOURCE
        if (kind == LocalContentKind.UNKNOWN) diagnostics += MetadataDiagnostic.UNKNOWN_RESOURCE_KIND
        return MotorolaMetadataObservation(kind, targetBuild, url, diagnostics.distinct(), observed = true)
    }

    private fun unknown(targetBuild: String?, diagnostics: List<MetadataDiagnostic>) =
        MotorolaMetadataObservation(targetBuild = targetBuild, diagnostics = diagnostics.distinct(), observed = true)

    private fun readObject(text: String): Map<String, Any?>? = runCatching { BoundedJsonReader(text).readObject() }.getOrNull()

    private data class JsonNumber(val text: String)

    /** JVM/Android-independent strict reader: bounded input, depth, values and strings. */
    private class BoundedJsonReader(private val input: String) {
        private var position = 0
        private var values = 0

        fun readObject(): Map<String, Any?> {
            require(input.length <= 131_072)
            val value = readValue(0)
            whitespace()
            require(position == input.length && value is Map<*, *>)
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }

        private fun readValue(depth: Int): Any? {
            require(depth <= 12 && ++values <= 4096)
            whitespace()
            require(position < input.length)
            return when (input[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            position++
            whitespace()
            val map = linkedMapOf<String, Any?>()
            if (consume('}')) return map
            while (true) {
                whitespace()
                require(position < input.length && input[position] == '"')
                val key = stringValue()
                require(!map.containsKey(key))
                whitespace()
                require(consume(':'))
                map[key] = readValue(depth + 1)
                whitespace()
                if (consume('}')) return map
                require(consume(','))
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            position++
            whitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                result += readValue(depth + 1)
                whitespace()
                if (consume(']')) return result
                require(consume(','))
            }
        }

        private fun stringValue(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (position < input.length) {
                val c = input[position++]
                if (c == '"') return result.toString()
                require(c >= ' ')
                if (c != '\\') result.append(c) else {
                    require(position < input.length)
                    when (val escaped = input[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= input.length)
                            result.append(input.substring(position, position + 4).toInt(16).toChar())
                            position += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                require(result.length <= 16_384)
            }
            error("Unterminated JSON string")
        }

        private fun numberValue(): JsonNumber {
            val start = position
            if (position < input.length && input[position] == '-') position++
            require(position < input.length)
            if (input[position] == '0') position++ else {
                require(input[position] in '1'..'9')
                while (position < input.length && input[position] in '0'..'9') position++
            }
            if (consume('.')) {
                val digits = position
                while (position < input.length && input[position] in '0'..'9') position++
                require(position > digits)
            }
            if (position < input.length && input[position] in "eE") {
                position++
                if (position < input.length && input[position] in "+-") position++
                val digits = position
                while (position < input.length && input[position] in '0'..'9') position++
                require(position > digits)
            }
            require(position - start <= 64)
            return JsonNumber(input.substring(start, position))
        }

        private fun literal(text: String, value: Any?): Any? {
            require(input.startsWith(text, position))
            position += text.length
            return value
        }

        private fun consume(c: Char): Boolean = if (position < input.length && input[position] == c) { position++; true } else false
        private fun whitespace() { while (position < input.length && input[position] in " \t\r\n") position++ }
    }
}
