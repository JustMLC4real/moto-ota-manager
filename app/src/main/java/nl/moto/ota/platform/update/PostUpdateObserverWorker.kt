package nl.moto.ota.platform.update

import android.content.Context
import androidx.work.*
import nl.moto.ota.core.update.*
import nl.moto.ota.platform.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Post-boot work reads only. It can never obtain the previous in-memory action authority. */
class PostUpdateObserverWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val id=inputData.getString("run")?:return Result.success()
        val updates=UpdateController(applicationContext)
        val before=updates.load()
        if(before.id!=id || before.simulated || before.stage in TERMINAL)return Result.success()
        if(!applicationContext.getSharedPreferences("user_choices",0).getBoolean("root_reads_allowed",false))return Result.success()
        return try {
            val observed=DeviceInspector().inspect(applicationContext,true)
            val device=observed.toDeviceState()
            val backup=observed.backupInventory.firstOrNull {it.provenanceVerified && it.sha1==observed.backupAssociation?.runtimeSha1}?.validation
            val after=updates.reconcile(device,backup,UpdateObservations.activeImageSha256(observed,before.stockPartitionType))
            if(after.stage in TERMINAL || runAttemptCount>=95)Result.success()else Result.retry()
        }catch(_:Exception){if(runAttemptCount>=95)Result.success()else Result.retry()}
    }
    companion object {
        private val TERMINAL=setOf(UpdateStage.IDLE,UpdateStage.PRECHECK_BLOCKED,UpdateStage.COMPLETE,UpdateStage.COMPLETE_REVIEW,UpdateStage.FAILED,UpdateStage.ROLLBACK,UpdateStage.RECOVERY_REQUIRED)
        fun schedule(context:Context,id:String) {
            require(UUID.fromString(id).toString()==id)
            val request=OneTimeWorkRequestBuilder<PostUpdateObserverWorker>().setInputData(workDataOf("run" to id))
                .setInitialDelay(1,TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.LINEAR,1,TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniqueWork("post-update-$id",ExistingWorkPolicy.KEEP,request)
        }
    }
}
