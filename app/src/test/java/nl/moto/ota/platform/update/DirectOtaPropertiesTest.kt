package nl.moto.ota.platform.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real ZIP streams exercise the header boundary handed to Android UpdateEngine. No root/API mocks. */
class DirectOtaPropertiesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val required = linkedMapOf("FILE_HASH" to Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        "FILE_SIZE" to "1048576", "METADATA_HASH" to Base64.getEncoder().encodeToString(ByteArray(32) { 2 }), "METADATA_SIZE" to "4096")
    private fun lines(values: Map<String, String> = required) = values.entries.joinToString("\n") { "${it.key}=${it.value}" }

    @Test fun readsTheFourExactPayloadVerificationHeadersFromARealZip() {
        assertEquals(required, parse(lines()))
    }

    @Test fun crlfAndBlankLinesDoNotAlterTheSignedHeaderValues() {
        assertEquals(required, parse("\r\n" + lines().replace("\n", "\r\n") + "\r\n \r\n"))
    }

    @Test fun packageOptionsCannotOverrideTheConstrainedRootAdaptersInstallFlags() {
        val text = lines() + "\nPOWERWASH=1\nSWITCH_SLOT_ON_REBOOT=0\nRUN_POST_INSTALL=0\n" +
            "SOURCE_FP=motorola/fixture/fixture:16/SOURCE/inc:user/release-keys\n" +
            "TARGET_FP=motorola/fixture/fixture:17/TARGET/inc:user/release-keys\nEXTRA=value=with=equals"
        val parsed = parse(text)
        assertEquals(required, parsed)
        assertFalse(parsed.containsKey("POWERWASH"))
        assertFalse(parsed.containsKey("SWITCH_SLOT_ON_REBOOT"))
        assertFalse(parsed.containsKey("RUN_POST_INSTALL"))
    }

    @Test fun eachVerificationHeaderIsMandatory() {
        required.keys.forEach { missing -> rejected { parse(lines(required - missing)) } }
    }

    @Test fun duplicateKnownAndUnknownKeysAreRejectedEvenIfTheirValuesAgree() {
        rejected { parse(lines() + "\nFILE_SIZE=1048576") }
        rejected { parse(lines() + "\nEXTRA=first\nEXTRA=first") }
    }

    @Test fun malformedLinesAndAmbiguousHeaderNamesAreRejected() {
        listOf("NO_SEPARATOR", "=value", " file_size=1", "FILE_SIZE =1", "file_size=1", "FILE\u0000SIZE=1", "X".repeat(81) + "=value").forEach { line ->
            rejected { parse(lines() + "\n" + line) }
        }
    }

    @Test fun missingEmptyAndWhollyBlankEntriesAreRejected() {
        val missing = zip("irrelevant.txt", byteArrayOf(1))
        rejected { ZipFile(missing).use { DirectOtaController.readProperties(it) } }
        rejected { parse("") }
        rejected { parse(" \n\r\n") }
    }

    @Test fun exactEntryLimitIsAcceptedAndOneExtraByteIsRejected() {
        val prefix = lines() + "\nEXTRA="
        assertEquals(required, parse(prefix + "x".repeat(65_536 - prefix.length)))
        rejected { parse(prefix + "x".repeat(65_537 - prefix.length)) }
    }

    @Test fun actualInflationLimitIsEnforcedWhenCentralDirectoryUnderstatesTheSize() {
        val prefix = lines() + "\nEXTRA="
        val file = zip("payload_properties.txt", (prefix + "x".repeat(131_072)).toByteArray())
        changeCentralSize(file, 1)
        rejected { ZipFile(file).use { DirectOtaController.readProperties(it) } }
    }

    @Test fun understatedAndOverstatedEntryLengthsCannotBeAcceptedAsCompleteProperties() {
        val original = lines().toByteArray()
        listOf(original.size - 1, original.size + 1).forEach { declared ->
            val file = zip("payload_properties.txt", original)
            changeCentralSize(file, declared)
            rejected { ZipFile(file).use { DirectOtaController.readProperties(it) } }
        }
    }

    @Test fun malformedUtf8CannotBeSilentlyReplacedInsideAPropertyValue() {
        val bytes = (lines() + "\nEXTRA=").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        val file = zip("payload_properties.txt", bytes)
        rejected { ZipFile(file).use { DirectOtaController.readProperties(it) } }
    }

    private fun parse(text: String): Map<String, String> = ZipFile(zip("payload_properties.txt", text.toByteArray())).use {
        DirectOtaController.readProperties(it)
    }

    private fun zip(name: String, bytes: ByteArray): File = temporary.newFile("properties-${java.util.UUID.randomUUID()}.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { output -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() }
    }

    private fun changeCentralSize(file: File, size: Int) {
        val bytes = file.readBytes()
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val central = (0..bytes.size - 46).single { offset -> signature.indices.all { bytes[offset + it] == signature[it] } }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 24, size)
        file.writeBytes(bytes)
    }

    private inline fun rejected(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: java.util.zip.ZipException) { return }
        catch (_: java.nio.charset.CharacterCodingException) { return }
        fail("Malformed or ambiguous payload properties were accepted")
    }
}
