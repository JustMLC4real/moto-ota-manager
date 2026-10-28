package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class BackupValidatorTest {
    @Test fun validCurrentReferenceAcceptsOnlyMatchingBytes() {
        assertTrue(trustedBackup().usableAsStock)
    }

    @Test fun existingBootImgGzDoesNotProveCurrentStock() {
        val bytes = gzip(initBootFixture())
        val result = BackupValidator.inspect(bytes.inputStream(), true, 8192, safetyDevice())
        assertTrue(result.structurallyValid)
        assertFalse(result.provenanceVerified)
        assertFalse(result.usableAsStock)
        assertEquals("init_boot", result.partitionType)
    }

    @Test fun validGzipHasSameContentHashAsRaw() {
        val raw = initBootFixture()
        val result = BackupValidator.inspect(gzip(raw).inputStream(), true, 8192, safetyDevice(), stockReference(raw))
        assertTrue(result.usableAsStock)
        assertEquals(sha256(raw), result.sha256)
        assertEquals(40, result.sha1.length)
    }

    @Test fun shellOutputOf83ExtraBytesIsRejected() {
        val raw = initBootFixture() + ByteArray(83) { 'x'.code.toByte() }
        val result = BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), stockReference(raw))
        assertFalse(result.structurallyValid)
        assertTrue(result.issues.any { it.code == "IMAGE_SIZE" })
    }

    @Test fun truncatedImageIsRejected() {
        val raw = initBootFixture().copyOf(8000)
        assertFalse(BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), stockReference(raw)).usableAsStock)
    }

    @Test fun gzipWithTrailingShellOutputIsRejected() {
        val packed = gzip(initBootFixture()) + "root: permission diagnostic\n".toByteArray()
        assertFalse(BackupValidator.inspect(packed.inputStream(), true, 8192, safetyDevice()).structurallyValid)
    }

    @Test fun gzipWithDamagedCrcIsRejected() {
        val packed = gzip(initBootFixture()).apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() }
        assertFalse(BackupValidator.inspect(packed.inputStream(), true, 8192, safetyDevice()).structurallyValid)
    }

    @Test fun differentCurrentBuildRejectsOldBackup() {
        val raw = initBootFixture()
        val oldReference = stockReference(raw).copy(build = "W3WMS36.40-75-5-2")
        val result = BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), oldReference)
        assertFalse(result.usableAsStock)
        assertTrue(result.structurallyValid)
    }

    @Test fun fingerprintAliasIsNeverSilentlyTreatedAsEquivalent() {
        val raw = initBootFixture()
        val device = safetyDevice()
        val alias = stockReference(raw).copy(fingerprint = device.vendorFingerprint)
        assertFalse(BackupValidator.inspect(raw.inputStream(), false, 8192, device, alias).provenanceVerified)
    }

    @Test fun patchedContentMismatchRejectsEvenWellFormedHeader() {
        val raw = initBootFixture()
        val ref = stockReference(raw)
        raw[4500] = (raw[4500].toInt() xor 1).toByte()
        assertFalse(BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), ref).usableAsStock)
    }

    @Test fun bootImageWithKernelIsNotInitBoot() {
        val raw = initBootFixture().apply { putLe32(this, 8, 1024) }
        val result = BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), stockReference(raw))
        assertEquals("unknown", result.partitionType)
        assertFalse(result.usableAsStock)
    }

    @Test fun unknownHeaderVersionStopsSafely() {
        val raw = initBootFixture().apply { putLe32(this, 40, 5) }
        assertFalse(BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), stockReference(raw)).usableAsStock)
    }

    @Test fun referenceForWrongRegionOrPartitionIsRejected() {
        val raw = initBootFixture()
        val refs = listOf(stockReference(raw).copy(channel = "retus"), stockReference(raw).copy(partition = "boot"))
        refs.forEach { assertFalse(BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), it).usableAsStock) }
    }

    @Test fun unauthenticatedManifestCannotDeclareImageStock() {
        val raw = initBootFixture()
        assertFalse(BackupValidator.inspect(raw.inputStream(), false, 8192, safetyDevice(), stockReference(raw).copy(authenticityVerified = false)).usableAsStock)
    }

    @Test fun unknownPartitionSizeAndMissingInputAreBlocked() {
        assertFalse(BackupValidator.inspect(byteArrayOf().inputStream(), false, -1, safetyDevice()).usableAsStock)
        assertFalse(BackupValidator.inspect(byteArrayOf().inputStream(), false, 8192, safetyDevice()).usableAsStock)
    }
}
