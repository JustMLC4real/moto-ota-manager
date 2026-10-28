package nl.moto.ota.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Byte transport only: never interprets a successful download as authenticated firmware. */
object PackageTransfer {
    const val MIN_RESERVE_BYTES = 256L * 1024 * 1024

    /**
     * Stores into a private sibling temporary file and commits only after EOF, exact length and fsync.
     * On any failed read, cancellation, storage check or atomic move, the previous destination survives.
     * The caller owns/closes the input and must separately validate the resulting OTA.
     */
    fun copyAtomically(
        input: InputStream,
        destination: File,
        limit: Long,
        expectedBytes: Long? = null,
        availableBytes: () -> Long = { destination.absoluteFile.parentFile?.usableSpace ?: 0 },
        checkCancelled: () -> Unit = {}
    ): File {
        require(limit > 0) { "Ongeldige bestandsgroottelimiet." }
        require(expectedBytes == null || expectedBytes in 1..limit) { "Verwachte downloadlengte ontbreekt of is ongeldig." }
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        require(parent.isDirectory) { "Private opslagmap ontbreekt." }
        val temp = Files.createTempFile(parent.toPath(), ".ota-transfer-", ".part").toFile()
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(n > 0) { "Downloadbron levert geen voortgang." }
                    require(count <= limit - n) { "Bestand overschrijdt de veilige limiet." }
                    require(availableBytes() >= MIN_RESERVE_BYTES + n) { "Onvoldoende vrije opslag." }
                    count += n
                    if (expectedBytes != null) require(count <= expectedBytes) { "Download overschrijdt de verwachte lengte." }
                    output.write(buffer, 0, n)
                }
                require(count > 0 && (expectedBytes == null || expectedBytes == count)) { "Download is leeg of afgebroken." }
                checkCancelled()
                output.fd.sync()
            }
            checkCancelled()
            Files.move(temp.toPath(), destination.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return destination
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
    }
}
