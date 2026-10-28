package nl.moto.ota.platform

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Plain values keep resolver policy testable without Android framework mocks. */
data class OfficialActivityCandidate(
    val packageName: String,
    val activityName: String,
    val priority: Int,
    val exported: Boolean,
    val activityEnabled: Boolean,
    val applicationEnabled: Boolean,
    val systemApplication: Boolean,
    val updatedSystemApplication: Boolean = false
)

object OfficialAppPolicy {
    const val MOTOROLA_UPDATER_PACKAGE = "com.motorola.ccc.ota"
    const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

    /** This app intentionally opens Motorola's updater; another handler requires manual navigation. */
    fun systemUpdater(candidates: List<OfficialActivityCandidate>): OfficialActivityCandidate? = candidates
        .filter { candidate ->
            candidate.packageName == MOTOROLA_UPDATER_PACKAGE && candidate.activityName.isNotBlank() &&
                candidate.priority > 0 && candidate.exported && candidate.activityEnabled && candidate.applicationEnabled &&
                (candidate.systemApplication || candidate.updatedSystemApplication)
        }
        .maxWithOrNull(compareBy<OfficialActivityCandidate> { it.priority }.thenBy { it.activityName })

    fun magiskActivityAllowed(candidate: OfficialActivityCandidate): Boolean =
        candidate.packageName == MAGISK_PACKAGE && candidate.activityName.isNotBlank() &&
            candidate.exported && candidate.activityEnabled && candidate.applicationEnabled
}

/** Constructs explicit intents only. The caller handles launching and manual fallback. */
object OfficialApps {
    // Public Settings constant was added in 36.1; the app compiles against SDK 35.
    const val SYSTEM_UPDATE_ACTION = "android.settings.SYSTEM_UPDATE_SETTINGS"

    /**
     * Android's Settings documentation requires MATCH_SYSTEM_ONLY and priority > 0:
     * https://developer.android.com/reference/android/provider/Settings#ACTION_SYSTEM_UPDATE_SETTINGS
     */
    fun systemUpdaterIntent(packageManager: PackageManager): Intent? = runCatching {
        val implicit = Intent(SYSTEM_UPDATE_ACTION)
        val candidates = packageManager.queryIntentActivities(implicit,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())).mapNotNull { match ->
            match.activityInfo?.let { activity -> descriptor(activity, match.priority) }
        }
        val selected = OfficialAppPolicy.systemUpdater(candidates) ?: return@runCatching null
        Intent(SYSTEM_UPDATE_ACTION).setComponent(ComponentName(selected.packageName, selected.activityName))
    }.getOrNull()

    /** Hidden/renamed or unavailable Magisk installations retain the caller's manual fallback. */
    fun magiskIntent(packageManager: PackageManager): Intent? = runCatching {
        val launch = packageManager.getLaunchIntentForPackage(OfficialAppPolicy.MAGISK_PACKAGE) ?: return@runCatching null
        val component = launch.component ?: return@runCatching null
        if (component.packageName != OfficialAppPolicy.MAGISK_PACKAGE) return@runCatching null
        val activity = packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        val candidate = descriptor(activity, 0) ?: return@runCatching null
        if (!OfficialAppPolicy.magiskActivityAllowed(candidate)) return@runCatching null
        Intent(launch).setComponent(component)
    }.getOrNull()

    private fun descriptor(activity: ActivityInfo, priority: Int): OfficialActivityCandidate? {
        val application = activity.applicationInfo ?: return null
        val packageName = activity.packageName ?: return null
        val activityName = activity.name ?: return null
        if (application.packageName != packageName) return null
        return OfficialActivityCandidate(packageName, activityName, priority, activity.exported,
            activity.enabled, application.enabled, application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            application.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
    }
}
