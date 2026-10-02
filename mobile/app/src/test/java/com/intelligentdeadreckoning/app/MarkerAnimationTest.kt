package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.map.MapPoint
import com.intelligentdeadreckoning.app.map.MarkerAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smoothing on the drawn marker is a display effect and nothing else: it interpolates inside
 * the span two published positions already bracket, holds at the newest published point, and
 * stops when the published position stops. It never extends a track.
 */
class MarkerAnimationTest {

    private val start = 1_000_000_000L
    private val a = MapPoint(17.4, 78.45)
    private val b = MapPoint(17.401, 78.451)

    @Test
    fun theFirstPublishIsDrawnImmediately() {
        val marker = MarkerAnimation()
        marker.publish(b, start)
        // Nothing to ease from: drawn at the published point from the first frame, and settled as
        // soon as the ease window has passed.
        assertEquals(b, marker.display(start))
        assertFalse(marker.settled(start))
        assertTrue(marker.settled(start + 300_000_000L))
    }

    @Test
    fun betweenTwoPublicationsTheMarkerInterpolates() {
        val marker = MarkerAnimation(easeMs = 300L)
        marker.publish(a, start)
        val second = start + 150_000_000L
        marker.publish(b, second)
        assertFalse(marker.settled(second + 100_000_000L))
        val middle = marker.display(second + 150_000_000L)!!
        assertEquals((a.latitude + b.latitude) / 2, middle.latitude, 1e-12)
        assertEquals((a.longitude + b.longitude) / 2, middle.longitude, 1e-12)
        assertTrue(middle.latitude > a.latitude && middle.latitude < b.latitude)
    }

    @Test
    fun theMarkerNeverMovesPastTheNewestPublishedPoint() {
        val marker = MarkerAnimation(easeMs = 300L)
        marker.publish(a, start)
        marker.publish(b, start + 100_000_000L)
        val settledAt = start + 100_000_000L + 300_000_000L
        assertTrue(marker.settled(settledAt))
        // The same settled value however long the engine stays silent: holding, not extrapolating.
        assertEquals(marker.display(settledAt), marker.display(settledAt + 60_000_000_000L))
        assertEquals(b.latitude, marker.display(settledAt + 60_000_000_000L)!!.latitude, 1e-12)
    }

    @Test
    fun aPublicationWhileEasingContinuesFromWhereTheMarkerIs() {
        val marker = MarkerAnimation(easeMs = 300L)
        marker.publish(a, start)
        val mid = start + 150_000_000L
        marker.publish(b, mid)
        val third = MapPoint(17.402, 78.452)
        val next = mid + 100_000_000L
        val shownBefore = marker.display(next)!!
        marker.publish(third, next)
        assertEquals(shownBefore, marker.display(next))
    }

    @Test
    fun noPublishedPointMeansNothingIsDrawn() {
        val marker = MarkerAnimation()
        marker.publish(a, start)
        marker.publish(null, start + 100_000_000L)
        assertNull(marker.display(start + 100_000_000L))
        assertNull(marker.display(start + 30_000_000_000L))
    }

    @Test
    fun resetForgetsEverything() {
        val marker = MarkerAnimation()
        marker.publish(b, start)
        marker.reset()
        assertNull(marker.display(start + 1_000_000_000L))
        assertTrue(marker.settled(start + 1_000_000_000L))
    }
}
