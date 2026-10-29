package nl.moto.ota.platform.update

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only isolated app-private files and immutable fixture observations; never su/device writes. */
@RunWith(AndroidJUnit4::class)
class UpdateRunStoreInstrumentedTest {
    private fun isolatedContext():Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val files=File(base.cacheDir,"update-journal-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        return object:ContextWrapper(base){override fun getFilesDir():File=files;override fun getApplicationContext():Context=this}
    }
    private fun run()=UpdateRun(id=UUID.randomUUID().toString(),stage=UpdateStage.STOCK_PREPARATION,sourceBuild="fixture-source",sourceFingerprint="fixture-source-fp",
        sourceBootId="fixture-source-boot",sourceSlot="_b",targetBuild="fixture-target",targetSlot="_a",stockSha256="a".repeat(64),
        lastObservationMillis=System.currentTimeMillis(),lastObservedBootId="fixture-source-boot",lastObservedBuild="fixture-source",lastObservedSlot="_b",
        lastObservedEngine=EngineState.IDLE,lastObservedSnapshot=SnapshotState.NONE)
    private fun plan(run:UpdateRun,tx:String=UUID.randomUUID().toString()):StockRestorePlan {
        val now=System.currentTimeMillis();val elapsed=android.os.SystemClock.elapsedRealtime()
        return StockRestorePlan(tx,run.id,ExecutionMode.LIVE,"XT2607-1","merc","reteu",run.sourceBuild,run.sourceFingerprint,run.sourceBootId,run.sourceSlot,
            8_388_608,run.stockSha256,"b".repeat(64),now,now+60_000,elapsed,elapsed+60_000)
    }
    @Test fun recreatedControllerLoadsCriticalJournalWithoutDispatchingAnything(){
        val c=isolatedContext();val r=run();UpdateRunStore(c).save(r)
        val first=UpdateController(c);val p=plan(r)
        first.prepareRestore(p);first.restoreDispatching(r.id,p.transactionId)
        val recreated=UpdateController(c).load()
        assertEquals(UpdateStage.STOCK_WRITE_RUNNING,recreated.stage);assertEquals(p.transactionId,recreated.restoreTransactionId)
        assertEquals(1,c.filesDir.listFiles()!!.size) // No root transaction or partition side effects.
    }
    @Test fun oldConfirmationCannotClaimANewerPreparedTransaction(){
        val c=isolatedContext();val r=run();UpdateRunStore(c).save(r);val controller=UpdateController(c)
        val first=plan(r);val second=plan(r)
        controller.prepareRestore(first);controller.prepareRestore(second)
        try{controller.restoreDispatching(r.id,first.transactionId);fail()}catch(_:IllegalArgumentException){}
        assertEquals(UpdateStage.STOCK_CONFIRMATION,controller.load().stage)
        assertEquals(second.transactionId,controller.load().restoreTransactionId)
    }
    @Test fun atomicFileBackupIsRecoveredWhenBaseFileIsMissing(){
        val c=isolatedContext();val r=run().copy(stage=UpdateStage.STOCK_WRITE_RUNNING,restoreTransactionId=UUID.randomUUID().toString())
        val file=File(c.filesDir,"guided-update-run.properties")
        File(file.path+".bak").writeText(r.encode())
        assertFalse(file.exists());assertEquals(r,UpdateRunStore(c).load());assertTrue(file.exists())
    }
    @Test fun existingEmptyJournalFailsClosedWhileMissingJournalIsIdle(){
        val c=isolatedContext();val store=UpdateRunStore(c)
        assertEquals(UpdateStage.IDLE,store.load().stage)
        File(c.filesDir,"guided-update-run.properties").writeText("")
        assertEquals(UpdateStage.RECOVERY_REQUIRED,store.load().stage)
    }
    @Test fun oversizedJournalFailsClosedWithoutLoadingAllBytes(){
        val c=isolatedContext()
        File(c.filesDir,"guided-update-run.properties").writeBytes(ByteArray(131_073){65})
        assertEquals(UpdateStage.RECOVERY_REQUIRED,UpdateRunStore(c).load().stage)
    }
}
