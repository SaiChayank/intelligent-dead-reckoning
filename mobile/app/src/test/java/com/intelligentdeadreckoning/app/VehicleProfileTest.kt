package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration is tested, plus a non-harm check on car-like SYNTHETIC motion. A synthetic leaning-turn simulation was tried and dropped because it could not show the
 * profile helps; its large errors at 0.5 rad/s and above did not reproduce in the later HardTurnSweepTest (see the T3 entry
 * in reports/gap_closure_log.md).
 */
class VehicleProfileTest {
    @Test
    fun theCarProfileIsTheUnchangedDefault() {
        assertEquals(FusionConfig(), VehicleProfile.configFor(VehicleProfile.Kind.CAR))
    }

    @Test
    fun theTwoWheelerProfileRelaxesOnlyTheConstraintGatesAndVariances() {
        val car = FusionConfig()
        val bike = VehicleProfile.configFor(VehicleProfile.Kind.TWO_WHEELER)
        assertTrue(bike.nhcMaxYawRateRad_S > car.nhcMaxYawRateRad_S)
        assertTrue(bike.nhcMinSpeedM_S < car.nhcMinSpeedM_S)
        assertTrue(bike.nhcMaxForwardAccelM_S2 > car.nhcMaxForwardAccelM_S2)
        assertTrue(bike.nhcDwellS < car.nhcDwellS)
        assertTrue(bike.nhcLateralVarianceM2 > car.nhcLateralVarianceM2)
        assertTrue(bike.nhcVerticalVarianceM2 > car.nhcVerticalVarianceM2)
        val restored = bike.copy(
            nhcMinSpeedM_S = car.nhcMinSpeedM_S, nhcMaxYawRateRad_S = car.nhcMaxYawRateRad_S,
            nhcMaxForwardAccelM_S2 = car.nhcMaxForwardAccelM_S2, nhcDwellS = car.nhcDwellS,
            nhcLateralVarianceM2 = car.nhcLateralVarianceM2, nhcVerticalVarianceM2 = car.nhcVerticalVarianceM2,
            bumpVerticalThresholdM_S2 = car.bumpVerticalThresholdM_S2,
        )
        assertEquals("nothing else may change", car, restored)
    }

    @Test
    fun aProfileBuildsOnTheConfigItIsGiven() {
        val base = FusionConfig(speedCorrectionEnabled = true, gnssVelocitySigmaM_S = 0.2)
        val bike = VehicleProfile.configFor(VehicleProfile.Kind.TWO_WHEELER, base)
        assertTrue(bike.speedCorrectionEnabled)
        assertEquals(0.2, bike.gnssVelocitySigmaM_S!!, 0.0)
    }

    @Test
    fun theTwoWheelerProfileIsStillMarkedUnvalidatedAndTheCarProfileIsNot() {
        assertTrue(VehicleProfile.validation(VehicleProfile.Kind.TWO_WHEELER).startsWith("UNVALIDATED"))
        assertTrue(!VehicleProfile.validation(VehicleProfile.Kind.CAR).startsWith("UNVALIDATED"))
    }

    /** SYNTHETIC car-like circles at ordinary rates, 12 noise seeds, tight GNSS velocity weighting in both arms. It does not show the profile suits a real two-wheeler. */
    @Test
    fun onCarLikeMotionTheRelaxedGatesDoNotMakeOutageErrorWorse() {
        val sweep = HardTurnSweepTest()
        val car = FusionConfig(gnssVelocitySigmaM_S = 0.2)
        val bike = VehicleProfile.configFor(VehicleProfile.Kind.TWO_WHEELER, car)
        for (w in listOf(0.0, 0.2, 0.4, -0.3)) {
            val c = (1L..12L).map { sweep.simulate(w, car, seed = it).worstOutageM }.sorted()
            val b = (1L..12L).map { sweep.simulate(w, bike, seed = it).worstOutageM }.sorted()
            println("PROFILE yaw %+.1f car median %.1f worst %.1f | two-wheeler median %.1f worst %.1f".format(w, c[5], c.last(), b[5], b.last()))
            assertTrue("yaw $w: two-wheeler median ${b[5]} vs car ${c[5]}", b[5] <= c[5] * 1.5 + 2.0)
            assertTrue("yaw $w: two-wheeler worst ${b.last()}", b.last() < 15.0)
        }
    }
}
