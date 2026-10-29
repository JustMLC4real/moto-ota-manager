package nl.moto.ota

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import java.util.Locale

/** Restores prior presentation/consent preferences after every disposable-emulator UI test. */
internal class PresentationStateRule(private val tourCompleted: Boolean) : ExternalResource() {
    private lateinit var context: Context
    private lateinit var localeManager: LocaleManager
    private lateinit var previousLocales: LocaleList
    private val snapshots = linkedMapOf<String, Map<String, *>>()

    override fun before() {
        assumeTrue("UI tests require a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm"))
        context = InstrumentationRegistry.getInstrumentation().targetContext
        localeManager = requireNotNull(context.getSystemService(LocaleManager::class.java))
        previousLocales = localeManager.applicationLocales
        listOf("presentation", "user_choices", "cds-network-consent").forEach { name ->
            snapshots[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        check(context.getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().clear()
            .putInt("tour_version", if (tourCompleted) 1 else 0).putString("appearance", "SYSTEM").commit())
        check(context.getSharedPreferences("user_choices", Context.MODE_PRIVATE).edit()
            .putBoolean("root_reads_allowed", false).commit())
        // No test opts in to real network traffic, even if another UI test preceded it.
        nl.moto.ota.platform.discovery.CdsController(context).setConsent(false)
        localeManager.applicationLocales = LocaleList.forLanguageTags("en")
    }

    override fun after() {
        if (!::context.isInitialized) return
        nl.moto.ota.platform.discovery.CdsController(context).setConsent(
            snapshots["cds-network-consent"]?.get("enabled") == true)
        snapshots.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) -> editor.restoreValue(key, value) }
            check(editor.commit())
        }
        localeManager.applicationLocales = previousLocales
    }

    fun text(id: Int, language: String = "en"): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(language))) }
    ).getString(id)

    fun rootReadsAllowed(): Boolean = context.getSharedPreferences("user_choices", Context.MODE_PRIVATE)
        .getBoolean("root_reads_allowed", false)

    fun preference(key: String): String? = context.getSharedPreferences("presentation", Context.MODE_PRIVATE).getString(key, null)

    fun languageTags(): String = localeManager.applicationLocales.toLanguageTags()

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> remove(key)
            else -> error("Unsupported preference type")
        }
    }
}
