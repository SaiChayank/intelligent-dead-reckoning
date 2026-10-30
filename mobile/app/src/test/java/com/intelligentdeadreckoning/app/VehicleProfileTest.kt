package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the configuration is tested. A synthetic leaning-turn simulation was tried and dropped because it could not show the
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
}
