package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

/** Foreground/lifecycle UI tests; outdoor GNSS and OS permission dialogs remain device procedure steps. */
class AcquisitionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchingToRealStopsSimulationAndLabelsTheSource() {
        compose.onNodeWithTag("start_button").performScrollTo().performClick()
        compose.onNodeWithTag("source_real").performClick()
        compose.onNodeWithText("REAL PHONE").assertIsDisplayed()
        compose.onNodeWithTag("real_start").assertIsEnabled()
        compose.onNodeWithTag("source_simulation").performClick()
        compose.onNodeWithTag("session_status").assertTextEquals("Stopped")
    }

    @Test fun realStartStopBackgroundAndRecreationRequireExplicitRestart() {
        compose.onNodeWithTag("source_real").performClick()
        compose.onNodeWithTag("real_start").performScrollTo().performClick()
        compose.onNodeWithTag("real_status").assertTextEquals("Running")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("real_status").assertTextEquals("Stopped")
        compose.onNodeWithTag("real_start").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("real_status").assertTextEquals("Stopped")
        compose.onNodeWithTag("real_stop").assertIsNotEnabled()
    }
}
