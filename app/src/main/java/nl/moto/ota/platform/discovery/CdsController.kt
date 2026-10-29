package nl.moto.ota.platform.discovery

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.moto.ota.core.discovery.*
import nl.moto.ota.platform.Inspection

data class CdsState(val consentGranted: Boolean = false, val checking: Boolean = false, val result: CdsCheckResult? = null,
                    val selectionProof: CdsSelectionProof? = null)

/** No identity read or HTTP occurs before separate explicit sharing consent. Only the boolean is stored. */
class CdsController(context: Context, private val transport: CdsTransport = HttpsCdsTransport(),
                    private val identitySource: CdsIdentitySource = CdsIdentityReader()) {
    private val preferences = context.applicationContext.getSharedPreferences("cds-network-consent", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(CdsState(consentGranted()))
    val state: StateFlow<CdsState> = mutableState
    fun consentGranted(): Boolean = !revoked.get() && preferences.getBoolean("enabled", false)
    fun selectionProof(): CdsSelectionProof? = if (consentGranted()) mutableState.value.selectionProof else null

    fun setConsent(granted: Boolean) {
        if (!granted) revoked.set(true)
        generation.incrementAndGet()
        val saved = preferences.edit().putBoolean("enabled",granted).commit()
        if (granted && saved) revoked.set(false)
        mutableState.value = CdsState(consentGranted())
        check(saved) { "CONSENT_STORAGE_FAILED" }
    }

    suspend fun check(inspection: Inspection, language: String = "en"): CdsCheckResult = withContext(Dispatchers.IO) { operations.withLock {
        if (!consentGranted()) return@withLock publish(CdsCheckResult(CdsStatus.CONSENT_REQUIRED))
        val admitted = generation.get()
        mutableState.value = CdsState(true, true)
        suspend fun stillAllowed() { currentCoroutineContext().ensureActive(); if(!consentGranted() || generation.get()!=admitted) throw ConsentRevoked() }
        try {
            stillAllowed()
            val read = identitySource.read(inspection,language)
            stillAllowed()
            val identity = read.identity ?: return@withLock publish(CdsCheckResult(CdsStatus.INPUT_MISSING, missingFields = read.missingFields))
            val missing = CdsProtocol.missing(identity,System.currentTimeMillis())
            if(missing.isNotEmpty()) return@withLock publish(CdsCheckResult(CdsStatus.INPUT_MISSING,missingFields=missing))
            if(identity.isPrc) return@withLock publish(CdsCheckResult(CdsStatus.UNSUPPORTED_REGION))
            stillAllowed()
            val response = transport.post(CdsProtocol.checkUrl(identity),CdsProtocol.checkBody(identity))
            stillAllowed()
            if(response.status!=200) return@withLock publish(CdsCheckResult(CdsStatus.HTTP_ERROR,httpStatus=response.status))
            val offer = CdsProtocol.parseCheck(response.body,identity) ?: return@withLock publish(CdsCheckResult(CdsStatus.UNKNOWN_RESPONSE))
            if(offer.result.status!=CdsStatus.OFFER) return@withLock publish(offer.result)
            stillAllowed()
            val resources = transport.post(CdsProtocol.resourcesUrl(offer),CdsProtocol.resourcesBody(identity,offer))
            stillAllowed()
            if(resources.status!=200) return@withLock publish(offer.result.copy(httpStatus=resources.status))
            val result = CdsProtocol.parseResources(resources.body,offer)
            publish(result, (transport as? HttpsCdsTransport)?.selectionProof(identity,response,resources,System.currentTimeMillis()))
        } catch(_:ConsentRevoked) { publish(CdsCheckResult(CdsStatus.CONSENT_REQUIRED)) }
          catch(cancelled:CancellationException) { mutableState.value=CdsState(consentGranted(),false,CdsCheckResult(CdsStatus.CANCELLED));throw cancelled }
          catch(_:Exception) { publish(CdsCheckResult(CdsStatus.NETWORK_ERROR)) }
    } }

    private fun publish(result:CdsCheckResult, proof:CdsSelectionProof?=null):CdsCheckResult {
        val allowed = consentGranted()
        val admitted = if (allowed) result else CdsCheckResult(CdsStatus.CONSENT_REQUIRED)
        mutableState.value=CdsState(allowed,false,admitted,proof.takeIf { allowed });return admitted
    }
    private class ConsentRevoked:RuntimeException()
    companion object {
        const val ENDPOINT = "https://moto-cds.appspot.com"
        val SHARED_DATA_FIELDS = listOf("SERIAL_NUMBER", "BUILD_CONTEXT_GUID", "MANUFACTURER", "MODEL", "PRODUCT", "DEVICE", "CARRIER", "BUILD", "FINGERPRINT", "ANDROID_VERSION", "LANGUAGE", "HARDWARE")
        private val revoked=AtomicBoolean(false)
        private val generation=AtomicLong(0)
        private val operations=Mutex()
    }
}
