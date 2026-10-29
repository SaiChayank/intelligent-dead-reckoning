package com.intelligentdeadreckoning.app.fusion

/**
 * Fusion settings by vehicle class.
 *
 * The defaults in [FusionConfig] are tuned for a car. A motorcycle or scooter differs in ways that make several car
 * gates wrong: it leans through turns (high yaw rate at ordinary speeds, with the body-frame lateral specific force
 * still near zero because the rider leans into the net force), it accelerates and brakes harder, it creeps at lower
 * speeds, and it rides over more vertical disturbance. This profile relaxes those gates and widens the lateral and
 * vertical constraint variances. The values are chosen, not measured: no two-wheeler recording exists in this
 * repository, and the only check is the synthetic leaning-turn test.
 */
object VehicleProfile {
    enum class Kind { CAR, TWO_WHEELER }

    fun configFor(kind: Kind, base: FusionConfig = FusionConfig()): FusionConfig = when (kind) {
        Kind.CAR -> base
        Kind.TWO_WHEELER -> base.copy(
            nhcMinSpeedM_S = 1.0,
            nhcMaxYawRateRad_S = 0.9,
            nhcMaxForwardAccelM_S2 = 1.2,
            nhcDwellS = 2.0,
            nhcLateralVarianceM2 = 0.25,
            nhcVerticalVarianceM2 = 0.5,
            bumpVerticalThresholdM_S2 = 5.0,
        )
    }
}
