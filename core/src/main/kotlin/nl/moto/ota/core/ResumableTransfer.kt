package nl.moto.ota.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class TransferError { NONE, URL_NOT_ALLOWED, NETWORK, HTTP_STATUS, REDIRECT, LENGTH_REQUIRED, INVALID_RANGE,
    ENTITY_CHANGED, STORAGE_LOW, SIZE_LIMIT, HASH_MISMATCH, STATE_CORRUPT, SECRET_UNAVAILABLE, SOURCE_CHANGED, IO, CANCELLED, FOREGROUND_UNAVAILABLE }

class TransferFailure(val code: TransferError, val retryable: Boolean = false) : IOException(code.name)

data class TransferCheckpoint(val sourceIdentity: String, val durableBytes: Long = 0, val totalBytes: Long = 0, val strongEtag: String? = null) {
    override fun toString() = "TransferCheckpoint(durableBytes=$durableBytes,totalBytes=$totalBytes,validatorPresent=${strongEtag != null})"
}
data class TransferRequest(val url: String, val offset: Long, val ifRange: String?) {
    override fun toString() = "TransferRequest(offset=$offset,validatorPresent=${ifRange != null})"
}
interface TransferResponse : Closeable {
    val statusCode: Int
    val headers: Map<String, String>
    val body: InputStream
}
fun interface TransferTransport { fun open(request: TransferRequest): TransferResponse }
data class TransferProgress(val downloadedBytes: Long, val totalBytes: Long)
data class CompletedTransfer(val bytes: Long, val sha256: String)

/**
 * A resumable byte transfer, not a firmware authenticator. A complete download
 * must still pass independent package, device and signature checks.
 *
 * Only durable prefixes with a strong entity validator can be resumed. A failed
 * or killed transfer cannot replace the previous complete package. Callers must
 * serialize commits with pause/cancel changes (the Android store does so).
 */
object ResumableTransfer {
    private const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    private const val MAX_HEADER = 512

    fun sourceIdentity(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).hex()

    fun copy(
        url: String,
        partial: File,
        destination: File,
        previous: TransferCheckpoint?,
        transport: TransferTransport,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        save: (TransferCheckpoint) -> Unit,
        availableBytes: () -> Long = { partial.parentFile?.usableSpace ?: 0 },
        checkCancelled: () -> Unit = {},
        progress: (TransferProgress) -> Unit = {},
        commit: (File, File, CompletedTransfer) -> Unit = { source, target, _ ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    ): CompletedTransfer {
        if (!OfficialDownloadPolicy.accepts(url)) throw TransferFailure(TransferError.URL_NOT_ALLOWED)
        if (expectedBytes != null && expectedBytes !in 1..FirmwareValidator.MAX_PACKAGE_BYTES) throw TransferFailure(TransferError.SIZE_LIMIT)
        if (expectedSha256 != null && !expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) throw TransferFailure(TransferError.HASH_MISMATCH)
        require(partial.absoluteFile != destination.absoluteFile && partial.parentFile?.isDirectory == true)
        val identity = sourceIdentity(url)
        val validPrevious = previous?.takeIf {
            it.sourceIdentity == identity && it.totalBytes in 1..FirmwareValidator.MAX_PACKAGE_BYTES &&
                it.durableBytes in 0..it.totalBytes && strongEtag(it.strongEtag) != null &&
                partial.isFile && partial.length() >= it.durableBytes &&
                (expectedBytes == null || expectedBytes == it.totalBytes)
        }
        // Ignore bytes written after the last fsync/checkpoint. Missing or untrusted
        // validators restart safely; they never concatenate unrelated responses.
        var offset = validPrevious?.durableBytes ?: 0
        var etag = validPrevious?.strongEtag
        if (partial.exists() && partial.length() != offset) FileOutputStream(partial, true).channel.use { it.truncate(offset) }
        checkCancelled()

        // A durable complete prefix may have survived a process death just before
        // its final hash/commit. Do not issue a nonsensical Range at EOF.
        if (validPrevious != null && offset == validPrevious.totalBytes) {
            val complete = verifyComplete(partial, offset, expectedSha256, checkCancelled)
            checkCancelled(); commit(partial, destination, complete)
            return complete
        }

        val response = try { transport.open(TransferRequest(url, offset, if (offset > 0) etag else null)) }
        catch (failure: TransferFailure) { throw failure }
        catch (_: IOException) { throw TransferFailure(TransferError.NETWORK, true) }
        response.use { connection ->
            if (connection.headers.keys.map { it.lowercase() }.distinct().size != connection.headers.size) throw TransferFailure(TransferError.INVALID_RANGE)
            val headers = connection.headers.entries.associate { it.key.lowercase() to it.value.trim() }
            val status = connection.statusCode
            if (status in 300..399) throw TransferFailure(TransferError.REDIRECT)
            if (status !in setOf(200, 206)) throw TransferFailure(TransferError.HTTP_STATUS, status >= 500 || status == 408 || status == 429)
            if (headers["content-encoding"].let { !it.isNullOrBlank() && !it.equals("identity", true) }) throw TransferFailure(TransferError.INVALID_RANGE)
            val length = headers["content-length"]?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw TransferFailure(TransferError.LENGTH_REQUIRED)
            val actualEtag = strongEtag(headers["etag"])
            val total: Long
            if (status == 206) {
                // Partial data is accepted only for the exact durable prefix and
                // unchanged strong entity. Weak/no ETags cannot establish identity.
                if (offset <= 0 || etag == null || actualEtag != etag) throw TransferFailure(TransferError.ENTITY_CHANGED)
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(headers["content-range"].orEmpty())
                    ?: throw TransferFailure(TransferError.INVALID_RANGE)
                val start = range.groupValues[1].toLongOrNull() ?: throw TransferFailure(TransferError.INVALID_RANGE)
                val end = range.groupValues[2].toLongOrNull() ?: throw TransferFailure(TransferError.INVALID_RANGE)
                total = range.groupValues[3].toLongOrNull() ?: throw TransferFailure(TransferError.INVALID_RANGE)
                if (start != offset || end < start || end >= total || end - start + 1 != length ||
                    validPrevious?.totalBytes != total || end != total - 1) throw TransferFailure(TransferError.INVALID_RANGE)
            } else {
                // A server may ignore Range or If-Range may correctly force 200
                // for a changed entity. Replace only the private partial, not the
                // user's previously completed OTA, and restart from byte zero.
                offset = 0
                etag = actualEtag
                total = length
                if (headers.containsKey("content-range")) throw TransferFailure(TransferError.INVALID_RANGE)
            }
            if (total !in 1..FirmwareValidator.MAX_PACKAGE_BYTES || length > FirmwareValidator.MAX_PACKAGE_BYTES) throw TransferFailure(TransferError.SIZE_LIMIT)
            if (expectedBytes != null && total != expectedBytes) throw TransferFailure(TransferError.INVALID_RANGE)
            if (availableBytes() < total - offset + PackageTransfer.MIN_RESERVE_BYTES) throw TransferFailure(TransferError.STORAGE_LOW)
            var checkpoint = TransferCheckpoint(identity, offset, total, etag)
            if (offset == 0L) FileOutputStream(partial, false).use { it.fd.sync() }
            save(checkpoint)
            progress(TransferProgress(offset, total))
            var received = 0L
            var count = offset
            try {
                FileOutputStream(partial, true).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = try { connection.body.read(buffer) } catch (_: IOException) { throw TransferFailure(TransferError.NETWORK, true) }
                        if (n < 0) break
                        if (n == 0) throw TransferFailure(TransferError.NETWORK, true)
                        if (received > length - n || count > total - n) throw TransferFailure(TransferError.INVALID_RANGE)
                        if (availableBytes() < PackageTransfer.MIN_RESERVE_BYTES + n) throw TransferFailure(TransferError.STORAGE_LOW)
                        output.write(buffer, 0, n)
                        received += n; count += n
                        progress(TransferProgress(count, total))
                        if (count - checkpoint.durableBytes >= CHECKPOINT_BYTES) {
                            output.fd.sync()
                            checkpoint = checkpoint.copy(durableBytes = count)
                            save(checkpoint)
                        }
                    }
                    if (received != length || count != total) throw TransferFailure(TransferError.NETWORK, true)
                    output.fd.sync()
                    checkpoint = checkpoint.copy(durableBytes = count)
                    save(checkpoint)
                }
            } catch (failure: Throwable) {
                // Preserve only the last published durable prefix; callers may
                // resume it after user pause, I/O failure or process recreation.
                if (partial.isFile && partial.length() > checkpoint.durableBytes) {
                    FileOutputStream(partial, true).channel.use { it.truncate(checkpoint.durableBytes); it.force(true) }
                }
                throw failure
            }
            checkCancelled()
            val complete = verifyComplete(partial, total, expectedSha256, checkCancelled)
            checkCancelled()
            commit(partial, destination, complete)
            return complete
        }
    }

    private fun verifyComplete(file: File, expectedBytes: Long, expectedHash: String?, checkCancelled: () -> Unit): CompletedTransfer {
        if (file.length() != expectedBytes) throw TransferFailure(TransferError.INVALID_RANGE)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                count += n
                if (count > expectedBytes) throw TransferFailure(TransferError.INVALID_RANGE)
                digest.update(buffer, 0, n)
            }
        }
        if (count != expectedBytes) throw TransferFailure(TransferError.INVALID_RANGE)
        val hash = digest.digest().hex()
        if (expectedHash != null && !hash.equals(expectedHash, true)) throw TransferFailure(TransferError.HASH_MISMATCH)
        return CompletedTransfer(count, hash)
    }
    private fun strongEtag(value: String?): String? = value?.takeIf {
        it.length in 2..MAX_HEADER && it.first() == '"' && it.last() == '"' &&
            it.none { c -> c == '\r' || c == '\n' || c.isISOControl() } && it.substring(1, it.lastIndex).none { c -> c == '"' } && !it.startsWith("W/")
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
