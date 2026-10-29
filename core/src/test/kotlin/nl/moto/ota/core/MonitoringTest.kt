package nl.moto.ota.core

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class MonitoringTest {
    private val now = 1_000_000L
    private val url = "https://dlmgredg-vz.gtm.svcmot.com/metadata.json?temporary=synthetic-secret"
    private fun observation() = MonitorObservation(safetyDevice().copy(capturedAtMillis = now), DiscoveryEvidence(
        localTargetBuild = "NEXT.17-1", localDownloadUrl = url, localContentKind = LocalContentKind.METADATA_ONLY,
        localMetadataObserved = true), recognizedMetadata = true)
    private fun assess(value: MonitorObservation = observation(), previous: String = "", at: Long = now) =
        MonitorPolicy.evaluate(value, previous, at)
    private fun assertQuiet(outcome: MonitorOutcome, value: MonitorObservation, at: Long = now) {
        val decision = assess(value, at = at)
        assertEquals(outcome, decision.outcome)
        assertNull(decision.notice)
    }
    private fun enabled() = MonitorState(enabled = true, generation = 4)
    private fun edit(encoded: String, key: String, value: String): String {
        val properties = Properties().apply { load(StringReader(encoded)); setProperty(key, value) }
        return StringWriter().also { properties.store(it, "test fixture") }.toString()
    }

    @Test fun freshNewMetadataCreatesInformationNoticeWithoutInstallabilityClaim() {
        val decision = assess()
        assertEquals(MonitorOutcome.NEW_METADATA, decision.outcome)
        assertEquals(MonitorNoticeKind.METADATA, decision.notice!!.kind)
        assertTrue(decision.notice.description.contains("alleen metadata"))
        assertTrue(decision.notice.description.contains("geen installeerbaar pakket bevestigd"))
        assertFalse(decision.notice.description.contains("synthetic-secret"))
        assertFalse(decision.notice.description.contains("https://"))
    }

    @Test fun zipReferenceRemainsUnverifiedLead() {
        val observed = observation()
        val decision = assess(observed.copy(discovery = observed.discovery.copy(localContentKind = LocalContentKind.OTA_ZIP)))
        assertEquals(MonitorOutcome.NEW_PACKAGE_REFERENCE, decision.outcome)
        assertEquals(MonitorNoticeKind.PACKAGE_REFERENCE, decision.notice!!.kind)
        assertTrue(decision.notice.description.contains("moeten nog worden gecontroleerd"))
    }

    @Test fun installedTargetNeverNotifiesEvenWhenOfficialUrlRemainsCached() {
        val observed = observation()
        assertQuiet(MonitorOutcome.NO_NEW_RELEASE,
            observed.copy(discovery = observed.discovery.copy(localTargetBuild = observed.device.build)))
    }

    @Test fun missingMetadataCannotAssertThatThereIsNoServerUpdate() {
        assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observation().copy(discovery = DiscoveryEvidence()))
        assertTrue(MonitorPolicy.describe(MonitorOutcome.METADATA_UNAVAILABLE).contains("bewijst niet"))
    }

    @Test fun unrecognizedSchemaAndUnobservedMetadataNeverNotify() {
        val observed = observation()
        assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observed.copy(recognizedMetadata = false))
        assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observed.copy(discovery = observed.discovery.copy(localMetadataObserved = false)))
    }

    @Test fun arbitraryHostAndUnknownResourceKindCannotBecomeNotification() {
        val observed = observation()
        listOf("https://untrusted.example/file", "http://dlmgredg-vz.gtm.svcmot.com/file", null).forEach {
            assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observed.copy(discovery = observed.discovery.copy(localDownloadUrl = it)))
        }
        assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observed.copy(discovery = observed.discovery.copy(localContentKind = LocalContentKind.UNKNOWN)))
    }

    @Test fun blankOrUnsafeBuildNamesNeverReachNotifications() {
        val observed = observation()
        listOf("", "next\nhttps://secret.example", "x".repeat(121), "../../build").forEach {
            assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observed.copy(discovery = observed.discovery.copy(localTargetBuild = it)))
            assertQuiet(MonitorOutcome.METADATA_UNAVAILABLE, observed.copy(device = observed.device.copy(build = it)))
        }
    }

    @Test fun onlyExactReviewedProfileAndAndroidVersionCanNotify() {
        val observed = observation()
        listOf(observed.device.copy(manufacturer = "other"), observed.device.copy(model = "XT2607-2"),
            observed.device.copy(device = "other"), observed.device.copy(channel = "retus"),
            observed.device.copy(androidVersion = "18"), observed.device.copy(simulated = true)).forEach {
            assertQuiet(MonitorOutcome.UNSUPPORTED_DEVICE, observed.copy(device = it))
        }
    }

    @Test fun revokedAndUnmeasuredRootRemainQuiet() {
        val observed = observation()
        listOf(observed.device.copy(rootGranted = false), observed.device.copy(rootChecked = false)).forEach {
            assertQuiet(MonitorOutcome.ROOT_REQUIRED, observed.copy(device = it))
        }
    }

    @Test fun staleFutureAndAbsentCaptureTimesRemainQuiet() {
        val observed = observation()
        listOf(0L, now + 1, now - 120_001).forEach {
            assertQuiet(MonitorOutcome.STALE_OBSERVATION, observed.copy(device = observed.device.copy(capturedAtMillis = it)))
        }
        assertNotNull(assess(observed.copy(device = observed.device.copy(capturedAtMillis = now - 120_000))).notice)
    }

    @Test fun everyActiveOrUnknownSnapshotAndEngineStateRemainQuiet() {
        val observed = observation()
        SnapshotState.entries.filter { it != SnapshotState.NONE }.forEach {
            assertQuiet(MonitorOutcome.DEVICE_BUSY, observed.copy(device = observed.device.copy(snapshot = it)))
        }
        EngineState.entries.filter { it != EngineState.IDLE }.forEach {
            assertQuiet(MonitorOutcome.DEVICE_BUSY, observed.copy(device = observed.device.copy(engine = it)))
        }
    }

    @Test fun sameReleaseAndRotatingSignedUrlStayDeduplicated() {
        val observed = observation()
        val identity = assess().notice!!.identity
        val rotated = observed.copy(discovery = observed.discovery.copy(localDownloadUrl = url.replace("synthetic-secret", "new-token")))
        assertEquals(identity, assess(rotated).notice!!.identity)
        assertQuietPreviouslyNotified(rotated, identity)
        assertNotEquals(identity, assess(observed.copy(discovery = observed.discovery.copy(localTargetBuild = "NEXT.17-2"))).notice!!.identity)
    }

    private fun assertQuietPreviouslyNotified(observation: MonitorObservation, id: String) {
        val decision = assess(observation, previous = id)
        assertEquals(MonitorOutcome.ALREADY_NOTIFIED, decision.outcome)
        assertNull(decision.notice)
    }

    @Test fun noticeIdentityIncludesInstalledBuildAndResourceKind() {
        val observed = observation()
        val identity = assess().notice!!.identity
        assertNotEquals(identity, assess(observed.copy(device = observed.device.copy(build = "OTHER_CURRENT"))).notice!!.identity)
        assertNotEquals(identity, assess(observed.copy(discovery = observed.discovery.copy(localContentKind = LocalContentKind.OTA_ZIP))).notice!!.identity)
        assertTrue(identity.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun disabledAndSupersededGenerationNeverInvokeNotificationSink() {
        var calls = 0
        val publish: (MonitorNotice) -> Boolean = { calls++; true }
        assertNull(MonitorTransition.recordIfCurrent(enabled().copy(enabled = false), 4, assess(), now, publish))
        assertNull(MonitorTransition.recordIfCurrent(enabled(), 3, assess(), now, publish))
        assertEquals(0, calls)
    }

    @Test fun publishedNoticeAndHistoryCommitTogetherAndSecondResultStaysQuiet() {
        var calls = 0
        val decision = assess()
        val after = MonitorTransition.recordIfCurrent(enabled(), 4, decision, now) { calls++; true }!!
        assertEquals(decision.notice!!.identity, after.lastNoticeIdentity)
        assertEquals(now, after.lastNoticeAtMillis)
        assertEquals(MonitorOutcome.NEW_METADATA, after.lastOutcome)
        assertTrue(after.history.single().notified)
        val repeated = MonitorTransition.recordIfCurrent(after, 4, decision, now + 1) { calls++; true }!!
        assertEquals(1, calls)
        assertEquals(MonitorOutcome.ALREADY_NOTIFIED, repeated.lastOutcome)
        assertFalse(repeated.history.last().notified)
    }

    @Test fun deniedOrFailingNotificationDoesNotConsumeReleaseIdentity() {
        listOf<(MonitorNotice) -> Boolean>({ false }, { throw SecurityException("permission revoked") }).forEach { publish ->
            val after = MonitorTransition.recordIfCurrent(enabled(), 4, assess(), now, publish)!!
            assertEquals("", after.lastNoticeIdentity)
            assertEquals(0L, after.lastNoticeAtMillis)
            assertEquals(MonitorOutcome.NOTIFICATIONS_DISABLED, after.lastOutcome)
            assertFalse(after.history.single().notified)
            val allowed = MonitorTransition.recordIfCurrent(after, 4, assess(), now + 1) { true }!!
            assertEquals(assess().notice!!.identity, allowed.lastNoticeIdentity)
        }
    }

    @Test fun unchangedResultRecordsSafeBoundedHistoryWithoutCallingSink() {
        var state = enabled()
        repeat(MonitorState.MAX_HISTORY + 8) { n ->
            state = MonitorTransition.recordIfCurrent(state, 4, MonitorDecision(MonitorOutcome.NO_NEW_RELEASE, "CURRENT", "CURRENT"),
                now + n) { fail("quiet result must not notify"); false }!!
        }
        assertEquals(MonitorState.MAX_HISTORY, state.history.size)
        assertEquals(now + 8, state.history.first().checkedAtMillis)
        assertEquals(now + 19, state.lastCheckedAtMillis)
    }

    @Test fun stateRoundTripPreservesOptInIntervalHistoryAndLastNotification() {
        val state = MonitorTransition.recordIfCurrent(enabled().copy(intervalHours = 12), 4, assess(), now) { true }!!
        assertEquals(state, MonitorState.decode(state.encode()))
    }

    @Test fun persistenceAndDiagnosticStringsExcludeSignedUrlsAndPrivateSourceIdentifiers() {
        val observed = observation().copy(device = observation().device.copy(bootId = "private-boot-id", modules = listOf("private-module")))
        val state = MonitorTransition.recordIfCurrent(enabled(), 4, assess(observed), now) { true }!!
        val text = state.encode() + observed.toString()
        listOf("https://", "synthetic-secret", "temporary", "private-boot-id", "private-module").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun absentConfigurationDefaultsOffAndCorruptionFailsClosed() {
        assertEquals(MonitorState(), MonitorState.decode(null))
        val valid = enabled().encode()
        listOf("", "not-properties", "x".repeat(65_537),
            edit(valid, "schema", "2"), edit(valid, "enabled", "yes"), edit(valid, "intervalHours", "0"),
            edit(valid, "intervalHours", "25"), edit(valid, "generation", "-1"),
            edit(valid, "lastOutcome", "UP_TO_DATE"), edit(valid, "lastCurrentBuild", "https://private.example"),
            edit(valid, "lastNoticeIdentity", "not-a-release-hash"), edit(valid, "historyCount", "13"),
            edit(valid, "lastCheckedAtMillis", "-1")).forEach {
            val decoded = MonitorState.decode(it)
            assertFalse(decoded.enabled)
            assertEquals(MonitorOutcome.STORE_CORRUPT, decoded.lastOutcome)
        }
    }

    @Test fun encoderSanitizesUnsafeIdentifiersAndBoundsHistoryBeforeWriting() {
        val state = enabled().copy(lastCurrentBuild = "https://private.example/secret", lastTargetBuild = "target\ntoken",
            lastNoticeIdentity = "secret-token", history = List(30) { MonitorHistoryEntry(now + it, MonitorOutcome.CHECK_FAILED, "bad\nvalue", "OK", false) })
        val restored = MonitorState.decode(state.encode())
        assertTrue(restored.enabled)
        assertEquals("", restored.lastCurrentBuild)
        assertEquals("", restored.lastTargetBuild)
        assertEquals("", restored.lastNoticeIdentity)
        assertEquals(MonitorState.MAX_HISTORY, restored.history.size)
        assertTrue(restored.history.all { it.currentBuild.isEmpty() })
    }
}
