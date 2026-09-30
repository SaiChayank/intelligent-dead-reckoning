package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.StationaryDetector
import com.intelligentdeadreckoning.app.fusion.StationaryVerdict
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The causal stillness detector against synthetic phone-in-a-parked-car samples.
 *
 * The production resting rule this detector implements is the one already declared in
 * `reports/body_frame_conventions.md` (2 s window, 20 samples, 0.25 s gap bound, 0.05 rad/s
 * gyro norm, 9.3–10.3 m/s² gravity band, 0.3 m/s² deviation, 0.1 m/s² window standard
 * deviation), so the thresholds are exercised at their declared values.
 */
class StationaryDetectorTest {

    private val config = FusionConfig()

    /** A parked car idles: small vibration on top of a fixed gravity reaction. */
    private fun parkedAccel(tS: Double): Vector3 = Vector3(
        -0.02 * 9.80665 + 0.02 * Math.sin(2.0 * Math.PI * 11.0 * tS),
        0.01 * Math.sin(2.0 * Math.PI * 13.0 * tS),
        9.80665 * Math.cos(0.02) + 0.02 * Math.cos(2.0 * Math.PI * 11.0 * tS),
    )

    private fun feed(
        detector: StationaryDetector,
        seconds: Double,
        accel: (Double) -> Vector3,
        gyro: (Double) -> Vector3 = { Vector3(0.0, 0.0, 0.0) },
        rateHz: Double = 50.0,
        startS: Double = 0.0,
    ): Double {
        var tS = startS
        val dt = 1.0 / rateHz
        val steps = Math.round(seconds * rateHz).toInt()
        repeat(steps) {
            tS += dt
            detector.offer((tS * 1e9).toLong(), accel(tS), gyro(tS))
        }
        return tS
    }

    @Test
    fun aParkedCarIsRecognizedAfterTheDeclaredWindow() {
        val detector = StationaryDetector(config)
        // One second is below the 2 s window: still not decided.
        feed(detector, 1.0, ::parkedAccel)
        assertEquals(StationaryVerdict.TOO_SOON, detector.verdict)
        assertFalse(detector.stationary)
        // Well past two full seconds of idle vibration: quiet.
        feed(detector, 1.5, ::parkedAccel, startS = 1.0)
        assertEquals(StationaryVerdict.QUIET, detector.verdict)
        assertTrue(detector.stationary)
        assertTrue(detector.stationarySinceNs != null)
    }

    @Test
    fun aPotholeEndsStillnessImmediately() {
        val detector = StationaryDetector(config)
        feed(detector, 2.5, ::parkedAccel)
        assertTrue(detector.stationary)
        // One 0.5 m/s² shock: the window is cleared and the verdict flips at once.
        detector.offer(2_600_000_000L, Vector3(0.0, 0.0, 10.3), Vector3(0.0, 0.0, 0.0))
        assertEquals(StationaryVerdict.VIBRATING, detector.verdict)
        assertFalse(detector.stationary)
        // Recovery requires the full window again, not one quiet sample.
        feed(detector, 1.0, ::parkedAccel, startS = 2.61)
        assertFalse("stillness returned after 1 s", detector.stationary)
        feed(detector, 1.5, ::parkedAccel, startS = 3.61)
        assertTrue(detector.stationary)
    }

    @Test
    fun rotationEndsStillnessImmediately() {
        val detector = StationaryDetector(config)
        feed(detector, 2.5, ::parkedAccel)
        assertTrue(detector.stationary)
        // A hand adjusting the phone: 0.2 rad/s, above the 0.05 rad/s resting limit.
        feed(detector, 0.3, ::parkedAccel, gyro = { Vector3(0.0, 0.2, 0.0) }, startS = 2.51)
        assertEquals(StationaryVerdict.ROTATING, detector.verdict)
        assertFalse(detector.stationary)
    }

    @Test
    fun anAcceleratingCarIsNeverStill() {
        val detector = StationaryDetector(config)
        // Accelerating at up to 1.5 m/s² tilts the specific force by up to ~8.7 degrees and
        // keeps changing, which violates the deviation bound against the running mean.
        feed(
            detector,
            3.0,
            accel = { tS ->
                val tilt = 0.152 * (tS / 3.0).coerceAtMost(1.0)
                Vector3(-Math.sin(tilt) * 9.80665, 0.0, Math.cos(tilt) * 9.80665)
            },
        )
        // The window keeps resetting while the force ramps, so the exact verdict at the end
        // depends on where the last reset landed (VIBRATING or TOO_SOON). The contract that
        // matters: an accelerating car is never reported still.
        assertTrue(
            "accelerating car reported ${detector.verdict}",
            detector.verdict != StationaryVerdict.QUIET,
        )
        assertFalse(detector.stationary)
    }

    @Test
    fun aConstantVelocitySlopeIsQuietAndThatIsWhyTheSpeedGateExists() {
        val detector = StationaryDetector(config)
        // A vehicle at constant speed on a constant slope produces a constant tilted specific
        // force: the same signature as rest. No IMU-only detector can separate them — this is
        // the documented limitation, and it is exactly why the engine's zero-velocity gate
        // additionally requires the filter's own speed below the creep bound and non-
        // contradicting GNSS speed. The detector alone must not be trusted with the decision.
        feed(
            detector,
            3.0,
            accel = { Vector3(-Math.sin(0.152) * 9.80665, 0.0, Math.cos(0.152) * 9.80665) },
        )
        assertEquals(StationaryVerdict.QUIET, detector.verdict)
        assertTrue(detector.stationary)
    }

    @Test
    fun aStreamHoleDiscardsTheWindow() {
        val detector = StationaryDetector(config)
        feed(detector, 2.5, ::parkedAccel)
        assertTrue(detector.stationary)
        // A 0.5 s hole exceeds the 0.25 s bound: the window is discarded, not interpolated.
        // The verdict may read GAP or TOO_SOON depending on ordering, but it must not be quiet.
        detector.offer(3_500_000_000L, parkedAccel(3.5), Vector3(0.0, 0.0, 0.0))
        assertFalse(detector.stationary)
        assertTrue(detector.verdict != StationaryVerdict.QUIET)
        // Fresh samples rebuild the window and stillness returns.
        feed(detector, 2.5, ::parkedAccel, startS = 3.51)
        assertTrue(detector.stationary)
    }

    @Test
    fun gravityOutsideTheBandIsRejected() {
        val detector = StationaryDetector(config)
        // Free fall: magnitude far from the 9.3–10.3 band. Nothing about this is stillness.
        feed(detector, 2.0, accel = { Vector3(0.0, 0.0, 0.0) })
        assertEquals(StationaryVerdict.GRAVITY_OUT_OF_BAND, detector.verdict)
        assertFalse(detector.stationary)
    }

    @Test
    fun aNonFiniteSampleIsRejectedRatherThanPropagated() {
        val detector = StationaryDetector(config)
        feed(detector, 1.0, ::parkedAccel)
        detector.offer(
            1_100_000_000L,
            Vector3(Double.NaN, 0.0, 9.8),
            Vector3(0.0, 0.0, 0.0),
        )
        assertEquals(StationaryVerdict.NON_FINITE, detector.verdict)
        assertFalse(detector.stationary)
    }
}
