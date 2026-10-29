package nl.moto.ota.platform.download

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import nl.moto.ota.core.OfficialDownloadPolicy
import nl.moto.ota.core.TransferError
import nl.moto.ota.core.TransferFailure
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Replaceable for tests; the production implementation never writes a plain URL. */
internal interface DownloadSecretStore {
    fun put(id:String,url:String)
    fun read(id:String):String
    fun remove(id:String)
}

internal class KeystoreDownloadSecrets(private val store:DownloadStore,private val alias:String="MotoOtaManager.DownloadUrl.v1"):DownloadSecretStore {
    private fun key():SecretKey=synchronized(KEY_LOCK){
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    override fun put(id:String,url:String) {
        if(!OfficialDownloadPolicy.accepts(url)||url.toByteArray(Charsets.UTF_8).size>16_384)throw TransferFailure(TransferError.URL_NOT_ALLOWED)
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            val encrypted=cipher.doFinal(url.toByteArray(Charsets.UTF_8))
            val bytes=byteArrayOf(1,cipher.iv.size.toByte())+cipher.iv+encrypted
            val file=AtomicFile(store.secret(id));val output=file.startWrite()
            try{output.write(bytes);file.finishWrite(output)}catch(error:Exception){file.failWrite(output);throw error}
        }catch(_:Exception){throw TransferFailure(TransferError.SECRET_UNAVAILABLE)}
    }
    override fun read(id:String):String=try {
        val bytes=AtomicFile(store.secret(id)).openRead().use { it.readNBytes(33_000) }
        require(bytes.size in 30..32_999&&bytes[0]==1.toByte()&&bytes[1]==12.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(2,14))) }
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        cipher.doFinal(bytes.copyOfRange(14,bytes.size)).toString(Charsets.UTF_8).also { require(OfficialDownloadPolicy.accepts(it)) }
    }catch(_:Exception){throw TransferFailure(TransferError.SECRET_UNAVAILABLE)}
    override fun remove(id:String){AtomicFile(store.secret(id)).delete()}
    companion object {private val KEY_LOCK=Any()}
}
