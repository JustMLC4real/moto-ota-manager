package nl.moto.ota.platform.discovery

import nl.moto.ota.core.discovery.CdsProtocol
import nl.moto.ota.core.discovery.CdsIdentity
import nl.moto.ota.core.DeviceState
import org.junit.Assert.*
import org.junit.Test

/** Invalid endpoints are rejected before opening a connection; no network is used. */
class HttpsCdsTransportTest {
    @Test fun onlyExactHttpsProductionOriginCanReachTheConnectionLayer() {
        listOf("http://moto-cds.appspot.com/cds/upgrade/1/check/ctx/ota/key/test",
            "https://other.appspot.com/cds/upgrade/1/check/ctx/ota/key/test",
            "https://moto-cds.appspot.com.evil.invalid/cds/upgrade/1/check/ctx/ota/key/test",
            "https://user@moto-cds.appspot.com/cds/upgrade/1/check/ctx/ota/key/test",
            "https://moto-cds.appspot.com/cds/upgrade/1/check/ctx/ota/key/test?extra=1",
            "https://moto-cds.appspot.com/unsupported").forEach { url ->
            try { HttpsCdsTransport().post(url,byteArrayOf(1));fail("invalid endpoint") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun anOversizedBodyIsRejectedBeforeOpeningAConnection() {
        try { HttpsCdsTransport().post("https://moto-cds.appspot.com/cds/upgrade/1/check/ctx/ota/key/test",ByteArray(CdsProtocol.MAX_REQUEST_BYTES+1));fail("body bound") }
        catch(_:IllegalArgumentException) { }
    }
    @Test fun aParsedFixtureCannotBecomeActualTransportSelectionEvidence() {
        val now=System.currentTimeMillis()
        val guid="a".repeat(40)
        val identity=CdsIdentity(DeviceState(manufacturer="motorola",model="XT0000",device="sample",channel="reteu",build="CURRENT",
            fingerprint="motorola/product/sample:17/CURRENT/1:user/release-keys",capturedAtMillis=now),"SYNTHETIC_SERIAL",guid,"Sample","Product","CURRENT")
        val check=CdsHttpResponse(200,"""{"proceed":true,"context":"ota","contextKey":"$guid","contentTimestamp":0,"trackingId":"test","content":{"size":8192,"fingerprint":"motorola/product/sample:17/NEXT/2:user/release-keys"}}""")
        val resources=CdsHttpResponse(200,"""{"proceed":true,"trackingId":"test","contentResources":[{"tags":["WIFI"],"url":"https://dlmgredg-vz.gtm.svcmot.com/update.zip","headers":{}}]}""")
        assertNull(HttpsCdsTransport().selectionProof(identity,check,resources,now))
    }
}
