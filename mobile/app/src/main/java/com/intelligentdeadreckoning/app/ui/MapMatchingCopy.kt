package com.intelligentdeadreckoning.app.ui

/** Wording for the experimental map-matching switch. The chip labels stay "Map matching on/off" (device tests match them exactly). */
object MapMatchingCopy {
    const val ON = "Map matching on"
    const val OFF = "Map matching off"
    const val TEST_TAG_CAVEAT = "map_matching_caveat"

    /** Shown beside the switch whether it is on or off. */
    const val CAVEAT =
        "Experimental. The evidence for map matching so far is synthetic motion on the Hyderabad road graph, not a recorded " +
            "drive. The choice is remembered, and it reaches the navigation engine from the next navigation session, never a running one."

    fun label(enabled: Boolean) = if (enabled) ON else OFF
}
