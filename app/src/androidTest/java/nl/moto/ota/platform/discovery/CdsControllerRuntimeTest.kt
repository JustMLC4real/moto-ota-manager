package nl.moto.ota.platform.discovery

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.*
import nl.moto.ota.core.discovery.*
import nl.moto.ota.platform.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All identities and HTTP replies are synthetic injections; no shell or network is invoked. */
@RunWith(AndroidJUnit4::class)
class CdsControllerRuntimeTest {
    private lateinit var context:Context
    private val guid="a".repeat(40)
    private val inspection=Inspection(emptyMap(),emptyMap(),emptyList(),SnapshotObservation("UNKNOWN"),"UNKNOWN",0,false)
    @Before fun setUp() {
        assumeTrue("CDS consent tests require a disposable emulator",Build.HARDWARE in setOf("ranchu","goldfish","cutf_cvm"))
        context=InstrumentationRegistry.getInstrumentation().targetContext
        CdsController(context).setConsent(false)
    }
    @After fun tearDown() { if(::context.isInitialized) CdsController(context).setConsent(false) }
    private fun identity()=CdsIdentity(DeviceState(manufacturer="motorola",model="XT0000-1",device="sample",channel="reteu",build="CURRENT",androidVersion="17",
        fingerprint="motorola/product/sample:17/CURRENT/123:user/release-keys",capturedAtMillis=System.currentTimeMillis()),
        "SYNTHETIC_SERIAL",guid,"Sample Motorola","SampleProduct","CURRENT")
    private fun offer()="""{"proceed":true,"context":"ota","contextKey":"$guid","contentTimestamp":1,"trackingId":"test","content":{"size":8192,"fingerprint":"motorola/product/sample:17/NEXT/234:user/release-keys"}}"""

    @Test fun noConsentMeansNoIdentityReadAndNoHttp()=runBlocking {
        val controller=CdsController(context,CdsTransport { _,_->fail("no HTTP before consent");CdsHttpResponse(0,"") },
            CdsIdentitySource { _,_->fail("no identity before consent");CdsIdentityRead() })
        assertEquals(CdsStatus.CONSENT_REQUIRED,controller.check(inspection).status)
        assertFalse(controller.state.value.checking)
    }
    @Test fun consentPersistsOnlyTheBooleanAndRevocationStopsFutureReads()=runBlocking {
        var reads=0
        val controller=CdsController(context,CdsTransport { _,_->CdsHttpResponse(200,"""{"proceed":false,"context":"ota","contextKey":"$guid","contentTimestamp":0}""") },
            CdsIdentitySource { _,_->reads++;CdsIdentityRead(identity()) })
        controller.setConsent(true)
        assertTrue(CdsController(context).consentGranted())
        assertEquals(CdsStatus.NO_OFFER,controller.check(inspection).status)
        val stored=context.getSharedPreferences("cds-network-consent",Context.MODE_PRIVATE).all
        assertEquals(mapOf("enabled" to true),stored)
        controller.setConsent(false)
        assertEquals(CdsStatus.CONSENT_REQUIRED,controller.check(inspection).status)
        assertEquals(1,reads)
        assertNull(controller.state.value.result?.packageReference)
    }
    @Test fun revocationDuringIdentityReadPreventsTheFirstRequest()=runBlocking {
        lateinit var controller:CdsController
        controller=CdsController(context,CdsTransport { _,_->fail("revoked check must not send");CdsHttpResponse(0,"") },
            CdsIdentitySource { _,_->controller.setConsent(false);CdsIdentityRead(identity()) })
        controller.setConsent(true)
        assertEquals(CdsStatus.CONSENT_REQUIRED,controller.check(inspection).status)
    }
    @Test fun checkAndResourceResponseBindBeforeExposingAPackage()=runBlocking {
        val urls=mutableListOf<String>()
        val controller=CdsController(context,CdsTransport { url,body->
            urls+=url
            assertFalse(body.toString(Charsets.UTF_8).contains("imei",true))
            CdsHttpResponse(200,if(url.contains("/check/")) offer() else """{"proceed":true,"trackingId":"test","contentResources":[{"tags":["WIFI"],"url":"https://dlmgredg-vz.gtm.svcmot.com/update.zip?token=SYNTHETIC_URL_SECRET","headers":{}}]}""")
        },CdsIdentitySource { _,_->CdsIdentityRead(identity()) })
        controller.setConsent(true)
        val result=controller.check(inspection,"nl")
        assertEquals(CdsStatus.OFFER,result.status)
        assertEquals(2,urls.size)
        assertTrue(urls.all { it.startsWith("https://moto-cds.appspot.com/") })
        assertEquals(LocalContentKind.OTA_ZIP,result.packageReference!!.contentKind)
        assertEquals(8192L,result.packageReference!!.expectedBytes)
        assertFalse(result.toString().contains("SYNTHETIC_URL_SECRET"))
        assertNull("Injected HTTP fixtures must never acquire live selection evidence",controller.selectionProof())
        assertNull(controller.state.value.selectionProof)
    }
    @Test fun missingIdentityServerFailureAndCancellationNeverConfirmAnOffer()=runBlocking {
        val missing=CdsController(context,CdsTransport { _,_->fail("missing identity");CdsHttpResponse(0,"") },CdsIdentitySource { _,_->CdsIdentityRead(missingFields=listOf(CdsInputField.ROOT)) })
        missing.setConsent(true)
        assertEquals(listOf(CdsInputField.ROOT),missing.check(inspection).missingFields)
        val failed=CdsController(context,CdsTransport { _,_->CdsHttpResponse(503,"SYNTHETIC_SECRET") },CdsIdentitySource { _,_->CdsIdentityRead(identity()) })
        assertEquals(CdsStatus.HTTP_ERROR,failed.check(inspection).status)
        val cancelled=CdsController(context,CdsTransport { _,_->throw CancellationException("synthetic") },CdsIdentitySource { _,_->CdsIdentityRead(identity()) })
        try { cancelled.check(inspection);fail("cancellation must propagate") } catch(_:CancellationException) { }
        assertEquals(CdsStatus.CANCELLED,cancelled.state.value.result!!.status)
        assertFalse(cancelled.state.value.checking)
    }
}
