package nl.moto.ota

import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

/** Runs on an emulator only in CI; never grants root or invokes external update tools. */
class SafeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsWithLocalReadOnlyDashboard() {
        compose.onNodeWithText("OTA Manager").assertExists()
        compose.onNodeWithText("Lokaal · uitsluitend uitlezend").assertExists()
        compose.onNodeWithText("Firmware").performClick()
        compose.onNodeWithText("Officiële updatebronnen").assertExists()
    }

    @Test fun rootDiagnosticsRequiresAnExplicitDialogAndCanBeCancelled() {
        waitUntilReady()
        compose.onNodeWithText("Controleer en bereid update voor").performScrollTo().performClick()
        compose.onNodeWithText("Uitlezende rootdiagnose").assertExists()
        compose.onNodeWithText("Annuleren").performClick()
        compose.onNodeWithText("OTA Manager").assertExists()
    }

    @Test fun demonstrationIsExplicitAndSeparateFromRealJournal() {
        waitUntilReady()
        compose.onNodeWithText("Diagnose").performClick()
        compose.onNodeWithText("Bekijk veilige simulatie").performScrollTo().performClick()
        compose.onNodeWithText("SIMULATIE").assertExists()
        compose.onNodeWithText("Overzicht").performClick()
        compose.onNodeWithText("DEMO-TARGET", substring = true).assertExists()
    }

    @Test fun emulatorCannotOfferAnUnprovenStockWrite() {
        waitUntilReady()
        compose.onNodeWithText("Workflow").performClick()
        compose.onNodeWithText("Start begeleide updatecontrole").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Maak stockherstelplan ter beoordeling").assertDoesNotExist()
        compose.onNodeWithText("Schrijf uitsluitend deze stock-image").assertDoesNotExist()
    }

    @Test fun fullFileBackedSimulationUsesEveryGuidedStepWithoutTouchingLiveJournal() {
        waitUntilReady()
        val liveStore = nl.moto.ota.platform.update.UpdateController(compose.activity)
        val liveBefore = liveStore.load()
        compose.onNodeWithText("Diagnose").performClick()
        compose.onNodeWithText("Bekijk veilige simulatie").performScrollTo().performClick()
        compose.onNodeWithText("Workflow").performClick()
        compose.onNodeWithText("Start volledige updatesimulatie").performScrollTo().performClick()
        repeat(5) {
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText("Volgende simulatiestap") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Volgende simulatiestap").performScrollTo().performClick()
            compose.waitForIdle()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Simulatie afgerond:", substring=true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("SIMULATIE").assertExists()
        compose.onNodeWithText("Schrijf uitsluitend deze stock-image").assertDoesNotExist()
        org.junit.Assert.assertEquals(liveBefore, liveStore.load())
    }

    private fun waitUntilReady() {
        compose.onNodeWithText("Controleer en bereid update voor").performScrollTo()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Controleer en bereid update voor") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
