package com.intelligentdeadreckoning.app

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.intelligentdeadreckoning.app.map.OfflineMapPack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Must be executed on emulator/phone before claiming offline rendering verified. */
class OfflineMapDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun demoOptionsPauseOverridesAndReset() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_MAP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Offline map loaded · 247 tiles").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("demo_options").performScrollTo().performClick()
        compose.onNodeWithTag("scenario_STRAIGHT").performScrollTo().performClick()
        compose.onNodeWithTag("rate_2.0").performScrollTo().performClick()
        compose.onNodeWithTag("map_demo_start").performScrollTo().performClick()
        compose.onNodeWithTag("demo_pause").performScrollTo().performClick()
        compose.onNodeWithTag("demo_pause").assertTextContains("Resume")
        compose.onNodeWithTag("scenario_CURVE").assertIsNotEnabled()
        compose.onNodeWithTag("signal_BLACKOUT").performScrollTo().performClick()
        compose.onNodeWithTag("map_demo_status").assertTextContains("SYNTHETIC DR",substring=true)
        compose.onNodeWithTag("signal_AVAILABLE").performScrollTo().performClick()
        for(name in listOf("comparison","trail","uncertainty","scenario","roads")) {
            compose.onNodeWithTag("overlay_$name").performScrollTo().performClick().assertIsNotSelected()
            compose.onNodeWithTag("overlay_$name").performClick().assertIsSelected()
        }
        compose.onNodeWithTag("demo_pause").performScrollTo().performClick()
        compose.onNodeWithTag("demo_pause").assertTextContains("Pause")
        compose.onNodeWithTag("demo_reset").performClick()
        compose.onNodeWithTag("demo_clock").assertTextContains("0s / 30s",substring=true)
        compose.onNodeWithTag("map_demo_stop").assertIsNotEnabled()
    }
    private fun camera(): CameraPosition {
        fun find(view: View): MapView? {
            if(view is MapView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val result = AtomicReference<CameraPosition>()
        val ready = CountDownLatch(1)
        compose.runOnUiThread {
            requireNotNull(find(compose.activity.window.decorView)).getMapAsync {
                result.set(it.cameraPosition); ready.countDown()
            }
        }
        assertTrue("Camera callback timed out",ready.await(5,TimeUnit.SECONDS))
        return result.get()
    }
    /** Actual device framebuffer evidence, app-private test cache only; no trip data. */
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("Device screenshot unavailable",bitmap)
        val destination = File(compose.activity.cacheDir,"map-verification").apply { mkdirs() }
        File(destination,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun syntheticScenarioVisualEvidenceAndRecreation() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_MAP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Offline map loaded · 247 tiles").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("offline_map").performScrollTo()
        compose.onNodeWithTag("map_attribution").assertIsDisplayed()
        Thread.sleep(1500) // allow native renderer's first fully drawn frame, outside Compose idling
        screenshot("hyderabad-overview")
        compose.onNodeWithTag("map_demo_start").performScrollTo().performClick()
        for ((label,file) in listOf("SYNTHETIC GNSS scenario" to "synthetic-gnss",
            "SYNTHETIC DR scenario (no INS running)" to "synthetic-dr",
            "SYNTHETIC GNSS recovery scenario (no fusion running)" to "synthetic-recovery")) {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("offline_map").performScrollTo()
            Thread.sleep(1200)
            screenshot(file)
        }
        compose.onNodeWithTag("map_demo_stop").performScrollTo().performClick()
        compose.onNodeWithTag("map_demo_stop").assertIsNotEnabled()
        compose.onNodeWithTag("offline_map").performScrollTo()
        val beforePan = camera()
        compose.onNodeWithTag("offline_map").performTouchInput { swipeLeft() }
        Thread.sleep(800)
        assertNotEquals(beforePan.target!!.longitude,camera().target!!.longitude,1e-6)
        val beforeZoom = camera().zoom
        compose.onNodeWithText("Zoom +",substring = false).performScrollTo().performClick()
        Thread.sleep(800)
        assertTrue(camera().zoom > beforeZoom)
        compose.onNodeWithText("North",substring = false).performClick()
        compose.onNodeWithText("Hyderabad",substring = false).performClick()
        Thread.sleep(800)
        assertEquals(17.425,camera().target!!.latitude,1e-5)
        assertEquals(78.475,camera().target!!.longitude,1e-5)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_MAP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Offline map loaded · 247 tiles").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("map_demo_stop").assertIsNotEnabled()
    }
    @Test fun bundledDatabaseInstallIsReadableAndIdempotentWithoutNetworkPermission() {
        val app = compose.activity.applicationContext
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.INTERNET))
        val first = OfflineMapPack(app).install()
        val file = File(first.directory,"hyderabad.mbtiles")
        val modified = file.lastModified()
        assertEquals(first,OfflineMapPack(app).install())
        assertEquals(modified,file.lastModified())
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM tiles",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(247,c.getInt(0)) }
        }
    }
    @Test fun mapLoadsLocallyAndSurvivesTabAndLifecycleChanges() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_MAP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        repeat(2) {
            compose.onNodeWithTag("tab_MAP").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Offline map loaded · 247 tiles").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("offline_map").assertExists()
            compose.onNodeWithTag("map_mode").assertTextContains("no live position", substring = true)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("tab_DASHBOARD").performClick()
        }
    }
    @Test fun syntheticDemoRequiresExplicitStartAndStopsOnBackground() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab_MAP").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Offline map loaded · 247 tiles").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("map_source").assertTextContains("SYNTHETIC UI FIXTURE",substring = true)
        compose.onNodeWithTag("map_demo_stop").assertIsNotEnabled()
        compose.onNodeWithTag("map_demo_start").performScrollTo().performClick()
        compose.onNodeWithTag("map_demo_start").assertIsNotEnabled()
        compose.onNodeWithTag("map_demo_stop").performClick()
        compose.onNodeWithTag("map_demo_start").assertIsEnabled()
        compose.onNodeWithTag("map_demo_start").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("map_demo_start").assertIsEnabled()
        compose.onNodeWithTag("map_demo_stop").assertIsNotEnabled()
        compose.onNodeWithTag("tab_DASHBOARD").performClick()
        compose.onNodeWithTag("tab_MAP").performClick()
        compose.onNodeWithTag("map_demo_stop").assertIsNotEnabled()
    }
}
