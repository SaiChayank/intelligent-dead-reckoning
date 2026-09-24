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

/** Must be executed on emulator/phone before claiming offline rendering verified. */
class OfflineMapDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
