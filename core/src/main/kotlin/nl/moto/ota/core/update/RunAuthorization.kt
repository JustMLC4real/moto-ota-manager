package nl.moto.ota.core.update

import nl.moto.ota.core.DeviceState
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class RunAction { RESTORE_STOCK, APPLY_OTA, PATCH_INACTIVE_MAGISK, REBOOT }

/** One user start authorizes this exact source boot and target, never arbitrary later updates. */
data class RunAuthorization(
    val runId: String,
    val model: String,
    val device: String,
    val channel: String,
    val sourceBuild: String,
    val sourceFingerprint: String,
    val sourceBootId: String,
    val sourceSlot: String,
    val targetBuild: String,
    val targetFingerprint: String,
    val packageSha256: String,
    val actions: Set<RunAction>,
    val consentNonce: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val issuedElapsedMillis: Long,
    val expiresElapsedMillis: Long,
    val macSha256: String
) {
    val targetSlot: String get() = if (sourceSlot == "_a") "_b" else "_a"
    fun canonical(): String = frame(listOf("moto-complete-update-authorization-v1", runId, model, device, channel,
        sourceBuild, sourceFingerprint, sourceBootId, sourceSlot, targetBuild, targetFingerprint, packageSha256,
        actions.map { it.name }.sorted().joinToString(","), consentNonce, issuedAtMillis.toString(),
        expiresAtMillis.toString(), issuedElapsedMillis.toString(), expiresElapsedMillis.toString()))
    fun scopeDigest(): String = digest(canonical())
}

/** A short action capability. The root adapter must durably claim its nonce before mutating. */
data class AuthorizedRunOperation(
    val runId: String,
    val action: RunAction,
    val scopeDigest: String,
    val sourceBootId: String,
    val nonce: String,
    val packageSha256: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val issuedElapsedMillis: Long,
    val expiresElapsedMillis: Long,
    val macSha256: String
) {
    fun canonical(): String = frame(listOf("moto-update-action-v1", runId, action.name, scopeDigest, sourceBootId,
        nonce, packageSha256, issuedAtMillis.toString(), expiresAtMillis.toString(), issuedElapsedMillis.toString(), expiresElapsedMillis.toString()))
}

/** This authority never substitutes for fresh image, engine, slot, snapshot or signature checks. */
object RunAuthorizationPolicy {
    const val MAX_RUN_LIFETIME_MILLIS = 6 * 60 * 60 * 1000L
    const val OPERATION_LIFETIME_MILLIS = 60_000L
    private val sha256 = Regex("[a-f0-9]{64}")

    fun authorize(runId: String, source: DeviceState, target: ObservedUpdateTarget, secret: ByteArray,
                  userStarted: Boolean, nowMillis: Long, elapsedMillis: Long,
                  actions: Set<RunAction> = RunAction.entries.toSet()): RunAuthorization {
        if (!userStarted) reject("RUN_START_REQUIRED")
        if (secret.size != 32 || !uuid(runId) || source.simulated || !source.manufacturer.equals("motorola", true) ||
            source.slot !in setOf("_a", "_b") || source.bootId.isBlank() || source.model.isBlank() || source.device.isBlank() ||
            source.channel.isBlank() || source.build.isBlank() || source.fingerprint.isBlank() || source.identityConflicts.isNotEmpty() ||
            target.build.isBlank() || target.build == source.build || target.build.length > 100 ||
            source.capturedAtMillis <= 0 || nowMillis - source.capturedAtMillis !in 0..120_000 || elapsedMillis < 0 ||
            nowMillis <= 0 || nowMillis > Long.MAX_VALUE - MAX_RUN_LIFETIME_MILLIS ||
            elapsedMillis > Long.MAX_VALUE - MAX_RUN_LIFETIME_MILLIS || actions.isEmpty() ||
            !bounded(source.model, 80) || !bounded(source.device, 128) || !bounded(source.channel, 128) ||
            !bounded(source.build, 100) || !bounded(source.fingerprint, 1024) || !bounded(source.bootId, 128) ||
            !bounded(target.build, 100) || !bounded(target.declaredFingerprint, 1024, true) ||
            (target.packageSha256.isNotEmpty() && !sha256.matches(target.packageSha256))) reject("RUN_SCOPE_INVALID")
        val unsigned = RunAuthorization(runId, source.model, source.device, source.channel, source.build, source.fingerprint,
            source.bootId, source.slot, target.build, target.declaredFingerprint, target.packageSha256, actions.toSet(),
            UUID.randomUUID().toString(), nowMillis, Math.addExact(nowMillis, MAX_RUN_LIFETIME_MILLIS),
            elapsedMillis, Math.addExact(elapsedMillis, MAX_RUN_LIFETIME_MILLIS), "")
        return unsigned.copy(macSha256 = sign(unsigned.canonical(), secret))
    }

    fun verify(authorization: RunAuthorization, secret: ByteArray, nowMillis: Long, elapsedMillis: Long) {
        val a = authorization
        validateShape(a)
        if (secret.size != 32 || !constantTime(sign(a.canonical(), secret), a.macSha256)) reject("RUN_AUTHORIZATION_INVALID")
        checkTimes(a.issuedAtMillis, a.expiresAtMillis, a.issuedElapsedMillis, a.expiresElapsedMillis,
            MAX_RUN_LIFETIME_MILLIS, nowMillis, elapsedMillis)
    }

    internal fun validateShape(a: RunAuthorization) {
        if (!uuid(a.runId) || !uuid(a.consentNonce) || a.actions.isEmpty() ||
            a.sourceSlot !in setOf("_a", "_b") || a.sourceBootId.isBlank() || a.model.isBlank() || a.device.isBlank() ||
            a.channel.isBlank() || a.sourceBuild.isBlank() || a.sourceFingerprint.isBlank() || a.targetBuild.isBlank() ||
            a.targetBuild == a.sourceBuild || !bounded(a.model, 80) || !bounded(a.device, 128) || !bounded(a.channel, 128) ||
            !bounded(a.sourceBuild, 100) || !bounded(a.sourceFingerprint, 1024) || !bounded(a.sourceBootId, 128) ||
            !bounded(a.targetBuild, 100) || !bounded(a.targetFingerprint, 1024, true) ||
            (a.packageSha256.isNotEmpty() && !sha256.matches(a.packageSha256)) || !sha256.matches(a.macSha256) ||
            !interval(a.issuedAtMillis, a.expiresAtMillis, a.issuedElapsedMillis, a.expiresElapsedMillis, MAX_RUN_LIFETIME_MILLIS)) reject("RUN_AUTHORIZATION_INVALID")
    }

    /** Narrow an initially unknown package to the verified bytes of the same offered target. */
    fun pinPackageHash(authorization: RunAuthorization, reportSha256: String, targetBuild: String,
                       declaredTargetFingerprint: String, signatureVerified: Boolean, secret: ByteArray,
                       nowMillis: Long, elapsedMillis: Long): RunAuthorization {
        verify(authorization, secret, nowMillis, elapsedMillis)
        if (!signatureVerified || !sha256.matches(reportSha256) || !bounded(declaredTargetFingerprint, 1024) || targetBuild != authorization.targetBuild ||
            (authorization.targetFingerprint.isNotBlank() && authorization.targetFingerprint != declaredTargetFingerprint) ||
            (authorization.packageSha256.isNotBlank() && authorization.packageSha256 != reportSha256)) reject("RUN_PACKAGE_CHANGED")
        val pinned = authorization.copy(packageSha256 = reportSha256,
            targetFingerprint = authorization.targetFingerprint.ifBlank { declaredTargetFingerprint }, macSha256 = "")
        return pinned.copy(macSha256 = sign(pinned.canonical(), secret))
    }

    fun verifySource(authorization: RunAuthorization, source: DeviceState, secret: ByteArray,
                     nowMillis: Long, elapsedMillis: Long) {
        verify(authorization, secret, nowMillis, elapsedMillis)
        if (source.simulated || !source.manufacturer.equals("motorola", true) || source.identityConflicts.isNotEmpty() ||
            source.model != authorization.model || source.device != authorization.device || source.channel != authorization.channel ||
            source.build != authorization.sourceBuild || source.fingerprint != authorization.sourceFingerprint ||
            source.bootId != authorization.sourceBootId || source.slot != authorization.sourceSlot ||
            source.capturedAtMillis <= 0 || nowMillis - source.capturedAtMillis !in 0..30_000) reject("RUN_SOURCE_CHANGED")
    }

    /** The durable store claims each action exactly once before this function may be dispatched. */
    fun issueOperation(authorization: RunAuthorization, action: RunAction, secret: ByteArray,
                       nowMillis: Long, elapsedMillis: Long, nonce: String = UUID.randomUUID().toString()): AuthorizedRunOperation {
        verify(authorization, secret, nowMillis, elapsedMillis)
        if (action !in authorization.actions || !sha256.matches(authorization.packageSha256) || !uuid(nonce)) reject("RUN_ACTION_NOT_ALLOWED")
        val unsigned = AuthorizedRunOperation(authorization.runId, action, authorization.scopeDigest(), authorization.sourceBootId,
            nonce, authorization.packageSha256, nowMillis,
            nowMillis + minOf(OPERATION_LIFETIME_MILLIS, authorization.expiresAtMillis - nowMillis), elapsedMillis,
            elapsedMillis + minOf(OPERATION_LIFETIME_MILLIS, authorization.expiresElapsedMillis - elapsedMillis), "")
        return unsigned.copy(macSha256 = sign(unsigned.canonical(), secret))
    }

    fun verifyOperation(authorization: RunAuthorization, operation: AuthorizedRunOperation, expectedAction: RunAction,
                        secret: ByteArray, nowMillis: Long, elapsedMillis: Long) {
        verify(authorization, secret, nowMillis, elapsedMillis)
        val p = operation
        validateOperationShape(p)
        if (p.action != expectedAction || p.action !in authorization.actions || p.runId != authorization.runId ||
            p.scopeDigest != authorization.scopeDigest() || p.sourceBootId != authorization.sourceBootId ||
            p.packageSha256 != authorization.packageSha256 || !sha256.matches(p.packageSha256) || !uuid(p.nonce) ||
            !sha256.matches(p.macSha256) || !constantTime(sign(p.canonical(), secret), p.macSha256) ||
            p.issuedAtMillis < authorization.issuedAtMillis || p.expiresAtMillis > authorization.expiresAtMillis ||
            p.issuedElapsedMillis < authorization.issuedElapsedMillis || p.expiresElapsedMillis > authorization.expiresElapsedMillis) reject("RUN_OPERATION_INVALID")
        checkTimes(p.issuedAtMillis, p.expiresAtMillis, p.issuedElapsedMillis, p.expiresElapsedMillis,
            OPERATION_LIFETIME_MILLIS, nowMillis, elapsedMillis)
    }

    internal fun validateOperationShape(p: AuthorizedRunOperation) {
        if (!uuid(p.runId) || !uuid(p.nonce) || !sha256.matches(p.scopeDigest) || !sha256.matches(p.packageSha256) ||
            !sha256.matches(p.macSha256) || !bounded(p.sourceBootId, 128) ||
            !interval(p.issuedAtMillis, p.expiresAtMillis, p.issuedElapsedMillis, p.expiresElapsedMillis, OPERATION_LIFETIME_MILLIS)) reject("RUN_OPERATION_INVALID")
    }

    private fun checkTimes(issued: Long, expires: Long, issuedElapsed: Long, expiresElapsed: Long, maximum: Long,
                           now: Long, elapsed: Long) {
        if (issued <= 0 || issuedElapsed < 0 || expires < issued || expiresElapsed < issuedElapsed ||
            expires - issued !in 0..maximum || expiresElapsed - issuedElapsed !in 0..maximum ||
            now < issued || now > expires || elapsed < issuedElapsed || elapsed > expiresElapsed) reject("RUN_AUTHORIZATION_EXPIRED")
    }

    private fun sign(value: String, secret: ByteArray): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal(value.toByteArray(UTF_8)).hex()
    }
    private fun constantTime(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(UTF_8), b.toByteArray(UTF_8))
    private fun uuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun bounded(value: String, maximum: Int, blankAllowed: Boolean = false): Boolean =
        value.length <= maximum && ((blankAllowed && value.isEmpty()) || value.isNotBlank()) && value.none { it.isISOControl() }
    private fun interval(issued: Long, expires: Long, issuedElapsed: Long, expiresElapsed: Long, maximum: Long): Boolean =
        issued > 0 && issuedElapsed >= 0 && expires >= issued && expiresElapsed >= issuedElapsed &&
            expires - issued <= maximum && expiresElapsed - issuedElapsed <= maximum
    private fun reject(code: String): Nothing = throw UpdateSafetyException(code)
}

private fun frame(values: List<String>): String = values.joinToString("") { "${it.toByteArray(UTF_8).size}:$it" }
private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
