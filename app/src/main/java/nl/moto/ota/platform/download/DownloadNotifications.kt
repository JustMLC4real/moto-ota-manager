package nl.moto.ota.platform.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import nl.moto.ota.MainActivity
import nl.moto.ota.R
import nl.moto.ota.core.DownloadJob

internal object DownloadNotifications {
    const val CHANNEL="moto_ota_downloads"
    const val ID=4302
    fun info(context:Context,job:DownloadJob):ForegroundInfo {
        val manager=context.getSystemService(NotificationManager::class.java)
        refreshChannel(context)
        val open=PendingIntent.getActivity(context,ID,Intent(context,MainActivity::class.java).putExtra("moto_open_tab","firmware"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action:String,code:Int)=PendingIntent.getBroadcast(context,code,Intent(context,DownloadActionReceiver::class.java)
            .setAction(action).putExtra(DownloadActionReceiver.EXTRA_JOB_ID,job.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent=if(job.expectedBytes>0)(job.downloadedBytes*1000/job.expectedBytes).toInt().coerceIn(0,1000)else 0
        val text=if(job.expectedBytes>0) context.getString(R.string.notification_download_progress,(job.downloadedBytes/1_048_576).toString(),(job.expectedBytes/1_048_576).toString()) else context.getString(R.string.notification_download_connecting)
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_download_title,job.targetBuild)).setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(1000,percent,job.expectedBytes<=0)
            .addAction(Notification.Action.Builder(null,context.getString(R.string.notification_action_pause),action(DownloadActionReceiver.ACTION_PAUSE,4303)).build())
            .addAction(Notification.Action.Builder(null,context.getString(R.string.notification_action_cancel),action(DownloadActionReceiver.ACTION_CANCEL,4304)).build()).build()
        return ForegroundInfo(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    fun update(context:Context,job:DownloadJob){
        try { context.getSystemService(NotificationManager::class.java).notify(ID,info(context,job).notification) }
        catch(_:SecurityException){ /* User may deny notification permission; never expose a URL elsewhere. */ }
    }
    fun refreshChannel(context:Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL,context.getString(R.string.notification_download_channel),NotificationManager.IMPORTANCE_LOW).apply {
                description=context.getString(R.string.notification_download_description)
            })
    }
}
