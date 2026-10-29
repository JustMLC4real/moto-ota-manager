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
import java.util.Base64
import java.util.UUID

/** Real JSON/crypto boundaries; no root command, file staging or update API. */
@RunWith(AndroidJUnit4::class)
class DirectOtaPreparedRuntimeTest {
    private val key=ByteArray(32){it.toByte()}
    private val run=UpdateRun(id=UUID.randomUUID().toString(),sourceBootId=UUID.randomUUID().toString(),targetSlot="_b",
        targetBuild="NEXT.17",declaredTargetFingerprint="motorola/fixture/fixture:17/NEXT.17/next123:user/release-keys",
        packageSha256="a".repeat(64),stockSha256="b".repeat(64),stockPartitionType="init_boot")
    @Before fun emulatorOnly()=assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish","cutf_cvm"))
    private fun body()=JSONObject().put("schema",1).put("runId",run.id).put("packageSha256",run.packageSha256).put("sourceBootId",run.sourceBootId)
        .put("targetBuild",run.targetBuild).put("targetFingerprint",run.declaredTargetFingerprint).put("targetSlot",run.targetSlot)
        .put("partitionType",run.stockPartitionType).put("stockSha256",run.stockSha256).put("oldBytes",8192).put("oldSha256",run.stockSha256)
        .put("targetBytes",8192).put("targetSha256","c".repeat(64)).put("payloadBytes",16384).put("payloadSha256","00".repeat(32))
        .put("FILE_HASH",Base64.getEncoder().encodeToString(ByteArray(32))).put("FILE_SIZE","16384")
        .put("METADATA_HASH",Base64.getEncoder().encodeToString(ByteArray(32){1})).put("METADATA_SIZE","4096")
        .put("dev",1).put("inode",2).put("mtime",3).put("ctime",4).put("anchors","d".repeat(64)).put("createdAtMillis",System.currentTimeMillis())
    private fun rejected(block:()->Unit)=assertNotNull(runCatching(block).exceptionOrNull())
    @Test fun exactRootPublishedFactsRoundTripWithoutWeakeningTheTarget() {
        val original=DirectOtaPreparedProtocol.sign(body(),key)
        val verified=DirectOtaPreparedProtocol.verify(JSONObject(original.toString()),key,run)
        assertEquals(16384L,verified.getLong("payloadBytes"));assertEquals(run.targetBuild,verified.getString("targetBuild"))
        assertEquals(2L,verified.getLong("inode"));assertEquals(run.stockSha256,verified.getString("oldSha256"))
    }
    @Test fun everyPersistedFactIncludingFilesystemIdentityIsAuthenticated() {
        val signed=DirectOtaPreparedProtocol.sign(body(),key).toString()
        val original=JSONObject(signed)
        original.keys().asSequence().filter {it!="schema" && it!="macSha256"}.forEach {field->
            val changed=JSONObject(signed).put(field,"changed")
            rejected {DirectOtaPreparedProtocol.verify(changed,key,run)}
        }
        rejected {DirectOtaPreparedProtocol.verify(JSONObject(signed),ByteArray(32){99},run)}
    }
    @Test fun anOtherwiseAuthenticReceiptCannotBeUsedForAnotherRunBootPackageOrTarget() {
        val signed=DirectOtaPreparedProtocol.sign(body(),key)
        listOf(run.copy(id=UUID.randomUUID().toString()),run.copy(sourceBootId=UUID.randomUUID().toString()),
            run.copy(targetBuild="OTHER.17"),run.copy(declaredTargetFingerprint="other"),run.copy(targetSlot="_a"),
            run.copy(packageSha256="e".repeat(64)),run.copy(stockSha256="f".repeat(64)),run.copy(stockPartitionType="boot"))
            .forEach {changed->rejected {DirectOtaPreparedProtocol.verify(signed,key,changed)}}
    }
    @Test fun missingUnknownOrSignedMalformedPayloadFactsAreRejected() {
        val signed=DirectOtaPreparedProtocol.sign(body(),key).toString()
        rejected {DirectOtaPreparedProtocol.verify(JSONObject(signed).put("POWERWASH",1),key,run)}
        rejected {DirectOtaPreparedProtocol.verify(JSONObject(signed).apply {remove("inode")},key,run)}
        listOf(body().put("FILE_HASH","bad"),body().put("METADATA_SIZE","20000"),body().put("FILE_SIZE","16383"),
            body().put("oldSha256","f".repeat(64)),body().put("inode",0)).forEach {malformed->
            rejected {DirectOtaPreparedProtocol.verify(DirectOtaPreparedProtocol.sign(malformed,key),key,run)}
        }
    }
}
