package nl.moto.ota.platform

import org.junit.Assert.*
import org.junit.Test

class OfficialAppsTest {
    private val motorola = OfficialActivityCandidate(
        packageName = "com.motorola.ccc.ota", activityName = "com.motorola.ccc.ota.MainActivity", priority = 1000,
        exported = true, activityEnabled = true, applicationEnabled = true, systemApplication = true
    )

    @Test fun observedMotorolaPriorityWinsOverGoogleHandlerAndUntrustedHighPriority() {
        val google = motorola.copy(packageName = "com.google.android.gms", activityName = "com.google.android.gms.UpdateActivity", priority = 1)
        val interception = motorola.copy(packageName = "example.untrusted", priority = Int.MAX_VALUE, systemApplication = false)
        assertEquals(motorola, OfficialAppPolicy.systemUpdater(listOf(google, interception, motorola)))
    }

    @Test fun highestPositiveEligibleMotorolaPriorityIsSelectedRegardlessOfQueryOrder() {
        val lower = motorola.copy(activityName = "com.motorola.ccc.ota.OtherActivity", priority = 1)
        assertEquals(motorola, OfficialAppPolicy.systemUpdater(listOf(lower, motorola)))
        assertEquals(motorola, OfficialAppPolicy.systemUpdater(listOf(motorola, lower)))
    }

    @Test fun nonSystemAppCannotBecomeOfficialEvenWithMotorolaPackageAndHigherPriority() {
        val impostor = motorola.copy(priority = Int.MAX_VALUE, systemApplication = false, updatedSystemApplication = false)
        assertNull(OfficialAppPolicy.systemUpdater(listOf(impostor)))
        assertEquals(motorola, OfficialAppPolicy.systemUpdater(listOf(impostor, motorola)))
    }

    @Test fun disabledNonexportedAndDefaultPriorityActivitiesAreNeverSelected() {
        listOf(motorola.copy(exported = false), motorola.copy(activityEnabled = false),
            motorola.copy(applicationEnabled = false), motorola.copy(priority = 0), motorola.copy(priority = -1),
            motorola.copy(activityName = "")).forEach { rejected ->
            assertNull(OfficialAppPolicy.systemUpdater(listOf(rejected)))
        }
        assertNull(OfficialAppPolicy.systemUpdater(emptyList()))
    }

    @Test fun updatedSystemMotorolaAppRetainsEligibility() {
        val updated = motorola.copy(systemApplication = false, updatedSystemApplication = true)
        assertEquals(updated, OfficialAppPolicy.systemUpdater(listOf(updated)))
    }

    @Test fun unrelatedSystemHandlersRequireManualNavigationWhenMotorolaIsUnavailable() {
        val google = motorola.copy(packageName = "com.google.android.gms", priority = 1)
        val settings = motorola.copy(packageName = "com.android.settings", priority = 1000)
        assertNull(OfficialAppPolicy.systemUpdater(listOf(google, settings)))
    }

    @Test fun magiskLauncherIsPackageBoundAndRequiresEnabledExportedActivity() {
        val magisk = motorola.copy(packageName = "com.topjohnwu.magisk", activityName = "com.topjohnwu.magisk.MainActivity",
            priority = 0, systemApplication = false)
        assertTrue(OfficialAppPolicy.magiskActivityAllowed(magisk))
        listOf(magisk.copy(packageName = "example.hidden"), magisk.copy(exported = false),
            magisk.copy(activityEnabled = false), magisk.copy(applicationEnabled = false),
            magisk.copy(activityName = "")).forEach { rejected ->
            assertFalse(OfficialAppPolicy.magiskActivityAllowed(rejected))
        }
    }
}
