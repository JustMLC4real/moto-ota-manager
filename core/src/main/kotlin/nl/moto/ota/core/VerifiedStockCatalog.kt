package nl.moto.ota.core

/**
 * Audited, built-in stock references. This is deliberately not an import format:
 * user-provided JSON, filenames and matching image headers cannot establish trust.
 * Each entry applies only to its measured device identity and authenticated bytes.
 */
object VerifiedStockCatalog {
    private const val TRUSTED_SPKI = "19acf5edb7a55816a55163183171097a785bdb5d815230b46d446d6e6004f8cc"
    private const val OTA_SHA256 = "7b548666bfb8ca2ef8a65026f449af35b87354a8b098424fda52c434d0502c90"
    private const val CURRENT_BUILD = "A171WMH.44-25"
    private const val MEASURED_FINGERPRINT = "motorola/merc_g_syseq/merc:17/A171WMH.44-25/0347f0-d290bf:user/release-keys"

    private val currentReference = StockReference(
        sha256 = "9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea",
        bytes = 8_388_608,
        build = CURRENT_BUILD,
        fingerprint = MEASURED_FINGERPRINT,
        device = "merc",
        model = "XT2607-1",
        channel = "reteu",
        partition = "init_boot",
        authenticityVerified = true,
        evidence = "Geauditeerde referentie: docs/DEVICE-VALIDATION.md. " +
            "Officiële OTA SHA-256 $OTA_SHA256; whole-filehandtekening onafhankelijk bevestigd met Python en OpenSSL " +
            "tegen de toestel-otacerts, SPKI SHA-256 $TRUSTED_SPKI. " +
            "De nieuwe init_boot-grootte en SHA-256 in het geauthenticeerde payloadmanifest komen exact overeen " +
            "met de onafhankelijk gelezen stockback-up. Alleen gekoppeld aan de exact gemeten Android 17-build en fingerprint."
    )

    /**
     * The caller supplies keys actually read from the device OTA trust store.
     * Signed-package fingerprint aliases never substitute for this measured profile.
     */
    fun referenceFor(device: DeviceState, trustedSpkiSha256: Set<String>): StockReference? {
        if (TRUSTED_SPKI !in trustedSpkiSha256 || device.simulated) return null
        if (device.manufacturer != "motorola" || device.model != currentReference.model ||
            device.device != currentReference.device || device.channel != currentReference.channel ||
            device.androidVersion != "17" || device.build != CURRENT_BUILD ||
            device.fingerprint != MEASURED_FINGERPRINT) return null
        return currentReference
    }
}
