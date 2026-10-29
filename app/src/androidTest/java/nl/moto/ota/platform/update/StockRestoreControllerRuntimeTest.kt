package nl.moto.ota.platform.update

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.moto.ota.core.update.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID

/** App-private regular files only; no root process or block partition is opened. */
@RunWith(AndroidJUnit4::class)
class StockRestoreControllerRuntimeTest {
    private lateinit var root:File
    private lateinit var context:Context
    private lateinit var controller:StockRestoreController
    private lateinit var plan:StockRestorePlan
    private lateinit var transaction:File
    private val backup get()=File(transaction,"patched-backup.img")
    @Before fun setUp() {
        assumeTrue("Disposable emulator only",Build.HARDWARE in setOf("ranchu","goldfish","cutf_cvm"))
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        root=Files.createTempDirectory(app.cacheDir.toPath(),"restore-runtime-").toFile()
        context=object:ContextWrapper(app){override fun getFilesDir()=root;override fun getApplicationContext():Context=this}
        controller=StockRestoreController(context)
        transaction=File(root,"update/transactions/${UUID.randomUUID()}").apply {check(mkdirs())}
        listOf(File(root,"update"),File(root,"update/transactions"),transaction).forEach {Os.chmod(it.path,0x1c0)}
        val now=System.currentTimeMillis()
        plan=StockRestorePlan(transaction.name,UUID.randomUUID().toString(),ExecutionMode.LIVE,"XT9999-1","testmotorola","reteu","TEST","motorola/test/test:16/TEST/1:user/release-keys",
            "test-boot","_a",32L*1024*1024,"a".repeat(64),"b".repeat(64),now,now+60_000,1_000,61_000,"boot")
    }
    @After fun tearDown() {
        if(::root.isInitialized)Files.walk(root.toPath()).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}
    }
    private fun createBackup(bytes:Long=plan.imageBytes) {
        val hash=MessageDigest.getInstance("SHA-256")
        val chunk=ByteArray(65_536){(it%251).toByte()};var written=0L
        backup.outputStream().use {output->while(written<bytes){val n=minOf(chunk.size.toLong(),bytes-written).toInt();output.write(chunk,0,n);hash.update(chunk,0,n);written+=n}}
        Os.chmod(backup.path,0x180)
        plan=plan.copy(previousImageSha256=hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)})
        storePlan()
    }
    private fun storePlan()=RestoreRootProtocol.writePrivate(File(transaction,"request.properties"),StockRestorePlanCodec.encode(plan).toByteArray())
    private fun mustReject(code:String?=null,block:()->Unit) {
        val failure=runCatching(block).exceptionOrNull()
        assertNotNull("Unsafe export must fail",failure)
        if(code!=null)assertEquals(code,(failure as? UpdateSafetyException)?.code)
    }
    @Test fun bootRecoveryLargerThanOldEightMiBLimitStreamsWithBoundedWrites()=runBlocking {
        createBackup()
        var count=0L;var maximumWrite=0
        val hash=MessageDigest.getInstance("SHA-256")
        val output=object:OutputStream(){override fun write(value:Int){count++;hash.update(value.toByte())}
            override fun write(bytes:ByteArray,offset:Int,length:Int){count+=length;maximumWrite=maxOf(maximumWrite,length);hash.update(bytes,offset,length)}}
        assertEquals(plan.imageBytes,controller.exportPatchedBackup(plan,output))
        assertEquals(plan.imageBytes,count);assertTrue(maximumWrite<=65_536)
        assertEquals(plan.previousImageSha256,hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)})
    }
    @Test fun corruptBackupFailsBeforeExportingAnyByte() {
        createBackup();backup.outputStream().use {it.write(ByteArray(plan.imageBytes.toInt()))}
        var writes=0
        val output=object:OutputStream(){override fun write(value:Int){writes++};override fun write(bytes:ByteArray,offset:Int,length:Int){writes+=length}}
        mustReject("BACKUP_MISMATCH"){runBlocking {controller.exportPatchedBackup(plan,output)}}
        assertEquals(0,writes)
    }
    @Test fun truncatedBackupAndForgedPlanCannotBeExported() {
        createBackup(plan.imageBytes-1)
        mustReject("RESTORE_PRIVATE_FILE_INVALID"){runBlocking {controller.exportPatchedBackup(plan,OutputStream.nullOutputStream())}}
        createBackup()
        mustReject("PLAN_CHANGED"){runBlocking {controller.exportPatchedBackup(plan.copy(partitionType="init_boot"),OutputStream.nullOutputStream())}}
    }
    @Test fun symlinkToDifferentPrivateFileIsRejected() {
        createBackup()
        val other=File(root,"other.img");check(backup.renameTo(other));Files.createSymbolicLink(backup.toPath(),other.toPath())
        mustReject("RESTORE_PRIVATE_FILE_INVALID"){runBlocking {controller.exportPatchedBackup(plan,OutputStream.nullOutputStream())}}
    }
    @Test fun oldPerStepBooleanCannotAuthorizeAWrite() {
        createBackup(4096)
        mustReject("RUN_ACTION_AUTHORIZATION_REQUIRED"){runBlocking {controller.confirmAndExecute(plan,true,true)}}
    }
}
