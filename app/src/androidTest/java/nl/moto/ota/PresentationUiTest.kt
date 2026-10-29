package nl.moto.ota

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
}
