package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.acquisition.CaptureState
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrIcon
import com.intelligentdeadreckoning.app.ui.design.IdrMeter
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrSectionLabel
import com.intelligentdeadreckoning.app.ui.design.IdrSize
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.IdrStateTone
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.KeyValueRow
import com.intelligentdeadreckoning.app.ui.design.StatTile
import com.intelligentdeadreckoning.app.ui.design.StatePanel
import com.intelligentdeadreckoning.app.ui.design.StatusDot
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.Sensor
import java.util.Locale

private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "Unavailable"

@Composable
fun RealDiagnostics(state: CaptureState, start: () -> Unit, stop: () -> Unit,
                    permission: () -> Unit, settings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
            Text("Live measurements", style = IdrType.headlineLarge, color = IdrPalette.textPrimary)
            Text(
                "Per-sensor values, measured rates and exact timestamps from the real acquisition stream.",
                color = IdrPalette.textSecondary,
                style = IdrType.bodyMedium,
            )
        }

        IdrCard(emphasis = IdrEmphasis.PRIMARY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (state.running) IdrPalette.accent else IdrPalette.textMuted)
                Spacer(Modifier.width(IdrSpace.sm))
                Text(
                    if (state.running) "Running" else "Stopped",
                    color = if (state.running) IdrPalette.accent else IdrPalette.textSecondary,
                    style = IdrType.titleMedium,
                    modifier = Modifier.testTag("real_status"),
                )
            }
            Text(state.message, color = IdrPalette.textSecondary, style = IdrType.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
                IdrButton("Start sensors", start, enabled = !state.running, variant = IdrButtonVariant.PRIMARY,
                    glyph = IdrGlyph.RECORD, testTag = "real_start", modifier = Modifier.weight(1.4f))
                IdrButton("Stop", stop, enabled = state.running, variant = IdrButtonVariant.SECONDARY,
                    glyph = IdrGlyph.STOP, testTag = "real_stop", modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                IdrButton("Allow location", permission, variant = IdrButtonVariant.SECONDARY,
                    glyph = IdrGlyph.LOCATE, testTag = "grant_location")
                IdrButton("App settings", settings, variant = IdrButtonVariant.GHOST, glyph = IdrGlyph.SLIDERS)
            }
            Text(
                "Precise location enables GNSS fixes and satellite status. Approximate location uses the network provider when available. IMU can run with location denied.",
                color = IdrPalette.textMuted,
                style = IdrType.bodySmall,
            )
            IdrDivider()
            Text(
                "Location: ${state.permission.name}",
                color = IdrPalette.textSecondary,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("location_permission"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
                StatTile("GNSS state", state.quality.state.wire, modifier = Modifier.weight(1f),
                    valueStyle = IdrType.titleMedium)
                StatTile("Fix age", number(state.quality.fix_age_s), modifier = Modifier.weight(1f),
                    unit = "s", valueStyle = IdrType.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
                StatTile("Satellites visible", state.satellitesVisible?.toString() ?: "Unavailable",
                    modifier = Modifier.weight(1f), valueStyle = IdrType.titleMedium)
                StatTile("Satellites used", state.satellitesUsed?.toString() ?: "Unavailable",
                    modifier = Modifier.weight(1f), valueStyle = IdrType.titleMedium)
            }
        }

        IdrCard(emphasis = IdrEmphasis.SECONDARY) {
            IdrSectionLabel("Capture counters")
            Text(
                "Accepted ${state.accepted} · delayed ${state.delayed} · duplicates ${state.duplicates}\n" +
                    "Dropped ${state.dropped} · invalid ${state.invalid}\n" +
                    "Queue ${state.queueDepth}/256 · peak ${state.queueHighWater}",
                color = IdrPalette.textSecondary,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("capture_counts"),
            )
            IdrMeter(fraction = state.queueHighWater / 256f, tone = IdrTone.INFO)
        }

        for (sensor in Sensor.entries) {
            val info = state.sensors[sensor]
            val reading = state.readings[sensor.wire]
            val imu = reading?.record?.event?.data as? ImuMeasurement
            IdrCard(emphasis = IdrEmphasis.SECONDARY) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        when (info?.available) {
                            true -> IdrPalette.accent
                            false -> IdrPalette.danger
                            null -> IdrPalette.textMuted
                        },
                    )
                    Spacer(Modifier.width(IdrSpace.sm))
                    Text(
                        sensor.wire.uppercase(Locale.ROOT),
                        color = IdrPalette.textPrimary,
                        style = IdrType.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            reading?.stale == true -> "STALE"
                            reading == null -> "WAITING"
                            else -> "LIVE"
                        },
                        color = when {
                            reading?.stale == true -> IdrPalette.warning
                            reading == null -> IdrPalette.textMuted
                            else -> IdrPalette.accent
                        },
                        style = IdrType.labelSmall,
                    )
                }
                Text(
                    when (info?.available) {
                        true -> "${info.name} / ${info.vendor}"
                        false -> "Unavailable on this device"
                        null -> "Not started"
                    },
                    color = IdrPalette.textSecondary,
                    style = IdrType.bodySmall,
                )
                Text(
                    "Measured ${number(reading?.rateHz)} Hz · requested ${number(info?.requestedHz)} Hz",
                    color = IdrPalette.textMuted,
                    style = IdrType.monoSmall,
                )
                if (imu == null) {
                    Text("No sample.", color = IdrPalette.textMuted, style = IdrType.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
                        StatTile("X", number(imu.xyz.x), modifier = Modifier.weight(1f), unit = imu.unit.wire)
                        StatTile("Y", number(imu.xyz.y), modifier = Modifier.weight(1f), unit = imu.unit.wire)
                        StatTile("Z", number(imu.xyz.z), modifier = Modifier.weight(1f), unit = imu.unit.wire)
                    }
                    KeyValueRow("Accuracy", imu.accuracy.wire, mono = true)
                }
                reading?.let {
                    KeyValueRow("Event ns", it.record.event.t_ns.toString(), mono = true)
                    KeyValueRow("Receipt ns", it.record.event.received_ns.toString(), mono = true)
                }
            }
        }

        // Named honestly: every value below is exactly what the platform reported — no
        // smoothing, correction, fusion or interpolation has touched it.
        IdrSectionLabel("Raw GNSS fixes (as reported)")
        val providers = state.readings.filterKeys { it.startsWith("gnss_") }
        if (providers.isEmpty()) {
            StatePanel(
                title = "No location provider fixes yet",
                message = "Start acquisition and grant location for GNSS/network fixes. Missing values stay null, never zero.",
                tone = IdrStateTone.EMPTY,
                testTag = "gnss_empty",
            )
        }
        for ((key, reading) in providers) {
            val fix = reading.record.event.data as GnssMeasurement
            IdrCard(emphasis = IdrEmphasis.SECONDARY) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdrIcon(IdrGlyph.SATELLITE, tint = IdrPalette.info, size = IdrSize.iconSm)
                    Spacer(Modifier.width(IdrSpace.sm))
                    Text(fix.provider, color = IdrPalette.textPrimary, style = IdrType.titleMedium,
                        modifier = Modifier.weight(1f))
                    Text(
                        if (reading.stale) "STALE" else "LATEST FIX",
                        color = if (reading.stale) IdrPalette.warning else IdrPalette.accent,
                        style = IdrType.labelSmall,
                    )
                }
                Text(
                    "${fix.provider} · ${number(reading.rateHz)} Hz · ${if (reading.stale) "STALE" else "latest fix"}\n" +
                        "Latitude ${fix.latitude_deg}°\nLongitude ${fix.longitude_deg}°\n" +
                        "Altitude ${number(fix.altitude_m)} m (${fix.altitude_reference?.wire ?: "unavailable"})\n" +
                        "Speed ${number(fix.speed_m_s)} m/s · bearing ${number(fix.bearing_deg)}°\n" +
                        "Horizontal accuracy ${number(fix.horizontal_accuracy_m)} m\n" +
                        "Vertical accuracy ${number(fix.vertical_accuracy_m)} m\n" +
                        "Event ns ${reading.record.event.t_ns}\nReceipt ns ${reading.record.event.received_ns}\n" +
                        "UTC ms ${fix.utc_ms ?: "Unavailable"}",
                    color = IdrPalette.textSecondary,
                    style = IdrType.monoSmall,
                    modifier = Modifier.testTag(key),
                )
            }
        }

        IdrCard(emphasis = IdrEmphasis.SECONDARY) {
            IdrSectionLabel("Recent diagnostic events")
            val recent = state.diagnostics.takeLast(8).asReversed()
            if (recent.isEmpty()) {
                Text("No diagnostic events this session.", color = IdrPalette.textMuted, style = IdrType.bodySmall)
            }
            for (record in recent) {
                val d = record.event.data as DiagnosticEvent
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    StatusDot(
                        when (d.severity.wire) {
                            "error" -> IdrPalette.danger
                            "warning" -> IdrPalette.warning
                            else -> IdrPalette.info
                        },
                        modifier = Modifier.padding(top = 5.dp),
                        size = IdrSize.dotSm,
                    )
                    Spacer(Modifier.width(IdrSpace.sm))
                    Text(
                        "${d.code}: ${d.message} (${d.dropped_count})",
                        color = IdrPalette.textSecondary,
                        style = IdrType.bodySmall,
                    )
                }
            }
        }

        Text(
            "Foreground acquisition only · no upload · navigation not implemented",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
    }
}
