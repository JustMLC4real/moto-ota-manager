package nl.moto.ota.core

import org.junit.Assert.*
import org.junit.Test

class DeviceCapabilitiesTest {
    private fun device()=safetyDevice().copy(sdkInt=37,abUpdate=true,partitionInventoryKnown=true,securityEvidenceSource="KERNEL_BOOTCONFIG")
    private fun association(d:DeviceState,b:BackupValidation)=BootBackupAssociation(b.partitionType,b.bytes,b.sha256,b.sha1,b.sha1,true,b.usableAsStock,d.bootId,d.capturedAtMillis)
    @Test fun qualifiedMercRetainsItsExistingCapabilities(){
        val d=device();val b=trustedBackup(d);val c=DeviceCapabilities.evaluate(d,b,association(d,b),true)
        assertEquals(DeviceSupportTier.QUALIFIED_MERC,c.supportTier);assertTrue(c.canPrepareStockRestore);assertTrue(c.canPreserveRootGuide)
    }
    @Test fun anotherMeasuredMotorolaCanBeQualifiedByProofWithoutAModelCatalog(){
        val d=device().copy(model="XT-FIXTURE-OTHER",device="fixture_other",channel="retgb",sdkInt=35,androidVersion="15")
        val b=trustedBackup(d);val c=DeviceCapabilities.evaluate(d,b,association(d,b),true)
        assertEquals(DeviceSupportTier.PROOF_QUALIFIED,c.supportTier);assertFalse(c.knownReferenceProfile)
        assertTrue(c.canPrepareStockRestore);assertTrue(c.canStartOfficialGuidance);assertTrue(c.canOpenOfficialUpdater)
    }
    @Test fun associatedBackupDoesNotBecomeSignedStock(){
        val d=device().copy(model="other-motorola",device="fixture_other")
        val b=trustedBackup(d).copy(provenanceVerified=false)
        val a=association(d,b).copy(signedProvenance=false)
        val c=DeviceCapabilities.evaluate(d,b,a,true)
        assertTrue(a.runtimeAssociated);assertTrue(c.canPreserveRootGuide);assertFalse(c.canPrepareStockRestore)
        assertEquals(DeviceSupportTier.MOTOROLA_GUIDED,c.supportTier)
    }
    @Test fun nonMotorolaStillHasDiagnosticsButNoMotorolaActions(){
        val d=device().copy(manufacturer="other");val b=trustedBackup(d)
        val c=DeviceCapabilities.evaluate(d,b,association(d,b),true)
        assertTrue(c.canDiagnose);assertFalse(c.canOpenOfficialUpdater);assertFalse(c.canStartOfficialGuidance);assertFalse(c.canPrepareStockRestore)
        assertEquals(DeviceSupportTier.UNSUPPORTED,c.supportTier)
    }
    @Test fun nonRootMotorolaCanUseItsOfficialUpdaterWithoutAFakeRootClaim(){
        val d=device().copy(rootGranted=false,rootChecked=false,magiskCode=0,partitionInventoryKnown=false,partitions=emptyList())
        val c=DeviceCapabilities.evaluate(d,officialUpdaterAvailable=true)
        assertTrue(c.canStartOfficialGuidance);assertTrue(c.canOpenOfficialUpdater);assertFalse(c.canPreserveRootGuide);assertFalse(c.canPrepareStockRestore)
    }
    @Test fun bootAndInitBootLayoutsComeFromObservedPartitions(){
        val init=DeviceCapabilities.evaluate(device());assertEquals(BootImageLayout.INIT_BOOT_AB,init.bootLayout)
        val boot=device().copy(partitions=listOf("boot_a","boot_b"),virtualAb=false,snapshot=SnapshotState.NOT_APPLICABLE)
        val c=DeviceCapabilities.evaluate(boot)
        assertEquals(BootImageLayout.BOOT_AB,c.bootLayout);assertTrue(c.canPreserveRootGuide);assertEquals(setOf("boot"),c.availableAbImageTypes)
    }
    @Test fun singleSlotMotorolaHasOfficialGuidanceButNoInactiveSlotProcedure(){
        val d=device().copy(slot="",abUpdate=false,virtualAb=false,snapshot=SnapshotState.NOT_APPLICABLE,partitions=listOf("boot"))
        val c=DeviceCapabilities.evaluate(d,officialUpdaterAvailable=true)
        assertEquals(BootImageLayout.BOOT_SINGLE,c.bootLayout);assertTrue(c.canStartOfficialGuidance);assertTrue(c.canOpenOfficialUpdater)
        assertFalse(c.canPreserveRootGuide);assertFalse(c.canPrepareStockRestore)
    }
    @Test fun unknownAndroidDoesNotDisableOfficialNavigationOrInventAWriteAdapter(){
        val d=device().copy(sdkInt=0,androidVersion="unknown")
        val c=DeviceCapabilities.evaluate(d,officialUpdaterAvailable=true)
        assertTrue(c.canOpenOfficialUpdater);assertTrue(c.canStartOfficialGuidance);assertFalse(c.canPrepareStockRestore)
        assertTrue(c.issues.any { it.code=="WRITE_RUNTIME_UNREVIEWED" })
    }
    @Test fun unknownInventoryAndContradictoryAbEvidenceAreNotGuessed(){
        assertEquals(BootImageLayout.AB_UNKNOWN,DeviceCapabilities.evaluate(device().copy(partitionInventoryKnown=false)).bootLayout)
        val c=DeviceCapabilities.evaluate(device().copy(abUpdate=false))
        assertEquals(BootImageLayout.UNKNOWN,c.bootLayout);assertFalse(c.canPreserveRootGuide);assertFalse(c.canPrepareStockRestore)
    }
    @Test fun staleOrDifferentBootAssociationCannotEnableWrites(){
        val d=device();val b=trustedBackup(d);val a=association(d,b)
        assertFalse(DeviceCapabilities.evaluate(d,b,a.copy(bootId="different")).canPrepareStockRestore)
        assertFalse(DeviceCapabilities.evaluate(d,b,a.copy(observedAtMillis=1)).canPrepareStockRestore)
        assertFalse(DeviceCapabilities.evaluate(d,b,a.copy(runtimeSha1="f".repeat(40))).canPrepareStockRestore)
    }
    @Test fun unavailableOrUnresolvedUpdaterIsNotClaimedAvailable(){
        assertFalse(DeviceCapabilities.evaluate(device()).canOpenOfficialUpdater)
        assertFalse(DeviceCapabilities.evaluate(device(),officialUpdaterAvailable=false).canOpenOfficialUpdater)
    }
    @Test fun identityConflictsBlockGuidanceAndWritingButKeepReadOnlyDiagnostics(){
        val d=device().copy(identityConflicts=listOf("MODEL_CONFLICT"));val b=trustedBackup(d)
        val c=DeviceCapabilities.evaluate(d,b,association(d,b),true)
        assertTrue(c.canDiagnose);assertFalse(c.canStartOfficialGuidance);assertFalse(c.canPrepareStockRestore)
    }
}
