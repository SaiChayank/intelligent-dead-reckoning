package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionInitialState
import com.intelligentdeadreckoning.app.fusion.GnssInsEkf
import com.intelligentdeadreckoning.app.fusion.GnssUpdateResult
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filter-level vehicle-motion constraints: the zero-velocity update and the non-holonomic
 * lateral/vertical constraints, applied through the same gated update path as GNSS rows.
 *
 * Frame convention under test (`C_VN` maps vehicle to ENU; its **columns** are the vehicle
 * axes expressed in ENU): the identity `C_VN` points vehicle forward at ENU-east, so the
 * lateral axis is north; `quaternionAboutZ(pi/2)` points forward at ENU-north, so lateral is
 * west. Getting these backwards is the classic NHC bug, which is why both cases are pinned.
 *
 * Nothing here forces a constraint: each pseudo-measurement passes the joint NIS gate with a
 * deliberately loose variance, and a refused constraint is booked as a constraint refusal,
 * never as a GNSS rejection or a persistent-rejection span extension.
 */
class VehicleConstraintsTest {

    private val anchor = GeodeticAnchor(17.5, 78.4, 0.0, true)
    private val filter = GnssInsEkf(FusionConfig())

    private fun align(velocityEnu: Vector3, velocityVariance: Double = 25.0) {
        assertTrue(
            filter.align(
                FusionInitialState(
                    anchor = anchor,
                    qEnuFromDevice = quaternionAboutZ(Math.PI / 2.0),
                    velocityEnuM_S = velocityEnu,
                    gyroBiasDeviceRad_S = Vector3(0.0, 0.0, 0.0),
                    accelBiasDeviceM_S2 = Vector3(0.0, 0.0, 0.0),
                    positionVarianceM2 = Vector3(25.0, 25.0, 25.0),
                    velocityVarianceM2 = Vector3(velocityVariance, velocityVariance, velocityVariance),
                    attitudeVarianceRad2 = Vector3(1e-4, 1e-4, 1e-4),
                    gyroBiasVarianceRad2_S2 = 1e-4,
                    accelBiasVarianceM2_S4 = 1e-2,
                ),
                0L,
            ),
        )
    }

    private fun speed(v: Vector3) = sqrt(v.x * v.x + v.y * v.y)

    @Test
    fun aZeroVelocityUpdateDrivesVelocityTowardZeroAndShrinksTheCovariance() {
        align(Vector3(1.0, 0.0, 0.0))
        val before = filter.solution
        val outcome = filter.updateZupt(1_000_000_000L, FusionConfig().zuptVarianceM2)
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        val after = filter.solution
        assertTrue(
            "speed ${speed(before.velocityEnuM_S)} -> ${speed(after.velocityEnuM_S)}",
            speed(after.velocityEnuM_S) < speed(before.velocityEnuM_S),
        )
        assertTrue(
            "sigma ${before.velocitySigmaM_S.x} -> ${after.velocitySigmaM_S.x}",
            after.velocitySigmaM_S.x < before.velocitySigmaM_S.x,
        )
        assertEquals(1L, after.constraintAccepted)
        assertEquals(0L, after.rejectedUpdates)
    }

    @Test
    fun aWrongZeroVelocityClaimIsGatedByItsNisNotForced() {
        // A filter that already knows its velocity (tight prior, sigma 0.5 m/s) moving at
        // 5 m/s: a zero claim at sigma 0.2 m/s is wildly inconsistent — NIS ~ 80 against a
        // threshold of 13.8 — so the gate refuses and the velocity is untouched. This is the
        // declared "do not blindly force constraints" behavior.
        align(Vector3(5.0, 0.0, 0.0), velocityVariance = 0.25)
        val speedBefore = speed(filter.solution.velocityEnuM_S)
        val outcome = filter.updateZupt(1_000_000_000L, FusionConfig().zuptVarianceM2)
        assertEquals(GnssUpdateResult.REJECTED_GATE, outcome.result)
        assertTrue("nis ${outcome.nis} vs threshold ${outcome.gateThreshold}", outcome.nis > outcome.gateThreshold)
        assertEquals(speedBefore, speed(filter.solution.velocityEnuM_S), 1e-12)
        assertEquals(0L, filter.solution.constraintAccepted)
        assertEquals(1L, filter.solution.constraintRejected)
        // The GNSS book is untouched by a constraint refusal.
        assertEquals(0L, filter.solution.rejectedUpdates)
        assertNull(filter.solution.lastRejectionNis)
    }

    @Test
    fun aZeroVelocityUpdateIsAcceptedWhenTheFilterGenuinelyDoesNotKnowBetter() {
        // The loose-prior twin of the rejection test: a filter aligned with a wide velocity
        // prior (sigma 5 m/s) finds a zero claim statistically consistent, and the update
        // *helps* — this is exactly the cold-start case the ZUPT exists for.
        align(Vector3(5.0, 0.0, 0.0), velocityVariance = 25.0)
        val outcome = filter.updateZupt(1_000_000_000L, FusionConfig().zuptVarianceM2)
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        assertTrue("nis ${outcome.nis} vs threshold ${outcome.gateThreshold}", outcome.nis <= outcome.gateThreshold)
        assertTrue(speed(filter.solution.velocityEnuM_S) < 1.0)
        assertEquals(1L, filter.solution.constraintAccepted)
    }

    @Test
    fun nhcWithIdentityFrameConstrainsNorthAsLateralAndLeavesForwardEastAlone() {
        // Identity C_VN: vehicle forward = east, lateral = north, vertical = up. Starting with
        // (east 10, north 3, up 0.5): the lateral and vertical rows pull north and up toward
        // zero while the forward east component survives.
        align(Vector3(10.0, 3.0, 0.5))
        val outcome = filter.updateNhc(
            1_000_000_000L,
            Quaternion(1.0, 0.0, 0.0, 0.0),
            FusionConfig().nhcLateralVarianceM2,
            FusionConfig().nhcVerticalVarianceM2,
        )
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        val v = filter.solution.velocityEnuM_S
        assertTrue("forward east ${v.x} must survive", v.x > 9.0)
        assertTrue("lateral north ${v.y} must be pulled toward zero", abs(v.y) < 3.0)
        assertTrue("vertical up ${v.z} must be pulled toward zero", abs(v.z) < 0.5)
        assertEquals(1L, filter.solution.constraintAccepted)
    }

    @Test
    fun nhcWithANorthwardFrameConstrainsEastAsLateral() {
        // quaternionAboutZ(pi/2): vehicle forward = north, lateral = west. Starting with
        // (east 5, north 10, up 0): the lateral row pulls the east component toward zero and
        // the forward north component survives.
        align(Vector3(5.0, 10.0, 0.0))
        val outcome = filter.updateNhc(
            1_000_000_000L,
            quaternionAboutZ(Math.PI / 2.0),
            FusionConfig().nhcLateralVarianceM2,
            FusionConfig().nhcVerticalVarianceM2,
        )
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        val v = filter.solution.velocityEnuM_S
        assertTrue("forward north ${v.y} must survive", v.y > 9.0)
        assertTrue("lateral east ${v.x} must be pulled toward zero", abs(v.x) < 5.0)
    }

    @Test
    fun constraintRefusalsNeverMoveTheGnssCountersOrTheGuard() {
        align(Vector3(5.0, 0.0, 0.0), velocityVariance = 0.25)
        val before = filter.solution
        filter.updateZupt(1_000_000_000L, FusionConfig().zuptVarianceM2)
        val after = filter.solution
        assertEquals(before.rejectedUpdates, after.rejectedUpdates)
        assertEquals(before.lastRejectionNis, after.lastRejectionNis)
        assertEquals(0L, after.rejectedUpdates)
        assertEquals(1L, after.constraintRejected)
    }

    @Test
    fun constraintAcceptanceLeavesTheGnssFreshnessBookUntouched() {
        align(Vector3(0.0, 0.5, 0.0))
        filter.updateZupt(1_000_000_000L, FusionConfig().zuptVarianceM2)
        val after = filter.solution
        assertEquals(1L, after.constraintAccepted)
        // A constraint is not a GNSS update: the freshness clock must not move.
        assertNull(after.lastGnssUpdateNs)
        assertEquals(0L, after.acceptedUpdates)
    }
}
