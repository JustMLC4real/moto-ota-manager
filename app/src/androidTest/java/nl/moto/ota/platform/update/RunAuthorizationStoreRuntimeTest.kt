package nl.moto.ota.platform.update

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID

/** Actual Android private/atomic files and MAC verification. Never starts su, a worker or an updater. */
@RunWith(AndroidJUnit4::class)
class RunAuthorizationStoreRuntimeTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: RunAuthorizationStore
    private lateinit var source: DeviceState
    private val packageHash = "b".repeat(64)
    private val targetFingerprint = "motorola/fixture_g_sys/fixture:17/NEXT.17/next123:user/release-keys"
    private val runId get() = UUID.randomUUID().toString()

    @Before fun setUp() {
        assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(app.cacheDir.toPath(), "run-authority-test-").toFile().canonicalFile
        Os.chmod(root.path, 0x1c0)
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        store = RunAuthorizationStore(context)
        source = DeviceState(manufacturer = "motorola", model = "XT9999-1", device = "fixture", channel = "retus",
            androidVersion = "16", sdkInt = 36, build = "CURRENT.16",
            fingerprint = "motorola/fixture_g_sys/fixture:16/CURRENT.16/current123:user/release-keys", slot = "_a",
            bootId = UUID.randomUUID().toString(), capturedAtMillis = System.currentTimeMillis())
    }

    @After fun tearDown() {
        if (::root.isInitialized) Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test fun inspectionOfAMissingAuthorizationCreatesNoKeyAndCannotActivateAWriter() {
        assertNull(store.load())
        assertTrue(root.listFiles()!!.isEmpty())
        rejected { store.claim(RunAction.RESTORE_STOCK) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun aProgrammaticFalseConsentCannotCreateAnyAuthorityFiles() {
        rejected("RUN_START_REQUIRED") { store.create(runId, source, ObservedUpdateTarget("NEXT.17"), false) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun anExistingNonterminalJournalBlocksANewRunBeforeKeyCreation() {
        val existing = journal(UUID.randomUUID().toString())
        save(existing)
        rejected("RUN_AUTHORIZATION_ACTIVE") { store.create(runId, source, ObservedUpdateTarget("NEXT.17"), true) }
        assertFalse(File(root, "update/authority.key").exists())
        assertEquals(existing, UpdateRunStore(context).load())
    }

    @Test fun aVerifiedSameTargetCanBePinnedAndClaimedExactlyOnce() {
        val authorization = createUnpinned()
        save(journal(authorization.runId))
        rejected("RUN_VERIFIED_PACKAGE_REQUIRED") { store.claim(RunAction.RESTORE_STOCK) }
        val pinned = store.pinPackage(packageHash, "NEXT.17", targetFingerprint, true)
        val (scope, operation) = store.claim(RunAction.RESTORE_STOCK)
        assertEquals(pinned, scope)
        assertEquals(scope.runId, operation.runId)
        assertEquals(RunAction.RESTORE_STOCK, operation.action)
        assertEquals(packageHash, operation.packageSha256)
        assertEquals(source.bootId, operation.sourceBootId)
        assertTrue(operation.expiresElapsedMillis - SystemClock.elapsedRealtime() in 0..60_000)
        assertTrue(File(root, "update/automation/dispatched/${scope.runId}/RESTORE_STOCK.claim").isFile)
        rejected("RUN_ACTION_ALREADY_CLAIMED") { store.claim(RunAction.RESTORE_STOCK) }
    }

    @Test fun aNewStoreCanInspectOldConsentButCannotReactivateItAfterAProcessRestart() {
        val pinned = pin()
        val recreated = RunAuthorizationStore(context)
        assertEquals(pinned, recreated.load())
        rejected("RUN_EXPLICIT_SESSION_REQUIRED") { recreated.claim(RunAction.RESTORE_STOCK) }
        assertFalse(File(root, "update/automation/dispatched/${pinned.runId}").exists())
    }

    @Test fun aReusedUuidCannotOverwriteItsEarlierAuthorizationEvenAfterACompletedRun() {
        val original = pin()
        save(journal(original.runId).copy(stage = UpdateStage.COMPLETE))
        rejected("RUN_ID_ALREADY_USED") {
            store.create(original.runId, source, ObservedUpdateTarget("ANOTHER.17"), true)
        }
        assertEquals(original, store.load())
    }

    @Test fun changedSourceBootBuildFingerprintSlotAndTechnicalProfileCannotClaimAnAction() {
        val authorization = pin()
        val original = journal(authorization.runId)
        listOf(original.copy(sourceBootId = UUID.randomUUID().toString()), original.copy(sourceBuild = "OTHER.16"),
            original.copy(sourceFingerprint = source.fingerprint.replace("current123", "other123")),
            original.copy(sourceSlot = "_b", targetSlot = "_a"), original.copy(sourceModel = "XT9999-2"),
            original.copy(sourceDevice = "other"), original.copy(sourceChannel = "reteu")).forEach { changed ->
            save(changed)
            rejected("RUN_SOURCE_CHANGED") { store.claim(RunAction.RESTORE_STOCK) }
        }
        assertFalse(File(root, "update/automation/dispatched/${authorization.runId}").exists())
    }

    @Test fun changedPackageTargetOrMissingVerificationCannotClaimAnAction() {
        val authorization = pin()
        val original = journal(authorization.runId)
        listOf(original.copy(targetBuild = "OTHER.17") to "RUN_SOURCE_CHANGED",
            original.copy(declaredTargetFingerprint = targetFingerprint.replace("next123", "other123")) to "RUN_PACKAGE_CHANGED",
            original.copy(packageSha256 = "c".repeat(64)) to "RUN_PACKAGE_CHANGED",
            original.copy(packageVerified = false) to "RUN_PACKAGE_CHANGED").forEach { (changed, code) ->
            save(changed)
            rejected(code) { store.claim(RunAction.RESTORE_STOCK) }
        }
        assertFalse(File(root, "update/automation/dispatched/${authorization.runId}").exists())
    }

    @Test fun staleFutureFailedAndRecoveryObservationsCannotAuthorizeDispatch() {
        val authorization = pin()
        val original = journal(authorization.runId)
        listOf(original.copy(lastObservationMillis = System.currentTimeMillis() - 30_001),
            original.copy(lastObservationMillis = System.currentTimeMillis() + 10_000),
            original.copy(stage = UpdateStage.FAILED), original.copy(stage = UpdateStage.RECOVERY_REQUIRED)).forEach { changed ->
            save(changed)
            rejected("RUN_SOURCE_CHANGED") { store.claim(RunAction.RESTORE_STOCK) }
        }
    }

    @Test fun tamperedMacScopeNeverMintsAnOperationAndLoadRemainsObservationOnly() {
        val authorization = pin()
        val file = File(root, "update/automation/authorization.properties")
        file.writeText(RunAuthorizationCodec.encode(authorization.copy(channel = "reteu")))
        assertNull(store.load())
        rejected("RUN_AUTHORIZATION_INVALID") { store.claim(RunAction.RESTORE_STOCK) }
        assertFalse(File(root, "update/automation/dispatched/${authorization.runId}").exists())
    }

    @Test fun aSymlinkCannotSubstituteForThePrivateAuthorizationFile() {
        val authorization = pin()
        val file = File(root, "update/automation/authorization.properties")
        val other = File(root, "same-authority.properties")
        check(file.renameTo(other))
        Files.createSymbolicLink(file.toPath(), other.toPath())
        assertNull(store.load())
        rejected("RUN_STORE_FILE_INVALID") { store.claim(RunAction.RESTORE_STOCK) }
        assertFalse(File(root, "update/automation/dispatched/${authorization.runId}").exists())
    }

    @Test fun scopeCannotBeNarrowedAfterAnActionWasAlreadyDispatched() {
        val authorization = store.create(runId, source, ObservedUpdateTarget("NEXT.17", "", packageHash, true), true)
        save(journal(authorization.runId).copy(declaredTargetFingerprint = ""))
        store.claim(RunAction.RESTORE_STOCK)
        save(journal(authorization.runId))
        rejected("RUN_SCOPE_ALREADY_DISPATCHED") { store.pinPackage(packageHash, "NEXT.17", targetFingerprint, true) }
        assertEquals(authorization, store.load())
    }

    private fun createUnpinned(): RunAuthorization =
        store.create(runId, source, ObservedUpdateTarget("NEXT.17", targetFingerprint), true)

    private fun pin(): RunAuthorization {
        val authorization = createUnpinned()
        save(journal(authorization.runId))
        return store.pinPackage(packageHash, "NEXT.17", targetFingerprint, true)
    }

    private fun journal(id: String): UpdateRun = UpdateRun(id = id, stage = UpdateStage.STOCK_PREPARATION,
        sourceModel = source.model, sourceDevice = source.device, sourceChannel = source.channel,
        sourceBuild = source.build, sourceFingerprint = source.fingerprint, sourceBootId = source.bootId, sourceSlot = source.slot,
        targetSlot = source.inactiveSlot, targetBuild = "NEXT.17", declaredTargetFingerprint = targetFingerprint,
        packageSha256 = packageHash, packageVerified = true, stockSha256 = "a".repeat(64),
        lastObservationMillis = System.currentTimeMillis(), lastObservedBootId = source.bootId,
        lastObservedBuild = source.build, lastObservedSlot = source.slot, lastObservedEngine = EngineState.IDLE,
        lastObservedSnapshot = SnapshotState.NOT_APPLICABLE)

    private fun save(run: UpdateRun) { UpdateRunStore(context).save(run) }

    private inline fun rejected(code: String? = null, block: () -> Unit) {
        val failure = try { block(); null } catch (error: UpdateSafetyException) { error }
        assertNotNull("An invalid authority must not mint an operation", failure)
        if (code != null) assertEquals(code, failure!!.code)
    }
}
