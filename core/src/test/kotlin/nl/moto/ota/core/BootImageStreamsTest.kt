package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BootImageStreamsTest {
    private fun boot(version:Int):ByteArray=ByteArray(16384).apply {
        "ANDROID!".toByteArray().copyInto(this);putLe32(this,8,512);putLe32(this,40,version.toLong())
        if(version>=3){putLe32(this,12,512);putLe32(this,20,if(version==4)1584 else 1580)}else{
            putLe32(this,16,512);putLe32(this,36,2048)
            if(version>=1)putLe32(this,1644,if(version==1)1648 else 1660)
        }
    }
    @Test fun actualHeaderSeparatesBootAndInitBootWithoutAndroidVersionGuessing(){
        assertEquals("init_boot",BootImageStreams.measure(initBootFixture().inputStream(),false).partitionType)
        for(v in 0..4)assertEquals("boot",BootImageStreams.measure(boot(v).inputStream(),false).partitionType)
    }
    @Test fun signedBootReferenceCanValidateBootWithoutChangingInitBootDefault(){
        val raw=boot(4);val d=safetyDevice().copy(model="fixture-motorola",device="fixture_other")
        val ref=stockReference(raw,d).copy(partition="boot")
        assertTrue(BackupValidator.inspect(raw.inputStream(),false,raw.size.toLong(),d,ref,"boot").usableAsStock)
        assertFalse(BackupValidator.inspect(raw.inputStream(),false,raw.size.toLong(),d,ref).usableAsStock)
    }
    @Test fun gzipAndRawYieldIdenticalCompleteDigests(){
        val raw=boot(3)
        assertEquals(BootImageStreams.measure(raw.inputStream(),false),BootImageStreams.measure(gzip(raw).inputStream(),true))
        val copied=ByteArrayOutputStream();BootImageStreams.measure(gzip(raw).inputStream(),true,raw.size.toLong(),copied)
        assertArrayEquals(raw,copied.toByteArray())
    }
    @Test fun trailingGarbageAndARepeatedFooterAreRejected(){
        val packed=gzip(initBootFixture())
        listOf(packed+byteArrayOf(1),packed+byteArrayOf(1,2,3)+packed.takeLast(8).toByteArray(),packed+gzip(byteArrayOf())).forEach { bad ->
            try{BootImageStreams.measure(bad.inputStream(),true);fail()}catch(e:BootImageReadFailure){assertEquals("IMAGE_CORRUPT",e.code)}
        }
    }
    @Test fun truncatedOrCorruptGzipAndOversizedRawFailBeforePublication(){
        val raw=initBootFixture();val packed=gzip(raw)
        listOf(packed.copyOf(packed.size-1),packed.copyOf().apply{this[size-8]=(this[size-8].toInt() xor 1).toByte()}).forEach { bad ->
            try{BootImageStreams.measure(bad.inputStream(),true);fail()}catch(_:BootImageReadFailure){}
        }
        try{BootImageStreams.measure((raw+byteArrayOf(0)).inputStream(),false,raw.size.toLong());fail()}catch(e:BootImageReadFailure){assertEquals("IMAGE_SIZE",e.code)}
    }
    @Test fun unknownHeaderAndImpossibleExtentsNeverBecomeBootPartitions(){
        assertEquals("unknown",BootImageStreams.measure(boot(9).inputStream(),false).partitionType)
        assertEquals("unknown",BootImageStreams.measure(boot(4).apply{putLe32(this,8,100_000)}.inputStream(),false).partitionType)
        assertEquals("unknown",BootImageStreams.measure(boot(1).apply{putLe32(this,36,17)}.inputStream(),false).partitionType)
    }
    @Test fun largeImageIsMeasuredWithBoundedReadRequests(){
        val header=initBootFixture();val bytes=32L*1024*1024
        var position=0L;var largest=0
        val source=object:InputStream(){
            override fun read():Int=if(position<bytes){val v=if(position<header.size)header[position.toInt()].toInt() and 255 else 0;position++;v}else -1
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                largest=maxOf(largest,length);if(position>=bytes)return -1
                val n=minOf(length.toLong(),bytes-position).toInt();for(i in 0 until n)buffer[offset+i]=if(position+i<header.size)header[(position+i).toInt()]else 0
                position+=n;return n
            }
        }
        val result=BootImageStreams.measure(source,false,bytes)
        assertEquals(bytes,result.bytes);assertEquals("init_boot",result.partitionType);assertTrue(largest<=65_536)
    }
}
