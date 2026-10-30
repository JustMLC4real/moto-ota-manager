package nl.moto.ota.platform.discovery

import nl.moto.ota.core.discovery.CdsProtocol
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.WeakHashMap
import nl.moto.ota.core.LocalContentKind
import nl.moto.ota.core.discovery.*
import nl.moto.ota.BuildConfig

class CdsHttpResponse(val status: Int, val body: String) {
    override fun toString(): String = "CdsHttpResponse(status=$status, body=redacted)"
}
fun interface CdsTransport { fun post(url: String, body: ByteArray): CdsHttpResponse }

/** System TLS trust, one exact production origin, no redirects, cookies, retries or OEM credentials. */
class HttpsCdsTransport : CdsTransport {
    private data class Exchange(val urlSha256: String, val requestSha256: String, val responseSha256: String)
    // Only this final transport can register an actual response object. A parsed
    // fixture or injected transport response never acquires origin evidence.
    private val actualExchanges = WeakHashMap<CdsHttpResponse, Exchange>()
    override fun post(url: String, body: ByteArray): CdsHttpResponse {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == CdsProtocol.HOST && uri.port in setOf(-1,443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.startsWith("/cds/upgrade/1/check/ctx/ota/key/") || uri.rawPath.startsWith("/cds/upgrade/1/resources/t/"))
        require(body.size in 1..CdsProtocol.MAX_REQUEST_BYTES)
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Moto-OTA-Manager/${BuildConfig.VERSION_NAME}")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != 200) return CdsHttpResponse(code, "")
            val length = connection.contentLengthLong
            require(length <= CdsProtocol.MAX_RESPONSE_BYTES)
            val encoding = connection.contentEncoding
            require(encoding.isNullOrBlank() || encoding.equals("identity", true))
            val started = System.nanoTime()
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    require((System.nanoTime() - started) / 1_000_000 <= 20_000)
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(read > 0 && output.size() <= CdsProtocol.MAX_RESPONSE_BYTES - read)
                    output.write(buffer, 0, read)
                }
            }
            if (length >= 0) require(length == output.size().toLong())
            val bytes = output.toByteArray()
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val response = CdsHttpResponse(code, text)
            synchronized(actualExchanges) {
                actualExchanges[response] = Exchange(CdsSelectionProof.sha256(url.toByteArray(Charsets.UTF_8)),
                    CdsSelectionProof.sha256(body), CdsSelectionProof.sha256(bytes))
            }
            return response
        } finally { connection.disconnect() }
    }

    /** Reparse the actual origin-attested pair; callers cannot replace it with user JSON. */
    internal fun selectionProof(identity: CdsIdentity, check: CdsHttpResponse, resources: CdsHttpResponse,
                                nowMillis: Long): CdsSelectionProof? = runCatching {
        require(CdsProtocol.missing(identity, nowMillis).isEmpty() && !identity.isPrc)
        val (first, second) = synchronized(actualExchanges) {
            requireNotNull(actualExchanges[check]) to requireNotNull(actualExchanges[resources])
        }
        val offer = requireNotNull(CdsProtocol.parseCheck(check.body, identity))
        val result = CdsProtocol.parseResources(resources.body, offer)
        val reference = requireNotNull(result.packageReference)
        require(result.status == CdsStatus.OFFER && reference.contentKind == LocalContentKind.OTA_ZIP && result.targetFingerprint.isNotBlank())
        require(first.urlSha256 == CdsSelectionProof.sha256(CdsProtocol.checkUrl(identity).toByteArray(Charsets.UTF_8)))
        require(first.requestSha256 == CdsSelectionProof.sha256(CdsProtocol.checkBody(identity)))
        require(second.urlSha256 == CdsSelectionProof.sha256(CdsProtocol.resourcesUrl(offer).toByteArray(Charsets.UTF_8)))
        require(second.requestSha256 == CdsSelectionProof.sha256(CdsProtocol.resourcesBody(identity, offer)))
        require(first.responseSha256 == CdsSelectionProof.sha256(check.body.toByteArray(Charsets.UTF_8)))
        require(second.responseSha256 == CdsSelectionProof.sha256(resources.body.toByteArray(Charsets.UTF_8)))
        CdsSelectionProof.fromVerifiedExchange(identity, result, reference,
            CdsSelectionProof.sha256((first.requestSha256 + "|" + second.requestSha256).toByteArray(Charsets.UTF_8)),
            first.responseSha256, second.responseSha256, nowMillis)
    }.getOrNull()
}
