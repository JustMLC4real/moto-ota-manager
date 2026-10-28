package nl.moto.ota.core

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

enum class WorkflowPhase {
    IDLE, BLOCKED, READY, DOWNLOADED, PACKAGE_INSPECTED,
    AWAITING_STOCK_RESTORE, AWAITING_OTA, OTA_APPLIED,
    MAGISK_PENDING, MAGISK_REPORTED, REBOOT_REQUIRED,
    BOOTED_TARGET, MERGING, COMPLETE, FAILED, ROLLBACK, RECOVERY_REQUIRED
}

/** Persistent expectations are not observations. Manual reports cannot set COMPLETE. */
data class WorkflowState(
    val phase: WorkflowPhase = WorkflowPhase.IDLE,
    val sourceBuild: String = "",
    val sourceSlot: String = "",
    val sourceBootId: String = "",
    val targetBuild: String = "",
    val targetFingerprint: String = "",
    val targetSlot: String = "",
    val packageSha256: String = "",
    val baselineModules: List<String> = emptyList(),
    val baselineUnlocked: Boolean? = null,
    val baselineVerifiedBootState: String = "",
    val manualStockRestoreReported: Boolean = false,
    val manualMagiskReported: Boolean = false,
    val otaAppliedObserved: Boolean = false,
    val simulated: Boolean = false,
    val message: String = "Nog geen update gestart."
) {
    fun encode(): String {
        val p = Properties()
        p.setProperty("schema", "1")
        p.setProperty("phase", phase.name)
        p.setProperty("sourceBuild", sourceBuild)
        p.setProperty("sourceSlot", sourceSlot)
        p.setProperty("sourceBootId", sourceBootId)
        p.setProperty("targetBuild", targetBuild)
        p.setProperty("targetFingerprint", targetFingerprint)
        p.setProperty("targetSlot", targetSlot)
        p.setProperty("packageSha256", packageSha256)
        p.setProperty("modules", baselineModules.joinToString("\n"))
        p.setProperty("unlocked", baselineUnlocked?.toString() ?: "unknown")
        p.setProperty("verifiedBoot", baselineVerifiedBootState)
        p.setProperty("stockReported", manualStockRestoreReported.toString())
        p.setProperty("magiskReported", manualMagiskReported.toString())
        p.setProperty("otaObserved", otaAppliedObserved.toString())
        p.setProperty("simulated", simulated.toString())
        p.setProperty("message", message)
        return StringWriter().also { p.store(it, "Moto OTA Manager workflow; expectations require live reconciliation") }.toString()
    }

    companion object {
        fun decode(text: String?): WorkflowState {
            if (text.isNullOrBlank()) return WorkflowState()
            return try {
                require(text.length <= 64 * 1024)
                val p = Properties().apply { load(StringReader(text)) }
                require(p.getProperty("schema") == "1")
                fun get(key: String) = p.getProperty(key) ?: error("Missing $key")
                fun boolean(key: String) = when (get(key)) { "true" -> true; "false" -> false; else -> error("Invalid boolean") }
                val state = WorkflowState(
                    phase = WorkflowPhase.valueOf(get("phase")),
                    sourceBuild = get("sourceBuild"), sourceSlot = get("sourceSlot"), sourceBootId = get("sourceBootId"),
                    targetBuild = get("targetBuild"), targetFingerprint = get("targetFingerprint"), targetSlot = get("targetSlot"),
                    packageSha256 = get("packageSha256"), baselineModules = get("modules").split('\n').filter { it.isNotBlank() },
                    baselineUnlocked = when (get("unlocked")) { "true" -> true; "false" -> false; "unknown" -> null; else -> error("Invalid lock state") },
                    baselineVerifiedBootState = get("verifiedBoot"), manualStockRestoreReported = boolean("stockReported"),
                    manualMagiskReported = boolean("magiskReported"), otaAppliedObserved = boolean("otaObserved"),
                    simulated = boolean("simulated"), message = get("message")
                )
                if (state.phase !in listOf(WorkflowPhase.IDLE, WorkflowPhase.BLOCKED, WorkflowPhase.RECOVERY_REQUIRED)) {
                    require(state.sourceSlot in listOf("_a", "_b") && state.targetSlot in listOf("_a", "_b") && state.sourceSlot != state.targetSlot)
                    require(state.sourceBootId.isNotBlank() && state.sourceBuild.isNotBlank() && state.targetBuild.isNotBlank())
                    require(state.packageSha256.matches(Regex("[a-fA-F0-9]{64}")))
                }
                state
            } catch (_: Exception) {
                WorkflowState(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Opgeslagen workflow is beschadigd of van een onbekende versie. Lees het toestel opnieuw uit; hervat geen schrijfacties.")
            }
        }
    }
}

object Workflow {
    /** Starts a guided journal only; this API never executes commands. */
    fun start(
        device: DeviceState,
        targetBuild: String,
        targetFingerprint: String,
        packageSha256: String,
        packageInspected: Boolean,
        readiness: ReadinessResult
    ): WorkflowState {
        val blockers = readiness.blockers.toMutableList()
        if (readiness.checkedDevice != device) blockers += CheckResult("PREFLIGHT_MISMATCH", "Gereedheidscontrole hoort niet bij dezelfde toestelobservaties; controleer opnieuw.")
        if (!packageInspected || !packageSha256.matches(Regex("[a-fA-F0-9]{64}"))) blockers += CheckResult("PACKAGE_UNINSPECTED", "Onderzoek eerst een volledig gedownload OTA-pakket.")
        if (targetBuild.isBlank() || targetBuild == device.build || targetFingerprint.isBlank()) blockers += CheckResult("TARGET_UNKNOWN", "Een andere doelbuild en exacte doelfingerprint zijn vereist.")
        if (device.simulated) blockers += CheckResult("SIMULATED", "Simulatie kan geen echte workflow starten.")
        if (blockers.isNotEmpty()) return WorkflowState(phase = WorkflowPhase.BLOCKED, message = blockers.joinToString("\n") { it.message })
        return WorkflowState(
            phase = WorkflowPhase.AWAITING_STOCK_RESTORE, sourceBuild = device.build, sourceSlot = device.slot,
            sourceBootId = device.bootId, targetBuild = targetBuild, targetFingerprint = targetFingerprint,
            targetSlot = device.inactiveSlot, packageSha256 = packageSha256,
            baselineModules = device.modules.sorted(), baselineUnlocked = device.bootloaderUnlocked,
            baselineVerifiedBootState = device.verifiedBootState,
            message = "Pakket lokaal onderzocht; authenticiteit moet de officiële updater verifiëren. Herstel alleen via Magisk na bewezen actuele stock-herkomst. Niet herstarten na herstel."
        )
    }

    fun reportStockRestore(state: WorkflowState): WorkflowState =
        if (state.phase == WorkflowPhase.AWAITING_STOCK_RESTORE) state.copy(
            phase = WorkflowPhase.AWAITING_OTA, manualStockRestoreReported = true,
            message = "Gebruiker meldt stock-herstel; niet onafhankelijk bewezen. Gebruik de officiële Motorola-updater en herstart nog niet."
        ) else state

    fun reportMagisk(state: WorkflowState): WorkflowState =
        if (state.otaAppliedObserved && state.phase in listOf(WorkflowPhase.OTA_APPLIED, WorkflowPhase.MAGISK_PENDING, WorkflowPhase.REBOOT_REQUIRED)) state.copy(
            phase = WorkflowPhase.MAGISK_REPORTED, manualMagiskReported = true,
            message = "Gebruiker meldt Magisk op het inactieve slot; de app kan dit vóór herstart niet onafhankelijk bewijzen. Controleer Magisk-log en doelpartitie zelf."
        ) else state

    fun fail(state: WorkflowState, reason: String): WorkflowState = state.copy(phase = WorkflowPhase.FAILED, message = reason)

    /** Reconcile on launch/resume and after every scan, including after process death or reboot. */
    fun reconcile(state: WorkflowState, device: DeviceState, nowMillis: Long = System.currentTimeMillis()): WorkflowState {
        if (state.phase in listOf(WorkflowPhase.IDLE, WorkflowPhase.BLOCKED, WorkflowPhase.RECOVERY_REQUIRED)) return state
        if (state.phase in listOf(WorkflowPhase.FAILED, WorkflowPhase.ROLLBACK)) return state
        if (state.simulated != device.simulated) return state.copy(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Simulatie en echte toestelobservaties mogen niet worden gemengd.")
        if (device.capturedAtMillis <= 0 || nowMillis < device.capturedAtMillis || nowMillis - device.capturedAtMillis > 120_000) return state.copy(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Actuele diagnostiek ontbreekt of is verouderd; hervat nooit op basis van alleen opgeslagen voortgang.")
        if (device.bootId.isBlank()) return state.copy(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Opstartsessie onbekend; voortgang is niet betrouwbaar vast te stellen.")
        if (device.engine == EngineState.ERROR || device.snapshot == SnapshotState.FAILED) return fail(state, "UpdateEngine of snapshotservice rapporteert een fout. Verzamel diagnostiek; geen automatische herstelactie.")
        val newBoot = device.bootId != state.sourceBootId
        if (!newBoot) {
            if (device.slot != state.sourceSlot || device.build != state.sourceBuild) return state.copy(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Slot of build veranderde binnen dezelfde opstartsessie; tegenstrijdige observaties.")
            if (device.engine == EngineState.UPDATED_NEED_REBOOT) return state.copy(
                phase = if (state.manualMagiskReported) WorkflowPhase.MAGISK_REPORTED else WorkflowPhase.MAGISK_PENDING,
                otaAppliedObserved = true,
                message = if (state.manualMagiskReported) "OTA wacht op herstart; Magisk is alleen door de gebruiker gemeld. De app herstart niet." else "UpdateEngine meldt OTA toegepast, herstart vereist. Open Magisk voor Install to Inactive Slot (After OTA), controleer doel ${state.targetSlot}; herstart nog niet."
            )
            return state
        }
        if (device.slot == state.sourceSlot && device.build == state.sourceBuild) return state.copy(
            phase = if (state.otaAppliedObserved) WorkflowPhase.ROLLBACK else WorkflowPhase.RECOVERY_REQUIRED,
            message = if (state.otaAppliedObserved) "Na herstart draait de oude slot/build. Mogelijke rollback; OTA-succes is niet bevestigd." else "Onverwachte herstart vóór bewezen OTA-toepassing. Inspecteer de actuele toestand; hervat niet blind."
        )
        if (device.slot != state.targetSlot || device.build != state.targetBuild || device.fingerprint != state.targetFingerprint) return state.copy(
            phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Actuele slot, build of exacte fingerprint wijkt af van het verwachte doel. Geen succesvolle update bevestigd."
        )
        if (!state.otaAppliedObserved) return state.copy(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Doelbuild draait, maar OTA-toepassing was niet waargenomen. Volledige updateketen is niet bewezen.")
        if (!device.rootChecked) return state.copy(phase = WorkflowPhase.BOOTED_TARGET, message = "Doelbuild is opgestart maar root is nog niet gemeten. Voer de rootdiagnose uit; ontbrekende observatie is geen bewezen rootverlies.")
        if (!device.rootGranted) return fail(state, "Doelbuild is opgestart maar root kan niet worden bevestigd. Controleer roottoestemming en Magisk handmatig; herstel niet automatisch.")
        if (device.snapshot in listOf(SnapshotState.MERGING, SnapshotState.INITIATED, SnapshotState.UNVERIFIED, SnapshotState.MERGE_NEEDS_REBOOT)) return state.copy(phase = WorkflowPhase.MERGING, message = "Nieuwe slot draait; snapshotproces ${device.snapshot} is nog niet afgerond. Start geen volgende OTA.")
        if (device.snapshot !in listOf(SnapshotState.NONE, SnapshotState.COMPLETE) || device.engine != EngineState.IDLE || device.bootSuccessful != true) return state.copy(
            phase = WorkflowPhase.BOOTED_TARGET, message = "Doelbuild en root zijn waargenomen; wacht op bewezen succesvolle boot, afgeronde merge en idle UpdateEngine."
        )
        if (!device.moduleInventoryKnown) return state.copy(phase = WorkflowPhase.BOOTED_TARGET, message = "Module-inventaris is niet gemeten; behoud kan nog niet worden bevestigd.")
        if (!device.modules.containsAll(state.baselineModules)) return fail(state, "Eén of meer eerder waargenomen Magisk-modules ontbreken. Modulecompatibiliteit blijft een handmatige controle.")
        if (device.magiskCode != 31100) return state.copy(phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Root werkt, maar de waargenomen Magisk-versie is niet de onderzochte 31.1. Beoordeel compatibiliteit handmatig.")
        if (state.baselineUnlocked == null || state.baselineVerifiedBootState.isBlank() || device.bootloaderUnlocked != state.baselineUnlocked || device.verifiedBootState != state.baselineVerifiedBootState) return state.copy(
            phase = WorkflowPhase.RECOVERY_REQUIRED, message = "Bootloader-/Verified Boot-status ontbreekt of wijkt af; controleer toestelbeveiliging handmatig."
        )
        return state.copy(phase = WorkflowPhase.COMPLETE, message = "Doelslot, exacte firmware, root, module-inventaris, bootstatus en afgeronde snapshotstatus zijn waargenomen. Functionele modulecompatibiliteit en Wallet blijven handmatig te testen.")
    }

    /** Synthetic demonstration is permanently labelled and never routed to real command adapters. */
    fun demo(phase: WorkflowPhase = WorkflowPhase.MERGING): WorkflowState = WorkflowState(
        phase = phase, sourceBuild = "DEMO-SOURCE", sourceSlot = "_a", sourceBootId = "demo-boot", targetBuild = "DEMO-TARGET",
        targetFingerprint = "demo/merc/merc:17/DEMO-TARGET/1:user/release-keys", targetSlot = "_b", packageSha256 = "0".repeat(64),
        simulated = true, message = "SIMULATIE: synthetische workflow zonder toestelacties; dit is geen echte OTA-status."
    )
}
