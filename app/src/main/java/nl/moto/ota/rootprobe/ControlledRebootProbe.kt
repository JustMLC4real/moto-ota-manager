package nl.moto.ota.rootprobe

import nl.moto.ota.core.update.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/** The sole live reboot boundary. Never called by diagnostics, startup or recovery. */
object ControlledRebootProbe {
    @JvmStatic fun main(args:Array<String>) {
        val watchdog=thread(isDaemon=true){try{Thread.sleep(25_000)}catch(_:InterruptedException){return@thread};Runtime.getRuntime().halt(72)}
        try {
            require(args.size==2 && args[0]=="reboot" && java.util.UUID.fromString(args[1]).toString()==args[1])
            val owner=RootUpdateFacts.owner();val run=RootUpdateFacts.journal(owner)
            require(run.id==args[1] && run.stage==UpdateStage.AWAITING_NEW_BOOT && run.rebootConsentRecorded &&
                run.magiskEvidence==StepEvidence.CRYPTOGRAPHIC_IMAGE && run.otaAppliedObserved)
            RandomAccessFile(File(RootUpdateFacts.FILES,"update/automation-actions.lock"),"rw").channel.use {channel->
                val lock=channel.tryLock()?:error("AUTOMATION_ALREADY_RUNNING")
                lock.use {
                    val receipt=MagiskInactiveProbe.verifiedReceipt(run,owner)
                    require(receipt.getString("targetStockSha1")==run.authenticatedTargetStockSha1)
                    val ota=JSONObject(RootUpdateFacts.privateFile(File(RootUpdateFacts.FILES,"update/ota/${run.id}/receipt.json"),owner,16_384).readText())
                    require(ota.getBoolean("applied") && ota.getString("run")==run.id && ota.getInt("completion")==0)
                    val source=RootUpdateFacts.block(run.stockPartitionType+run.sourceSlot)
                    require(RootUpdateFacts.digest(source,RootUpdateFacts.blockBytes(source),true)==run.stockSha256)
                    RootUpdateFacts.requireNextSlot(run)
                    val device=RootUpdateFacts.observe(run,true)
                    val authority=RootRunAuthority.verify(owner,run.id,File(RootUpdateFacts.FILES,"update/reboot/${run.id}/operation.properties"),RunAction.REBOOT,device)
                    RootRunAuthority.consume(authority,owner)
                    require(RootUpdateFacts.journal(owner).stage==UpdateStage.AWAITING_NEW_BOOT)
                    authority.recheck()
                    // Fixed Android power command, after every source/target/engine guard.
                    // No bootloader/recovery mode, partition command, slot change or retry.
                    RootUpdateFacts.command(listOf("/system/bin/svc","power","reboot"),5)
                    println(JSONObject().put("schema",1).put("dispatched",true).put("run",run.id))
                }
            }
        }catch(error:Throwable){println(JSONObject().put("error",(error as? UpdateSafetyException)?.code?:"REBOOT_GUARD_REJECTED"))}
        finally{watchdog.interrupt();System.out.flush()}
        kotlin.system.exitProcess(0)
    }
}
