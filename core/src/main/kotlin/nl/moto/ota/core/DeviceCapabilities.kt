package nl.moto.ota.core

enum class DeviceSupportTier { QUALIFIED_MERC, PROOF_QUALIFIED, MOTOROLA_GUIDED, MOTOROLA_DIAGNOSTIC, UNSUPPORTED }
enum class BootImageLayout { INIT_BOOT_AB, BOOT_AB, INIT_BOOT_SINGLE, BOOT_SINGLE, AB_UNKNOWN, UNKNOWN }

/** An observed backup association is distinct from independently authenticated stock provenance. */
data class BootBackupAssociation(
    val partitionType:String,
    val bytes:Long,
    val sha256:String,
    val sha1:String,
    val runtimeSha1:String,
    val structurallyValid:Boolean,
    val signedProvenance:Boolean,
    val bootId:String,
    val observedAtMillis:Long
) {
    val runtimeAssociated:Boolean get()=structurallyValid && partitionType in setOf("boot","init_boot") && bytes>0 &&
        sha256.matches(Regex("[a-f0-9]{64}")) && sha1.matches(Regex("[a-f0-9]{40}")) && sha1==runtimeSha1
    fun freshFor(device:DeviceState,nowMillis:Long):Boolean=runtimeAssociated && bootId.isNotBlank() && bootId==device.bootId &&
        observedAtMillis>0 && nowMillis-observedAtMillis in 0..120_000
}

data class DeviceCapabilityReport(
    val supportTier:DeviceSupportTier,
    val bootLayout:BootImageLayout,
    val motorola:Boolean,
    val knownReferenceProfile:Boolean,
    val canDiagnose:Boolean,
    /** Requires an actual Android resolver result. Navigation does not approve installation. */
    val canOpenOfficialUpdater:Boolean,
    val officialUpdaterAvailable:Boolean?,
    val canStartOfficialGuidance:Boolean,
    val canPreserveRootGuide:Boolean,
    val canPrepareStockRestore:Boolean,
    val availableAbImageTypes:Set<String>,
    val issues:List<CheckResult>
)

/** Capabilities come from measured identity/layout/evidence, never from a guessed model catalog. */
object DeviceCapabilities {
    val supportedRestoreSdks:Set<Int> = (33..37).toSet()
    fun knownRestoreRuntime(device:DeviceState):Boolean=device.sdkInt in supportedRestoreSdks &&
        device.androidVersion==(device.sdkInt-20).toString()

    fun evaluate(device:DeviceState,backup:BackupValidation?=null,association:BootBackupAssociation?=null,
                 officialUpdaterAvailable:Boolean?=null,nowMillis:Long=System.currentTimeMillis()):DeviceCapabilityReport {
        val d=device
        val motorola=d.manufacturer.equals("motorola",true)
        val fresh=d.capturedAtMillis>0 && nowMillis-d.capturedAtMillis in 0..120_000
        val identity=d.model.isNotBlank() && d.device.isNotBlank() && d.build.isNotBlank() && d.fingerprint.isNotBlank() && d.identityConflicts.isEmpty()
        val slot=d.slot in setOf("_a","_b")
        val pairs=buildSet {
            if(d.partitionInventoryKnown && d.partitions.containsAll(listOf("init_boot_a","init_boot_b")))add("init_boot")
            if(d.partitionInventoryKnown && d.partitions.containsAll(listOf("boot_a","boot_b")))add("boot")
        }
        val abContradiction=d.abUpdate==false && (d.virtualAb==true || slot || pairs.isNotEmpty())
        val ab=!abContradiction && (d.abUpdate==true || d.virtualAb==true || (slot && pairs.isNotEmpty()))
        val layout=when {
            abContradiction->BootImageLayout.UNKNOWN
            ab && slot && "init_boot" in pairs->BootImageLayout.INIT_BOOT_AB
            ab && slot && "boot" in pairs->BootImageLayout.BOOT_AB
            ab->BootImageLayout.AB_UNKNOWN
            d.abUpdate==false && d.virtualAb!=true && d.partitionInventoryKnown && "init_boot" in d.partitions->BootImageLayout.INIT_BOOT_SINGLE
            d.abUpdate==false && d.virtualAb!=true && d.partitionInventoryKnown && "boot" in d.partitions->BootImageLayout.BOOT_SINGLE
            else->BootImageLayout.UNKNOWN
        }
        val start=motorola && identity && fresh && !d.simulated && d.bootId.isNotBlank()
        val rootGuide=start && ab && slot && pairs.isNotEmpty() && d.rootChecked && d.rootGranted && d.magiskCode>0
        val associationOk=association?.freshFor(d,nowMillis)==true && association.partitionType in pairs
        val sourceOk=backup?.usableFor(d)==true && backup.partitionType in pairs && associationOk &&
            association?.signedProvenance==true && association.sha256==backup.sha256 && association.bytes==backup.bytes
        val restore=rootGuide && sourceOk && knownRestoreRuntime(d) && d.magiskCode==31100 &&
            snapshotSafe(d) && d.engine==EngineState.IDLE && d.bootSuccessful==true && d.bootloaderUnlocked==true &&
            d.verifiedBootState=="orange" && d.securityEvidenceSource=="KERNEL_BOOTCONFIG" && d.moduleInventoryKnown &&
            d.batteryPercent in 50..100 && d.freeBytes>=512L*1024*1024
        val issues=buildList {
            fun addIssue(code:String){add(CheckResult(code,code,"capability.$code"))}
            if(!motorola)addIssue("MOTOROLA_REQUIRED")
            if(!identity)addIssue("IDENTITY_INCOMPLETE")
            if(!fresh)addIssue("OBSERVATIONS_STALE")
            if(d.simulated)addIssue("SIMULATION")
            if(officialUpdaterAvailable==null)addIssue("UPDATER_UNCHECKED")
            if(officialUpdaterAvailable==false)addIssue("UPDATER_UNAVAILABLE")
            if(abContradiction)addIssue("AB_EVIDENCE_CONFLICT")
            if(layout==BootImageLayout.UNKNOWN || layout==BootImageLayout.AB_UNKNOWN)addIssue("BOOT_LAYOUT_UNKNOWN")
            if(!rootGuide)addIssue("ROOT_AB_GUIDANCE_UNAVAILABLE")
            if(rootGuide && !associationOk)addIssue("BACKUP_ASSOCIATION_UNPROVEN")
            if(!sourceOk)addIssue("SIGNED_CURRENT_IMAGE_REQUIRED")
            if(!knownRestoreRuntime(d))addIssue("WRITE_RUNTIME_UNREVIEWED")
            if(d.magiskCode!=31100)addIssue("WRITE_MAGISK_UNREVIEWED")
        }
        val tier=when {
            !motorola->DeviceSupportTier.UNSUPPORTED
            restore && d.exactProfileMatch->DeviceSupportTier.QUALIFIED_MERC
            restore->DeviceSupportTier.PROOF_QUALIFIED
            start->DeviceSupportTier.MOTOROLA_GUIDED
            else->DeviceSupportTier.MOTOROLA_DIAGNOSTIC
        }
        return DeviceCapabilityReport(tier,layout,motorola,d.exactProfileMatch,true,motorola && officialUpdaterAvailable==true && !d.simulated,
            officialUpdaterAvailable,start,rootGuide,restore,pairs,issues)
    }

    fun snapshotSafe(device:DeviceState):Boolean=when(device.virtualAb){
        true->device.snapshot==SnapshotState.NONE
        false->device.snapshot in setOf(SnapshotState.NOT_APPLICABLE,SnapshotState.NONE)
        null->false
    }
}
