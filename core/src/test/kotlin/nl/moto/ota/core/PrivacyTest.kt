package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class PrivacyTest {
    @Test fun sensitiveFieldsAreOmittedRatherThanExportedWithCosmeticRedaction() {
        val device = DeviceState(model = "XT2607-1", device = "merc", channel = "reteu", build = "A171WMH.44-25",
            fingerprint = "account-fingerprint-secret", vendorFingerprint = "vendor-secret-identifier",
            bootId = "boot-unique-secret", modules = listOf("personal-module-secret"), magiskVersion = "unneeded-version-secret")
        val result = Privacy.diagnosticReport(device,
            mapOf("OTA_PREFS" to "https://official.invalid/path?token=signed-url-secret", "ROOT_ID" to "OK"), 2, "INSPECTED")
        listOf("account-fingerprint-secret", "vendor-secret-identifier", "boot-unique-secret", "personal-module-secret",
            "unneeded-version-secret", "signed-url-secret", "https://").forEach { assertFalse(result.contains(it)) }
        assertTrue(result.contains("module count: 1")); assertTrue(result.contains("Probe ROOT_ID: OK"))
    }

    @Test fun reportLanguageChangesOnlyAllowlistedLabels() {
        val device = DeviceState(model = "XT2607-1", simulated = true, fingerprint = "fingerprint-secret", bootId = "boot-secret", modules = listOf("module-secret"))
        for (language in listOf("en", "nl", "fr")) {
            val report = Privacy.diagnosticReport(device, mapOf("ROOT_ID" to "OK", "OTA_PREFS" to "https://source.invalid/token-secret"), 1, "CHECKING", language)
            assertTrue(report.contains("Moto OTA Manager 1.0.0"))
            assertTrue(report.contains(if (language == "nl") "lokaal rapport" else "local report"))
            assertTrue(report.contains(if (language == "nl") "SIMULATIE" else "SIMULATION"))
            assertTrue(report.contains("Probe ROOT_ID: OK"))
            listOf("fingerprint-secret", "boot-secret", "module-secret", "token-secret", "https://").forEach { assertFalse(report.contains(it)) }
        }
    }

    @Test fun probeKeysCannotInjectRawOutputOrAdditionalReportLines() {
        val result = Privacy.diagnosticReport(DeviceState(), mapOf("ROOT\nIMEI=123456" to "OK", "ROOT_ID" to "TIMEOUT"), 0, "CHECKING")
        assertFalse(result.contains("123456")); assertTrue(result.contains("Probe ROOT_ID: TIMEOUT"))
    }

    @Test fun simulatedObservationsAreExplicitlyMarked() {
        assertTrue(Privacy.diagnosticReport(DeviceState(simulated = true), emptyMap(), 0, "CHECKING").contains("SIMULATION"))
        assertTrue(Privacy.diagnosticReport(DeviceState(), emptyMap(), 0, "CHECKING").contains("device observation"))
    }

    @Test fun userControlledNewlinesCannotForgeSuccessInReport() {
        val value = Privacy.diagnosticReport(DeviceState(model = "XT2607-1\nSUCCESS=true"), emptyMap(), 0, "CHECKING\nVERIFIED")
        assertFalse(value.contains("\nSUCCESS")); assertFalse(value.contains("\nVERIFIED"))
    }
}
