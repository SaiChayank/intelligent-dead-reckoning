package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.acquisition.*
import com.intelligentdeadreckoning.contracts.v1.*
import java.util.Locale

private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "Unavailable"

@Composable
fun RealDiagnostics(state: CaptureState, start: () -> Unit, stop: () -> Unit,
                    permission: () -> Unit, settings: () -> Unit) {
    Text("Live measurements", style = MaterialTheme.typography.headlineMedium)
    Text(if (state.running) "Running" else "Stopped", modifier = Modifier.testTag("real_status"), color = Lime)
    Text(state.message, color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = start, enabled = !state.running, modifier = Modifier.testTag("real_start")) { Text("Start sensors") }
        OutlinedButton(onClick = stop, enabled = state.running, modifier = Modifier.testTag("real_stop")) { Text("Stop") }
    }
    Text("Location: ${state.permission.name}", modifier = Modifier.testTag("location_permission"))
    Text("Precise location enables GNSS fixes and satellite status. Approximate location uses the network provider when available. IMU can run with location denied.", color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = permission, modifier = Modifier.testTag("grant_location")) { Text("Allow location") }
        TextButton(onClick = settings) { Text("App settings") }
    }
    Text("Accepted ${state.accepted} · delayed ${state.delayed} · duplicates ${state.duplicates}\nDropped ${state.dropped} · invalid ${state.invalid}\nQueue ${state.queueDepth}/256 · peak ${state.queueHighWater}", modifier = Modifier.testTag("capture_counts"))
    for (sensor in Sensor.entries) {
        val info = state.sensors[sensor]
        val reading = state.readings[sensor.wire]
        val imu = reading?.record?.event?.data as? ImuMeasurement
        Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sensor.wire.uppercase(Locale.ROOT), color = Lime)
                Text(when (info?.available) { true -> "${info.name} / ${info.vendor}"; false -> "Unavailable"; null -> "Not started" })
                Text("Measured ${number(reading?.rateHz)} Hz · requested ${number(info?.requestedHz)} Hz")
                Text(if (imu == null) "No sample" else "X ${number(imu.xyz.x)}  Y ${number(imu.xyz.y)}  Z ${number(imu.xyz.z)} ${imu.unit.wire}")
                Text("Accuracy ${imu?.accuracy?.wire ?: "unknown"} · ${if (reading?.stale == true) "STALE" else if (reading == null) "waiting" else "latest sample"}")
                reading?.let { Text("Event ns ${it.record.event.t_ns}\nReceipt ns ${it.record.event.received_ns}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Text("Location status: ${state.quality.state.wire} · fix age ${number(state.quality.fix_age_s)} s")
    Text("Satellites visible ${state.satellitesVisible ?: "Unavailable"} · used ${state.satellitesUsed ?: "Unavailable"}")
    for ((key, reading) in state.readings.filterKeys { it.startsWith("gnss_") }) {
        val fix = reading.record.event.data as GnssMeasurement
        Text("${fix.provider} · ${number(reading.rateHz)} Hz · ${if (reading.stale) "STALE" else "latest fix"}")
        Text("Latitude ${fix.latitude_deg}°\nLongitude ${fix.longitude_deg}°\nAltitude ${number(fix.altitude_m)} m (${fix.altitude_reference?.wire ?: "unavailable"})\nSpeed ${number(fix.speed_m_s)} m/s · bearing ${number(fix.bearing_deg)}°\nHorizontal accuracy ${number(fix.horizontal_accuracy_m)} m\nVertical accuracy ${number(fix.vertical_accuracy_m)} m\nEvent ns ${reading.record.event.t_ns}\nReceipt ns ${reading.record.event.received_ns}\nUTC ms ${fix.utc_ms ?: "Unavailable"}", modifier = Modifier.testTag(key))
    }
    Text("Recent diagnostic events", style = MaterialTheme.typography.titleMedium)
    for (record in state.diagnostics.takeLast(8).asReversed()) {
        val d = record.event.data as DiagnosticEvent
        Text("${d.code}: ${d.message} (${d.dropped_count})", style = MaterialTheme.typography.bodySmall)
    }
    Text("Recording off · orientation/calibration unavailable · navigation not implemented", color = Muted)
}
