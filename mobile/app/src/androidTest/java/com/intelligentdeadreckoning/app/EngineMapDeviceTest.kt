package com.intelligentdeadreckoning.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The navigation engine view on a real device. The engine only publishes while a measured session
 * is running, so these tests pin down what the view must do when it has nothing to show: name
 * itself honestly, borrow nothing from the fixture or the live stream, offer the evaluation overlay
 * as an explicit opt-in, refuse to move the camera onto a position that does not exist, and keep its
 * state across a background/foreground cycle without starting anything by itself.
 */
class EngineMapDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bundledRoadGraphInstallsAndLoadsInPrivateStorage() {
        assertNotNull(com.intelligentdeadreckoning.app.matching.RoadGraphPack(
            compose.activity.applicationContext).install())
    }

    private fun openMap() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("tab_MAP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Offline map loaded · 247 tiles").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Actual device framebuffer evidence, app-private test cache only; no trip data. */
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("Device screenshot unavailable", bitmap)
        val destination = File(compose.activity.cacheDir, "map-verification").apply { mkdirs() }
        File(destination, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun engineModeNamesItsOwnSourceAndShowsNothingItWasNotGiven() {
        openMap()
        compose.onNodeWithTag("map_source_synthetic").assertIsSelected()
        compose.onNodeWithTag("map_source_engine").performScrollTo().performClick()
        compose.onNodeWithTag("map_source").assertTextContains("NAVIGATION ENGINE", substring = true)
        compose.onNodeWithTag("map_mode").assertTextContains("Navigation engine output", substring = true)
        // The fixture console and the live console belong to their own modes and must not appear.
        compose.onNodeWithTag("map_demo_start").assertDoesNotExist()
        compose.onNodeWithTag("live_status").assertDoesNotExist()
        // With no engine session running there is no published position, and the view says exactly
        // that rather than drawing one: every value is the engine's or absent.
        compose.onNodeWithTag("engine_status").assertTextContains("No engine position", substring = true)
        compose.onNodeWithTag("engine_calibration").assertTextContains("Mount calibration: waiting", substring = true)
        compose.onNodeWithTag("engine_calibration").assertTextContains("two separate straight", substring = true)
        compose.onNodeWithTag("engine_values").assertTextContains("Position —", substring = true)
        compose.onNodeWithTag("engine_values").assertTextContains("heading —", substring = true)
        compose.onNodeWithTag("engine_confidence").assertTextContains("not published", substring = true)
        compose.onNodeWithTag("engine_quality").assertTextContains("acquisition GNSS quality", substring = true)
        compose.onNodeWithTag("engine_disclaimer").assertTextContains("reads no sensor", substring = true)
        compose.onNodeWithTag("offline_map").performScrollTo()
        Thread.sleep(1000)
        screenshot("engine-no-output")
        // Nothing published, so the camera cannot be pointed at a position that does not exist.
        compose.onNodeWithTag("engine_focus").assertIsNotEnabled()
        compose.onNodeWithTag("engine_fit").assertIsNotEnabled()
    }

    @Test fun evaluationOverlayIsAnExplicitOptInThatChangesNoSourceLabel() {
        openMap()
        compose.onNodeWithTag("map_source_engine").performScrollTo().performClick()
        compose.onNodeWithTag("map_evaluation").performScrollTo()
        compose.onNodeWithTag("map_evaluation").assertTextContains("Map matching off")
        compose.onNodeWithTag("map_evaluation").assertIsNotSelected()
        compose.onNodeWithTag("engine_evaluation").assertTextContains("Evaluation overlay off", substring = true)
        // Turning it on installs and verifies the bundled road graph on the device.
        compose.onNodeWithTag("map_evaluation").performClick()
        compose.onNodeWithTag("map_evaluation").assertTextContains("Map matching on")
        compose.onNodeWithTag("map_evaluation").assertIsSelected()
        compose.onNodeWithTag("engine_evaluation").assertTextContains("Evaluation overlay on", substring = true)
        // An evaluation overlay is not a source: what the map claims to be drawing cannot change.
        compose.onNodeWithTag("map_source").assertTextContains("NAVIGATION ENGINE", substring = true)
        compose.onNodeWithTag("map_evaluation").performClick()
        compose.onNodeWithTag("map_evaluation").assertTextContains("Map matching off")
        compose.onNodeWithTag("map_evaluation").assertIsNotSelected()
    }

    @Test fun engineModeSurvivesBackgroundingWithoutStartingAnything() {
        openMap()
        compose.onNodeWithTag("map_source_engine").performScrollTo().performClick()
        compose.onNodeWithTag("engine_status").assertTextContains("No engine position", substring = true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // Still the engine view, and still nothing published: the session was not restarted by the
        // screen, and leaving the app did not promote the fixture or the live stream into its place.
        compose.onNodeWithTag("map_source").assertTextContains("NAVIGATION ENGINE", substring = true)
        compose.onNodeWithTag("map_mode").assertTextContains("Navigation engine output", substring = true)
        compose.onNodeWithTag("engine_status").assertTextContains("No engine position", substring = true)
        compose.onNodeWithTag("map_demo_start").assertDoesNotExist()
        compose.onNodeWithTag("live_status").assertDoesNotExist()
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.onNodeWithTag("map_source_engine").assertIsSelected()
    }
}
