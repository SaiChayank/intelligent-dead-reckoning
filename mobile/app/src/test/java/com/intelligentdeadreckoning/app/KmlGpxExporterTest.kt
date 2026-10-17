package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.export.KmlGpxExporter
import com.intelligentdeadreckoning.contracts.v1.GeoOrigin
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.LocalizationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KmlGpxExporterTest {

    @Test
    fun testKmlAndGpxExportFromNavigationStates() {
        val anchor = GeoOrigin(17.4350, 78.4450, 505.0)
        val states = listOf(
            NavigationState(
                status = NavigationStatus.TRACKING,
                initialization_mode = InitializationMode.DEPLOYABLE,
                origin_wgs84_deg_m = anchor,
                position_enu_m = Vector3(0.0, 0.0, 0.0),
                velocity_enu_m_s = Vector3(0.0, 10.0, 0.0),
                q_enu_from_vehicle_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
                heading_deg = 0.0,
                calibration_id = "cal_1",
                gnss_used_after_initialization = true,
                localization_mode = LocalizationMode.FUSED,
            ),
            NavigationState(
                status = NavigationStatus.TRACKING,
                initialization_mode = InitializationMode.DEPLOYABLE,
                origin_wgs84_deg_m = anchor,
                position_enu_m = Vector3(0.0, 100.0, 0.0),
                velocity_enu_m_s = Vector3(0.0, 12.0, 0.0),
                q_enu_from_vehicle_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
                heading_deg = 0.0,
                calibration_id = "cal_1",
                gnss_used_after_initialization = true,
                localization_mode = LocalizationMode.DR,
            ),
        )

        val pts = KmlGpxExporter.extractTrackPoints(states)
        assertEquals(2, pts.size)
        assertEquals(17.4350, pts[0].latitudeDeg, 1e-4)
        assertEquals(78.4450, pts[0].longitudeDeg, 1e-4)
        assertTrue(pts[1].latitudeDeg > pts[0].latitudeDeg) // Moved North

        val kml = KmlGpxExporter.toKml("Test Track", pts)
        assertTrue(kml.contains("<kml"))
        assertTrue(kml.contains("<LineString>"))
        assertTrue(kml.contains("78.4450"))

        val gpx = KmlGpxExporter.toGpx("Test Track", pts)
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("<trkpt lat=\"17.4350"))
        assertTrue(gpx.contains("<speed>10.00</speed>"))
    }
}
