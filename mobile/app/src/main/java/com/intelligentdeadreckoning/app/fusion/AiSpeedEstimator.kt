package com.intelligentdeadreckoning.app.fusion

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Output of Model A: forward vehicle speed, uncertainty, and stillness verdict.
 */
data class SpeedEstimate(
    val speedM_S: Double,
    val sigmaM_S: Double,
    val isStationary: Boolean,
    val confidence: Double,
)

/**
 * Parsed neural network parameters for Model A (Speed & Vibration Estimator).
 */
class ModelAWeights(
    val conv1Weight: Array<Array<FloatArray>>, // [16][6][5]
    val conv1Bias: FloatArray,                 // [16]
    val conv2Weight: Array<Array<FloatArray>>, // [32][16][5]
    val conv2Bias: FloatArray,                 // [32]
    val conv3Weight: Array<Array<FloatArray>>, // [32][32][3]
    val conv3Bias: FloatArray,                 // [32]
    val fc1Weight: Array<FloatArray>,          // [48][74]
    val fc1Bias: FloatArray,                   // [48]
    val fc2Weight: Array<FloatArray>,          // [24][48]
    val fc2Bias: FloatArray,                   // [24]
    val headSpeedWeight: FloatArray,           // [24]
    val headSpeedBias: Float,
    val headSigmaWeight: FloatArray,           // [24]
    val headSigmaBias: Float,
    val headStopWeight: FloatArray,            // [24]
    val headStopBias: Float,
) {
    /**
     * Executes causal inference on an ordered 100x6 window of IMU channels in vehicle frame:
     * [0:ax, 1:ay, 2:az, 3:gx, 4:gy, 5:gz].
     */
    fun predict(window: Array<FloatArray>): SpeedEstimate {
        val windowLen = window.size
        require(windowLen == 100) { "Model A requires exactly 100 samples (1.0 s at 100 Hz), got $windowLen" }

        // 1. Compute statistical features (10 dimensions)
        val stats = FloatArray(10)
        var sumAx = 0.0; var sumAy = 0.0; var sumAz = 0.0
        var sumGz = 0.0; var sumNormA = 0.0; var sumNormG = 0.0

        for (i in 0 until 100) {
            val r = window[i]
            val ax = r[0].toDouble(); val ay = r[1].toDouble(); val az = r[2].toDouble()
            val gx = r[3].toDouble(); val gy = r[4].toDouble(); val gz = r[5].toDouble()
            sumAx += ax; sumAy += ay; sumAz += az
            sumGz += gz
            sumNormA += sqrt(ax * ax + ay * ay + az * az)
            sumNormG += sqrt(gx * gx + gy * gy + gz * gz)
        }
        val meanAx = (sumAx / 100.0).toFloat()
        val meanAy = (sumAy / 100.0).toFloat()
        val meanAz = (sumAz / 100.0).toFloat()
        val meanGz = (sumGz / 100.0).toFloat()

        var varAx = 0.0; var varAy = 0.0; var varAz = 0.0; var varGz = 0.0
        var sumJerk2 = 0.0

        for (i in 0 until 100) {
            val r = window[i]
            val dAx = r[0] - meanAx; val dAy = r[1] - meanAy; val dAz = r[2] - meanAz
            val dGz = r[5] - meanGz
            varAx += dAx * dAx; varAy += dAy * dAy; varAz += dAz * dAz
            varGz += dGz * dGz

            if (i > 0) {
                val jerk = (r[0] - window[i - 1][0]) / 0.01f
                sumJerk2 += jerk * jerk
            }
        }

        stats[0] = meanAx
        stats[1] = meanAy
        stats[2] = meanAz
        stats[3] = sqrt(varAx / 100.0).toFloat()
        stats[4] = sqrt(varAy / 100.0).toFloat()
        stats[5] = sqrt(varAz / 100.0).toFloat()
        stats[6] = sqrt(varGz / 100.0).toFloat()
        stats[7] = sqrt(sumJerk2 / 99.0).toFloat()
        stats[8] = (sumNormA / 100.0).toFloat()
        stats[9] = (sumNormG / 100.0).toFloat()

        // 2. Transpose input for 1D convolution: seq[6][100]
        val seq = Array(6) { FloatArray(100) }
        for (c in 0 until 6) {
            for (t in 0 until 100) {
                seq[c][t] = window[t][c]
            }
        }

        // 3. Conv1: (16, 6, 5), padding 2, stride 1 -> (16, 100)
        val h1 = conv1d(seq, conv1Weight, conv1Bias, stride = 1, padding = 2)
        leakyRelu(h1)

        // 4. Conv2: (32, 16, 5), padding 2, stride 2 -> (32, 50)
        val h2 = conv1d(h1, conv2Weight, conv2Bias, stride = 2, padding = 2)
        leakyRelu(h2)

        // 5. Conv3: (32, 32, 3), padding 1, stride 1 -> (32, 50)
        val h3 = conv1d(h2, conv3Weight, conv3Bias, stride = 1, padding = 1)
        leakyRelu(h3)

        // 6. Global pooling: mean pool + max pool across time -> (64)
        val fused = FloatArray(74)
        val numC = 32
        val lOut = h3[0].size // 50
        for (c in 0 until numC) {
            var sum = 0.0f
            var maxV = -Float.MAX_VALUE
            val chan = h3[c]
            for (t in 0 until lOut) {
                val v = chan[t]
                sum += v
                if (v > maxV) maxV = v
            }
            fused[c] = sum / lOut.toFloat()
            fused[numC + c] = maxV
        }
        for (s in 0 until 10) {
            fused[64 + s] = stats[s]
        }

        // 7. Dense FC1: (48, 74)
        val feat1 = FloatArray(48)
        for (i in 0 until 48) {
            var sum = fc1Bias[i]
            val w = fc1Weight[i]
            for (j in 0 until 74) {
                sum += w[j] * fused[j]
            }
            feat1[i] = if (sum > 0f) sum else 0.1f * sum
        }

        // 8. Dense FC2: (24, 48)
        val feat2 = FloatArray(24)
        for (i in 0 until 24) {
            var sum = fc2Bias[i]
            val w = fc2Weight[i]
            for (j in 0 until 48) {
                sum += w[j] * feat1[j]
            }
            feat2[i] = if (sum > 0f) sum else 0.1f * sum
        }

        // 9. Output Heads
        var rawSpeed = headSpeedBias
        var rawSigma = headSigmaBias
        var rawStop = headStopBias
        for (j in 0 until 24) {
            val f = feat2[j]
            rawSpeed += headSpeedWeight[j] * f
            rawSigma += headSigmaWeight[j] * f
            rawStop += headStopWeight[j] * f
        }

        var speed = if (rawSpeed < 20.0f) ln(1.0 + exp(rawSpeed.toDouble())) else rawSpeed.toDouble()
        var sigma = (if (rawSigma < 20.0f) ln(1.0 + exp(rawSigma.toDouble())) else rawSigma.toDouble()) + 0.25
        val pStop = 1.0 / (1.0 + exp(-rawStop.toDouble()))

        val isStationary = (pStop > 0.5) || (speed < 0.35)
        if (isStationary) {
            speed = 0.0
            sigma = 0.05
        }

        val confidence = (1.0 - (sigma - 0.25) / 1.5).coerceIn(0.1, 0.99)
        return SpeedEstimate(speed, sigma, isStationary, confidence)
    }

    private fun conv1d(
        x: Array<FloatArray>,
        w: Array<Array<FloatArray>>,
        b: FloatArray,
        stride: Int,
        padding: Int,
    ): Array<FloatArray> {
        val cIn = x.size
        val lIn = x[0].size
        val cOut = w.size
        val k = w[0][0].size

        val lPadded = lIn + 2 * padding
        val lOut = (lPadded - k) / stride + 1
        val out = Array(cOut) { FloatArray(lOut) }

        for (i in 0 until lOut) {
            val start = i * stride - padding
            for (co in 0 until cOut) {
                var sum = b[co]
                val wCo = w[co]
                for (ci in 0 until cIn) {
                    val wCoCi = wCo[ci]
                    val xCi = x[ci]
                    for (ki in 0 until k) {
                        val pos = start + ki
                        if (pos in 0 until lIn) {
                            sum += xCi[pos] * wCoCi[ki]
                        }
                    }
                }
                out[co][i] = sum
            }
        }
        return out
    }

    private fun leakyRelu(tensor: Array<FloatArray>) {
        for (c in tensor.indices) {
            val row = tensor[c]
            for (i in row.indices) {
                val v = row[i]
                if (v < 0f) row[i] = 0.1f * v
            }
        }
    }

    companion object {
        fun fromJson(jsonStr: String): ModelAWeights {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            return ModelAWeights(
                conv1Weight = parse3d(root.getAsJsonArray("conv1.weight")),
                conv1Bias = parse1d(root.getAsJsonArray("conv1.bias")),
                conv2Weight = parse3d(root.getAsJsonArray("conv2.weight")),
                conv2Bias = parse1d(root.getAsJsonArray("conv2.bias")),
                conv3Weight = parse3d(root.getAsJsonArray("conv3.weight")),
                conv3Bias = parse1d(root.getAsJsonArray("conv3.bias")),
                fc1Weight = parse2d(root.getAsJsonArray("fc1.weight")),
                fc1Bias = parse1d(root.getAsJsonArray("fc1.bias")),
                fc2Weight = parse2d(root.getAsJsonArray("fc2.weight")),
                fc2Bias = parse1d(root.getAsJsonArray("fc2.bias")),
                headSpeedWeight = parse2d(root.getAsJsonArray("head_speed.weight"))[0],
                headSpeedBias = root.getAsJsonArray("head_speed.bias")[0].asFloat,
                headSigmaWeight = parse2d(root.getAsJsonArray("head_sigma.weight"))[0],
                headSigmaBias = root.getAsJsonArray("head_sigma.bias")[0].asFloat,
                headStopWeight = parse2d(root.getAsJsonArray("head_stop.weight"))[0],
                headStopBias = root.getAsJsonArray("head_stop.bias")[0].asFloat,
            )
        }

        private fun parse1d(arr: JsonArray): FloatArray {
            val res = FloatArray(arr.size())
            for (i in 0 until arr.size()) res[i] = arr[i].asFloat
            return res
        }

        private fun parse2d(arr: JsonArray): Array<FloatArray> {
            return Array(arr.size()) { parse1d(arr[it].asJsonArray) }
        }

        private fun parse3d(arr: JsonArray): Array<Array<FloatArray>> {
            return Array(arr.size()) { parse2d(arr[it].asJsonArray) }
        }
    }
}

/**
 * Streaming on-device forward speed estimator managing a 100-sample circular ring buffer.
 */
class AiSpeedEstimator(
    private val weights: ModelAWeights,
    val capacity: Int = 100,
) {
    private val buffer = Array(capacity) { FloatArray(6) }
    private var writeIdx = 0
    private var count = 0

    /**
     * Ingests one calibrated vehicle-frame IMU sample.
     */
    fun acceptSample(accelVehicleM_S2: Vector3, gyroVehicleRad_S: Vector3) {
        val slot = buffer[writeIdx]
        slot[0] = accelVehicleM_S2.x.toFloat()
        slot[1] = accelVehicleM_S2.y.toFloat()
        slot[2] = accelVehicleM_S2.z.toFloat()
        slot[3] = gyroVehicleRad_S.x.toFloat()
        slot[4] = gyroVehicleRad_S.y.toFloat()
        slot[5] = gyroVehicleRad_S.z.toFloat()

        writeIdx = (writeIdx + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Returns true when the sliding window has collected a full second of data (100 samples).
     */
    fun isReady(): Boolean = count >= capacity

    /**
     * Resets the buffer history.
     */
    fun reset() {
        writeIdx = 0
        count = 0
    }

    /**
     * Computes the current forward speed estimate from the sliding window.
     * Returns null if the buffer is not yet filled.
     */
    fun estimate(): SpeedEstimate? {
        if (!isReady()) return null

        // Unroll circular buffer into ordered window [oldest .. newest]
        val ordered = Array(capacity) { FloatArray(6) }
        val start = writeIdx // writeIdx points to the oldest sample
        for (i in 0 until capacity) {
            val src = (start + i) % capacity
            val srcArr = buffer[src]
            val dstArr = ordered[i]
            System.arraycopy(srcArr, 0, dstArr, 0, 6)
        }

        return weights.predict(ordered)
    }

    companion object {
        fun fromStream(stream: InputStream): AiSpeedEstimator {
            val json = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
            return AiSpeedEstimator(ModelAWeights.fromJson(json))
        }

        fun fromResource(classLoader: ClassLoader = AiSpeedEstimator::class.java.classLoader!!): AiSpeedEstimator {
            val stream = classLoader.getResourceAsStream("speed_estimator_weights.json")
                ?: classLoader.getResourceAsStream("models/speed_estimator_weights.json")
                ?: error("speed_estimator_weights.json not found on classpath")
            return fromStream(stream)
        }
    }
}
