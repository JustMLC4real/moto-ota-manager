package nl.moto.ota.platform.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.moto.ota.core.importer.MotorolaImportPolicy
import nl.moto.ota.core.importer.MotorolaImportFailure
import nl.moto.ota.core.importer.MotorolaImportError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

/** Ordinary app UID and synthetic private files only; the root transport is never instantiated. */
@RunWith(AndroidJUnit4::class)
class MotorolaImporterGuardInstrumentedTest {
    @Test fun normalAppUidCannotEnterRootImporter(){
        assertFalse(ImportRuntimePolicy.accepts(android.os.Process.myUid(),android.os.Build.VERSION.SDK_INT,"motorola",listOf("XT2607-1"),"merc",listOf("reteu"),"17"))
    }
    @Test fun fileBackedCopyDoesNotChangeSourceAndProducesExactPrivateBytes(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"local-import-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val source=File(root,"source").apply { check(mkdirs()) };val destination=File(root,"destination").apply { check(mkdirs()) }
        val bytes=ByteArray(65_537){(it%251).toByte()};val file=File(source,"fixture-update.zip").apply { writeBytes(bytes) }
        val receipt=MotorolaImportPolicy.copy(source,file.name,File(destination,"copied.zip"),availableBytes={1024L*1024*1024})
        assertEquals(bytes.size.toLong(),receipt.bytes);assertArrayEquals(bytes,file.readBytes());assertArrayEquals(bytes,File(destination,"copied.zip").readBytes())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)},receipt.sha256)
    }
    @Test fun lateSelectionCannotBeOverwrittenByAnOlderImport(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"import-selection-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val absent=ImportSelection.capture(root)
        val selected=File(root,"selected-ota.zip").apply { writeBytes(byteArrayOf(1,2,3)) }
        try{ImportSelection.requireUnchanged(root,absent);fail()}catch(e:IllegalStateException){assertEquals("MOTOROLA_IMPORT_SELECTION_CHANGED",e.message)}
        val before=ImportSelection.capture(root)
        ImportSelection.requireUnchanged(root,before)
        val newer=File(root,"new.zip").apply { writeBytes(byteArrayOf(4,5,6)) }
        java.nio.file.Files.move(newer.toPath(),selected.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        try{ImportSelection.requireUnchanged(root,before);fail()}catch(_:IllegalStateException){}
        assertArrayEquals(byteArrayOf(4,5,6),selected.readBytes())
    }
    @Test fun actualSourceSymlinkIsNeitherDiscoveredNorRead(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"import-source-symlink-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val source=File(root,"source").apply { check(mkdirs()) };val destination=File(root,"destination").apply { check(mkdirs()) }
        val outside=File(root,"outside-private-fixture.bin").apply { writeBytes(byteArrayOf(8,7,6,5)) }
        val link=File(source,"linked-update.zip")
        java.nio.file.Files.createSymbolicLink(link.toPath(),outside.toPath())
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertTrue(MotorolaImportPolicy.discover(source).isEmpty())
        try{MotorolaImportPolicy.copy(source,link.name,File(destination,"selected.zip"),availableBytes={1024L*1024*1024});fail()}
        catch(e:MotorolaImportFailure){assertEquals(MotorolaImportError.SOURCE_INVALID,e.code)}
        assertFalse(File(destination,"selected.zip").exists());assertArrayEquals(byteArrayOf(8,7,6,5),outside.readBytes())
    }
    @Test fun actualDestinationSymlinkCannotReplaceItsTarget(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"import-destination-symlink-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val source=File(root,"source").apply { check(mkdirs()) };val destination=File(root,"destination").apply { check(mkdirs()) }
        val original=File(root,"preserved-private-fixture.bin").apply { writeBytes(byteArrayOf(1,3,5,7)) }
        File(source,"update.zip").writeBytes(byteArrayOf(9,9,9,9))
        val link=File(destination,"selected.zip")
        java.nio.file.Files.createSymbolicLink(link.toPath(),original.toPath())
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        try{MotorolaImportPolicy.copy(source,"update.zip",link,availableBytes={1024L*1024*1024});fail()}
        catch(e:MotorolaImportFailure){assertEquals(MotorolaImportError.DESTINATION_INVALID,e.code)}
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()));assertArrayEquals(byteArrayOf(1,3,5,7),original.readBytes())
    }
    @Test fun cancelledTransactionCleanupDoesNotFollowChildSymlinkOrDeleteOtherData()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"import-cleanup-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val transactions=File(root,"motorola-import/transactions").apply { check(mkdirs()) }
        val tx=File(transactions,UUID.randomUUID().toString()).apply { check(mkdir()) }
        File(tx,"request.json").writeText("fixture-request")
        File(tx,"package.zip").writeBytes(ByteArray(1_048_576){19})
        val outside=File(root,"outside").apply { check(mkdir()) }
        val preserved=File(outside,"backup.img").apply { writeBytes(byteArrayOf(7,8,9)) }
        java.nio.file.Files.createSymbolicLink(File(tx,"outside-link").toPath(),outside.toPath())
        val selected=File(root,"selected-ota.zip").apply { writeBytes(byteArrayOf(4,5,6)) }
        val sibling=File(transactions,UUID.randomUUID().toString()).apply { check(mkdir()) }
        File(sibling,"package.zip").writeBytes(byteArrayOf(2,3,4))
        try{ImportTransactionCleanup.withCleanup(transactions,tx){throw CancellationException("fixture cancellation")};fail()}
        catch(_:CancellationException){}
        assertFalse(tx.exists());assertArrayEquals(byteArrayOf(7,8,9),preserved.readBytes())
        assertArrayEquals(byteArrayOf(4,5,6),selected.readBytes());assertArrayEquals(byteArrayOf(2,3,4),File(sibling,"package.zip").readBytes())
    }
    @Test fun lateSelectionFailureCleansOnlyItsStagedTransaction()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"import-late-cleanup-${UUID.randomUUID()}").apply { check(mkdirs()) }.canonicalFile
        val selected=File(root,"selected-ota.zip").apply { writeBytes(byteArrayOf(1,2,3)) }
        val baseline=ImportSelection.capture(root)
        val transactions=File(root,"motorola-import/transactions").apply { check(mkdirs()) }
        val tx=File(transactions,UUID.randomUUID().toString()).apply { check(mkdir()) }
        File(tx,"request.json").writeText("fixture-request")
        val staged=File(tx,"package.zip").apply { writeBytes(ByteArray(1_048_576){23}) }
        val newer=File(root,"newer.zip").apply { writeBytes(byteArrayOf(9,8,7)) }
        java.nio.file.Files.move(newer.toPath(),selected.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        try{ImportTransactionCleanup.withCleanup(transactions,tx){ImportSelection.requireUnchanged(root,baseline)};fail()}
        catch(e:IllegalStateException){assertEquals("MOTOROLA_IMPORT_SELECTION_CHANGED",e.message)}
        assertFalse(staged.exists());assertFalse(tx.exists());assertArrayEquals(byteArrayOf(9,8,7),selected.readBytes())
    }
}
