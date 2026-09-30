package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fusion filter's geodesy against the validated offline model.
 *
 * `training/strapdown_ins.py` is the reference implementation: its constants and formulas are
 * exercised by `tests/test_strapdown_ins.py`, which checks them independently of any second copy
 * of the same formulas. The numbers asserted here were produced by that module, so this test
 * witnesses that the on-device copy did not drift from the model the baseline report validated —
 * it is a parity check, not an independent re-derivation.
 *
 * Two properties *are* checked independently of the reference: the ENU/geodetic round trip, and
 * the earth-rate vector's closure against cos/sin computed here.
 */
class GeodesyTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, label: String) {
        assertTrue(
            "$label expected $expected got $actual",
            abs(expected - actual) <= tolerance,
        )
    }

    // ------------------------------------------------------------- gravity (Somigliana)

    @Test
    fun normalGravityMatchesTheValidatedModel() {
        // From training/strapdown_ins.py normal_gravity_m_s2 at these latitudes, altitude 0.
        assertClose(9.7803253359, Geodesy.normalGravity(0.0), 1e-9, "gravity at the equator")
        assertClose(9.784995856031937, Geodesy.normalGravity(Math.toRadians(17.5)), 1e-9, "gravity at 17.5N")
        assertClose(9.80619776934378, Geodesy.normalGravity(Math.toRadians(45.0)), 1e-9, "gravity at 45N")
        assertClose(9.832184937799997, Geodesy.normalGravity(Math.PI / 2.0), 1e-9, "gravity at the pole")
    }

    @Test
    fun normalGravityFallsWithTheFreeAirGradient() {
        // The reference module applies the same -3.086e-6 per metre, so altitude and latitude are
        // independent in both implementations by construction.
        for (latitudeRad in listOf(0.0, Math.toRadians(17.5), Math.toRadians(45.0))) {
            val surface = Geodesy.normalGravity(latitudeRad)
            val at1k = Geodesy.normalGravity(latitudeRad, 1000.0)
            assertClose(
                -3.086e-6 * 1000.0, at1k - surface, 1e-12,
                "free-air difference at $latitudeRad",
            )
        }
    }

    @Test
    fun gravityVectorPointsDownOnly() {
        val gravity = Geodesy.gravityEnu(Math.toRadians(17.5), 250.0)
        assertEquals(0.0, gravity.x, 0.0)
        assertEquals(0.0, gravity.y, 0.0)
        assertEquals(
            -Geodesy.normalGravity(Math.toRadians(17.5), 250.0),
            gravity.z, 0.0,
        )
    }

    // ------------------------------------------------------------- radii of curvature

    @Test
    fun radiiMatchTheValidatedModel() {
        // From training/strapdown_ins.py wgs84_radii.
        val equator = Geodesy.radii(0.0)
        assertClose(6378137.0, equator.first, 1e-6, "R_E at the equator")
        assertClose(6335439.3272928195, equator.second, 1e-6, "R_N at the equator")
        val hyderabad = Geodesy.radii(Math.toRadians(17.5))
        assertClose(6380068.323569569, hyderabad.first, 1e-6, "R_E at 17.5N")
        assertClose(6341196.253826835, hyderabad.second, 1e-6, "R_N at 17.5N")
        val mid = Geodesy.radii(Math.toRadians(45.0))
        assertClose(6388838.290121148, mid.first, 1e-6, "R_E at 45N")
        assertClose(6367381.815619548, mid.second, 1e-6, "R_N at 45N")
        val pole = Geodesy.radii(Math.PI / 2.0)
        // At the pole the two radii coincide, which is a property of the ellipsoid, not a coincidence.
        assertClose(6399593.625758493, pole.first, 1e-6, "R_E at the pole")
        assertClose(6399593.625758493, pole.second, 1e-6, "R_N at the pole")
    }

    // ------------------------------------------------------------- frame rates

    @Test
    fun earthRateMatchesTheValidatedModel() {
        // omega * [0, cos(lat), sin(lat)], written out here rather than imported so the vector's
        // shape is visible in the test itself.
        val latitudeRad = Math.toRadians(17.5)
        val omega = 7.292115e-5
        val expected = Vector3(0.0, omega * cos(latitudeRad), omega * sin(latitudeRad))
        val actual = Geodesy.earthRateEnu(latitudeRad)
        assertEquals(expected.x, actual.x, 0.0)
        assertEquals(expected.y, actual.y, 0.0)
        assertEquals(expected.z, actual.z, 0.0)
        // The reference module's value at this latitude, to the digits it prints.
        assertClose(6.954613682305407e-05, actual.y, 1e-17, "earth rate east at 17.5N")
        assertClose(2.1927812711521025e-05, actual.z, 1e-17, "earth rate up at 17.5N")
    }

    @Test
    fun transportRateMatchesTheValidatedModel() {
        val latitudeRad = Math.toRadians(17.5)
        // training/strapdown_ins.py transport_rate_enu, printed to 8 significant digits.
        val eastOnly = Geodesy.transportRateEnu(latitudeRad, 0.0, Vector3(30.0, 0.0, 0.0))
        assertClose(-0.0, eastOnly.x, 1e-12, "north velocity drives the east rate, not this one")
        assertClose(4.70214400e-06, eastOnly.y, 1e-12, "east rate from east velocity")
        assertClose(1.48258031e-06, eastOnly.z, 1e-12, "up rate from east velocity")
        val northOnly = Geodesy.transportRateEnu(latitudeRad, 100.0, Vector3(0.0, 25.0, 0.0))
        assertClose(-3.94241161e-06, northOnly.x, 1e-12, "north rate from north velocity")
        assertEquals(0.0, northOnly.y, 1e-12)
        assertEquals(0.0, northOnly.z, 1e-12)
        val mixed = Geodesy.transportRateEnu(latitudeRad, 42.0, Vector3(12.0, -7.0, 3.0))
        assertClose(1.10388535e-06, mixed.x, 1e-12, "east rate from the north velocity")
        assertClose(1.88084522e-06, mixed.y, 1e-12, "north rate from the east velocity")
        assertClose(5.93028220e-07, mixed.z, 1e-12, "up rate from the east velocity")
    }

    // ------------------------------------------------------------- the anchor map

    @Test
    fun enuAndGeodeticAreExactInverses() {
        val anchor = GeodeticAnchor(17.5, 78.4, 540.0, true)
        // A few-kilometre excursion in every direction: the size a real drive covers, where the
        // stated tangent-plane approximation is claimed to stay far below a metre.
        val offsets = listOf(
            Vector3(0.0, 0.0, 0.0),
            Vector3(3000.0, -4000.0, 25.0),
            Vector3(-1500.0, 2500.0, -30.0),
            Vector3(0.0, 6000.0, 0.0),
        )
        for (offset in offsets) {
            val point = Geodesy.geodeticFromEnu(anchor, offset)
            val back = Geodesy.enuFromGeodetic(anchor, point.latitudeDeg, point.longitudeDeg, point.altitudeM)
            assertClose(offset.x, back.x, 1e-9, "east round trip of $offset")
            assertClose(offset.y, back.y, 1e-9, "north round trip of $offset")
            assertClose(offset.z, back.z, 1e-9, "up round trip of $offset")
        }
    }

    @Test
    fun anAnchorWithoutAltitudeDisablesTheVerticalRow() {
        // hasAltitude is a data property, not a policy: the filter reads it to decide whether a
        // vertical measurement row can exist. It is asserted here so the flag's meaning is pinned
        // next to the type it guards.
        val anchor = GeodeticAnchor(17.5, 78.4, 0.0, false)
        val point = Geodesy.geodeticFromEnu(anchor, Vector3(10.0, 20.0, 0.0))
        assertEquals(anchor.altitudeM, point.altitudeM, 0.0)
        val withAltitude = GeodeticAnchor(17.5, 78.4, 540.0, true)
        val raised = Geodesy.geodeticFromEnu(withAltitude, Vector3(10.0, 20.0, 12.5))
        assertEquals(552.5, raised.altitudeM, 1e-9)
    }

    @Test
    fun oneMetreNorthIsTheExpectedFractionOfADegree() {
        val anchor = GeodeticAnchor(17.5, 78.4, 0.0, true)
        val (_, rN) = Geodesy.radii(Math.toRadians(17.5))
        val point = Geodesy.geodeticFromEnu(anchor, Vector3(0.0, 1.0, 0.0))
        // One metre north is 1/R_N degrees-of-latitude scaled through the radian, and nothing else:
        // the conversion must not silently involve R_E.
        assertClose(Math.toDegrees(1.0 / rN), point.latitudeDeg - anchor.latitudeDeg, 1e-15, "one metre north")
        assertEquals(anchor.longitudeDeg, point.longitudeDeg, 0.0)
    }
}
