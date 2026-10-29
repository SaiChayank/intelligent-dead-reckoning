package com.intelligentdeadreckoning.app.calibration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.io.File

/**
 * The last valid mount calibration of one device, kept between drives so a new drive does not have to
 * wait for the live calibrator (which needs a stationary spell and straight driving) before it can navigate.
 *
 * It is a prior, not a result: the engine adopts it only when it passes [SavedCalibrationPolicy] and drops it
 * as soon as the live calibrator disagrees ([SavedCalibrationPolicy.MAX_DISAGREEMENT_RAD]).
 */
data class SavedCalibration(
    val deviceKey: String,
    val savedAtEpochMs: Long,
    val qVehicleFromDevice: Quaternion,
    val gyroBiasRad_S: Vector3,
    val accelerometerBiasM_S2: Vector3?,
    val confidence: Double,
)

interface CalibrationStore {
    fun load(deviceKey: String): SavedCalibration?
    fun save(calibration: SavedCalibration)
}

object SavedCalibrationPolicy {
    /** A phone is usually in the same mount for days; a month-old calibration is more likely a different mount. */
    const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    const val MIN_CONFIDENCE = 0.5
    /** A gyro bias this large is not a bias, it is a bad estimate. */
    const val MAX_GYRO_BIAS_RAD_S = 0.2
    /** Live and saved mounts further apart than this mean the phone was remounted: the saved one is dropped. */
    const val MAX_DISAGREEMENT_RAD = 20.0 * Math.PI / 180.0

    /** Why [saved] cannot be used now, or null when it can. */
    fun rejection(saved: SavedCalibration, deviceKey: String, nowEpochMs: Long): String? = when {
        saved.deviceKey != deviceKey -> "saved for a different device"
        saved.savedAtEpochMs > nowEpochMs -> "saved in the future"
        nowEpochMs - saved.savedAtEpochMs > MAX_AGE_MS -> "older than 30 days"
        !isProperRotation(saved.qVehicleFromDevice, 1e-6) -> "mount is not a rotation"
        !saved.confidence.isFinite() || saved.confidence < MIN_CONFIDENCE -> "confidence too low"
        !saved.gyroBiasRad_S.finite() || saved.gyroBiasRad_S.norm() > MAX_GYRO_BIAS_RAD_S -> "gyro bias implausible"
        saved.accelerometerBiasM_S2?.finite() == false -> "accelerometer bias not finite"
        else -> null
    }

    private fun Vector3.finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** One JSON file per device key under [directory]. Writes go to a temporary file first, so a crash cannot leave half a file. */
class FileCalibrationStore(private val directory: File) : CalibrationStore {
    private fun fileFor(deviceKey: String) =
        File(directory, "mount_" + deviceKey.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    override fun load(deviceKey: String): SavedCalibration? = try {
        val file = fileFor(deviceKey)
        if (!file.isFile) null else parse(JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject)
    } catch (_: Exception) {
        null    // unreadable or malformed: behave as if nothing was saved
    }

    override fun save(calibration: SavedCalibration) {
        directory.mkdirs()
        val target = fileFor(calibration.deviceKey)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(toJson(calibration).toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "could not replace $target" }
        }
    }

    private fun toJson(c: SavedCalibration) = JsonObject().apply {
        addProperty("device_key", c.deviceKey)
        addProperty("saved_at_epoch_ms", c.savedAtEpochMs)
        add("q_vehicle_from_device_wxyz", com.google.gson.JsonArray().apply {
            listOf(c.qVehicleFromDevice.w, c.qVehicleFromDevice.x, c.qVehicleFromDevice.y, c.qVehicleFromDevice.z).forEach(::add)
        })
        add("gyro_bias_rad_s", vec(c.gyroBiasRad_S))
        c.accelerometerBiasM_S2?.let { add("accelerometer_bias_m_s2", vec(it)) }
        addProperty("confidence", c.confidence)
    }

    private fun vec(v: Vector3) = com.google.gson.JsonArray().apply { add(v.x); add(v.y); add(v.z) }

    private fun parse(o: JsonObject): SavedCalibration {
        fun vec(name: String) = o.getAsJsonArray(name).map { it.asDouble }.also { require(it.size == 3) }.let { Vector3(it[0], it[1], it[2]) }
        val q = o.getAsJsonArray("q_vehicle_from_device_wxyz").map { it.asDouble }.also { require(it.size == 4) }
        return SavedCalibration(
            deviceKey = o.get("device_key").asString,
            savedAtEpochMs = o.get("saved_at_epoch_ms").asLong,
            qVehicleFromDevice = Quaternion(q[0], q[1], q[2], q[3]),
            gyroBiasRad_S = vec("gyro_bias_rad_s"),
            accelerometerBiasM_S2 = if (o.has("accelerometer_bias_m_s2")) vec("accelerometer_bias_m_s2") else null,
            confidence = o.get("confidence").asDouble,
        )
    }
}
