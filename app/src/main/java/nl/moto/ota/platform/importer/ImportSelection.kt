package nl.moto.ota.platform.importer

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal data class ImportSelectionIdentity(val device:Long,val inode:Long,val bytes:Long,val modified:Long,val changed:Long,val modifiedNanos:Long)

/** Capture/check under DownloadController's selection mutex; no path or package data is returned. */
internal object ImportSelection {
    fun capture(files:File):ImportSelectionIdentity? {
        val file=File(files,"selected-ota.zip")
        require(file.parentFile.canonicalFile==files.canonicalFile && !Files.isSymbolicLink(file.toPath()))
        val stat=try{Os.lstat(file.path)}catch(e:ErrnoException){if(e.errno==OsConstants.ENOENT)return null else throw e}
        require(OsConstants.S_ISREG(stat.st_mode))
        val nanos=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).lastModifiedTime().to(TimeUnit.NANOSECONDS)
        return ImportSelectionIdentity(stat.st_dev,stat.st_ino,stat.st_size,stat.st_mtime,stat.st_ctime,nanos)
    }
    fun requireUnchanged(files:File,expected:ImportSelectionIdentity?){
        check(capture(files)==expected){"MOTOROLA_IMPORT_SELECTION_CHANGED"}
    }
}
