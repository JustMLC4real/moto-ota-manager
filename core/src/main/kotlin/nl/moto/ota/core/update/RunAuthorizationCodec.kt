package nl.moto.ota.core.update

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Canonical bounded transport, not authentication. Always verify the decoded HMAC and clocks. */
object RunAuthorizationCodec {
    const val MAX_AUTHORIZATION_BYTES = 16_384
    const val MAX_OPERATION_BYTES = 4_096
    private val authKeys = setOf("schema", "runId", "model", "device", "channel", "sourceBuild", "sourceFingerprint", "sourceBootId",
        "sourceSlot", "targetBuild", "targetFingerprint", "packageSha256", "actions", "consentNonce", "issuedAtMillis", "expiresAtMillis",
        "issuedElapsedMillis", "expiresElapsedMillis", "macSha256")
    private val operationKeys = setOf("schema", "runId", "action", "scopeDigest", "sourceBootId", "nonce", "packageSha256",
        "issuedAtMillis", "expiresAtMillis", "issuedElapsedMillis", "expiresElapsedMillis", "macSha256")

    fun encode(auth: RunAuthorization): String {
        RunAuthorizationPolicy.validateShape(auth)
        return encodeFields(linkedMapOf("schema" to "1", "runId" to auth.runId, "model" to auth.model, "device" to auth.device,
            "channel" to auth.channel, "sourceBuild" to auth.sourceBuild, "sourceFingerprint" to auth.sourceFingerprint,
            "sourceBootId" to auth.sourceBootId, "sourceSlot" to auth.sourceSlot, "targetBuild" to auth.targetBuild,
            "targetFingerprint" to auth.targetFingerprint, "packageSha256" to auth.packageSha256,
            "actions" to auth.actions.map { it.name }.sorted().joinToString(","), "consentNonce" to auth.consentNonce,
            "issuedAtMillis" to auth.issuedAtMillis.toString(), "expiresAtMillis" to auth.expiresAtMillis.toString(),
            "issuedElapsedMillis" to auth.issuedElapsedMillis.toString(), "expiresElapsedMillis" to auth.expiresElapsedMillis.toString(),
            "macSha256" to auth.macSha256), MAX_AUTHORIZATION_BYTES)
    }

    fun decode(text: String): RunAuthorization {
        val f = decodeFields(text, authKeys, MAX_AUTHORIZATION_BYTES)
        val actionNames = f.getValue("actions").split(',')
        require(actionNames.isNotEmpty() && actionNames.size <= RunAction.entries.size && actionNames.distinct().size == actionNames.size &&
            actionNames == actionNames.sorted()) { "RUN_AUTHORIZATION_CODEC_INVALID" }
        val actions = actionNames.map { RunAction.valueOf(it) }.toSet()
        return RunAuthorization(f.getValue("runId"), f.getValue("model"), f.getValue("device"), f.getValue("channel"),
            f.getValue("sourceBuild"), f.getValue("sourceFingerprint"), f.getValue("sourceBootId"), f.getValue("sourceSlot"),
            f.getValue("targetBuild"), f.getValue("targetFingerprint"), f.getValue("packageSha256"), actions, f.getValue("consentNonce"),
            number(f, "issuedAtMillis"), number(f, "expiresAtMillis"), number(f, "issuedElapsedMillis"), number(f, "expiresElapsedMillis"),
            f.getValue("macSha256")).also(RunAuthorizationPolicy::validateShape)
    }

    fun encodeOperation(operation: AuthorizedRunOperation): String {
        RunAuthorizationPolicy.validateOperationShape(operation)
        return encodeFields(linkedMapOf("schema" to "1", "runId" to operation.runId, "action" to operation.action.name,
            "scopeDigest" to operation.scopeDigest, "sourceBootId" to operation.sourceBootId, "nonce" to operation.nonce,
            "packageSha256" to operation.packageSha256, "issuedAtMillis" to operation.issuedAtMillis.toString(),
            "expiresAtMillis" to operation.expiresAtMillis.toString(), "issuedElapsedMillis" to operation.issuedElapsedMillis.toString(),
            "expiresElapsedMillis" to operation.expiresElapsedMillis.toString(), "macSha256" to operation.macSha256), MAX_OPERATION_BYTES)
    }

    fun decodeOperation(text: String): AuthorizedRunOperation {
        val f = decodeFields(text, operationKeys, MAX_OPERATION_BYTES)
        return AuthorizedRunOperation(f.getValue("runId"), RunAction.valueOf(f.getValue("action")), f.getValue("scopeDigest"),
            f.getValue("sourceBootId"), f.getValue("nonce"), f.getValue("packageSha256"), number(f, "issuedAtMillis"),
            number(f, "expiresAtMillis"), number(f, "issuedElapsedMillis"), number(f, "expiresElapsedMillis"), f.getValue("macSha256"))
            .also(RunAuthorizationPolicy::validateOperationShape)
    }

    private fun encodeFields(fields: Map<String, String>, maximum: Int): String = fields.entries.joinToString("\n", postfix = "\n") {
        "${it.key}=${Base64.getUrlEncoder().withoutPadding().encodeToString(it.value.toByteArray(Charsets.UTF_8))}"
    }.also { require(it.toByteArray(Charsets.UTF_8).size <= maximum) { "RUN_AUTHORIZATION_CODEC_BOUNDS" } }

    private fun decodeFields(text: String, keys: Set<String>, maximum: Int): Map<String, String> {
        require(text.length in 1..maximum && text.toByteArray(Charsets.UTF_8).size <= maximum && text.endsWith('\n') &&
            '\r' !in text && '\u0000' !in text) { "RUN_AUTHORIZATION_CODEC_BOUNDS" }
        val fields = linkedMapOf<String, String>()
        val lines = text.dropLast(1).split('\n')
        require(lines.size == keys.size) { "RUN_AUTHORIZATION_CODEC_FIELDS" }
        lines.forEach { line ->
            val equal = line.indexOf('=')
            require(equal > 0) { "RUN_AUTHORIZATION_CODEC_FIELDS" }
            val key = line.substring(0, equal)
            val raw = line.substring(equal + 1)
            require(key in keys && !fields.containsKey(key) && raw.matches(Regex("[A-Za-z0-9_-]*"))) { "RUN_AUTHORIZATION_CODEC_FIELDS" }
            val bytes = Base64.getUrlDecoder().decode(raw)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == raw) { "RUN_AUTHORIZATION_CODEC_CANONICAL" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            fields[key] = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }
        require(fields.keys == keys && fields["schema"] == "1") { "RUN_AUTHORIZATION_CODEC_SCHEMA" }
        return fields
    }

    private fun number(fields: Map<String, String>, name: String): Long {
        val value = fields.getValue(name)
        require(value.matches(Regex("0|[1-9][0-9]{0,18}"))) { "RUN_AUTHORIZATION_CODEC_NUMBER" }
        return value.toLong().also { require(it >= 0) }
    }
}
