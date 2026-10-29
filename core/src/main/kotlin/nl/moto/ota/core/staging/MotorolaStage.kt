package nl.moto.ota.core.staging

import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

enum class StageError { STAGING_CONFIRMATION_REQUIRED, STAGING_RUNTIME_UNSUPPORTED, STAGING_RUN_INVALID,
    STAGING_SOURCE_CHANGED, STAGING_STOCK_UNPROVEN, STAGING_DEVICE_UNSAFE, STAGING_PACKAGE_UNVERIFIED,
    STAGING_METADATA_MISMATCH, STAGING_CHANNEL_UNPROVEN, STAGING_ROLLBACK_OR_WIPE, STAGING_PATH_REJECTED, STAGING_SPACE_LOW,
    STAGING_HASH_MISMATCH, STAGING_DESTINATION_EXISTS, STAGING_ATOMIC_PUBLISH_UNSUPPORTED,
    STAGING_DURABILITY_UNPROVEN, STAGING_HELPER_INTERRUPTED, STAGING_IO }
class StageFailure(val code: StageError): IOException(code.name)

data class StageEnvironment(val device: DeviceState, val sdk: Int, val automaticUpdatesDisabled: Boolean?,
    val activeStockSha256: String, val nowMillis: Long, val measuredSystemFingerprint: String = "")

/** This receipt proves a file copy only; it does not prove Motorola recognized or installed it. */
data class MotorolaStageReceipt(val runId: String, val packageSha256: String, val packageBytes: Long,
    val destinationPath: String, val sourceBootId: String, val stagedAtMillis: Long, val alreadyPresent: Boolean,
    val oemReviewRequired: Boolean = true, val signedChannelProven: Boolean = false,
    val androidSourceFingerprint: String = "", val payloadSourceFingerprint: String = "")

object MotorolaStagePolicy {
    const val DESTINATION_DIRECTORY = "/storage/emulated/0/Android/data/com.motorola.ccc.ota/files"
    const val TEMPORARY_DIRECTORY = "/storage/emulated/0/Android/data/nl.moto.ota/files/staging"
    private val HASH = Regex("[a-f0-9]{64}")
    private val FP = Regex("motorola/[A-Za-z0-9_.-]+/merc:[1-9][0-9]?/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+:user/release-keys")

    fun requireConfirmed(explicit: Boolean, automaticRecognitionAcknowledged: Boolean) {
        if (!explicit || !automaticRecognitionAcknowledged) throw StageFailure(StageError.STAGING_CONFIRMATION_REQUIRED)
    }

    fun requireSameRun(expected: UpdateRun, current: UpdateRun) {
        if (current.id != expected.id || current.stage != expected.stage || current.route != expected.route || current.simulated ||
            current.sourceBuild != expected.sourceBuild || current.sourceFingerprint != expected.sourceFingerprint ||
            current.sourceSlot != expected.sourceSlot || current.sourceBootId != expected.sourceBootId ||
            current.targetBuild != expected.targetBuild || current.targetSlot != expected.targetSlot ||
            current.declaredTargetFingerprint != expected.declaredTargetFingerprint || current.packageSha256 != expected.packageSha256 ||
            current.packageVerified != expected.packageVerified || current.stockSha256 != expected.stockSha256 ||
            current.stockRestoreEvidence != expected.stockRestoreEvidence || current.otaAppliedObserved != expected.otaAppliedObserved)
            fail(StageError.STAGING_SOURCE_CHANGED)
    }

    fun requireSafe(report: FirmwareReport, run: UpdateRun, environment: StageEnvironment) {
        val d = environment.device
        if (run.simulated || !run.id.matches(Regex("[a-f0-9-]{36}")) ||
            run.stage !in setOf(UpdateStage.STOCK_RESTORED, UpdateStage.AWAITING_OFFICIAL_OTA)) fail(StageError.STAGING_RUN_INVALID)
        if (environment.sdk != 37 || d.androidVersion != "17") fail(StageError.STAGING_RUNTIME_UNSUPPORTED)
        if (d.simulated || !d.exactProfileMatch || !d.rootChecked || !d.rootGranted || d.magiskCode != 31100 ||
            d.virtualAb != true || d.snapshot != SnapshotState.NONE || d.engine != EngineState.IDLE ||
            environment.automaticUpdatesDisabled != true || d.bootSuccessful != true ||
            d.bootloaderUnlocked != true || d.verifiedBootState != "orange" || d.batteryPercent < 50 ||
            d.capturedAtMillis <= 0 || environment.nowMillis - d.capturedAtMillis !in 0..30_000) fail(StageError.STAGING_DEVICE_UNSAFE)
        if (run.sourceBuild != d.build || run.sourceFingerprint != d.fingerprint || run.sourceSlot != d.slot ||
            d.slot !in setOf("_a", "_b") || run.targetSlot != d.inactiveSlot || run.sourceBootId != d.bootId ||
            !d.bootId.matches(Regex("[a-f0-9-]{36}"))) fail(StageError.STAGING_SOURCE_CHANGED)
        if (run.stockRestoreEvidence !in setOf(StepEvidence.OBSERVED, StepEvidence.CRYPTOGRAPHIC_IMAGE) ||
            !run.stockSha256.matches(HASH) || environment.activeStockSha256 != run.stockSha256) fail(StageError.STAGING_STOCK_UNPROVEN)
        if (!report.structureValid || !report.payloadHashValid || !report.signatureVerified || !report.sha256.matches(HASH) ||
            report.packageBytes !in 1..FirmwareValidator.MAX_PACKAGE_BYTES || !run.packageVerified ||
            run.packageSha256 != report.sha256) fail(StageError.STAGING_PACKAGE_UNVERIFIED)
        // Publishing into Motorola's directory can trigger processing. A trusted
        // signing key, current device carrier or official host does not prove the
        // package's channel. This gate also applies to the official-guided route.
        val motorolaChannel = report.metadata["motorola-channel"]
        val preChannel = report.metadata["pre-channel"]
        val channel = motorolaChannel ?: preChannel
        if ((motorolaChannel != null && preChannel != null && motorolaChannel != preChannel) ||
            (channel != null && channel.isNotBlank() && channel != "reteu") ||
            report.issues.any { it.code == "WRONG_REGION" }) fail(StageError.STAGING_METADATA_MISMATCH)
        if (channel != "reteu" || !report.regionVerified || report.issues.any { it.code == "REGION_UNPROVEN" })
            fail(StageError.STAGING_CHANNEL_UNPROVEN)
        if (run.route == UpdateRoute.AUTHENTICATED_PACKAGE && !report.readyForOfficialReview) fail(StageError.STAGING_PACKAGE_UNVERIFIED)
        // Copy-only OEM handoff preserves both signed namespaces. No compatible=true
        // is manufactured: Motorola remains responsible for its installation policy.
        val permittedOemReview = setOf("SOURCE_BUILD", "SOURCE_FINGERPRINT", "OEM_METADATA_REVIEW_REQUIRED")
        if (report.issues.any { it.code !in permittedOemReview } || report.metadata["ota-type"] != "AB" ||
            "merc" !in report.metadata["pre-device"].orEmpty().split('|') ||
            report.targetBuild != run.targetBuild || report.targetBuild == run.sourceBuild ||
            !report.targetFingerprint.matches(FP) || report.targetFingerprint != run.declaredTargetFingerprint ||
            report.targetFingerprint.split('/')[3] != run.targetBuild) fail(StageError.STAGING_METADATA_MISMATCH)
        val payloadSource = report.payloadSourceFingerprint
        val payloadTarget = report.payloadTargetFingerprint
        if (payloadSource.isNotBlank() || payloadTarget.isNotBlank()) {
            if (!payloadSource.matches(FP) || payloadTarget != run.declaredTargetFingerprint)
                fail(StageError.STAGING_METADATA_MISMATCH)
            val sourceParts = payloadSource.split('/')
            if (sourceParts[2].substringAfter(':') != d.androidVersion || sourceParts[3] != d.build ||
                d.incrementalBuild.isBlank() || sourceParts[4].substringBefore(':') != d.incrementalBuild)
                fail(StageError.STAGING_METADATA_MISMATCH)
            if (payloadSource != run.sourceFingerprint) {
                val independentlyMeasured = setOf(d.vendorFingerprint, environment.measuredSystemFingerprint).filter(String::isNotBlank)
                if (run.route != UpdateRoute.OFFICIAL_MOTOROLA ||
                    report.androidMetadataSourceFingerprint.split('|').none { it in independentlyMeasured })
                    fail(StageError.STAGING_METADATA_MISMATCH)
                // A copy-only OEM review mapping: no product alias is created and
                // standard Android installation compatibility remains unproven.
            }
        } else if (run.sourceFingerprint !in report.androidMetadataSourceFingerprint.split('|') ||
            d.incrementalBuild.isBlank() || report.sourceBuild != d.incrementalBuild ||
            report.androidMetadataTargetFingerprint != run.declaredTargetFingerprint) fail(StageError.STAGING_METADATA_MISMATCH)
        val targetAndroid = report.targetAndroidVersion.toIntOrNull()
        val currentAndroid = d.androidVersion.toIntOrNull()
        val targetTime = report.metadata["post-timestamp"]?.toLongOrNull()
        val sourcePatch = patch(d.securityPatch)
        val targetPatch = patch(report.metadata["post-security-patch-level"].orEmpty())
        if (listOf("ota-wipe", "ota-downgrade").any { report.metadata[it]?.let { value -> value != "no" } == true } ||
            targetAndroid == null || currentAndroid == null || targetAndroid < currentAndroid ||
            d.buildTimestamp <= 0 || targetTime == null || targetTime <= d.buildTimestamp ||
            sourcePatch == null || targetPatch == null || targetPatch < sourcePatch) fail(StageError.STAGING_ROLLBACK_OR_WIPE)
    }

    fun destinationName(sha256: String): String {
        if (!sha256.matches(HASH)) fail(StageError.STAGING_PACKAGE_UNVERIFIED)
        return "moto-ota-$sha256.zip"
    }
    private fun patch(value: String): LocalDate? = runCatching {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))); LocalDate.parse(value)
    }.getOrNull()
    private fun fail(code: StageError): Nothing = throw StageFailure(code)
}

/** Full hashed/fsynced/read-back copy, then caller-provided atomic NOREPLACE publish.
 * The temporary directory must be outside Motorola's watched directory.
 */
object VerifiedStageTransfer {
    fun copy(source: File, temporaryDirectory: File, destination: File, expectedBytes: Long, expectedSha256: String,
             availableBytes: () -> Long = { temporaryDirectory.usableSpace }, check: () -> Unit = {},
             publish: (File, File, Long) -> Unit): Boolean {
        if (expectedBytes !in 1..FirmwareValidator.MAX_PACKAGE_BYTES || !expectedSha256.matches(Regex("[a-f0-9]{64}")))
            throw StageFailure(StageError.STAGING_PACKAGE_UNVERIFIED)
        if (!Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) || source.length() != expectedBytes ||
            !temporaryDirectory.isDirectory || !destination.parentFile.isDirectory ||
            temporaryDirectory.canonicalFile == destination.parentFile.canonicalFile ||
            destination.name != MotorolaStagePolicy.destinationName(expectedSha256)) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        check()
        if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) {
            val matching = try {
                Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS) && destination.length() == expectedBytes &&
                    digest(destination, expectedBytes, check) == expectedSha256
            } catch (error: StageFailure) {
                if (error.code != StageError.STAGING_HASH_MISMATCH) throw error
                false
            }
            if (!matching)
                throw StageFailure(StageError.STAGING_DESTINATION_EXISTS)
            return true
        }
        if (availableBytes() < expectedBytes + PackageTransfer.MIN_RESERVE_BYTES) throw StageFailure(StageError.STAGING_SPACE_LOW)
        val temporary = File(temporaryDirectory, ".moto-stage-${UUID.randomUUID()}.part")
        if (!temporary.createNewFile()) throw StageFailure(StageError.STAGING_PATH_REJECTED)
        try {
            source.inputStream().use { input -> FileOutputStream(temporary, false).use { output ->
                val buffer = ByteArray(1024 * 1024); var count = 0L
                while (true) {
                    check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0 || count > expectedBytes - read) throw StageFailure(StageError.STAGING_HASH_MISMATCH)
                    if (availableBytes() < read + PackageTransfer.MIN_RESERVE_BYTES) throw StageFailure(StageError.STAGING_SPACE_LOW)
                    output.write(buffer, 0, read); count += read
                }
                if (count != expectedBytes) throw StageFailure(StageError.STAGING_HASH_MISMATCH)
                output.fd.sync()
            } }
            if (digest(temporary, expectedBytes, check) != expectedSha256 || digest(source, expectedBytes, check) != expectedSha256)
                throw StageFailure(StageError.STAGING_HASH_MISMATCH)
            check()
            publish(temporary, destination, expectedBytes)
            if (!Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS) || digest(destination, expectedBytes, check) != expectedSha256)
                throw StageFailure(StageError.STAGING_HASH_MISMATCH)
            return false
        } finally { temporary.delete() }
    }

    fun digest(file: File, bytes: Long, check: () -> Unit = {}): String {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() != bytes) throw StageFailure(StageError.STAGING_HASH_MISMATCH)
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        file.inputStream().use { input -> val buffer = ByteArray(1024 * 1024)
            while (true) { check(); val n = input.read(buffer); if (n < 0) break
                if (n == 0 || count > bytes - n) throw StageFailure(StageError.STAGING_HASH_MISMATCH)
                count += n; digest.update(buffer, 0, n)
            }
        }
        if (count != bytes) throw StageFailure(StageError.STAGING_HASH_MISMATCH)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
