package nl.moto.ota.platform.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.moto.ota.core.update.*
import java.io.File

class ControlledRebootController(private val context:Context) {
    suspend fun reboot(run:UpdateRun,operation:AuthorizedRunOperation):Unit=withContext(Dispatchers.IO) {
        require(!run.simulated && run.stage==UpdateStage.REBOOT_BY_SYSTEM_UPDATER && run.magiskEvidence==StepEvidence.CRYPTOGRAPHIC_IMAGE &&
            operation.runId==run.id && operation.action==RunAction.REBOOT && operation.packageSha256==run.packageSha256)
        val directory=File(context.filesDir,"update/reboot/${run.id}").apply {mkdirs()}
        require(directory.canonicalPath==directory.path)
        android.system.Os.chmod(directory.path,0x1c0)
        RestoreRootProtocol.writePrivate(File(directory,"operation.properties"),RunAuthorizationCodec.encodeOperation(operation).toByteArray())
        val waiting=UpdateController(context).acknowledgeReboot(true)
        require(waiting.stage==UpdateStage.AWAITING_NEW_BOOT && waiting.rebootConsentRecorded)
        PostUpdateObserverWorker.schedule(context,run.id)
        val result=AutomationRootTransport(context.applicationInfo.sourceDir).invoke("ControlledRebootProbe","reboot",run.id,30)
        require(result.optBoolean("dispatched") && result.optString("run")==run.id) {"REBOOT_DISPATCH_UNCONFIRMED"}
    }
}
