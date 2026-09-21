package com.intelligentdeadreckoning.contracts.recording.v1

import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.Source

const val RECORDING_CONTRACT_VERSION = "1.0.0"
const val MEASUREMENT_CONTRACT_VERSION = "1.0.0"

interface RecordingWireEnum { val wire: String }

enum class RecordingStartState(override val wire: String) : RecordingWireEnum {
    RECORDING("recording")
}

enum class RecordingEndState(override val wire: String) : RecordingWireEnum {
    STOPPED("stopped"),
    INTERRUPTED("interrupted"),
    FAILED("failed")
}

enum class CompletionState(override val wire: String) : RecordingWireEnum {
    OPEN("open"),
    COMPLETED("completed"),
    INCOMPLETE("incomplete"),
    FAILED("failed")
}

enum class RecoveryState(override val wire: String) : RecordingWireEnum {
    NONE("none"),
    REQUIRED("required"),
    RECOVERED("recovered"),
    UNRECOVERABLE("unrecoverable")
}

enum class CalibrationApplication(override val wire: String) : RecordingWireEnum {
    UNAVAILABLE("unavailable"),
    NOT_APPLIED("not_applied"),
    APPLIED("applied")
}

enum class LocationPermissionState(override val wire: String) : RecordingWireEnum {
    NOT_REQUESTED("not_requested"),
    DENIED("denied"),
    APPROXIMATE("approximate"),
    PRECISE("precise"),
    REVOKED("revoked")
}

enum class ClockDomain(override val wire: String) : RecordingWireEnum {
    ANDROID_ELAPSED_REALTIME_NS("android_elapsed_realtime_ns")
}

data class ClockIdentity(
    val domain: ClockDomain,
    val bootId: String?,
    val sessionClockId: String,
    val originNs: Long,
    val startedNs: Long,
    val endedNs: Long?,
)

data class DeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val osName: String?,
    val osVersion: String?,
    val apiLevel: Long?,
)

data class ApplicationInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val sourceVersion: String?,
)

data class SensorDescriptor(
    val sensor: Sensor,
    val name: String?,
    val vendor: String?,
    val available: Boolean,
    val requestedHz: Double?,
    val measuredHz: Double?,
)

data class SourceConfiguration(
    val locationPermission: LocationPermissionState,
    val locationServiceEnabled: Boolean?,
    val gpsProviderEnabled: Boolean?,
    val networkProviderEnabled: Boolean?,
    val satelliteStatusEnabled: Boolean?,
    val foregroundOnly: Boolean,
)

data class CalibrationInfo(
    val state: CalibrationApplication,
    val calibrationId: String?,
)

data class ChannelCount(
    val channel: String,
    val count: Long,
)

data class RecordingMetadata(
    val recordingId: String,
    val acquisitionSessionId: String,
    val source: Source,
    val startState: RecordingStartState,
    val endState: RecordingEndState?,
    val completionState: CompletionState,
    val recoveryState: RecoveryState,
    val createdUtcMs: Long?,
    val startedUtcMs: Long?,
    val endedUtcMs: Long?,
    val clock: ClockIdentity,
    val device: DeviceInfo,
    val application: ApplicationInfo,
    val sensors: List<SensorDescriptor>,
    val sourceConfiguration: SourceConfiguration,
    val calibration: CalibrationInfo,
    val recordCount: Long?,
    val channelCounts: List<ChannelCount>?,
    val recordingContractVersion: String = RECORDING_CONTRACT_VERSION,
    val measurementContractVersion: String = MEASUREMENT_CONTRACT_VERSION,
)

fun replaySource(source: Source): Source = when (source) {
    Source.REAL -> Source.REPLAY_REAL
    Source.SIMULATION -> Source.REPLAY_SIMULATION
    Source.REPLAY_REAL, Source.REPLAY_SIMULATION ->
        throw IllegalArgumentException("Only original real/simulation sources may be recorded")
}
