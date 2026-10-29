package nl.moto.ota.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class PartitionDigest(val name: String, val bytes: Long, val sha256: String)
data class PayloadManifestInfo(val blockSize: Int, val initBootSource: PartitionDigest?, val initBootTarget: PartitionDigest,
                               val partitionNames: List<String>, val manifestBytes: Int)

/** Read-only bounded projection of AOSP DeltaArchiveManifest, never an installer.
 * Schema: system/update_engine 4591637f51644f5429f9e2fe589ab22b92f61842/update_metadata.proto.
 * Trust comes from the separately verified whole-file ZIP, not these self-declared fields.
 */
object PayloadManifest {
    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    fun inspect(file: File): PayloadManifestInfo = ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry("payload.bin")) { "PAYLOAD_MISSING" }
        zip.getInputStream(entry).use { input ->
            val header = input.readNBytes(24)
            require(header.size == 24 && header.copyOfRange(0, 4).contentEquals("CrAU".toByteArray())) { "PAYLOAD_HEADER" }
            val numbers = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            require(numbers.getLong(4) == 2L) { "PAYLOAD_VERSION" }
            val length = numbers.getLong(12)
            require(length in 1..MAX_MANIFEST_BYTES.toLong() && length <= entry.size - 24) { "MANIFEST_BOUNDS" }
            val data = input.readNBytes(length.toInt())
            require(data.size == length.toInt()) { "MANIFEST_TRUNCATED" }
            decode(data)
        }
    }

    fun decode(bytes: ByteArray): PayloadManifestInfo {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "MANIFEST_BOUNDS" }
        var blockSize = 4096
        var explicitBlockSize = false
        val names = linkedSetOf<String>()
        var source: PartitionDigest? = null
        var target: PartitionDigest? = null
        Proto(bytes).fields { field, wire, input ->
            when (field) {
                3 -> {
                    require(wire == 0 && !explicitBlockSize) { "DUPLICATE_BLOCK_SIZE" }
                    val value = input.varint()
                    require(value in setOf(4096L, 8192L, 16384L)) { "BLOCK_SIZE" }
                    blockSize = value.toInt(); explicitBlockSize = true
                }
                13 -> {
                    require(wire == 2 && names.size < 256) { "PARTITION_LIMIT" }
                    var name: String? = null
                    var old: ByteArray? = null
                    var new: ByteArray? = null
                    Proto(input.blob()).fields { pf, pw, pi ->
                        when (pf) {
                            1 -> { require(pw == 2 && name == null); name = pi.blob().toString(Charsets.UTF_8) }
                            6 -> { require(pw == 2 && old == null); old = pi.blob() }
                            7 -> { require(pw == 2 && new == null); new = pi.blob() }
                            else -> pi.skip(pw)
                        }
                    }
                    val partition = requireNotNull(name)
                    require(partition.matches(Regex("[a-z0-9_]{1,64}")) && names.add(partition)) { "PARTITION_NAME" }
                    if (partition == "init_boot") {
                        source = old?.let { digest(partition, it) }
                        target = digest(partition, requireNotNull(new) { "INIT_BOOT_TARGET_MISSING" })
                    }
                }
                else -> input.skip(wire)
            }
        }
        return PayloadManifestInfo(blockSize, source, requireNotNull(target) { "INIT_BOOT_NOT_UPDATED" }, names.toList(), bytes.size)
    }

    private fun digest(name: String, bytes: ByteArray): PartitionDigest {
        var size: Long? = null
        var hash: ByteArray? = null
        Proto(bytes).fields { field, wire, input ->
            when (field) {
                1 -> { require(wire == 0 && size == null); size = input.varint() }
                2 -> { require(wire == 2 && hash == null); hash = input.blob() }
                else -> input.skip(wire)
            }
        }
        require(size != null && size!! in 4096..(128L * 1024 * 1024) && hash?.size == 32) { "PARTITION_DIGEST" }
        return PartitionDigest(name, size!!, hash!!.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private class Proto(private val bytes: ByteArray) {
        private var position = 0
        private val remaining get() = bytes.size - position
        fun fields(read: (Int, Int, Proto) -> Unit) {
            var count = 0
            while (remaining > 0) {
                require(++count <= 1_000_000) { "PROTO_FIELD_LIMIT" }
                val tag = varint()
                require(tag in 8..0xffff_ffffL)
                val field = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
                require(field in 1..0x1fff_ffff)
                val before = position
                read(field, wire, this)
                require(position > before) { "PROTO_NO_PROGRESS" }
            }
        }
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                require(remaining > 0)
                val b = bytes[position++].toInt() and 255
                require(index != 9 || b <= 1)
                value = value or ((b and 127).toLong() shl (index * 7))
                if (b and 128 == 0) { require(value >= 0); return value }
            }
            throw IllegalArgumentException("PROTO_VARINT")
        }
        fun blob(): ByteArray {
            val length = varint()
            require(length in 0..remaining.toLong())
            return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> { val length = varint(); require(length <= remaining); advance(length.toInt()) }
                5 -> advance(4)
                else -> throw IllegalArgumentException("PROTO_WIRE_TYPE")
            }
        }
        private fun advance(count: Int) { require(count in 0..remaining); position += count }
    }
}
