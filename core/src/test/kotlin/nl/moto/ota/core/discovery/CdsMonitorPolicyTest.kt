package nl.moto.ota.core.discovery

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

class CdsMonitorPolicyTest {
    private val now=1_000_000L
    private val device=DeviceState(manufacturer="motorola",model="XT0000-1",device="sample",channel="reteu",build="CURRENT",capturedAtMillis=now)
    private val offer=CdsCheckResult(CdsStatus.OFFER,"NEXT")
    @Test fun freshServerInformationIsInformationalAndDeduplicatedWithoutUrl() {
        val result=CdsMonitorPolicy.evaluate(device,offer,"",now)
        assertEquals(MonitorOutcome.NEW_SERVER_OFFER,result.outcome)
        assertEquals(MonitorNoticeKind.SERVER_OFFER,result.notice!!.kind)
        assertEquals(MonitorOutcome.ALREADY_NOTIFIED,CdsMonitorPolicy.evaluate(device,offer,result.notice!!.identity,now).outcome)
        assertEquals(result.notice!!.identity,CdsMonitorPolicy.evaluate(device,offer.copy(packageReference=CdsPackageReference("https://example.invalid/token", "NEXT",LocalContentKind.OTA_ZIP)),"",now).notice!!.identity)
    }
    @Test fun noOfferUnchangedAndMissingTargetNeverNotify() {
        listOf(offer.copy(status=CdsStatus.NO_OFFER),offer.copy(targetBuild="CURRENT"),offer.copy(targetBuild="")).forEach {
            assertNull(CdsMonitorPolicy.evaluate(device,it,"",now).notice)
        }
    }
    @Test fun failuresUnknownInputRevocationAndHttpWarningsStayQuiet() {
        CdsStatus.entries.filter { it !in setOf(CdsStatus.OFFER,CdsStatus.METADATA_ONLY) }.forEach {
            assertNull(CdsMonitorPolicy.evaluate(device,offer.copy(status=it),"",now).notice)
        }
        assertNull(CdsMonitorPolicy.evaluate(device,offer.copy(httpStatus=503),"",now).notice)
    }
    @Test fun staleSimulatedNonMotorolaAndConflictingIdentitiesStayQuiet() {
        listOf(device.copy(capturedAtMillis=now-120001),device.copy(simulated=true),device.copy(manufacturer="other"),device.copy(identityConflicts=listOf("CONFLICT"))).forEach {
            assertNull(CdsMonitorPolicy.evaluate(it,offer,"",now).notice)
        }
    }
}
