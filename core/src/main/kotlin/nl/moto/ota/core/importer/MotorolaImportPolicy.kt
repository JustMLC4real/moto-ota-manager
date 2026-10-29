package nl.moto.ota.core.importer

import nl.moto.ota.core.FirmwareValidator
import nl.moto.ota.core.PackageTransfer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

enum class MotorolaImportError {
    INVALID_ID, SOURCE_DIRECTORY_INVALID, SOURCE_INVALID, ENTRY_LIMIT, SIZE_LIMIT,
    DESTINATION_INVALID, SOURCE_CHANGED, DESTINATION_CHANGED, STORAGE_LOW,
    HASH_MISMATCH, ATOMIC_PUBLISH_UNSUPPORTED, IO
}

class MotorolaImportFailure(val code: MotorolaImportError) : IOException(code.name)

data class MotorolaImportEntry(val id: String, val bytes: Long) {
    override fun toString() = "MotorolaImportEntry(bytes=$bytes)"
}

/** Byte-copy receipt only. Independent firmware authentication remains mandatory. */
data class MotorolaImportReceipt(val bytes: Long, val sha256: String) {
    override fun toString() = "MotorolaImportReceipt(bytes=$bytes, contentHashPresent=${sha256.isNotEmpty()})"
}

/**
 * Caller fixes the OEM source and its own private destination directory. Android
 * UID, exact OEM path/profile, process lifecycle and ownership checks belong to
 * the root adapter. This layer accepts only a bounded basename, never a path.
 */
object MotorolaImportPolicy {
    const val MAX_DISCOVERED = 12
    const val MAX_DIRECTORY_ENTRIES = 256
    private val NAME = Regex("[A-Za-z0-9_.-]+")

    fun validId(id: String): Boolean = id.length in 5..160 && id.matches(NAME) &&
        id.endsWith(".zip", ignoreCase = true) && !id.contains("..")

    fun discover(sourceDirectory: File): List<MotorolaImportEntry> = guarded {
        val source = directory(sourceDirectory, MotorolaImportError.SOURCE_DIRECTORY_INVALID)
        val entries = mutableListOf<MotorolaImportEntry>()
        Files.newDirectoryStream(source.toPath()).use { stream ->
            var count = 0
            for (path in stream) {
                if (++count > MAX_DIRECTORY_ENTRIES) fail(MotorolaImportError.ENTRY_LIMIT)
                val id = path.fileName.toString()
                if (!validId(id) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) continue
                val file = path.toFile()
                if (file.canonicalFile.parentFile != source || !noSymlinks(path)) continue
                val size = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).size()
                if (size in 1..FirmwareValidator.MAX_PACKAGE_BYTES) entries += MotorolaImportEntry(id, size)
            }
        }
        entries.sortedBy { it.id }.take(MAX_DISCOVERED)
    }

    fun copy(sourceDirectory: File, id: String, destination: File,
             availableBytes: () -> Long = { destination.absoluteFile.parentFile?.usableSpace ?: 0 }): MotorolaImportReceipt = guarded {
        if (!validId(id)) fail(MotorolaImportError.INVALID_ID)
        val sourceRoot = directory(sourceDirectory, MotorolaImportError.SOURCE_DIRECTORY_INVALID)
        val source = File(sourceRoot, id)
        if (source.canonicalFile.parentFile != sourceRoot || !noSymlinks(source.toPath()) || !Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS))
            fail(MotorolaImportError.SOURCE_INVALID)
        val before = identity(source, MotorolaImportError.SOURCE_INVALID)
        if (before.bytes !in 1..FirmwareValidator.MAX_PACKAGE_BYTES) fail(MotorolaImportError.SIZE_LIMIT)
        val target = destination.absoluteFile
        val parent = directory(requireNotNull(target.parentFile), MotorolaImportError.DESTINATION_INVALID)
        if (!validId(target.name) || target.canonicalFile != target || parent.toPath().startsWith(sourceRoot.toPath()) ||
            !noSymlinks(target.toPath())) fail(MotorolaImportError.DESTINATION_INVALID)
        val destinationBefore = optionalIdentity(target)
        if (availableBytes() < before.bytes + PackageTransfer.MIN_RESERVE_BYTES) fail(MotorolaImportError.STORAGE_LOW)
        val temporary = Files.createTempFile(parent.toPath(), ".motorola-import-", ".part").toFile()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { input -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), before.bytes - count + 1).toInt())
                    if (n < 0) break
                    if (n == 0 || count > before.bytes - n) fail(MotorolaImportError.SOURCE_CHANGED)
                    if (availableBytes() < PackageTransfer.MIN_RESERVE_BYTES + n) fail(MotorolaImportError.STORAGE_LOW)
                    output.write(buffer, 0, n); hash.update(buffer, 0, n); count += n
                }
                if (count != before.bytes) fail(MotorolaImportError.SOURCE_CHANGED)
                output.fd.sync()
            } }
            if (availableBytes() < PackageTransfer.MIN_RESERVE_BYTES) fail(MotorolaImportError.STORAGE_LOW)
            val expectedHash = hash.digest().hex()
            if (identity(source, MotorolaImportError.SOURCE_CHANGED) != before ||
                digest(source, before.bytes) != expectedHash) fail(MotorolaImportError.SOURCE_CHANGED)
            if (digest(temporary, before.bytes) != expectedHash) fail(MotorolaImportError.HASH_MISMATCH)
            if (directory(sourceDirectory, MotorolaImportError.SOURCE_CHANGED) != sourceRoot ||
                directory(parent, MotorolaImportError.DESTINATION_CHANGED) != parent ||
                identity(source, MotorolaImportError.SOURCE_CHANGED) != before) fail(MotorolaImportError.SOURCE_CHANGED)
            if (optionalIdentity(target) != destinationBefore) fail(MotorolaImportError.DESTINATION_CHANGED)
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { fail(MotorolaImportError.ATOMIC_PUBLISH_UNSUPPORTED) }
            if (digest(target, before.bytes) != expectedHash) fail(MotorolaImportError.HASH_MISMATCH)
            if (identity(source, MotorolaImportError.SOURCE_CHANGED) != before) fail(MotorolaImportError.SOURCE_CHANGED)
            MotorolaImportReceipt(before.bytes, expectedHash)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private data class Identity(val key: Any?, val bytes: Long, val modified: FileTime, val created: FileTime)

    private fun identity(file: File, error: MotorolaImportError): Identity {
        if (!noSymlinks(file.toPath())) fail(error)
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!a.isRegularFile) fail(error)
        return Identity(a.fileKey(), a.size(), a.lastModifiedTime(), a.creationTime())
    }

    private fun optionalIdentity(file: File): Identity? =
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) identity(file, MotorolaImportError.DESTINATION_INVALID) else null

    private fun directory(file: File, error: MotorolaImportError): File {
        val absolute = file.absoluteFile
        if (absolute.canonicalFile != absolute || !noSymlinks(absolute.toPath()) || !Files.isDirectory(absolute.toPath(), NOFOLLOW_LINKS)) fail(error)
        return absolute
    }

    private fun noSymlinks(path: Path): Boolean {
        var current: Path? = path.toAbsolutePath()
        while (current != null) {
            if (Files.isSymbolicLink(current)) return false
            current = current.parent
        }
        return true
    }

    private fun digest(file: File, expectedBytes: Long): String {
        val before = identity(file, MotorolaImportError.SOURCE_CHANGED)
        if (before.bytes != expectedBytes) fail(MotorolaImportError.SOURCE_CHANGED)
        val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - count + 1).toInt())
                if (n < 0) break
                if (n == 0 || count > expectedBytes - n) fail(MotorolaImportError.SOURCE_CHANGED)
                count += n; hash.update(buffer, 0, n)
            }
        }
        if (count != expectedBytes || identity(file, MotorolaImportError.SOURCE_CHANGED) != before) fail(MotorolaImportError.SOURCE_CHANGED)
        return hash.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fail(error: MotorolaImportError): Nothing = throw MotorolaImportFailure(error)
    private inline fun <T> guarded(action: () -> T): T = try { action() }
        catch (failure: MotorolaImportFailure) { throw failure }
        catch (_: Exception) { throw MotorolaImportFailure(MotorolaImportError.IO) }
}
