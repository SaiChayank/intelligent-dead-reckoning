package com.intelligentdeadreckoning.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Tests Kotlin-native values against the same wire fixture as Python. No Android APIs. */
class ContractFixtureTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private fun wire() = javaClass.getResourceAsStream("/golden.json")!!.bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }

    @Test fun kotlinNativeFixtureMatchesEveryWireValueIncludingNulls() {
        assertEquals(wire(), JsonParser.parseString(gson.toJson(expected)))
    }

    @Test fun nanosecondsSurviveLongConversionBeyondDoubleIntegerPrecision() {
        val events = wire().getAsJsonArray("events")
        val first = events[0].asJsonObject["t_ns"].asString.toLong()
        assertEquals(9007199254740993L, first)
        assertNotEquals(first, first.toDouble().toLong())
        events.forEachIndexed { index, value ->
            val event = value.asJsonObject
            assertEquals(first + index * 10_000_000L, event["t_ns"].asString.toLong())
            assertEquals(5_000_000L, event["received_ns"].asString.toLong() - event["t_ns"].asString.toLong())
        }
    }

    @Test fun nullSpeedAndBearingAreNotInventedMeasurements() {
        val events = wire().getAsJsonArray("events")
        val fix = events[2].asJsonObject.getAsJsonObject("data")
        listOf("speed_m_s", "bearing_deg", "altitude_m", "satellites_used").forEach {
            assertTrue(fix[it].isJsonNull)
        }
        assertTrue(events[6].asJsonObject.getAsJsonObject("data")["probability"].isJsonNull)
    }

    @Test fun unitsAndKnownHeadingRotationAgreeWithPythonFixture() {
        val events = wire().getAsJsonArray("events")
        val acceleration = events[0].asJsonObject.getAsJsonObject("data")
        val gyro = events[1].asJsonObject.getAsJsonObject("data")
        assertEquals("m/s^2", acceleration["unit"].asString)
        assertEquals(9.80665, acceleration.getAsJsonArray("xyz")[2].asDouble, 1e-12)
        assertEquals("rad/s", gyro["unit"].asString)
        assertEquals(-0.5, gyro.getAsJsonArray("xyz")[2].asDouble, 1e-12)
        val nav = events[4].asJsonObject.getAsJsonObject("data")
        val yaw = Math.PI / 2 - Math.toRadians(nav["heading_deg"].asDouble)
        val velocity = nav.getAsJsonArray("velocity_enu_m_s")
        assertEquals(10 * cos(yaw), velocity[0].asDouble, 1e-12)
        assertEquals(10 * sin(yaw), velocity[1].asDouble, 1e-12)
        val q = nav.getAsJsonArray("q_enu_from_vehicle_wxyz").map { it.asDouble }
        assertTrue(abs(q.sumOf { it * it } - 1) < 1e-12)
        assertEquals(0.0, 1 - 2 * (q[2]*q[2] + q[3]*q[3]), 1e-12)
        assertEquals(1.0, 2 * (q[1]*q[2] + q[0]*q[3]), 1e-12)
    }

    // Independently serialized Kotlin-native fixture; integer-string times never become Double.
    private val expected = mapOf(
        "contract_version" to "1.0.0",
        "session_id" to "golden-sim",
        "source" to "simulation",
        "events" to listOf(
            mapOf(
                "event_id" to "0",
                "type" to "imu",
                "t_ns" to "9007199254740993",
                "received_ns" to "9007199259740993",
                "data" to mapOf(
                    "sensor" to "accelerometer",
                    "frame" to "android_device",
                    "unit" to "m/s^2",
                    "xyz" to listOf(0.0, 0.0, 9.80665),
                    "accuracy" to "high",
                ),
            ),
            mapOf(
                "event_id" to "1",
                "type" to "imu",
                "t_ns" to "9007199264740993",
                "received_ns" to "9007199269740993",
                "data" to mapOf(
                    "sensor" to "gyroscope",
                    "frame" to "android_device",
                    "unit" to "rad/s",
                    "xyz" to listOf(0.0, 0.0, -0.5),
                    "accuracy" to "unreliable",
                ),
            ),
            mapOf(
                "event_id" to "2",
                "type" to "gnss",
                "t_ns" to "9007199274740993",
                "received_ns" to "9007199279740993",
                "data" to mapOf(
                    "latitude_deg" to 12.9716,
                    "longitude_deg" to 77.5946,
                    "altitude_m" to null,
                    "altitude_reference" to null,
                    "speed_m_s" to null,
                    "bearing_deg" to null,
                    "horizontal_accuracy_m" to 3.5,
                    "vertical_accuracy_m" to null,
                    "satellites_used" to null,
                    "provider" to "fixture",
                    "utc_ms" to null,
                ),
            ),
            mapOf(
                "event_id" to "3",
                "type" to "calibration",
                "t_ns" to "9007199284740993",
                "received_ns" to "9007199289740993",
                "data" to mapOf(
                    "id" to "fixture-cal",
                    "status" to "valid",
                    "q_vehicle_from_device_wxyz" to listOf(1.0, 0.0, 0.0, 0.0),
                    "gyro_bias_rad_s" to listOf(0.01, -0.02, 0.03),
                    "accelerometer_bias_m_s2" to null,
                    "confidence" to 0.8,
                ),
            ),
            mapOf(
                "event_id" to "4",
                "type" to "navigation",
                "t_ns" to "9007199294740993",
                "received_ns" to "9007199299740993",
                "data" to mapOf(
                    "status" to "tracking",
                    "initialization_mode" to "deployable",
                    "origin_wgs84_deg_m" to listOf(12.9716, 77.5946, 0.0),
                    "position_enu_m" to listOf(1.25, -2.5, 0.0),
                    "velocity_enu_m_s" to listOf(0.0, 10.0, 0.0),
                    "q_enu_from_vehicle_wxyz" to listOf(0.7071067811865476, 0.0, 0.0, 0.7071067811865476),
                    "heading_deg" to 0.0,
                    "calibration_id" to "fixture-cal",
                    "gnss_used_after_initialization" to false,
                ),
            ),
            mapOf(
                "event_id" to "5",
                "type" to "gnss_quality",
                "t_ns" to "9007199304740993",
                "received_ns" to "9007199309740993",
                "data" to mapOf(
                    "state" to "unavailable",
                    "fix_age_s" to null,
                    "satellites_used" to null,
                    "reasons" to listOf("NO_FIX"),
                ),
            ),
            mapOf(
                "event_id" to "6",
                "type" to "confidence",
                "t_ns" to "9007199314740993",
                "received_ns" to "9007199319740993",
                "data" to mapOf(
                    "state" to "unvalidated",
                    "probability" to null,
                    "horizontal_accuracy_95_m" to null,
                    "speed_std_m_s" to null,
                ),
            ),
            mapOf(
                "event_id" to "7",
                "type" to "diagnostic",
                "t_ns" to "9007199324740993",
                "received_ns" to "9007199329740993",
                "data" to mapOf(
                    "severity" to "warning",
                    "code" to "SIMULATED_INPUT",
                    "message" to "Synthetic contract fixture; not live sensor data.",
                    "dropped_count" to 1.0,
                ),
            ),
        ),
    )
}
