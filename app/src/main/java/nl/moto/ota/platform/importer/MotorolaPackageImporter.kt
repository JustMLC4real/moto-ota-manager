package nl.moto.ota.platform.importer

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.moto.ota.core.importer.MotorolaImportPolicy
import nl.moto.ota.platform.download.DownloadController
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ExistingMotorolaPackage(val id:String,val bytes:Long) {
    override fun toString():String="ExistingMotorolaPackage(bytes=$bytes)"
}

/** Explicit local import. It never stages into Motorola, authenticates, installs or changes the source ZIP. */
class MotorolaPackageImporter(private val context:Context) {
    private val files=context.filesDir.canonicalFile
    private val directory=File(files,"motorola-import").apply { check(mkdirs() || isDirectory) }.canonicalFile
    private val transport=ImporterTransport(context.applicationInfo.sourceDir)
    private val downloads=DownloadController(context)
    init {
        require(directory.parentFile==files && !Files.isSymbolicLink(File(files,"motorola-import").toPath()))
    }
    suspend fun discover():List<ExistingMotorolaPackage> {
        val result=transport.invoke("discover").getJSONArray("packages")
        require(result.length()<=12)
        return (0 until result.length()).map { index ->
            val entry=result.getJSONObject(index);val id=entry.getString("id");val bytes=entry.getLong("bytes")
            require(MotorolaImportPolicy.validId(id) && bytes in 1..12L*1024*1024*1024)
            ExistingMotorolaPackage(id,bytes)
        }.also { require(it.map(ExistingMotorolaPackage::id).distinct().size==it.size) }
    }
    suspend fun import(id:String):File=withContext(Dispatchers.IO) { IMPORTS.withLock {
        require(MotorolaImportPolicy.validId(id)){"MOTOROLA_PACKAGE_ID_INVALID"}
        // Pause and capture atomically against other selection actions, then release
        // that mutex for the long root copy. No nested controller action is used.
        val selectionBefore=downloads.withPausedSelection { ImportSelection.capture(files) }
        val transactions=File(directory,"transactions")
        require(!Files.isSymbolicLink(transactions.toPath()) && transactions.canonicalFile.parentFile==directory)
        check(transactions.mkdirs() || transactions.isDirectory)
        val tx=File(transactions,UUID.randomUUID().toString()).apply { check(mkdir()) }
        require(tx.canonicalPath==tx.path && !Files.isSymbolicLink(tx.toPath()))
        ImportTransactionCleanup.withCleanup(transactions,tx) {
        val request=JSONObject().put("schema",1).put("id",id).toString().toByteArray(Charsets.UTF_8)
        val requestFile=File(tx,"request.json")
        FileOutputStream(requestFile).use { it.write(request);it.fd.sync() }
        Os.chmod(requestFile.path,0x180)
        val result=transport.invoke("copy",tx.name)
        coroutineContext.ensureActive()
        val bytes=result.getLong("bytes");val expected=result.getString("sha256")
        require(bytes in 1..12L*1024*1024*1024 && expected.matches(Regex("[a-f0-9]{64}")))
        val staged=File(tx,"package.zip")
        val before=Os.lstat(staged.path)
        require(staged.canonicalPath==staged.path && OsConstants.S_ISREG(before.st_mode) && before.st_uid==android.os.Process.myUid() && before.st_size==bytes)
        val digest=MessageDigest.getInstance("SHA-256");var count=0L
        Files.newInputStream(staged.toPath(),NOFOLLOW_LINKS).use { input ->val buffer=ByteArray(1_048_576);while(true){
            coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=bytes);digest.update(buffer,0,n)
        } }
        val actual=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        val after=Os.lstat(staged.path)
        require(count==bytes && actual==expected && before.st_dev==after.st_dev && before.st_ino==after.st_ino && before.st_size==after.st_size && before.st_mtime==after.st_mtime && before.st_ctime==after.st_ctime)
        downloads.withPausedSelection {
            coroutineContext.ensureActive()
            ImportSelection.requireUnchanged(files,selectionBefore)
            val destination=File(files,"selected-ota.zip")
            require(destination.parentFile.canonicalFile==files && !Files.isSymbolicLink(destination.toPath()))
            val current=Os.lstat(staged.path);require(current.st_dev==after.st_dev && current.st_ino==after.st_ino && current.st_size==bytes && current.st_mtime==after.st_mtime && current.st_ctime==after.st_ctime)
            Files.move(staged.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            val fd=Os.open(files.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            try{require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}
            destination
        }
        }
    } }
    companion object { private val IMPORTS=Mutex() }
}
