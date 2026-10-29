package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ResumableTransferTest {
    private val url = "https://dlmgredg-vz.gtm.svcmot.com/private-package.zip?token=must-not-escape"
    private val data = ByteArray(6 * 1024 * 1024 + 17) { (it % 251).toByte() }
    private fun response(bytes: ByteArray, status: Int = 200, headers: Map<String, String> = emptyMap(), failAt: Int? = null): TransferResponse = object : TransferResponse {
        override val statusCode = status
        override val headers = mapOf("Content-Length" to bytes.size.toString(), "ETag" to "\"entity-v1\"") + headers
        override val body: InputStream = if (failAt == null) ByteArrayInputStream(bytes) else object : InputStream() {
            var index=0
            override fun read(): Int { if (index>=failAt) throw IOException("private network failure"); return if (index>=bytes.size) -1 else bytes[index++].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if(index>=failAt) throw IOException("private URL and token must not escape")
                if(index>=bytes.size)return -1
                val n=minOf(len,failAt-index,bytes.size-index)
                bytes.copyInto(b,off,index,index+n);index+=n;return n
            }
        }
        override fun close() { body.close() }
    }
    private fun workspace(block:(File,File)->Unit) {
        val dir=Files.createTempDirectory("moto-resume-").toFile()
        try { block(File(dir,"job.part"),File(dir,"selected.zip")) } finally { dir.deleteRecursively() }
    }
    private fun failure(code:TransferError,block:()->Unit) {
        try { block();fail("Expected $code") } catch(e:TransferFailure) { assertEquals(code,e.code);assertEquals(code.name,e.message) }
    }
    @Test fun interruptedNetworkRetainsOnlyDurablePrefixThenResumesExactEntity()=workspace { partial,destination ->
        destination.writeText("previous verified package")
        var checkpoint:TransferCheckpoint?=null
        failure(TransferError.NETWORK) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(data,failAt=5*1024*1024)},save={checkpoint=it}) }
        assertEquals("previous verified package",destination.readText())
        assertEquals(4L*1024*1024,checkpoint!!.durableBytes)
        assertEquals(checkpoint!!.durableBytes,partial.length())
        var request:TransferRequest?=null
        val remaining=data.copyOfRange(partial.length().toInt(),data.size)
        val result=ResumableTransfer.copy(url,partial,destination,checkpoint,TransferTransport { r -> request=r;response(remaining,206,mapOf("Content-Range" to "bytes ${r.offset}-${data.lastIndex}/${data.size}")) },save={checkpoint=it})
        assertEquals(4L*1024*1024,request!!.offset);assertEquals("\"entity-v1\"",request!!.ifRange)
        assertArrayEquals(data,destination.readBytes());assertFalse(partial.exists());assertEquals(data.size.toLong(),result.bytes)
    }
    @Test fun serverReturning200AfterResumeRestartsPrivatePartialWithoutConcatenation()=workspace { partial,destination ->
        partial.writeBytes(byteArrayOf(99,99));var checkpoint:TransferCheckpoint?=null
        val previous=TransferCheckpoint(ResumableTransfer.sourceIdentity(url),2,6,"\"old\"")
        ResumableTransfer.copy(url,partial,destination,previous,TransferTransport{response(byteArrayOf(1,2,3),headers=mapOf("ETag" to "\"new\""))},save={checkpoint=it})
        assertArrayEquals(byteArrayOf(1,2,3),destination.readBytes());assertEquals(3L,checkpoint!!.totalBytes)
    }
    @Test fun changedEntityCannotBeAppendedToOldPrefix()=workspace { partial,destination ->
        partial.writeBytes(byteArrayOf(1,2));destination.writeText("old")
        val checkpoint=TransferCheckpoint(ResumableTransfer.sourceIdentity(url),2,4,"\"old\"")
        failure(TransferError.ENTITY_CHANGED) { ResumableTransfer.copy(url,partial,destination,checkpoint,TransferTransport{response(byteArrayOf(3,4),206,mapOf("ETag" to "\"new\"","Content-Range" to "bytes 2-3/4"))},save={}) }
        assertEquals("old",destination.readText());assertArrayEquals(byteArrayOf(1,2),partial.readBytes())
    }
    @Test fun overlappingOrWrongTotalRangesAreRejected()=workspace { partial,destination ->
        partial.writeBytes(byteArrayOf(1,2))
        val checkpoint=TransferCheckpoint(ResumableTransfer.sourceIdentity(url),2,4,"\"entity-v1\"")
        for(range in listOf("bytes 1-2/4","bytes 2-3/5","bytes 2-2/4","bytes 2-3/*")) {
            failure(TransferError.INVALID_RANGE) { ResumableTransfer.copy(url,partial,destination,checkpoint,TransferTransport{response(byteArrayOf(3,4),206,mapOf("Content-Range" to range))},save={}) }
        }
        assertFalse(destination.exists())
    }
    @Test fun absentWeakValidatorOrDifferentSourceRestartsFromZero()=workspace { partial,destination ->
        for(etag in listOf(null,"W/\"weak\"","invalid")) {
            partial.writeBytes(byteArrayOf(9,9));var request:TransferRequest?=null
            val checkpoint=TransferCheckpoint(ResumableTransfer.sourceIdentity(url),2,4,etag)
            ResumableTransfer.copy(url,partial,destination,checkpoint,TransferTransport { request=it;response(byteArrayOf(1,2,3)) },save={})
            assertEquals(0L,request!!.offset);assertNull(request!!.ifRange)
        }
        partial.writeBytes(byteArrayOf(9));var request:TransferRequest?=null
        ResumableTransfer.copy(url,partial,destination,TransferCheckpoint("wrong-source",1,4,"\"entity-v1\""),TransferTransport{request=it;response(byteArrayOf(7))},save={})
        assertEquals(0L,request!!.offset)
    }
    @Test fun processDeathUncheckpointedTailIsTrimmedBeforeResume()=workspace { partial,destination ->
        partial.writeBytes(byteArrayOf(1,2,99,99))
        val checkpoint=TransferCheckpoint(ResumableTransfer.sourceIdentity(url),2,4,"\"entity-v1\"")
        ResumableTransfer.copy(url,partial,destination,checkpoint,TransferTransport { assertEquals(2L,it.offset);assertEquals(2L,partial.length());response(byteArrayOf(3,4),206,mapOf("Content-Range" to "bytes 2-3/4")) },save={})
        assertArrayEquals(byteArrayOf(1,2,3,4),destination.readBytes())
    }
    @Test fun completeDurablePrefixFinishesAfterProcessDeathWithoutNetwork()=workspace { partial,destination ->
        partial.writeBytes(byteArrayOf(1,2,3))
        val checkpoint=TransferCheckpoint(ResumableTransfer.sourceIdentity(url),3,3,"\"entity-v1\"")
        ResumableTransfer.copy(url,partial,destination,checkpoint,TransferTransport{throw AssertionError("No HTTP request at EOF")},save={})
        assertArrayEquals(byteArrayOf(1,2,3),destination.readBytes())
    }
    @Test fun cancelledTransferCannotCommitOrDestroyPreviousPackage()=workspace { partial,destination ->
        destination.writeText("keep");var checkpoint:TransferCheckpoint?=null;var calls=0
        try { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(data)},save={checkpoint=it},checkCancelled={if(++calls>8)throw CancellationException()}) ;fail() } catch(_:CancellationException) {}
        assertEquals("keep",destination.readText());assertEquals(checkpoint!!.durableBytes,partial.length())
    }
    @Test fun commitGateCanRefuseAfterHashWithoutReplacingOldFile()=workspace { partial,destination ->
        destination.writeText("keep")
        try { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1,2))},save={},commit={_,_,_->throw CancellationException()});fail() }catch(_:CancellationException){}
        assertEquals("keep",destination.readText());assertTrue(partial.exists())
    }
    @Test fun insufficientSpaceAndHashMismatchNeverPublish()=workspace { partial,destination ->
        destination.writeText("keep")
        failure(TransferError.STORAGE_LOW) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1,2))},save={},availableBytes={1}) }
        failure(TransferError.HASH_MISMATCH) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1,2))},expectedSha256="0".repeat(64),save={}) }
        assertEquals("keep",destination.readText())
    }
    @Test fun redirectsMissingLengthEncodingAndDuplicateHeaderCasesFailClosed()=workspace { partial,destination ->
        failure(TransferError.REDIRECT) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1),302)},save={}) }
        failure(TransferError.LENGTH_REQUIRED) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1),headers=mapOf("Content-Length" to ""))},save={}) }
        failure(TransferError.INVALID_RANGE) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1),headers=mapOf("Content-Encoding" to "gzip"))},save={}) }
        failure(TransferError.INVALID_RANGE) { ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(byteArrayOf(1),headers=mapOf("content-length" to "1"))},save={}) }
    }
    @Test fun signedUrlAndEntityValidatorAreNotInterpolatedIntoDiagnostics() {
        assertFalse(TransferRequest(url,5,"\"private-etag\"").toString().contains("must-not-escape"))
        assertFalse(TransferCheckpoint(ResumableTransfer.sourceIdentity(url),5,10,"\"private-etag\"").toString().contains("private-etag"))
        assertFalse(TransferFailure(TransferError.NETWORK).toString().contains("https:"))
    }
    @Test fun finalDigestAndSizeAreMeasuredFromCommittedBytes()=workspace { partial,destination ->
        val bytes=byteArrayOf(1,2,3)
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val result=ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(bytes)},expectedBytes=3,expectedSha256=hash,save={})
        assertEquals(hash,result.sha256);assertEquals(3L,result.bytes)
    }
    @Test fun storageRunningOutMidstreamRetainsOnlyDurableBytes()=workspace { partial,destination ->
        destination.writeText("keep")
        var checkpoint:TransferCheckpoint?=null
        var free=FirmwareValidator.MAX_PACKAGE_BYTES
        failure(TransferError.STORAGE_LOW) {
            ResumableTransfer.copy(url,partial,destination,null,TransferTransport{response(data)},save={checkpoint=it},
                availableBytes={free},progress={if(it.downloadedBytes>0)free=0})
        }
        assertEquals(0L,checkpoint!!.durableBytes)
        assertEquals(0L,partial.length())
        assertEquals("keep",destination.readText())
    }
    @Test fun truncatedAndOverlongBodiesCannotPublishEvenWithValidHttpHeaders()=workspace { partial,destination ->
        destination.writeText("keep")
        failure(TransferError.NETWORK) {
            ResumableTransfer.copy(url,partial,destination,null,
                TransferTransport{response(byteArrayOf(1,2),headers=mapOf("Content-Length" to "3"))},save={})
        }
        failure(TransferError.INVALID_RANGE) {
            ResumableTransfer.copy(url,partial,destination,null,
                TransferTransport{response(byteArrayOf(1,2,3),headers=mapOf("Content-Length" to "2"))},save={})
        }
        assertEquals("keep",destination.readText())
    }
    @Test fun untrustedUrlAndOversizeResponsesFailBeforeReadingOrPublishing()=workspace { partial,destination ->
        failure(TransferError.URL_NOT_ALLOWED) {
            ResumableTransfer.copy("https://dlmgredg-vz.gtm.svcmot.com.evil.invalid/file",partial,destination,null,
                TransferTransport{throw AssertionError("Untrusted host contacted")},save={})
        }
        failure(TransferError.SIZE_LIMIT) {
            ResumableTransfer.copy(url,partial,destination,null,TransferTransport{
                response(byteArrayOf(1),headers=mapOf("Content-Length" to (FirmwareValidator.MAX_PACKAGE_BYTES+1).toString()))},save={})
        }
        assertFalse(destination.exists())
    }
}
