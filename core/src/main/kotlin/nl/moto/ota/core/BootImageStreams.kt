package nl.moto.ota.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

data class BootImageMeasurement(val bytes:Long,val sha256:String,val sha1:String,val partitionType:String,val headerVersion:Int?)
class BootImageReadFailure(val code:String):IOException(code)

/** Streaming boot/init_boot measurement; at most a header and two 64 KiB buffers are retained. */
object BootImageStreams {
    const val MAX_IMAGE_BYTES=512L*1024*1024
    const val MAX_PACKED_BYTES=MAX_IMAGE_BYTES+16L*1024*1024

    fun measure(input:InputStream,compressed:Boolean,expectedBytes:Long?=null,output:OutputStream?=null):BootImageMeasurement {
        if(expectedBytes!=null && expectedBytes !in 4096..MAX_IMAGE_BYTES)throw BootImageReadFailure("PARTITION_SIZE_UNKNOWN")
        val max=expectedBytes?:MAX_IMAGE_BYTES
        val sha256=MessageDigest.getInstance("SHA-256");val sha1=MessageDigest.getInstance("SHA-1")
        val header=ByteArray(4096);var headerBytes=0;var total=0L
        fun emit(buffer:ByteArray,count:Int){
            if(count<=0 || total>max-count)throw BootImageReadFailure("IMAGE_SIZE")
            if(headerBytes<header.size){val n=minOf(count,header.size-headerBytes);buffer.copyInto(header,headerBytes,0,n);headerBytes+=n}
            total+=count;sha256.update(buffer,0,count);sha1.update(buffer,0,count);output?.write(buffer,0,count)
        }
        if(compressed)decodeGzip(input,::emit)else{
            val buffer=ByteArray(65_536)
            while(true){val n=input.read(buffer);if(n<0)break;if(n==0)throw BootImageReadFailure("IMAGE_CORRUPT");emit(buffer,n)}
        }
        val kind=headerType(header.copyOf(headerBytes),total)
        return BootImageMeasurement(total,sha256.digest().hex(),sha1.digest().hex(),kind,if(headerBytes>43)le32(header,40).toInt()else null)
    }

    /** AOSP bootimg.h v0–v4; vendor_boot and unsupported headers never become write targets. */
    fun headerType(header:ByteArray,imageBytes:Long):String {
        if(header.size<1660 || imageBytes<4096 || header.copyOfRange(0,8).toString(Charsets.US_ASCII)!="ANDROID!")return "unknown"
        val version=le32(header,40)
        if(version in 3..4){
            val kernel=le32(header,8);val ramdisk=le32(header,12)
            if(le32(header,20)!=(if(version==4L)1584L else 1580L))return "unknown"
            val signature=if(version==4L)le32(header,1580)else 0L
            val extent=4096+aligned(kernel,4096)+aligned(ramdisk,4096)+aligned(signature,4096)
            if(extent>imageBytes)return "unknown"
            return when{version==4L && kernel==0L && ramdisk>0->"init_boot";kernel>0->"boot";else->"unknown"}
        }
        if(version !in 0..2)return "unknown"
        val kernel=le32(header,8);val ramdisk=le32(header,16);val second=le32(header,24);val page=le32(header,36)
        if(kernel<=0 || ramdisk<=0 || page !in 2048L..65_536L || page and (page-1)!=0L)return "unknown"
        if(version>0 && le32(header,1644)!=(if(version==1L)1648L else 1660L))return "unknown"
        var extent=page+aligned(kernel,page)+aligned(ramdisk,page)+aligned(second,page)
        if(version>0){
            val recoveryBytes=le32(header,1632)
            if(recoveryBytes>0){val offset=le64(header,1636);if(offset<extent || offset>imageBytes || recoveryBytes>imageBytes-offset || offset%page!=0L)return "unknown";extent=offset+aligned(recoveryBytes,page)}
        }
        if(version==2L)extent+=aligned(le32(header,1648),page)
        return if(extent<=imageBytes)"boot"else"unknown"
    }

    /** Exactly one RFC1952 stream: CRC, ISIZE, optional-header bounds and trailing data are checked. */
    private fun decodeGzip(source:InputStream,emit:(ByteArray,Int)->Unit){
        val bounded=object:FilterInputStream(source){
            var count=0L
            override fun read():Int {val n=super.read();if(n>=0 && ++count>MAX_PACKED_BYTES)throw BootImageReadFailure("IMAGE_SIZE");return n}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {val n=`in`.read(buffer,offset,length);if(n>0){count+=n;if(count>MAX_PACKED_BYTES)throw BootImageReadFailure("IMAGE_SIZE")};return n}
        }
        val input=PushbackInputStream(bounded,65_536);val headerCrc=CRC32()
        fun byte(header:Boolean=true):Int {val n=input.read();if(n<0)throw BootImageReadFailure("IMAGE_CORRUPT");if(header)headerCrc.update(n);return n}
        if(byte()!=0x1f || byte()!=0x8b || byte()!=8)throw BootImageReadFailure("IMAGE_CORRUPT")
        val flags=byte();if(flags and 0xe0!=0)throw BootImageReadFailure("IMAGE_CORRUPT")
        repeat(6){byte()}
        if(flags and 4!=0){val length=byte() or (byte() shl 8);if(length>4096)throw BootImageReadFailure("IMAGE_CORRUPT");repeat(length){byte()}}
        for(flag in listOf(8,16)){if(flags and flag!=0){var done=false;repeat(4096){if(!done && byte()==0)done=true};if(!done)throw BootImageReadFailure("IMAGE_CORRUPT")}}
        if(flags and 2!=0){val expected=byte(false) or (byte(false) shl 8);if(expected!=(headerCrc.value.toInt() and 0xffff))throw BootImageReadFailure("IMAGE_CORRUPT")}
        val inflater=Inflater(true);val packed=ByteArray(65_536);val raw=ByteArray(65_536);val crc=CRC32();var bytes=0L;var lastRead=0
        try{
            while(!inflater.finished()){
                if(inflater.needsInput()){lastRead=input.read(packed);if(lastRead<=0)throw BootImageReadFailure("IMAGE_CORRUPT");inflater.setInput(packed,0,lastRead)}
                val n=inflater.inflate(raw)
                if(n>0){emit(raw,n);crc.update(raw,0,n);bytes+=n}
                else if(!inflater.finished() && (inflater.needsDictionary() || !inflater.needsInput()))throw BootImageReadFailure("IMAGE_CORRUPT")
            }
            val remaining=inflater.remaining;if(remaining>0)input.unread(packed,lastRead-remaining,remaining)
            var expectedCrc=0L;var expectedSize=0L
            for(i in 0..3)expectedCrc=expectedCrc or (byte(false).toLong() shl (8*i))
            for(i in 0..3)expectedSize=expectedSize or (byte(false).toLong() shl (8*i))
            if(expectedCrc!=crc.value || expectedSize!=(bytes and 0xffff_ffffL) || input.read()!=-1)throw BootImageReadFailure("IMAGE_CORRUPT")
        }finally{inflater.end()}
    }
    private fun aligned(value:Long,page:Long)=((value+page-1)/page)*page
    private fun le32(bytes:ByteArray,offset:Int)=(0..3).fold(0L){result,i->result or ((bytes[offset+i].toLong() and 255) shl (i*8))}
    private fun le64(bytes:ByteArray,offset:Int)=(0..7).fold(0L){result,i->result or ((bytes[offset+i].toLong() and 255) shl (i*8))}
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
}
