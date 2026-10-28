package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class FirmwareDiscoveryTest {
    private val url = "https://dlmgredg-vz.gtm.svcmot.com/example.zip?temporary=synthetic"

    @Test fun noCachedMetadataDoesNotAssertThatMotorolaHasNoUpdate() {
        val result = FirmwareDiscovery.assess(DiscoveryEvidence(officialUpdaterReachable = true), "CURRENT")
        assertEquals(DiscoveryStatus.NO_LOCAL_METADATA, result.status)
        assertFalse(result.authoritativeUpdateAvailable)
        assertFalse(result.canDownloadCandidate)
    }

    @Test fun cachedAllowedUrlIsOnlyCandidateUntilPackageValidation() {
        val result = FirmwareDiscovery.assess(DiscoveryEvidence(localTargetBuild = "NEXT", localDownloadUrl = url), "CURRENT")
        assertEquals(DiscoveryStatus.CACHED_OFFICIAL_CANDIDATE, result.status)
        assertTrue(result.canDownloadCandidate)
        assertFalse(result.authoritativeUpdateAvailable)
    }

    @Test fun externalReleaseDoesNotOverrideUpToDateMotorolaResponse() {
        val result = FirmwareDiscovery.assess(DiscoveryEvidence(officialUpdaterReachable = true, externalTargetBuild = "NEXT", updaterReportsUpToDate = true), "CURRENT")
        assertEquals(DiscoveryStatus.OFFER_DISCREPANCY, result.status)
        assertFalse(result.canDownloadCandidate)
        assertFalse(result.authoritativeUpdateAvailable)
    }

    @Test fun externalReportAloneCannotSupplyTrustedDownload() {
        val result = FirmwareDiscovery.assess(DiscoveryEvidence(externalTargetBuild = "NEXT"), "CURRENT")
        assertEquals(DiscoveryStatus.EXTERNAL_REPORT_ONLY, result.status)
        assertFalse(result.authoritativeUpdateAvailable)
        assertFalse(result.canDownloadCandidate)
    }

    @Test fun alreadyInstalledCachedBuildIsNotNewUpdate() {
        val result = FirmwareDiscovery.assess(DiscoveryEvidence(localTargetBuild = "CURRENT", localDownloadUrl = url), "CURRENT")
        assertEquals(DiscoveryStatus.ALREADY_INSTALLED, result.status)
        assertFalse(result.canDownloadCandidate)
    }

    @Test fun arbitraryExternalDownloadHostDoesNotBecomeOfficial() {
        val result = FirmwareDiscovery.assess(DiscoveryEvidence(localTargetBuild = "NEXT", localDownloadUrl = "https://untrusted.example/ota.zip"), "CURRENT")
        assertEquals(DiscoveryStatus.UNTRUSTED_LOCAL_METADATA, result.status)
        assertFalse(result.canDownloadCandidate)
    }

    @Test fun missingUpdaterAndMetadataRemainUnknown() {
        assertEquals(DiscoveryStatus.UNKNOWN, FirmwareDiscovery.assess(DiscoveryEvidence(), "CURRENT").status)
    }
}
