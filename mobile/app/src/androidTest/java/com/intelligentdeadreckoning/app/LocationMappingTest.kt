package com.intelligentdeadreckoning.app

import android.location.Location
import com.intelligentdeadreckoning.app.acquisition.mapLocation
import org.junit.Assert.*
import org.junit.Test

class LocationMappingTest {
    @Test fun androidPresenceFlagsPreserveAbsentVersusMeasuredZero() {
        val location = Location("gps").apply { latitude = 12.0; longitude = 77.0 }
        val missing = mapLocation(location, null)
        assertNull(missing.speed_m_s); assertNull(missing.bearing_deg)
        assertNull(missing.altitude_m); assertNull(missing.horizontal_accuracy_m)
        location.speed = 0f; location.bearing = 0f; location.altitude = 0.0; location.accuracy = 0f
        val zero = mapLocation(location, 0)
        assertEquals(0.0,zero.speed_m_s!!,0.0); assertEquals(0.0,zero.bearing_deg!!,0.0)
        assertEquals(0.0,zero.altitude_m!!,0.0); assertEquals(0L,zero.satellites_used)
        location.removeSpeed(); location.removeBearing()
        assertNull(mapLocation(location,null).speed_m_s)
        assertNull(mapLocation(location,null).bearing_deg)
    }
}
