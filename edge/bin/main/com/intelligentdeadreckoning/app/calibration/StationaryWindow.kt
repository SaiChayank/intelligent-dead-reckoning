package com.intelligentdeadreckoning.app.calibration

import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One IMU sample pair, already validated as accelerometer plus gyroscope in the Android device
 * frame with the contract's units.
 */
data class ImuPair(
    val tNs: Long,
    val accelM_S2: Vector3,
    val gyroRad_S: Vector3,
)

/**
 * Why a candidate resting period was not accepted as a stationary window.
 *
 * Rejections are reported rather than silently dropped: "the phone was never stable" and "the
 * phone was never still" are different facts about a collection, and a calibration that never
 * becomes valid needs to say which one applied.
 */
enum class StationaryRejection {
    TOO_SHORT,
    NOT_ENOUGH_SAMPLES,
    VIBRATING,
    ROTATING,
    GRAVITY_OUT_OF_BAND,
    ATTITUDE_CHANGED,
}

/**
 * A resting period that passed every stability gate, with the metrics that let a later reader
 * judge how stable it really was.
 *
 * `meanAccelM_S2` is the *specific force* the accelerometer measured, which at rest is the
 * gravity reaction: it points **up**, not down. That sign is the single easiest thing to get
 * wrong in a gravity alignment, so it is named here and used in exactly one place
 * ([MountEstimator]). `upDevice` is the unit vector of that measurement.
 *
 * [accelStdM_S2] and [gyroStdRad_S] are per-axis maxima over the whole window, and they are
 * *descriptions* rather than the gates that admitted it: acceptance is decided per sub-window
 * (see [StationaryAccumulator]), because a period assembled from individually quiet sub-windows
 * can still carry a visibly larger spread than any one of them. They feed the quality score.
 *
 * @param accelStdM_S2 largest per-axis accelerometer standard deviation in the window
 * @param gyroStdRad_S largest per-axis gyroscope standard deviation in the window
 * @param gravityMagnitudeM_S2 magnitude of the mean specific force, which at rest is gravity
 */
data class StationaryWindow(
    val startNs: Long,
    val endNs: Long,
    val samples: Int,
    val meanAccelM_S2: Vector3,
    val meanGyroRad_S: Vector3,
    val accelStdM_S2: Double,
    val gyroStdRad_S: Double,
    val gravityMagnitudeM_S2: Double,
) {
    val durationS: Double get() = (endNs - startNs) / 1e9

    /** The world "up" direction expressed in device coordinates, from the gravity reaction. */
    val upDevice: Vector3? get() = meanAccelM_S2.normalizedOrNull()

    val gravityErrorM_S2: Double get() = abs(gravityMagnitudeM_S2 - GRAVITY_STANDARD_M_S2)
}

/**
 * Thresholds for "this phone is at rest, and stayed at rest long enough to measure gravity".
 *
 * Every value is a documented engineering choice, not a measured constant, and they are
 * injectable so tests can exercise the gates without synthesizing production-scale data.
 *
 * Defaults are chosen against phone-grade MEMS behaviour: an accelerometer resting on a car
 * mount shows per-axis standard deviation well under 0.05 m/s^2, and a gyroscope at rest well
 * under 0.01 rad/s, while engine idle, a passenger touching the phone, or being driven exceeds
 * both by an order of magnitude.
 *
 * @param maxAccelStdM_S2 per-axis accelerometer standard deviation allowed inside a sub-window
 * @param maxGyroStdRad_S per-axis gyroscope standard deviation allowed inside a sub-window
 * @param maxGyroRateRad_S magnitude above which a single sample counts as rotation
 * @param accelBandM_S2 how far a sub-window's mean magnitude may sit from standard gravity
 * @param minDurationS shortest window accepted
 * @param minSamples fewest paired samples accepted
 * @param subWindowS length of the interval that is judged on its own
 * @param maxAttitudeDriftDeg how far a sub-window's mean gravity direction may move from the
 *   direction the window so far has established
 */
data class StationaryThresholds(
    val maxAccelStdM_S2: Double = 0.05,
    val maxGyroStdRad_S: Double = 0.01,
    val maxGyroRateRad_S: Double = 0.15,
    val accelBandM_S2: Double = 0.6,
    val minDurationS: Double = 1.5,
    val minSamples: Int = 60,
    val subWindowS: Double = 0.5,
    val maxAttitudeDriftDeg: Double = 1.0,
)

/**
 * Accumulates consecutive quiet IMU samples into a [StationaryWindow].
 *
 * A resting period is judged in short **sub-windows** (default 0.5 s) rather than over the whole
 * candidate, because the two ways a "resting" period can be a lie look completely different in
 * aggregate:
 *
 * - **The phone is being driven.** Engine and road vibration lift the per-axis standard
 *   deviation far above the resting limit, and accelerating at `a` tilts the specific force by
 *   `atan(a / g)` — about 9 degrees at a mild 1.5 m/s^2. Judging the whole period at once would
 *   blend a genuine parked measurement with driving samples into a window that looks perfectly
 *   stable while its gravity direction is wrong by degrees. Only a per-sub-window test, plus the
 *   hold-attitude test below, separates them.
 * - **The phone is being re-mounted.** Slow rotation raises the per-sample rate above the resting
 *   limit, and a completed re-placement moves the mean direction, which no stability statistic
 *   can see at all.
 *
 * So a sub-window contributes to the window in progress only when it is not vibrating, not
 * rotating, has a plausible gravity magnitude, and points the same way as the window so far. The
 * first sub-window that fails ends the window, which means a drive starting after a parked period
 * closes it within half a second and delivers the parked measurement intact. A window is built
 * only from sub-windows that each passed on their own; the metrics it carries describe how much
 * margin they had.
 *
 * Memory is O(1) in the number of samples: running sums and per-axis sums of squares, no
 * buffering, and no filter — a period is either quiet enough to measure gravity or it is
 * reported as rejected with the reason.
 *
 * [offer] returns a window exactly when a qualifying resting period ends, so a caller can act on
 * each one; [flush] closes a period that is still open.
 */
class StationaryAccumulator(
    private val thresholds: StationaryThresholds = StationaryThresholds(),
) {
    /** The sub-window currently being judged. */
    private val subAccel = Moments()
    private val subGyro = Moments()
    private var subStartNs = 0L
    private var subEndNs = 0L
    private var subPeakRate = 0.0

    /** Sub-windows that passed, and therefore make up the window being built. */
    private val windowAccel = Moments()
    private val windowGyro = Moments()
    private var startNs = 0L
    private var endNs = 0L

    /**
     * Why the most recent candidate ended or was refused, in order; for diagnostics only.
     *
     * A period can both deliver a window and carry a reason (a drive that begins after a parked
     * stretch closes a perfectly good window), so this is deliberately not a verdict.
     */
    var lastRejections: List<StationaryRejection> = emptyList()
        private set

    private val subDurationS: Double get() = (subEndNs - subStartNs) / 1e9

    /**
     * Feed one paired sample.
     *
     * @return the window when this sample ended a qualifying resting period, otherwise null.
     */
    fun offer(pair: ImuPair): StationaryWindow? {
        if (!pair.accelM_S2.isFiniteVector() || !pair.gyroRad_S.isFiniteVector()) {
            return endWindow(null)
        }
        if (subAccel.samples == 0) subStartNs = pair.tNs
        subEndNs = pair.tNs
        subAccel.add(pair.accelM_S2)
        subGyro.add(pair.gyroRad_S)
        subPeakRate = max(subPeakRate, pair.gyroRad_S.norm())
        if (subDurationS < thresholds.subWindowS) return null

        val rejection = subWindowRejection()
        if (rejection != null) return endWindow(rejection)
        commitSubWindow()
        return null
    }

    /**
     * Close an open resting period.
     *
     * A period that is still open when the session ends contributes its last, partial sub-window
     * when that part was itself quiet, so a short recording does not lose the tail it measured.
     */
    fun flush(): StationaryWindow? {
        val partial = if (subAccel.samples > 0) subWindowRejection() else null
        // "Too short to judge" is not a reason to refuse the samples; it is a reason to ignore
        // the question. Anything else means the phone was not at rest, and ends the period.
        val blocking = partial?.takeIf { it != StationaryRejection.NOT_ENOUGH_SAMPLES }
        if (blocking == null) commitSubWindow()
        return endWindow(blocking)
    }

    /** The first way this sub-window fails to be a resting measurement, or null if it is one. */
    private fun subWindowRejection(): StationaryRejection? {
        if (subAccel.samples < 2) return StationaryRejection.NOT_ENOUGH_SAMPLES
        if (subAccel.std > thresholds.maxAccelStdM_S2) return StationaryRejection.VIBRATING
        if (subGyro.std > thresholds.maxGyroStdRad_S) return StationaryRejection.ROTATING
        if (subPeakRate > thresholds.maxGyroRateRad_S) return StationaryRejection.ROTATING
        val direction = subAccel.mean.normalizedOrNull() ?: return StationaryRejection.GRAVITY_OUT_OF_BAND
        if (abs(subAccel.mean.norm() - GRAVITY_STANDARD_M_S2) > thresholds.accelBandM_S2) {
            return StationaryRejection.GRAVITY_OUT_OF_BAND
        }
        // Holding the attitude is what a whole-window statistic cannot check: a driven phone is
        // as steady as a parked one but its gravity direction has moved by degrees.
        val established = windowAccel.mean.normalizedOrNull() ?: return null
        if (Math.toDegrees(angleBetweenVectors(established, direction)) >
            thresholds.maxAttitudeDriftDeg
        ) {
            return StationaryRejection.ATTITUDE_CHANGED
        }
        return null
    }

    /** Promote the current sub-window's samples into the window being built. */
    private fun commitSubWindow() {
        if (subAccel.samples == 0) return
        if (windowAccel.samples == 0) startNs = subStartNs
        windowAccel.merge(subAccel)
        windowGyro.merge(subGyro)
        endNs = subEndNs
        clearSubWindow()
    }

    /**
     * End the candidate window, delivering it when the samples committed to it qualify.
     *
     * [endReason] describes the sub-window that ended the period. It never overrides the window's
     * own verdict: a period that is quiet and steady and then stops being quiet has still
     * measured gravity, and refusing it would throw away the only usable evidence in the session.
     */
    private fun endWindow(endReason: StationaryRejection?): StationaryWindow? {
        val rejected = windowRejections()
        lastRejections = rejected.ifEmpty { endReason?.let { listOf(it) } ?: emptyList() }
        val window = if (rejected.isEmpty()) build() else null
        clearSubWindow()
        clearWindow()
        return window
    }

    /** Gate the committed samples as a whole; these are the judgements no sub-window can make. */
    private fun windowRejections(): List<StationaryRejection> = buildList {
        if (windowAccel.samples == 0) return@buildList
        if ((endNs - startNs) / 1e9 < thresholds.minDurationS) add(StationaryRejection.TOO_SHORT)
        if (windowAccel.samples < thresholds.minSamples) {
            add(StationaryRejection.NOT_ENOUGH_SAMPLES)
        }
        if (abs(windowAccel.mean.norm() - GRAVITY_STANDARD_M_S2) > thresholds.accelBandM_S2) {
            add(StationaryRejection.GRAVITY_OUT_OF_BAND)
        }
    }

    private fun build(): StationaryWindow? {
        if (windowAccel.samples == 0) return null
        val meanAccel = windowAccel.mean
        val meanGyro = windowGyro.mean
        return StationaryWindow(
            startNs = startNs,
            endNs = endNs,
            samples = windowAccel.samples,
            meanAccelM_S2 = meanAccel,
            meanGyroRad_S = meanGyro,
            accelStdM_S2 = windowAccel.std,
            gyroStdRad_S = windowGyro.std,
            gravityMagnitudeM_S2 = meanAccel.norm(),
        )
    }

    private fun clearSubWindow() {
        subAccel.clear()
        subGyro.clear()
        subStartNs = 0L
        subEndNs = 0L
        subPeakRate = 0.0
    }

    private fun clearWindow() {
        windowAccel.clear()
        windowGyro.clear()
        startNs = 0L
        endNs = 0L
    }

    /**
     * Running per-axis sums and sums of squares, so the mean and the per-axis standard deviation
     * cost constant memory however long a period runs.
     */
    private class Moments {
        var samples = 0
            private set

        private var sum = Vector3(0.0, 0.0, 0.0)
        private var sumXX = 0.0
        private var sumYY = 0.0
        private var sumZZ = 0.0

        val mean: Vector3 get() = if (samples == 0) Vector3(0.0, 0.0, 0.0) else sum * (1.0 / samples)

        /**
         * Largest per-axis standard deviation.
         *
         * The largest axis is reported rather than an average, because a phone rocking about one
         * axis is not less at rest than one rocking about all three. The bias correction is
         * omitted: the threshold is an engineering choice for phone-grade MEMS, and the
         * correction is under 2% at the shortest sub-window this class accepts.
         */
        val std: Double
            get() {
                if (samples < 2) return 0.0
                val m = mean
                val x = max(sumXX / samples - m.x * m.x, 0.0)
                val y = max(sumYY / samples - m.y * m.y, 0.0)
                val z = max(sumZZ / samples - m.z * m.z, 0.0)
                return sqrt(max(x, max(y, z)))
            }

        fun add(v: Vector3) {
            samples += 1
            sum = sum + v
            sumXX += v.x * v.x
            sumYY += v.y * v.y
            sumZZ += v.z * v.z
        }

        fun merge(other: Moments) {
            if (other.samples == 0) return
            samples += other.samples
            sum = sum + other.sum
            sumXX += other.sumXX
            sumYY += other.sumYY
            sumZZ += other.sumZZ
        }

        fun clear() {
            samples = 0
            sum = Vector3(0.0, 0.0, 0.0)
            sumXX = 0.0
            sumYY = 0.0
            sumZZ = 0.0
        }
    }
}

private fun Vector3.isFiniteVector(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
