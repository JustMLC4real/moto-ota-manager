package nl.moto.ota

import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import nl.moto.ota.platform.update.UpdateController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class PresentationUiTest {
    private val presentation = PresentationStateRule(tourCompleted = true)
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(presentation).around(compose)

    @Test fun switchingLanguagesUsesAndroidAppLocalesAndSurvivesRecreation() {
        val before = UpdateController(compose.activity).load()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("language_nl").performScrollTo().performClick()
        compose.waitUntil(20_000) { presentation.languageTags() == "nl" &&
            compose.onAllNodesWithText(presentation.text(R.string.ui_settings_title, "nl")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(presentation.text(R.string.ui_settings_title, "nl")).assertExists()
        compose.onNodeWithTag("language_nl").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("language_en").performScrollTo().performClick()
        compose.waitUntil(20_000) { presentation.languageTags() == "en" &&
            compose.onAllNodesWithText(presentation.text(R.string.ui_settings_title)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, UpdateController(compose.activity).load())
        assertEquals(false, presentation.rootReadsAllowed())
    }

    @Test fun appearanceIsPersistedAndDoesNotAuthorizeAnUpdate() {
        val before = UpdateController(compose.activity).load()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("theme_dark").performScrollTo().performClick()
        compose.onNodeWithTag("theme_dark").assertIsSelected()
        assertEquals("DARK", presentation.preference("appearance"))
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("theme_dark").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("theme_light").performScrollTo().performClick()
        compose.onNodeWithTag("theme_light").assertIsSelected()
        assertEquals("LIGHT", presentation.preference("appearance"))
        assertEquals(before, UpdateController(compose.activity).load())
        assertEquals(false, presentation.rootReadsAllowed())
    }

    @Test fun firmwareToolsExpandWithoutAuthorizingRootNetworkOrAnUpdate() {
        waitUntilReady()
        val before = UpdateController(compose.activity).load()
        compose.onNodeWithTag("nav_firmware").performClick()
        compose.onNodeWithTag("check_online").assertExists()
        compose.onNodeWithTag("import_ota").assertDoesNotExist()
        compose.onNodeWithTag("monitoring_switch").assertDoesNotExist()
        compose.onNodeWithTag("import_details").performScrollTo().performClick()
        compose.onNodeWithTag("import_ota").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("import_details").performScrollTo().performClick()
        compose.onNodeWithTag("import_ota").assertDoesNotExist()
        compose.onNodeWithTag("monitoring_details").performScrollTo().performClick()
        compose.onNodeWithTag("monitoring_switch").performScrollTo().assertExists()
        assertEquals(false, presentation.rootReadsAllowed())
        assertEquals(false, nl.moto.ota.platform.discovery.CdsController(compose.activity).consentGranted())
        assertEquals(before, UpdateController(compose.activity).load())
    }

    @Test fun firmwareDisclosureSurvivesNavigationAndActivityRecreation() {
        waitUntilReady()
        compose.onNodeWithTag("nav_firmware").performClick()
        compose.onNodeWithTag("import_details").performScrollTo().performClick()
        compose.onNodeWithTag("import_ota").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav_overview").performClick()
        compose.onNodeWithTag("nav_firmware").performClick()
        compose.onNodeWithTag("import_ota").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("nav_firmware").assertIsSelected()
        compose.onNodeWithTag("import_ota").performScrollTo().assertIsDisplayed()
        assertEquals(false, presentation.rootReadsAllowed())
    }

    @Test fun simulationKeepsTheExpandedImportAndMonitorActionsDisabled() {
        waitUntilReady()
        val before = UpdateController(compose.activity).load()
        compose.onNodeWithTag("nav_diagnostics").performClick()
        compose.onNodeWithTag("open_simulation").performScrollTo().performClick()
        compose.onNodeWithTag("nav_firmware").performClick()
        compose.onNodeWithTag("check_online").assertIsNotEnabled()
        compose.onNodeWithTag("import_details").performScrollTo().performClick()
        compose.onNodeWithTag("import_ota").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("monitoring_details").performScrollTo().performClick()
        compose.onNodeWithTag("monitoring_switch").performScrollTo().assertIsNotEnabled()
        assertEquals(before, UpdateController(compose.activity).load())
        assertEquals(false, presentation.rootReadsAllowed())
    }

    @Test fun reducedMotionKeepsNavigationDisclosuresAndPermissionChoicesUsable() {
        waitUntilReady()
        val resolver = compose.activity.contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getString(resolver, key)
        val before = UpdateController(compose.activity).load()
        try {
            setAnimationScale("0")
            assertEquals(0f, Settings.Global.getFloat(resolver, key), 0f)
            // A fresh composition observes the platform setting before any transitions.
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            waitUntilReady()
            compose.onNodeWithTag("nav_firmware").performClick()
            compose.onNodeWithTag("nav_firmware").assertIsSelected()
            compose.onNodeWithTag("import_details").performScrollTo().performClick()
            compose.onNodeWithTag("import_ota").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("import_details").performScrollTo().performClick()
            compose.onNodeWithTag("import_ota").assertDoesNotExist()
            compose.onNodeWithTag("check_online").performScrollTo().performClick()
            compose.onNodeWithText(presentation.text(R.string.ui_online_privacy)).assertIsDisplayed()
            compose.onNodeWithText(presentation.text(R.string.ui_cancel)).performClick()
            compose.onNodeWithTag("nav_overview").performClick()
            compose.onNodeWithTag("nav_overview").assertIsSelected()
            compose.onNodeWithTag("check_device").performScrollTo().performClick()
            compose.onNodeWithText(presentation.text(R.string.ui_root_diagnostic_title)).assertIsDisplayed()
            compose.onNodeWithText(presentation.text(R.string.ui_cancel)).performClick()
            assertEquals(false, presentation.rootReadsAllowed())
            assertEquals(false, nl.moto.ota.platform.discovery.CdsController(compose.activity).consentGranted())
            assertEquals(before, UpdateController(compose.activity).load())
        } finally {
            setAnimationScale(previous)
            assertEquals(previous, Settings.Global.getString(resolver, key))
        }
    }

    private fun setAnimationScale(value: String?) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Global.putString(compose.activity.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, value))
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun waitUntilReady() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("check_device") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
