package com.intelligentdeadreckoning.app.settings

import android.content.Context
import android.content.SharedPreferences

/** Where a few user choices are kept between launches. An interface so the logic is testable without a device. */
interface SettingsStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class SharedPreferencesSettingsStore(private val prefs: SharedPreferences) : SettingsStore {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val FILE = "idr_settings"
        fun of(context: Context) = SharedPreferencesSettingsStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

class InMemorySettingsStore : SettingsStore {
    private val values = HashMap<String, Boolean>()
    override fun getBoolean(key: String, default: Boolean) = values[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
}

/** The app's persisted choices. Every one defaults to the behaviour the app had before the choice existed. */
class AppSettings(private val store: SettingsStore) {
    /**
     * Experimental map matching: the road-graph overlay and, from the next navigation session on, the engine's HMM map
     * matcher with a cross-track correction. Default OFF. The evidence for it so far is synthetic motion on the Hyderabad
     * road graph (reports/map_matching_eval.json); it has not been tested on a recorded drive.
     */
    var experimentalMapMatching: Boolean
        get() = store.getBoolean(KEY_EXPERIMENTAL_MAP_MATCHING, false)
        set(value) = store.putBoolean(KEY_EXPERIMENTAL_MAP_MATCHING, value)

    companion object {
        const val KEY_EXPERIMENTAL_MAP_MATCHING = "experimental_map_matching"
    }
}
