package com.intelligentdeadreckoning.app.fusion

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.expm1
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * Learned GNSS trust: predicts the real horizontal error of a phone fix, in metres, from the fix's metadata and its
 * consistency with the previous fix. Training, feature definitions and the leave-one-drive-out evaluation are in
 * `training/gnss_trust.py`; this file must reproduce the same features and output, which `GnssTrustParityTest` checks
 * against golden vectors from the Python code.
 *
 * Feature order: accuracy_m, log_accuracy, satellites, speed_m_s, dt_s, speed_consistency_m_s,
 * course_consistency_deg, moving.
 */
class GnssTrustModel(
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val w1: Array<DoubleArray>,
    private val b1: DoubleArray,
    private val w2: Array<DoubleArray>,
    private val b2: DoubleArray,
    private val w3: Array<DoubleArray>,
    private val b3: DoubleArray,
    private val yScale: Double,
    private val yMean: Double,
) {
    init {
        require(mean.size == FEATURE_COUNT && std.size == FEATURE_COUNT) { "expected $FEATURE_COUNT features" }
    }

    /** Predicted horizontal error of the fix, metres, never negative. */
    fun predictErrorM(features: DoubleArray): Double {
        require(features.size == FEATURE_COUNT) { "expected $FEATURE_COUNT features, got ${features.size}" }
        val z0 = DoubleArray(FEATURE_COUNT) { (features[it] - mean[it]) / std[it] }
        val z1 = layer(w1, b1, z0)
        val z2 = layer(w2, b2, z1)
        var out = b3[0]
        for (j in z2.indices) out += w3[0][j] * z2[j]
        return max(expm1(out * yScale + yMean), 0.0)
    }

    private fun layer(w: Array<DoubleArray>, b: DoubleArray, x: DoubleArray): DoubleArray =
        DoubleArray(w.size) { i ->
            var s = b[i]
            for (j in x.indices) s += w[i][j] * x[j]
            Math.tanh(s)
        }

    companion object {
        const val FEATURE_COUNT = 8
        const val RESOURCE = "gnss_trust_weights.json"
        val FEATURE_NAMES = listOf(
            "accuracy_m", "log_accuracy", "satellites", "speed_m_s", "dt_s", "speed_consistency_m_s",
            "course_consistency_deg", "moving",
        )

        fun fromJson(text: String): GnssTrustModel {
            val o: JsonObject = JsonParser.parseString(text).asJsonObject
            val names = o.getAsJsonArray("features").map { it.asString }
            require(names == FEATURE_NAMES) { "feature list $names does not match $FEATURE_NAMES" }
            fun vec(k: String) = o.getAsJsonArray(k).map { it.asDouble }.toDoubleArray()
            fun mat(k: String) = o.getAsJsonArray(k).map { row -> row.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray()
            return GnssTrustModel(
                vec("mean"), vec("std"), mat("w1"), vec("b1"), mat("w2"), vec("b2"), mat("w3"), vec("b3"),
                o.get("y_scale").asDouble, o.get("y_mean").asDouble,
            )
        }

        fun fromStream(stream: InputStream): GnssTrustModel =
            fromJson(InputStreamReader(stream, Charsets.UTF_8).use { it.readText() })

        fun fromResource(
            classLoader: ClassLoader = GnssTrustModel::class.java.classLoader!!,
        ): GnssTrustModel {
            val stream = classLoader.getResourceAsStream(RESOURCE)
                ?: classLoader.getResourceAsStream("models/$RESOURCE")
                ?: error("$RESOURCE not found on classpath")
            return fromStream(stream)
        }
    }
}

/**
 * Builds the model's features fix by fix. It must see every GNSS fix in order (the consistency features compare a fix
 * with the one before it, whether or not the filter used that one), so the engine calls [observe] before any gating.
 */
class GnssTrustTracker(private val model: GnssTrustModel) {
    private var prevTNs: Long? = null
    private var prevLat = 0.0
    private var prevLon = 0.0

    fun reset() {
        prevTNs = null
    }

    /** The feature vector for this fix, or null when it cannot be formed (first fix, missing fields, gap). */
    fun features(tNs: Long, fix: GnssMeasurement): DoubleArray? {
        val pt = prevTNs ?: return null
        val acc = fix.horizontal_accuracy_m ?: return null
        val spd = fix.speed_m_s ?: return null
        val crs = fix.bearing_deg ?: return null
        val dt = (tNs - pt) / 1e9
        if (!(dt > 0.0 && dt <= MAX_GAP_S)) return null
        val dN = Math.toRadians(fix.latitude_deg - prevLat) * EARTH_R
        val dE = Math.toRadians(fix.longitude_deg - prevLon) * EARTH_R * cos(Math.toRadians(prevLat))
        val dist = hypot(dN, dE)
        val moving = dist / dt > 1.0
        val bearing = (Math.toDegrees(atan2(dE, dN)) + 360.0) % 360.0
        val dCourse = if (moving) abs((crs - bearing + 540.0) % 360.0 - 180.0) else 0.0
        return doubleArrayOf(
            acc, ln(max(acc, 0.1)), (fix.satellites_used ?: 0L).toDouble(), spd, dt,
            abs(spd - dist / dt), dCourse, if (moving) 1.0 else 0.0,
        )
    }

    /** Predicted horizontal error of [fix] in metres, or null; always records the fix as the new previous one. */
    fun observe(tNs: Long, fix: GnssMeasurement): Double? {
        val f = features(tNs, fix)
        prevTNs = tNs
        prevLat = fix.latitude_deg
        prevLon = fix.longitude_deg
        return f?.let { model.predictErrorM(it) }
    }

    private companion object {
        const val EARTH_R = 6_371_000.0
        const val MAX_GAP_S = 15.0
    }
}
