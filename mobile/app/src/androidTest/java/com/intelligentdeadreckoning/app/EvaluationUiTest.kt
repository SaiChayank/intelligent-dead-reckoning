package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Evaluation tab on a device. It is an engineering surface, so what it must do is state the
 * provenance and the limits of the report it is showing, keep the driver's recording console off
 * the page, and never render a metric the bundled report did not measure. The bundled document is
 * staged into the APK from `contracts/evaluation/v1/golden_report.json` at build time, so this test
 * also proves the asset survives packaging.
 */
class EvaluationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openEvaluation() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("tab_EVALUATION").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("tab_EVALUATION").performClick()
    }

    @Test fun theTabShowsTheBundledReportWithItsReferenceAndLimits() {
        openEvaluation()
        compose.onNodeWithTag("evaluation_report_name").assertTextContains("scripted-outage-70s", substring = true)
        compose.onNodeWithTag("evaluation_reference_state").assertTextContains("independent", substring = true)
        compose.onNodeWithTag("evaluation_table").assertExists()
        assertTrue(
            "the arm that did not run must explain itself",
            compose.onAllNodesWithTag("evaluation_absent").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("evaluation_platform_note").assertTextContains("Host JVM replay", substring = true)
        compose.onNodeWithTag("evaluation_reload").assertExists()
    }

    @Test fun theDriverConsoleStaysOffTheEvaluationPage() {
        openEvaluation()
        compose.onNodeWithTag("recording_status").assertDoesNotExist()
        compose.onNodeWithTag("evaluation_empty").assertDoesNotExist()
        compose.onNodeWithTag("evaluation_problem").assertDoesNotExist()
        compose.onNodeWithTag("tab_DASHBOARD").performClick()
        compose.onNodeWithTag("recording_status").assertExists()
    }
}
