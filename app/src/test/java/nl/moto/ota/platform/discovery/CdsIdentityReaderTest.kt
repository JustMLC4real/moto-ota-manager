package nl.moto.ota.platform.discovery

import nl.moto.ota.core.discovery.CdsInputField
import nl.moto.ota.core.discovery.CdsProtocol
import nl.moto.ota.platform.Inspection
import nl.moto.ota.platform.SnapshotObservation
import org.junit.Assert.*
import org.junit.Test

/** Synthetic measured-property fixtures. These tests never start su or open a connection. */
class CdsIdentityReaderTest {
    private val guid = "abc0123456789de"
    private fun properties() = mapOf(
        "ro.serialno" to "SYNTHETIC_SERIAL", "ro.boot.serialno" to "SYNTHETIC_SERIAL",
        "ro.mot.build.guid" to guid, "ro.product.manufacturer" to "motorola",
        "ro.product.model" to "Synthetic Motorola", "ro.boot.hardware.sku" to "XT0000-1",
        "ro.product.device" to "sample", "ro.product.name" to "sample_g", "ro.boot.carrier" to "reteu",
        "ro.build.id" to "CURRENT", "ro.build.fingerprint" to "motorola/sample_g/sample:17/CURRENT/123:user/release-keys",
        "ro.vendor.build.fingerprint" to "motorola/sample_g/sample:15/OLDER/100:user/release-keys",
        "ro.build.version.release" to "17", "ro.build.version.incremental" to "123"
    )
    private fun inspection(properties: Map<String, String> = emptyMap()) =
        Inspection(properties, emptyMap(), emptyList(), SnapshotObservation("UNKNOWN"), "UNKNOWN", 0, false)
    private fun read(properties: Map<String, String>, observed: Map<String, String> = emptyMap()) =
        CdsIdentityReader { properties }.read(inspection(observed), "nl")

    @Test fun fifteenHexGuidAndMissingLegacyDisplayUseTheObservedProductName() {
        val result = read(properties())
        assertTrue(result.missingFields.isEmpty())
        val identity = requireNotNull(result.identity)
        assertEquals(guid, identity.contextKey)
        assertEquals("sample_g", identity.product)
        assertEquals("XT0000-1", identity.device.model)
        assertEquals("motorola/sample_g/sample:15/OLDER/100:user/release-keys", identity.device.vendorFingerprint)
        assertTrue(CdsProtocol.checkUrl(identity).endsWith("/key/$guid"))
        assertTrue(CdsProtocol.checkBody(identity).toString(Charsets.UTF_8).contains("\"product\":\"sample_g\""))
    }

    @Test fun legacyDisplayStillTakesPrecedenceAndFullSha1StillWorks() {
        val fullGuid = "Ab".repeat(20)
        val identity = requireNotNull(read(properties() + mapOf("ro.mot.build.guid" to fullGuid,
            "ro.vendor.product.display" to "Synthetic Motorola Product")).identity)
        assertEquals("Synthetic Motorola Product", identity.product)
        assertEquals(fullGuid, identity.contextKey)
        assertTrue(CdsProtocol.checkUrl(identity).endsWith("/key/$fullGuid"))
    }

    @Test fun absentGuidAndBothAbsentProductSourcesRemainUnavailable() {
        val result = read(properties() - setOf("ro.mot.build.guid", "ro.product.name"))
        assertNull(result.identity)
        assertEquals(listOf(CdsInputField.CONTEXT_KEY, CdsInputField.PRODUCT), result.missingFields)
        val inventedAlias = read(properties() - "ro.mot.build.guid" + ("ro.vendor.mot.build.guid" to guid))
        assertNull(inventedAlias.identity)
        assertTrue(CdsInputField.CONTEXT_KEY in inventedAlias.missingFields)
        assertFalse(result.toString().contains("SYNTHETIC_SERIAL"))
        assertFalse(result.toString().contains(guid))
    }

    @Test fun aSpoofedProductNameCannotFillTheMissingMotorolaProduct() {
        val result = read(properties() + ("ro.product.name" to "bluejay"))
        assertNull(result.identity)
        assertTrue(CdsInputField.IDENTITY in result.missingFields)
    }

    @Test fun aSpoofedFingerprintCannotBeCombinedWithTheMotorolaHardware() {
        val result = read(properties() + mapOf("ro.product.name" to "bluejay",
            "ro.build.fingerprint" to "google/bluejay/bluejay:17/CURRENT/123:user/release-keys"))
        assertNull(result.identity)
        assertTrue(CdsInputField.IDENTITY in result.missingFields)
    }

    @Test fun changedInspectionOrConflictingVendorEvidenceCannotAcquireAnIdentity() {
        val changed = read(properties(), mapOf("ro.product.name" to "different_g"))
        assertNull(changed.identity)
        assertEquals(listOf(CdsInputField.IDENTITY), changed.missingFields)
        val vendor = read(properties() + ("ro.vendor.build.fingerprint" to "google/bluejay/bluejay:15/OLDER/100:user/release-keys"))
        assertNull(vendor.identity)
        assertTrue(CdsInputField.IDENTITY in vendor.missingFields)
    }

    @Test fun serialChannelAndTechnicalModelConflictsRemainBlocked() {
        assertEquals(listOf(CdsInputField.SERIAL_NUMBER), read(properties() + ("ro.boot.serialno" to "OTHER_SERIAL")).missingFields)
        listOf("ro.carrier" to "retus", "ro.boot.product.hardware.sku" to "XT0000-2").forEach { conflict ->
            val result = read(properties() + conflict)
            assertNull(result.identity)
            assertTrue(CdsInputField.IDENTITY in result.missingFields)
        }
    }

    @Test fun failedRootReadNeverBecomesAnEmptyOrFabricatedIdentity() {
        val result = CdsIdentityReader { null }.read(inspection(), "nl")
        assertNull(result.identity)
        assertEquals(listOf(CdsInputField.ROOT), result.missingFields)
    }

    @Test fun fixedOutputPreservesEmptyPropertiesAndRejectsMalformedOrUnprivilegedReads() {
        val values = properties()
        val output = "0\n" + CdsIdentityReader.PROPERTIES.joinToString("\n", postfix = "\n") { values[it].orEmpty() }
        val parsed = requireNotNull(CdsIdentityReader.parseFixedOutput(output))
        assertEquals(guid, parsed["ro.mot.build.guid"])
        assertEquals("", parsed["ro.vendor.product.display"])
        assertEquals("sample_g", parsed["ro.product.name"])
        listOf(output.replaceFirst("0\n", "2000\n"), output + "extra\n", output.dropLast(2),
            output.replace("sample_g\n", "sample_g\t\n"), output.replace(guid, "a".repeat(513))).forEach {
            assertNull(CdsIdentityReader.parseFixedOutput(it))
        }
    }
}
