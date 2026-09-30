package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS-84 geodesy, gravity and frame rates for the fusion filter.
 *
 * Every constant and formula here is the same one the validated offline baseline uses
 * (`training/strapdown_ins.py`, verified in `tests/test_strapdown_ins.py`), so the on-device
 * mechanization is not a second, differently-wrong implementation of the same physics. The two
 * places that baseline's tests found to be wrong before are spelled out with their reasons
 * rather than left as bare numbers:
 *
 * - **Gravity** is Somigliana with `k = b*gamma_p/(a*gamma_e) - 1`. The frequently mis-transcribed
 *   `k = a*omega^2/gamma_e` is a different quantity and is wrong by about 1.5e-2 m/s^2 at the
 *   pole, which is why the polar gravity is carried as its own constant.
 * - **The ENU transport rate** is `(-v_N/(R_N+h), +v_E/(R_E+h), +v_E*tan(lat)/(R_E+h))`. The sign
 *   is the content of the term: it forms the stabilising quadratic part of the velocity
 *   equation, and negating it makes that part destabilising instead. The baseline's tests check
 *   it against a finite difference of the ENU triad rather than against a second copy of the
 *   same formula.
 *
 * ## Position representation
 *
 * The filter's position state is metres east, north and up from a fixed anchor, and the geodetic
 * position is **derived** from it by [geodeticFromEnu]. There is exactly one source of truth, so
 * a position measurement update moves the geodetic position with it and cannot leave the two out
 * of step. [enuFromGeodetic] and [geodeticFromEnu] are an exact inverse pair, which is what makes
 * a recorded fix reproducible as a residual.
 *
 * The map is the local tangent plane at the anchor with the ellipsoid radii evaluated **at the
 * anchor**. Over the few kilometres a phone drive covers, the second-order distortion of using
 * anchor radii is far below the metre, and the alternative — re-evaluating the radii per step —
 * makes the map non-invertible and accumulates a geodetic error the baseline report measured as
 * hundreds of degrees before the accumulate-versus-increment bug was found. The approximation is
 * a stated limitation, not a hidden one.
 */

/** Earth rotation rate, rad/s. WGS-84 defining constant. */
const val OMEGA_EARTH_RAD_S = 7.292115e-5

/** WGS-84 equatorial gravity, m/s^2. */
const val WGS84_EQUATORIAL_GRAVITY_M_S2 = 9.7803253359

/** WGS-84 polar gravity, m/s^2. */
const val WGS84_POLAR_GRAVITY_M_S2 = 9.8321849378

/** WGS-84 semi-major axis, metres. */
const val WGS84_SEMI_MAJOR_M = 6378137.0

/** WGS-84 flattening, dimensionless. */
const val WGS84_FLATTENING = 1.0 / 298.257223563

/** Free-air gravity gradient, m/s^2 per metre above the ellipsoid. */
const val FREE_AIR_GRADIENT_PER_M = -3.086e-6

/** A fixed local origin. The anchor is the only absolute reference the filter has. */
data class GeodeticAnchor(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Ellipsoidal altitude in metres; zero when the provider reported none. */
    val altitudeM: Double,
    /** False when the altitude is a placeholder, which disables vertical measurements. */
    val hasAltitude: Boolean,
)

/** A geodetic position derived from the filter's ENU state. */
data class GeodeticPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
)

object Geodesy {
    /** Prime-vertical (`R_E`) and meridional (`R_N`) radii of curvature, metres. */
    fun radii(latitudeRad: Double): Pair<Double, Double> {
        val e2 = WGS84_FLATTENING * (2.0 - WGS84_FLATTENING)
        val sinLat = sin(latitudeRad)
        val denominator = 1.0 - e2 * sinLat * sinLat
        val rE = WGS84_SEMI_MAJOR_M / sqrt(denominator)
        val rN = WGS84_SEMI_MAJOR_M * (1.0 - e2) / (denominator.pow(1.5))
        return rE to rN
    }

    private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)

    /**
     * Somigliana normal gravity on the ellipsoid plus a free-air altitude term, m/s^2.
     *
     * `gamma(phi) = gamma_e (1 + k sin^2 phi) / sqrt(1 - e^2 sin^2 phi)` with
     * `k = b gamma_p / (a gamma_e) - 1`, the closed form that reproduces both published end
     * values exactly.
     */
    fun normalGravity(latitudeRad: Double, altitudeM: Double = 0.0): Double {
        val sinLat = sin(latitudeRad)
        val semiMinorM = WGS84_SEMI_MAJOR_M * (1.0 - WGS84_FLATTENING)
        val k = semiMinorM * WGS84_POLAR_GRAVITY_M_S2 /
            (WGS84_SEMI_MAJOR_M * WGS84_EQUATORIAL_GRAVITY_M_S2) - 1.0
        val e2 = WGS84_FLATTENING * (2.0 - WGS84_FLATTENING)
        val ellipsoid = WGS84_EQUATORIAL_GRAVITY_M_S2 * (1.0 + k * sinLat * sinLat) /
            sqrt(1.0 - e2 * sinLat * sinLat)
        return ellipsoid + FREE_AIR_GRADIENT_PER_M * altitudeM
    }

    /** Gravity as a vector in ENU: north- and east-pointing components are zero. */
    fun gravityEnu(latitudeRad: Double, altitudeM: Double): Vector3 =
        Vector3(0.0, 0.0, -normalGravity(latitudeRad, altitudeM))

    /** Earth rotation rate expressed in ENU at this latitude. */
    fun earthRateEnu(latitudeRad: Double): Vector3 = Vector3(
        0.0,
        OMEGA_EARTH_RAD_S * cos(latitudeRad),
        OMEGA_EARTH_RAD_S * sin(latitudeRad),
    )

    /** Angular rate of the ENU frame relative to the Earth, expressed in ENU. */
    fun transportRateEnu(latitudeRad: Double, altitudeM: Double, velocityEnu: Vector3): Vector3 {
        val (rE, rN) = radii(latitudeRad)
        return Vector3(
            -velocityEnu.y / (rN + altitudeM),
            velocityEnu.x / (rE + altitudeM),
            velocityEnu.x * tan(latitudeRad) / (rE + altitudeM),
        )
    }

    /** ENU metres of a geodetic position relative to the anchor. Exact inverse of [geodeticFromEnu]. */
    fun enuFromGeodetic(
        anchor: GeodeticAnchor,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ): Vector3 {
        val latitudeRad = Math.toRadians(anchor.latitudeDeg)
        val (rE, rN) = radii(latitudeRad)
        val deltaLatitude = Math.toRadians(latitudeDeg - anchor.latitudeDeg)
        val deltaLongitude = Math.toRadians(longitudeDeg - anchor.longitudeDeg)
        return Vector3(
            deltaLongitude * (rE + anchor.altitudeM) * cos(latitudeRad),
            deltaLatitude * (rN + anchor.altitudeM),
            altitudeM - anchor.altitudeM,
        )
    }

    /** Geodetic position of an ENU offset from the anchor. Exact inverse of [enuFromGeodetic]. */
    fun geodeticFromEnu(anchor: GeodeticAnchor, positionEnu: Vector3): GeodeticPoint {
        val latitudeRad = Math.toRadians(anchor.latitudeDeg)
        val (rE, rN) = radii(latitudeRad)
        val latitudeDeg = anchor.latitudeDeg +
            Math.toDegrees(positionEnu.y / (rN + anchor.altitudeM))
        val longitudeDeg = anchor.longitudeDeg +
            Math.toDegrees(positionEnu.x / ((rE + anchor.altitudeM) * cos(latitudeRad)))
        return GeodeticPoint(latitudeDeg, longitudeDeg, anchor.altitudeM + positionEnu.z)
    }
}
