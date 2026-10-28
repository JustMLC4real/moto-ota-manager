package nl.moto.ota.platform

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

object ObservationParsers {
    private val snapshotNames = mapOf(
        "0" to "NONE", "None" to "NONE", "1" to "INITIATED", "Initiated" to "INITIATED",
        "2" to "UNVERIFIED", "Unverified" to "UNVERIFIED", "3" to "MERGING", "Merging" to "MERGING",
        "4" to "MERGE_NEEDS_REBOOT", "MergeNeedsReboot" to "MERGE_NEEDS_REBOOT",
        "5" to "COMPLETE", "MergeCompleted" to "COMPLETE", "6" to "FAILED", "MergeFailed" to "FAILED",
        "7" to "CANCELLED", "Cancelled" to "CANCELLED"
    )

    fun snapshot(dump: ProbeResult?, hal: ProbeResult?): SnapshotObservation {
        val evidence = mutableListOf<String>()
        val globalStates = if (dump?.successful == true) Regex("(?m)^Update state: *([A-Za-z0-9]+) *$")
            .findAll(dump.stdout).map { snapshotNames[it.groupValues[1]] ?: "UNKNOWN" }.toList() else emptyList()
        val globalStateLines = if (dump?.successful == true) Regex("(?m)^Update state:").findAll(dump.stdout).count() else 0
        // A failed/absent tool can fall back to the HAL. A successful but malformed or
        // ambiguous dump is contradictory evidence, including duplicates of the same value.
        val malformedDump = dump?.successful == true && (globalStates.size != 1 || globalStateLines != 1)
        val global = globalStates.singleOrNull()
        val halState = if (hal?.successful == true) when (hal.stdout.trim()) {
            "none" -> "NONE"
            "snapshotted" -> "UNVERIFIED"
            "merging" -> "MERGING"
            "cancelled" -> "CANCELLED"
            else -> "UNKNOWN"
        } else null
        if (global != null) evidence += "snapshotctl: $global"
        if (halState != null) evidence += "boot HAL: $halState"
        // A disagreement about idle is never permission to start another update.
        val state = when {
            malformedDump -> "UNKNOWN"
            global == null && halState == null -> "UNKNOWN"
            global == "UNKNOWN" || halState == "UNKNOWN" -> "UNKNOWN"
            global != null && halState != null && global != halState -> "UNKNOWN"
            else -> global ?: halState ?: "UNKNOWN"
        }
        val percent = if (state == "MERGING" && dump?.successful == true) {
            Regex("(?m)^Merge completion: *([0-9]+(?:\\.[0-9]+)?)% *$")
                .findAll(dump.stdout).toList().singleOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
        } else null
        return SnapshotObservation(state, percent, evidence)
    }

    fun engine(observation: ProbeResult?): String {
        if (observation == null || observation.truncated || observation.failure != null) return "UNKNOWN"
        // timeout(1) returns 124 after a successful read-only callback subscription.
        if (observation.exitCode !in listOf(0, 124) || observation.timedOut) return "UNKNOWN"
        val text = observation.stdout + "\n" + observation.stderr
        val completion = Regex("onPayloadApplicationComplete\\([^\\r\\n]*?\\((\\d+)\\)\\)")
            .findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        // A fresh failure callback must not disappear behind a following IDLE event.
        // Nonzero special results also require review; none is an observed successful OTA.
        if (completion != null && completion != 0) return "ERROR"
        val callback = Regex("onStatusUpdate\\((?:UPDATE_STATUS_)?([A-Z_]+) \\((\\d+)\\), [0-9.eE+\\-]+\\)")
            .findAll(text).lastOrNull() ?: return "UNKNOWN"
        val expected = mapOf("IDLE" to 0, "CHECKING_FOR_UPDATE" to 1, "UPDATE_AVAILABLE" to 2,
            "DOWNLOADING" to 3, "VERIFYING" to 4, "FINALIZING" to 5, "UPDATED_NEED_REBOOT" to 6,
            "REPORTING_ERROR_EVENT" to 7, "ATTEMPTING_ROLLBACK" to 8, "DISABLED" to 9,
            "NEED_PERMISSION_TO_UPDATE" to 10, "CLEANUP_PREVIOUS_UPDATE" to 11)
        val name = callback.groupValues[1]
        if (expected[name] != callback.groupValues[2].toIntOrNull()) return "UNKNOWN"
        return when (name) {
            "IDLE", "DOWNLOADING", "VERIFYING", "FINALIZING", "UPDATED_NEED_REBOOT" -> name
            "REPORTING_ERROR_EVENT", "ATTEMPTING_ROLLBACK" -> "ERROR"
            else -> "UNKNOWN"
        }
    }

    /** Parse a bounded XML map; DTD and entity declarations are rejected before parser creation. */
    fun preferences(xml: String): Map<String, String> {
        require(xml.length <= 1_048_576) { "Preferences exceed diagnostic limit" }
        require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "DTD/entities forbidden" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External entity forbidden") }
        }
        val document = builder.parse(InputSource(StringReader(xml)))
        require(document.documentElement.tagName == "map") { "Expected SharedPreferences map" }
        val result = linkedMapOf<String, String>()
        val nodes = document.documentElement.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            require(!result.containsKey(name)) { "Duplicate preference" }
            if (node.nodeName in setOf("string", "int", "long", "boolean", "float")) {
                result[name] = node.attributes?.getNamedItem("value")?.nodeValue ?: node.textContent
            }
        }
        return result
    }
}
