package nl.moto.ota.core.update

import nl.moto.ota.core.*

/** Compatibility for the direct official-payload route. A live selection is origin evidence,
 * not signed channel metadata. Only the Android platform applies and verifies the payload. */
object AutomaticUpdatePolicy {
    fun blockers(device:DeviceState,report:FirmwareReport,manifest:PayloadManifestInfo,backup:BackupValidation?,
                 liveSelectionBound:Boolean,selectedTargetFingerprint:String,nowMillis:Long=System.currentTimeMillis()):List<CheckResult> {
        val result=Readiness.evaluate(device,backup,report.packageBytes,nowMillis).blockers.toMutableList()
        fun check(ok:Boolean,code:String){if(!ok)result+=CheckResult(code,code,"automation.$code")}
        check(report.signatureVerified && report.structureValid && report.payloadHashValid && report.sha256.matches(Regex("[a-f0-9]{64}")),"AUTOMATION_PACKAGE_UNVERIFIED")
        check(report.targetBuild.isNotBlank() && report.targetBuild!=device.build,"AUTOMATION_TARGET_INVALID")
        check(liveSelectionBound && selectedTargetFingerprint==report.targetFingerprint,"MOTOROLA_LIVE_SELECTION_REQUIRED")
        val bridge=StockEvidencePolicy.sourceIdentityProven(report,device)
        val full=report.metadata["pre-build"].isNullOrEmpty() && report.metadata["pre-build-incremental"].isNullOrEmpty()
        check(bridge || (full && report.compatible),"AUTOMATION_SOURCE_UNPROVEN")
        val allowed=buildSet {
            if(liveSelectionBound)add("REGION_UNPROVEN")
            if(bridge)addAll(setOf("OEM_METADATA_REVIEW_REQUIRED","SOURCE_FINGERPRINT","SOURCE_BUILD"))
        }
        result+=report.issues.filter {it.code !in allowed}
        val type=backup?.partitionType.orEmpty()
        val target=manifest.targetImages.singleOrNull {it.name==type}
        check(target!=null && target.bytes in 4096..BootImageStreams.MAX_IMAGE_BYTES,"TARGET_BOOT_DIGEST_MISSING")
        val source=manifest.sourceImages.singleOrNull {it.name==type}
        check(source==null || (source.sha256==backup?.sha256 && source.bytes==backup.bytes),"SOURCE_BOOT_DIGEST_MISMATCH")
        check(device.androidVersion.toIntOrNull()?.let {current->
            report.targetAndroidVersion.toIntOrNull()?.let {it in current..17} }==true,"TARGET_RUNTIME_UNREVIEWED")
        return result.distinctBy {it.code}
    }
}
