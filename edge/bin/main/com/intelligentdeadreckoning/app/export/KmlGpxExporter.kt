package com.intelligentdeadreckoning.app.export

import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import java.util.Locale

/**
 * On-device exporter that converts [NavigationState] trajectories into Google Earth KML
 * and standard GPX 1.1 formats for post-drive verification and auditability.
 */
object KmlGpxExporter {

    data class TrackPoint(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeM: Double,
        val tNs: Long,
        val speedM_S: Double? = null,
    )

    /**
     * Converts a stream of [NavigationState] events into geodetic track points using their anchor.
     */
    fun extractTrackPoints(states: List<NavigationState>): List<TrackPoint> {
        return states.mapIndexedNotNull { index, state ->
            val origin = state.origin_wgs84_deg_m ?: return@mapIndexedNotNull null
            val posEnu = state.position_enu_m ?: return@mapIndexedNotNull null
            val anchor = GeodeticAnchor(
                latitudeDeg = origin.latitude_deg,
                longitudeDeg = origin.longitude_deg,
                altitudeM = origin.altitude_m ?: 0.0,
                hasAltitude = origin.altitude_m != null,
            )
            val pt = Geodesy.geodeticFromEnu(anchor, posEnu)
            val speed = state.velocity_enu_m_s?.let { v ->
                kotlin.math.sqrt(v.x * v.x + v.y * v.y)
            }
            TrackPoint(pt.latitudeDeg, pt.longitudeDeg, pt.altitudeM, index * 100_000_000L, speed)
        }
    }

    /**
     * Generates standard Google Earth KML 2.2 XML string.
     */
    fun toKml(
        trackName: String,
        points: List<TrackPoint>,
        colorHex: String = "ffffaa00", // AABBGGRR in KML
        lineWidth: Int = 4,
    ): String {
        val coords = points.joinToString(" ") { pt ->
            String.format(Locale.US, "%.7f,%.7f,%.2f", pt.longitudeDeg, pt.latitudeDeg, pt.altitudeM)
        }

        return """
<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>$trackName</name>
    <Style id="trackStyle">
      <LineStyle>
        <color>$colorHex</color>
        <width>$lineWidth</width>
      </LineStyle>
    </Style>
    <Placemark>
      <name>$trackName</name>
      <styleUrl>#trackStyle</styleUrl>
      <LineString>
        <extrude>1</extrude>
        <tessellate>1</tessellate>
        <altitudeMode>clampToGround</altitudeMode>
        <coordinates>$coords</coordinates>
      </LineString>
    </Placemark>
  </Document>
</kml>
        """.trimIndent()
    }

    /**
     * Generates standard GPX 1.1 XML string.
     */
    fun toGpx(trackName: String, points: List<TrackPoint>): String {
        val trkpts = points.joinToString("\n") { pt ->
            val speedTag = if (pt.speedM_S != null) "        <speed>${String.format(Locale.US, "%.2f", pt.speedM_S)}</speed>\n" else ""
            """
      <trkpt lat="${String.format(Locale.US, "%.7f", pt.latitudeDeg)}" lon="${String.format(Locale.US, "%.7f", pt.longitudeDeg)}">
        <ele>${String.format(Locale.US, "%.2f", pt.altitudeM)}</ele>
$speedTag      </trkpt>
            """.trimEnd()
        }

        return """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Intelligent Dead Reckoning (SIH #26168)"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>$trackName</name>
    <trkseg>
$trkpts
    </trkseg>
  </trk>
</gpx>
        """.trimIndent()
    }
}
