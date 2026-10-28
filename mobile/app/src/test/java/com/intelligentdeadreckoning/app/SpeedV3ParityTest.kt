package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.fusion.OutageSpeedTracker
import com.intelligentdeadreckoning.app.fusion.SpeedCorrectionModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Kotlin must reproduce the Python v3 model and feature definitions (tools/make_speed_v3_golden.py). */
class SpeedV3ParityTest {
    private val asset = File("src/main/assets/models/speed_estimator_v3_weights.json")
    private val golden = JsonParser.parseString(File("src/test/resources/speed_v3_golden.json").readText()).asJsonObject

    private fun model() = SpeedCorrectionModel.fromJson(asset.readText())

    private fun doubles(array: com.google.gson.JsonArray) = array.map { it.asDouble }.toDoubleArray()

    @Test
    fun residualMatchesPythonOnGoldenFeatureVectors() {
        val m = model()
        for (entry in golden.getAsJsonArray("model")) {
            val o = entry.asJsonObject
            assertEquals(o.get("residual").asDouble, m.residual(doubles(o.getAsJsonArray("features"))), 1e-9)
        }
    }

    @Test
    fun trackerFeaturesAndEstimateMatchPython() {
        val tr = golden.getAsJsonObject("tracker")
        val tNs = tr.getAsJsonArray("t_ns").map { it.asLong }
        val aLong = doubles(tr.getAsJsonArray("a_long"))
        val aLat = doubles(tr.getAsJsonArray("a_lat"))
        val yaw = doubles(tr.getAsJsonArray("yaw"))
        val anchorIndex = tr.get("anchor_index").asInt
        val queries = tr.getAsJsonArray("queries").map { it.asJsonObject }.associateBy { it.get("index").asInt }
        val tracker = OutageSpeedTracker(model())
        assertFalse(tracker.hasAnchor())
        var checked = 0
        for (i in tNs.indices) {
            tracker.accept(tNs[i], aLong[i], aLat[i], yaw[i])
            if (i == anchorIndex) tracker.onGnssFix(tNs[i], tr.get("anchor_speed").asDouble)
            val q = queries[i] ?: continue
            val features = tracker.features()
            assertNotNull(features)
            assertArrayEquals("features at $i", doubles(q.getAsJsonArray("features")), features!!, 1e-9)
            val est = tracker.estimate()!!
            assertEquals(q.get("speed").asDouble, est.speedM_S, 1e-9)
            assertEquals(q.get("integrated").asDouble, est.integratedSpeedM_S, 1e-9)
            checked++
        }
        assertEquals(queries.size, checked)
    }

    @Test
    fun noEstimateBeforeAnAnchorOrBeforeTheMinimumOutage() {
        val tracker = OutageSpeedTracker(model())
        tracker.accept(1_000_000_000L, 0.0, 0.0, 0.0)
        assertNull(tracker.estimate())
        tracker.onGnssFix(1_000_000_000L, 12.0)
        for (k in 1..30) tracker.accept(1_000_000_000L + k * 100_000_000L, 0.0, 0.0, 0.0)
        assertNull(tracker.estimate(minOutageS = 5.0))
        assertNotNull(tracker.estimate(minOutageS = 2.0))
    }

    @Test
    fun invalidFixSpeedDropsTheAnchor() {
        val tracker = OutageSpeedTracker(model())
        tracker.accept(1_000_000_000L, 0.0, 0.0, 0.0)
        tracker.onGnssFix(1_000_000_000L, 12.0)
        assertTrue(tracker.hasAnchor())
        tracker.onGnssFix(1_100_000_000L, null)
        assertFalse(tracker.hasAnchor())
    }

    @Test
    fun appAssetIsTheExportedWeightsFile() {
        val exported = File("../../models/speed_estimator_v3_weights.json").readBytes()
        assertEquals(sha(exported), sha(asset.readBytes()))
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
