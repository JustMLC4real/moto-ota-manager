package nl.moto.ota.platform.monitoring

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.moto.ota.core.MonitorState
import java.util.concurrent.TimeUnit

/** User opt-in is committed before any work is queued. Disabling takes effect immediately. */
class MonitorController(context: Context) {
    private val app = context.applicationContext
    private val store = MonitorStore.get(app)
    private val notifications = MonitorNotifications(app)
    val state: StateFlow<MonitorState> = store.state

    suspend fun enable(intervalHours: Int = 6) = withContext(Dispatchers.IO) { operations.withLock {
        val configured = store.configure(true, intervalHours)
        try { schedule(configured) }
        catch (error: Exception) { store.configure(false, intervalHours); throw error }
    } }

    suspend fun disable() = withContext(Dispatchers.IO) { operations.withLock {
        // Each cleanup is attempted even if a full disk or a notification
        // service error prevents a different cleanup from finishing.
        val configuration = runCatching { store.configure(false, store.current().intervalHours) }
        val alerts = runCatching { notifications.cancel() }
        val work = runCatching { WorkManager.getInstance(app) }
        val periodic = runCatching { work.getOrThrow().cancelUniqueWork(PERIODIC_NAME).result.get(30, TimeUnit.SECONDS) }
        val immediate = runCatching { work.getOrThrow().cancelUniqueWork(IMMEDIATE_NAME).result.get(30, TimeUnit.SECONDS) }
        configuration.getOrThrow()
        periodic.getOrThrow()
        immediate.getOrThrow()
        alerts.getOrThrow()
        Unit
    } }

    suspend fun checkNow(): Boolean = withContext(Dispatchers.IO) { operations.withLock {
        if (!store.current().enabled) return@withLock false
        WorkManager.getInstance(app).enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MotorolaMonitorWorker>().addTag(WORK_TAG).build()).result.get(30, TimeUnit.SECONDS)
        true
    } }

    suspend fun refresh() = withContext(Dispatchers.IO) { store.reload(); Unit }

    /** Useful after an app update; Android still decides the precise execution time. */
    suspend fun restoreSchedule() = withContext(Dispatchers.IO) { operations.withLock {
        val config = store.reload()
        if (config.enabled) schedule(config)
    } }

    fun notificationsAllowed(): Boolean = notifications.allowed()

    private fun schedule(config: MonitorState) {
        val request = PeriodicWorkRequestBuilder<MotorolaMonitorWorker>(config.intervalHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            .result.get(30, TimeUnit.SECONDS)
    }

    companion object {
        // Serializes scheduling with opt-out even if multiple screens/controllers
        // issue commands concurrently. The store separately guards worker commits.
        private val operations = Mutex()
        const val PERIODIC_NAME = "moto_ota_metadata_monitor_periodic"
        const val IMMEDIATE_NAME = "moto_ota_metadata_monitor_now"
        const val WORK_TAG = "moto_ota_metadata_monitor"
    }
}
