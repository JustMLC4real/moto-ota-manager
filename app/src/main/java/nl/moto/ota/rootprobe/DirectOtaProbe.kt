package nl.moto.ota.rootprobe

import android.os.*
import nl.moto.ota.core.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.update.DirectOtaController
import nl.moto.ota.platform.update.RestoreRootProtocol
import nl.moto.ota.platform.update.AutomationDeviceCodec
import nl.moto.ota.platform.update.DirectOtaPreparedProtocol
import nl.moto.ota.platform.DeviceTrustAnchors
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Android's boot-framework UpdateEngine, never guessed Binder transactions or OEM scripts. */
object DirectOtaProbe {
    @JvmStatic fun main(args:Array<String>) {
        val deadline=if(args.firstOrNull()=="apply")6*60*60*1000L else if(args.firstOrNull() in setOf("prepare","discard"))300_000L else 25_000L
        val watchdog=thread(isDaemon=true){try{Thread.sleep(deadline)}catch(_:InterruptedException){return@thread};Runtime.getRuntime().halt(71)}
        try {
            require(args.size==2 && args[0] in setOf("prepare","apply","observe","discard") && java.util.UUID.fromString(args[1]).toString()==args[1])
            val owner=RootUpdateFacts.owner();val run=RootUpdateFacts.journal(owner);require(run.id==args[1])
            if(args[0]=="observe") {
                val pending=run.otaAppliedObserved || run.stage in setOf(UpdateStage.MAGISK_REQUIRED,UpdateStage.REBOOT_BY_SYSTEM_UPDATER,UpdateStage.AWAITING_NEW_BOOT)
                println(JSONObject().put("schema",1).put("device",AutomationDeviceCodec.encode(RootUpdateFacts.observe(run,pending))))
                System.out.flush();kotlin.system.exitProcess(0)
            }
            val directory=File(RootUpdateFacts.FILES,"update/ota/${run.id}")
            val secret=RootUpdateFacts.privateFile(File(RootUpdateFacts.FILES,"update/authority.key"),owner,32).readBytes().also {require(it.size==32)}
            val anchors=DeviceTrustAnchors.spkiSha256().also {require(it.isNotEmpty())}.sorted().joinToString(",")
            val cached=if(args[0]=="prepare")null else DirectOtaPreparedProtocol.verify(JSONObject(
                RootUpdateFacts.privateFile(File(directory,"prepared.json"),owner,16_384).readText()),secret,run).also {require(it.getString("anchors")==anchors)}
            val plan=cached?:JSONObject(RootUpdateFacts.privateFile(File(directory,"plan.json"),owner,4096).readText()).also {
                require(it.getInt("schema")==1 && it.getString("run")==run.id && it.getString("packageSha256")==run.packageSha256)
            }
            val source=if(cached==null){
                val packageName=plan.getString("packageName");require(packageName.matches(Regex("[a-f0-9-]{36}[.]zip")))
                RootUpdateFacts.privateFile(File(RootUpdateFacts.FILES,"verified-packages/$packageName"),owner,FirmwareValidator.MAX_PACKAGE_BYTES)
            }else null
            val size=plan.getLong("payloadBytes")
            if(source!=null)require(RootUpdateFacts.digest(source,plan.getLong("packageBytes"))==run.packageSha256)
            val props=if(cached!=null)listOf("FILE_HASH","FILE_SIZE","METADATA_HASH","METADATA_SIZE").associateWith {cached.getString(it)}
                else ZipFile(requireNotNull(source)).use {DirectOtaController.readProperties(it)}
            require(props["FILE_SIZE"]?.toLongOrNull()==size)
            val hash=Base64.getDecoder().decode(requireNotNull(props["FILE_HASH"])).joinToString(""){"%02x".format(it.toInt() and 255)}
            if(args[0]=="discard") {
                require(run.stage==UpdateStage.STOCK_PREPARATION && run.stockRestoreEvidence==StepEvidence.NONE && run.restoreTransactionId.isBlank())
                require(!File(RootUpdateFacts.FILES,"update/automation/root-consumed/${run.id}/APPLY_OTA.claim").exists())
                RootUpdateFacts.observe(run,false)
                RootOtaPayloadStore.discard(run,size,hash)
                println(JSONObject().put("schema",1).put("discarded",true));System.out.flush();kotlin.system.exitProcess(0)
            }
            val payload=if(args[0]=="prepare")RootUpdateFacts.privateFile(File(directory,"payload.bin"),owner,FirmwareValidator.MAX_PACKAGE_BYTES)
                else RootOtaPayloadStore.existing(run,size,hash,measure=false)
            fun samePublishedFile(s:android.system.StructStat)=cached==null || (s.st_dev==cached.getLong("dev") && s.st_ino==cached.getLong("inode") &&
                s.st_size==size && s.st_mtime==cached.getLong("mtime") && s.st_ctime==cached.getLong("ctime"))
            require(samePublishedFile(Os.lstat(payload.path)))
            if(cached==null)require(RootUpdateFacts.digest(payload,size)==hash)
            val device=RootUpdateFacts.observe(run,false)
            val manifest=source?.let(PayloadManifest::inspect)
            val target=if(cached!=null)PartitionDigest(run.stockPartitionType,cached.getLong("targetBytes"),cached.getString("targetSha256"))
                else manifest?.targetImages?.singleOrNull {it.name==run.stockPartitionType}?:error("TARGET_BOOT_DIGEST_MISSING")
            require(target.bytes==RootUpdateFacts.blockBytes(RootUpdateFacts.block(run.stockPartitionType+run.targetSlot)))
            val old=if(cached!=null)cached.getLong("oldBytes").takeIf {it>0}?.let {PartitionDigest(run.stockPartitionType,it,cached.getString("oldSha256"))}
                else manifest?.sourceImages?.singleOrNull {it.name==run.stockPartitionType}
            require(old==null || (old.sha256==run.stockSha256 && old.bytes==RootUpdateFacts.blockBytes(RootUpdateFacts.block(run.stockPartitionType+run.sourceSlot))))
            if(args[0]=="prepare") {
                require(run.stage==UpdateStage.STOCK_PREPARATION)
                val zip=requireNotNull(source)
                RecoverySystem.verifyPackage(zip,null,null)
                require(DeviceTrustAnchors.spkiSha256().sorted().joinToString(",")==anchors && RootUpdateFacts.digest(zip,plan.getLong("packageBytes"))==run.packageSha256)
                val staged=RootOtaPayloadStore.prepare(run,payload,size,hash,owner)
                try {
                    val s=Os.lstat(staged.path)
                    val body=JSONObject().put("schema",1).put("runId",run.id).put("packageSha256",run.packageSha256).put("sourceBootId",run.sourceBootId)
                        .put("targetBuild",run.targetBuild).put("targetFingerprint",run.declaredTargetFingerprint).put("targetSlot",run.targetSlot)
                        .put("partitionType",run.stockPartitionType).put("stockSha256",run.stockSha256).put("oldBytes",old?.bytes?:0)
                        .put("oldSha256",old?.sha256.orEmpty()).put("targetBytes",target.bytes).put("targetSha256",target.sha256)
                        .put("payloadBytes",size).put("payloadSha256",hash).put("dev",s.st_dev).put("inode",s.st_ino).put("mtime",s.st_mtime).put("ctime",s.st_ctime)
                        .put("anchors",anchors).put("createdAtMillis",System.currentTimeMillis())
                    props.forEach {(k,v)->body.put(k,v)}
                    val file=File(directory,"prepared.json")
                    RestoreRootProtocol.writePrivate(file,DirectOtaPreparedProtocol.sign(body,secret).toString().toByteArray())
                    Os.chown(file.path,owner,owner)
                }catch(error:Throwable){runCatching {RootOtaPayloadStore.discard(run,size,hash)};throw error}
                println(JSONObject().put("schema",1).put("prepared",true).put("run",run.id).put("device",AutomationDeviceCodec.encode(RootUpdateFacts.observe(run,false))))
            }else {
                RandomAccessFile(File(RootUpdateFacts.FILES,"update/automation-actions.lock"),"rw").channel.use {channel->
                    val lock=channel.tryLock()?:throw UpdateSafetyException("AUTOMATION_ALREADY_RUNNING")
                    lock.use {
                        require(run.stage==UpdateStage.OTA_APPLYING && run.stockRestoreEvidence==StepEvidence.CRYPTOGRAPHIC_IMAGE)
                        val authority=RootRunAuthority.verify(owner,run.id,File(directory,"operation.properties"),RunAction.APPLY_OTA,device)
                        require(authority.authorization.packageSha256==run.packageSha256)
                        val active=RootUpdateFacts.block(run.stockPartitionType+run.sourceSlot)
                        require(RootUpdateFacts.digest(active,RootUpdateFacts.blockBytes(active),true)==run.stockSha256)
                        RootUpdateFacts.observe(run,false)
                        val engine=RootUpdateFacts.engine()
                        val callback=ApplicationCallback()
                        var bound=false
                        try {
                            bound=engine.bind(callback);require(bound && callback.first.await(3,TimeUnit.SECONDS) && callback.idle())
                            val descriptor=android.system.Os.open(payload.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC,0)
                            try {
                                // This root-owned, immutable, correctly labelled OTA path also
                                // avoids depending on permission to use a Magisk-owned Binder FD.
                                // The inode is root-owned/read-only and its complete hash was
                                // authenticated at publication. No app can replace these bytes.
                                // Android also verifies the actual payload hash/signature itself.
                                require(samePublishedFile(Os.fstat(descriptor)) && samePublishedFile(Os.lstat(payload.path)))
                                val fresh=RootUpdateFacts.observe(run,false)
                                RootRunAuthority.verify(owner,run.id,File(directory,"operation.properties"),RunAction.APPLY_OTA,fresh)
                                require(callback.idle())
                                RootRunAuthority.consume(authority,owner)
                                val current=RootUpdateFacts.journal(owner)
                                require(current.id==run.id && current.stage==UpdateStage.OTA_APPLYING && current.packageSha256==run.packageSha256)
                                authority.recheck()
                                // Deliberately no cancel/reset, verification bypass, dynamic partition
                                // operation, bootctl, slot repair, automatic retry or service replacement.
                                val headers=props.map {"${it.key}=${it.value}"}+listOf("SWITCH_SLOT_ON_REBOOT=1","RUN_POST_INSTALL=1")
                                callback.dispatched=true
                                engine.applyPayload("file://${payload.path}",0,size,headers.toTypedArray())
                            }finally{android.system.Os.close(descriptor)}
                            require(callback.finished.await(5*60*60,TimeUnit.SECONDS)) {"OTA_CALLBACK_DEADLINE"}
                            require(callback.success()) {"OTA_APPLICATION_FAILED"}
                        }finally{if(bound)require(engine.unbind(callback))}
                        require(RootUpdateFacts.engineStatus()==6)
                        RootUpdateFacts.requireNextSlot(run)
                        runCatching {RootOtaPayloadStore.discard(run,size,hash)}
                        val receipt=JSONObject().put("schema",1).put("applied",true).put("completion",0).put("run",run.id)
                            .put("device",AutomationDeviceCodec.encode(RootUpdateFacts.observe(run,true)))
                        RestoreRootProtocol.writePrivate(File(directory,"receipt.json"),receipt.toString().toByteArray())
                        android.system.Os.chown(File(directory,"receipt.json").path,owner,owner)
                        println(receipt)
                        // The complete callback, fresh engine status and boot metadata
                        // already prove all payload reads finished. Remove only our input.
                    }
                }
            }
        }catch(error:Throwable){
            val code=(error as? UpdateSafetyException)?.code?:"DIRECT_OTA_REJECTED_OR_INCOMPLETE"
            println(JSONObject().put("error",code))
        }finally{watchdog.interrupt();System.out.flush()}
        kotlin.system.exitProcess(0)
    }
    private class ApplicationCallback:IUpdateEngineCallback.Stub() {
        val first=CountDownLatch(1);val finished=CountDownLatch(1)
        @Volatile var dispatched=false
        private var status=-1;private var completion:Int?=null;private var invalid=false
        @Synchronized override fun onStatusUpdate(code:Int,progress:Float) {
            if(!progress.isFinite() || progress !in 0f..1f || code !in 0..11)invalid=true
            status=code;first.countDown()
            if(dispatched && ((code==6 && completion!=null) || invalid || code in setOf(7,8,9,10)))finished.countDown()
        }
        @Synchronized override fun onPayloadApplicationComplete(code:Int){completion=code;if(code!=0)invalid=true;first.countDown();if(dispatched && (invalid || status==6))finished.countDown()}
        @Synchronized fun idle()=!invalid && status==0 && completion==null
        @Synchronized fun success()=!invalid && completion==0 && status==6
    }
}
