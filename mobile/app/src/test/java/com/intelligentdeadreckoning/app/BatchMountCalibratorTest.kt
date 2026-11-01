package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.BatchMountCalibration
import com.intelligentdeadreckoning.app.calibration.BatchMountCalibrator
import com.intelligentdeadreckoning.app.calibration.FallbackCalibrator
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** The batch mount calibration recovers a known mount from simulated driving, and the combiner lets the right calibrator through. */
class BatchMountCalibratorTest {
    private val header = Header("batch-cal", Source.REAL, "1.1.0")
    private val g = 9.80665

    /** R_vehicle_from_device = Rz(yaw) Ry(pitch) Rx(roll), row-major. */
    private fun rotation(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Array<DoubleArray> {
        val y = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
        val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val ry = arrayOf(doubleArrayOf(cos(p), 0.0, sin(p)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(p), 0.0, cos(p)))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(r), -sin(r)), doubleArrayOf(0.0, sin(r), cos(r)))
        return mul(mul(rz, ry), rx)
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }

    private fun matrix(q: Quaternion): Array<DoubleArray> {
        val w = q.w; val x = q.x; val y = q.y; val z = q.z
        return arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
            doubleArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
            doubleArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
        )
    }

    private fun angleDeg(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        // angle of a^T b
        var trace = 0.0
        for (i in 0..2) for (j in 0..2) trace += a[j][i] * b[j][i]
        return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
    }

    private class Drive(val accel: List<BatchMountCalibration.Sample>, val gyro: List<BatchMountCalibration.Sample>, val fixes: List<BatchMountCalibration.Fix>)

    /** 12 s parked, then speed changes and turns; the phone sits at [rTrue] (vehicle <- device) with a gyro bias. */
    private fun drive(rTrue: Array<DoubleArray>, bias: DoubleArray, seconds: Double = 300.0, hz: Double = 50.0, constantSpeed: Boolean = false): Drive {
        val rng = Random(3)
        val accel = ArrayList<BatchMountCalibration.Sample>()
        val gyro = ArrayList<BatchMountCalibration.Sample>()
        val fixes = ArrayList<BatchMountCalibration.Fix>()
        var previousV = 0.0
        val n = (seconds * hz).toInt()
        for (i in 0 until n) {
            val t = i / hz
            val ramp = ((t - 12.0) / 8.0).coerceIn(0.0, 1.0)
            val v = if (constantSpeed) ramp * 15.0 else ramp * (12.0 + 6.0 * sin(2 * PI * (t - 12.0) / 40.0))
            val aLong = if (i == 0) 0.0 else (v - previousV) * hz
            previousV = v
            val yawRate = ramp * 0.25 * sin(2 * PI * (t - 12.0) / 27.0)
            val av = doubleArrayOf(aLong, v * yawRate, g)
            val wv = doubleArrayOf(0.0, 0.0, yawRate)
            // device = R^T vehicle
            fun toDevice(vec: DoubleArray, noise: Double, offset: DoubleArray) = DoubleArray(3) { j ->
                (0..2).sumOf { k -> rTrue[k][j] * vec[k] } + offset[j] + rng.nextGaussian() * noise
            }
            val a = toDevice(av, 0.05, doubleArrayOf(0.0, 0.0, 0.0))
            val w = toDevice(wv, 0.003, bias)
            accel += BatchMountCalibration.Sample(t, a[0], a[1], a[2])
            gyro += BatchMountCalibration.Sample(t, w[0], w[1], w[2])
            // constantSpeed models a speed signal that carries no direction information: every fix reads 15 m/s
            if (i % hz.toInt() == 0) fixes += BatchMountCalibration.Fix(t, if (constantSpeed) 15.0 else v)
        }
        return Drive(accel, gyro, fixes)
    }

    @Test fun anAwkwardlyMountedPhoneIsRecoveredToWithinTwoDegrees() {
        val rTrue = rotation(40.0, 25.0, -15.0)
        val d = drive(rTrue, doubleArrayOf(0.002, -0.001, 0.003))
        val result = BatchMountCalibration.calibrate(d.accel, d.gyro, d.fixes, 120.0)
        assertTrue(result.toString(), result is BatchMountCalibration.Result.Valid)
        result as BatchMountCalibration.Result.Valid
        assertTrue("rotation error ${angleDeg(matrix(result.q), rTrue)} deg", angleDeg(matrix(result.q), rTrue) < 2.0)
        assertTrue(result.rLong > 0.9)
        assertEquals(0.002, result.gyroBias.x, 0.001)
        assertEquals(-0.001, result.gyroBias.y, 0.001)
        assertEquals(0.003, result.gyroBias.z, 0.001)
    }

    @Test fun aPhoneMountedBackwardsIsRecoveredToo() {
        val rTrue = rotation(180.0, -10.0, 5.0)
        val d = drive(rTrue, doubleArrayOf(0.0, 0.0, 0.0))
        val result = BatchMountCalibration.calibrate(d.accel, d.gyro, d.fixes, 120.0) as BatchMountCalibration.Result.Valid
        assertTrue("rotation error ${angleDeg(matrix(result.q), rTrue)} deg", angleDeg(matrix(result.q), rTrue) < 2.0)
    }

    @Test fun aDriveAtConstantSpeedCarriesNoForwardDirectionAndIsNotCalibrated() {
        val d = drive(rotation(10.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), seconds = 200.0, constantSpeed = true)
        assertTrue(BatchMountCalibration.calibrate(d.accel, d.gyro, d.fixes, 120.0) is BatchMountCalibration.Result.Invalid)
    }

    @Test fun tooLittleDataIsInvalidRatherThanGuessed() {
        val d = drive(rotation(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0))
        val result = BatchMountCalibration.calibrate(d.accel.take(20), d.gyro.take(20), d.fixes, 120.0)
        assertTrue(result is BatchMountCalibration.Result.Invalid)
    }

    private fun imu(sensor: Sensor, t: Long, v: Vector3) = Record(header, Event("i$t$sensor", t, t, ImuMeasurement(
        sensor, DeviceFrame.ANDROID_DEVICE, if (sensor == Sensor.GYROSCOPE) ImuUnit.RADIANS_PER_SECOND else ImuUnit.METRES_PER_SECOND_SQUARED, v, SensorAccuracy.HIGH)))

    private fun fix(t: Long, speed: Double) = Record(header, Event("g$t", t, t, GnssMeasurement(17.0, 78.0, null, null, speed, 0.0, 3.0, null, 10, "gps", null)))

    private fun session() = EngineSession(header, "boot", 0L)
    private fun dummyCalibration() = Record(header, Event("c", 0L, 0L, CalibrationResult("none", CalibrationStatus.PENDING, null, null, null, null)))

    private fun feed(engine: NavigationEngine, d: Drive): List<Record> {
        engine.initialize(session(), dummyCalibration(), InitializationMode.EVALUATION)
        val out = ArrayList<Record>()
        val events = ArrayList<Triple<Long, Int, Record>>()
        for (s in d.accel) events += Triple((s.t * 1e9).toLong() + 1_000_000_000L, 0, imu(Sensor.ACCELEROMETER, (s.t * 1e9).toLong() + 1_000_000_000L, Vector3(s.x, s.y, s.z)))
        for (s in d.gyro) events += Triple((s.t * 1e9).toLong() + 1_000_000_000L, 0, imu(Sensor.GYROSCOPE, (s.t * 1e9).toLong() + 1_000_000_000L, Vector3(s.x, s.y, s.z)))
        for (f in d.fixes) events += Triple((f.t * 1e9).toLong() + 1_000_000_000L, 1, fix((f.t * 1e9).toLong() + 1_000_000_000L, f.speedM_S))
        events.sortWith(compareBy({ it.first }, { it.second }))
        for ((_, kind, record) in events) {
            if (kind == 0) engine.acceptImu(record) else engine.acceptGnss(record)
            out += engine.drain()
        }
        return out
    }

    @Test fun theCalibratorPublishesOneValidResultAtTheEndOfTheFirstWindowThatWorks() {
        val rTrue = rotation(-30.0, 12.0, 8.0)
        val results = feed(BatchMountCalibrator(), drive(rTrue, doubleArrayOf(0.001, 0.0, -0.002))).filter { it.event.data is CalibrationResult }
        assertEquals(1, results.size)
        val data = results[0].event.data as CalibrationResult
        assertEquals(CalibrationStatus.VALID, data.status)
        assertEquals("batch-120", data.id)
        assertTrue(angleDeg(matrix(data.q_vehicle_from_device_wxyz!!), rTrue) < 2.0)
        assertNotNull(data.gyro_bias_rad_s)
        assertTrue("released only after the window ended", results[0].event.t_ns >= 1_000_000_000L + 120_000_000_000L)
    }

    @Test fun aDriveThatNeverQualifiesProducesNoCalibrationAndSaysWhy() {
        val calibrator = BatchMountCalibrator()
        val results = feed(calibrator, drive(rotation(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), seconds = 560.0, constantSpeed = true))
        assertTrue(results.none { it.event.data is CalibrationResult })
        assertEquals(3, calibrator.reasons.size)
    }

    private class Scripted(private val onImu: (Record) -> Record?) : NavigationEngine {
        val out = ArrayDeque<Record>()
        override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) = Unit
        override fun acceptImu(measurement: Record) { onImu(measurement)?.let { out.addLast(it) } }
        override fun acceptGnss(measurement: Record) = Unit
        override fun drain(): Sequence<Record> = out.toList().also { out.clear() }.asSequence()
        override fun stop() = Unit
        override fun reset() = out.clear()
    }

    private fun valid(id: String) = Record(header, Event("v$id", 5L, 5L, CalibrationResult(id, CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), null, 0.9)))
    private fun drain(e: NavigationEngine) = e.drain().filter { (it.event.data as? CalibrationResult)?.status == CalibrationStatus.VALID }.map { (it.event.data as CalibrationResult).id }.toList()

    @Test fun theFallbackIsHeldBackWhileThePrimaryHasLockedAndIsUsedWhenItHasNot() {
        val primary = Scripted { valid("primary") }
        val fallback = Scripted { valid("fallback") }
        val both = FallbackCalibrator(primary, fallback)
        both.initialize(session(), dummyCalibration(), InitializationMode.EVALUATION)
        both.acceptImu(imu(Sensor.ACCELEROMETER, 1L, Vector3(0.0, 0.0, g)))
        assertEquals(listOf("primary"), drain(both))

        val silent = FallbackCalibrator(Scripted { null }, Scripted { valid("fallback") })
        silent.initialize(session(), dummyCalibration(), InitializationMode.EVALUATION)
        silent.acceptImu(imu(Sensor.ACCELEROMETER, 1L, Vector3(0.0, 0.0, g)))
        assertEquals(listOf("fallback"), drain(silent))
    }

    @Test fun aLaterPrimaryResultIsDroppedAfterTheFallbackHasPublishedSoTheMountDoesNotChangeUnderTheFilter() {
        var primaryReady = false
        val primary = Scripted { if (primaryReady) valid("primary-late") else null }
        val fallback = Scripted { valid("fallback") }
        val both = FallbackCalibrator(primary, fallback)
        both.initialize(session(), dummyCalibration(), InitializationMode.EVALUATION)
        both.acceptImu(imu(Sensor.ACCELEROMETER, 1L, Vector3(0.0, 0.0, g)))
        assertEquals(listOf("fallback"), drain(both))
        primaryReady = true
        both.acceptImu(imu(Sensor.ACCELEROMETER, 2L, Vector3(0.0, 0.0, g)))
        assertTrue(drain(both).isEmpty())
    }

    @Test fun resetClearsTheBatchCalibratorSoANewSessionStartsFresh() {
        val calibrator = BatchMountCalibrator()
        feed(calibrator, drive(rotation(20.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0)))
        calibrator.reset()
        assertTrue(calibrator.reasons.isEmpty())
        assertNull(calibrator.drain().firstOrNull())
        assertFalse(calibrator.drain().iterator().hasNext())
    }
}
