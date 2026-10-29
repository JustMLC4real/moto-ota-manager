package nl.moto.ota.platform.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import nl.moto.ota.core.*
import nl.moto.ota.platform.*
import nl.moto.ota.core.discovery.*
import nl.moto.ota.platform.discovery.CdsController

/** Checks CDS only with separate online-sharing consent; local metadata remains the fallback. */
class MotorolaMonitorWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val store = MonitorStore.get(applicationContext)
        val admitted = store.current()
        if (!admitted.enabled) return Result.success()
        val cds = CdsController(applicationContext)
        if (cds.consentGranted()) {
            val inspection = DeviceInspector().inspect(applicationContext, rootRequested = false)
            if (store.current().let { !it.enabled || it.generation != admitted.generation }) return Result.success()
            val response = cds.check(inspection, applicationContext.resources.configuration.locales[0].language)
            if (store.current().let { !it.enabled || it.generation != admitted.generation }) return Result.success()
            val decision = CdsMonitorPolicy.evaluate(inspection.toDeviceState(), response, admitted.lastNoticeIdentity, System.currentTimeMillis())
            if (response.status in setOf(CdsStatus.OFFER, CdsStatus.METADATA_ONLY, CdsStatus.NO_OFFER)) {
                store.recordIfCurrent(admitted.generation, decision, System.currentTimeMillis(), MonitorNotifications(applicationContext)::publish)
                return Result.success()
            }
            // Refusal/unavailability is not a no-update assertion. The existing
            // bounded local reader can still provide an informational fallback.
        }
        val runner = MonitorRunner(store, MonitorInspectionSource {
            fun stillEnabled() = store.current().let { it.enabled && it.generation == admitted.generation }
            if (!stillEnabled()) throw MonitorSupersededException()
            // Unsupported devices never receive an automatic root request. The
            // reviewed phone exposes its profile identity without root; protected
            // capability properties are read in the subsequent authorized scan.
            val initial = DeviceInspector().inspect(applicationContext, rootRequested = false)
            val initialDevice = initial.toDeviceState()
            if (!stillEnabled()) throw MonitorSupersededException()
            val inspected = if (initialDevice.exactProfileMatch && initialDevice.androidVersion == "17")
                DeviceInspector().inspect(applicationContext, rootRequested = true) else initial
            val metadata = inspected.otaMetadata
            val recognized = metadata.observed && metadata.diagnostics.all { it == MetadataDiagnostic.METADATA_RESOURCE }
            MonitorObservation(inspected.toDeviceState(), DiscoveryEvidence(
                localTargetBuild = metadata.targetBuild.orEmpty(), localDownloadUrl = metadata.downloadUrl,
                localContentKind = metadata.contentKind, localMetadataObserved = metadata.observed), recognized)
        }, MonitorNotifications(applicationContext))
        return try {
            runner.check()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retry only infrastructure failures, with WorkManager's bounded
            // backoff. Root-denied and missing-metadata decisions finish quietly.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
