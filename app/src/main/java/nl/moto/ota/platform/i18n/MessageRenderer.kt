package nl.moto.ota.platform.i18n

import android.content.Context
import androidx.annotation.StringRes
import nl.moto.ota.R
import nl.moto.ota.core.*
import nl.moto.ota.core.discovery.*
import nl.moto.ota.core.update.UpdateRun
import nl.moto.ota.platform.BackupCandidate
import nl.moto.ota.platform.SnapshotObservation

enum class CheckMessageDomain { GENERAL, READINESS, FIRMWARE, BACKUP, RESTORE, CAPABILITY }

/** Resolve at the Android boundary so persisted observations follow the current app language.
 * Legacy prose is never used as a fallback; typed evidence and machine codes remain authoritative. */
object MessageRenderer {
    @StringRes private fun resource(key: String): Int? = when (key) {
        "check.DEVICE_PROFILE" -> R.string.msg_check_device_profile
        "check.ANDROID_UNREVIEWED" -> R.string.msg_check_android_unreviewed
        "check.FIRMWARE_UNKNOWN" -> R.string.msg_check_firmware_unknown
        "check.ROOT_DENIED" -> R.string.msg_check_root_denied
        "check.MAGISK_UNREVIEWED" -> R.string.msg_check_magisk_unreviewed
        "check.VIRTUAL_AB_UNKNOWN" -> R.string.msg_check_virtual_ab_unknown
        "check.SLOT_UNKNOWN" -> R.string.msg_check_slot_unknown
        "check.PARTITIONS_UNKNOWN" -> R.string.msg_check_partitions_unknown
        "check.SNAPSHOT_BLOCKED" -> R.string.msg_check_snapshot_blocked
        "check.ENGINE_NOT_IDLE" -> R.string.msg_check_engine_not_idle
        "check.BATTERY_LOW" -> R.string.msg_check_battery_low
        "check.STORAGE_LOW" -> R.string.msg_check_storage_low
        "check.BACKUP_UNVERIFIED" -> R.string.msg_check_backup_unverified
        "check.OBSERVATIONS_STALE" -> R.string.msg_check_observations_stale
        "check.SIMULATED" -> R.string.msg_check_simulated
        "check.BOOT_ID_UNKNOWN" -> R.string.msg_check_boot_id_unknown
        "check.BOOT_NOT_CONFIRMED" -> R.string.msg_check_boot_not_confirmed
        "check.BOOT_SECURITY_UNKNOWN" -> R.string.msg_check_boot_security_unknown
        "check.MODULE_INVENTORY_UNKNOWN" -> R.string.msg_check_module_inventory_unknown
        "check.GUIDED_ONLY" -> R.string.msg_check_guided_only
        "check.WRONG_DEVICE" -> R.string.msg_check_wrong_device
        "check.SOURCE_INCREMENTAL_UNKNOWN" -> R.string.msg_check_source_incremental_unknown
        "check.SOURCE_BUILD" -> R.string.msg_check_source_build
        "check.SOURCE_FINGERPRINT" -> R.string.msg_check_source_fingerprint
        "check.SOURCE_INCOMPLETE" -> R.string.msg_check_source_incomplete
        "check.TARGET_FINGERPRINT" -> R.string.msg_check_target_fingerprint
        "check.PAYLOAD_FINGERPRINTS_INVALID" -> R.string.msg_check_payload_fingerprints_invalid
        "check.OEM_METADATA_REVIEW_REQUIRED" -> R.string.msg_check_oem_metadata_review_required
        "check.ANDROID_VERSION_UNKNOWN" -> R.string.msg_check_android_version_unknown
        "check.ANDROID_ROLLBACK" -> R.string.msg_check_android_rollback
        "check.ALREADY_INSTALLED" -> R.string.msg_check_already_installed
        "check.WRONG_REGION" -> R.string.msg_check_wrong_region
        "check.REGION_UNPROVEN" -> R.string.msg_check_region_unproven
        "check.DOWNGRADE_OR_WIPE" -> R.string.msg_check_downgrade_or_wipe
        "check.TIMESTAMP_UNKNOWN" -> R.string.msg_check_timestamp_unknown
        "check.TIMESTAMP_ROLLBACK" -> R.string.msg_check_timestamp_rollback
        "check.PATCH_UNKNOWN" -> R.string.msg_check_patch_unknown
        "check.PATCH_ROLLBACK" -> R.string.msg_check_patch_rollback
        "check.PAYLOAD_HASH" -> R.string.msg_check_payload_hash
        "check.INVALID_PACKAGE" -> R.string.msg_check_invalid_package
        "check.PARTITION_SIZE_UNKNOWN" -> R.string.msg_check_partition_size_unknown
        "check.IMAGE_SIZE" -> R.string.msg_check_image_size
        "check.IMAGE_HEADER" -> R.string.msg_check_image_header
        "check.STOCK_PROVENANCE_UNKNOWN" -> R.string.msg_check_stock_provenance_unknown
        "check.IMAGE_CORRUPT" -> R.string.msg_check_image_corrupt
        "check.PROFILE" -> R.string.msg_check_profile
        "check.ROOT" -> R.string.msg_check_root
        "check.SLOT" -> R.string.msg_check_slot
        "check.PARTITIONS" -> R.string.msg_check_partitions
        "check.UPDATE_BUSY" -> R.string.msg_check_update_busy
        "check.BOOT_STATE" -> R.string.msg_check_boot_state
        "check.SECURITY_SOURCE" -> R.string.msg_check_security_source
        "check.IDENTITY" -> R.string.msg_check_identity
        "check.STALE" -> R.string.msg_check_stale
        "check.POWER_STORAGE" -> R.string.msg_check_power_storage
        "check.MODULES" -> R.string.msg_check_modules
        "check.AUTOMATIC_UPDATES" -> R.string.msg_check_automatic_updates
        "check.STOCK_PROVENANCE" -> R.string.msg_check_stock_provenance
        "check.IMAGE_DIGEST" -> R.string.msg_check_image_digest
        "check.EXECUTION_MODE" -> R.string.msg_check_execution_mode
        "discovery.UNKNOWN" -> R.string.msg_discovery_unknown
        "discovery.NO_LOCAL_METADATA" -> R.string.msg_discovery_no_local_metadata
        "discovery.METADATA_ONLY" -> R.string.msg_discovery_metadata_only
        "discovery.CACHED_OFFICIAL_CANDIDATE" -> R.string.msg_discovery_cached_official_candidate
        "discovery.UNTRUSTED_LOCAL_METADATA" -> R.string.msg_discovery_untrusted_local_metadata
        "discovery.EXTERNAL_REPORT_ONLY" -> R.string.msg_discovery_external_report_only
        "discovery.OFFER_DISCREPANCY" -> R.string.msg_discovery_offer_discrepancy
        "discovery.ALREADY_INSTALLED" -> R.string.msg_discovery_already_installed
        "monitor.NEVER" -> R.string.msg_monitor_never
        "monitor.DISABLED" -> R.string.msg_monitor_disabled
        "monitor.UNSUPPORTED_DEVICE" -> R.string.msg_monitor_unsupported_device
        "monitor.ROOT_REQUIRED" -> R.string.msg_monitor_root_required
        "monitor.STALE_OBSERVATION" -> R.string.msg_monitor_stale_observation
        "monitor.DEVICE_BUSY" -> R.string.msg_monitor_device_busy
        "monitor.NO_NEW_RELEASE" -> R.string.msg_monitor_no_new_release
        "monitor.METADATA_UNAVAILABLE" -> R.string.msg_monitor_metadata_unavailable
        "monitor.NEW_METADATA" -> R.string.msg_monitor_new_metadata
        "monitor.NEW_PACKAGE_REFERENCE" -> R.string.msg_monitor_new_package_reference
        "monitor.ALREADY_NOTIFIED" -> R.string.msg_monitor_already_notified
        "monitor.NOTIFICATIONS_DISABLED" -> R.string.msg_monitor_notifications_disabled
        "monitor.CHECK_FAILED" -> R.string.msg_monitor_check_failed
        "monitor.STORE_CORRUPT" -> R.string.msg_monitor_store_corrupt
        "unknown" -> R.string.msg_unknown
        "failure" -> R.string.msg_failure
        "backup.unknown" -> R.string.msg_backup_unknown
        "backup.gzip_size" -> R.string.msg_backup_gzip_size
        "backup.unstable" -> R.string.msg_backup_unstable
        "backup.header" -> R.string.msg_backup_header
        "backup.sha1" -> R.string.msg_backup_sha1
        "backup.provenance" -> R.string.msg_backup_provenance
        "backup.corrupt" -> R.string.msg_backup_corrupt
        "snapshot.unknown" -> R.string.msg_snapshot_unknown
        "snapshot.tool" -> R.string.msg_snapshot_tool
        "snapshot.hal" -> R.string.msg_snapshot_hal
        "snapshot.tool_unreadable" -> R.string.msg_snapshot_tool_unreadable
        "snapshot.metadata_unreadable" -> R.string.msg_snapshot_metadata_unreadable
        "snapshot.file" -> R.string.msg_snapshot_file
        "snapshot.misc" -> R.string.msg_snapshot_misc
        "snapshot.conflict" -> R.string.msg_snapshot_conflict
        "snapshot.directory" -> R.string.msg_snapshot_directory
        "snapshot.tool_conflict" -> R.string.msg_snapshot_tool_conflict
        "workflow.IDLE" -> R.string.msg_workflow_idle
        "workflow.PRECHECK_BLOCKED" -> R.string.msg_workflow_precheck_blocked
        "workflow.STOCK_PREPARATION" -> R.string.msg_workflow_stock_preparation
        "workflow.STOCK_CONFIRMATION" -> R.string.msg_workflow_stock_confirmation
        "workflow.STOCK_WRITE_RUNNING" -> R.string.msg_workflow_stock_write_running
        "workflow.STOCK_RESTORED" -> R.string.msg_workflow_stock_restored
        "workflow.AWAITING_OFFICIAL_OTA" -> R.string.msg_workflow_awaiting_official_ota
        "workflow.OTA_APPLYING" -> R.string.msg_workflow_ota_applying
        "workflow.MAGISK_REQUIRED" -> R.string.msg_workflow_magisk_required
        "workflow.MAGISK_REPORTED_UNVERIFIED" -> R.string.msg_workflow_magisk_reported_unverified
        "workflow.REBOOT_BY_SYSTEM_UPDATER" -> R.string.msg_workflow_reboot_by_system_updater
        "workflow.AWAITING_NEW_BOOT" -> R.string.msg_workflow_awaiting_new_boot
        "workflow.TARGET_BOOTED" -> R.string.msg_workflow_target_booted
        "workflow.MERGING" -> R.string.msg_workflow_merging
        "workflow.FINAL_CHECKS" -> R.string.msg_workflow_final_checks
        "workflow.COMPLETE" -> R.string.msg_workflow_complete
        "workflow.COMPLETE_REVIEW" -> R.string.msg_workflow_complete_review
        "workflow.FAILED" -> R.string.msg_workflow_failed
        "workflow.ROLLBACK" -> R.string.msg_workflow_rollback
        "workflow.RECOVERY_REQUIRED" -> R.string.msg_workflow_recovery_required
        "capability.MOTOROLA_REQUIRED" -> R.string.msg_capability_motorola_required
        "capability.IDENTITY_INCOMPLETE" -> R.string.msg_capability_identity_incomplete
        "capability.OBSERVATIONS_STALE" -> R.string.msg_capability_observations_stale
        "capability.SIMULATION" -> R.string.msg_capability_simulation
        "capability.UPDATER_UNCHECKED" -> R.string.msg_capability_updater_unchecked
        "capability.UPDATER_UNAVAILABLE" -> R.string.msg_capability_updater_unavailable
        "capability.AB_EVIDENCE_CONFLICT" -> R.string.msg_capability_ab_evidence_conflict
        "capability.BOOT_LAYOUT_UNKNOWN" -> R.string.msg_capability_boot_layout_unknown
        "capability.ROOT_AB_GUIDANCE_UNAVAILABLE" -> R.string.msg_capability_root_ab_guidance_unavailable
        "capability.BACKUP_ASSOCIATION_UNPROVEN" -> R.string.msg_capability_backup_association_unproven
        "capability.SIGNED_CURRENT_IMAGE_REQUIRED" -> R.string.msg_capability_signed_current_image_required
        "capability.WRITE_RUNTIME_UNREVIEWED" -> R.string.msg_capability_write_runtime_unreviewed
        "capability.WRITE_MAGISK_UNREVIEWED" -> R.string.msg_capability_write_magisk_unreviewed
        "cds.CONSENT_REQUIRED" -> R.string.msg_cds_consent_required
        "cds.INPUT_MISSING" -> R.string.msg_cds_input_missing
        "cds.UNSUPPORTED_REGION" -> R.string.msg_cds_unsupported_region
        "cds.CHECKING" -> R.string.msg_cds_checking
        "cds.NO_OFFER" -> R.string.msg_cds_no_offer
        "cds.OFFER" -> R.string.msg_cds_offer
        "cds.METADATA_ONLY" -> R.string.msg_cds_metadata_only
        "cds.UNKNOWN_RESPONSE" -> R.string.msg_cds_unknown_response
        "cds.HTTP_ERROR" -> R.string.msg_cds_http_error
        "cds.NETWORK_ERROR" -> R.string.msg_cds_network_error
        "cds.CANCELLED" -> R.string.msg_cds_cancelled
        "monitor.NEW_SERVER_OFFER" -> R.string.msg_monitor_new_server_offer
        "cds.input.ROOT" -> R.string.msg_cds_input_root
        "cds.input.SERIAL_NUMBER" -> R.string.msg_cds_input_serial_number
        "cds.input.CONTEXT_KEY" -> R.string.msg_cds_input_context_key
        "cds.input.MANUFACTURER" -> R.string.msg_cds_input_manufacturer
        "cds.input.MODEL" -> R.string.msg_cds_input_model
        "cds.input.PRODUCT" -> R.string.msg_cds_input_product
        "cds.input.DEVICE" -> R.string.msg_cds_input_device
        "cds.input.CARRIER" -> R.string.msg_cds_input_carrier
        "cds.input.BUILD_ID" -> R.string.msg_cds_input_build_id
        "cds.input.BUILD_FINGERPRINT" -> R.string.msg_cds_input_build_fingerprint
        "cds.input.IDENTITY" -> R.string.msg_cds_input_identity
        "cds.input.FRESH_OBSERVATION" -> R.string.msg_cds_input_fresh_observation
        "workflow.precheck_blocked" -> R.string.msg_workflow_detail_precheck_blocked
        "workflow.started_qualified" -> R.string.msg_workflow_detail_started_qualified
        "workflow.started_official" -> R.string.msg_workflow_detail_started_official
        "workflow.manual_stock_restore" -> R.string.msg_workflow_detail_manual_stock_restore
        "workflow.restore_plan" -> R.string.msg_workflow_detail_restore_plan
        "workflow.restore_running" -> R.string.msg_workflow_detail_restore_running
        "workflow.stock_verified" -> R.string.msg_workflow_detail_stock_verified
        "workflow.restore_recovery" -> R.string.msg_workflow_detail_restore_recovery
        "workflow.stock_reported" -> R.string.msg_workflow_detail_stock_reported
        "workflow.stock_observed" -> R.string.msg_workflow_detail_stock_observed
        "workflow.stock_hash_mismatch" -> R.string.msg_workflow_detail_stock_hash_mismatch
        "workflow.restore_backup_check_required" -> R.string.msg_workflow_detail_restore_backup_check_required
        "workflow.source_refresh_required" -> R.string.msg_workflow_detail_source_refresh_required
        "workflow.source_changed" -> R.string.msg_workflow_detail_source_changed
        "workflow.official_handoff" -> R.string.msg_workflow_detail_official_handoff
        "workflow.ota_reported" -> R.string.msg_workflow_detail_ota_reported
        "workflow.ota_applied" -> R.string.msg_workflow_detail_ota_applied
        "workflow.ota_progress" -> R.string.msg_workflow_detail_ota_progress
        "workflow.magisk_reported" -> R.string.msg_workflow_detail_magisk_reported
        "workflow.magisk_proof_unverified" -> R.string.msg_workflow_detail_magisk_proof_unverified
        "workflow.magisk_proven" -> R.string.msg_workflow_detail_magisk_proven
        "workflow.awaiting_new_boot" -> R.string.msg_workflow_detail_awaiting_new_boot
        "workflow.failed" -> R.string.msg_workflow_detail_failed
        "workflow.magisk_retry_permitted" -> R.string.msg_workflow_detail_magisk_retry_permitted
        "workflow.restore_new_plan_permitted" -> R.string.msg_workflow_detail_restore_new_plan_permitted
        "workflow.recovery" -> R.string.msg_workflow_detail_recovery
        "workflow.rollback" -> R.string.msg_workflow_detail_rollback
        "workflow.unexpected_reboot" -> R.string.msg_workflow_detail_unexpected_reboot
        "workflow.target_mismatch" -> R.string.msg_workflow_detail_target_mismatch
        "workflow.target_runtime_unreviewed" -> R.string.msg_workflow_detail_target_runtime_unreviewed
        "workflow.target_root_pending" -> R.string.msg_workflow_detail_target_root_pending
        "workflow.root_missing" -> R.string.msg_workflow_detail_root_missing
        "workflow.merge_progress" -> R.string.msg_workflow_detail_merge_progress
        "workflow.final_checks_pending" -> R.string.msg_workflow_detail_final_checks_pending
        "workflow.modules_changed" -> R.string.msg_workflow_detail_modules_changed
        "workflow.security_changed" -> R.string.msg_workflow_detail_security_changed
        "workflow.complete_verified" -> R.string.msg_workflow_detail_complete_verified
        "workflow.complete_stock_unproven" -> R.string.msg_workflow_detail_complete_stock_unproven
        "workflow.complete_observational" -> R.string.msg_workflow_detail_complete_observational
        "backup.IMAGE_SIZE" -> R.string.msg_backup_image_size_values
        "readiness.STORAGE_LOW" -> R.string.msg_readiness_storage_required
        "check.RUNTIME" -> R.string.msg_check_runtime
        "check.MAGISK_BACKUP_ASSOCIATION_MISMATCH" -> R.string.msg_check_magisk_backup_association_mismatch
        "check.WRONG_MODEL" -> R.string.msg_check_wrong_model
        else -> if (key.substringBefore('.') in setOf("readiness", "restore", "firmware", "backup"))
            resource("check.${key.substringAfter('.')}") else null
    }

    fun text(context: Context, key: String, args: List<String> = emptyList()): String {
        if (key == "backup.IMAGE_SIZE" && args.size != 2) return context.getString(R.string.msg_check_image_size)
        if (key == "readiness.STORAGE_LOW" && args.size != 1) return context.getString(R.string.msg_check_storage_low)
        val id = resource(key) ?: return context.getString(R.string.msg_unknown, safeCode(key))
        return runCatching { context.getString(id, *args.toTypedArray()) }
            .getOrElse { context.getString(R.string.msg_unknown, safeCode(key)) }
    }

    fun check(context: Context, result: CheckResult, domain: CheckMessageDomain = CheckMessageDomain.GENERAL): String {
        val key = result.presentationKey
        return if (key != null) text(context, key, result.presentationArgs)
        else text(context, "${domain.name.lowercase()}.${result.code}".takeIf { resource(it) != null } ?: "check.${result.code}")
    }

    fun discovery(context: Context, assessment: DiscoveryAssessment): String =
        text(context, "discovery.${assessment.status.name}")

    fun monitor(context: Context, outcome: MonitorOutcome): String = text(context, "monitor.${outcome.name}")

    fun backupErrors(context: Context, candidate: BackupCandidate): List<String> {
        if (candidate.presentationMessages.isNotEmpty()) return candidate.presentationMessages.map { text(context, it.key, it.args) }
        val issues = candidate.validation?.issues.orEmpty().map { check(context, it, CheckMessageDomain.BACKUP) }
        return issues + if (candidate.errors.isNotEmpty()) listOf(text(context, "backup.unknown")) else emptyList()
    }

    fun snapshotEvidence(context: Context, observation: SnapshotObservation): List<String> =
        if (observation.presentationMessages.isNotEmpty()) observation.presentationMessages.map { text(context, it.key, it.args) }
        else if (observation.evidence.isNotEmpty()) listOf(text(context, "snapshot.unknown")) else emptyList()

    fun workflow(context: Context, run: UpdateRun): String = run.presentationKey?.let { text(context,it,run.presentationArgs) }
        ?: text(context, "workflow.${run.stage.name}")

    fun cds(context: Context, result: CdsCheckResult): String = text(context, "cds.${result.status.name}", when(result.status) {
        CdsStatus.INPUT_MISSING -> listOf(result.missingFields.joinToString(", ") { text(context,"cds.input.${it.name}") })
        CdsStatus.OFFER, CdsStatus.METADATA_ONLY -> listOf(result.targetBuild)
        CdsStatus.HTTP_ERROR -> listOf(result.httpStatus?.toString() ?: "UNKNOWN")
        else -> emptyList()
    })

    fun failure(context: Context, code: String?): String {
        resource("check.${code.orEmpty()}")?.let {return context.getString(it)}
        val id=when {
            code in setOf("CDS_NO_OFFER","CDS_METADATA_ONLY","CDS_OFFER")->R.string.ui_failure_no_offer
            code=="MOTOROLA_LIVE_SELECTION_REQUIRED" || code=="LIVE_SELECTION_TARGET_CHANGED"->R.string.ui_failure_origin
            code?.startsWith("MAGISK_OFFICIAL_")==true->R.string.ui_failure_magisk_manager
            code in setOf("MAGISK_PATCH_CONFIGURATION_UNSUPPORTED","MAGISK_PREINIT_DEVICE_UNPROVEN","MAGISK_LEGACY_SAR_UNSUPPORTED")->R.string.ui_failure_magisk_configuration
            code=="MAGISK_INACTIVE_WRITE_UNCERTAIN"->R.string.ui_failure_magisk_write
            code?.startsWith("DIRECT_OTA_")==true || code?.startsWith("OTA_STAGING_")==true || code?.startsWith("OTA_PAYLOAD_")==true->R.string.ui_failure_direct_ota
            code?.startsWith("RUN_")==true->R.string.ui_failure_authority
            else->null
        }
        return id?.let {context.getString(R.string.ui_failure_detail_code,context.getString(it),safeCode(code.orEmpty()))}
            ?:context.getString(R.string.msg_failure,safeCode(code.orEmpty()))
    }

    private fun safeCode(value: String): String = value.take(100).replace(Regex("[^A-Za-z0-9_.:-]"), "_").ifBlank { "UNKNOWN" }
}
