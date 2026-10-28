package nl.moto.ota.platform

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/** Public system certificates only; no private key or account data is read. */
object DeviceTrustAnchors {
    fun spkiSha256(file: File = File("/system/etc/security/otacerts.zip")): Set<String> = runCatching {
        require(file.length() in 1..1_048_576)
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size in 1..128)
            entries.filter { !it.isDirectory }.map { entry ->
                require(entry.size in 1..65_536)
                val cert = zip.getInputStream(entry).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.toSet()
        }
    }.getOrDefault(emptySet())
}
