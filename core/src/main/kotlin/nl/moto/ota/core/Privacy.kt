package nl.moto.ota.core

/** Export a small allowlist; root stdout, module names, file names and URL paths are never exported. */
object Privacy {
    fun diagnosticReport(device: DeviceState, probeStatus: Map<String, String>, backupCount: Int, workflow: String): String = buildString {
        appendLine("Moto OTA Manager 0.1.0-safe — lokaal rapport")
        appendLine("Bron: " + if (device.simulated) "SIMULATIE" else "toestelobservatie")
        appendLine("Model: ${safe(device.model)} / ${safe(device.device)} / ${safe(device.channel)}")
        appendLine("Android: ${safe(device.androidVersion)}; build: ${safe(device.build)}; patch: ${safe(device.securityPatch)}")
        appendLine("Actieve slot: ${safe(device.slot)}; inactieve slot: ${safe(device.inactiveSlot)}")
        appendLine("Root: ${device.rootGranted}; Magisk-code: ${device.magiskCode}")
        appendLine("Virtual A/B: ${device.virtualAb}; snapshot: ${device.snapshot}; engine: ${device.engine}")
        appendLine("Back-upkandidaten: $backupCount; moduleaantal: ${device.modules.size}")
        appendLine("Workflow: ${safe(workflow)}")
        probeStatus.toSortedMap().forEach { (key, status) ->
            if (key.matches(Regex("[A-Z_]{1,64}")) && status in setOf("OK", "UNAVAILABLE", "TIMEOUT", "TRUNCATED")) appendLine("Probe $key: $status")
        }
        appendLine("Geen serienummers, boot-id, IMEI, accounts, fingerprints, ruwe logs of download-URL's geëxporteerd.")
        appendLine("Een build of rapport bewijst geen veilige OTA-installatie op een echt toestel.")
    }
    private fun safe(value: String): String = value.take(100).replace(Regex("[^A-Za-z0-9._ /:-]"), "?")
}
