package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DownloadManifestCodecTest {
    private val source=DeviceState(manufacturer="motorola",model="XT2607-1",device="merc",channel="reteu",build="SOURCE",fingerprint="motorola/merc/merc:17/SOURCE/1:user/release-keys")
    private fun manifest()=DownloadManifest(DownloadJob("12345678-1234-1234-1234-123456789abc",DownloadStatus.PAUSED,100,200,
        sourceBuild="SOURCE",targetBuild="TARGET",createdAtMillis=1,updatedAtMillis=2),source.fingerprint,source.model,source.device,source.channel,
        checkpoint=TransferCheckpoint("a".repeat(64),100,200,"\"etag\""),destinationBaseline="absent")
    private fun encode(value:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    @Test fun interruptedDownloadRoundTripsWithoutPersistingUrl() {
        val value=manifest();val text=DownloadManifestCodec.encode(value)
        assertEquals(value,DownloadManifestCodec.decode(text))
        assertFalse(text.contains("https:"));assertFalse(text.contains("token="))
    }
    @Test fun duplicateUnknownAndMissingFieldsCannotBecomeCompletedJobs() {
        val text=DownloadManifestCodec.encode(manifest())
        for(bad in listOf(text+"status=${encode("COMPLETED")}\n",text+"unknown=\n",text.lineSequence().filterNot{it.startsWith("status=")}.joinToString("\n"))){
            try { DownloadManifestCodec.decode(bad);fail("Corrupt state accepted") }catch(_:IllegalArgumentException){}
        }
    }
    @Test fun completedStateNeedsExactLengthAndMeasuredDigest() {
        val original=manifest()
        for(job in listOf(original.job.copy(status=DownloadStatus.COMPLETED),original.job.copy(status=DownloadStatus.COMMITTING,packageSha256="a".repeat(64)))){
            try { DownloadManifestCodec.decode(DownloadManifestCodec.encode(original.copy(job=job)));fail("Unproven completion accepted") }catch(_:IllegalArgumentException){}
        }
        val complete=original.copy(job=original.job.copy(status=DownloadStatus.COMPLETED,downloadedBytes=200,packageSha256="a".repeat(64)))
        assertEquals(complete,DownloadManifestCodec.decode(DownloadManifestCodec.encode(complete)))
    }
    @Test fun otherObservedMotorolaProfilePersistsWithoutAuthorizingInstallation() {
        val value=manifest().copy(sourceModel="XT9999-2",sourceDevice="synthetic",sourceChannel="retus",
            sourceFingerprint="motorola/synthetic_g/synthetic:17/SOURCE/1:user/release-keys")
        assertEquals(value,DownloadManifestCodec.decode(DownloadManifestCodec.encode(value)))
    }
    @Test fun malformedIdentityAndTraversalAreRejected() {
        val original=manifest()
        for(bad in listOf(original.copy(sourceModel="../private"),original.copy(sourceChannel="retus\nInjected"),
            original.copy(sourceDevice="other"),original.copy(sourceFingerprint=source.fingerprint.replace("SOURCE/","OTHER/")),
            original.copy(job=original.job.copy(id="../../private")))){
            try { DownloadManifestCodec.decode(DownloadManifestCodec.encode(bad));fail("Untrusted state accepted") }catch(_:IllegalArgumentException){}
        }
    }
    @Test fun impossibleCheckpointAndProgressBoundsAreRejected() {
        val original=manifest()
        for(bad in listOf(original.copy(checkpoint=original.checkpoint!!.copy(durableBytes=201)),original.copy(job=original.job.copy(downloadedBytes=201)),original.copy(job=original.job.copy(expectedBytes=FirmwareValidator.MAX_PACKAGE_BYTES+1)))){
            try { DownloadManifestCodec.decode(DownloadManifestCodec.encode(bad));fail("Invalid progress accepted") }catch(_:IllegalArgumentException){}
        }
    }
}
