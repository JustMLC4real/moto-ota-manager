package nl.moto.ota.platform.monitoring

import kotlinx.coroutines.CancellationException
import nl.moto.ota.core.*

/** A source may only inspect; no mutation or download API belongs in this contract. */
fun interface MonitorInspectionSource { suspend fun inspect(): MonitorObservation }
fun interface MonitorNotificationSink { fun publish(notice: MonitorNotice): Boolean }

interface MonitorRepository {
    fun current(): MonitorState
    /** The repository rechecks opt-in/generation before publishing and committing. */
    fun recordIfCurrent(generation: Long, decision: MonitorDecision, checkedAtMillis: Long,
                        publish: (MonitorNotice) -> Boolean): MonitorState?
}

enum class MonitorRunResult { DISABLED, SUPERSEDED, COMPLETED }

/** A configuration change is a quiet successful stop, distinct from system cancellation. */
class MonitorSupersededException : RuntimeException()

/** Injectable orchestration. Production uses DeviceInspector; tests never need a real root shell. */
class MonitorRunner(
    private val repository: MonitorRepository,
    private val source: MonitorInspectionSource,
    private val notifications: MonitorNotificationSink,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun check(): MonitorRunResult {
        val before = repository.current()
        if (!before.enabled) return MonitorRunResult.DISABLED
        val decision = try {
            val observation = source.inspect()
            MonitorPolicy.evaluate(observation, before.lastNoticeIdentity, clock())
        } catch (_: MonitorSupersededException) {
            return MonitorRunResult.SUPERSEDED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Neither raw exception messages nor source objects reach persistence.
            MonitorDecision(MonitorOutcome.CHECK_FAILED)
        }
        val result = repository.recordIfCurrent(before.generation, decision, clock(), notifications::publish)
        return if (result == null) MonitorRunResult.SUPERSEDED else MonitorRunResult.COMPLETED
    }
}
