package com.intelligentdeadreckoning.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intelligentdeadreckoning.app.simulation.*
import com.intelligentdeadreckoning.app.acquisition.*
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.app.recording.RecorderState
import com.intelligentdeadreckoning.app.recording.RecorderPhase
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private enum class Screen(val title: String) { DASHBOARD("Dashboard"), DIAGNOSTICS("Diagnostics"), ABOUT("About") }

@Composable
fun IdrApp(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit,
           source: InputSource = InputSource.SIMULATION, capture: CaptureState = CaptureState(),
           onSource: (InputSource) -> Unit = {}, onPermission: () -> Unit = {}, onSettings: () -> Unit = {},
           recording: RecorderState = RecorderState(), onStartRecording: () -> Unit = {},
           onStopRecording: () -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    BackHandler(enabled = selected != Screen.DASHBOARD) { selected = Screen.DASHBOARD }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = selected == screen,
                        onClick = { selected = screen },
                        icon = { NavigationGlyph(screen, selected == screen) },
                        label = { Text(screen.title) },
                        modifier = Modifier.testTag("tab_${screen.name}"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Lime, selectedTextColor = Lime,
                            indicatorColor = Panel, unselectedIconColor = Muted, unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Lime, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Text("i↗", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("DEAD RECKONING", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("MOTION LAB / 01", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(Amber, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(if (source == InputSource.SIMULATION) "SIMULATION" else "REAL PHONE", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (source == InputSource.SIMULATION) "Demo data · not live sensors" else "Measured · foreground only", color = Amber, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = source == InputSource.SIMULATION, onClick = { onSource(InputSource.SIMULATION) },
                        label = { Text("Simulation") }, modifier = Modifier.testTag("source_simulation"))
                    FilterChip(selected = source == InputSource.REAL, onClick = { onSource(InputSource.REAL) },
                        label = { Text("Phone sensors") }, modifier = Modifier.testTag("source_real"))
                }
            }
            // Each page scrolls independently, including on small displays / larger font settings.
            key(selected) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (selected != Screen.ABOUT) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Local recording · ${recording.phase.name.lowercase()}", modifier = Modifier.testTag("recording_status"))
                            Text(recording.message, style = MaterialTheme.typography.bodySmall)
                            Text("Written ${recording.written} · dropped ${recording.dropped} · write errors ${recording.writeErrors} · ID gaps ${recording.eventIdGaps}",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onStartRecording,
                                    enabled = source == InputSource.REAL && capture.running && capture.sensors.isNotEmpty() && !recording.busy,
                                    modifier = Modifier.testTag("recording_start")) { Text("Start recording") }
                                OutlinedButton(onClick = onStopRecording,
                                    enabled = recording.phase in listOf(RecorderPhase.STARTING, RecorderPhase.RECORDING) && recording.recordingId != null,
                                    modifier = Modifier.testTag("recording_stop")) { Text("Stop recording") }
                            }
                            if (source == InputSource.SIMULATION) Text("Recording requires the Phone sensors acquisition stream.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    when (selected) {
                        Screen.DASHBOARD -> {
                            if (source == InputSource.REAL) {
                                RealDashboard(
                                    state = capture,
                                    start = onStart,
                                    stop = onStop,
                                    permission = onPermission,
                                    settings = onSettings,
                                )
                            } else {
                                Dashboard(state, onStart, onStop)
                            }
                        }

                        Screen.DIAGNOSTICS -> {
                            if (source == InputSource.REAL) {
                                RealDiagnostics(
                                    state = capture,
                                    start = onStart,
                                    stop = onStop,
                                    permission = onPermission,
                                    settings = onSettings,
                                )
                            } else {
                                Diagnostics(state, onStart, onStop)
                            }
                        }

                        Screen.ABOUT -> About()
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Dashboard(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit) {
    Column {
        Text("Ready to explore.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(5.dp))
        Text("A first look at motion, before the real drive.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
    Surface(shape = RoundedCornerShape(28.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("MOTION PREVIEW", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                Text(statusLabel(state), color = if (state.isRunning) Lime else Muted, fontSize = 12.sp,
                    modifier = Modifier.testTag("session_status"))
            }
            SpeedGauge(state.measurement?.speedKmh)
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("SESSION TIME", duration(state.measurement?.elapsedMillis ?: 0), Modifier.weight(1f))
                Metric("DEMO HEADING", state.measurement?.let { "${decimal(it.headingDegrees, 0)}°" } ?: "—", Modifier.weight(1f))
            }
        }
    }
    SessionControls(state, onStart, onStop)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallCard("DATA SOURCE", "Scripted demo", "No hardware accessed", Modifier.weight(1f))
        SmallCard("NAVIGATION", "Not connected", "Core integration later", Modifier.weight(1f))
    }
    Text("Made for GNSS-challenged journeys. This build previews the interface only; it cannot locate or navigate your vehicle.",
        color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp)
}

@Composable
private fun SessionControls(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart, enabled = !state.isRunning,
                shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp),
                modifier = Modifier.weight(1.45f).heightIn(min = 56.dp).testTag("start_button"),
            ) { Text(if (state.status == SessionStatus.STOPPED) "Start new demo" else "Start simulation", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onStop, enabled = state.isRunning,
                shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("stop_button"),
            ) { Text("Stop", fontWeight = FontWeight.Bold) }
        }
        Text(
            when {
                state.isRunning -> "Demo running · stops when you leave the app."
                state.stopReason == StopReason.BACKGROUND -> "Stopped in background. Start a new demo to continue."
                state.status == SessionStatus.STOPPED -> "Stopped · last values retained. A new demo resets them."
                else -> "No sensors, location access or recording."
            }, color = Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("session_message"),
        )
    }
}

@Composable
private fun ColumnScope.RealDashboard(
    state: CaptureState,
    start: () -> Unit,
    stop: () -> Unit,
    permission: () -> Unit,
    settings: () -> Unit,
) {
    Column {
        Text(
            "Live measurements",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Foreground phone sensors and permitted location.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (state.running) "Running" else "Stopped",
                modifier = Modifier.testTag("real_status"),
                color = Lime,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Text(state.message, color = Muted)

            Text(
                "Location: ${state.permission.name}",
                modifier = Modifier.testTag("location_permission"),
            )

            Text(
                "GNSS: ${state.quality.state.wire}",
                color = Muted,
            )

            val availableSensors = Sensor.entries.count {
                state.sensors[it]?.available == true
            }

            Text(
                "Sensors available: $availableSensors / ${Sensor.entries.size}",
                color = Muted,
            )

            Text(
                "Accepted ${state.accepted} · dropped ${state.dropped} · invalid ${state.invalid}",
                color = Muted,
            )

            Text(
                "Queue ${state.queueDepth}/256 · peak ${state.queueHighWater}",
                color = Muted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = start,
            enabled = !state.running,
            modifier = Modifier
                .weight(1.4f)
                .heightIn(min = 56.dp)
                .testTag("real_start"),
        ) {
            Text("Start sensors", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = stop,
            enabled = state.running,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .testTag("real_stop"),
        ) {
            Text("Stop", fontWeight = FontWeight.Bold)
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = permission,
            modifier = Modifier.testTag("grant_location"),
        ) {
            Text("Allow location")
        }

        TextButton(onClick = settings) {
            Text("App settings")
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Detailed diagnostics",
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                "Open the Diagnostics tab for per-sensor values, measured rates, timestamps, GNSS fields, queue statistics and diagnostic events.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 19.sp,
            )
        }
    }

    Text(
        "Foreground acquisition only · recording off · navigation not running",
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ColumnScope.Diagnostics(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit) {
    Column {
        Text("Under the hood.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(6.dp))
        Text("Scripted values, with explicit units.\nNot a physical sensor model or real IMU data.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallCard("UI DEMO CADENCE", "10 Hz target", "Not a measured sensor rate", Modifier.weight(1f))
        SmallCard("DEMO SAMPLES", state.sampleCount.toString(), statusLabel(state), Modifier.weight(1f))
    }
    SensorCard("Accelerometer", "m/s²", state.measurement?.accelerometer, Lime)
    SensorCard("Gyroscope", "rad/s", state.measurement?.gyroscope, Color(0xFF92C8F1))
    SensorCard("Magnetometer", "µT", state.measurement?.magnetometer, Amber)
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GNSS & device sensors", fontWeight = FontWeight.SemiBold)
            Text("Not connected in this build. No real position, accuracy, satellite count or sensor accuracy is available.", color = Muted,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    SessionControls(state, onStart, onStop)
}

@Composable
private fun ColumnScope.About() {
    Column {
        Text("Built for the\njourney ahead.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(8.dp))
        Text("Intelligent Dead Reckoning\nSIH problem statement #26168", color = Muted)
    }
    Surface(shape = RoundedCornerShape(24.dp), color = Panel) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Milestone("01", "App foundation", "Available now", "Kotlin + Jetpack Compose, screen navigation and an in-memory demo.", true)
            HorizontalDivider(color = Line)
            Milestone("02", "Real-world acquisition", "Available now", "Select Phone sensors for foreground IMU and location measurements. Recording remains planned.", true)
            HorizontalDivider(color = Line)
            Milestone("03", "Navigation integration", "Future work", "A validated shared navigation core, calibration and eventually map display.", false)
        }
    }
    Text("Private by design", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("Simulation uses scripted values. Phone sensors mode reads IMU and permitted location while this app is visible. Data stays in bounded memory and is never recorded or uploaded. Leaving the app stops acquisition; returning requires Start.", color = Muted, lineHeight = 23.sp)
    Text("Scientific boundary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("No INS, AI or fusion is running here. Dataset frame conventions are not automatically valid for this phone; real-device calibration and core validation remain separate work.", color = Muted, lineHeight = 23.sp)
    Text("ANDROID IS THE MAIN PRODUCT\nPython supports offline research. Edge deployment is secondary.", color = Muted, fontSize = 11.sp, lineHeight = 19.sp, letterSpacing = 0.5.sp)
    Text("FOREGROUND ACQUISITION · NAVIGATION PLANNED", color = Lime, fontSize = 11.sp, letterSpacing = 1.sp)
}

@Composable
private fun Milestone(number: String, title: String, stage: String, description: String, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(number, color = if (active) Lime else Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stage.uppercase(Locale.ROOT), color = if (active) Lime else Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun SensorCard(title: String, unit: String, vector: Vector3?, accent: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(unit, color = Muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("X" to vector?.x, "Y" to vector?.y, "Z" to vector?.z).forEach { (axis, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(axis, color = Muted, fontSize = 10.sp)
                        Text(value?.let { decimal(it, 3) } ?: "—", fontFamily = FontFamily.Monospace, fontSize = 17.sp,
                            modifier = Modifier.semantics { contentDescription = "$title $axis: ${value?.let { decimal(it, 3) } ?: "no sample"} $unit, simulated" })
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallCard(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.border(1.dp, Line, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 24.sp)
    }
}

@Composable
private fun SpeedGauge(speed: Double?) {
    Box(Modifier.fillMaxWidth().height(225.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(218.dp)) {
            val inset = 14.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(Line, 140f, 260f, false, Offset(inset, inset), arcSize, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            val progress = ((speed ?: 0.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
            if (progress > 0) drawArc(Lime, 140f, 260f * progress, false, Offset(inset, inset), arcSize, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            for (tick in 0..30) {
                val angle = Math.toRadians(140.0 + tick * 260.0 / 30)
                val radius = size.width / 2 - 27.dp.toPx()
                val length = if (tick % 5 == 0) 9.dp.toPx() else 4.dp.toPx()
                drawLine(Muted.copy(alpha = 0.5f),
                    center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat()),
                    center + Offset((cos(angle) * (radius - length)).toFloat(), (sin(angle) * (radius - length)).toFloat()),
                    1.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DEMO SPEED", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(speed?.let { decimal(it, 1) } ?: "—", fontSize = 54.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp,
                modifier = Modifier.testTag("demo_speed"))
            Text("km/h", color = Lime, fontSize = 13.sp)
        }
        Text("SCRIPTED · NOT MEASURED", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 11.dp))
    }
}

@Composable
private fun NavigationGlyph(screen: Screen, selected: Boolean) {
    val color = if (selected) Lime else Muted
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        when (screen) {
            Screen.DASHBOARD -> {
                val path = Path().apply {
                    moveTo(w * .15f, w * .45f); lineTo(w * .5f, w * .15f); lineTo(w * .85f, w * .45f)
                    lineTo(w * .85f, w * .85f); lineTo(w * .15f, w * .85f); close()
                }
                drawPath(path, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
            }
            Screen.DIAGNOSTICS -> {
                val path = Path().apply {
                    moveTo(0f, w * .55f); lineTo(w * .2f, w * .55f); lineTo(w * .35f, w * .2f)
                    lineTo(w * .55f, w * .85f); lineTo(w * .7f, w * .4f); lineTo(w, w * .4f)
                }
                drawPath(path, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
            }
            Screen.ABOUT -> {
                drawCircle(color, w * .4f, style = Stroke(1.7.dp.toPx()))
                drawCircle(color, 1.3.dp.toPx(), Offset(w / 2, w * .32f))
                drawLine(color, Offset(w / 2, w * .48f), Offset(w / 2, w * .72f), 1.7.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

private fun statusLabel(state: SimulationState) = when (state.status) {
    SessionStatus.READY -> "Ready"
    SessionStatus.RUNNING -> "Running"
    SessionStatus.STOPPED -> "Stopped"
}

internal fun duration(millis: Long): String {
    val seconds = millis / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private fun decimal(value: Double, places: Int) = String.format(Locale.ROOT, "%.${places}f", value)

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DashboardPreview() {
    IdrTheme { IdrApp(SimulationState(SessionStatus.RUNNING, DemoSignal.at(15000), 151), {}, {}) }
}
