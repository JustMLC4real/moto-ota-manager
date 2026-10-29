package nl.moto.ota.core.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SandboxUpdateSessionTest {
    private fun create()=SandboxUpdateSession.create(Files.createTempDirectory("moto-ota-sandbox-flow-").toFile())
    @Test fun fullOfflineUpdateChangesTwoRegularFilesAndDurablyCompletes(){
        val s=create();val currentBefore=s.partitionSha256("_b");val inactiveBefore=s.partitionSha256("_a")
        assertEquals(UpdateStage.STOCK_RESTORED,s.restoreStock().stage)
        assertNotEquals(currentBefore,s.partitionSha256("_b"));assertEquals(inactiveBefore,s.partitionSha256("_a"))
        assertEquals(UpdateStage.MAGISK_REQUIRED,s.simulateOfficialOta().stage)
        assertNotEquals(inactiveBefore,s.partitionSha256("_a"))
        assertEquals(StepEvidence.USER_REPORTED,s.simulateMagiskHandoff().magiskEvidence)
        assertEquals(UpdateStage.MERGING,s.simulateRebootAndMerge().stage)
        val done=s.simulateMergeFinished()
        assertEquals(UpdateStage.COMPLETE,done.stage);assertTrue(done.simulated);assertTrue(done.newStockBackupVerified)
        assertEquals(StepEvidence.OBSERVED_AFTER_BOOT,done.magiskEvidence);assertEquals("_a",s.device.slot)
        assertEquals(done,s.journal());assertTrue(File(s.directory,"new-stock-backup.img").isFile)
        val backups=File(s.directory,"transactions").listFiles()!!.map { File(it,"patched-backup.img") }
        assertTrue(backups.single().isFile);assertEquals(currentBefore,nl.moto.ota.core.sha256(backups.single().readBytes()))
    }
    @Test fun existingSandboxCannotBeAccidentallyRestartedOrReset(){
        val s=create();s.restoreStock();val before=s.partitionSha256("_b")
        try{SandboxUpdateSession.create(s.directory);fail()}catch(_:IllegalArgumentException){}
        assertEquals(before,s.partitionSha256("_b"));assertEquals(UpdateStage.STOCK_RESTORED,s.journal().stage)
    }
    @Test fun outOfOrderMockActionsDoNotChangePartitions(){
        val s=create();val before=s.partitionSha256("_a")
        try{s.simulateMagiskHandoff();fail()}catch(_:IllegalArgumentException){}
        try{s.simulateRebootAndMerge();fail()}catch(_:IllegalArgumentException){}
        assertEquals(before,s.partitionSha256("_a"));assertEquals(UpdateStage.STOCK_PREPARATION,s.journal().stage)
    }
}
