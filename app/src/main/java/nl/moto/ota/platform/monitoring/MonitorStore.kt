package nl.moto.ota.platform.monitoring

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.moto.ota.core.*
import java.io.File

/** Atomic, app-private configuration/history. The monitor never stores an OTA URL. */
class MonitorStore private constructor(context: Context) : MonitorRepository {
    private val file = AtomicFile(File(context.filesDir, "metadata-monitor.properties"))
    private val lock = Any()
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<MonitorState> = mutable.asStateFlow()

    override fun current(): MonitorState = synchronized(lock) { mutable.value }

    fun reload(): MonitorState = synchronized(lock) { load().also { mutable.value = it } }

    fun configure(enabled: Boolean, intervalHours: Int): MonitorState = synchronized(lock) {
        require(intervalHours in 1..24)
        val before = mutable.value
        require(before.generation < Long.MAX_VALUE)
        val result = before.copy(enabled = enabled, intervalHours = intervalHours, generation = before.generation + 1,
            lastOutcome = if (enabled) before.lastOutcome.takeUnless { it in setOf(MonitorOutcome.DISABLED, MonitorOutcome.STORE_CORRUPT) } ?: MonitorOutcome.NEVER else MonitorOutcome.DISABLED)
        // Opt-out also closes the in-memory gate if storage is full/broken.
        // If the atomic replacement fails, remove the old opt-in configuration
        // as a best-effort fail-closed fallback for the next process start.
        if (!enabled) mutable.value = result
        try { write(result) }
        catch (error: Exception) {
            if (!enabled) runCatching { file.delete() }
            throw error
        }
        result
    }

    override fun recordIfCurrent(generation: Long, decision: MonitorDecision, checkedAtMillis: Long,
                                 publish: (MonitorNotice) -> Boolean): MonitorState? = synchronized(lock) {
        val before = mutable.value
        // Configuration changes serialize with this very short publish operation.
        // A disabled/superseded worker cannot post a late notification.
        val next = MonitorTransition.recordIfCurrent(before, generation, decision, checkedAtMillis, publish) ?: return null
        write(next)
        next
    }

    private fun load(): MonitorState = try {
        file.openRead().use { input ->
            val bytes = input.readNBytes(65_537)
            if (bytes.size > 65_536) MonitorState(lastOutcome = MonitorOutcome.STORE_CORRUPT)
            else MonitorState.decode(bytes.toString(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) MonitorState(lastOutcome = MonitorOutcome.STORE_CORRUPT) else MonitorState()
    }

    private fun write(value: MonitorState) {
        val stream = file.startWrite()
        try {
            stream.write(value.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            mutable.value = value
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object {
        @Volatile private var instance: MonitorStore? = null
        fun get(context: Context): MonitorStore = instance ?: synchronized(this) {
            instance ?: MonitorStore(context.applicationContext).also { instance = it }
        }
    }
}
