package nl.moto.ota.platform

import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.FirmwareValidator
import nl.moto.ota.core.OfficialDownloadPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independent adversarial fixtures: matching source labels must not bless an unrelated target. */
class FirmwareSecurityReviewTest {
    private val source = "motorola/merc_g_sys/merc:17/SOURCE/old:user/release-keys"
    private val device = DeviceState(manufacturer = "motorola", model = "XT2607-1", device = "merc",
        channel = "reteu", build = "SOURCE", fingerprint = source, securityPatch = "2026-10-01")

    @Test fun targetFingerprintMustBelongToSameCodename() {
        withPackage("motorola/other_g_sys/other:17/TARGET/new:user/release-keys") {
            assertFalse(FirmwareValidator.inspect(it, device).compatible)
        }
    }

    @Test fun targetFingerprintMustBelongToMotorola() {
        withPackage("unrelated/merc_g_sys/merc:17/TARGET/new:user/release-keys") {
            assertFalse(FirmwareValidator.inspect(it, device).compatible)
        }
    }

    @Test fun targetIncrementalLabelCannotContradictFingerprintBuild() {
        withPackage("motorola/merc_g_sys/merc:17/OTHER/new:user/release-keys") {
            assertFalse(FirmwareValidator.inspect(it, device).compatible)
        }
    }

    @Test fun correctLabelsAndSelfHashesNeverProveAnOfficialSignature() {
        withPackage("motorola/merc_g_sys/merc:17/TARGET/new:user/release-keys") {
            val result = FirmwareValidator.inspect(it, device)
            assertTrue(result.structureValid)
            assertTrue(result.payloadHashValid)
            assertFalse(result.signatureVerified)
            assertFalse(result.readyForOfficialReview)
        }
    }

    @Test fun endpointPolicyBlocksCredentialHostsRedirectTargetsAndLookalikes() {
        val host = "dlmgredg-vz.gtm.svcmot.com"
        assertTrue(OfficialDownloadPolicy.accepts("https://$host/official.zip?temporary=token"))
        listOf("http://$host/official.zip", "https://$host.attacker.invalid/a", "https://$host@attacker.invalid/a",
            "https://user@$host/a", "https://$host:444/a", "https://$host/a#fragment", "file:///data/a.zip",
            "https://127.0.0.1/a", "https://$host\\@attacker.invalid/a").forEach {
            assertFalse("Untrusted URL accepted", OfficialDownloadPolicy.accepts(it))
        }
    }

    private fun withPackage(targetFingerprint: String, assertion: (File) -> Unit) {
        val payload = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
            .put("CrAU".toByteArray()).putLong(2).putLong(1).putInt(0).put(0).array()
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload))
        val metadata = """
            ota-type=AB
            pre-device=merc
            pre-build=$source
            pre-build-incremental=SOURCE
            post-build=$targetFingerprint
            post-build-incremental=TARGET
            post-security-patch-level=2026-11-01
            motorola-channel=reteu
        """.trimIndent()
        val properties = "FILE_HASH=$hash\nFILE_SIZE=25\nMETADATA_SIZE=25\nMETADATA_HASH=$hash\n"
        val file = File.createTempFile("moto-review-", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                listOf("META-INF/com/android/metadata" to metadata.toByteArray(),
                    "payload_properties.txt" to properties.toByteArray(), "payload.bin" to payload).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
            assertion(file)
        } finally { file.delete() }
    }
}
