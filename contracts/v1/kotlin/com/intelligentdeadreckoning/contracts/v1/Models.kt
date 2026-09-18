package com.intelligentdeadreckoning.contracts.v1

// This package imports no Android, Compose, map or algorithm classes.
interface WireEnum { val wire: String }
enum class Source(override val wire: String) : WireEnum {
    REAL("real"),
    SIMULATION("simulation"),
    REPLAY_REAL("replay_real"),
    REPLAY_SIMULATION("replay_simulation")
}
enum class Sensor(override val wire: String) : WireEnum {
    ACCELEROMETER("accelerometer"),
    GYROSCOPE("gyroscope"),
    GRAVITY("gravity"),
    MAGNETOMETER("magnetometer")
}
enum class DeviceFrame(override val wire: String) : WireEnum {
    ANDROID_DEVICE("android_device")
}
enum class ImuUnit(override val wire: String) : WireEnum {
    METRES_PER_SECOND_SQUARED("m/s^2"),
    RADIANS_PER_SECOND("rad/s"),
    MICROTESLA("uT")
}
enum class SensorAccuracy(override val wire: String) : WireEnum {
    UNKNOWN("unknown"),
    UNRELIABLE("unreliable"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high")
}
enum class AltitudeReference(override val wire: String) : WireEnum {
    ELLIPSOID("ellipsoid"),
    MSL("msl"),
    UNKNOWN("unknown")
}
enum class CalibrationStatus(override val wire: String) : WireEnum {
    PENDING("pending"),
    VALID("valid"),
    INVALID("invalid"),
    EXPIRED("expired")
}
enum class NavigationStatus(override val wire: String) : WireEnum {
    UNINITIALIZED("uninitialized"),
    CALIBRATING("calibrating"),
    TRACKING("tracking"),
    DEGRADED("degraded"),
    FAILED("failed")
}
enum class InitializationMode(override val wire: String) : WireEnum {
    EVALUATION("evaluation"),
    DEPLOYABLE("deployable")
}
enum class GnssState(override val wire: String) : WireEnum {
    UNAVAILABLE("unavailable"),
    ACQUIRING("acquiring"),
    GOOD("good"),
    DEGRADED("degraded"),
    STALE("stale"),
    DENIED("denied")
}
enum class ConfidenceState(override val wire: String) : WireEnum {
    UNAVAILABLE("unavailable"),
    UNVALIDATED("unvalidated"),
    CALIBRATED("calibrated")
}
enum class Severity(override val wire: String) : WireEnum {
    INFO("info"),
    WARNING("warning"),
    ERROR("error")
}
data class Vector3(val x: Double, val y: Double, val z: Double)
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double)
data class GeoOrigin(val latitude_deg: Double, val longitude_deg: Double, val altitude_m: Double)
sealed interface Payload { val type: String }
data class ImuMeasurement(
    val sensor: Sensor,
    val frame: DeviceFrame,
    val unit: ImuUnit,
    val xyz: Vector3,
    val accuracy: SensorAccuracy
) : Payload { override val type = "imu" }
data class GnssMeasurement(
    val latitude_deg: Double,
    val longitude_deg: Double,
    val altitude_m: Double?,
    val altitude_reference: AltitudeReference?,
    val speed_m_s: Double?,
    val bearing_deg: Double?,
    val horizontal_accuracy_m: Double?,
    val vertical_accuracy_m: Double?,
    val satellites_used: Long?,
    val provider: String,
    val utc_ms: Long?
) : Payload { override val type = "gnss" }
data class CalibrationResult(
    val id: String,
    val status: CalibrationStatus,
    val q_vehicle_from_device_wxyz: Quaternion?,
    val gyro_bias_rad_s: Vector3?,
    val accelerometer_bias_m_s2: Vector3?,
    val confidence: Double?
) : Payload { override val type = "calibration" }
data class NavigationState(
    val status: NavigationStatus,
    val initialization_mode: InitializationMode,
    val origin_wgs84_deg_m: GeoOrigin?,
    val position_enu_m: Vector3?,
    val velocity_enu_m_s: Vector3?,
    val q_enu_from_vehicle_wxyz: Quaternion?,
    val heading_deg: Double?,
    val calibration_id: String?,
    val gnss_used_after_initialization: Boolean
) : Payload { override val type = "navigation" }
data class GnssQualityState(
    val state: GnssState,
    val fix_age_s: Double?,
    val satellites_used: Long?,
    val reasons: List<String>
) : Payload { override val type = "gnss_quality" }
data class Confidence(
    val state: ConfidenceState,
    val probability: Double?,
    val horizontal_accuracy_95_m: Double?,
    val speed_std_m_s: Double?
) : Payload { override val type = "confidence" }
data class DiagnosticEvent(
    val severity: Severity,
    val code: String,
    val message: String,
    val dropped_count: Long
) : Payload { override val type = "diagnostic" }
data class Header(val session_id: String, val source: Source, val contract_version: String = "1.0.0")
data class Event(val event_id: String, val t_ns: Long, val received_ns: Long, val data: Payload)
data class Record(val header: Header, val event: Event)
data class EngineSession(val header: Header, val boot_id: String, val origin_ns: Long)

/** Interface ONLY; deliberately no implementation/factory or sensor/navigation logic.
 * Future implementations must validate record kind, causal calibration, session ownership
 * and frozen initialization mode; drain bounded output in measurement-time order.
 */
interface NavigationEngine {
    fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode)
    fun acceptImu(measurement: Record)
    fun acceptGnss(measurement: Record)
    fun drain(): Sequence<Record>
    fun stop()
    fun reset()
}

