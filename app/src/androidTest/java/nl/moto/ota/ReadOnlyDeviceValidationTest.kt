package nl.moto.ota

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.*
import nl.moto.ota.platform.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

/**
 * Opt-in physical-device evidence. These tests never import a package, write
 * settings, change root policy, restore an image, launch an updater or reboot.
 * Invocation requires the explicit motoReadOnlyValidation=yes runner argument.
 */
class ReadOnlyDeviceValidationTest {
    @Before fun explicitReadOnlyRunOnReviewedDeviceOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("motoReadOnlyValidation") == "yes")
        assertEquals("motorola", Build.MANUFACTURER.lowercase())
        assertEquals("merc", Build.DEVICE)
        assertEquals(37, Build.VERSION.SDK_INT)
    }

    @Test fun realDiagnosticsMatchTheIndependentReadOnlyBaseline() {
        val observed = liveInspection
        val device = observed.toDeviceState()
        assertTrue(device.exactProfileMatch)
        assertEquals("A171WMH.44-25", device.build)
        assertEquals("_b", device.slot)
        assertEquals("_a", device.inactiveSlot)
        assertTrue(device.rootChecked && device.rootGranted)
        assertEquals(31100, device.magiskCode)
        assertEquals(SnapshotState.NONE, device.snapshot)
        assertEquals(EngineState.IDLE, device.engine)
        assertEquals(true, device.bootSuccessful)
        assertEquals(true, device.bootloaderUnlocked)
        assertEquals("orange", device.verifiedBootState)
        assertEquals("KERNEL_BOOTCONFIG", device.securityEvidenceSource)
        assertTrue(device.securityPropertyMismatch)
        assertTrue(device.moduleInventoryKnown)
        assertTrue(device.partitions.containsAll(listOf("init_boot_a", "init_boot_b")))
        val backup = observed.backupInventory.singleOrNull { it.provenanceVerified }?.validation
        assertTrue(Readiness.evaluate(device, backup).blockers.joinToString { it.code }, Readiness.evaluate(device, backup).ready)
        val report = Privacy.diagnosticReport(device, observed.probes.mapValues { if (it.value.successful) "OK" else "UNAVAILABLE" }, observed.backupInventory.size, "IDLE")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("motoReadOnlyReport", report) })
    }

    @Test fun cachedMotorolaMetadataDoesNotOfferTheInstalledUpdateAgain() {
        val observed = liveInspection
        val metadata = observed.otaMetadata
        assertTrue("metadata=${metadata}; prefs=" + observed.probes.filterKeys { it in setOf("OTA_PREFS", "DOWNLOAD_PREFS") }.mapValues { "exit=${it.value.exitCode},timeout=${it.value.timedOut},truncated=${it.value.truncated},bytes=${it.value.stdout.length}" }, metadata.observed)
        assertEquals(LocalContentKind.METADATA_ONLY, metadata.contentKind)
        assertEquals(observed.toDeviceState().build, metadata.targetBuild)
        val discovery = FirmwareDiscovery.assess(DiscoveryEvidence(localTargetBuild = metadata.targetBuild.orEmpty(),
            localDownloadUrl = metadata.downloadUrl, localContentKind = metadata.contentKind, localMetadataObserved = metadata.observed), observed.toDeviceState().build)
        assertEquals(DiscoveryStatus.ALREADY_INSTALLED, discovery.status)
        assertFalse(discovery.canDownloadCandidate)
        assertFalse(discovery.authoritativeUpdateAvailable)
        assertEquals("0", observed.counters["ota.service.update.failure_count"])
        assertEquals("0", observed.counters["ota.service.update.updateFailCount"])
    }

    @Test fun actualBackupMatchesTheAuthenticatedStockAndUpdaterResolvesExplicitly() {
        val observed = liveInspection
        val backup = observed.backupInventory.single { it.provenanceVerified }
        assertEquals(8_388_608L, backup.uncompressedBytes)
        assertEquals("9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea", backup.sha256)
        assertTrue(backup.validation?.usableAsStock == true)
        assertTrue(backup.errors.isEmpty())
        val intent = OfficialApps.systemUpdaterIntent(InstrumentationRegistry.getInstrumentation().targetContext.packageManager)
        assertNotNull(intent)
        assertEquals("com.motorola.ccc.ota", intent?.component?.packageName)
    }

    @Test fun existingHistoricalOtaPassesTheActualPlatformSignatureVerifier() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = ReadOnlyShell().firmwareSignature(context.applicationInfo.sourceDir,
            "/sdcard/Android/data/com.motorola.ccc.ota/files/ab_dp_VABc_delta-Ota_Version_merc_g_sys_W3WMS36.40-75-5-2-A171WMH.44-25_release-keys_cid50.zip")
        assertTrue("Signature probe unavailable: exit=${result.exitCode},timeout=${result.timedOut}", result.successful)
        val json = JSONObject(result.stdout.trim())
        assertEquals(1, json.getInt("schema"))
        assertEquals(3_417_373_748L, json.getLong("packageBytes"))
        assertEquals("7b548666bfb8ca2ef8a65026f449af35b87354a8b098424fda52c434d0502c90", json.getString("sha256"))
        assertTrue("Actual Android signature verification failed: ${json.optString("reason")}", json.getBoolean("signatureVerified"))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("motoOtaVerification", result.stdout.trim()) })
    }

    companion object {
        private val liveInspection: Inspection by lazy {
            runBlocking { DeviceInspector().inspect(InstrumentationRegistry.getInstrumentation().targetContext, true) }
        }
    }
}
