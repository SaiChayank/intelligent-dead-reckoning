package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.app.fusion.FusionConfig

/**
 * Fusion settings for the edge engine, by the kind of inertial source it is fed.
 *
 * The learned speed correction (v3) and the tight GNSS-velocity weighting were measured on a vehicle-frame inertial
 * input (the IO-VNBD external-IMU case) and help there (reports/iovnbd_eval/vehicle). On a phone accelerometer
 * export the same correction has no support, so the phone app keeps them off and only this profile switches them on.
 */
object EdgeProfiles {
    /** GNSS velocity one-sigma, m/s; the value chosen by the sweep recorded in reports/gap_closure_log.md. */
    const val GNSS_VELOCITY_SIGMA_M_S = 0.2

    /** The default: exactly the settings the shipped navigation engine uses on its own. */
    val default: FusionConfig get() = FusionConfig()

    /** A vehicle-mounted external IMU whose axes are already vehicle-aligned. */
    val externalVehicleImu: FusionConfig
        get() = FusionConfig(speedCorrectionEnabled = true, gnssVelocitySigmaM_S = GNSS_VELOCITY_SIGMA_M_S)

    fun byName(name: String): FusionConfig = when (name) {
        "default" -> default
        "external-imu" -> externalVehicleImu
        else -> throw IllegalArgumentException("unknown profile '$name' (expected default or external-imu)")
    }
}
