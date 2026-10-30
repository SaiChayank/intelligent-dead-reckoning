package com.intelligentdeadreckoning.app

import android.content.SharedPreferences
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.app.navigation.AppFusion
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.app.settings.AppSettings
import com.intelligentdeadreckoning.app.settings.InMemorySettingsStore
import com.intelligentdeadreckoning.app.settings.SharedPreferencesSettingsStore
import com.intelligentdeadreckoning.app.ui.MapMatchingCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** The experimental map-matching switch: persisted, default off, and read by the engine factory at session start. */
class MapMatchingToggleTest {
    // ---- persistence -------------------------------------------------------------------------------------

    private class FakePrefs : SharedPreferences {
        val values = HashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value ?: "" }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { values.putAll(pending); return true }
            override fun apply() { values.putAll(pending) }
        }
    }

    @Test
    fun theSwitchDefaultsToOffAndTheChoiceSurvivesANewSettingsObject() {
        val prefs = FakePrefs()
        assertFalse(AppSettings(SharedPreferencesSettingsStore(prefs)).experimentalMapMatching)
        AppSettings(SharedPreferencesSettingsStore(prefs)).experimentalMapMatching = true
        assertTrue("a new object over the same file must see the stored choice",
            AppSettings(SharedPreferencesSettingsStore(prefs)).experimentalMapMatching)
        AppSettings(SharedPreferencesSettingsStore(prefs)).experimentalMapMatching = false
        assertFalse(AppSettings(SharedPreferencesSettingsStore(prefs)).experimentalMapMatching)
        assertEquals("one stored key, named explicitly", setOf(AppSettings.KEY_EXPERIMENTAL_MAP_MATCHING), prefs.values.keys)
    }

    @Test
    fun inMemoryStoreBehavesTheSameAsTheFileBackedOne() {
        val settings = AppSettings(InMemorySettingsStore())
        assertFalse(settings.experimentalMapMatching)
        settings.experimentalMapMatching = true
        assertTrue(settings.experimentalMapMatching)
    }

    // ---- plumbing to the engine --------------------------------------------------------------------------

    private class Captured(var heading: Double? = null, var config: FusionConfig? = null, var graph: RoadGraph? = null, var builds: Int = 0)

    private fun stubEngine(): NavigationEngine = Proxy.newProxyInstance(
        NavigationEngine::class.java.classLoader, arrayOf(NavigationEngine::class.java),
    ) { _, _, _ -> null } as NavigationEngine

    private fun factory(captured: Captured, enabled: () -> Boolean, graph: () -> RoadGraph?) =
        AppFusion.factory(enabled, graph) { heading, config, roadGraph ->
            captured.heading = heading
            captured.config = config
            captured.graph = roadGraph
            captured.builds++
            stubEngine()
        }

    private val graph: RoadGraph by lazy {
        val dir = java.nio.file.Files.createTempDirectory("toggle-test").toFile()
        dir.deleteOnExit()
        val file = java.io.File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(listOf(RoadGraphFixtures.northSouth(1L, 0.0))))
        RoadGraph.load(file)
    }

    @Test
    fun withTheSwitchOffTheEngineConfigurationIsExactlyTheShippedOneAndHasNoGraph() {
        val c = Captured()
        factory(c, { false }, { graph })(1.25)
        assertEquals(1.25, c.heading!!, 0.0)
        assertEquals(AppFusion.config(experimentalMapMatching = false), c.config)
        assertNull(c.graph)
        val shipped = FusionConfig(
            aiSpeedEstimationEnabled = true, adaptiveNoiseScalingEnabled = true,
            mountSlipDetectionEnabled = true, mapHeadingFeedbackEnabled = true, adaptiveDecimationEnabled = true,
        )
        assertEquals("the off configuration must equal the pre-switch app configuration", shipped, c.config)
        assertFalse(c.config!!.hmmMapMatchingEnabled)
        assertFalse(c.config!!.mapCrossTrackEnabled)
    }

    @Test
    fun withTheSwitchOnAndAGraphLoadedTheEngineGetsTheMatcherCrossTrackAndTheGraph() {
        val c = Captured()
        factory(c, { true }, { graph })(0.0)
        assertSame(graph, c.graph)
        assertTrue(c.config!!.hmmMapMatchingEnabled)
        assertTrue(c.config!!.mapCrossTrackEnabled)
        assertEquals(AppFusion.config(false).copy(hmmMapMatchingEnabled = true, mapCrossTrackEnabled = true,
            mapCrossTrackMinConfidence = AppFusion.MAP_CROSS_TRACK_MIN_CONFIDENCE), c.config)
    }

    @Test
    fun withTheSwitchOnButNoGraphYetTheEngineIsBuiltWithoutMapFeedback() {
        val c = Captured()
        factory(c, { true }, { null })(0.0)
        assertNull(c.graph)
        assertEquals(AppFusion.config(false), c.config)
    }

    @Test
    fun theSwitchIsReadWhenFusionStartsNotWhenTheFactoryIsMade() {
        var enabled = false
        val c = Captured()
        val make = factory(c, { enabled }, { graph })
        make(0.0)
        assertNull(c.graph)
        enabled = true
        make(0.0)
        assertNotNull("a later session picks up the change", c.graph)
        assertEquals(2, c.builds)
    }

    // ---- wording -----------------------------------------------------------------------------------------

    @Test
    fun theLabelsStayExactForTheDeviceTestsAndTheCaveatStatesTheEvidence() {
        assertEquals("Map matching on", MapMatchingCopy.label(true))
        assertEquals("Map matching off", MapMatchingCopy.label(false))
        assertTrue(MapMatchingCopy.CAVEAT.startsWith("Experimental"))
        assertTrue(MapMatchingCopy.CAVEAT.contains("synthetic"))
        assertTrue(MapMatchingCopy.CAVEAT.contains("not a recorded drive"))
        assertTrue(MapMatchingCopy.CAVEAT.contains("next navigation session"))
    }
}
