package nl.moto.ota.platform.discovery

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import nl.moto.ota.core.discovery.CdsPackageReference
import nl.moto.ota.platform.*
import nl.moto.ota.platform.download.DownloadController
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** No public constructor/decoder: a user ZIP, URL, parsed JSON or fixture cannot mint this. */
class VerifiedLivePackage private constructor(val file:File,val report:FirmwareReport,val selection:CdsSelectionProof,
                                              private val observedSource:DeviceState) {
    fun boundTo(device:DeviceState):Boolean = device.manufacturer.equals(observedSource.manufacturer,true) &&
        device.model==observedSource.model && device.device==observedSource.device && device.channel==observedSource.channel &&
        device.build==observedSource.build && device.fingerprint==observedSource.fingerprint && device.bootId==observedSource.bootId &&
        device.slot==observedSource.slot && device.identityConflicts.isEmpty() && !device.simulated &&
        report.signatureVerified && report.targetFingerprint==selection.targetFingerprint && report.targetBuild==selection.targetBuild
    override fun toString()="VerifiedLivePackage(origin=MOTOROLA_LIVE_SELECTION, identifiers=redacted)"

    companion object {
        /** One real HTTPS response, complete bounded EOF, measured SHA256, immutable pin and
         * OEM whole-ZIP signature. Consent is checked throughout; no identifier/URL is persisted. */
        suspend fun download(context:Context,source:DeviceState,reference:CdsPackageReference,selection:CdsSelectionProof,
                             stillConsented:()->Boolean,progress:(Long,Long)->Unit):VerifiedLivePackage=withContext(Dispatchers.IO) {
            require(stillConsented() && selection.matches(source,reference.url,reference.targetBuild,System.currentTimeMillis())) {"MOTOROLA_LIVE_SELECTION_REQUIRED"}
            require(OfficialDownloadPolicy.accepts(reference.url) && reference.contentKind==LocalContentKind.OTA_ZIP)
            val download=DownloadController(context)
            download.withPausedSelection {
                val pending=File(context.filesDir,"one-tap-download-${java.util.UUID.randomUUID()}.pending")
                val connection=URL(reference.url).openConnection() as HttpsURLConnection
                val md=MessageDigest.getInstance("SHA-256")
                var total=0L
                try {
                    connection.instanceFollowRedirects=false;connection.useCaches=false
                    connection.connectTimeout=20_000;connection.readTimeout=30_000
                    connection.setRequestProperty("Accept-Encoding","identity")
                    require(connection.responseCode==200) {"LIVE_DOWNLOAD_HTTP_REJECTED"}
                    require(connection.contentEncoding.isNullOrBlank() || connection.contentEncoding.equals("identity",true))
                    val length=connection.contentLengthLong
                    require(length in 1..FirmwareValidator.MAX_PACKAGE_BYTES && (reference.expectedBytes==null || reference.expectedBytes==length)) {"LIVE_DOWNLOAD_SIZE_REJECTED"}
                    require(context.filesDir.usableSpace>=length*2+2L*1024*1024*1024) {"STORAGE_LOW"}
                    val fd=Os.open(pending.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,0x180)
                    val started=android.os.SystemClock.elapsedRealtime()
                    FileOutputStream(fd).use {output->connection.inputStream.use {input->
                        val b=ByteArray(65_536);var last=0L
                        while(true){
                            coroutineContext.ensureActive();require(stillConsented()) {"CONSENT_REVOKED"}
                            require(android.os.SystemClock.elapsedRealtime()-started<=5*60*60*1000L) {"LIVE_DOWNLOAD_DEADLINE"}
                            val n=input.read(b);if(n<0)break;total+=n;require(total<=length)
                            md.update(b,0,n);output.write(b,0,n)
                            if(total-last>=1024*1024){progress(total,length);last=total}
                        }
                        require(total==length);output.flush();output.fd.sync();progress(total,length)
                    }}
                    val sha=md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                    require(reference.sha256==null || reference.sha256.equals(sha,true)) {"LIVE_DOWNLOAD_HASH_MISMATCH"}
                    require(stillConsented());Os.rename(pending.path,File(context.filesDir,"selected-ota.zip").path)
                    val pinned=FirmwareVault(context).pinSelected()
                    val before=Os.lstat(pinned.path)
                    val inspected=FirmwareValidator.inspect(pinned,source)
                    require(inspected.structureValid && inspected.payloadHashValid && inspected.sha256==sha && inspected.packageBytes==length)
                    require(stillConsented());android.os.RecoverySystem.verifyPackage(pinned,null,null)
                    require(stillConsented() && nl.moto.ota.platform.update.RestoreRootProtocol.digest(pinned)==sha)
                    val after=Os.lstat(pinned.path)
                    require(before.st_dev==after.st_dev && before.st_ino==after.st_ino && before.st_size==after.st_size &&
                        before.st_mtime==after.st_mtime && before.st_ctime==after.st_ctime)
                    val report=inspected.copy(signatureVerified=true)
                    require(report.targetBuild==selection.targetBuild && report.targetFingerprint==selection.targetFingerprint) {"LIVE_SELECTION_TARGET_CHANGED"}
                    VerifiedLivePackage(pinned,report,selection,source)
                }finally{connection.disconnect();pending.delete()}
            }
        }
    }
}
