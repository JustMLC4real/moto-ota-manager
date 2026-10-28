package nl.moto.ota.platform

import android.content.Context
import android.util.AtomicFile
import nl.moto.ota.core.WorkflowState
import java.io.File

class WorkflowStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "workflow.properties"))
    fun load(): WorkflowState = WorkflowState.decode(runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull())
    fun save(state: WorkflowState) {
        require(!state.simulated) { "Simulation cannot overwrite the real journal" }
        val output = file.startWrite()
        try { output.write(state.encode().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
