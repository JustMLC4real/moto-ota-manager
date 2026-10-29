package nl.moto.ota.core.update

import nl.moto.ota.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class ExecutionMode { LIVE, SANDBOX }

data class RestoreEnvironment(
    val device: DeviceState,
    val partitionBytes: Long,
    val activeImageSha256: String,
    val automaticUpdatesDisabled: Boolean?,
    val stockValidation: BackupValidation,
    val mode: ExecutionMode = ExecutionMode.LIVE,
    val elapsedRealtimeMillis: Long = System.nanoTime() / 1_000_000
)

class UpdateSafetyException(val code: String) : IllegalStateException(code)

/** A reviewable, immutable write plan. It is not permission to execute. */
data class StockRestorePlan(
    val transactionId: String,
    val runId: String,
    val mode: ExecutionMode,
    val model: String,
    val device: String,
    val channel: String,
    val build: String,
    val fingerprint: String,
    val bootId: String,
    val slot: String,
    val imageBytes: Long,
    val stockSha256: String,
    val previousImageSha256: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val createdElapsedRealtimeMillis: Long,
    val expiresElapsedRealtimeMillis: Long
) {
    val partition: String get() = "init_boot$slot"
    val confirmationSummary: String get() = "Herstel $partition op $model ($build), $imageBytes bytes. " +
        "Originele SHA-256 $stockSha256. De huidige gepatchte image wordt eerst volledig bewaard. " +
        "Herstart daarna niet voordat OTA en Magisk zijn afgerond."

    /** Length framing prevents ambiguous values or newline-based field injection. */
    fun canonical(): String = listOf("moto-stock-restore-v1", transactionId, runId, mode.name, model, device,
        channel, build, fingerprint, bootId, slot, imageBytes.toString(), stockSha256, previousImageSha256,
        createdAtMillis.toString(), expiresAtMillis.toString(), createdElapsedRealtimeMillis.toString(),
        expiresElapsedRealtimeMillis.toString()).joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
}

data class RestoreConsent(val transactionId: String, val macSha256: String, val noRebootAcknowledged: Boolean)

object StockRestorePolicy {
    const val CONSENT_LIFETIME_MILLIS = 60_000L
    private val digest = Regex("[a-f0-9]{64}")

    fun blockers(environment: RestoreEnvironment, nowMillis: Long = System.currentTimeMillis()): List<CheckResult> {
        val d = environment.device
        val b = mutableListOf<CheckResult>()
        fun require(ok: Boolean, code: String, message: String) { if (!ok) b += CheckResult(code, message) }
        require(d.exactProfileMatch && d.androidVersion == "17", "PROFILE", "Alleen het onderzochte XT2607-1/merc/reteu Android 17-profiel is toegestaan.")
        require(d.rootChecked && d.rootGranted && d.magiskCode == 31100, "ROOT", "Bevestigde Magisk 31.1-root is vereist.")
        require(d.virtualAb == true && d.slot in setOf("_a", "_b"), "SLOT", "Actieve Virtual A/B-slot moet bekend zijn.")
        require(d.partitions.containsAll(listOf("init_boot_a", "init_boot_b")), "PARTITIONS", "Beide init_boot-partities moeten zijn waargenomen.")
        require(d.snapshot == SnapshotState.NONE && d.engine == EngineState.IDLE, "UPDATE_BUSY", "Snapshot NONE en een actuele idle-engine zijn vereist.")
        require(d.bootSuccessful == true && d.bootloaderUnlocked == true && d.verifiedBootState == "orange", "BOOT_STATE", "Een succesvolle boot en kernelbevestigde ontgrendelde bootloader zijn vereist.")
        require(d.securityEvidenceSource == "KERNEL_BOOTCONFIG" || environment.mode == ExecutionMode.SANDBOX, "SECURITY_SOURCE", "De kernelbootgegevens moeten de toestand bevestigen.")
        require(d.bootId.isNotBlank() && d.build.isNotBlank() && d.fingerprint.isNotBlank(), "IDENTITY", "Build, fingerprint en opstartsessie zijn vereist.")
        require(d.capturedAtMillis > 0 && nowMillis >= d.capturedAtMillis && nowMillis - d.capturedAtMillis <= 30_000,
            "STALE", "Voer vlak vóór stockherstel een nieuwe controle uit (maximaal 30 seconden oud).")
        require(d.batteryPercent in 50..100 && d.freeBytes >= 512L * 1024 * 1024, "POWER_STORAGE", "Minimaal 50% accu en 512 MiB vrije ruimte zijn vereist.")
        require(d.moduleInventoryKnown, "MODULES", "De module-inventaris moet bekend zijn.")
        require(environment.automaticUpdatesDisabled == true, "AUTOMATIC_UPDATES", "Schakel automatische systeemupdates zelf uit in Ontwikkelaarsopties; de app wijzigt deze instelling niet.")
        require(environment.stockValidation.usableAsStock && environment.stockValidation.bytes == environment.partitionBytes,
            "STOCK_PROVENANCE", "Exacte originele init_boot-herkomst en volledige partitiegrootte zijn vereist.")
        require(environment.partitionBytes in 4096..128L * 1024 * 1024 &&
            (environment.mode != ExecutionMode.LIVE || environment.partitionBytes == 8_388_608L), "IMAGE_SIZE", "De partitiegrootte is niet het onderzochte formaat.")
        require(digest.matches(environment.activeImageSha256) && digest.matches(environment.stockValidation.sha256), "IMAGE_DIGEST", "Volledige SHA-256-metingen van bron en actuele partitie zijn vereist.")
        require((environment.mode == ExecutionMode.SANDBOX) == d.simulated, "EXECUTION_MODE", "Simulatie en echte uitvoering moeten strikt gescheiden blijven.")
        return b
    }

    fun plan(environment: RestoreEnvironment, runId: String, nowMillis: Long = System.currentTimeMillis()): StockRestorePlan {
        val blockers = blockers(environment, nowMillis)
        if (blockers.isNotEmpty()) throw UpdateSafetyException(blockers.first().code)
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val d = environment.device
        return StockRestorePlan(UUID.randomUUID().toString(), runId, environment.mode, d.model, d.device, d.channel,
            d.build, d.fingerprint, d.bootId, d.slot, environment.partitionBytes, environment.stockValidation.sha256,
            environment.activeImageSha256, nowMillis, nowMillis + CONSENT_LIFETIME_MILLIS,
            environment.elapsedRealtimeMillis, environment.elapsedRealtimeMillis + CONSENT_LIFETIME_MILLIS)
    }

    fun confirm(plan: StockRestorePlan, secret: ByteArray, explicitConfirmation: Boolean,
                noRebootAcknowledged: Boolean, nowMillis: Long = System.currentTimeMillis(),
                elapsedRealtimeMillis: Long = System.nanoTime() / 1_000_000): RestoreConsent {
        if (!explicitConfirmation || !noRebootAcknowledged) throw UpdateSafetyException("CONSENT_REQUIRED")
        if (nowMillis !in plan.createdAtMillis..plan.expiresAtMillis ||
            elapsedRealtimeMillis !in plan.createdElapsedRealtimeMillis..plan.expiresElapsedRealtimeMillis) throw UpdateSafetyException("CONSENT_EXPIRED")
        require(secret.size >= 32)
        return RestoreConsent(plan.transactionId, mac(plan, secret), true)
    }

    fun verify(plan: StockRestorePlan, consent: RestoreConsent, secret: ByteArray, now: RestoreEnvironment,
               nowMillis: Long = System.currentTimeMillis()) {
        if (consent.transactionId != plan.transactionId || !consent.noRebootAcknowledged ||
            nowMillis !in plan.createdAtMillis..plan.expiresAtMillis || secret.size < 32 ||
            now.elapsedRealtimeMillis !in plan.createdElapsedRealtimeMillis..plan.expiresElapsedRealtimeMillis ||
            plan.expiresElapsedRealtimeMillis-plan.createdElapsedRealtimeMillis != CONSENT_LIFETIME_MILLIS ||
            !MessageDigest.isEqual(consent.macSha256.toByteArray(StandardCharsets.US_ASCII), mac(plan, secret).toByteArray(StandardCharsets.US_ASCII))) {
            throw UpdateSafetyException("CONSENT_INVALID_OR_EXPIRED")
        }
        val errors = blockers(now, nowMillis)
        if (errors.isNotEmpty()) throw UpdateSafetyException(errors.first().code)
        val d = now.device
        if (now.mode != plan.mode || d.model != plan.model || d.device != plan.device || d.channel != plan.channel ||
            d.build != plan.build || d.fingerprint != plan.fingerprint || d.bootId != plan.bootId || d.slot != plan.slot ||
            now.partitionBytes != plan.imageBytes || now.activeImageSha256 != plan.previousImageSha256 ||
            now.stockValidation.sha256 != plan.stockSha256) throw UpdateSafetyException("OBSERVATIONS_CHANGED")
    }

    private fun mac(plan: StockRestorePlan, secret: ByteArray): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(plan.canonical().toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Bounded persistence format; the HMAC authenticates every one of these fields. */
object StockRestorePlanCodec {
    fun encode(plan:StockRestorePlan,consent:RestoreConsent?=null):String = java.util.Properties().apply {
        setProperty("schema","2")
        val fields=listOf(plan.transactionId,plan.runId,plan.mode.name,plan.model,plan.device,plan.channel,plan.build,plan.fingerprint,
            plan.bootId,plan.slot,plan.imageBytes.toString(),plan.stockSha256,plan.previousImageSha256,plan.createdAtMillis.toString(),
            plan.expiresAtMillis.toString(),plan.createdElapsedRealtimeMillis.toString(),plan.expiresElapsedRealtimeMillis.toString())
        fields.forEachIndexed { i,s -> setProperty("p$i",s) }
        consent?.let { setProperty("consent",it.macSha256);setProperty("noRebootAcknowledged",it.noRebootAcknowledged.toString()) }
    }.let { p -> java.io.StringWriter().also { p.store(it,"Authenticated immutable stock restore request") }.toString() }

    fun decode(text:String):Pair<StockRestorePlan,RestoreConsent?> {
        require(text.length<=16_384)
        val p=java.util.Properties().apply { load(java.io.StringReader(text)) };require(p.getProperty("schema")=="2")
        fun s(i:Int)=requireNotNull(p.getProperty("p$i")).also { require(it.length<=2048) }
        val plan=StockRestorePlan(s(0),s(1),ExecutionMode.valueOf(s(2)),s(3),s(4),s(5),s(6),s(7),s(8),s(9),s(10).toLong(),
            s(11),s(12),s(13).toLong(),s(14).toLong(),s(15).toLong(),s(16).toLong())
        require(plan.transactionId.matches(Regex("[a-f0-9-]{36}")) && plan.runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(plan.slot in setOf("_a","_b") && plan.imageBytes in 4096..128L*1024*1024)
        require(plan.stockSha256.matches(Regex("[a-f0-9]{64}")) && plan.previousImageSha256.matches(Regex("[a-f0-9]{64}")))
        require(plan.expiresAtMillis-plan.createdAtMillis==StockRestorePolicy.CONSENT_LIFETIME_MILLIS)
        val consent=p.getProperty("consent")?.let { require(it.matches(Regex("[a-f0-9]{64}")));RestoreConsent(plan.transactionId,it,p.getProperty("noRebootAcknowledged")=="true") }
        return plan to consent
    }
}

enum class RestoreCheckpoint { PREPARED, BACKUP_VERIFIED, TOKEN_CONSUMED, WRITE_STARTED, STOCK_VERIFIED, SOURCE_UNCHANGED_VERIFIED, REPREPARATION_READY, RECOVERY_REQUIRED }
data class StockRestoreResult(val transactionId: String, val checkpoint: RestoreCheckpoint, val observedSha256: String,
                              val backupSha256: String, val stockRestored: Boolean, val errorCode: String? = null)

/** Implementations are either strictly private regular-file sandboxes or the audited root helper. */
interface StockRestoreBackend {
    val mode: ExecutionMode
    fun execute(plan: StockRestorePlan, consent: RestoreConsent): StockRestoreResult
    /** Recovery only observes. It must never replay a consumed confirmation or write automatically. */
    fun recover(plan: StockRestorePlan): StockRestoreResult
}
