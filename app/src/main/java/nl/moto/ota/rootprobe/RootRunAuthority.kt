package nl.moto.ota.rootprobe

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.DeviceState
import nl.moto.ota.core.update.*
import java.io.File

/** Fixed app-private authority, verified again in the dedicated UID0 process. */
internal object RootRunAuthority {
    private const val ROOT="/data/user/0/nl.moto.ota/files/update"
    data class Verified(val authorization:RunAuthorization,val operation:AuthorizedRunOperation,val key:ByteArray) {
        fun recheck()=RunAuthorizationPolicy.verifyOperation(authorization,operation,operation.action,key,System.currentTimeMillis(),SystemClock.elapsedRealtime())
    }
    fun verify(owner:Int,runId:String,operationFile:File,action:RunAction,device:DeviceState):Verified {
        val root=File(ROOT).canonicalFile
        require(root.path in setOf(ROOT,"/data/data/nl.moto.ota/files/update"))
        val key=read(File(root,"authority.key"),owner,32).also {require(it.size==32)}
        val auth=RunAuthorizationCodec.decode(read(File(root,"automation/authorization.properties"),owner,RunAuthorizationCodec.MAX_AUTHORIZATION_BYTES).toString(Charsets.UTF_8))
        val permit=RunAuthorizationCodec.decodeOperation(read(operationFile,owner,RunAuthorizationCodec.MAX_OPERATION_BYTES).toString(Charsets.UTF_8))
        if(auth.runId!=runId)throw UpdateSafetyException("RUN_AUTHORIZATION_CHANGED")
        val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        RunAuthorizationPolicy.verifySource(auth,device,key,now,elapsed)
        RunAuthorizationPolicy.verifyOperation(auth,permit,action,key,now,elapsed)
        val run=RootUpdateFacts.journal(owner)
        val stage=when(action){RunAction.RESTORE_STOCK->UpdateStage.STOCK_WRITE_RUNNING;RunAction.APPLY_OTA->UpdateStage.OTA_APPLYING;
            RunAction.PATCH_INACTIVE_MAGISK->UpdateStage.MAGISK_REQUIRED;RunAction.REBOOT->UpdateStage.AWAITING_NEW_BOOT}
        require(run.id==auth.runId && run.sourceModel==auth.model && run.sourceDevice==auth.device && run.sourceChannel==auth.channel &&
            run.sourceBootId==auth.sourceBootId && run.sourceBuild==auth.sourceBuild && run.sourceFingerprint==auth.sourceFingerprint &&
            run.sourceSlot==auth.sourceSlot && run.targetSlot==auth.targetSlot && run.targetBuild==auth.targetBuild &&
            run.declaredTargetFingerprint==auth.targetFingerprint && run.packageSha256==auth.packageSha256 && run.stage==stage)
        return Verified(auth,permit,key)
    }
    /** Root's consumption is separate from the app's durable dispatch claim. Never replay. */
    fun consume(verified:Verified,owner:Int) {
        verified.recheck()
        val root=File(ROOT).canonicalFile
        var directory=root
        for(component in listOf("automation","root-consumed",verified.authorization.runId)){
            directory=File(directory,component)
            if(!directory.exists()){require(directory.mkdir());Os.chmod(directory.path,0x1c0);Os.chown(directory.path,owner,owner);sync(requireNotNull(directory.parentFile))}
            val s=Os.lstat(directory.path)
            require(directory.canonicalPath==directory.path && OsConstants.S_ISDIR(s.st_mode) && s.st_uid==owner && s.st_mode and 0x3f==0)
        }
        val marker=File(directory,verified.operation.action.name+".claim")
        val fd=try{Os.open(marker.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)}
        catch(_:Exception){throw UpdateSafetyException("RUN_ACTION_ALREADY_CONSUMED")}
        try{
            val bytes=verified.operation.nonce.toByteArray(Charsets.US_ASCII)
            var at=0;while(at<bytes.size){val n=Os.write(fd,bytes,at,bytes.size-at);require(n>0);at+=n}
            Os.fchown(fd,owner,owner);Os.fsync(fd)
        }finally{Os.close(fd)}
        sync(directory)
    }
    private fun read(file:File,owner:Int,limit:Int):ByteArray {
        require(file.canonicalPath==file.path)
        val before=Os.lstat(file.path)
        require(OsConstants.S_ISREG(before.st_mode) && before.st_uid==owner && before.st_mode and 0x3f==0 && before.st_size in 1..limit.toLong())
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        try{
            val s=Os.fstat(fd);require(s.st_dev==before.st_dev && s.st_ino==before.st_ino && s.st_size==before.st_size)
            val bytes=ByteArray(limit+1);var count=0
            while(count<bytes.size){val n=Os.read(fd,bytes,count,bytes.size-count);if(n==0)break;require(n>0);count+=n}
            val after=Os.fstat(fd);val named=Os.lstat(file.path)
            require(count.toLong()==before.st_size && count<=limit && after.st_size==s.st_size && after.st_mtime==s.st_mtime && after.st_ctime==s.st_ctime && named.st_dev==s.st_dev && named.st_ino==s.st_ino)
            return bytes.copyOf(count)
        }finally{Os.close(fd)}
    }
    private fun sync(directory:File){val fd=Os.open(directory.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0);try{require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}}
}
