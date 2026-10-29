package nl.moto.ota.platform.monitoring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import nl.moto.ota.MainActivity
import nl.moto.ota.R
import nl.moto.ota.core.MonitorNotice
import nl.moto.ota.core.MonitorNoticeKind

/** Alerts are informational leads only. No action installs, downloads or requests root. */
class MonitorNotifications(private val context: Context) : MonitorNotificationSink {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun allowed(): Boolean {
        val service = manager ?: return false
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            service.areNotificationsEnabled() && service.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun publish(notice: MonitorNotice): Boolean {
        if (!notice.identity.matches(Regex("[a-f0-9]{64}"))) return false
        val service = manager ?: return false
        refreshChannel(context)
        if (!allowed()) return false
        val open = Intent(context, MainActivity::class.java).putExtra(OPEN_TAB_EXTRA, "firmware")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTICE_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(when(notice.kind) {
            MonitorNoticeKind.METADATA -> R.string.notification_metadata_title
            MonitorNoticeKind.PACKAGE_REFERENCE -> R.string.notification_reference_title
            MonitorNoticeKind.SERVER_OFFER -> R.string.notification_server_title
        })
        val body = context.getString(when(notice.kind) {
            MonitorNoticeKind.METADATA -> R.string.notification_metadata_body
            MonitorNoticeKind.PACKAGE_REFERENCE -> R.string.notification_reference_body
            MonitorNoticeKind.SERVER_OFFER -> R.string.notification_server_body
        }, notice.targetBuild)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_moto_monitor)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        return try {
            // The deterministic per-release tag also makes a retry after process
            // death update the same notification without another alert. A new
            // release has a different tag and can legitimately alert once.
            val tag = NOTICE_TAG_PREFIX + notice.identity
            service.activeNotifications.filter { it.id == NOTICE_ID && it.tag?.startsWith(NOTICE_TAG_PREFIX) == true && it.tag != tag }
                .forEach { service.cancel(it.tag, NOTICE_ID) }
            service.notify(tag, NOTICE_ID, notification)
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel() {
        val service = manager ?: return
        service.activeNotifications.filter { it.id == NOTICE_ID && it.tag?.startsWith(NOTICE_TAG_PREFIX) == true }
            .forEach { service.cancel(it.tag, NOTICE_ID) }
    }

    companion object {
        fun refreshChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_monitor_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notification_monitor_description)
                })
        }
        const val OPEN_TAB_EXTRA = "moto_open_tab"
        private const val CHANNEL_ID = "moto_metadata_monitor"
        private const val NOTICE_TAG_PREFIX = "moto_metadata_notice_"
        private const val NOTICE_ID = 0x4d4f5441
    }
}
