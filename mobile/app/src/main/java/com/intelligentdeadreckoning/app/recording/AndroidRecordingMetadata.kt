package com.intelligentdeadreckoning.app.recording

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.intelligentdeadreckoning.app.acquisition.CaptureState
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.Source
import java.util.UUID

/** Snapshot existing acquisition configuration; unavailable provider flags remain null.
 * No sensor access, permission changes, calibration or filesystem access.
 */
fun recordingMetadata(context: Context, capture: CaptureState): RecordingMetadata {
    require(capture.running && capture.sessionId != null && capture.originNs != null)
    val now = System.currentTimeMillis()
    return RecordingMetadata(
        recordingId = UUID.randomUUID().toString(), acquisitionSessionId = capture.sessionId,
        source = Source.REAL, startState = RecordingStartState.RECORDING, endState = null,
        completionState = CompletionState.OPEN, recoveryState = RecoveryState.NONE,
        createdUtcMs = now, startedUtcMs = now, endedUtcMs = null,
        clock = ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS, null, capture.sessionId,
            capture.originNs, SystemClock.elapsedRealtimeNanos(), null),
        device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, "Android", Build.VERSION.RELEASE, Build.VERSION.SDK_INT.toLong()),
        // Build provenance is unavailable here; avoid inventing version information.
        application = ApplicationInfo(context.packageName, null, null, null),
        sensors = capture.sensors.map { (sensor, info) ->
            SensorDescriptor(sensor, if (info.available) info.name else null,
                if (info.available) info.vendor else null, info.available, info.requestedHz,
                capture.readings[sensor.wire]?.rateHz?.takeIf { it > 0 && it.isFinite() })
        },
        sourceConfiguration = SourceConfiguration(LocationPermissionState.valueOf(capture.permission.name),
            null, null, null, null, true),
        calibration = CalibrationInfo(CalibrationApplication.NOT_APPLIED, null),
        recordCount = null, channelCounts = null,
    )
}
