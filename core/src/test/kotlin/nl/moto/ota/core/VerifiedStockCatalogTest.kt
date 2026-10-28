package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class VerifiedStockCatalogTest {
    // Independently recorded public build identity and digests; no image bytes or
    // private download references are included in these fixtures.
    private val trustedKey = "19acf5edb7a55816a55163183171097a785bdb5d815230b46d446d6e6004f8cc"
    private val measured = DeviceState(
        manufacturer = "motorola", model = "XT2607-1", device = "merc", channel = "reteu",
        androidVersion = "17", build = "A171WMH.44-25",
        fingerprint = "motorola/merc_g_syseq/merc:17/A171WMH.44-25/0347f0-d290bf:user/release-keys"
    )

    @Test fun independentlyAuthenticatedReferenceIsAvailableForExactMeasuredProfileAndDeviceKey() {
        val reference = requireNotNull(VerifiedStockCatalog.referenceFor(measured, setOf(trustedKey)))
        assertTrue(reference.authenticityVerified)
        assertEquals("9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea", reference.sha256)
        assertEquals(8_388_608L, reference.bytes)
        assertEquals("init_boot", reference.partition)
        assertEquals(measured.build, reference.build)
        assertEquals(measured.fingerprint, reference.fingerprint)
        assertEquals(measured.model, reference.model)
        assertEquals(measured.device, reference.device)
        assertEquals(measured.channel, reference.channel)
        assertTrue(reference.evidence.contains("docs/DEVICE-VALIDATION.md"))
        assertTrue(reference.evidence.contains("7b548666bfb8ca2ef8a65026f449af35b87354a8b098424fda52c434d0502c90"))
        assertTrue(reference.evidence.contains(trustedKey))
    }

    @Test fun previousAndFutureBuildsNeverReuseCurrentStockReference() {
        listOf("W3WMS36.40-75-5-2", "A171WMH.44-26", "A171WMH.44-25-1", "").forEach { build ->
            assertNull(build, VerifiedStockCatalog.referenceFor(measured.copy(build = build), setOf(trustedKey)))
        }
    }

    @Test fun signedPackageAndSystemFingerprintAliasesAreNotMeasuredFingerprintProof() {
        listOf(
            // The package vendor alias cannot establish the running system identity.
            "motorola/merc_g_vext/merc:15/A171WMH.44-25/0347f0:user/release-keys",
            "motorola/merc_g_sys/merc:17/A171WMM.44-25/d290bf:user/release-keys",
            measured.fingerprint.replace("0347f0-d290bf", "0347f0"),
            measured.fingerprint + " ", ""
        ).forEach { fingerprint ->
            assertNull(fingerprint, VerifiedStockCatalog.referenceFor(measured.copy(fingerprint = fingerprint), setOf(trustedKey)))
        }
    }

    @Test fun regionalModelAndDeviceDifferencesFailClosedEvenWithSameBuildAndKey() {
        listOf(measured.copy(channel = "retail"), measured.copy(channel = "retus"), measured.copy(channel = ""),
            measured.copy(model = "XT2607-2"), measured.copy(model = "motorola edge 70 pro"),
            measured.copy(device = "different"), measured.copy(manufacturer = "other"),
            measured.copy(androidVersion = "16"), measured.copy(androidVersion = "18"),
            measured.copy(androidVersion = ""), measured.copy(simulated = true), DeviceState()).forEach { device ->
            assertNull(VerifiedStockCatalog.referenceFor(device, setOf(trustedKey)))
        }
    }

    @Test fun absentOrDifferentTrustAnchorsCannotActivateTheBuiltInReference() {
        listOf(emptySet(), setOf("0".repeat(64)), setOf(trustedKey.dropLast(1) + "d"), setOf(trustedKey + " ")).forEach { keys ->
            assertNull(VerifiedStockCatalog.referenceFor(measured, keys))
        }
        assertNotNull(VerifiedStockCatalog.referenceFor(measured, setOf("0".repeat(64), trustedKey)))
    }
}
