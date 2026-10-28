package com.intelligentdeadreckoning.app.fusion

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Model A v3: a learned correction to the speed that integrated longitudinal acceleration gives
 * after the last GNSS fix. The training side, feature definitions and evaluation are in
 * `training/speed_v3.py`; this file must produce the same features and the same output, which
 * `SpeedV3ParityTest` checks against golden vectors produced by the Python code.
 *
 * Feature order (see `FEATURES` in the Python module):
 * v0, h, i_long, i_long_per_s, mean_abs_yaw, std_a_long_3s, mean_a_long_3s, std_a_lat_3s, v_dr.
 */
class SpeedCorrectionModel(
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val w1: Array<DoubleArray>,
    private val b1: DoubleArray,
    private val w2: Array<DoubleArray>,
    private val b2: DoubleArray,
    private val w3: Array<DoubleArray>,
    private val b3: DoubleArray,
    private val yScale: Double,
) {
    init {
        require(mean.size == FEATURE_COUNT && std.size == FEATURE_COUNT) { "expected $FEATURE_COUNT features" }
    }

    /** Predicted residual `v_true - v_dr`, metres per second. */
    fun residual(features: DoubleArray): Double {
        require(features.size == FEATURE_COUNT) { "expected $FEATURE_COUNT features, got ${features.size}" }
        val z0 = DoubleArray(FEATURE_COUNT) { (features[it] - mean[it]) / std[it] }
        val z1 = layer(w1, b1, z0)
        val z2 = layer(w2, b2, z1)
        var out = b3[0]
        for (j in z2.indices) out += w3[0][j] * z2[j]
        return out * yScale
    }

    private fun layer(w: Array<DoubleArray>, b: DoubleArray, x: DoubleArray): DoubleArray =
        DoubleArray(w.size) { i ->
            var s = b[i]
            for (j in x.indices) s += w[i][j] * x[j]
            Math.tanh(s)
        }

    companion object {
        const val FEATURE_COUNT = 9
        const val RESOURCE = "speed_estimator_v3_weights.json"

        fun fromJson(text: String): SpeedCorrectionModel {
            val o: JsonObject = JsonParser.parseString(text).asJsonObject
            val names = o.getAsJsonArray("features").map { it.asString }
            require(names == FEATURE_NAMES) { "feature list $names does not match $FEATURE_NAMES" }
            fun vec(k: String) = o.getAsJsonArray(k).map { it.asDouble }.toDoubleArray()
            fun mat(k: String) = o.getAsJsonArray(k).map { row -> row.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray()
            return SpeedCorrectionModel(
                vec("mean"), vec("std"), mat("w1"), vec("b1"), mat("w2"), vec("b2"), mat("w3"), vec("b3"),
                o.get("y_scale").asDouble,
            )
        }

        fun fromStream(stream: InputStream): SpeedCorrectionModel =
            fromJson(InputStreamReader(stream, Charsets.UTF_8).use { it.readText() })

        fun fromResource(
            classLoader: ClassLoader = SpeedCorrectionModel::class.java.classLoader!!,
        ): SpeedCorrectionModel {
            val stream = classLoader.getResourceAsStream(RESOURCE)
                ?: classLoader.getResourceAsStream("models/$RESOURCE")
                ?: error("$RESOURCE not found on classpath")
            return fromStream(stream)
        }

        val FEATURE_NAMES = listOf(
            "v0", "h", "i_long", "i_long_per_s", "mean_abs_yaw", "std_a_long_3s", "mean_a_long_3s",
            "std_a_lat_3s", "v_dr",
        )
    }
}

/** Speed estimate for the time asked about. */
data class OutageSpeedEstimate(val speedM_S: Double, val integratedSpeedM_S: Double, val outageS: Double)

/**
 * Causal bookkeeping for the model: anchors at each accepted GNSS fix and accumulates
 * longitudinal acceleration and yaw from then on. Inputs are vehicle-frame samples (x forward,
 * y left, yaw rate about up, counter-clockwise positive).
 *
 * A fix between two samples anchors to the latest sample at or before it, so the integral can
 * differ from the training code (which interpolates the cumulative integral) by at most one
 * sample interval of acceleration; the parity test aligns fixes and queries to the sample grid.
 */
class OutageSpeedTracker(private val model: SpeedCorrectionModel) {
    private class Sample(val t: Double, val aLong: Double, val aLat: Double)

    private val recent = ArrayDeque<Sample>()
    private var cumLong = 0.0
    private var cumAbsYaw = 0.0
    private var lastT: Double? = null
    private var lastALong = 0.0
    private var lastYaw = 0.0
    private var anchorT: Double? = null
    private var anchorSpeed = 0.0
    private var anchorCumLong = 0.0
    private var anchorCumAbsYaw = 0.0

    fun reset() {
        recent.clear()
        cumLong = 0.0; cumAbsYaw = 0.0; lastT = null
        anchorT = null; anchorSpeed = 0.0
    }

    /** One vehicle-frame sample. Samples must arrive in non-decreasing time order. */
    fun accept(tNs: Long, aLong: Double, aLat: Double, yawRad_S: Double) {
        val t = tNs / NANOS
        val previous = lastT
        if (previous != null) {
            if (t < previous) return
            val dt = t - previous
            cumLong += 0.5 * (aLong + lastALong) * dt
            cumAbsYaw += 0.5 * (abs(yawRad_S) + abs(lastYaw)) * dt
        }
        lastT = t; lastALong = aLong; lastYaw = yawRad_S
        recent.addLast(Sample(t, aLong, aLat))
        while (recent.isNotEmpty() && recent.first().t < t - RECENT_S) recent.removeFirst()
    }

    /** An accepted GNSS fix: the outage clock restarts from this speed. */
    fun onGnssFix(tNs: Long, speedM_S: Double?) {
        if (speedM_S == null || !speedM_S.isFinite() || speedM_S < 0.0) { anchorT = null; return }
        anchorT = tNs / NANOS
        anchorSpeed = speedM_S
        anchorCumLong = cumLong
        anchorCumAbsYaw = cumAbsYaw
    }

    fun hasAnchor(): Boolean = anchorT != null

    /** Feature vector for the latest sample time, or null before an anchor exists. */
    fun features(): DoubleArray? {
        val t0 = anchorT ?: return null
        val t = lastT ?: return null
        val h = max(t - t0, 1e-3)
        val iLong = cumLong - anchorCumLong
        val a = recent.map { it.aLong }
        val b = recent.map { it.aLat }
        return doubleArrayOf(
            anchorSpeed, h, iLong, iLong / h, (cumAbsYaw - anchorCumAbsYaw) / h,
            populationStd(a), if (a.isEmpty()) 0.0 else a.average(), populationStd(b), anchorSpeed + iLong,
        )
    }

    /** Speed now, or null when there is no anchor or the outage is too short to improve on it. */
    fun estimate(minOutageS: Double = 0.0): OutageSpeedEstimate? {
        val f = features() ?: return null
        if (f[1] < minOutageS) return null
        val dr = f[8]
        return OutageSpeedEstimate(max(dr + model.residual(f), 0.0), max(dr, 0.0), f[1])
    }

    private fun populationStd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    private companion object {
        const val NANOS = 1e9
        const val RECENT_S = 3.0
    }
}
