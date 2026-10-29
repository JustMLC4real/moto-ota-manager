package nl.moto.ota.platform.update

import nl.moto.ota.core.update.*
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Constructed only after the fixed root adapter's authenticated, complete readback receipt. */
class MagiskInstallResult internal constructor(
    val proof:TargetMagiskProof,
    val targetStockSha1:String,
    val targetStockSha256:String,
    val targetStockBytes:Long,
    val packageSha256:String,
    val runId:String
)

internal object MagiskInactiveProtocol {
    const val APK_BYTES=10_679_283L
    const val APK_SHA256="89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7"
    private val fields=listOf("checkpoint","runId","packageSha256","sourceBootId","targetSlot","partitionType","targetStockBytes",
        "targetStockSha256","targetStockSha1","patchedImageSha256","embeddedStockSha1","toolApkSha256","capturedAtMillis")
    fun sign(receipt:JSONObject,key:ByteArray):JSONObject {
        require(key.size==32);validate(receipt)
        return receipt.put("macSha256",mac(receipt,key))
    }
    fun verify(receipt:JSONObject,key:ByteArray,run:UpdateRun,installed:Boolean=true):JSONObject {
        require(key.size==32 && receipt.getInt("schema")==1)
        require(receipt.keys().asSequence().toSet()==fields.toSet()+setOf("schema","macSha256"))
        validate(receipt)
        require(MessageDigest.isEqual(receipt.getString("macSha256").toByteArray(Charsets.US_ASCII),mac(receipt,key).toByteArray(Charsets.US_ASCII)))
        require(receipt.getString("checkpoint")==if(installed)"INSTALLED"else"PREPARED")
        require(receipt.getString("runId")==run.id && receipt.getString("packageSha256")==run.packageSha256 &&
            receipt.getString("sourceBootId")==run.sourceBootId && receipt.getString("targetSlot")==run.targetSlot &&
            receipt.getString("partitionType")==run.stockPartitionType && receipt.getString("toolApkSha256")==APK_SHA256 &&
            receipt.getString("targetStockSha1")==receipt.getString("embeddedStockSha1"))
        return receipt
    }
    fun result(receipt:JSONObject):MagiskInstallResult=MagiskInstallResult(
        TargetMagiskProof(receipt.getString("targetSlot"),receipt.getString("patchedImageSha256"),receipt.getString("targetStockSha1"),
            receipt.getString("embeddedStockSha1"),true,true,31100,receipt.getLong("capturedAtMillis")),
        receipt.getString("targetStockSha1"),receipt.getString("targetStockSha256"),receipt.getLong("targetStockBytes"),
        receipt.getString("packageSha256"),receipt.getString("runId"))
    private fun validate(j:JSONObject) {
        require(j.getInt("schema")==1 && j.getString("checkpoint") in setOf("PREPARED","INSTALLED"))
        require(j.getString("runId").matches(Regex("[a-f0-9-]{36}")) && j.getString("sourceBootId").matches(Regex("[a-f0-9-]{36}")))
        require(j.getString("targetSlot") in setOf("_a","_b") && j.getString("partitionType") in setOf("boot","init_boot"))
        for(field in listOf("packageSha256","targetStockSha256","patchedImageSha256","toolApkSha256"))require(j.getString(field).matches(Regex("[a-f0-9]{64}")))
        for(field in listOf("targetStockSha1","embeddedStockSha1"))require(j.getString(field).matches(Regex("[a-f0-9]{40}")))
        require(j.getLong("targetStockBytes") in 4096..nl.moto.ota.core.BootImageStreams.MAX_IMAGE_BYTES && j.getLong("capturedAtMillis")>0)
    }
    private fun mac(j:JSONObject,key:ByteArray):String {
        val framed=(listOf("moto-magisk-inactive-receipt-v1")+fields.map {j.getString(it)}).joinToString(""){"${it.toByteArray(Charsets.UTF_8).size}:$it"}
        return Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(key,"HmacSHA256"));doFinal(framed.toByteArray(Charsets.UTF_8))}.joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
