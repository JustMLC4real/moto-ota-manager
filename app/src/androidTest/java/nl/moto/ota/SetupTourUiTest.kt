package nl.moto.ota

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import nl.moto.ota.platform.update.UpdateController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Setup and native app-language transitions, without requesting root/network access. */
class SetupTourUiTest {
    private val presentation = PresentationStateRule(tourCompleted = false)
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(presentation).around(compose)

    @Test fun allFourTourPagesLeadToTheDashboardWithoutAuthorizingAnUpdate() {
        val liveBefore = UpdateController(compose.activity).load()
        listOf(R.string.ui_setup_welcome_title, R.string.ui_setup_route_title,
            R.string.ui_setup_root_title, R.string.ui_setup_safety_title).forEach { title ->
            compose.onNodeWithText(presentation.text(title)).assertExists()
            compose.onNodeWithTag("setup_next").performClick()
        }
        compose.onNodeWithTag("setup_tour").assertDoesNotExist()
        compose.onNodeWithTag("nav_overview").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("setup_tour").assertDoesNotExist()
        assertEquals(false, presentation.rootReadsAllowed())
        assertEquals(liveBefore, UpdateController(compose.activity).load())
    }

    @Test fun skipPersistsAndTheGuideCanBeOpenedAgain() {
        compose.onNodeWithTag("setup_skip").performClick()
        compose.onNodeWithTag("open_setup").performClick()
        compose.onNodeWithTag("setup_tour").assertExists()
        compose.onNodeWithTag("setup_skip").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("nav_settings").assertExists()
        compose.onNodeWithTag("setup_tour").assertDoesNotExist()
        assertEquals(false, presentation.rootReadsAllowed())
    }

    @Test fun nativeDutchLanguageSwitchRetainsTourProgressAcrossRecreation() {
        compose.onNodeWithTag("language_nl").performScrollTo().performClick()
        compose.waitUntil(20_000) { presentation.languageTags() == "nl" &&
            compose.onAllNodesWithText(presentation.text(R.string.ui_setup_welcome_title, "nl")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("setup_next").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(presentation.text(R.string.ui_setup_route_title, "nl")).assertExists()
        compose.onNodeWithTag("setup_back").performClick()
        compose.onNodeWithText(presentation.text(R.string.ui_setup_welcome_title, "nl")).assertExists()
        compose.onNodeWithTag("language_nl").performScrollTo().assertIsSelected()
        assertEquals("nl", presentation.languageTags())
        assertEquals(false, presentation.rootReadsAllowed())
    }
}
