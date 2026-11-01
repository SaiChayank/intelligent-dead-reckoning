package com.intelligentdeadreckoning.app.calibration

import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mount calibration from the first minutes of a drive, in one batch: which way is up, which way is forward, and what the gyroscope reads at rest.
 *
 * This is the Kotlin form of `tools/gsdc_batch_calibration.py`, written because the in-drive calibrator ([CalibrationNavigationEngine]) reached VALID on
 * 0 of 38 real phone-in-car trips (reports/gsdc_calibrator_probe_development.json) while this method calibrated every one. It uses only what the engine
 * itself receives, the phone IMU and the GNSS speed, and it is released at the end of the window it was estimated from, so nothing earlier can use it.
 *
 * Vehicle frame: x forward, y left, z up. With `u` the unit up vector in device coordinates and (e1, e2) a horizontal basis, the mount is a rotation
 * about u by theta: (vx, vy) = e^{i theta} (a.e1 + i a.e2) for the horizontal specific force a.
 *  - up: the accelerometer mean while GNSS says the vehicle is stopped, else the mean over the window
 *  - theta: the horizontal specific force against the GNSS speed's rate of change (forward) and speed times yaw rate (left)
 *  - gyro bias: the gyroscope mean while stopped, zero when there is no stop
 */
object BatchMountCalibration {
    const val STOPPED_SPEED_M_S = 0.3
    const val MIN_STOPPED_FIXES = 4
    const val MIN_STOPPED_SAMPLES = 50
    const val MOVING_SPEED_M_S = 3.0
    const val MIN_MOVING_SECONDS = 30
    const val MIN_EXCITATION_M_S2 = 0.08
    const val MIN_R_LONG = 0.5

    /** A time-stamped three-axis sample, seconds on any clock shared with the fixes. */
    class Sample(val t: Double, val x: Double, val y: Double, val z: Double)

    /** A GNSS fix reduced to what the method reads. */
    class Fix(val t: Double, val speedM_S: Double)

    sealed interface Result {
        class Valid(
            val q: Quaternion, val gyroBias: Vector3, val rLong: Double, val rLat: Double, val movingSeconds: Int, val thetaRad: Double,
            val stoppedFixes: Int,
        ) : Result

        class Invalid(val reason: String) : Result
    }

    private class Cumulative(samples: List<Sample>) {
        val t = DoubleArray(samples.size) { samples[it].t }
        private val cx = DoubleArray(samples.size + 1)
        private val cy = DoubleArray(samples.size + 1)
        private val cz = DoubleArray(samples.size + 1)

        init {
            for (i in samples.indices) {
                cx[i + 1] = cx[i] + samples[i].x
                cy[i + 1] = cy[i] + samples[i].y
                cz[i + 1] = cz[i] + samples[i].z
            }
        }

        /** First index with t >= value. */
        fun lowerBound(value: Double): Int {
            var lo = 0
            var hi = t.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (t[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Mean of the samples with lo <= t < hi (the Python code's searchsorted semantics), or null when there are none. */
        fun mean(loT: Double, hiT: Double): DoubleArray? {
            val a = lowerBound(loT)
            val b = lowerBound(hiT)
            if (b <= a) return null
            val n = (b - a).toDouble()
            return doubleArrayOf((cx[b] - cx[a]) / n, (cy[b] - cy[a]) / n, (cz[b] - cz[a]) / n)
        }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
    private fun unit(a: DoubleArray): DoubleArray {
        val n = norm(a)
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in a.indices) {
            sab += (a[i] - ma) * (b[i] - mb)
            saa += (a[i] - ma) * (a[i] - ma)
            sbb += (b[i] - mb) * (b[i] - mb)
        }
        return sab / sqrt(saa * sbb)
    }

    /** Samples and fixes up to [windowS] after the start of the drive; [accel] and [gyro] must be sorted by time. */
    fun calibrate(accel: List<Sample>, gyro: List<Sample>, fixes: List<Fix>, windowS: Double): Result {
        val a = accel.filter { it.t <= windowS }
        val g = gyro.filter { it.t <= windowS }
        val f = fixes.filter { it.t <= windowS }
        if (a.size < 100 || g.size < 100 || f.size < 5) return Result.Invalid("${windowS.toInt()} s: too little data")

        val accelCum = Cumulative(a)
        val gyroCum = Cumulative(g)
        val stoppedTimes = f.filter { it.speedM_S < STOPPED_SPEED_M_S }.map { it.t }
        val maskA = BooleanArray(a.size)
        val maskG = BooleanArray(g.size)
        for (ts in stoppedTimes) {
            for (i in accelCum.lowerBound(ts - 0.5) until a.size) { if (a[i].t > ts + 0.5) break; maskA[i] = true }
            for (i in gyroCum.lowerBound(ts - 0.5) until g.size) { if (g[i].t > ts + 0.5) break; maskG[i] = true }
        }
        val nStoppedA = maskA.count { it }
        val nStoppedG = maskG.count { it }
        val upVec: DoubleArray
        val bias: DoubleArray
        if (stoppedTimes.size >= MIN_STOPPED_FIXES && nStoppedA >= MIN_STOPPED_SAMPLES && nStoppedG > 0) {
            upVec = doubleArrayOf(0.0, 0.0, 0.0)
            for (i in a.indices) if (maskA[i]) { upVec[0] += a[i].x; upVec[1] += a[i].y; upVec[2] += a[i].z }
            for (k in 0..2) upVec[k] /= nStoppedA
            bias = doubleArrayOf(0.0, 0.0, 0.0)
            for (i in g.indices) if (maskG[i]) { bias[0] += g[i].x; bias[1] += g[i].y; bias[2] += g[i].z }
            for (k in 0..2) bias[k] /= nStoppedG
        } else {
            upVec = doubleArrayOf(a.sumOf { it.x } / a.size, a.sumOf { it.y } / a.size, a.sumOf { it.z } / a.size)
            bias = doubleArrayOf(0.0, 0.0, 0.0)
        }
        val u = unit(upVec)
        val helper = if (abs(u[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val e1 = unit(cross(helper, u))
        val e2 = cross(u, e1)

        val h1 = ArrayList<Double>()
        val h2 = ArrayList<Double>()
        val aLong = ArrayList<Double>()
        val aLat = ArrayList<Double>()
        for (k in 1 until f.size - 1) {
            val dt2 = f[k + 1].t - f[k - 1].t
            if (!(dt2 > 0.0 && dt2 <= 3.0) || f[k].speedM_S < MOVING_SPEED_M_S) continue
            val acc = accelCum.mean(f[k].t - 0.5, f[k].t + 0.5) ?: continue
            val gyr = gyroCum.mean(f[k].t - 0.5, f[k].t + 0.5) ?: continue
            val wUp = (gyr[0] - bias[0]) * u[0] + (gyr[1] - bias[1]) * u[1] + (gyr[2] - bias[2]) * u[2]
            h1 += dot(acc, e1)
            h2 += dot(acc, e2)
            aLong += (f[k + 1].speedM_S - f[k - 1].speedM_S) / dt2
            aLat += f[k].speedM_S * wUp
        }
        if (h1.size < MIN_MOVING_SECONDS) return Result.Invalid("${windowS.toInt()} s: ${h1.size} moving seconds")
        val longArr = aLong.toDoubleArray()
        val mean = longArr.average()
        val std = sqrt(longArr.sumOf { (it - mean) * (it - mean) } / longArr.size)
        if (std < MIN_EXCITATION_M_S2) return Result.Invalid("${windowS.toInt()} s: longitudinal excitation ${"%.3f".format(java.util.Locale.ROOT, std)} m/s^2")

        val m1 = h1.average()
        val m2 = h2.average()
        val mLong = longArr.average()
        val mLat = aLat.average()
        // theta = angle( sum veh * conj(dev) ), dev = (h1 + i h2) minus means, veh = (aLong + i aLat) minus means
        var re = 0.0
        var im = 0.0
        for (i in h1.indices) {
            val dr = h1[i] - m1
            val di = h2[i] - m2
            val vr = longArr[i] - mLong
            val vi = aLat[i] - mLat
            re += vr * dr + vi * di
            im += vi * dr - vr * di
        }
        val theta = atan2(im, re)
        val rotatedRe = DoubleArray(h1.size)
        val rotatedIm = DoubleArray(h1.size)
        val vehRe = DoubleArray(h1.size)
        val vehIm = DoubleArray(h1.size)
        val c = cos(theta)
        val s = sin(theta)
        for (i in h1.indices) {
            val dr = h1[i] - m1
            val di = h2[i] - m2
            rotatedRe[i] = c * dr - s * di
            rotatedIm[i] = s * dr + c * di
            vehRe[i] = longArr[i] - mLong
            vehIm[i] = aLat[i] - mLat
        }
        val rLong = pearson(rotatedRe, vehRe)
        val rLat = pearson(rotatedIm, vehIm)
        if (!(rLong >= MIN_R_LONG)) return Result.Invalid("${windowS.toInt()} s: longitudinal r=${"%.2f".format(java.util.Locale.ROOT, rLong)}")

        val xv = doubleArrayOf(c * e1[0] - s * e2[0], c * e1[1] - s * e2[1], c * e1[2] - s * e2[2])
        val yv = doubleArrayOf(s * e1[0] + c * e2[0], s * e1[1] + c * e2[1], s * e1[2] + c * e2[2])
        return Result.Valid(
            q = quaternionFromRows(xv, yv, u), gyroBias = Vector3(bias[0], bias[1], bias[2]), rLong = rLong, rLat = rLat,
            movingSeconds = h1.size, thetaRad = theta, stoppedFixes = stoppedTimes.size,
        )
    }

    /** Unit quaternion (w >= 0) of the rotation whose rows are [x], [y], [z]. */
    fun quaternionFromRows(x: DoubleArray, y: DoubleArray, z: DoubleArray): Quaternion {
        val r00 = x[0]; val r01 = x[1]; val r02 = x[2]
        val r10 = y[0]; val r11 = y[1]; val r12 = y[2]
        val r20 = z[0]; val r21 = z[1]; val r22 = z[2]
        val trace = r00 + r11 + r22
        val q: DoubleArray = when {
            trace > 0 -> { val s = sqrt(trace + 1.0) * 2; doubleArrayOf(0.25 * s, (r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s) }
            r00 > r11 && r00 > r22 -> { val s = sqrt(1.0 + r00 - r11 - r22) * 2; doubleArrayOf((r21 - r12) / s, 0.25 * s, (r01 + r10) / s, (r02 + r20) / s) }
            r11 > r22 -> { val s = sqrt(1.0 + r11 - r00 - r22) * 2; doubleArrayOf((r02 - r20) / s, (r01 + r10) / s, 0.25 * s, (r12 + r21) / s) }
            else -> { val s = sqrt(1.0 + r22 - r00 - r11) * 2; doubleArrayOf((r10 - r01) / s, (r02 + r20) / s, (r12 + r21) / s, 0.25 * s) }
        }
        val n = sqrt(q.sumOf { it * it })
        val sign = if (q[0] >= 0) 1.0 else -1.0
        return Quaternion(sign * q[0] / n, sign * q[1] / n, sign * q[2] / n, sign * q[3] / n)
    }
}

/**
 * A [NavigationEngine] that only calibrates: it buffers the first minutes of IMU and GNSS speed and, at the end of each window (120, 240 then 480 s of
 * drive time), tries [BatchMountCalibration]. The first window that succeeds is published once as a VALID [CalibrationResult]; if none does it stays
 * silent. It never navigates, so it plugs in wherever the calibrator of [com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine] goes.
 */
class BatchMountCalibrator(private val windowsS: List<Double> = listOf(120.0, 240.0, 480.0)) : NavigationEngine {
    private var header: Header? = null
    private var originNs: Long? = null
    private var nextWindow = 0
    private var finished = false
    private val accel = ArrayList<BatchMountCalibration.Sample>()
    private val gyro = ArrayList<BatchMountCalibration.Sample>()
    private val fixes = ArrayList<BatchMountCalibration.Fix>()
    private val pending = ArrayDeque<Record>()

    /** Why each window that did not succeed did not, in order. */
    val reasons: List<String> get() = failureReasons
    private val failureReasons = ArrayList<String>()

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
        header = session.header
        clearBuffers()
    }

    private fun clearBuffers() {
        originNs = null
        nextWindow = 0
        finished = false
        accel.clear(); gyro.clear(); fixes.clear(); pending.clear(); failureReasons.clear()
    }

    private fun seconds(tNs: Long): Double = (tNs - (originNs ?: tNs)) / 1e9

    override fun acceptImu(measurement: Record) {
        if (finished) return
        val data = measurement.event.data as? ImuMeasurement ?: return
        if (originNs == null) originNs = measurement.event.t_ns
        val t = seconds(measurement.event.t_ns)
        if (t > windowsS.last()) { advance(measurement.event.t_ns); return }
        val sample = BatchMountCalibration.Sample(t, data.xyz.x, data.xyz.y, data.xyz.z)
        when (data.sensor) {
            Sensor.ACCELEROMETER -> accel += sample
            Sensor.GYROSCOPE -> gyro += sample
            else -> Unit
        }
        advance(measurement.event.t_ns)
    }

    override fun acceptGnss(measurement: Record) {
        if (finished) return
        val data = measurement.event.data as? GnssMeasurement ?: return
        if (originNs == null) return
        val speed = data.speed_m_s
        if (data.provider == "gps" && speed != null && speed.isFinite()) {
            val t = seconds(measurement.event.t_ns)
            if (t <= windowsS.last() && (fixes.isEmpty() || t > fixes.last().t)) fixes += BatchMountCalibration.Fix(t, speed)
        }
        advance(measurement.event.t_ns)
    }

    /** A window is attempted once drive time has passed its end by the half second the last fix's interval needs. */
    private fun advance(tNs: Long) {
        val h = header ?: return
        while (!finished && nextWindow < windowsS.size && seconds(tNs) >= windowsS[nextWindow] + 0.6) {
            val window = windowsS[nextWindow]
            when (val result = BatchMountCalibration.calibrate(accel, gyro, fixes, window)) {
                is BatchMountCalibration.Result.Valid -> {
                    finished = true
                    val id = "batch-${window.toInt()}"
                    pending.addLast(Record(h, Event("cal-$id", tNs, tNs, CalibrationResult(
                        id = id, status = CalibrationStatus.VALID, q_vehicle_from_device_wxyz = result.q, gyro_bias_rad_s = result.gyroBias,
                        accelerometer_bias_m_s2 = null, confidence = null))))
                    accel.clear(); gyro.clear(); fixes.clear()
                }
                is BatchMountCalibration.Result.Invalid -> {
                    failureReasons += result.reason
                    nextWindow += 1
                    if (nextWindow >= windowsS.size) { finished = true; accel.clear(); gyro.clear(); fixes.clear() }
                }
            }
        }
    }

    override fun drain(): Sequence<Record> = pending.toList().also { pending.clear() }.asSequence()
    override fun stop() = Unit
    override fun reset() { header = null; clearBuffers() }
}

/**
 * Runs two calibrators side by side: everything the [primary] emits passes through, and the [fallback]'s VALID result is published only if the primary
 * has not produced one by then. After the fallback has published, a later VALID result from the primary is dropped, because adopting a second,
 * different mount would make the fusion engine restart for no reason.
 */
class FallbackCalibrator(private val primary: NavigationEngine, private val fallback: NavigationEngine) : NavigationEngine {
    private var primaryValid = false
    private var fallbackPublished = false
    private val pending = ArrayDeque<Record>()

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
        primaryValid = false
        fallbackPublished = false
        pending.clear()
        primary.initialize(session, calibration, mode)
        fallback.initialize(session, calibration, mode)
    }

    override fun acceptImu(measurement: Record) {
        primary.acceptImu(measurement)
        fallback.acceptImu(measurement)
        collect()
    }

    override fun acceptGnss(measurement: Record) {
        primary.acceptGnss(measurement)
        fallback.acceptGnss(measurement)
        collect()
    }

    private fun isValidCalibration(record: Record) = (record.event.data as? CalibrationResult)?.status == CalibrationStatus.VALID

    private fun collect() {
        for (record in primary.drain()) {
            if (fallbackPublished && isValidCalibration(record)) continue
            if (isValidCalibration(record)) primaryValid = true
            pending.addLast(record)
        }
        for (record in fallback.drain()) {
            if (primaryValid || fallbackPublished) continue
            fallbackPublished = true
            pending.addLast(record)
        }
    }

    override fun drain(): Sequence<Record> = pending.toList().also { pending.clear() }.asSequence()
    override fun stop() { primary.stop(); fallback.stop(); collect() }
    override fun reset() { primary.reset(); fallback.reset(); primaryValid = false; fallbackPublished = false; pending.clear() }
}
