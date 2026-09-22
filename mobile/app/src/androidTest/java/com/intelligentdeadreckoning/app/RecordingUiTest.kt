package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

/** Uses the real downstream stream; absent hardware/permission may produce an empty valid session.
 * These tests do not establish GNSS accuracy or sustained recording performance.
 */
class RecordingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun phase(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText("Local recording · $text").fetchSemanticsNodes().isNotEmpty()
    }
    private fun start() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Local recording · starting").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("source_real").performClick()
        compose.onNodeWithTag("real_start").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("recording_start") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("recording_start").performScrollTo().performClick()
        phase("recording")
    }
    @Test fun explicitRecordingStopLeavesAcquisitionRunning() {
        start()
        compose.onNodeWithTag("recording_start").assertIsNotEnabled()
        compose.onNodeWithTag("recording_stop").performScrollTo().performClick()
        phase("completed")
        compose.onNodeWithTag("real_status").assertTextEquals("Running")
    }
    @Test fun acquisitionStopFinalizesRecording() {
        start()
        compose.onNodeWithTag("real_stop").performScrollTo().performClick()
        phase("completed")
        compose.onNodeWithTag("recording_start").assertIsNotEnabled()
    }
    @Test fun backgroundFinalizesAndReturningDoesNotRestart() {
        start()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        phase("completed")
        compose.onNodeWithTag("real_status").assertTextEquals("Stopped")
        compose.onNodeWithTag("recording_start").assertIsNotEnabled()
    }
    @Test fun sourceSwitchEndsTheCurrentRecording() {
        start()
        compose.onNodeWithTag("source_simulation").performClick()
        phase("completed")
        compose.onNodeWithTag("recording_start").assertIsNotEnabled()
        compose.onNodeWithText("SIMULATION").assertIsDisplayed()
    }
}
