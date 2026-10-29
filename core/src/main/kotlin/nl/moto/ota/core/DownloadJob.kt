package nl.moto.ota.core

import java.nio.charset.CodingErrorAction
import java.util.Base64

enum class DownloadStatus { QUEUED, RUNNING, COMMITTING, PAUSED, FAILED, CANCELLED, COMPLETED }

/** Public progress contains no URL, server validator, private path or credentials. */
data class DownloadJob(
    val id: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0,
    val expectedBytes: Long = 0,
    val errorCode: TransferError = TransferError.NONE,
    val packageSha256: String = "",
    val sourceBuild: String = "",
    val targetBuild: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val wifiOnly: Boolean = false
) {
    val finished: Boolean get() = status in setOf(DownloadStatus.CANCELLED, DownloadStatus.COMPLETED)
    val canResume: Boolean get() = status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)
}

/** Private persistence model. The signed URL itself is held only in encrypted storage. */
data class DownloadManifest(
    val job: DownloadJob,
    val sourceFingerprint: String,
    val sourceModel: String,
    val sourceDevice: String,
    val sourceChannel: String,
    val expectedSha256: String? = null,
    val checkpoint: TransferCheckpoint? = null,
    val destinationBaseline: String = "absent"
) {
    override fun toString() = "DownloadManifest(status=${job.status},downloadedBytes=${job.downloadedBytes},expectedBytes=${job.expectedBytes})"
}

/** Strict versioned line codec; duplicate/unknown fields and malformed bounds are rejected. */
object DownloadManifestCodec {
    private val keys = setOf("schema","id","status","downloaded","expected","error","sha256","sourceBuild","targetBuild","created","updated","wifi",
        "fingerprint","model","device","channel","expectedSha","checkpointSource","checkpointBytes","checkpointTotal","checkpointEtag","destination")
    fun encode(manifest: DownloadManifest): String {
        val j=manifest.job;val c=manifest.checkpoint
        val values=linkedMapOf("schema" to "1","id" to j.id,"status" to j.status.name,"downloaded" to j.downloadedBytes.toString(),"expected" to j.expectedBytes.toString(),
            "error" to j.errorCode.name,"sha256" to j.packageSha256,"sourceBuild" to j.sourceBuild,"targetBuild" to j.targetBuild,"created" to j.createdAtMillis.toString(),
            "updated" to j.updatedAtMillis.toString(),"wifi" to j.wifiOnly.toString(),"fingerprint" to manifest.sourceFingerprint,"model" to manifest.sourceModel,
            "device" to manifest.sourceDevice,"channel" to manifest.sourceChannel,"expectedSha" to manifest.expectedSha256.orEmpty(),
            "checkpointSource" to c?.sourceIdentity.orEmpty(),"checkpointBytes" to (c?.durableBytes?:0).toString(),"checkpointTotal" to (c?.totalBytes?:0).toString(),
            "checkpointEtag" to c?.strongEtag.orEmpty(),"destination" to manifest.destinationBaseline)
        return values.entries.joinToString("\n",postfix="\n") { "${it.key}=${Base64.getUrlEncoder().withoutPadding().encodeToString(it.value.toByteArray(Charsets.UTF_8))}" }
    }
    fun decode(text:String): DownloadManifest {
        require(text.length in 1..65_536)
        val fields=linkedMapOf<String,String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val split=line.indexOf('=');require(split>0)
            val key=line.substring(0,split);require(key in keys && !fields.containsKey(key))
            val bytes=Base64.getUrlDecoder().decode(line.substring(split+1))
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            fields[key]=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        require(fields.keys==keys && fields["schema"]=="1")
        fun s(key:String)=fields.getValue(key)
        fun l(key:String)=s(key).toLong().also { require(it>=0) }
        fun hash(value:String,blankAllowed:Boolean=false) { require((blankAllowed&&value.isEmpty())||value.matches(Regex("[a-f0-9]{64}"))) }
        require(s("id").matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        require(s("sourceBuild").matches(Regex("[A-Za-z0-9_.-]{1,100}")) && s("targetBuild").matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        require(s("sourceBuild")!=s("targetBuild"))
        require(s("model").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.+ -]{0,79}")) &&
            s("device").matches(Regex("[A-Za-z0-9_.+-]{1,128}")) && s("channel").matches(Regex("[A-Za-z0-9_.+-]{1,128}")))
        val sourceFingerprint = requireNotNull(MotorolaFingerprint.parse(s("fingerprint")))
        require(sourceFingerprint.device == s("device") && sourceFingerprint.build == s("sourceBuild"))
        require(l("expected")<=FirmwareValidator.MAX_PACKAGE_BYTES&&l("downloaded")<=FirmwareValidator.MAX_PACKAGE_BYTES)
        require(l("expected")==0L||l("downloaded")<=l("expected"))
        require(s("wifi") in setOf("true","false"))
        hash(s("sha256"),true);hash(s("expectedSha"),true)
        require(s("destination")=="absent"||s("destination").matches(Regex("[a-f0-9]{64}")))
        val checkpoint=if(s("checkpointSource").isEmpty()) {
            require(l("checkpointBytes")==0L&&l("checkpointTotal")==0L&&s("checkpointEtag").isEmpty());null
        } else {
            hash(s("checkpointSource"));require(l("checkpointTotal") in 1..FirmwareValidator.MAX_PACKAGE_BYTES&&l("checkpointBytes")<=l("checkpointTotal"))
            require(s("checkpointEtag").length<=512&&s("checkpointEtag").none { it.isISOControl() })
            TransferCheckpoint(s("checkpointSource"),l("checkpointBytes"),l("checkpointTotal"),s("checkpointEtag").ifBlank { null })
        }
        val job=DownloadJob(s("id"),DownloadStatus.valueOf(s("status")),l("downloaded"),l("expected"),TransferError.valueOf(s("error")),s("sha256"),s("sourceBuild"),s("targetBuild"),l("created"),l("updated"),s("wifi")=="true")
        if(job.status in setOf(DownloadStatus.COMPLETED,DownloadStatus.COMMITTING)) require(job.expectedBytes>0&&job.downloadedBytes==job.expectedBytes&&job.packageSha256.isNotBlank())
        return DownloadManifest(job,s("fingerprint"),s("model"),s("device"),s("channel"),s("expectedSha").ifBlank { null },checkpoint,s("destination"))
    }
}
