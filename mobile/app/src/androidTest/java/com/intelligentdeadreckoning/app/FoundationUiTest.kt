package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

class FoundationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun initialScreenClearlyIdentifiesSimulationAndMissingSamples() {
        compose.onNodeWithText("SIMULATION").assertIsDisplayed()
        compose.onNodeWithTag("demo_speed").assertTextEquals("—")
        compose.onNodeWithTag("start_button").assertIsEnabled()
        compose.onNodeWithTag("stop_button").assertIsNotEnabled()
    }

    @Test fun startStopAndRestartWorkFromDashboard() {
        compose.onNodeWithTag("start_button").performScrollTo().performClick()
        compose.onNodeWithTag("session_status").assertTextEquals("Running")
        compose.onNodeWithTag("start_button").assertIsNotEnabled()
        compose.onNodeWithTag("stop_button").performScrollTo().performClick()
        compose.onNodeWithTag("session_status").assertTextEquals("Stopped")
        compose.onNodeWithText("Start new demo").assertExists()
        compose.onNodeWithTag("start_button").performClick()
        compose.onNodeWithTag("session_status").assertTextEquals("Running")
    }

    @Test fun screenNavigationKeepsSourceLabelAndSession() {
        compose.onNodeWithTag("start_button").performScrollTo().performClick()
        compose.onNodeWithTag("tab_DIAGNOSTICS").performClick()
        compose.onNodeWithText("SIMULATION").assertIsDisplayed()
        compose.onNodeWithText("Accelerometer").assertIsDisplayed()
        compose.onNodeWithText("Gyroscope").assertExists()
        compose.onNodeWithText("Running").assertExists()
        compose.onNodeWithTag("tab_ABOUT").performClick()
        compose.onNodeWithText("SIMULATION").assertIsDisplayed()
        compose.onNodeWithText("App foundation").assertIsDisplayed()
        compose.onNodeWithTag("tab_DASHBOARD").performClick()
        compose.onNodeWithTag("session_status").assertTextEquals("Running")
    }

    @Test fun activityLeavingForegroundStopsAndDoesNotAutoResume() {
        compose.onNodeWithTag("start_button").performScrollTo().performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("session_status").assertTextEquals("Stopped")
        compose.onNodeWithTag("session_message").assertTextEquals("Stopped in background. Start a new demo to continue.")
        compose.onNodeWithTag("start_button").assertIsEnabled()
    }

    @Test fun recreationAlsoStopsSessionSafely() {
        compose.onNodeWithTag("start_button").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("session_status").assertTextEquals("Stopped")
        compose.onNodeWithTag("stop_button").assertIsNotEnabled()
    }
}
