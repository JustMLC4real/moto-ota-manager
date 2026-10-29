package nl.moto.ota.platform

import nl.moto.ota.core.*

/** Decodes read-only, bounded evidence; no missing read is converted to an empty state file. */
object MetadataObservations {
    fun hex(probe: ProbeResult?, expectedBytes: Int): ByteArray? {
        if (probe?.successful != true) return null
        val text = probe.stdout.trim()
        val tokens = if (text.isEmpty()) emptyList() else text.split(Regex("\\s+"))
        if (tokens.size != expectedBytes || tokens.any { !it.matches(Regex("[a-fA-F0-9]{2}")) }) return null
        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }

    fun snapshotFile(probe: ProbeResult?): SnapshotFileMetadata? {
        if (probe?.successful != true) return null
        val lines = probe.stdout.trim().lines()
        if (lines.size < 2) return null
        val before = Regex("bytes=([0-9]+)").matchEntire(lines.first())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val after = Regex("after=([0-9]+)").matchEntire(lines.last())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (before != after || before !in 0..65_536) return null
        val body = lines.drop(1).dropLast(1).joinToString("\n")
        val bytes = hex(ProbeResult(0, body, ""), before) ?: return null
        return ReadOnlyMetadata.parseSnapshotState(bytes)
    }

    fun kernelConfig(probe: ProbeResult?): Map<String, String> {
        if (probe?.successful != true) return emptyMap()
        val permitted = setOf("vbmeta.device_state", "verifiedbootstate", "flash.locked", "slot_suffix")
        val parsed = linkedMapOf<String, String>()
        for (line in probe.stdout.lineSequence().filter { it.isNotBlank() }) {
            val m = Regex("androidboot[.]([a-z_.]+) = \"([A-Za-z0-9_]+)\"").matchEntire(line.trim()) ?: return emptyMap()
            val key = m.groupValues[1]
            if (key !in permitted || parsed.containsKey(key)) return emptyMap()
            parsed[key] = m.groupValues[2]
        }
        return parsed
    }

    fun combineSnapshot(original: SnapshotObservation, file: SnapshotFileMetadata?, vab: VirtualAbMetadata?, currentSlot: String,
                        directory: ProbeResult?, metadataReadAttempted: Boolean, toolReadPresent: Boolean = false): SnapshotObservation {
        if (!metadataReadAttempted) return original
        if (toolReadPresent && original.state == "UNKNOWN") return original.copy(evidence = original.evidence + "Onleesbare of tegenstrijdige tooluitvoer blijft blokkerend",
            presentationMessages = original.presentationMessages + DiagnosticMessage("snapshot.tool_unreadable"))
        val index = when (currentSlot) { "_a" -> 0; "_b" -> 1; else -> -1 }
        val hal = vab?.effectiveMergeStatus(index)
        if (file == null || hal == null || hal == VirtualAbMergeStatus.UNKNOWN) {
            // Successful alternate tools remain useful, but partial metadata cannot green-light idle.
            return if (original.state != "UNKNOWN" && original.state != "NONE") original else SnapshotObservation("UNKNOWN", evidence = original.evidence + "Metadata niet volledig leesbaar of niet ondersteund",
                presentationMessages = original.presentationMessages + DiagnosticMessage("snapshot.metadata_unreadable"))
        }
        val allowedHal = when (file.state) {
            SnapshotState.NONE, SnapshotState.INITIATED, SnapshotState.MERGE_NEEDS_REBOOT, SnapshotState.COMPLETE -> setOf(VirtualAbMergeStatus.NONE)
            SnapshotState.UNVERIFIED -> setOf(VirtualAbMergeStatus.NONE, VirtualAbMergeStatus.SNAPSHOTTED)
            SnapshotState.MERGING, SnapshotState.FAILED -> setOf(VirtualAbMergeStatus.MERGING)
            SnapshotState.CANCELLED -> setOf(VirtualAbMergeStatus.CANCELLED)
            else -> emptySet()
        }
        val evidence = original.evidence + "AOSP statebestand: ${file.state} (${file.format})" + "misc Virtual A/B: $hal"
        val messages = original.presentationMessages + DiagnosticMessage("snapshot.file", listOf(file.state.name, file.format.name)) + DiagnosticMessage("snapshot.misc", listOf(hal.name))
        if (hal !in allowedHal) return SnapshotObservation("UNKNOWN", evidence = evidence + "Tegenstrijdige snapshotbronnen", presentationMessages = messages + DiagnosticMessage("snapshot.conflict"))
        if (file.state == SnapshotState.NONE && (directory?.successful != true || directory.stdout.isNotBlank())) return SnapshotObservation("UNKNOWN", evidence = evidence + "Snapshotdirectory ontbreekt of bevat nog snapshots", presentationMessages = messages + DiagnosticMessage("snapshot.directory"))
        if (original.state != "UNKNOWN" && original.state != file.state.name) return SnapshotObservation("UNKNOWN", evidence = evidence + "Tool en directe metadata spreken elkaar tegen", presentationMessages = messages + DiagnosticMessage("snapshot.tool_conflict"))
        return SnapshotObservation(file.state.name, original.mergePercent, evidence, messages)
    }

    fun engineCallback(probe: ProbeResult?): String {
        if (probe?.successful != true) return "UNKNOWN"
        return runCatching {
            // This is our own fixed-schema serializer, not arbitrary JSON. An
            // extra record, duplicate key or format drift invalidates the probe.
            val pattern = Regex("""\{"schema":1,"status":"([A-Z_]+)","statusCode":(-?[0-9]+),"percent":([0-9.eE+\-]+|null),"completionCode":(-?[0-9]+|null),"source":"UpdateEngine\.bind\(callback\)","checkedNanos":([0-9]+),"reason":"([A-Z_]*)"\}""")
            val m = pattern.matchEntire(probe.stdout.trim()) ?: return "UNKNOWN"
            require(m.groupValues[5].toLong() > 0)
            val status = m.groupValues[1]
            val completion = m.groupValues[4].toIntOrNull()
            if (completion != null && completion != 0) return "ERROR"
            val names = listOf("IDLE", "CHECKING_FOR_UPDATE", "UPDATE_AVAILABLE", "DOWNLOADING", "VERIFYING", "FINALIZING", "UPDATED_NEED_REBOOT", "REPORTING_ERROR_EVENT", "ATTEMPTING_ROLLBACK", "DISABLED", "NEED_PERMISSION_TO_UPDATE", "CLEANUP_PREVIOUS_UPDATE")
            val code = m.groupValues[2].toInt()
            require(code in names.indices && names[code] == status && m.groupValues[6].isEmpty())
            val percent = m.groupValues[3].toDouble()
            require(percent.isFinite() && percent in 0.0..100.0)
            when (status) {
                "IDLE", "DOWNLOADING", "VERIFYING", "FINALIZING", "UPDATED_NEED_REBOOT" -> status
                "REPORTING_ERROR_EVENT", "ATTEMPTING_ROLLBACK" -> "ERROR"
                else -> "UNKNOWN"
            }
        }.getOrDefault("UNKNOWN")
    }
}
