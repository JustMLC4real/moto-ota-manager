package nl.moto.ota.platform.download

import nl.moto.ota.core.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class OfficialHttpsTransport:TransferTransport {
    override fun open(request:TransferRequest):TransferResponse {
        if(!OfficialDownloadPolicy.accepts(request.url))throw TransferFailure(TransferError.URL_NOT_ALLOWED)
        val connection=URL(request.url).openConnection() as? HttpsURLConnection?:throw TransferFailure(TransferError.URL_NOT_ALLOWED)
        try {
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.connectTimeout=20_000;connection.readTimeout=30_000
            connection.setRequestProperty("Accept-Encoding","identity")
            if(request.offset>0){connection.setRequestProperty("Range","bytes=${request.offset}-");request.ifRange?.let{connection.setRequestProperty("If-Range",it)}}
            val status=connection.responseCode
            val fields=linkedMapOf<String,String>()
            for((name,values)in connection.headerFields){
                if(name==null||name.lowercase() !in setOf("content-length","content-range","content-encoding","etag"))continue
                if(values==null||values.size!=1)throw TransferFailure(TransferError.INVALID_RANGE)
                fields[name]=values.single()
            }
            return object:TransferResponse {
                override val statusCode=status
                override val headers=fields
                override val body get()=connection.inputStream
                override fun close(){connection.disconnect()}
            }
        }catch(error:Exception){connection.disconnect();throw error}
    }
}
