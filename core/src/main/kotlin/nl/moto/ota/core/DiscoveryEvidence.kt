package nl.moto.ota.core

enum class DiscoveryStatus { UNKNOWN, NO_LOCAL_METADATA, CACHED_OFFICIAL_CANDIDATE, UNTRUSTED_LOCAL_METADATA, EXTERNAL_REPORT_ONLY, OFFER_DISCREPANCY, ALREADY_INSTALLED }

/** Local preferences and external claims are untrusted leads, not server-authorized update offers. */
data class DiscoveryEvidence(
    val officialUpdaterReachable: Boolean = false,
    val localTargetBuild: String = "",
    val localDownloadUrl: String? = null,
    val externalTargetBuild: String? = null,
    val updaterReportsUpToDate: Boolean? = null
)

data class DiscoveryAssessment(
    val status: DiscoveryStatus,
    val explanation: String,
    val canDownloadCandidate: Boolean = false,
    val authoritativeUpdateAvailable: Boolean = false
)

object FirmwareDiscovery {
    fun assess(evidence: DiscoveryEvidence, currentBuild: String): DiscoveryAssessment {
        val external = !evidence.externalTargetBuild.isNullOrBlank() && evidence.externalTargetBuild != currentBuild
        val local = evidence.localTargetBuild.isNotBlank() || !evidence.localDownloadUrl.isNullOrBlank()
        if (external && evidence.updaterReportsUpToDate == true) return DiscoveryAssessment(
            DiscoveryStatus.OFFER_DISCREPANCY,
            "De updater meldt geen update terwijl een externe bron een release noemt. Dit kan een gefaseerde uitrol, ongeschikt kanaal of verouderde melding zijn; geen serverblokkade bewezen. Gebruik diagnostiek en de officiële updater."
        )
        if (evidence.localTargetBuild.isNotBlank() && evidence.localTargetBuild == currentBuild) return DiscoveryAssessment(
            DiscoveryStatus.ALREADY_INSTALLED, "Lokale metadata verwijst naar de al geïnstalleerde build; bied dit pakket niet opnieuw als nieuwe update aan."
        )
        if (local && evidence.localDownloadUrl?.let(OfficialDownloadPolicy::accepts) == true) return DiscoveryAssessment(
            DiscoveryStatus.CACHED_OFFICIAL_CANDIDATE,
            "Een bestaande lokale verwijzing gebruikt de expliciet toegestane Motorola-downloadhost. Beschikbaarheid, bronbuild, regio en authenticiteit zijn nog niet bewezen; de URL kan verlopen zijn.",
            canDownloadCandidate = true
        )
        if (local) return DiscoveryAssessment(DiscoveryStatus.UNTRUSTED_LOCAL_METADATA, "Lokale metadata is onvolledig of gebruikt geen toegestane officiële downloadhost; geen automatische download.")
        if (external) return DiscoveryAssessment(DiscoveryStatus.EXTERNAL_REPORT_ONLY, "Alleen een externe releasemelding. Geen officieel toestel-/regiospecifiek aanbod of geschikte download bewezen.")
        if (evidence.officialUpdaterReachable) return DiscoveryAssessment(DiscoveryStatus.NO_LOCAL_METADATA, "Geen lokale OTA-downloadmetadata gevonden. Dit bewijst niet dat Motorola geen update aanbiedt; controleer de officiële updater.")
        return DiscoveryAssessment(DiscoveryStatus.UNKNOWN, "Officieel updateaanbod is niet vast te stellen. Er is geen geverifieerde publieke Motorola-check-API voor deze app.")
    }
}
