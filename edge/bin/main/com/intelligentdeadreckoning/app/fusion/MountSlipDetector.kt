package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.app.calibration.angleBetweenVectors
import com.intelligentdeadreckoning.app.calibration.canonical
import com.intelligentdeadreckoning.app.calibration.conjugate
import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.app.calibration.normalized
import com.intelligentdeadreckoning.app.calibration.normalizedOrNull
import com.intelligentdeadreckoning.app.calibration.plus
import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.calibration.rotate
import com.intelligentdeadreckoning.app.calibration.rotationFromTo
import com.intelligentdeadreckoning.app.calibration.times
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.atan2
import kotlin.math.exp

/**
 * An in-motion mount slip event detected by [MountSlipDetector].
 */
data class MountSlipEvent(
    val tNs: Long,
    val slipAngleDeg: Double,
    val dwellDurationS: Double,
    val relevelCount: Int,
    val previousMounting: Quaternion,
    val updatedMounting: Quaternion,
    val apparentGravityDevice: Vector3,
)

/**
 * Dynamic mount slip detector & causal in-motion re-leveler (Phase 2).
 *
 * Detects when a phone shifts or tilts in its dashboard/windshield cradle while driving.
 * Re-levels the mounting orientation and filter attitude causally without requiring the vehicle
 * to stop, preventing gravity leakage from corrupting dead reckoning.
 */
class MountSlipDetector(val config: FusionConfig = FusionConfig()) {

    private var lastSampleTNs: Long? = null
    private var filteredAccelDevice: Vector3? = null
    private var slipCandidateSinceNs: Long? = null
    private var relevelCount: Int = 0
    private var lastRelevelTNs: Long? = null

    /** Current count of confirmed in-motion re-levelings. */
    val currentRelevelCount: Int get() = relevelCount

    /**
     * Offer an inertial sample to the slip detector.
     *
     * @return [MountSlipEvent] if a persistent mount slip was confirmed and re-leveled, null otherwise.
     */
    fun offer(
        tNs: Long,
        accelDeviceM_S2: Vector3,
        gyroDeviceRad_S: Vector3,
        currentMounting: Quaternion?,
    ): MountSlipEvent? {
        if (!config.mountSlipDetectionEnabled || currentMounting == null) {
            slipCandidateSinceNs = null
            return null
        }

        val prevTNs = lastSampleTNs
        lastSampleTNs = tNs

        if (prevTNs == null || filteredAccelDevice == null) {
            filteredAccelDevice = accelDeviceM_S2
            return null
        }

        val dt = (tNs - prevTNs) / 1e9
        if (dt <= 0.0 || dt > 1.0) {
            filteredAccelDevice = accelDeviceM_S2
            slipCandidateSinceNs = null
            return null
        }

        // Low-pass filter the device acceleration to estimate apparent gravity
        val tau = maxOf(config.gravityFilterTimeConstantS, 0.05)
        val alpha = exp(-dt / tau)
        val prevFilt = filteredAccelDevice!!
        val fFilt = Vector3(
            prevFilt.x * alpha + accelDeviceM_S2.x * (1.0 - alpha),
            prevFilt.y * alpha + accelDeviceM_S2.y * (1.0 - alpha),
            prevFilt.z * alpha + accelDeviceM_S2.z * (1.0 - alpha),
        )
        filteredAccelDevice = fFilt

        // Cooldown period after re-leveling (1.0 s)
        val lastRelevel = lastRelevelTNs
        if (lastRelevel != null && (tNs - lastRelevel) < 1_000_000_000L) {
            slipCandidateSinceNs = null
            return null
        }

        // Validity gates:
        // 1. Force magnitude check: norm must be consistent with gravity
        val forceNorm = fFilt.norm()
        if (forceNorm < MIN_GRAVITY_NORM_M_S2 || forceNorm > MAX_GRAVITY_NORM_M_S2) {
            slipCandidateSinceNs = null
            return null
        }

        // 2. Gyro rate check: reject turns and high rotation
        val gyroRate = gyroDeviceRad_S.norm()
        if (gyroRate > config.nhcMaxYawRateRad_S) {
            slipCandidateSinceNs = null
            return null
        }

        // Compute expected device UP from current mounting:
        // currentMounting.rotate(expectedUpDev) = VEHICLE_UP
        // -> expectedUpDev = currentMounting.conjugate().rotate(VEHICLE_UP)
        val expectedUpDev = currentMounting.conjugate().rotate(VEHICLE_UP).normalizedOrNull()
            ?: return null

        // Apparent gravity in device frame:
        val measuredUpDev = fFilt.normalizedOrNull() ?: return null

        val angleDevRad = angleBetweenVectors(expectedUpDev, measuredUpDev)
        val angleDevDeg = Math.toDegrees(angleDevRad)

        if (angleDevDeg >= config.mountSlipAngleThresholdDeg) {
            val candidateSince = slipCandidateSinceNs
            if (candidateSince == null) {
                slipCandidateSinceNs = tNs
                return null
            }

            val dwellS = (tNs - candidateSince) / 1e9
            if (dwellS >= config.mountSlipDwellS) {
                // Persistent mount slip confirmed!
                relevelCount++
                lastRelevelTNs = tNs
                slipCandidateSinceNs = null

                val updatedMounting = computeReleveledMounting(currentMounting, measuredUpDev)
                return MountSlipEvent(
                    tNs = tNs,
                    slipAngleDeg = angleDevDeg,
                    dwellDurationS = dwellS,
                    relevelCount = relevelCount,
                    previousMounting = currentMounting,
                    updatedMounting = updatedMounting,
                    apparentGravityDevice = measuredUpDev,
                )
            }
        } else {
            slipCandidateSinceNs = null
        }

        return null
    }

    /**
     * Resets internal filter state and timers.
     */
    fun reset() {
        lastSampleTNs = null
        filteredAccelDevice = null
        slipCandidateSinceNs = null
        relevelCount = 0
        lastRelevelTNs = null
    }

    companion object {
        val VEHICLE_UP = Vector3(0.0, 0.0, 1.0)
        private const val MIN_GRAVITY_NORM_M_S2 = 7.5
        private const val MAX_GRAVITY_NORM_M_S2 = 12.5

        /**
         * Computes a re-leveled mounting quaternion given an existing mounting and the newly
         * measured device UP vector (from apparent gravity).
         *
         * Preserves the vehicle yaw orientation while re-aligning vehicle UP with the new gravity vector.
         */
        fun computeReleveledMounting(oldMounting: Quaternion, newUpDevice: Vector3): Quaternion {
            val uOldDev = oldMounting.conjugate().rotate(VEHICLE_UP).normalizedOrNull()
                ?: return oldMounting
            val uNewDev = newUpDevice.normalizedOrNull() ?: return oldMounting
            val oldTilt = rotationFromTo(uOldDev, VEHICLE_UP)
            val rawRz = oldMounting * oldTilt.conjugate()
            val yawRad = 2.0 * atan2(rawRz.z, rawRz.w)
            val rz = quaternionAboutZ(yawRad)
            val newTilt = rotationFromTo(uNewDev, VEHICLE_UP)
            return (rz * newTilt).normalized().canonical()
        }
    }
}
