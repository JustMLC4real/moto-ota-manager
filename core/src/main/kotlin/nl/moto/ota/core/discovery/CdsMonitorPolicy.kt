package nl.moto.ota.core.discovery

import java.security.MessageDigest
import nl.moto.ota.core.*

/** Fresh server information can notify, but never grants download, write or installation authority. */
object CdsMonitorPolicy {
    fun evaluate(device: DeviceState, result: CdsCheckResult, previouslyNotified: String, nowMillis: Long): MonitorDecision {
        val current = MonitorState.safeBuild(device.build)
        val target = MonitorState.safeBuild(result.targetBuild)
        fun quiet(outcome:MonitorOutcome) = MonitorDecision(outcome,current,target)
        if(device.simulated || !device.manufacturer.equals("motorola",true) || device.identityConflicts.isNotEmpty()) return quiet(MonitorOutcome.UNSUPPORTED_DEVICE)
        if(device.capturedAtMillis<=0 || nowMillis-device.capturedAtMillis !in 0..120_000) return quiet(MonitorOutcome.STALE_OBSERVATION)
        if(result.httpStatus!=null) return quiet(MonitorOutcome.CHECK_FAILED)
        if(result.status==CdsStatus.NO_OFFER || (current.isNotBlank() && current==target)) return quiet(MonitorOutcome.NO_NEW_RELEASE)
        if(result.status !in setOf(CdsStatus.OFFER,CdsStatus.METADATA_ONLY)) return quiet(
            if(result.status in setOf(CdsStatus.HTTP_ERROR,CdsStatus.NETWORK_ERROR,CdsStatus.UNKNOWN_RESPONSE)) MonitorOutcome.CHECK_FAILED else MonitorOutcome.METADATA_UNAVAILABLE)
        if(current.isEmpty() || target.isEmpty() || device.model.isBlank() || device.device.isBlank() || device.channel.isBlank()) return quiet(MonitorOutcome.METADATA_UNAVAILABLE)
        val identity=MessageDigest.getInstance("SHA-256").digest(
            "cds-monitor-v1|${device.model}|${device.device}|${device.channel}|$current|$target".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if(identity==previouslyNotified) return quiet(MonitorOutcome.ALREADY_NOTIFIED)
        return MonitorDecision(MonitorOutcome.NEW_SERVER_OFFER,current,target,
            MonitorNotice(identity,MonitorNoticeKind.SERVER_OFFER,target,"CDS release information","Unverified package; review in app"))
    }
}
