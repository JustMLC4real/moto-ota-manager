package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RunAuthorizationTest {
    private val now = 1_000_000L
    private val elapsed = 10_000L
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val secret = ByteArray(32) { it.toByte() }
    private val sha = "a".repeat(64)
    private val fp = "motorola/synthetic_g/synthetic:17/NEXT/next:user/release-keys"
    private fun source() = DeviceState(manufacturer = "motorola", model = "XT9999-1", device = "synthetic", channel = "reteu",
        build = "CURRENT", fingerprint = "motorola/synthetic_g/synthetic:17/CURRENT/current:user/release-keys",
        bootId = "synthetic-boot", slot = "_a", capturedAtMillis = now)
    private fun auth(known: Boolean = true) = RunAuthorizationPolicy.authorize(id, source(),
        ObservedUpdateTarget("NEXT", if (known) fp else "", if (known) sha else "", known), secret, true, now, elapsed)
    private fun rejected(block: () -> Unit) { assertThrows(UpdateSafetyException::class.java, block) }

    @Test fun explicitStartAndFreshMeasuredSourceAreRequired() {
        rejected { RunAuthorizationPolicy.authorize(id, source(), ObservedUpdateTarget("NEXT"), secret, false, now, elapsed) }
        listOf(source().copy(simulated = true), source().copy(capturedAtMillis = now - 120_001),
            source().copy(manufacturer = "other"), source().copy(identityConflicts = listOf("MODEL")),
            source().copy(slot = ""), source().copy(fingerprint = "x".repeat(1025)), source().copy(model = "model\nInjected")).forEach {
            rejected { RunAuthorizationPolicy.authorize(id, it, ObservedUpdateTarget("NEXT"), secret, true, now, elapsed) }
        }
    }
    @Test fun everyAuthorizationScopeFieldIsHmacBound() {
        val a = auth()
        listOf(a.copy(runId = "87654321-1234-1234-1234-123456789abc"), a.copy(model = "other"), a.copy(device = "other"),
            a.copy(channel = "retus"), a.copy(sourceBuild = "other"), a.copy(sourceFingerprint = "other"), a.copy(sourceBootId = "other"),
            a.copy(sourceSlot = "_b"), a.copy(targetBuild = "other"), a.copy(targetFingerprint = "other"),
            a.copy(packageSha256 = "b".repeat(64)), a.copy(actions = setOf(RunAction.REBOOT)),
            a.copy(consentNonce = "87654321-1234-1234-1234-123456789abc"), a.copy(issuedAtMillis = now - 1),
            a.copy(expiresAtMillis = a.expiresAtMillis - 1), a.copy(issuedElapsedMillis = elapsed - 1),
            a.copy(expiresElapsedMillis = a.expiresElapsedMillis - 1), a.copy(macSha256 = "0".repeat(64))).forEach {
            rejected { RunAuthorizationPolicy.verify(it, secret, now, elapsed) }
        }
        RunAuthorizationPolicy.verify(a, secret, now, elapsed)
        rejected { RunAuthorizationPolicy.verify(a, ByteArray(32), now, elapsed) }
    }
    @Test fun wallClockAndMonotonicDeadlinesIndependentlyStopActions() {
        val a = auth()
        rejected { RunAuthorizationPolicy.verify(a, secret, now - 1, elapsed) }
        rejected { RunAuthorizationPolicy.verify(a, secret, a.expiresAtMillis + 1, elapsed) }
        rejected { RunAuthorizationPolicy.verify(a, secret, now, elapsed - 1) }
        rejected { RunAuthorizationPolicy.verify(a, secret, now, a.expiresElapsedMillis + 1) }
        val p = RunAuthorizationPolicy.issueOperation(a, RunAction.APPLY_OTA, secret, now, elapsed)
        assertEquals(60_000, p.expiresAtMillis - p.issuedAtMillis)
        rejected { RunAuthorizationPolicy.verifyOperation(a, p, RunAction.APPLY_OTA, secret, now, elapsed + 60_001) }
    }
    @Test fun sourceBootSlotBuildProfileAndFingerprintCannotChangeDuringRun() {
        val a = auth()
        listOf(source().copy(bootId = "new-boot"), source().copy(slot = "_b"), source().copy(build = "NEXT"),
            source().copy(fingerprint = fp), source().copy(model = "other"), source().copy(channel = "retus"),
            source().copy(capturedAtMillis = now - 30_001)).forEach {
            rejected { RunAuthorizationPolicy.verifySource(a, it, secret, now, elapsed) }
        }
    }
    @Test fun onlyVerifiedSameTargetCanNarrowUnknownHashAndFingerprint() {
        val a = auth(false)
        rejected { RunAuthorizationPolicy.issueOperation(a, RunAction.RESTORE_STOCK, secret, now, elapsed) }
        rejected { RunAuthorizationPolicy.pinPackageHash(a, sha, "NEXT", fp, false, secret, now, elapsed) }
        val pinned = RunAuthorizationPolicy.pinPackageHash(a, sha, "NEXT", fp, true, secret, now, elapsed)
        assertEquals(fp, pinned.targetFingerprint); assertEquals(sha, pinned.packageSha256)
        assertNotEquals(a.scopeDigest(), pinned.scopeDigest())
        rejected { RunAuthorizationPolicy.pinPackageHash(pinned, "b".repeat(64), "NEXT", fp, true, secret, now, elapsed) }
        rejected { RunAuthorizationPolicy.pinPackageHash(pinned, sha, "OTHER", fp, true, secret, now, elapsed) }
        rejected { RunAuthorizationPolicy.pinPackageHash(pinned, sha, "NEXT", "other", true, secret, now, elapsed) }
    }
    @Test fun operationIsBoundToExactAuthorizationActionNoncePackageBootAndTimes() {
        val a = auth()
        val p = RunAuthorizationPolicy.issueOperation(a, RunAction.APPLY_OTA, secret, now, elapsed)
        listOf(p.copy(runId = "87654321-1234-1234-1234-123456789abc"), p.copy(action = RunAction.REBOOT),
            p.copy(scopeDigest = "0".repeat(64)), p.copy(sourceBootId = "other"), p.copy(nonce = a.consentNonce),
            p.copy(packageSha256 = "b".repeat(64)), p.copy(issuedAtMillis = now + 1), p.copy(expiresAtMillis = now + 59_999),
            p.copy(issuedElapsedMillis = elapsed + 1), p.copy(expiresElapsedMillis = elapsed + 59_999), p.copy(macSha256 = "0".repeat(64))).forEach {
            rejected { RunAuthorizationPolicy.verifyOperation(a, it, RunAction.APPLY_OTA, secret, now, elapsed) }
        }
        rejected { RunAuthorizationPolicy.verifyOperation(a, p, RunAction.REBOOT, secret, now, elapsed) }
        RunAuthorizationPolicy.verifyOperation(a, p, RunAction.APPLY_OTA, secret, now, elapsed)
    }
    @Test fun codecRoundTripsCanonicalAuthorizationAndShortPermit() {
        val a = auth(); val p = RunAuthorizationPolicy.issueOperation(a, RunAction.RESTORE_STOCK, secret, now, elapsed)
        assertEquals(a, RunAuthorizationCodec.decode(RunAuthorizationCodec.encode(a)))
        assertEquals(p, RunAuthorizationCodec.decodeOperation(RunAuthorizationCodec.encodeOperation(p)))
        assertEquals(RunAuthorizationCodec.encode(a), RunAuthorizationCodec.encode(a.copy(actions = a.actions.reversed().toSet())))
    }
    @Test fun codecRejectsDuplicateUnknownMissingBoundsPaddingAndMalformedUtf8() {
        val text = RunAuthorizationCodec.encode(auth())
        fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val line = text.lineSequence().first()
        listOf(text + line + "\n", text.replace(line, "unknown=MQ"), text.substringAfter('\n'), text.dropLast(1),
            text.replace("schema=MQ", "schema=MQ=="), text.replace("schema=MQ", "schema=_w"),
            text.replace("issuedAtMillis=${encoded(now.toString())}", "issuedAtMillis=${encoded("01")}"),
            "x".repeat(RunAuthorizationCodec.MAX_AUTHORIZATION_BYTES + 1)).forEach {
            assertThrows(Exception::class.java) { RunAuthorizationCodec.decode(it) }
        }
    }
}
