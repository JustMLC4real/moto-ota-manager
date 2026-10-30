package nl.moto.ota.core.discovery

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire fixtures only. No Motorola request or physical-device read is performed. */
class CdsProtocolTest {
    private val now=1_000_000L
    private val guid="a".repeat(40)
    private fun identity()=CdsIdentity(DeviceState(manufacturer="motorola",model="XT0000-1",device="testdevice",channel="reteu",androidVersion="17",
        build="CURRENT",fingerprint="motorola/product/testdevice:17/CURRENT/123:user/release-keys",capturedAtMillis=now),
        "SYNTHETIC_SERIAL",guid,"Test Motorola","TestProduct","CURRENT",language="nl")
    private fun response(proceed:Boolean=true,target:String="NEXT",source:String=guid,tracking:String="synthetic-track")="""{"proceed":$proceed,"context":"ota","contextKey":"$guid","contentTimestamp":42,"trackingId":"$tracking","reportingTags":"OTA","content":{"size":8192,"displayVersion":"2","fingerprint":"motorola/product/testdevice:17/$target/456:user/release-keys","otaSourceSha1":"$source"}}"""
    private fun resources(path:String="update.zip",headers:String="{}",tracking:String="synthetic-track")="""{"proceed":true,"trackingId":"$tracking","contentResources":[{"tags":["WIFI"],"url":"https://dlmgredg-vz.gtm.svcmot.com/$path?token=SYNTHETIC_URL_SECRET","headers":$headers}]}"""

    @Test fun requestUsesSourceObservedSerialOnlyIdentityAndActualStringBuildId() {
        val body=CdsProtocol.checkBody(identity()).toString(Charsets.UTF_8)
        val parsed=BoundedCdsJsonReader(body).readObject()
        assertEquals("serialNumber",parsed["idType"])
        assertEquals(mapOf("serialNumber" to "SYNTHETIC_SERIAL"),parsed["identityInfo"])
        assertEquals("CURRENT",(parsed["extraInfo"] as Map<*,*>)["buildId"])
        assertEquals("nl",(parsed["deviceInfo"] as Map<*,*>)["userLanguage"])
        listOf("imei","meid","appsecret","Authorization","sourceVersion").forEach { assertFalse(body.contains(it,true)) }
        assertEquals("https://moto-cds.appspot.com/cds/upgrade/1/check/ctx/ota/key/$guid",CdsProtocol.checkUrl(identity()))
    }
    @Test fun missingAndConflictingIdentityCannotBeSilentlyReplaced() {
        assertTrue(CdsProtocol.missing(identity(),now).isEmpty())
        assertTrue(CdsInputField.FRESH_OBSERVATION in CdsProtocol.missing(identity(),now+120001))
        assertTrue(CdsInputField.FRESH_OBSERVATION in CdsProtocol.missing(identity(),now-1))
        val d=identity().device.copy(identityConflicts=listOf("CONFLICT"),simulated=true)
        val bad=CdsIdentity(d,"unknown","not-a-guid","","","OTHER")
        assertTrue(CdsProtocol.missing(bad,now).containsAll(listOf(CdsInputField.SERIAL_NUMBER,CdsInputField.CONTEXT_KEY,CdsInputField.MODEL,CdsInputField.PRODUCT,CdsInputField.BUILD_ID,CdsInputField.IDENTITY)))
    }
    @Test fun measuredFifteenHexContextKeysAreNotMistakenForMissingSha1() {
        val observed = "aB0123456789cDe"
        val base = identity()
        val id = CdsIdentity(base.device, base.serialNumber, observed, base.model, base.product, base.buildId)
        assertTrue(CdsProtocol.missing(id, now).isEmpty())
        assertTrue(CdsProtocol.checkUrl(id).endsWith("/key/$observed"))
        assertTrue(CdsProtocol.checkBody(id).toString(Charsets.UTF_8).contains("\"otaSourceSha1\":\"$observed\""))
        val reply = response().replace(guid, observed)
        assertEquals(CdsStatus.OFFER, CdsProtocol.parseCheck(reply, id)!!.result.status)
        assertNull(CdsProtocol.parseCheck(reply.replace("\"contextKey\":\"$observed\"", "\"contextKey\":\"${observed.lowercase()}\""), id))
        assertNull(CdsProtocol.parseCheck(response(), id))
    }
    @Test fun unsupportedContextLengthsAndUrlSyntaxAreStillRejected() {
        val base = identity()
        listOf("", "a".repeat(14), "a".repeat(16), "a".repeat(39), "a".repeat(41), "x".repeat(15),
            "abc0123456789de/", "abc0123456789de?x", " abc0123456789de", "abc0123456789de\n").forEach { key ->
            val id = CdsIdentity(base.device, base.serialNumber, key, base.model, base.product, base.buildId)
            assertTrue(CdsInputField.CONTEXT_KEY in CdsProtocol.missing(id, now))
        }
    }
    @Test fun wellFormedSpoofedFingerprintsCannotSupplyAnotherDeviceOrBuild() {
        val base = identity()
        listOf("google/bluejay/bluejay:17/CURRENT/123:user/release-keys",
            "motorola/product/otherdevice:17/CURRENT/123:user/release-keys",
            "motorola/product/testdevice:17/OTHER/123:user/release-keys").forEach { source ->
            val id = CdsIdentity(base.device.copy(fingerprint = source), base.serialNumber, base.contextKey, base.model, base.product, base.buildId)
            assertTrue(CdsInputField.IDENTITY in CdsProtocol.missing(id, now))
        }
    }
    @Test fun noOfferIsAResponseForThisContextOnly() {
        assertEquals(CdsStatus.NO_OFFER,CdsProtocol.parseCheck(response(false),identity())!!.result.status)
        assertNull(CdsProtocol.parseCheck(response(false).replace(guid,"b".repeat(40)),identity()))
        assertNull(CdsProtocol.parseCheck(response(false).replace("\"ota\"","\"other\""),identity()))
    }
    @Test fun targetComesFromExplicitFingerprintNotTheResourceFilename() {
        val offer=CdsProtocol.parseCheck(response(),identity())!!
        assertEquals("NEXT",offer.result.targetBuild)
        assertEquals(2L,offer.result.targetVersion)
        val result=CdsProtocol.parseResources(resources("MISLEADING_BUILD.zip"),offer)
        assertEquals("NEXT",result.packageReference!!.targetBuild)
        assertEquals(8192L,result.packageReference!!.expectedBytes)
        assertNull(result.packageReference!!.sha256)
    }
    @Test fun sourceMismatchMissingTargetAndNonPositiveSizeFailClosed() {
        assertNull(CdsProtocol.parseCheck(response(source="b".repeat(40)),identity()))
        assertNull(CdsProtocol.parseCheck(response().replace("\"fingerprint\"","\"unknownFingerprint\""),identity()))
        assertNull(CdsProtocol.parseCheck(response().replace("8192","0"),identity()))
        assertNull(CdsProtocol.parseCheck(response().replace("8192",(CdsProtocol.MAX_PACKAGE_BYTES+1).toString()),identity()))
    }
    @Test fun currentBuildIsNeverANewOffer() {
        assertEquals(CdsStatus.NO_OFFER,CdsProtocol.parseCheck(response(target="CURRENT"),identity())!!.result.status)
    }
    @Test fun trackingCannotEscapeFixedResourcesPath() {
        assertNull(CdsProtocol.parseCheck(response(tracking="../other"),identity()))
        val offer=CdsProtocol.parseCheck(response(),identity())!!
        assertEquals("https://moto-cds.appspot.com/cds/upgrade/1/resources/t/synthetic-track/ctx/ota/key/$guid",CdsProtocol.resourcesUrl(offer))
        assertEquals(CdsStatus.UNKNOWN_RESPONSE,CdsProtocol.parseResources(resources(tracking="wrong"),offer).status)
    }
    @Test fun completeZipAndMetadataReferencesStayDistinct() {
        val offer=CdsProtocol.parseCheck(response(),identity())!!
        val metadata=CdsProtocol.parseResources(resources("payload_metadata.bin"),offer)
        assertEquals(CdsStatus.METADATA_ONLY,metadata.status)
        assertEquals(LocalContentKind.METADATA_ONLY,metadata.packageReference!!.contentKind)
        assertNull(metadata.packageReference!!.expectedBytes)
        val unknown=CdsProtocol.parseResources(resources("resource.bin"),offer)
        assertNull(unknown.packageReference)
    }
    @Test fun arbitraryHostsTagsAndHeadersCannotAuthorizeADownload() {
        val offer=CdsProtocol.parseCheck(response(),identity())!!
        assertNull(CdsProtocol.parseResources(resources().replace("dlmgredg-vz.gtm.svcmot.com","moto-cds.appspot.com"),offer).packageReference)
        assertNull(CdsProtocol.parseResources(resources().replace("WIFI","CELL"),offer).packageReference)
        assertNull(CdsProtocol.parseResources(resources(headers="""{"Authorization":"SYNTHETIC_SECRET"}"""),offer).packageReference)
    }
    @Test fun duplicateKeysTruncationHugeAndDeepResponsesFailClosed() {
        assertNull(CdsProtocol.parseCheck(response().replaceFirst("{","{\"proceed\":false,"),identity()))
        assertNull(CdsProtocol.parseCheck(response().dropLast(1),identity()))
        assertNull(CdsProtocol.parseCheck(" ".repeat(CdsProtocol.MAX_RESPONSE_BYTES+1)+response(),identity()))
        assertNull(CdsProtocol.parseCheck("{\"x\":"+"[".repeat(15)+"0"+"]".repeat(15)+"}",identity()))
    }
    @Test fun protocolObjectsNeverInterpolateSerialGuidUrlOrResponseBody() {
        val id=identity();val offer=CdsProtocol.parseCheck(response(),id)!!
        val result=CdsProtocol.parseResources(resources(),offer)
        listOf(id.toString(),offer.toString(),result.toString(),result.packageReference.toString()).forEach { text ->
            assertFalse(text.contains("SYNTHETIC_SERIAL"));assertFalse(text.contains(guid));assertFalse(text.contains("SYNTHETIC_URL_SECRET"))
        }
    }
    @Test fun resourcesRequestUsesReturnedTimestampAndSerialOnlyIdentity() {
        val offer=CdsProtocol.parseCheck(response(),identity())!!
        val body=BoundedCdsJsonReader(CdsProtocol.resourcesBody(identity(),offer).toString(Charsets.UTF_8)).readObject()
        assertEquals("42",(body["contentTimestamp"] as CdsJsonNumber).text)
        assertEquals(mapOf("serialNumber" to "SYNTHETIC_SERIAL"),body["identityInfo"])
        assertEquals("OTA",body["reportingTags"])
    }
}
