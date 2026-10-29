package nl.moto.ota.core

import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Properties

enum class MonitorOutcome {
    NEVER, DISABLED, UNSUPPORTED_DEVICE, ROOT_REQUIRED, STALE_OBSERVATION,
    DEVICE_BUSY, NO_NEW_RELEASE, METADATA_UNAVAILABLE, NEW_METADATA, NEW_PACKAGE_REFERENCE,
    ALREADY_NOTIFIED, NOTIFICATIONS_DISABLED, CHECK_FAILED, STORE_CORRUPT, NEW_SERVER_OFFER
}

enum class MonitorNoticeKind { METADATA, PACKAGE_REFERENCE, SERVER_OFFER }

data class MonitorNotice(
    val identity: String,
    val kind: MonitorNoticeKind,
    val targetBuild: String,
    val title: String,
    val description: String
)

data class MonitorDecision(
    val outcome: MonitorOutcome,
    val currentBuild: String = "",
    val targetBuild: String = "",
    val notice: MonitorNotice? = null
)

/** Contains only technical observations; private URLs are used transiently for host policy. */
data class MonitorObservation(
    val device: DeviceState,
    val discovery: DiscoveryEvidence,
    val recognizedMetadata: Boolean
) {
    override fun toString() = "MonitorObservation(exactProfile=${device.exactProfileMatch}, recognizedMetadata=$recognizedMetadata, discovery=$discovery)"
}

data class MonitorHistoryEntry(
    val checkedAtMillis: Long,
    val outcome: MonitorOutcome,
    val currentBuild: String,
    val targetBuild: String,
    val notified: Boolean
)

/** Stored locally without download URLs, tokens, raw XML, boot-id or module identifiers. */
data class MonitorState(
    val enabled: Boolean = false,
    val intervalHours: Int = 6,
    val generation: Long = 0,
    val lastCheckedAtMillis: Long = 0,
    val lastOutcome: MonitorOutcome = MonitorOutcome.NEVER,
    val lastCurrentBuild: String = "",
    val lastTargetBuild: String = "",
    val lastNoticeIdentity: String = "",
    val lastNoticeAtMillis: Long = 0,
    val history: List<MonitorHistoryEntry> = emptyList()
) {
    fun encode(): String {
        require(intervalHours in 1..24 && generation >= 0)
        val p = Properties()
        p.setProperty("schema", "1")
        p.setProperty("enabled", enabled.toString())
        p.setProperty("intervalHours", intervalHours.toString())
        p.setProperty("generation", generation.toString())
        p.setProperty("lastCheckedAtMillis", lastCheckedAtMillis.toString())
        p.setProperty("lastOutcome", lastOutcome.name)
        p.setProperty("lastCurrentBuild", safeBuild(lastCurrentBuild))
        p.setProperty("lastTargetBuild", safeBuild(lastTargetBuild))
        p.setProperty("lastNoticeIdentity", lastNoticeIdentity.takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty())
        p.setProperty("lastNoticeAtMillis", lastNoticeAtMillis.toString())
        val bounded = history.takeLast(MAX_HISTORY)
        p.setProperty("historyCount", bounded.size.toString())
        bounded.forEachIndexed { index, entry ->
            p.setProperty("history.$index.time", entry.checkedAtMillis.toString())
            p.setProperty("history.$index.outcome", entry.outcome.name)
            p.setProperty("history.$index.current", safeBuild(entry.currentBuild))
            p.setProperty("history.$index.target", safeBuild(entry.targetBuild))
            p.setProperty("history.$index.notified", entry.notified.toString())
        }
        return StringWriter().also { p.store(it, "Moto OTA Manager local metadata monitor; no URLs or identifiers") }.toString()
    }

    companion object {
        const val MAX_HISTORY = 12
        fun safeBuild(value: String): String = value.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,120}")) }.orEmpty()
        fun decode(text: String?): MonitorState {
            if (text == null) return MonitorState()
            return runCatching {
                require(text.length in 1..65_536)
                val p = Properties().apply { load(StringReader(text)) }
                fun get(key: String) = requireNotNull(p.getProperty(key))
                fun positive(key: String) = get(key).toLong().also { require(it >= 0) }
                fun boolean(key: String) = when (get(key)) { "true" -> true; "false" -> false; else -> error("Invalid boolean") }
                fun build(key: String) = get(key).also { require(it.isEmpty() || safeBuild(it) == it) }
                require(get("schema") == "1")
                val interval = get("intervalHours").toInt().also { require(it in 1..24) }
                val noticeId = get("lastNoticeIdentity").also { require(it.isEmpty() || it.matches(Regex("[a-f0-9]{64}"))) }
                val count = get("historyCount").toInt().also { require(it in 0..MAX_HISTORY) }
                val history = (0 until count).map { index ->
                    MonitorHistoryEntry(positive("history.$index.time"), MonitorOutcome.valueOf(get("history.$index.outcome")),
                        build("history.$index.current"), build("history.$index.target"), boolean("history.$index.notified"))
                }
                MonitorState(boolean("enabled"), interval, positive("generation"), positive("lastCheckedAtMillis"),
                    MonitorOutcome.valueOf(get("lastOutcome")), build("lastCurrentBuild"), build("lastTargetBuild"),
                    noticeId, positive("lastNoticeAtMillis"), history)
            }.getOrElse { MonitorState(lastOutcome = MonitorOutcome.STORE_CORRUPT) }
        }
    }
}

/** Caller serializes this operation with configuration writes and commits its result atomically. */
object MonitorTransition {
    fun recordIfCurrent(before: MonitorState, generation: Long, decision: MonitorDecision, checkedAtMillis: Long,
                        publish: (MonitorNotice) -> Boolean): MonitorState? {
        if (!before.enabled || before.generation != generation) return null
        require(checkedAtMillis > 0)
        val notice = decision.notice
        val duplicate = notice != null && notice.identity == before.lastNoticeIdentity
        val emitted = notice != null && !duplicate && runCatching { publish(notice) }.getOrDefault(false)
        val outcome = when {
            duplicate -> MonitorOutcome.ALREADY_NOTIFIED
            notice != null && !emitted -> MonitorOutcome.NOTIFICATIONS_DISABLED
            else -> decision.outcome
        }
        val entry = MonitorHistoryEntry(checkedAtMillis, outcome, MonitorState.safeBuild(decision.currentBuild),
            MonitorState.safeBuild(decision.targetBuild), emitted)
        return before.copy(lastCheckedAtMillis = checkedAtMillis, lastOutcome = outcome,
            lastCurrentBuild = entry.currentBuild, lastTargetBuild = entry.targetBuild,
            lastNoticeIdentity = if (emitted) notice!!.identity else before.lastNoticeIdentity,
            lastNoticeAtMillis = if (emitted) checkedAtMillis else before.lastNoticeAtMillis,
            history = (before.history + entry).takeLast(MonitorState.MAX_HISTORY))
    }
}

/** Local metadata is a lead. This policy never declares firmware officially installable. */
object MonitorPolicy {
    fun evaluate(observation: MonitorObservation, previouslyNotified: String, nowMillis: Long): MonitorDecision {
        val device = observation.device
        val current = MonitorState.safeBuild(device.build)
        val target = MonitorState.safeBuild(observation.discovery.localTargetBuild)
        fun quiet(outcome: MonitorOutcome) = MonitorDecision(outcome, current, target)
        if (device.simulated || !device.exactProfileMatch || device.androidVersion != "17") return quiet(MonitorOutcome.UNSUPPORTED_DEVICE)
        if (!device.rootChecked || !device.rootGranted) return quiet(MonitorOutcome.ROOT_REQUIRED)
        if (device.capturedAtMillis <= 0 || nowMillis < device.capturedAtMillis || nowMillis - device.capturedAtMillis > 120_000) return quiet(MonitorOutcome.STALE_OBSERVATION)
        if (device.snapshot != SnapshotState.NONE || device.engine != EngineState.IDLE) return quiet(MonitorOutcome.DEVICE_BUSY)
        if (!observation.recognizedMetadata || !observation.discovery.localMetadataObserved || current.isEmpty() || target.isEmpty()) return quiet(MonitorOutcome.METADATA_UNAVAILABLE)
        val discovery = FirmwareDiscovery.assess(observation.discovery, current)
        if (target == current || discovery.status == DiscoveryStatus.ALREADY_INSTALLED) return quiet(MonitorOutcome.NO_NEW_RELEASE)
        val kind = when {
            observation.discovery.localContentKind == LocalContentKind.METADATA_ONLY &&
                observation.discovery.localDownloadUrl?.let(OfficialDownloadPolicy::accepts) == true -> MonitorNoticeKind.METADATA
            discovery.status == DiscoveryStatus.CACHED_OFFICIAL_CANDIDATE && discovery.canDownloadCandidate -> MonitorNoticeKind.PACKAGE_REFERENCE
            else -> return quiet(MonitorOutcome.METADATA_UNAVAILABLE)
        }
        val id = MessageDigest.getInstance("SHA-256")
            .digest("monitor-v1|XT2607-1|merc|reteu|$current|$target|${kind.name}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (id == previouslyNotified) return quiet(MonitorOutcome.ALREADY_NOTIFIED)
        val notice = when (kind) {
            MonitorNoticeKind.METADATA -> MonitorNotice(id, kind, target, "Nieuwe Motorola-update-informatie",
                "Informatie over build $target gevonden. Dit is alleen metadata; controleer de officiële updater. Er is geen installeerbaar pakket bevestigd.")
            MonitorNoticeKind.PACKAGE_REFERENCE -> MonitorNotice(id, kind, target, "Nieuwe Motorola-pakketverwijzing",
                "Een lokale verwijzing voor build $target gevonden. Beschikbaarheid, ondertekening en geschiktheid moeten nog worden gecontroleerd.")
            MonitorNoticeKind.SERVER_OFFER -> error("Local metadata cannot declare a server offer")
        }
        return MonitorDecision(if (kind == MonitorNoticeKind.METADATA) MonitorOutcome.NEW_METADATA else MonitorOutcome.NEW_PACKAGE_REFERENCE,
            current, target, notice)
    }

    fun describe(outcome: MonitorOutcome): String = when (outcome) {
        MonitorOutcome.NEVER -> "Nog geen achtergrondcontrole uitgevoerd."
        MonitorOutcome.DISABLED -> "Achtergrondcontrole staat uit."
        MonitorOutcome.UNSUPPORTED_DEVICE -> "Toestelprofiel of Android-versie is niet onderzocht; geen melding verzonden."
        MonitorOutcome.ROOT_REQUIRED -> "Roottoegang ontbreekt. Verleen toegang in Magisk en controleer opnieuw in de app."
        MonitorOutcome.STALE_OBSERVATION -> "De uitlezing is onvolledig of verouderd; geen update vastgesteld."
        MonitorOutcome.DEVICE_BUSY -> "Snapshot- of enginetoestand is actief of onbekend; geen update voorgesteld."
        MonitorOutcome.NO_NEW_RELEASE -> "De gevonden metadata hoort bij de huidige build; geen nieuwe update vastgesteld."
        MonitorOutcome.METADATA_UNAVAILABLE -> "Geen bruikbare nieuwe Motorola-metadata; dit bewijst niet dat er geen update is."
        MonitorOutcome.NEW_METADATA -> "Nieuwe lokale Motorola-informatie gevonden; nog geen installeerbare OTA bevestigd."
        MonitorOutcome.NEW_PACKAGE_REFERENCE -> "Nieuwe lokale pakketverwijzing gevonden; pakketverificatie blijft vereist."
        MonitorOutcome.ALREADY_NOTIFIED -> "Deze buildverwijzing was al gemeld; geen herhaalde melding verzonden."
        MonitorOutcome.NOTIFICATIONS_DISABLED -> "Nieuwe informatie gevonden, maar meldingen zijn niet toegestaan. Open de app om deze te bekijken."
        MonitorOutcome.CHECK_FAILED -> "Achtergrondcontrole kon niet worden voltooid; er is niets gewijzigd."
        MonitorOutcome.STORE_CORRUPT -> "Opgeslagen monitorinstellingen zijn onleesbaar; achtergrondcontrole is veilig uitgeschakeld."
        MonitorOutcome.NEW_SERVER_OFFER -> "CDS heeft een nieuwe build gemeld; een installeerbaar geverifieerd pakket is niet bevestigd."
    }
}
