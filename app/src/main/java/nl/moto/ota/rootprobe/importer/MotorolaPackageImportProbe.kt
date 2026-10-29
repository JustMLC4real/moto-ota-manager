package nl.moto.ota.rootprobe.importer

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import nl.moto.ota.core.importer.MotorolaImportPolicy
import nl.moto.ota.core.importer.MotorolaImportFailure
import nl.moto.ota.platform.importer.ImportRuntimePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Reads an already-existing updater ZIP and writes only one unique private staging file. */
object MotorolaPackageImportProbe {
    private const val FILES="/data/user/0/nl.moto.ota/files"
    private const val SOURCE="/storage/emulated/0/Android/data/com.motorola.ccc.ota/files"
    @JvmStatic fun main(args:Array<String>){
        var phase="MOTOROLA_IMPORT_RUNTIME_UNSUPPORTED"
        val timeout=if(args.firstOrNull()=="copy")1_800_000L else 30_000L
        val watchdog=thread(isDaemon=true,name="motorola-import-deadline"){
            try{Thread.sleep(timeout)}catch(_:InterruptedException){return@thread}
            println("{\"schema\":1,\"error\":\"MOTOROLA_IMPORT_DEADLINE\"}");System.out.flush();Runtime.getRuntime().halt(70)
        }
        try{
            require(android.os.Process.myUid()==0 && Build.VERSION.SDK_INT==37)
            phase="MOTOROLA_IMPORT_ARGUMENTS_INVALID"
            require(args.isNotEmpty() && args[0] in setOf("discover","copy"))
            require(if(args[0]=="discover")args.size==1 else args.size==2 && ImportRuntimePolicy.allowedTransactionId(args[1]))
            phase="MOTOROLA_IMPORT_PROFILE_UNVERIFIED"
            fun property(key:String)=command(listOf("/system/bin/getprop",key)).trim()
            val models=listOf(property("ro.boot.hardware.sku"),property("ro.boot.product.hardware.sku"),property("ro.product.model")).filter { it.matches(Regex("XT[0-9]+(?:-[0-9]+)?",RegexOption.IGNORE_CASE)) }
            require(ImportRuntimePolicy.accepts(android.os.Process.myUid(),Build.VERSION.SDK_INT,property("ro.product.manufacturer"),models,
                property("ro.product.device"),listOf(property("ro.boot.carrier"),property("ro.carrier")),property("ro.build.version.release")))
            val original=File(SOURCE)
            phase="MOTOROLA_UPDATER_DIRECTORY_UNREADABLE"
            val sourceStat=try { Os.lstat(original.path) }catch(e:ErrnoException){
                if(args[0]=="discover" && e.errno==OsConstants.ENOENT){
                    println(JSONObject().put("schema",1).put("packages",JSONArray()).toString());System.out.flush();watchdog.interrupt();kotlin.system.exitProcess(0)
                }
                throw e
            }
            require(OsConstants.S_ISDIR(sourceStat.st_mode))
            require(original.isDirectory && !Files.isSymbolicLink(original.toPath()))
            val source=original.canonicalFile
            phase="MOTOROLA_IMPORT_PATH_IDENTITY_OR_IO_FAILED"
            val result=if(args[0]=="discover"){
                val entries=MotorolaImportPolicy.discover(source)
                JSONObject().put("schema",1).put("packages",JSONArray().also { array ->entries.forEach { array.put(JSONObject().put("id",it.id).put("bytes",it.bytes)) } })
            }else copy(source,args[1])
            println(result.toString());System.out.flush()
        }catch(error:Throwable){
            val code=(error as? MotorolaImportFailure)?.let { "MOTOROLA_IMPORT_"+it.code.name } ?: phase
            println(JSONObject().put("schema",1).put("error",code).toString());System.out.flush()
        }
        watchdog.interrupt();kotlin.system.exitProcess(0)
    }
    private fun copy(source:File,id:String):JSONObject {
        val files=File(FILES).canonicalFile
        require(ImportRuntimePolicy.allowedFilesRoot(files.path))
        val owner=Os.stat(files.path).st_uid;require(owner>=10_000)
        val directory=File(files,"motorola-import");val tx=File(directory,"transactions/$id")
        require(tx.isDirectory && tx.canonicalPath==tx.path && Os.lstat(tx.path).st_uid==owner && Os.lstat(tx.path).st_mode and 0x002==0)
        val request=File(tx,"request.json");val stat=Os.lstat(request.path)
        require(request.canonicalPath==request.path && OsConstants.S_ISREG(stat.st_mode) && stat.st_uid==owner && stat.st_mode and 0x03f==0 && stat.st_size in 1..4096)
        val requestBytes=request.inputStream().use { it.readNBytes(4097) };require(requestBytes.size<=4096)
        val parsed=JSONObject(requestBytes.toString(Charsets.UTF_8));require(parsed.getInt("schema")==1)
        val name=parsed.getString("id");require(MotorolaImportPolicy.validId(name))
        val lockFile=File(directory,"copy.lock")
        require(!Files.isSymbolicLink(lockFile.toPath()) && lockFile.canonicalPath==lockFile.path)
        RandomAccessFile(lockFile,"rw").channel.use { channel ->
            Os.chown(lockFile.path,owner,owner);Os.chmod(lockFile.path,0x180)
            val lock=channel.tryLock() ?: error("IMPORT_ALREADY_RUNNING")
            lock.use {
                val marker=File(tx,"copy.started");val markerFd=Os.open(marker.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180)
                try{Os.fsync(markerFd)}finally{Os.close(markerFd)}
                val candidate=File(source,name);require(!Files.isSymbolicLink(candidate.toPath()) && candidate.canonicalFile.parentFile==source)
                val before=Os.lstat(candidate.path);require(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..12L*1024*1024*1024)
                val destination=File(tx,"package.zip");require(!destination.exists() && !Files.isSymbolicLink(destination.toPath()))
                val receipt=MotorolaImportPolicy.copy(source,name,destination,availableBytes={
                    // The app withdraws request.json before its scoped finally cleanup.
                    // An orphan root copy must stop growing an unlinked staging inode.
                    require(Files.isDirectory(tx.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        Files.isRegularFile(request.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    val live=Os.lstat(request.path)
                    require(live.st_dev==stat.st_dev && live.st_ino==stat.st_ino && live.st_uid==owner)
                    android.os.StatFs(tx.path).availableBytes
                })
                val after=Os.lstat(candidate.path)
                require(OsConstants.S_ISREG(after.st_mode) && before.st_dev==after.st_dev && before.st_ino==after.st_ino && before.st_size==after.st_size && before.st_mtime==after.st_mtime && before.st_ctime==after.st_ctime)
                Os.chown(destination.path,owner,owner);Os.chmod(destination.path,0x180)
                val fd=Os.open(tx.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
                try{require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}
                return JSONObject().put("schema",1).put("bytes",receipt.bytes).put("sha256",receipt.sha256)
            }
        }
    }
    private fun command(args:List<String>):String {
        val process=ProcessBuilder(args).redirectErrorStream(true).start()
        val output=java.io.ByteArrayOutputStream();var overflow=false
        val reader=thread(isDaemon=true){process.inputStream.use { input ->val b=ByteArray(1024);while(true){val n=input.read(b);if(n<0)break;if(output.size()+n<=4096)output.write(b,0,n)else overflow=true} }}
        val complete=process.waitFor(2,TimeUnit.SECONDS);if(!complete)process.destroyForcibly();reader.join(500)
        require(complete && !reader.isAlive && !overflow && process.exitValue()==0)
        return output.toString("UTF-8")
    }
}
