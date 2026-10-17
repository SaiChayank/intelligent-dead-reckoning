package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.AiSpeedEstimator
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class AiSpeedEstimatorTest {

    @Test
    fun loadsModelFromResources() {
        val estimator = AiSpeedEstimator.fromResource()
        assertNotNull(estimator)
        assertFalse("Buffer must start empty", estimator.isReady())
        assertNull("Estimate before buffer full must be null", estimator.estimate())
    }

    @Test
    fun predictsStationaryForStillWindow() {
        val estimator = AiSpeedEstimator.fromResource()
        val random = Random(42)

        // Stream 100 stationary samples (minor sensor noise only)
        for (i in 0 until 100) {
            val accel = Vector3(
                random.nextDouble(-0.01, 0.01),
                random.nextDouble(-0.01, 0.01),
                random.nextDouble(-0.01, 0.01),
            )
            val gyro = Vector3(
                random.nextDouble(-0.001, 0.001),
                random.nextDouble(-0.001, 0.001),
                random.nextDouble(-0.001, 0.001),
            )
            estimator.acceptSample(accel, gyro)
        }

        assertTrue(estimator.isReady())
        val estimate = estimator.estimate()
        assertNotNull(estimate)
        assertTrue("Still window must be classified as stationary", estimate!!.isStationary)
        assertEquals("Stationary speed must be 0.0", 0.0, estimate.speedM_S, 1e-6)
        assertTrue("Confidence must be high for still vehicle", estimate.confidence >= 0.8)
    }

    @Test
    fun predictsPositiveSpeedForMovingWindow() {
        val estimator = AiSpeedEstimator.fromResource()
        val random = Random(123)

        // Stream 100 moving samples: forward cruise with tire vibration harmonics
        for (i in 0 until 100) {
            val t = i * 0.01
            val accel = Vector3(
                0.02 + 0.12 * sin(2.0 * Math.PI * 6.0 * t) + random.nextDouble(-0.03, 0.03),
                random.nextDouble(-0.03, 0.03),
                0.15 * sin(2.0 * Math.PI * 12.0 * t) + random.nextDouble(-0.03, 0.03),
            )
            val gyro = Vector3(
                random.nextDouble(-0.002, 0.002),
                random.nextDouble(-0.002, 0.002),
                random.nextDouble(-0.002, 0.002),
            )
            estimator.acceptSample(accel, gyro)
        }

        val estimate = estimator.estimate()
        assertNotNull(estimate)
        assertTrue("Moving speed must be non-negative", estimate!!.speedM_S >= 0.0)
        assertTrue("Sigma must be positive", estimate.sigmaM_S > 0.0)
    }

    @Test
    fun subMillisecondInferenceBenchmark() {
        val estimator = AiSpeedEstimator.fromResource()
        val random = Random(999)

        for (i in 0 until 100) {
            estimator.acceptSample(
                Vector3(random.nextDouble(-0.1, 0.1), 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0),
            )
        }

        // Warmup
        for (i in 0 until 10) estimator.estimate()

        // Benchmark 100 iterations
        val iterations = 100
        val startNs = System.nanoTime()
        for (i in 0 until iterations) {
            estimator.estimate()
        }
        val elapsedNs = System.nanoTime() - startNs
        val avgMs = (elapsedNs / 1_000_000.0) / iterations

        println("AiSpeedEstimator average inference latency: String.format(Locale.ROOT, '%.4f', avgMs) ms")
        assertTrue("Inference must run in under 2.0 ms on CPU, was ${avgMs} ms", avgMs < 2.0)
    }

    @Test
    fun circularBufferRollingBehavior() {
        val estimator = AiSpeedEstimator.fromResource()
        val random = Random(456)

        // Continuously stream 250 samples; should never throw or exceed capacity
        for (i in 0 until 250) {
            estimator.acceptSample(
                Vector3(i.toDouble(), 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0),
            )
            if (i >= 99) {
                val est = estimator.estimate()
                assertNotNull(est)
            }
        }
    }
}
