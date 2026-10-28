package nl.moto.ota.platform

import nl.moto.ota.rootprobe.FirmwareSignatureProbe
import org.junit.Assert.*
import org.junit.Test

class FirmwareSignatureProbeSafetyTest {
    private val parent = "/storage/emulated/0/Android/data/com.motorola.ccc.ota/files"
    @Test fun normalAppsAndUnreviewedAndroidVersionsCannotVerifyAsRoot() {
        assertTrue(FirmwareSignatureProbe.supportsRuntime(0, 37))
        assertFalse(FirmwareSignatureProbe.supportsRuntime(2000, 37))
        assertFalse(FirmwareSignatureProbe.supportsRuntime(10001, 37))
        assertFalse(FirmwareSignatureProbe.supportsRuntime(0, 38))
    }
    @Test fun emptyNegativeAndOversizedPackagesAreRejected() {
        assertFalse(FirmwareSignatureProbe.isAllowedPackageBytes(0))
        assertFalse(FirmwareSignatureProbe.isAllowedPackageBytes(-1))
        assertTrue(FirmwareSignatureProbe.isAllowedPackageBytes(3_417_373_748))
        assertFalse(FirmwareSignatureProbe.isAllowedPackageBytes(FirmwareSignatureProbe.MAX_PACKAGE_BYTES + 1))
    }
    @Test fun canonicalNeighborFoldersOtherAppsAndNonZipFilesAreNotReadableTargets() {
        assertTrue(FirmwareSignatureProbe.isAllowedCanonicalPath("$parent/official-ota.zip", parent))
        assertFalse(FirmwareSignatureProbe.isAllowedCanonicalPath("${parent}2/official-ota.zip", parent))
        assertFalse(FirmwareSignatureProbe.isAllowedCanonicalPath(parent.replace("com.motorola.ccc.ota", "other.app") + "/a.zip", parent))
        assertFalse(FirmwareSignatureProbe.isAllowedCanonicalPath("$parent/payload_metadata.bin", parent))
        assertFalse(FirmwareSignatureProbe.isAllowedCanonicalPath("$parent/../other.zip", parent))
        assertFalse(FirmwareSignatureProbe.isAllowedCanonicalPath("$parent/package.zip;reboot", parent))
    }
}
