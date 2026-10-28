package nl.moto.ota.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

internal fun safetyDevice() = DeviceState(
    manufacturer = "motorola", model = "XT2607-1", device = "merc", channel = "reteu", androidVersion = "17",
    build = "A171WMH.44-25", fingerprint = "motorola/merc_g_syseq/merc:17/A171WMH.44-25/0347f0-d290bf:user/release-keys",
    vendorFingerprint = "motorola/merc_g_sys/merc:17/A171WMH.44-25/0347f0-d290bf:user/release-keys", securityPatch = "2026-10-01",
    slot = "_b", rootGranted = true, magiskVersion = "31.1", magiskCode = 31100, virtualAb = true,
    snapshot = SnapshotState.NONE, engine = EngineState.IDLE, batteryPercent = 80, freeBytes = 50L * 1024 * 1024 * 1024,
    bootId = "fixture-boot-1", partitions = listOf("init_boot_a", "init_boot_b"), modules = listOf("fps", "module-b"),
    capturedAtMillis = System.currentTimeMillis(), bootSuccessful = true, bootloaderUnlocked = true, verifiedBootState = "orange",
    moduleInventoryKnown = true, rootChecked = true
)

internal fun initBootFixture(size: Int = 8192): ByteArray = ByteArray(size).apply {
    "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(this)
    putLe32(this, 8, 0)
    putLe32(this, 12, 512)
    putLe32(this, 20, 1584)
    putLe32(this, 40, 4)
    for (offset in 4096 until 4608) this[offset] = (offset % 251).toByte()
}

internal fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0..3) bytes[offset + i] = (value ushr (i * 8)).toByte()
}

internal fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun stockReference(bytes: ByteArray, device: DeviceState = safetyDevice()) = StockReference(
    sha256 = sha256(bytes), bytes = bytes.size.toLong(), build = device.build, fingerprint = device.fingerprint,
    device = device.device, model = device.model, channel = device.channel, authenticityVerified = true,
    evidence = "UNIT TEST FIXTURE: authenticated test source, never real firmware"
)
internal fun trustedBackup(device: DeviceState = safetyDevice()): BackupValidation {
    val bytes = initBootFixture()
    return BackupValidator.inspect(bytes.inputStream(), false, bytes.size.toLong(), device, stockReference(bytes, device))
}
