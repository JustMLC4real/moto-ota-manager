package nl.moto.ota.core.update

import nl.moto.ota.core.*
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import java.util.UUID

enum class UpdateStage {
    IDLE, PRECHECK_BLOCKED, STOCK_PREPARATION, STOCK_CONFIRMATION, STOCK_WRITE_RUNNING,
    STOCK_RESTORED, AWAITING_OFFICIAL_OTA, OTA_APPLYING, MAGISK_REQUIRED,
    MAGISK_REPORTED_UNVERIFIED, REBOOT_BY_SYSTEM_UPDATER, AWAITING_NEW_BOOT,
    TARGET_BOOTED, MERGING, FINAL_CHECKS, COMPLETE, COMPLETE_REVIEW, FAILED, ROLLBACK, RECOVERY_REQUIRED
}
enum class StepEvidence { NONE, USER_REPORTED, OBSERVED, CRYPTOGRAPHIC_IMAGE, OBSERVED_AFTER_BOOT }
enum class UpdateRoute { OFFICIAL_MOTOROLA, AUTHENTICATED_PACKAGE }

data class ObservedUpdateTarget(
    val build: String,
    /** Retained as declared metadata; never normalized into the running system namespace. */
    val declaredFingerprint: String = "",
    val packageSha256: String = "",
    val packageVerified: Boolean = false
)

data class TargetMagiskProof(
    val targetSlot: String,
    val imageSha256: String,
    val expectedStockSha1: String,
    val embeddedStockSha1: String,
    val imageIsMagiskPatched: Boolean,
    val verifiedMagiskTool: Boolean,
    val magiskCode: Int,
    val capturedAtMillis: Long
)

/** A durable journal of expectations and separate observations, not an executable script. */
data class UpdateRun(
    val id: String = "",
    val stage: UpdateStage = UpdateStage.IDLE,
    val route: UpdateRoute = UpdateRoute.OFFICIAL_MOTOROLA,
    val sourceBuild: String = "",
    val sourceFingerprint: String = "",
    val sourceSlot: String = "",
    val sourceBootId: String = "",
    val targetBuild: String = "",
    val declaredTargetFingerprint: String = "",
    val observedTargetFingerprint: String = "",
    val targetSlot: String = "",
    val packageSha256: String = "",
    val packageVerified: Boolean = false,
    /** Reserved for a future authenticated raw-target-image adapter, not self-declared proof input. */
    val authenticatedTargetStockSha1: String = "",
    val stockSha256: String = "",
    val stockRestoreEvidence: StepEvidence = StepEvidence.NONE,
    val patchedBackupSha256: String = "",
    val restoreTransactionId: String = "",
    val otaAppliedObserved: Boolean = false,
    val otaAuthenticityDelegatedToOfficialUpdater: Boolean = false,
    val magiskEvidence: StepEvidence = StepEvidence.NONE,
    val rebootConsentRecorded: Boolean = false,
    val newStockBackupVerified: Boolean = false,
    val modulesBaseline: List<String> = emptyList(),
    val baselineUnlocked: Boolean? = null,
    val baselineVerifiedBoot: String = "",
    val lastObservationMillis: Long = 0,
    val lastObservedBootId: String = "",
    val lastObservedBuild: String = "",
    val lastObservedSlot: String = "",
    val lastObservedEngine: EngineState = EngineState.UNKNOWN,
    val lastObservedSnapshot: SnapshotState = SnapshotState.UNKNOWN,
    val lastActiveImageSha256: String = "",
    val lastActiveHashAtMillis: Long = 0,
    val simulated: Boolean = false,
    val errorCode: String? = null,
    val message: String = "Nog geen update gestart.",
    val presentationKey: String? = null,
    val presentationArgs: List<String> = emptyList(),
    val sourceModel: String = "",
    val sourceDevice: String = "",
    val sourceChannel: String = "",
    val stockPartitionType: String = "init_boot"
) {
    val criticalSession: Boolean get() = (stage==UpdateStage.STOCK_WRITE_RUNNING ||
        (stage==UpdateStage.RECOVERY_REQUIRED && restoreTransactionId.isNotBlank()) ||
        stockRestoreEvidence in setOf(StepEvidence.OBSERVED, StepEvidence.CRYPTOGRAPHIC_IMAGE)) &&
        stage !in setOf(UpdateStage.COMPLETE, UpdateStage.COMPLETE_REVIEW, UpdateStage.ROLLBACK)

    fun encode(): String = Properties().apply {
        setProperty("schema", "2"); setProperty("id", id); setProperty("stage", stage.name); setProperty("route", route.name)
        setProperty("sourceBuild", sourceBuild); setProperty("sourceFingerprint", sourceFingerprint)
        setProperty("sourceSlot", sourceSlot); setProperty("sourceBootId", sourceBootId)
        setProperty("targetBuild", targetBuild); setProperty("declaredTargetFingerprint", declaredTargetFingerprint)
        setProperty("observedTargetFingerprint", observedTargetFingerprint); setProperty("targetSlot", targetSlot)
        setProperty("packageSha256", packageSha256); setProperty("packageVerified", packageVerified.toString())
        setProperty("authenticatedTargetStockSha1",authenticatedTargetStockSha1)
        setProperty("stockSha256", stockSha256); setProperty("stockRestoreEvidence", stockRestoreEvidence.name)
        setProperty("patchedBackupSha256", patchedBackupSha256); setProperty("restoreTransactionId", restoreTransactionId)
        setProperty("otaAppliedObserved", otaAppliedObserved.toString()); setProperty("officialAuthority", otaAuthenticityDelegatedToOfficialUpdater.toString())
        setProperty("magiskEvidence", magiskEvidence.name); setProperty("rebootConsent", rebootConsentRecorded.toString())
        setProperty("newStockBackupVerified", newStockBackupVerified.toString()); setProperty("modules", modulesBaseline.joinToString("\n"))
        setProperty("baselineUnlocked", baselineUnlocked?.toString() ?: "unknown"); setProperty("baselineVerifiedBoot", baselineVerifiedBoot)
        setProperty("lastObservationMillis", lastObservationMillis.toString()); setProperty("simulated", simulated.toString())
        setProperty("lastObservedBootId",lastObservedBootId);setProperty("lastObservedBuild",lastObservedBuild);setProperty("lastObservedSlot",lastObservedSlot)
        setProperty("lastObservedEngine",lastObservedEngine.name);setProperty("lastObservedSnapshot",lastObservedSnapshot.name)
        setProperty("lastActiveImageSha256",lastActiveImageSha256);setProperty("lastActiveHashAtMillis",lastActiveHashAtMillis.toString())
        setProperty("error", errorCode.orEmpty()); setProperty("message", message)
        setProperty("sourceModel",sourceModel);setProperty("sourceDevice",sourceDevice);setProperty("sourceChannel",sourceChannel);setProperty("stockPartitionType",stockPartitionType)
        setProperty("presentationKey",presentationKey.orEmpty());setProperty("presentationArgCount",presentationArgs.size.toString())
        presentationArgs.forEachIndexed { index,value ->setProperty("presentationArg.$index",value)}
    }.let { p -> StringWriter().also { p.store(it, "Moto OTA Manager durable guided update; no stored state substitutes for live evidence") }.toString() }

    companion object {
        fun decode(text: String?): UpdateRun {
            if (text==null) return UpdateRun()
            return runCatching {
                require(text.isNotBlank())
                require(text.length <= 131_072)
                val p = Properties().apply { load(StringReader(text)) }
                require(p.getProperty("schema") == "2")
                fun s(name: String) = requireNotNull(p.getProperty(name))
                fun b(name: String) = when (s(name)) { "true" -> true; "false" -> false; else -> error("Malformed boolean") }
                UpdateRun(id=s("id"), stage=UpdateStage.valueOf(s("stage")), route=UpdateRoute.valueOf(s("route")),
                    sourceBuild=s("sourceBuild"), sourceFingerprint=s("sourceFingerprint"), sourceSlot=s("sourceSlot"), sourceBootId=s("sourceBootId"),
                    targetBuild=s("targetBuild"), declaredTargetFingerprint=s("declaredTargetFingerprint"), observedTargetFingerprint=s("observedTargetFingerprint"),
                    targetSlot=s("targetSlot"), packageSha256=s("packageSha256"), packageVerified=b("packageVerified"), stockSha256=s("stockSha256"),
                    stockRestoreEvidence=StepEvidence.valueOf(s("stockRestoreEvidence")), patchedBackupSha256=s("patchedBackupSha256"), restoreTransactionId=s("restoreTransactionId"),
                    otaAppliedObserved=b("otaAppliedObserved"), otaAuthenticityDelegatedToOfficialUpdater=b("officialAuthority"), magiskEvidence=StepEvidence.valueOf(s("magiskEvidence")),
                    rebootConsentRecorded=b("rebootConsent"), newStockBackupVerified=b("newStockBackupVerified"), modulesBaseline=s("modules").split('\n').filter(String::isNotBlank),
                    baselineUnlocked=when(s("baselineUnlocked")){"true"->true;"false"->false;"unknown"->null;else->error("lock")}, baselineVerifiedBoot=s("baselineVerifiedBoot"),
                    lastObservationMillis=s("lastObservationMillis").toLong(), simulated=b("simulated"), errorCode=s("error").ifBlank { null }, message=s("message"),
                    lastObservedBootId=p.getProperty("lastObservedBootId", ""),lastObservedBuild=p.getProperty("lastObservedBuild", ""),lastObservedSlot=p.getProperty("lastObservedSlot", ""),
                    lastObservedEngine=EngineState.valueOf(p.getProperty("lastObservedEngine","UNKNOWN")),lastObservedSnapshot=SnapshotState.valueOf(p.getProperty("lastObservedSnapshot","UNKNOWN")),
                    lastActiveImageSha256=p.getProperty("lastActiveImageSha256",""),lastActiveHashAtMillis=p.getProperty("lastActiveHashAtMillis","0").toLong(),
                    authenticatedTargetStockSha1=p.getProperty("authenticatedTargetStockSha1",""),
                    sourceModel=p.getProperty("sourceModel",""),sourceDevice=p.getProperty("sourceDevice",""),sourceChannel=p.getProperty("sourceChannel",""),stockPartitionType=p.getProperty("stockPartitionType","init_boot").also{require(it in setOf("boot","init_boot"))},
                    presentationKey=p.getProperty("presentationKey","").ifBlank {null},
                    presentationArgs=p.getProperty("presentationArgCount","0").toInt().also{require(it in 0..16)}.let {count->
                        (0 until count).map {index->requireNotNull(p.getProperty("presentationArg.$index")).also{require(it.length<=2048)}}})
                    .also { r ->
                        if (r.stage !in setOf(UpdateStage.IDLE,UpdateStage.PRECHECK_BLOCKED,UpdateStage.RECOVERY_REQUIRED)) {
                            require(r.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
                            require(r.sourceSlot in setOf("_a","_b") && r.targetSlot in setOf("_a","_b") && r.sourceSlot != r.targetSlot)
                            require(r.sourceBootId.isNotBlank() && r.sourceBuild.isNotBlank() && r.targetBuild.isNotBlank())
                            require(r.stockSha256.matches(Regex("[a-f0-9]{64}")))
                        }
                    }
            }.getOrElse { UpdateRun(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="JOURNAL_INVALID",message="Workflowgegevens zijn beschadigd of van een onbekende versie. Lees het toestel opnieuw uit; voer niets automatisch opnieuw uit.") }
        }
    }
}

object UpdateCoordinator {
    private fun safeSnapshot(state:SnapshotState)=state in setOf(SnapshotState.NONE,SnapshotState.NOT_APPLICABLE)
    private fun matchesSourceDevice(run:UpdateRun,device:DeviceState):Boolean =
        device.manufacturer.equals("motorola",true) && device.identityConflicts.isEmpty() &&
        if(run.sourceModel.isBlank() || run.sourceDevice.isBlank() || run.sourceChannel.isBlank()) device.exactProfileMatch
        else device.model==run.sourceModel && device.device==run.sourceDevice && device.channel==run.sourceChannel
    private fun planMatchesSource(run:UpdateRun,plan:StockRestorePlan):Boolean =
        if(run.sourceModel.isBlank()) plan.model=="XT2607-1" && plan.device=="merc" && plan.channel=="reteu" && plan.partitionType=="init_boot"
        else plan.model==run.sourceModel && plan.device==run.sourceDevice && plan.channel==run.sourceChannel && plan.partitionType==run.stockPartitionType
    /** Explicit sandbox entry point. A simulated run never becomes a live run. */
    fun startSandboxGuidedRun(device:DeviceState,backup:BackupValidation?,target:ObservedUpdateTarget,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        require(device.simulated)
        return startOfficialGuidedRun(device.copy(simulated=false),backup,target,nowMillis).copy(simulated=true)
    }
    fun startOfficialGuidedRun(device: DeviceState, backup: BackupValidation?, target: ObservedUpdateTarget,
                               nowMillis: Long = System.currentTimeMillis(), runId: String = UUID.randomUUID().toString()): UpdateRun {
        require(runCatching { UUID.fromString(runId).toString()==runId }.getOrDefault(false))
        val checks = Readiness.evaluate(device, backup, nowMillis=nowMillis)
        val problems=checks.blockers.toMutableList()
        if (target.build.isBlank() || target.build==device.build) problems += CheckResult("TARGET", "Selecteer een werkelijk andere doelbuild uit de officiële updater of een geverifieerd pakket.")
        if (device.simulated) problems += CheckResult("SIMULATION", "Een demo kan geen echte update begeleiden.")
        if (problems.isNotEmpty()) return UpdateRun(stage=UpdateStage.PRECHECK_BLOCKED,errorCode=problems.first().code,message=problems.joinToString("\n"){it.message})
        return UpdateRun(id=runId,sourceModel=device.model,sourceDevice=device.device,sourceChannel=device.channel,stockPartitionType=backup!!.partitionType,stage=UpdateStage.STOCK_PREPARATION,sourceBuild=device.build,sourceFingerprint=device.fingerprint,
            sourceSlot=device.slot,sourceBootId=device.bootId,targetBuild=target.build,declaredTargetFingerprint=target.declaredFingerprint,
            targetSlot=device.inactiveSlot,packageSha256=target.packageSha256,packageVerified=target.packageVerified,stockSha256=backup!!.sha256,
            modulesBaseline=device.modules.sorted(),baselineUnlocked=device.bootloaderUnlocked,baselineVerifiedBoot=device.verifiedBootState,
            lastObservationMillis=device.capturedAtMillis,lastObservedBootId=device.bootId,lastObservedBuild=device.build,lastObservedSlot=device.slot,
            lastObservedEngine=device.engine,lastObservedSnapshot=device.snapshot,message="Officiële begeleide update gestart. De metadata is geen installatiebewijs. Controleer de actuele stock-image en herstelplan; herstart niet tussen stockherstel en Magisk na OTA.")
    }

    fun startAuthenticatedPackageRun(device:DeviceState,backup:BackupValidation?,report:FirmwareReport,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if (!report.readyForOfficialReview) return UpdateRun(stage=UpdateStage.PRECHECK_BLOCKED,errorCode="PACKAGE_UNAPPROVED",message="Pakketondertekening, inhoud, bronvoorwaarden en kanaal zijn niet allemaal bevestigd. Gebruik de officiële Motorola-route of onderzoek de blokkade.")
        return startOfficialGuidedRun(device,backup,ObservedUpdateTarget(report.targetBuild,report.targetFingerprint,report.sha256,true),nowMillis).copy(route=UpdateRoute.AUTHENTICATED_PACKAGE)
    }
    /** Attach authentication facts only; this does not approve compatibility or installation. */
    fun attachVerifiedPackage(run:UpdateRun,report:FirmwareReport,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if(run.route!=UpdateRoute.OFFICIAL_MOTOROLA || run.stage !in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.STOCK_CONFIRMATION,UpdateStage.STOCK_RESTORED,UpdateStage.AWAITING_OFFICIAL_OTA) ||
            !freshSource(run,nowMillis) || run.lastObservedEngine!=EngineState.IDLE || !safeSnapshot(run.lastObservedSnapshot) ||
            !report.signatureVerified || !report.structureValid || !report.payloadHashValid || !report.sha256.matches(Regex("[a-f0-9]{64}")) || report.targetBuild!=run.targetBuild)return run
        return run.copy(packageSha256=report.sha256,packageVerified=true,
            declaredTargetFingerprint=report.targetFingerprint,
            message="Geauthenticeerd pakket gekoppeld aan de verwachte doelbuild. Eventuele metadata-/kanaalblokkades blijven in het pakketrapport staan; Motorola beslist over installatie en compatibiliteit.")
    }

    fun restorePrepared(run:UpdateRun,plan:StockRestorePlan):UpdateRun {
        if (run.stage !in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.STOCK_CONFIRMATION))return run
        if (run.id!=plan.runId || run.sourceBootId!=plan.bootId || run.sourceSlot!=plan.slot || run.stockSha256!=plan.stockSha256 ||
            run.sourceBuild!=plan.build || run.sourceFingerprint!=plan.fingerprint || !planMatchesSource(run,plan) ||
            run.simulated!=(plan.mode==ExecutionMode.SANDBOX)) return fail(run,"RESTORE_PLAN_MISMATCH","Het herstelplan hoort niet bij deze workflow.")
        return run.copy(stage=UpdateStage.STOCK_CONFIRMATION,restoreTransactionId=plan.transactionId,patchedBackupSha256=plan.previousImageSha256,message=plan.confirmationSummary)
    }

    /** Persist this phase before dispatching the helper. Reopening it never replays a write. */
    fun restoreDispatching(run:UpdateRun):UpdateRun = if(run.stage==UpdateStage.STOCK_CONFIRMATION) run.copy(stage=UpdateStage.STOCK_WRITE_RUNNING,
        message="Stockherstel is expliciet bevestigd. Bij onderbreking alleen opnieuw uitlezen; niet automatisch herhalen of herstarten.") else run

    fun restoreFinished(run:UpdateRun,result:StockRestoreResult):UpdateRun {
        if(run.stage !in setOf(UpdateStage.STOCK_WRITE_RUNNING,UpdateStage.STOCK_CONFIRMATION,UpdateStage.RECOVERY_REQUIRED))return run
        if(result.transactionId!=run.restoreTransactionId)return fail(run,"RESTORE_RECEIPT_MISMATCH","Herstelresultaat hoort bij een andere transactie.")
        return if(result.stockRestored && result.checkpoint==RestoreCheckpoint.STOCK_VERIFIED && result.observedSha256==run.stockSha256 && result.backupSha256==run.patchedBackupSha256) run.copy(
            stage=UpdateStage.STOCK_RESTORED,stockRestoreEvidence=StepEvidence.CRYPTOGRAPHIC_IMAGE,errorCode=null,lastActiveImageSha256=result.observedSha256,lastActiveHashAtMillis=System.currentTimeMillis(),
            message="De oorspronkelijke init_boot is teruggelezen en hashgelijk aan de actuele geauthenticeerde stock-image. De gepatchte image is bewaard. Open Motorola voor de OTA en herstart nog niet.")
        else run.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode=result.errorCode?:"RESTORE_NOT_PROVEN",message="Stockherstel is niet volledig bewezen. De bewaarde image blijft beschikbaar. Herstart niet; lees de partitie opnieuw uit en kies geen automatische herstelactie.")
    }

    fun reportManualStockRestore(run:UpdateRun):UpdateRun = if(run.stage in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.STOCK_CONFIRMATION))
        run.copy(stockRestoreEvidence=StepEvidence.USER_REPORTED,message="Stockherstel is door de gebruiker gemeld; meet de actieve init_boot-hash voordat de workflow dit bevestigt.") else run

    fun observeStockHash(run:UpdateRun,bootId:String,slot:String,sha256:String):UpdateRun {
        if(run.stage !in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.STOCK_CONFIRMATION,UpdateStage.STOCK_WRITE_RUNNING,UpdateStage.RECOVERY_REQUIRED,UpdateStage.STOCK_RESTORED))return run
        if(run.restoreTransactionId.isNotBlank() && run.stage in setOf(UpdateStage.STOCK_WRITE_RUNNING,UpdateStage.RECOVERY_REQUIRED))return run.copy(message="Controleer na onderbroken stockherstel ook de bewaarde gepatchte image via de uitlezende herstelcontrole; alleen een actieve hash is onvoldoende.")
        if(!freshSource(run,System.currentTimeMillis()))return run.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="OBSERVATION_INVALID",message="Een verse en ongewijzigde bronbuild/opstartsessie ontbreekt; lees eerst opnieuw uit.")
        if(bootId!=run.sourceBootId || slot!=run.sourceSlot)return run.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="BOOT_CHANGED",message="Opstartsessie of slot veranderde; voer geen stockherstel uit.")
        return if(sha256==run.stockSha256)run.copy(stage=UpdateStage.STOCK_RESTORED,stockRestoreEvidence=StepEvidence.OBSERVED,lastActiveImageSha256=sha256,lastActiveHashAtMillis=System.currentTimeMillis(),
            message="Actieve init_boot komt hashgelijk overeen met de geauthenticeerde huidige stock-image. Gebruik de officiële updater; herstart nog niet.")
        else run.copy(message="De actieve init_boot is nog niet gelijk aan de verwachte stock-image; de gemelde restore is niet bevestigd.")
    }

    fun openOfficialOta(run:UpdateRun,nowMillis:Long=System.currentTimeMillis()):UpdateRun = if(run.stage in setOf(UpdateStage.STOCK_RESTORED,UpdateStage.AWAITING_OFFICIAL_OTA,UpdateStage.OTA_APPLYING) &&
        freshSource(run,nowMillis) && run.lastActiveImageSha256==run.stockSha256 && nowMillis-run.lastActiveHashAtMillis in 0..120_000 && run.lastObservedEngine in setOf(EngineState.IDLE,EngineState.DOWNLOADING,EngineState.VERIFYING,EngineState.FINALIZING) &&
        (run.lastObservedEngine!=EngineState.IDLE || safeSnapshot(run.lastObservedSnapshot)) && run.stockRestoreEvidence in setOf(StepEvidence.OBSERVED,StepEvidence.CRYPTOGRAPHIC_IMAGE))run.copy(
        stage=UpdateStage.AWAITING_OFFICIAL_OTA,otaAuthenticityDelegatedToOfficialUpdater=true,
        message="Gebruik Motorola's officiële updatecontrole en installatie. Motorola verifieert zijn updateketen. Wacht op voltooiing, maar druk nog niet op Herstarten.")else run

    fun reportMagiskInstalled(run:UpdateRun):UpdateRun = if(run.otaAppliedObserved && freshSource(run,System.currentTimeMillis()) && run.lastObservedEngine==EngineState.UPDATED_NEED_REBOOT && run.magiskEvidence!=StepEvidence.CRYPTOGRAPHIC_IMAGE && run.stage in setOf(UpdateStage.MAGISK_REQUIRED,UpdateStage.MAGISK_REPORTED_UNVERIFIED))run.copy(
        stage=UpdateStage.MAGISK_REPORTED_UNVERIFIED,magiskEvidence=StepEvidence.USER_REPORTED,
        message="Magisk naar het inactieve slot is door de gebruiker gemeld, niet onafhankelijk bewezen. Controleer de officiële Magisk-log: doelslot ${run.targetSlot}, init_boot${run.targetSlot} en All done. De definitieve rootcontrole volgt na de nieuwe boot.")else run

    fun automaticOtaDispatching(run:UpdateRun):UpdateRun {
        require(run.stage==UpdateStage.AWAITING_OFFICIAL_OTA && run.packageVerified &&
            run.stockRestoreEvidence==StepEvidence.CRYPTOGRAPHIC_IMAGE && freshSource(run,System.currentTimeMillis()) &&
            run.lastObservedEngine==EngineState.IDLE && safeSnapshot(run.lastObservedSnapshot))
        return run.copy(stage=UpdateStage.OTA_APPLYING,otaAuthenticityDelegatedToOfficialUpdater=false,
            presentationKey=null,presentationArgs=emptyList(),message="The authenticated official payload is being submitted to Android UpdateEngine. Do not restart.")
    }

    /** The platform adapter accepts only its independently verified, HMAC-bound root receipt. */
    fun authenticatedTargetStock(run:UpdateRun,runId:String,packageSha256:String,slot:String,sha1:String):UpdateRun {
        require(run.id==runId && run.packageVerified && run.packageSha256==packageSha256 && run.targetSlot==slot &&
            sha1.matches(Regex("[a-f0-9]{40}")) && run.stage==UpdateStage.MAGISK_REQUIRED && run.otaAppliedObserved &&
            freshSource(run,System.currentTimeMillis()) && run.lastObservedEngine==EngineState.UPDATED_NEED_REBOOT)
        require(run.authenticatedTargetStockSha1.isBlank() || run.authenticatedTargetStockSha1==sha1)
        return run.copy(authenticatedTargetStockSha1=sha1)
    }

    fun observeMagiskProof(run:UpdateRun,proof:TargetMagiskProof,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if(run.stage !in setOf(UpdateStage.MAGISK_REQUIRED,UpdateStage.MAGISK_REPORTED_UNVERIFIED,UpdateStage.REBOOT_BY_SYSTEM_UPDATER))return run
        if(!run.otaAppliedObserved || !run.authenticatedTargetStockSha1.matches(Regex("[a-f0-9]{40}")) || proof.expectedStockSha1!=run.authenticatedTargetStockSha1 ||
            !freshSource(run,nowMillis) || run.lastObservedEngine!=EngineState.UPDATED_NEED_REBOOT || proof.targetSlot!=run.targetSlot || !proof.imageIsMagiskPatched || !proof.verifiedMagiskTool || proof.magiskCode!=31100 ||
            !proof.imageSha256.matches(Regex("[a-f0-9]{64}")) || !proof.expectedStockSha1.matches(Regex("[a-f0-9]{40}")) ||
            proof.embeddedStockSha1!=proof.expectedStockSha1 || proof.capturedAtMillis<=0 || nowMillis-proof.capturedAtMillis !in 0..30_000) return run.copy(
            message="Magisk-imagebewijs is onvolledig of niet bij de verwachte doelslot/stock-image; handmatige melding blijft onbewezen.")
        return run.copy(stage=UpdateStage.REBOOT_BY_SYSTEM_UPDATER,magiskEvidence=StepEvidence.CRYPTOGRAPHIC_IMAGE,
            message="Doelimage bevat onafhankelijk gecontroleerde Magisk-patching en de verwachte stock-SHA1. Bevestig de herstart afzonderlijk in Motorola; de app herstart niet zelf.")
    }

    fun acknowledgeReboot(run:UpdateRun,explicitConsent:Boolean):UpdateRun {
        if(!explicitConsent || run.stage !in setOf(UpdateStage.MAGISK_REPORTED_UNVERIFIED,UpdateStage.REBOOT_BY_SYSTEM_UPDATER) ||
            !freshSource(run,System.currentTimeMillis()) || run.lastObservedEngine!=EngineState.UPDATED_NEED_REBOOT || !run.otaAppliedObserved || run.magiskEvidence==StepEvidence.NONE) return run
        return run.copy(stage=UpdateStage.AWAITING_NEW_BOOT,rebootConsentRecorded=true,
            message=if(run.magiskEvidence==StepEvidence.USER_REPORTED)"Herstart is expliciet erkend; Magisk is vóór boot nog alleen handmatig gemeld. Voer de herstart zelf uit via Motorola en controleer daarna root en merge."
            else "Herstart is expliciet erkend. Voer hem zelf uit via Motorola; de app controleert de nieuwe boot, root en merge na terugkeer.")
    }

    fun fail(run:UpdateRun,code:String,message:String):UpdateRun=run.copy(stage=UpdateStage.FAILED,errorCode=code,message=message)
    fun cancelBeforeRestore(run:UpdateRun):UpdateRun = if(run.stage in setOf(UpdateStage.STOCK_PREPARATION,UpdateStage.PRECHECK_BLOCKED) &&
        run.stockRestoreEvidence==StepEvidence.NONE && run.restoreTransactionId.isBlank() && !run.otaAppliedObserved)UpdateRun(simulated=run.simulated)else run
    /** Explicit observation retry for recoverable final-check failures; never repeats an operation. */
    fun retryFinalChecks(run:UpdateRun,device:DeviceState,newStockBackup:BackupValidation?=null,activeImageSha256:String?=null,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if(run.stage !in setOf(UpdateStage.FAILED,UpdateStage.RECOVERY_REQUIRED) || run.errorCode !in setOf("ROOT_MISSING","MODULE_INVENTORY_CHANGED","MAGISK_VERSION") ||
            !run.otaAppliedObserved || device.bootId==run.sourceBootId || device.build!=run.targetBuild || device.slot!=run.targetSlot)return run
        return reconcile(run.copy(stage=UpdateStage.TARGET_BOOTED,errorCode=null),device,newStockBackup,activeImageSha256,nowMillis)
    }
    /** A fresh observation may release only the official manual Magisk retry, never an installer. */
    fun retryMagiskStep(run:UpdateRun,device:DeviceState,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if(run.stage!=UpdateStage.FAILED || run.errorCode!="MAGISK_INACTIVE_FAILED" || !run.otaAppliedObserved ||
            device.simulated!=run.simulated || !matchesSourceDevice(run,device) || !DeviceCapabilities.knownRestoreRuntime(device) ||
            device.bootId!=run.sourceBootId || device.slot!=run.sourceSlot || device.build!=run.sourceBuild || device.fingerprint!=run.sourceFingerprint ||
            device.capturedAtMillis<=0 || nowMillis-device.capturedAtMillis !in 0..120_000 || !device.rootChecked || !device.rootGranted || device.magiskCode!=31100 ||
            device.engine!=EngineState.UPDATED_NEED_REBOOT || device.snapshot!=SnapshotState.UNVERIFIED || device.virtualAb!=true)return run
        return run.copy(stage=UpdateStage.MAGISK_REQUIRED,magiskEvidence=StepEvidence.NONE,rebootConsentRecorded=false,errorCode=null,
            lastObservationMillis=device.capturedAtMillis,lastObservedBootId=device.bootId,lastObservedSlot=device.slot,lastObservedBuild=device.build,
            lastObservedEngine=device.engine,lastObservedSnapshot=device.snapshot,
            message="Dezelfde bronboot en toegepaste OTA zijn opnieuw uitgelezen; root/Magisk 31.1 werkt nog en de snapshot wacht op de doelboot. Probeer uitsluitend de officiële Magisk-installatie naar de verwachte inactieve slot opnieuw. De app schrijft niets en herstart niet.")
    }
    /** Explicitly releases an interrupted plan only after locked source/backup remeasurement. */
    fun releasePreparation(run:UpdateRun,plan:StockRestorePlan,result:StockRestoreResult,environment:RestoreEnvironment,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if(run.stage!=UpdateStage.RECOVERY_REQUIRED || run.id!=plan.runId || run.restoreTransactionId!=plan.transactionId ||
            result.transactionId!=plan.transactionId || result.checkpoint!=RestoreCheckpoint.REPREPARATION_READY || result.stockRestored ||
            result.observedSha256!=plan.previousImageSha256 || result.backupSha256!=plan.previousImageSha256 ||
            environment.activeImageSha256!=plan.previousImageSha256 || environment.stockValidation.sha256!=run.stockSha256 || plan.stockSha256!=run.stockSha256 || environment.partitionBytes!=plan.imageBytes ||
            StockRestorePolicy.blockers(environment,nowMillis).isNotEmpty())return run
        val d=environment.device
        if(d.bootId!=run.sourceBootId || d.slot!=run.sourceSlot || d.build!=run.sourceBuild || d.fingerprint!=run.sourceFingerprint ||
            !d.modules.containsAll(run.modulesBaseline) || run.simulated!=d.simulated || run.simulated!=(plan.mode==ExecutionMode.SANDBOX))return run
        return run.copy(stage=UpdateStage.STOCK_PREPARATION,restoreTransactionId="",patchedBackupSha256="",stockRestoreEvidence=StepEvidence.NONE,
            errorCode=null,lastObservationMillis=d.capturedAtMillis,lastObservedBootId=d.bootId,lastObservedBuild=d.build,lastObservedSlot=d.slot,
            lastObservedEngine=d.engine,lastObservedSnapshot=d.snapshot,lastActiveImageSha256=environment.activeImageSha256,lastActiveHashAtMillis=d.capturedAtMillis,
            message="De oorspronkelijke gepatchte bron-image en haar volledige back-up zijn opnieuw hashbevestigd; de oude bevestiging is ongeldig gemaakt. Bereken en beoordeel een nieuwe transactie met nieuwe toestemming. Er is geen partitie opnieuw geschreven.")
    }
    private fun freshSource(run:UpdateRun,now:Long):Boolean=run.lastObservedBootId==run.sourceBootId && run.lastObservedSlot==run.sourceSlot &&
        run.lastObservedBuild==run.sourceBuild && run.lastObservationMillis>0 && now-run.lastObservationMillis in 0..120_000

    fun reconcile(run:UpdateRun,device:DeviceState,newStockBackup:BackupValidation?=null,activeImageSha256:String?=null,nowMillis:Long=System.currentTimeMillis()):UpdateRun {
        if(run.stage in setOf(UpdateStage.IDLE,UpdateStage.PRECHECK_BLOCKED,UpdateStage.FAILED,UpdateStage.ROLLBACK))return run
        if(run.simulated!=device.simulated)return run.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="MODE_MISMATCH",message="Simulatie en echte observaties mogen niet worden gemengd.")
        if(!matchesSourceDevice(run,device) || device.capturedAtMillis<=0 || nowMillis-device.capturedAtMillis !in 0..120_000 || device.bootId.isBlank())return run.copy(
            stage=UpdateStage.RECOVERY_REQUIRED,errorCode="OBSERVATION_INVALID",message="Actuele toestelidentiteit of verse opstartsessie ontbreekt. Lees opnieuw uit; voer geen actie automatisch opnieuw uit.")
        if(device.engine==EngineState.ERROR || device.snapshot==SnapshotState.FAILED)return fail(run,"ENGINE_OR_MERGE_ERROR","De actuele engine of snapshotstatus meldt een fout. Niet herstarten of automatisch terugschrijven; verzamel diagnostiek.")
        val sameBoot=device.bootId==run.sourceBootId
        val hash=activeImageSha256?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        val updated=run.copy(lastObservationMillis=device.capturedAtMillis,lastObservedBootId=device.bootId,lastObservedBuild=device.build,
            lastObservedSlot=device.slot,lastObservedEngine=device.engine,lastObservedSnapshot=device.snapshot,
            lastActiveImageSha256=hash?:run.lastActiveImageSha256,lastActiveHashAtMillis=if(hash!=null)device.capturedAtMillis else run.lastActiveHashAtMillis)
        if(sameBoot){
            if(device.slot!=run.sourceSlot || device.build!=run.sourceBuild || device.fingerprint!=run.sourceFingerprint)return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="CONTRADICTORY_SOURCE",message="Build, exacte systeemfingerprint of slot veranderde binnen dezelfde boot; waarnemingen zijn tegenstrijdig.")
            if(run.stockRestoreEvidence in setOf(StepEvidence.OBSERVED,StepEvidence.CRYPTOGRAPHIC_IMAGE) && hash!=null && hash!=run.stockSha256 &&
                run.stage in setOf(UpdateStage.STOCK_RESTORED,UpdateStage.AWAITING_OFFICIAL_OTA,UpdateStage.OTA_APPLYING))return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,
                    stockRestoreEvidence=StepEvidence.NONE,errorCode="ACTIVE_IMAGE_CHANGED",message="De actieve init_boot-hash wijkt af van de bevestigde stock-image. Eerder herstelbewijs vervalt. Start geen OTA of automatische schrijfactie.")
            if(run.stage==UpdateStage.STOCK_WRITE_RUNNING){
                return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="RESTORE_INTERRUPTED",message=if(hash==run.stockSha256)
                    "De actieve stockhash is na onderbreking bevestigd; controleer via de uitlezende herstelcontrole ook de volledige bewaarde gepatchte image. Geen automatische replay."
                    else "Stockherstel werd onderbroken. Geen automatische replay. Lees de partitie en de bewaarde gepatchte image uit; herstart niet.")
            }
            if(device.engine==EngineState.UPDATED_NEED_REBOOT)return updated.copy(stage=if(run.magiskEvidence==StepEvidence.NONE)UpdateStage.MAGISK_REQUIRED else if(run.rebootConsentRecorded)UpdateStage.AWAITING_NEW_BOOT else if(run.magiskEvidence==StepEvidence.CRYPTOGRAPHIC_IMAGE)UpdateStage.REBOOT_BY_SYSTEM_UPDATER else UpdateStage.MAGISK_REPORTED_UNVERIFIED,
                otaAppliedObserved=true,otaAuthenticityDelegatedToOfficialUpdater=true,message="UpdateEngine bevestigt OTA toegepast; herstart vereist. Bereid Magisk in de officiële app voor op ${run.targetSlot} en controleer de log. Herstart nog niet.")
            if(device.engine in setOf(EngineState.DOWNLOADING,EngineState.VERIFYING,EngineState.FINALIZING))return updated.copy(stage=UpdateStage.OTA_APPLYING,message="De officiële update-engine verwerkt de OTA (${device.engine}). De app annuleert of herstart dit proces niet.")
            return updated
        }
        if(device.slot==run.sourceSlot && device.build==run.sourceBuild)return updated.copy(stage=if(run.otaAppliedObserved)UpdateStage.ROLLBACK else UpdateStage.RECOVERY_REQUIRED,
            errorCode=if(run.otaAppliedObserved)"POSSIBLE_ROLLBACK" else "UNEXPECTED_REBOOT",message="Na de herstart draait nog de bronbuild/slot. Succes is niet bevestigd; onderzoek de toestand zonder automatische herstelactie.")
        if(device.slot!=run.targetSlot || device.build!=run.targetBuild)return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="WRONG_TARGET",message="De actuele build of slot wijkt af van het verwachte doel.")
        if(!DeviceCapabilities.knownRestoreRuntime(device))return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="TARGET_ANDROID_UNREVIEWED",observedTargetFingerprint=device.fingerprint,
            message="De verwachte doelbuild/slot draait, maar Android ${device.androidVersion.ifBlank { "onbekend" }} valt buiten de onderzochte Android-runtime-adapters. Geen volledige kwalificatie of volgende voorbereiding zonder nieuw platformonderzoek.")
        if(!run.otaAppliedObserved)return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="OTA_APPLICATION_UNOBSERVED",message="Doelbuild draait, maar de OTA-toepassing was niet waargenomen. Onderzoek de updateketen; claim geen volledige begeleide uitvoering.")
        if(!device.rootChecked)return updated.copy(stage=UpdateStage.TARGET_BOOTED,observedTargetFingerprint=device.fingerprint,message="Doelbuild/slot gestart; root is nog niet gemeten. Voer de uitlezende rootcontrole uit.")
        if(!device.rootGranted)return fail(updated,"ROOT_MISSING","De nieuwe build draait, maar root is niet bevestigd. Controleer roottoestemming en Magisk; er wordt niets automatisch geflasht.")
        if(device.magiskCode!=31100)return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="MAGISK_VERSION",message="Root werkt maar de Magisk-versie is niet de onderzochte 31.1.")
        if(device.snapshot in setOf(SnapshotState.MERGING,SnapshotState.INITIATED,SnapshotState.UNVERIFIED,SnapshotState.MERGE_NEEDS_REBOOT))return updated.copy(stage=UpdateStage.MERGING,
            observedTargetFingerprint=device.fingerprint,magiskEvidence=StepEvidence.OBSERVED_AFTER_BOOT,message="Nieuwe build en root bevestigd; snapshotproces ${device.snapshot} loopt nog. Start geen volgende OTA.")
        if(!DeviceCapabilities.snapshotSafe(device) || device.engine!=EngineState.IDLE || device.bootSuccessful!=true || !device.moduleInventoryKnown)return updated.copy(stage=UpdateStage.FINAL_CHECKS,
            observedTargetFingerprint=device.fingerprint,magiskEvidence=StepEvidence.OBSERVED_AFTER_BOOT,message="Nieuwe build/root bevestigd; wacht op succesvolle boot, snapshot NONE, idle-engine en module-inventaris.")
        if(!device.modules.containsAll(run.modulesBaseline))return fail(updated,"MODULE_INVENTORY_CHANGED","Een eerder waargenomen module of inschakelstatus ontbreekt. Functionele modulecompatibiliteit blijft handmatig te controleren.")
        if(device.bootloaderUnlocked!=run.baselineUnlocked || device.verifiedBootState!=run.baselineVerifiedBoot || device.fingerprint.isBlank())return updated.copy(stage=UpdateStage.RECOVERY_REQUIRED,errorCode="SECURITY_OR_IDENTITY_CHANGED",message="Kernelbootbeveiliging of firmware-identiteit ontbreekt of wijkt af.")
        val backupOk=newStockBackup?.usableFor(device)==true
        return updated.copy(stage=if(backupOk)UpdateStage.COMPLETE else UpdateStage.COMPLETE_REVIEW,observedTargetFingerprint=device.fingerprint,
            magiskEvidence=StepEvidence.OBSERVED_AFTER_BOOT,newStockBackupVerified=backupOk,errorCode=null,
            message=if(backupOk)"Doelbuild, juiste slot, root, succesvolle boot, modules, bootbeveiliging en afgeronde merge zijn werkelijk bevestigd. De nieuwe stockback-up is geauthenticeerd. Wallet en functionele modulecompatibiliteit blijven handmatig te controleren."
            else "Doelbuild, slot, root en afgeronde merge zijn bevestigd. De originele image voor deze nieuwe build is nog niet onafhankelijk geauthenticeerd; een volgende OTA blijft geblokkeerd. De gedeclareerde en gemeten fingerprints worden afzonderlijk bewaard.")
    }
}
