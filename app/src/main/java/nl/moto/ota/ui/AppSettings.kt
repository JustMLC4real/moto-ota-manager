package nl.moto.ota.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppAppearance { SYSTEM, LIGHT, DARK }

/** Presentation preferences never grant root, change update settings or authorize writes. */
class AppSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("presentation", Context.MODE_PRIVATE)
    var appearance by mutableStateOf(runCatching {
        AppAppearance.valueOf(preferences.getString("appearance", AppAppearance.SYSTEM.name)!!)
    }.getOrDefault(AppAppearance.SYSTEM)); private set
    var tourCompleted by mutableStateOf(preferences.getInt("tour_version", 0) >= 1); private set

    fun selectAppearance(value: AppAppearance) {
        appearance = value
        preferences.edit().putString("appearance", value.name).apply()
    }

    fun languageTag(): String = context.getSystemService(LocaleManager::class.java)
        ?.applicationLocales?.toLanguageTags().orEmpty()

    fun selectLanguage(tag: String) {
        require(tag in setOf("", "en", "nl"))
        val manager = requireNotNull(context.getSystemService(LocaleManager::class.java))
        if (manager.applicationLocales.toLanguageTags() != tag) {
            manager.applicationLocales = LocaleList.forLanguageTags(tag)
        }
    }

    fun completeTour() {
        tourCompleted = true
        preferences.edit().putInt("tour_version", 1).apply()
    }
}
