package nl.moto.ota.core

/** Export a small allowlist; root stdout, module names, file names and URL paths are never exported. */
object Privacy {
    fun diagnosticReport(device: DeviceState, probeStatus: Map<String, String>, backupCount: Int, workflow: String,
                         language: String = "en"): String = buildString {
        val nl = language.equals("nl", ignoreCase = true)
        fun label(en: String, dutch: String) = if (nl) dutch else en
        appendLine("Moto OTA Manager 1.0.0 — " + label("local report", "lokaal rapport"))
        appendLine(label("Source: ", "Bron: ") + if (device.simulated) label("SIMULATION", "SIMULATIE") else label("device observation", "toestelobservatie"))
        appendLine("Model: ${safe(device.model)} / ${safe(device.device)} / ${safe(device.channel)}")
        appendLine("Android: ${safe(device.androidVersion)}; build: ${safe(device.build)}; patch: ${safe(device.securityPatch)}")
        appendLine(label("Active slot: ", "Actieve slot: ") + safe(device.slot) + label("; inactive slot: ", "; inactieve slot: ") + safe(device.inactiveSlot))
        appendLine("Root: ${device.rootGranted}; Magisk-code: ${device.magiskCode}")
        appendLine("Virtual A/B: ${device.virtualAb}; snapshot: ${device.snapshot}; engine: ${device.engine}")
        appendLine(label("Boot successful: ", "Boot succesvol: ") + device.bootSuccessful + label("; bootloader unlocked: ", "; bootloader ontgrendeld: ") + device.bootloaderUnlocked + "; Verified Boot: ${safe(device.verifiedBootState)}")
        appendLine(label("Security source: ", "Beveiligingsbron: ") + safe(device.securityEvidenceSource) + label("; property mismatch: ", "; properties wijken af: ") + device.securityPropertyMismatch)
        appendLine(label("Backup candidates: ", "Back-upkandidaten: ") + backupCount + label("; module count: ", "; moduleaantal: ") + device.modules.size)
        appendLine("Workflow: ${safe(workflow)}")
        probeStatus.toSortedMap().forEach { (key, status) ->
            if (key.matches(Regex("[A-Z_]{1,64}")) && status in setOf("OK", "UNAVAILABLE", "TIMEOUT", "TRUNCATED")) appendLine("Probe $key: $status")
        }
        appendLine(label("No serial numbers, boot ID, IMEI, accounts, fingerprints, raw logs or download URLs are exported.",
            "Geen serienummers, boot-id, IMEI, accounts, fingerprints, ruwe logs of download-URL\'s geëxporteerd."))
        appendLine(label("A build or report does not prove a safe OTA installation on a real device.",
            "Een build of rapport bewijst geen veilige OTA-installatie op een echt toestel."))
    }
    private fun safe(value: String): String = value.take(100).replace(Regex("[^A-Za-z0-9._ /:-]"), "?")
}
