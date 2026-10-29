package nl.moto.ota.platform.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Explicit, non-exported notification actions never carry a download URL. */
class DownloadActionReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(ACTION_PAUSE,ACTION_CANCEL))return
        val id=intent.getStringExtra(EXTRA_JOB_ID)?:return
        val controller=DownloadController(context)
        if(controller.current()?.id!=id)return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { if(intent.action==ACTION_PAUSE)controller.pauseExpected(id)else controller.cancelExpected(id) }
            catch(_:Exception){ /* State stays resumable; no credential-bearing exception is logged. */ }
            finally{pending.finish()}
        }
    }
    companion object {
        const val ACTION_PAUSE="nl.moto.ota.DOWNLOAD_PAUSE"
        const val ACTION_CANCEL="nl.moto.ota.DOWNLOAD_CANCEL"
        const val EXTRA_JOB_ID="jobId"
    }
}
