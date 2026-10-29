package nl.moto.ota.platform.update

import nl.moto.ota.core.update.UpdateRun
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Root-authenticated whole-ZIP verification and immutable OTA-file publication facts. */
internal object DirectOtaPreparedProtocol {
    private val fields=listOf("runId","packageSha256","sourceBootId","targetBuild","targetFingerprint","targetSlot","partitionType",
        "stockSha256","oldBytes","oldSha256","targetBytes","targetSha256","payloadBytes","payloadSha256",
        "FILE_HASH","FILE_SIZE","METADATA_HASH","METADATA_SIZE","dev","inode","mtime","ctime","anchors","createdAtMillis")
    fun sign(body:JSONObject,key:ByteArray):JSONObject {
        require(key.size==32 && body.getInt("schema")==1 && body.keys().asSequence().toSet()==fields.toSet()+"schema")
        return body.put("macSha256",mac(body,key))
    }
    fun verify(body:JSONObject,key:ByteArray,run:UpdateRun):JSONObject {
        require(key.size==32 && body.getInt("schema")==1 && body.keys().asSequence().toSet()==fields.toSet()+setOf("schema","macSha256"))
        require(MessageDigest.isEqual(body.getString("macSha256").toByteArray(Charsets.US_ASCII),mac(body,key).toByteArray(Charsets.US_ASCII)))
        require(body.getString("runId")==run.id && body.getString("packageSha256")==run.packageSha256 && body.getString("sourceBootId")==run.sourceBootId &&
            body.getString("targetBuild")==run.targetBuild && body.getString("targetFingerprint")==run.declaredTargetFingerprint &&
            body.getString("targetSlot")==run.targetSlot && body.getString("partitionType")==run.stockPartitionType && body.getString("stockSha256")==run.stockSha256)
        require(body.getLong("payloadBytes") in 24..nl.moto.ota.core.FirmwareValidator.MAX_PACKAGE_BYTES && body.getString("FILE_SIZE").toLong()==body.getLong("payloadBytes"))
        require(body.getLong("targetBytes") in 4096..nl.moto.ota.core.BootImageStreams.MAX_IMAGE_BYTES)
        for(field in listOf("packageSha256","stockSha256","targetSha256","payloadSha256"))require(body.getString(field).matches(Regex("[a-f0-9]{64}")))
        require(body.getLong("oldBytes")>=0 && (body.getLong("oldBytes")==0L || body.getString("oldSha256")==run.stockSha256))
        require(body.getString("targetSlot") in setOf("_a","_b") && body.getString("partitionType") in setOf("boot","init_boot"))
        val payload=java.util.Base64.getDecoder().decode(body.getString("FILE_HASH"))
        require(payload.size==32 && payload.joinToString(""){"%02x".format(it.toInt() and 255)}==body.getString("payloadSha256"))
        require(java.util.Base64.getDecoder().decode(body.getString("METADATA_HASH")).size==32 &&
            body.getString("METADATA_SIZE").toLong() in 24..body.getLong("payloadBytes"))
        require(body.getLong("dev")>0 && body.getLong("inode")>0 && body.getLong("mtime")>=0 && body.getLong("ctime")>=0)
        require(body.getString("anchors").split(',').let {it.size in 1..128 && it.distinct().size==it.size && it.all {value->value.matches(Regex("[a-f0-9]{64}"))}})
        require(body.getLong("createdAtMillis")>0)
        return body
    }
    private fun mac(body:JSONObject,key:ByteArray):String {
        val canonical=(listOf("moto-direct-ota-prepared-v1")+fields.map {body.getString(it)}).joinToString(""){"${it.toByteArray(Charsets.UTF_8).size}:$it"}
        return Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(key,"HmacSHA256"));doFinal(canonical.toByteArray(Charsets.UTF_8))}
            .joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
