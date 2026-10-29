package nl.moto.ota.rootprobe

import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.update.UpdateRun
import java.io.File
import java.security.MessageDigest

/** AOSP update_engine can read ota_package_file, not arbitrary app_data_file.
 * Only a unique root-owned child of an EXISTING system OTA directory is used.
 * restorecon applies the firmware's normal file label; no policy is modified. */
internal object RootOtaPayloadStore {
    private const val BASE="/data/ota_package"
    private fun directory(run:UpdateRun):File {
        require(java.util.UUID.fromString(run.id).toString()==run.id)
        val base=File(BASE)
        require(base.canonicalPath==BASE)
        val s=Os.lstat(BASE)
        require(OsConstants.S_ISDIR(s.st_mode) && s.st_uid in setOf(0,1000) && s.st_mode and 0x002==0)
        require(Os.getxattr(BASE,"security.selinux").toString(Charsets.US_ASCII).trimEnd('\u0000')=="u:object_r:ota_package_file:s0")
        return File(base,"moto-ota-${run.id}")
    }
    fun prepare(run:UpdateRun,source:File,bytes:Long,sha256:String,owner:Int):File {
        val directory=directory(run)
        require(!directory.exists() && StatFs(BASE).availableBytes>=bytes+2L*1024*1024*1024)
        Os.mkdir(directory.path,0x1c9) // 0711; update_engine traverses a known exact name.
        val pending=File(directory,"payload.pending")
        val input=Os.open(source.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try {
            require(RootUpdateFacts.digestDescriptor(input,bytes,owner)==sha256)
            val output=Os.open(pending.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
            try {
                val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65_536);var remaining=bytes
                while(remaining>0){val n=Os.read(input,buffer,0,minOf(buffer.size.toLong(),remaining).toInt());require(n>0);md.update(buffer,0,n)
                    var at=0;while(at<n){val wrote=Os.write(output,buffer,at,n-at);require(wrote>0);at+=wrote};remaining-=n}
                require(Os.read(input,buffer,0,1)==0 && md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==sha256)
                Os.fchown(output,0,0);Os.fchmod(output,0x124);Os.fsync(output) // 0444; public official firmware, no identifiers.
            }finally{Os.close(output)}
        }finally{Os.close(input)}
        val payload=File(directory,"payload.bin")
        require(!payload.exists());Os.rename(pending.path,payload.path)
        RootUpdateFacts.command(listOf("/system/bin/restorecon",directory.path,payload.path),10)
        sync(directory);sync(File(BASE))
        return existing(run,bytes,sha256)
    }
    fun existing(run:UpdateRun,bytes:Long,sha256:String,measure:Boolean=true):File {
        val directory=directory(run);val d=Os.lstat(directory.path)
        require(directory.canonicalPath==directory.path && OsConstants.S_ISDIR(d.st_mode) && d.st_uid==0 && d.st_mode and 0x12==0)
        require(Os.getxattr(directory.path,"security.selinux").toString(Charsets.US_ASCII).trimEnd('\u0000')=="u:object_r:ota_package_file:s0")
        val payload=File(directory,"payload.bin");val s=Os.lstat(payload.path)
        require(payload.canonicalPath==payload.path && OsConstants.S_ISREG(s.st_mode) && s.st_uid==0 && s.st_mode and 0xdb==0 && s.st_size==bytes)
        val label=Os.getxattr(payload.path,"security.selinux").toString(Charsets.US_ASCII).trimEnd('\u0000')
        require(label=="u:object_r:ota_package_file:s0") {"OTA_PAYLOAD_LABEL_UNSUPPORTED"}
        if(measure)require(RootUpdateFacts.digest(payload,bytes)==sha256)
        return payload
    }
    fun discard(run:UpdateRun,bytes:Long,sha256:String) {
        val payload=existing(run,bytes,sha256)
        val directory=requireNotNull(payload.parentFile)
        require(directory.list()?.toSet()==setOf("payload.bin"))
        require(payload.delete());sync(directory)
        require(directory.delete());sync(File(BASE))
    }
    private fun sync(directory:File) {
        val fd=Os.open(directory.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try{require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}
    }
}
