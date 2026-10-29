package nl.moto.ota.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.moto.ota.R
import nl.moto.ota.core.BootImageLayout
import nl.moto.ota.core.EngineState
import nl.moto.ota.core.SnapshotState
import nl.moto.ota.core.update.StepEvidence
import nl.moto.ota.core.update.UpdateStage

@Composable
fun stageTitle(value: UpdateStage): String = stringResource(when (value) {
    UpdateStage.IDLE -> R.string.ui_updatestage_idle
    UpdateStage.PRECHECK_BLOCKED -> R.string.ui_updatestage_precheck_blocked
    UpdateStage.STOCK_PREPARATION -> R.string.ui_updatestage_stock_preparation
    UpdateStage.STOCK_CONFIRMATION -> R.string.ui_updatestage_stock_confirmation
    UpdateStage.STOCK_WRITE_RUNNING -> R.string.ui_updatestage_stock_write_running
    UpdateStage.STOCK_RESTORED -> R.string.ui_updatestage_stock_restored
    UpdateStage.AWAITING_OFFICIAL_OTA -> R.string.ui_updatestage_awaiting_official_ota
    UpdateStage.OTA_APPLYING -> R.string.ui_updatestage_ota_applying
    UpdateStage.MAGISK_REQUIRED -> R.string.ui_updatestage_magisk_required
    UpdateStage.MAGISK_REPORTED_UNVERIFIED -> R.string.ui_updatestage_magisk_reported_unverified
    UpdateStage.REBOOT_BY_SYSTEM_UPDATER -> R.string.ui_updatestage_reboot_by_system_updater
    UpdateStage.AWAITING_NEW_BOOT -> R.string.ui_updatestage_awaiting_new_boot
    UpdateStage.TARGET_BOOTED -> R.string.ui_updatestage_target_booted
    UpdateStage.MERGING -> R.string.ui_updatestage_merging
    UpdateStage.FINAL_CHECKS -> R.string.ui_updatestage_final_checks
    UpdateStage.COMPLETE -> R.string.ui_updatestage_complete
    UpdateStage.COMPLETE_REVIEW -> R.string.ui_updatestage_complete_review
    UpdateStage.FAILED -> R.string.ui_updatestage_failed
    UpdateStage.ROLLBACK -> R.string.ui_updatestage_rollback
    UpdateStage.RECOVERY_REQUIRED -> R.string.ui_updatestage_recovery_required
})

@Composable
fun snapshotTitle(value: SnapshotState): String = stringResource(when (value) {
    SnapshotState.UNKNOWN -> R.string.ui_snapshotstate_unknown
    SnapshotState.NONE -> R.string.ui_snapshotstate_none
    SnapshotState.NOT_APPLICABLE -> R.string.ui_snapshotstate_not_applicable
    SnapshotState.INITIATED -> R.string.ui_snapshotstate_initiated
    SnapshotState.UNVERIFIED -> R.string.ui_snapshotstate_unverified
    SnapshotState.MERGING -> R.string.ui_snapshotstate_merging
    SnapshotState.MERGE_NEEDS_REBOOT -> R.string.ui_snapshotstate_merge_needs_reboot
    SnapshotState.FAILED -> R.string.ui_snapshotstate_failed
    SnapshotState.CANCELLED -> R.string.ui_snapshotstate_cancelled
    SnapshotState.COMPLETE -> R.string.ui_snapshotstate_complete
})

@Composable
fun engineTitle(value: EngineState): String = stringResource(when (value) {
    EngineState.UNKNOWN -> R.string.ui_enginestate_unknown
    EngineState.IDLE -> R.string.ui_enginestate_idle
    EngineState.DOWNLOADING -> R.string.ui_enginestate_downloading
    EngineState.VERIFYING -> R.string.ui_enginestate_verifying
    EngineState.FINALIZING -> R.string.ui_enginestate_finalizing
    EngineState.UPDATED_NEED_REBOOT -> R.string.ui_enginestate_updated_need_reboot
    EngineState.ERROR -> R.string.ui_enginestate_error
})

@Composable
fun evidenceTitle(value: StepEvidence): String = stringResource(when (value) {
    StepEvidence.NONE -> R.string.ui_stepevidence_none
    StepEvidence.USER_REPORTED -> R.string.ui_stepevidence_user_reported
    StepEvidence.OBSERVED -> R.string.ui_stepevidence_observed
    StepEvidence.CRYPTOGRAPHIC_IMAGE -> R.string.ui_stepevidence_cryptographic_image
    StepEvidence.OBSERVED_AFTER_BOOT -> R.string.ui_stepevidence_observed_after_boot
})

@Composable
fun layoutTitle(value: BootImageLayout): String = stringResource(when (value) {
    BootImageLayout.INIT_BOOT_AB -> R.string.ui_bootimagelayout_init_boot_ab
    BootImageLayout.BOOT_AB -> R.string.ui_bootimagelayout_boot_ab
    BootImageLayout.INIT_BOOT_SINGLE -> R.string.ui_bootimagelayout_init_boot_single
    BootImageLayout.BOOT_SINGLE -> R.string.ui_bootimagelayout_boot_single
    BootImageLayout.AB_UNKNOWN -> R.string.ui_bootimagelayout_ab_unknown
    BootImageLayout.UNKNOWN -> R.string.ui_bootimagelayout_unknown
})
