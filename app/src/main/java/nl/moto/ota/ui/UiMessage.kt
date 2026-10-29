package nl.moto.ota.ui

import android.content.Context
import androidx.annotation.StringRes
import nl.moto.ota.R
import nl.moto.ota.core.update.UpdateRun
import nl.moto.ota.platform.i18n.MessageRenderer

/** Keep a message's meaning in state; resolve its language only when displaying it. */
sealed interface UiMessage {
    fun resolve(context: Context): String

    data class Resource(@StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiMessage {
        override fun resolve(context: Context): String = context.getString(id, *arguments.toTypedArray())
    }

    data class Workflow(val run: UpdateRun, val simulation: Boolean = false) : UiMessage {
        override fun resolve(context: Context): String = MessageRenderer.workflow(context, run).let {
            if (simulation) context.getString(R.string.ui_simulation_phase, it) else it
        }
    }

    data class Failure(val code: String?, val criticalRun: UpdateRun? = null) : UiMessage {
        override fun resolve(context: Context): String = buildString {
            append(MessageRenderer.failure(context, code))
            criticalRun?.let { append("\n\n"); append(MessageRenderer.workflow(context, it)) }
            append("\n"); append(context.getString(R.string.ui_no_automatic_retry))
        }
    }
}

fun uiMessage(@StringRes id: Int, vararg arguments: Any): UiMessage = UiMessage.Resource(id, arguments.toList())
