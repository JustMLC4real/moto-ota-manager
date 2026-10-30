package nl.moto.ota.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.moto.ota.BuildConfig
import nl.moto.ota.Destination
import nl.moto.ota.MotoViewModel
import nl.moto.ota.R
import nl.moto.ota.core.*
import nl.moto.ota.core.discovery.CdsStatus
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.update.UpdateAutomationService
import nl.moto.ota.platform.i18n.CheckMessageDomain
import nl.moto.ota.platform.i18n.MessageRenderer
import java.text.DateFormat
import java.util.Date

class ScreenActions(
    val checkDevice: () -> Unit,
    val checkOnline: () -> Unit,
    val download: () -> Unit,
    val monitor: (Boolean) -> Unit,
    val openUpdater: () -> Unit,
    val openMagisk: () -> Unit,
    val importOta: () -> Unit,
    val importImage: () -> Unit,
    val exportReport: () -> Unit,
    val exportRecovery: () -> Unit,
    val navigate: (Destination) -> Unit,
    val startUpdate: () -> Unit
)

@Composable
fun OverviewScreen(vm: MotoViewModel, actions: ScreenActions) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    PageHeader(R.string.ui_overview_title, R.string.ui_overview_body)
    StatusPanel {
        StatusBadge(stringResource(if (vm.device.rootGranted) R.string.ui_device_checked else R.string.ui_device_local))
        Text(vm.device.model.ifBlank { stringResource(R.string.ui_model_pending) },
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(listOf(vm.device.device, vm.device.channel).filter(String::isNotBlank).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Detail(R.string.ui_android_build, "${vm.device.androidVersion.ifBlank { "?" }} · ${vm.device.build.ifBlank { stringResource(R.string.ui_unknown) }}")
        Button(onClick = actions.checkDevice, enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("check_device")) {
            Text(stringResource(if (vm.busy) R.string.ui_check_progress else R.string.ui_check_device))
        }
    }
    Panel(R.string.ui_readiness) {
        StatusBadge(stringResource(if (vm.readiness.ready) R.string.ui_ready else R.string.ui_blocked))
        Detail(R.string.ui_availability, vm.online.result?.let { MessageRenderer.cds(context, it) } ?: MessageRenderer.discovery(context, vm.discovery))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { Detail(R.string.ui_security_patch, vm.device.securityPatch) }
            Column(Modifier.weight(1f)) { Detail(R.string.ui_battery_storage, "${vm.device.batteryPercent.takeIf { it >= 0 } ?: "?"}% · ${displaySize(vm.device.freeBytes)}") }
        }
        OutlinedButton(onClick = { actions.navigate(Destination.FIRMWARE) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ui_find_updates))
        }
        TextButton(onClick = { actions.navigate(Destination.UPDATE) }, enabled = !vm.busy) { Text(stringResource(R.string.ui_open_update)) }
    }
    if (vm.readiness.blockers.isNotEmpty()) ExpandablePanel(R.string.ui_next_steps,
        summary = stringResource(R.string.ui_checks_attention_count, vm.readiness.blockers.size), tag = "readiness_details") {
        vm.readiness.blockers.forEach { Text(MessageRenderer.check(context, it, CheckMessageDomain.READINESS), style = MaterialTheme.typography.bodyMedium) }
    }
    ExpandablePanel(R.string.ui_device_details, summary = stringResource(R.string.ui_device_details_summary), tag = "device_details") {
        Detail(R.string.ui_root_magisk, "${stringResource(if (vm.device.rootGranted) R.string.ui_root_granted else R.string.ui_root_unconfirmed)} · ${vm.device.magiskVersion.ifBlank { stringResource(R.string.ui_unknown) }}")
        Detail(R.string.ui_slots, "${vm.device.slot.ifBlank { "?" }} → ${vm.device.inactiveSlot.ifBlank { "?" }}")
        Detail(R.string.ui_snapshot, snapshotTitle(vm.device.snapshot))
        vm.inspection?.snapshot?.mergePercent?.takeIf { vm.device.snapshot == SnapshotState.MERGING && it.isFinite() && it in 0.0..100.0 }?.let { percent ->
            val formatted = java.text.NumberFormat.getNumberInstance(configuration.locales[0]).apply { maximumFractionDigits = 1 }.format(percent)
            Detail(R.string.ui_merge_progress, "$formatted%")
            AnimatedProgress((percent / 100).toFloat())
        }
        Detail(R.string.ui_engine, engineTitle(vm.device.engine))
        Detail(R.string.ui_original_backup, stringResource(if (vm.backup?.usableAsStock == true) R.string.ui_proven else R.string.ui_unproven))
        Detail(R.string.ui_support, stringResource(when (vm.capabilities.supportTier) {
            DeviceSupportTier.QUALIFIED_MERC, DeviceSupportTier.PROOF_QUALIFIED -> R.string.ui_support_qualified
            DeviceSupportTier.MOTOROLA_GUIDED -> R.string.ui_support_guided
            DeviceSupportTier.MOTOROLA_DIAGNOSTIC -> R.string.ui_support_diagnostics
            else -> R.string.ui_support_other
        }))
        Detail(R.string.ui_boot_layout, layoutTitle(vm.capabilities.bootLayout))
        if (vm.device.securityPropertyMismatch) Text(stringResource(R.string.ui_security_mismatch), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { actions.navigate(Destination.DIAGNOSTICS) }) { Text(stringResource(R.string.ui_view_diagnostics)) }
    }
}

@Composable
fun FirmwareScreen(vm: MotoViewModel, actions: ScreenActions) {
    val context = LocalContext.current
    val attention = vm.online.result?.status in setOf(CdsStatus.INPUT_MISSING, CdsStatus.UNSUPPORTED_REGION,
        CdsStatus.UNKNOWN_RESPONSE, CdsStatus.HTTP_ERROR, CdsStatus.NETWORK_ERROR)
    PageHeader(R.string.ui_firmware_title, R.string.ui_firmware_body)
    StatusPanel(attention = attention) {
        Text(stringResource(R.string.ui_server_check), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Crossfade(targetState = vm.online.result, animationSpec = tween(180), label = "officialResult") { result ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (result == null) {
                    Text(stringResource(R.string.ui_firmware_check_ready), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ui_server_check_body), style = MaterialTheme.typography.bodyMedium)
                } else {
                    // Errors and missing fields stay in the main card, never inside a disclosure.
                    Text(MessageRenderer.cds(context, result), style = MaterialTheme.typography.titleMedium,
                        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (result.targetBuild.isNotBlank()) Detail(R.string.ui_target_build, result.targetBuild)
                    result.targetVersion?.let { Detail(R.string.ui_target_android, it.toString()) }
                    if (result.status in setOf(CdsStatus.OFFER, CdsStatus.METADATA_ONLY))
                        Text(stringResource(R.string.ui_server_offer_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(onClick = actions.checkOnline, enabled = !vm.busy && !vm.demo,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("check_online")) {
            Text(stringResource(if (vm.online.checking) R.string.ui_check_server_busy else R.string.ui_check_updates))
        }
        if (vm.candidateAvailable) OutlinedButton(onClick = actions.download, enabled = !vm.busy && !vm.demo,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_download_candidate)) }
        TextButton(onClick = actions.openUpdater, enabled = !vm.demo && !vm.busy) { Text(stringResource(R.string.ui_open_system_updates)) }
    }
    if (!vm.demo) DownloadProgressPanel(vm.downloadJob, vm.busy, true, vm::pauseDownload, vm::resumeDownload, vm::cancelDownload, vm::inspectSaved)
    vm.firmware?.let { report ->
        Panel(R.string.ui_package_result) {
            StatusBadge(stringResource(if (report.signatureVerified && report.compatible) R.string.ui_package_verified else R.string.ui_package_needs_checks))
            Detail(R.string.ui_source_target, "${vm.device.build} → ${report.targetBuild}")
            Detail(R.string.ui_package_size, displaySize(report.packageBytes))
            report.issues.forEach { Text(MessageRenderer.check(context, it, CheckMessageDomain.FIRMWARE),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = vm::verify, enabled = !vm.busy && !vm.demo && report.structureValid && report.payloadHashValid,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_verify_signature)) }
            vm.verificationProgress?.let {
                AnimatedProgress(it / 100f)
                Text(stringResource(R.string.ui_verification_progress, it), style = MaterialTheme.typography.bodySmall)
            }
        }
        ExpandablePanel(R.string.ui_package_details, tag = "package_details") {
            Detail(R.string.ui_target_android, report.targetAndroidVersion)
            Detail(R.string.ui_package_structure, stringResource(if (report.structureValid) R.string.ui_valid else R.string.ui_invalid))
            Detail(R.string.ui_payload_hash, stringResource(if (report.payloadHashValid) R.string.ui_matches else R.string.ui_unproven))
            Detail(R.string.ui_source_compatibility, stringResource(if (report.compatible) R.string.ui_checks_passed else R.string.ui_not_ready))
            Detail(R.string.ui_region, stringResource(if (report.regionVerified) R.string.ui_channel_match else R.string.ui_unproven))
            Detail(R.string.ui_signature, stringResource(if (report.signatureVerified) R.string.ui_signature_verified else R.string.ui_unproven))
            Detail("SHA-256", report.sha256)
            Text(stringResource(R.string.ui_package_signature_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    ExpandablePanel(R.string.ui_monitoring,
        summary = stringResource(if (vm.monitorState.enabled) R.string.ui_monitoring_on else R.string.ui_monitoring_off), tag = "monitoring_details") {
        Text(stringResource(R.string.ui_monitoring_body), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ui_monitoring_switch), modifier = Modifier.weight(1f))
            Switch(checked = vm.monitorState.enabled, onCheckedChange = actions.monitor,
                enabled = !vm.busy && !vm.demo, modifier = Modifier.testTag("monitoring_switch"))
        }
        if (!vm.notificationsAllowed) Text(stringResource(R.string.ui_notifications_denied), style = MaterialTheme.typography.bodySmall)
        Detail(R.string.ui_last_result, MessageRenderer.monitor(context, vm.monitorState.lastOutcome))
        if (vm.monitorState.lastCheckedAtMillis > 0) Detail(R.string.ui_last_check, DateFormat.getDateTimeInstance().format(Date(vm.monitorState.lastCheckedAtMillis)))
        TextButton(onClick = vm::checkMonitoringNow, enabled = vm.monitorState.enabled && !vm.busy && !vm.demo) { Text(stringResource(R.string.ui_check_local)) }
    }
    ExpandablePanel(R.string.ui_package_inspect, summary = stringResource(R.string.ui_import_summary), tag = "import_details") {
        Text(stringResource(R.string.ui_package_inspect_body))
        OutlinedButton(onClick = actions.importOta, enabled = !vm.busy && !vm.demo, modifier = Modifier.fillMaxWidth().testTag("import_ota")) { Text(stringResource(R.string.ui_choose_ota)) }
        TextButton(onClick = vm::inspectSaved, enabled = !vm.busy && !vm.demo) { Text(stringResource(R.string.ui_inspect_saved)) }
    }
    ExpandablePanel(R.string.ui_local_fallback, summary = stringResource(R.string.ui_local_fallback_summary), tag = "local_firmware_details") {
        Text(MessageRenderer.discovery(context, vm.discovery))
        Text(stringResource(R.string.ui_find_existing_body), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::discoverExistingPackages, enabled = !vm.busy && !vm.demo && vm.device.rootGranted) { Text(stringResource(R.string.ui_find_existing)) }
        vm.existingMotorolaPackages.forEachIndexed { index, candidate ->
            TextButton(onClick = { vm.importExistingPackage(candidate.id) }, enabled = !vm.busy && !vm.demo) { Text(stringResource(R.string.ui_existing_candidate, index + 1, displaySize(candidate.bytes))) }
        }
    }
    ExpandablePanel(R.string.ui_image_vault, summary = stringResource(R.string.ui_image_vault_summary), tag = "image_vault_details") {
        Text(stringResource(R.string.ui_image_vault_body))
        OutlinedButton(onClick = actions.importImage, enabled = !vm.busy && !vm.demo) { Text(stringResource(R.string.ui_choose_image)) }
        vm.backup?.let { backup ->
            Detail(R.string.ui_image_size, displaySize(backup.bytes))
            Detail(R.string.ui_partition_type, backup.partitionType)
            Detail("SHA-256", backup.sha256)
            backup.issues.forEach { Text(MessageRenderer.check(context, it, CheckMessageDomain.BACKUP), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun UpdateScreen(vm: MotoViewModel, actions: ScreenActions) {
    val context = LocalContext.current
    PageHeader(R.string.ui_update_title, R.string.ui_update_body)
    if (vm.demo) {
        StatusPanel {
            Text(stringResource(R.string.ui_simulation_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.ui_simulation_body))
            Button(onClick = vm::simulateOneTap, enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("run_simulation")) { Text(stringResource(R.string.ui_simulation_start)) }
            if (vm.sandboxActive && vm.workflow.stage == UpdateStage.COMPLETE) Text(stringResource(R.string.ui_simulation_finished),
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("simulation_complete"))
            TextButton(onClick = { vm.scan(false) }, enabled = !vm.busy) { Text(stringResource(R.string.ui_exit_simulation)) }
        }
    } else {
        Panel(R.string.ui_update_plan) {
            Detail(R.string.ui_source_target, "${vm.device.build.ifBlank { "?" }} → ${vm.observedTargetBuild.ifBlank { stringResource(R.string.ui_update_target_pending) }}")
            Detail(R.string.ui_slots, "${vm.device.slot.ifBlank { "?" }} → ${vm.device.inactiveSlot.ifBlank { "?" }}")
            Text(stringResource(R.string.ui_update_plan_body), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            // The full authorization stays visible immediately before Start.
            Text(stringResource(R.string.ui_complete_run_consent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.ui_complete_run_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = actions.startUpdate, enabled = !vm.busy && vm.capabilities.motorola,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_one_tap_update")) { Text(stringResource(R.string.ui_start_update)) }
            if (!vm.capabilities.motorola) Text(stringResource(R.string.ui_motorola_required), style = MaterialTheme.typography.bodySmall)
        }
    }
    val steps = listOf(R.string.ui_step_verify, R.string.ui_step_restore, R.string.ui_step_install, R.string.ui_step_magisk, R.string.ui_step_finish)
    val activeStep = when (vm.workflow.stage) {
        UpdateStage.IDLE, UpdateStage.PRECHECK_BLOCKED -> 0
        UpdateStage.STOCK_PREPARATION, UpdateStage.STOCK_CONFIRMATION, UpdateStage.STOCK_WRITE_RUNNING -> 1
        UpdateStage.STOCK_RESTORED, UpdateStage.AWAITING_OFFICIAL_OTA, UpdateStage.OTA_APPLYING -> 2
        UpdateStage.MAGISK_REQUIRED, UpdateStage.MAGISK_REPORTED_UNVERIFIED, UpdateStage.REBOOT_BY_SYSTEM_UPDATER -> 3
        else -> 4
    }
    StatusPanel(attention = vm.workflow.errorCode != null) {
        Text(stringResource(R.string.ui_update_progress), style = MaterialTheme.typography.labelLarge)
        AnimatedContent(targetState = vm.workflow.stage, label = "workflowStage") { stage ->
            Text(stageTitle(stage), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        val workflowMessage = MessageRenderer.workflow(context, vm.workflow)
        Text(workflowMessage, style = MaterialTheme.typography.bodyMedium)
        val message = vm.message.resolve(context)
        if (message != workflowMessage) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.automationActive) {
            Text(stringResource(UpdateAutomationService.phaseResource(vm.automation.phase)), style = MaterialTheme.typography.titleSmall)
            if (vm.automation.totalBytes > 0 && vm.automation.downloadedBytes < vm.automation.totalBytes) {
                AnimatedProgress(vm.automation.downloadedBytes.toFloat() / vm.automation.totalBytes)
                Text("${displaySize(vm.automation.downloadedBytes)} / ${displaySize(vm.automation.totalBytes)}", style = MaterialTheme.typography.bodySmall)
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { index, _ ->
                Surface(modifier = Modifier.weight(1f).height(5.dp), shape = CircleShape,
                    color = if (index <= activeStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
            }
        }
        Text(stringResource(R.string.ui_workflow_step, activeStep + 1, steps.size, stringResource(steps[activeStep])),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.workflow.errorCode != null) Detail(R.string.ui_diagnostic_code, vm.workflow.errorCode.orEmpty())
        TextButton(onClick = actions.checkDevice, enabled = !vm.busy) { Text(stringResource(R.string.ui_refresh_progress)) }
    }
    ExpandablePanel(R.string.ui_workflow_details, tag = "workflow_details") {
        steps.forEachIndexed { index, title ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (index < activeStep || vm.workflow.stage == UpdateStage.COMPLETE) "✓" else "${index + 1}",
                    color = if (index <= activeStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(title), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (index == activeStep) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        if (vm.workflow.sourceBuild.isNotBlank()) Detail(R.string.ui_source_target, "${vm.workflow.sourceBuild} → ${vm.workflow.targetBuild}")
        Detail(R.string.ui_stock_restore, evidenceTitle(vm.workflow.stockRestoreEvidence))
        Detail(R.string.ui_magisk_target, evidenceTitle(vm.workflow.magiskEvidence))
    }

    if (!vm.demo) ExpandablePanel(R.string.ui_advanced_recovery) {
        Text(stringResource(R.string.ui_advanced_recovery_body), style = MaterialTheme.typography.bodySmall)
        if (vm.workflow.stage in setOf(UpdateStage.STOCK_WRITE_RUNNING, UpdateStage.RECOVERY_REQUIRED) && vm.workflow.restoreTransactionId.isNotBlank()) {
            OutlinedButton(onClick = vm::recoverRestore, enabled = !vm.busy) { Text(stringResource(R.string.ui_observe_restore)) }
            if (vm.workflow.stage == UpdateStage.RECOVERY_REQUIRED) TextButton(onClick = vm::releasePreparation, enabled = !vm.busy) { Text(stringResource(R.string.ui_release_preparation)) }
        }
        if (vm.workflow.stage == UpdateStage.FAILED && vm.workflow.errorCode in setOf("ROOT_MISSING", "MODULE_INVENTORY_CHANGED", "MAGISK_VERSION")) TextButton(onClick = vm::retryFinalChecks, enabled = !vm.busy) { Text(stringResource(R.string.ui_retry_final)) }
        TextButton(onClick = actions.openUpdater, enabled = !vm.busy) { Text(stringResource(R.string.ui_open_system_updates)) }
        TextButton(onClick = actions.openMagisk, enabled = !vm.busy) { Text(stringResource(R.string.ui_open_magisk)) }
        var target by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(value = target, onValueChange = { target = it.take(100) }, label = { Text(stringResource(R.string.ui_manual_target)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.startGuided(target.trim()) }, enabled = !vm.busy && target.isNotBlank()) { Text(stringResource(R.string.ui_start_guidance)) }
        if (vm.workflow.stage in setOf(UpdateStage.STOCK_PREPARATION, UpdateStage.STOCK_CONFIRMATION)) {
            OutlinedButton(onClick = vm::prepareRestore, enabled = !vm.busy) { Text(stringResource(R.string.ui_prepare_restore)) }
            TextButton(onClick = vm::reportStock, enabled = !vm.busy) { Text(stringResource(R.string.ui_check_manual_restore)) }
            if (vm.workflow.stage == UpdateStage.STOCK_PREPARATION) TextButton(onClick = vm::cancelPreparation, enabled = !vm.busy) { Text(stringResource(R.string.ui_cancel_preparation)) }
        }
        vm.restorePlan?.let { plan ->
            Detail(R.string.ui_restore_partition, plan.partition)
            Detail(R.string.ui_image_size, displaySize(plan.imageBytes))
            Detail("SHA-256", plan.stockSha256)
            Text(stringResource(R.string.ui_restore_manual_scope), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.confirmRestore(true) }, enabled = !vm.busy) { Text(stringResource(R.string.ui_manual_restore_write)) }
        }
        if (vm.workflow.stage in setOf(UpdateStage.STOCK_RESTORED, UpdateStage.AWAITING_OFFICIAL_OTA, UpdateStage.OTA_APPLYING)) {
            TextButton(onClick = { vm.markOfficialOtaHandoff(actions.openUpdater) }, enabled = !vm.busy) { Text(stringResource(R.string.ui_continue_official)) }
        }
        if (vm.workflow.stage in setOf(UpdateStage.MAGISK_REQUIRED, UpdateStage.MAGISK_REPORTED_UNVERIFIED, UpdateStage.REBOOT_BY_SYSTEM_UPDATER)) {
            TextButton(onClick = vm::reportMagisk, enabled = !vm.busy) { Text(stringResource(R.string.ui_report_magisk)) }
            TextButton(onClick = vm::reportMagiskFailure, enabled = !vm.busy) { Text(stringResource(R.string.ui_report_magisk_failure)) }
            TextButton(onClick = { vm.acknowledgeReboot(actions.openUpdater) }, enabled = !vm.busy && vm.workflow.magiskEvidence != StepEvidence.NONE) { Text(stringResource(R.string.ui_official_restart)) }
        }
        if (vm.workflow.stage == UpdateStage.FAILED && vm.workflow.errorCode == "MAGISK_INACTIVE_FAILED") TextButton(onClick = vm::retryMagiskStep, enabled = !vm.busy) { Text(stringResource(R.string.ui_retry_magisk)) }
        if (vm.workflow.stage in setOf(UpdateStage.STOCK_RESTORED, UpdateStage.AWAITING_OFFICIAL_OTA) && vm.firmware?.signatureVerified == true && vm.firmware?.regionVerified == true) {
            Text(stringResource(R.string.ui_stage_body), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.stagePackage(true) }, enabled = !vm.busy) { Text(stringResource(R.string.ui_stage_package)) }
        }
    }
}

@Composable
fun DiagnosticsScreen(vm: MotoViewModel, actions: ScreenActions) {
    val context = LocalContext.current
    PageHeader(R.string.ui_diagnostics_title, R.string.ui_diagnostics_body)
    Panel(R.string.ui_diagnostics_actions) {
        OutlinedButton(onClick = actions.checkDevice, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_check_device)) }
        OutlinedButton(onClick = actions.exportReport, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_export_report)) }
        if (vm.workflow.restoreTransactionId.isNotBlank()) TextButton(onClick = actions.exportRecovery, enabled = !vm.busy && !vm.demo) { Text(stringResource(R.string.ui_export_backup)) }
        Text(stringResource(R.string.ui_report_privacy), style = MaterialTheme.typography.bodySmall)
    }
    Panel(R.string.ui_platform_details) {
        Detail(R.string.ui_measured_model, vm.inspection?.properties?.get("ro.product.model").orEmpty())
        Detail(R.string.ui_system_fingerprint, vm.device.fingerprint)
        Detail(R.string.ui_vendor_fingerprint, vm.device.vendorFingerprint)
        Detail(R.string.ui_partitions, vm.device.partitions.joinToString(", "))
        Detail(R.string.ui_security_source, vm.device.securityEvidenceSource)
        Detail(R.string.ui_boot_security, "${stringResource(if (vm.device.bootloaderUnlocked == true) R.string.ui_unlocked else if (vm.device.bootloaderUnlocked == false) R.string.ui_locked else R.string.ui_unknown)} · ${vm.device.verifiedBootState}")
        if (vm.device.securityPropertyMismatch) Text(stringResource(R.string.ui_security_mismatch), style = MaterialTheme.typography.bodySmall)
        Detail(R.string.ui_modules, stringResource(R.string.ui_modules_count, vm.device.modules.size))
        vm.inspection?.snapshot?.let { observation -> MessageRenderer.snapshotEvidence(context, observation).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        vm.inspection?.counters?.forEach { (key, value) -> Detail(key.substringAfterLast('.'), value) }
        Text(stringResource(R.string.ui_counters_note), style = MaterialTheme.typography.bodySmall)
    }
    ExpandablePanel(R.string.ui_probe_results) {
        vm.inspection?.probes?.forEach { (name, probe) -> Detail(name, stringResource(when {
            probe.timedOut -> R.string.ui_probe_timeout
            probe.truncated -> R.string.ui_probe_truncated
            probe.successful -> R.string.ui_probe_read
            else -> R.string.ui_probe_unavailable
        })) }
    }
    ExpandablePanel(R.string.ui_magisk_backups) {
        if (vm.inspection?.backupInventory.isNullOrEmpty()) Text(stringResource(R.string.ui_backups_unknown))
        vm.inspection?.backupInventory?.forEachIndexed { index, candidate ->
            Text(stringResource(R.string.ui_backup_candidate, index + 1), style = MaterialTheme.typography.titleSmall)
            Detail(R.string.ui_image_size, displaySize(candidate.uncompressedBytes ?: -1))
            Detail("SHA-256", candidate.sha256.orEmpty())
            Detail(R.string.ui_original_backup, stringResource(if (candidate.provenanceVerified) R.string.ui_proven else R.string.ui_unproven))
            MessageRenderer.backupErrors(context, candidate).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Panel(R.string.ui_simulation_title) {
        Text(stringResource(R.string.ui_simulation_body))
        OutlinedButton(onClick = { vm.showDemo(); actions.navigate(Destination.UPDATE) }, enabled = !vm.busy, modifier = Modifier.testTag("open_simulation")) { Text(stringResource(R.string.ui_try_simulation)) }
        TextButton(onClick = { vm.scan(false) }, enabled = !vm.busy) { Text(stringResource(R.string.ui_check_without_root)) }
        Text(stringResource(R.string.ui_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium)
    }
}
