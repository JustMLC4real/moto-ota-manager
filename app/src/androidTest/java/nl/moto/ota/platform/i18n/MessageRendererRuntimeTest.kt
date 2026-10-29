package nl.moto.ota.platform.i18n

import nl.moto.ota.R

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.*
import nl.moto.ota.platform.monitoring.MonitorNotifications
import nl.moto.ota.platform.download.DownloadNotifications
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Resource rendering only; no worker, root, network or real-device operation is invoked. */
@RunWith(AndroidJUnit4::class)
class MessageRendererRuntimeTest {
    private lateinit var en: Context
    private lateinit var nl: Context
    @Before fun setUp() {
        assumeTrue("Language runtime tests require a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(language: String): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        })
        en = localized("en")
        nl = localized("nl")
    }

    @Test fun cachedLegacyChecksAreRenderedInTheCurrentLanguage() {
        val cached = CheckResult("REGION_UNPROVEN", "ALLEEN_OUDE_NEDERLANDSE_TEKST")
        assertEquals("The signed metadata does not prove the required Motorola channel.", MessageRenderer.check(en, cached))
        assertEquals("De ondertekende metadata bewijst het vereiste Motorola-kanaal niet.", MessageRenderer.check(nl, cached))
        val capability = CheckResult("SIGNED_CURRENT_IMAGE_REQUIRED", "LEGACY_SECRET", "capability.SIGNED_CURRENT_IMAGE_REQUIRED")
        assertTrue(MessageRenderer.check(en, capability).startsWith("A verified signed stock image"))
        assertTrue(MessageRenderer.check(nl, capability).startsWith("Een geverifieerde ondertekende stockimage"))
        assertFalse(MessageRenderer.check(en, CheckResult("FUTURE_CODE", "LEGACY_SECRET")).contains("LEGACY_SECRET"))
    }

    @Test fun cachedSnapshotAndBackupEvidenceRetainTypedDetailsAfterLanguageSwitch() {
        val snapshot = SnapshotObservation("UNKNOWN", evidence = listOf("OUDE_TEKST"), presentationMessages = listOf(
            DiagnosticMessage("snapshot.file", listOf("MERGING", "PROTOBUF")), DiagnosticMessage("snapshot.conflict")))
        assertEquals(listOf("AOSP state file: MERGING (PROTOBUF)", "The snapshot sources conflict."), MessageRenderer.snapshotEvidence(en, snapshot))
        assertEquals(listOf("AOSP-statebestand: MERGING (PROTOBUF)", "De snapshotbronnen spreken elkaar tegen."), MessageRenderer.snapshotEvidence(nl, snapshot))
        val backup = BackupCandidate("magisk_backup_" + "a".repeat(40), 10, 20, null, null, false,
            listOf("OUDE_TEKST"), presentationMessages = listOf(DiagnosticMessage("backup.sha1")))
        assertEquals(listOf("The Magisk SHA1 directory does not match the image."), MessageRenderer.backupErrors(en, backup))
        assertEquals(listOf("De Magisk-SHA1-directory komt niet overeen met de image."), MessageRenderer.backupErrors(nl, backup))
    }

    @Test fun allDiscoveryMonitorAndWorkflowStatesHaveLanguageResources() {
        DiscoveryStatus.entries.forEach { state ->
            val cached = DiscoveryAssessment(state, "LEGACY_SECRET")
            listOf(en, nl).forEach { context ->
                val rendered=MessageRenderer.discovery(context,cached)
                assertFalse(rendered.contains("LEGACY_SECRET"))
                assertNotEquals(context.getString(R.string.msg_unknown,"discovery.${state.name}"),rendered)
            }
        }
        MonitorOutcome.entries.forEach { state ->
            listOf(en, nl).forEach { context ->
                assertNotEquals(context.getString(R.string.msg_unknown,"monitor.${state.name}"),MessageRenderer.monitor(context,state))
            }
        }
        UpdateStage.entries.forEach { state ->
            listOf(en, nl).forEach { context ->
                val rendered = MessageRenderer.workflow(context, UpdateRun(stage = state, message = "LEGACY_SECRET"))
                assertFalse(rendered.contains("LEGACY_SECRET"))
                assertNotEquals(context.getString(R.string.msg_unknown,"workflow.${state.name}"),rendered)
            }
        }
    }

    @Test fun notificationChannelNamesRefreshWithoutSendingANotice() {
        val manager = en.getSystemService(NotificationManager::class.java)
        MonitorNotifications.refreshChannel(en)
        DownloadNotifications.refreshChannel(en)
        assertEquals("Motorola update information", manager.getNotificationChannel("moto_metadata_monitor").name.toString())
        assertEquals("OTA downloads", manager.getNotificationChannel("moto_ota_downloads").name.toString())
        MonitorNotifications.refreshChannel(nl)
        DownloadNotifications.refreshChannel(nl)
        assertEquals("Motorola-update-informatie", manager.getNotificationChannel("moto_metadata_monitor").name.toString())
        assertEquals("OTA-downloads", manager.getNotificationChannel("moto_ota_downloads").name.toString())
        MonitorNotifications.refreshChannel(en)
        DownloadNotifications.refreshChannel(en)
    }
}
