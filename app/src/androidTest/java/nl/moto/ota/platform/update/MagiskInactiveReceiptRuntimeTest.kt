package nl.moto.ota.platform.update

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.moto.ota.core.update.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Receipt policy only. No root, Magisk process, block device or phone operation. */
@RunWith(AndroidJUnit4::class)
class MagiskInactiveReceiptRuntimeTest {
    private val key=ByteArray(32){it.toByte()}
    private val run=UpdateRun(id=UUID.randomUUID().toString(),sourceBootId=UUID.randomUUID().toString(),
        targetSlot="_b",packageSha256="a".repeat(64),stockPartitionType="boot")
    @Before fun emulatorOnly()=assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish","cutf_cvm"))
    private fun receipt(checkpoint:String="INSTALLED")=JSONObject().put("schema",1).put("checkpoint",checkpoint).put("runId",run.id)
        .put("packageSha256",run.packageSha256).put("sourceBootId",run.sourceBootId).put("targetSlot",run.targetSlot)
        .put("partitionType",run.stockPartitionType).put("targetStockBytes",32L*1024*1024).put("targetStockSha256","b".repeat(64))
        .put("targetStockSha1","c".repeat(40)).put("patchedImageSha256","d".repeat(64)).put("embeddedStockSha1","c".repeat(40))
        .put("toolApkSha256",MagiskInactiveProtocol.APK_SHA256).put("capturedAtMillis",System.currentTimeMillis())
    private fun mustReject(block:()->Unit)=assertNotNull("Untrusted receipt must fail",runCatching(block).exceptionOrNull())
    @Test fun actualJsonTypesRoundTripToOpaqueCurrentTargetProof() {
        val signed=MagiskInactiveProtocol.sign(receipt(),key)
        val roundTrip=MagiskInactiveProtocol.verify(JSONObject(signed.toString()),key,run)
        val result=MagiskInactiveProtocol.result(roundTrip)
        assertEquals(run.id,result.runId);assertEquals(run.packageSha256,result.packageSha256)
        assertEquals(32L*1024*1024,result.targetStockBytes);assertEquals("c".repeat(40),result.targetStockSha1)
        assertEquals(result.targetStockSha1,result.proof.expectedStockSha1);assertEquals(result.targetStockSha1,result.proof.embeddedStockSha1)
        assertEquals("_b",result.proof.targetSlot);assertEquals(31100,result.proof.magiskCode)
        assertTrue(result.proof.imageIsMagiskPatched);assertTrue(result.proof.verifiedMagiskTool)
    }
    @Test fun eachMutationRelevantFieldIsAuthenticated() {
        val original=MagiskInactiveProtocol.sign(receipt(),key).toString()
        val changes=mapOf("checkpoint" to "PREPARED","runId" to UUID.randomUUID().toString(),"packageSha256" to "f".repeat(64),
            "sourceBootId" to UUID.randomUUID().toString(),"targetSlot" to "_a","partitionType" to "init_boot","targetStockBytes" to 4096,
            "targetStockSha256" to "e".repeat(64),"targetStockSha1" to "e".repeat(40),"patchedImageSha256" to "e".repeat(64),
            "embeddedStockSha1" to "e".repeat(40),"toolApkSha256" to "e".repeat(64),"capturedAtMillis" to 1L)
        changes.forEach {(field,value)->mustReject {MagiskInactiveProtocol.verify(JSONObject(original).put(field,value),key,run)}}
        mustReject {MagiskInactiveProtocol.verify(JSONObject(original),ByteArray(32){99},run)}
    }
    @Test fun signedPreparedReceiptAndChangedRunAreNotInstalledEvidence() {
        val prepared=MagiskInactiveProtocol.sign(receipt("PREPARED"),key)
        MagiskInactiveProtocol.verify(prepared,key,run,installed=false)
        mustReject {MagiskInactiveProtocol.verify(prepared,key,run)}
        val installed=MagiskInactiveProtocol.sign(receipt(),key)
        for(changed in listOf(run.copy(id=UUID.randomUUID().toString()),run.copy(sourceBootId=UUID.randomUUID().toString()),
            run.copy(packageSha256="f".repeat(64)),run.copy(targetSlot="_a"),run.copy(stockPartitionType="init_boot")))
            mustReject {MagiskInactiveProtocol.verify(installed,key,changed)}
    }
    @Test fun mismatchedEmbeddedStockOrArbitraryToolNeverBecomesProof() {
        val embedded=MagiskInactiveProtocol.sign(receipt().put("embeddedStockSha1","e".repeat(40)),key)
        mustReject {MagiskInactiveProtocol.verify(embedded,key,run)}
        val tool=MagiskInactiveProtocol.sign(receipt().put("toolApkSha256","e".repeat(64)),key)
        mustReject {MagiskInactiveProtocol.verify(tool,key,run)}
        mustReject {MagiskInactiveProtocol.sign(receipt().put("targetStockBytes",513L*1024*1024),key)}
        mustReject {MagiskInactiveProtocol.sign(receipt().put("partitionType","vendor_boot"),key)}
    }
    @Test fun unknownAndMissingReceiptFieldsFailClosed() {
        val signed=MagiskInactiveProtocol.sign(receipt(),key).toString()
        mustReject {MagiskInactiveProtocol.verify(JSONObject(signed).put("isSafe",true),key,run)}
        val removed=JSONObject(signed);removed.remove("targetStockSha256")
        mustReject {MagiskInactiveProtocol.verify(removed,key,run)}
    }
}
