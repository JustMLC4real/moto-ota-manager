package nl.moto.ota

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import nl.moto.ota.platform.update.UpdateController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Disposable-emulator UI checks. Never grants root or invokes an updater. */
class SafeUiTest {
    private val presentation = PresentationStateRule(tourCompleted = true)
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(presentation).around(compose)

    @Test fun startsWithLocalReadOnlyDashboard() {
        compose.onNodeWithText(presentation.text(R.string.ui_app_name)).assertExists()
        compose.onNodeWithText(presentation.text(R.string.ui_overview_title)).assertExists()
        compose.onNodeWithTag("nav_firmware").performClick()
        compose.onNodeWithText(presentation.text(R.string.ui_server_check)).assertExists()
        assertEquals(false, presentation.rootReadsAllowed())
    }

    @Test fun rootDiagnosticsRequiresAnExplicitDialogAndCanBeCancelled() {
        waitUntilReady()
        val before = UpdateController(compose.activity).load()
        compose.onNodeWithTag("check_device").performScrollTo().performClick()
        compose.onNodeWithText(presentation.text(R.string.ui_root_diagnostic_title)).assertExists()
        compose.onNodeWithText(presentation.text(R.string.ui_cancel)).performClick()
        compose.onNodeWithText(presentation.text(R.string.ui_root_diagnostic_title)).assertDoesNotExist()
        assertEquals(false, presentation.rootReadsAllowed())
        assertEquals(before, UpdateController(compose.activity).load())
    }

    @Test fun networkCheckRequiresItsOwnDisclosureAndCancelDoesNotEnableIt() {
        waitUntilReady()
        compose.onNodeWithTag("nav_firmware").performClick()
        compose.onNodeWithTag("check_online").performScrollTo().performClick()
        compose.onNodeWithText(presentation.text(R.string.ui_online_privacy)).assertExists()
        compose.onNodeWithText(presentation.text(R.string.ui_cancel)).performClick()
        compose.onNodeWithText(presentation.text(R.string.ui_online_privacy)).assertDoesNotExist()
        assertEquals(false, nl.moto.ota.platform.discovery.CdsController(compose.activity).consentGranted())
    }

    @Test fun demonstrationIsExplicitAndSeparateFromTheLiveJournal() {
        waitUntilReady()
        val before = UpdateController(compose.activity).load()
        openSimulation()
        compose.onNodeWithText(presentation.text(R.string.ui_simulation)).assertExists()
        compose.onNodeWithTag("run_simulation").assertExists()
        compose.onNodeWithTag("start_one_tap_update").assertDoesNotExist()
        compose.onNodeWithTag("nav_overview").performClick()
        compose.onNodeWithText("DEMO-TARGET", substring = true).assertExists()
        assertEquals(before, UpdateController(compose.activity).load())
    }

    @Test fun emulatorCannotOfferAnUnprovenStockWrite() {
        waitUntilReady()
        compose.onNodeWithTag("nav_update").performClick()
        compose.onNodeWithTag("start_one_tap_update").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(presentation.text(R.string.ui_manual_restore_write)).assertDoesNotExist()
        compose.onNodeWithText(presentation.text(R.string.ui_prepare_restore)).assertDoesNotExist()
    }

    @Test fun oneStartRunsTheFullFileBackedSimulationWithoutChangingTheLiveJournal() {
        waitUntilReady()
        val before = UpdateController(compose.activity).load()
        openSimulation()
        compose.onNodeWithTag("run_simulation").performScrollTo().performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("simulation_complete").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("simulation_complete").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(presentation.text(R.string.ui_simulation)).assertExists()
        compose.onNodeWithTag("start_one_tap_update").assertDoesNotExist()
        assertEquals(before, UpdateController(compose.activity).load())
    }

    private fun openSimulation() {
        compose.onNodeWithTag("nav_diagnostics").performClick()
        compose.onNodeWithTag("open_simulation").performScrollTo().performClick()
    }

    private fun waitUntilReady() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("check_device") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
