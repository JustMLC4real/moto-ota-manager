package nl.moto.ota.platform

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import java.util.Properties
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App-private file APIs on a disposable emulator only. Reports and observations
 * below are synthetic policy fixtures: no RecoverySystem verification succeeds,
 * no package is installed, and no device/root interaction occurs in these tests.
 */
@RunWith(AndroidJUnit4::class)
class VaultRuntimeTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var vault: FirmwareVault
    private val source get() = File(root, "selected-ota.zip")
    private val selection get() = File(root, "package-selection.properties")
    private val proofs get() = File(root, "signed-stock-evidence.properties")
    private val directory get() = File(root, "verified-packages")
    private val anchors = setOf("a".repeat(64))
    private val payloadFp = "motorola/merc_g_sys/merc:17/A171WMH.44-26/synthetic123:user/release-keys"
    private val actualFp = "motorola/merc_g_syseq/merc:17/A171WMH.44-26/synthetic123:user/release-keys"
    private val syntheticReport = FirmwareReport(structureValid = true, payloadHashValid = true, signatureVerified = true,
        regionVerified = true, targetBuild = "A171WMH.44-26", targetFingerprint = payloadFp, sha256 = "b".repeat(64), metadata = mapOf(
            "ota-type" to "AB", "pre-device" to "merc", "pre-model" to "XT2607-1", "pre-channel" to "reteu",
            "post-build" to payloadFp, "post-build-incremental" to "synthetic123", "post-timestamp" to "2000", "post-security-patch-level" to "2026-10-01"))
    private val syntheticManifest = PayloadManifestInfo(4096, null, PartitionDigest("init_boot", 8192, "c".repeat(64)), listOf("init_boot"), 100)
    private fun syntheticTarget() = DeviceState(manufacturer = "motorola", model = "XT2607-1", device = "merc", channel = "reteu",
        androidVersion = "17", build = syntheticReport.targetBuild, fingerprint = actualFp, incrementalBuild = "synthetic123", slot = "_b",
        rootChecked = true, rootGranted = true, snapshot = SnapshotState.NONE, engine = EngineState.IDLE,
        bootSuccessful = true, moduleInventoryKnown = true, bootloaderUnlocked = true, verifiedBootState = "orange",
        capturedAtMillis = System.currentTimeMillis(), bootId = "synthetic-private-boot-id", modules = listOf("synthetic-private-module"),
        vendorFingerprint = payloadFp, buildTimestamp = 2000, securityPatch = "2026-10-01", virtualAb = true)

    /** Storage fixture only: never invokes or bypasses the production signature-verification API. */
    private fun writeSyntheticStoredFacts() {
        val e = requireNotNull(StockEvidencePolicy.create(syntheticReport, syntheticManifest, anchors, System.currentTimeMillis()))
        Properties().apply {
            setProperty("schema", "2"); setProperty("count", "1")
            mapOf("package" to e.packageSha256, "build" to e.targetBuild, "android" to e.targetAndroid, "incremental" to e.targetIncremental,
                "targetFp" to e.payloadTargetFingerprint, "image" to e.imageSha256, "bytes" to e.imageBytes.toString(),
                "anchors" to e.trustedSpki.joinToString(","), "verifiedAt" to e.verifiedAtMillis.toString(), "measuredFp" to "",
                "partition" to e.partition, "kind" to e.kind.name, "device" to e.device, "model" to e.model, "channel" to e.channel,
                "androidFps" to e.androidFingerprints.joinToString("|"), "timestamp" to e.identityTimestamp.toString(),
                "patch" to e.identitySecurityPatch, "vendorFp" to "", "scope" to e.proofScope)
                .forEach { (key, value) -> setProperty("0.$key", value) }
            proofs.outputStream().use { store(it, "Synthetic emulator storage fixture; not real signature evidence") }
        }
    }

    @Before fun setUp() {
        assumeTrue("Vault runtime tests require a disposable Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(application.cacheDir.toPath(), "vault-runtime-").toFile()
        // Isolated storage prevents the tests from replacing the actual app's
        // selected firmware, stock evidence, workflow journal or monitor state.
        context = object : ContextWrapper(application) { override fun getFilesDir() = root }
        vault = FirmwareVault(context)
    }

    @After fun tearDown() {
        if (::root.isInitialized) {
            // Files.walk does not follow symlinks. Every deletion stays under
            // this test's generated private cache directory.
            Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun bytes(seed: Int) = ByteArray(4096) { ((it + seed) % 251).toByte() }
    private fun replaceSource(data: ByteArray) {
        PackageTransfer.copyAtomically(data.inputStream(), source, FirmwareValidator.MAX_PACKAGE_BYTES, data.size.toLong())
    }
    private fun mustReject(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Unsafe or corrupt input must be rejected", rejected)
    }

    @Test fun pinnedInodeSurvivesAtomicDownloadReplacementAndSelectionReload() {
        val first = bytes(1)
        val replacement = bytes(2)
        replaceSource(first)
        val pinned = vault.pinSelected()
        // Java's hard-link operation is optional. Probe independently in the
        // same directory, then verify native inode identity rather than assuming
        // that a particular emulator/provider supports the optimized path.
        val probe = File(directory, "hardlink-capability-probe")
        val capability = runCatching { Files.createLink(probe.toPath(), source.toPath()) }
        val sourceStat = Os.stat(source.path)
        val pinStat = Os.stat(pinned.path)
        val sameInode = sourceStat.st_dev == pinStat.st_dev && sourceStat.st_ino == pinStat.st_ino
        try {
            if (capability.isSuccess) {
                assertTrue("An independently supported hard-link operation must also be used by the vault", sameInode)
                val probeStat = Os.stat(probe.path)
                assertEquals(sourceStat.st_dev, probeStat.st_dev)
                assertEquals(sourceStat.st_ino, probeStat.st_ino)
                Log.i("VaultRuntimeTest", "Pin strategy=hardlink; native inode identity confirmed")
            } else {
                val error = requireNotNull(capability.exceptionOrNull())
                assertTrue("Only a documented unavailable-link condition may choose the copy fallback",
                    error is java.io.IOException || error is UnsupportedOperationException || error is SecurityException)
                assertFalse("The copy fallback must own an independent regular-file inode", sameInode)
                val errno = generateSequence(error) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
                Log.i("VaultRuntimeTest", "Pin strategy=durable-copy; hardlink=${error.javaClass.simpleName}; errno=${errno ?: "not-exposed"}")
            }
        } finally { probe.delete() }
        assertArrayEquals(first, pinned.readBytes())
        replaceSource(replacement)
        val replacementStat = Os.stat(source.path)
        assertFalse(replacementStat.st_dev == pinStat.st_dev && replacementStat.st_ino == pinStat.st_ino)
        assertArrayEquals(first, pinned.readBytes())
        assertArrayEquals(replacement, source.readBytes())
        assertEquals(pinned.canonicalFile, FirmwareVault(context).selected()!!.canonicalFile)
        val next = vault.pinSelected()
        assertNotEquals(pinned, next)
        assertArrayEquals(replacement, next.readBytes())
        assertFalse("A superseded large package pin must not remain allocated", pinned.exists())
        assertEquals(setOf(next.name), directory.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(next.canonicalFile, FirmwareVault(context).selected()!!.canonicalFile)
    }

    @Test fun malformedOversizedAndMissingSelectionsFailClosed() {
        listOf("../selected-ota.zip", "-".repeat(36) + ".zip", "a".repeat(129), UUID.randomUUID().toString() + ".zip").forEach {
            selection.writeText(it)
            assertNull(vault.selected())
            assertNull(FirmwareVault(context).selected())
        }
    }

    @Test fun sourceSymlinkEmptyFileAndOversizedSparseFileCannotBePinned() {
        val other = File(root, "other.bin").apply { writeBytes(bytes(3)) }
        Files.createSymbolicLink(source.toPath(), other.toPath())
        mustReject { vault.pinSelected() }
        assertNull(vault.selected())
        Files.delete(source.toPath())
        source.writeBytes(byteArrayOf())
        mustReject { vault.pinSelected() }
        // ftruncate creates a sparse file; this allocates no 12-GiB byte array.
        RandomAccessFile(source, "rw").use { it.setLength(FirmwareValidator.MAX_PACKAGE_BYTES + 1) }
        mustReject { vault.pinSelected() }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun selectedSymlinkAndEscapedVaultDirectoryFailClosed() {
        replaceSource(bytes(4))
        val pinned = vault.pinSelected()
        val other = File(directory, UUID.randomUUID().toString() + ".zip").apply { writeBytes(bytes(5)) }
        Files.delete(pinned.toPath())
        Files.createSymbolicLink(pinned.toPath(), other.toPath())
        assertNull(vault.selected())
        Files.delete(pinned.toPath())
        Files.delete(other.toPath())
        Files.delete(directory.toPath())
        val escaped = File(root, "other-directory").apply { mkdirs() }
        Files.createSymbolicLink(directory.toPath(), escaped.toPath())
        assertNull(vault.selected())
        mustReject { vault.pinSelected() }
    }

    @Test fun failedSelectionCommitPreservesPreviousSelectionAndRemovesNewPin() {
        replaceSource(bytes(6))
        val original = vault.pinSelected()
        replaceSource(bytes(7))
        val obstruction = File(selection.path + ".new").apply { mkdirs() }
        mustReject { vault.pinSelected() }
        obstruction.delete()
        assertEquals(original.canonicalFile, vault.selected()!!.canonicalFile)
        assertEquals(setOf(original.name), directory.listFiles().orEmpty().map { it.name }.toSet())
        assertArrayEquals(bytes(6), original.readBytes())
    }

    @Test fun successfulPinPrunesOnlyObsoleteRegularUuidPackages() {
        replaceSource(bytes(8))
        val original = vault.pinSelected()
        val note = File(directory, "keep-note.txt").apply { writeText("private test note") }
        val folder = File(directory, UUID.randomUUID().toString() + ".zip").apply { mkdirs() }
        val target = File(root, "keep-link-target.bin").apply { writeBytes(bytes(9)) }
        val link = File(directory, UUID.randomUUID().toString() + ".zip")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        replaceSource(bytes(10))
        val selected = vault.pinSelected()
        assertFalse(original.exists())
        assertTrue(note.isFile)
        assertTrue(folder.isDirectory)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertArrayEquals(bytes(9), target.readBytes())
        assertEquals(selected.canonicalFile, vault.selected()!!.canonicalFile)
    }

    @Test fun syntheticStockFactsReloadAndBindOnlyAfterObservedTargetBoot() {
        writeSyntheticStoredFacts()
        assertEquals(1, FirmwareVault(context).proofCount())
        val target = syntheticTarget()
        assertNull(vault.referenceFor(target.copy(build = "OLD_BUILD"), anchors))
        assertNull(vault.referenceFor(target.copy(rootGranted = false), anchors))
        val reference = requireNotNull(FirmwareVault(context).referenceFor(target, anchors))
        assertEquals(requireNotNull(syntheticManifest.initBootTarget).sha256, reference.sha256)
        assertEquals(requireNotNull(syntheticManifest.initBootTarget).bytes, reference.bytes)
        assertEquals(actualFp, reference.fingerprint)
        val reloaded = requireNotNull(FirmwareVault(context).referenceFor(target, anchors))
        assertEquals(reference, reloaded)
        assertNull(vault.referenceFor(target, anchors + "d".repeat(64)))
        assertNull(vault.referenceFor(target.copy(fingerprint = payloadFp), anchors))
        val text = proofs.readText()
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("token="))
        assertFalse(text.contains(target.bootId))
        assertFalse(text.contains(target.modules.single()))
    }

    @Test fun malformedOversizedAndInvalidStockEvidenceCannotSupplyReference() {
        writeSyntheticStoredFacts()
        val valid = proofs.readText()
        listOf("schema=1\ncount=1\n", "x".repeat(256 * 1024 + 1),
            valid.replace("0.image=" + "c".repeat(64), "0.image=invalid"),
            valid.replace("0.bytes=8192", "0.bytes=-1"), valid + "\nunknown.token=private\n").forEach {
            proofs.writeText(it)
            val reloaded = FirmwareVault(context)
            assertEquals(0, reloaded.proofCount())
            assertNull(reloaded.referenceFor(syntheticTarget(), anchors))
        }
    }

    @Test fun callerSuppliedSignatureBooleanNeverBecomesPersistedTrust() = runBlocking {
        replaceSource(bytes(12))
        val pinned = vault.pinSelected()
        // Even signatureVerified=true cannot authorize random bytes against the emulator's actual OTA certs.
        mustReject { runBlocking { vault.recordVerified(pinned, syntheticReport, syntheticTarget()) } }
        mustReject { runBlocking { vault.recordVerified(source, syntheticReport, syntheticTarget()) } }
        assertEquals(0, vault.proofCount())
        assertNull(vault.referenceFor(syntheticTarget(), anchors))
    }
}
