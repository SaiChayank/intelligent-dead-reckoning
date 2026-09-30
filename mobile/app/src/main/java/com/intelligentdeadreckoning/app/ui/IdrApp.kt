package com.intelligentdeadreckoning.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intelligentdeadreckoning.app.acquisition.CaptureState
import com.intelligentdeadreckoning.app.acquisition.InputSource
import com.intelligentdeadreckoning.app.recording.RecorderPhase
import com.intelligentdeadreckoning.app.recording.RecorderState
import com.intelligentdeadreckoning.app.replay.ReplayState
import com.intelligentdeadreckoning.app.sessions.ExportPhase
import com.intelligentdeadreckoning.app.sessions.ExportState
import com.intelligentdeadreckoning.app.sessions.SavedSession
import com.intelligentdeadreckoning.app.sessions.SessionPage
import com.intelligentdeadreckoning.app.simulation.DemoSignal
import com.intelligentdeadreckoning.app.simulation.SessionStatus
import com.intelligentdeadreckoning.app.simulation.SimulationState
import com.intelligentdeadreckoning.app.simulation.StopReason
import com.intelligentdeadreckoning.app.simulation.Vector3
import com.intelligentdeadreckoning.app.ui.design.DockItem
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrChip
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrDock
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrIcon
import com.intelligentdeadreckoning.app.ui.design.IdrMeter
import com.intelligentdeadreckoning.app.ui.design.IdrMotion
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrSectionLabel
import com.intelligentdeadreckoning.app.ui.design.IdrShapes
import com.intelligentdeadreckoning.app.ui.design.IdrSize
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.KeyValueRow
import com.intelligentdeadreckoning.app.ui.design.StatusDot
import com.intelligentdeadreckoning.app.map.LiveGnssView
import com.intelligentdeadreckoning.app.map.NO_LIVE_GNSS
import com.intelligentdeadreckoning.app.ui.design.IdrStateTone
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.LocalIdrReducedMotion
import com.intelligentdeadreckoning.app.ui.design.StatePanel
import com.intelligentdeadreckoning.app.ui.design.StatTile
import com.intelligentdeadreckoning.contracts.v1.Sensor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private enum class Screen(val id: String, val title: String, val glyph: IdrGlyph, val testTag: String) {
    DASHBOARD("dashboard", "Home", IdrGlyph.HOME, "tab_DASHBOARD"),
    MAP("map", "Map", IdrGlyph.NAVIGATE, "tab_MAP"),
    DIAGNOSTICS("diagnostics", "Signals", IdrGlyph.PULSE, "tab_DIAGNOSTICS"),
    ABOUT("about", "About", IdrGlyph.INFO, "tab_ABOUT"),
}

private val dockItems = Screen.entries.map { DockItem(it.id, it.title, it.glyph, it.testTag) }

@Composable
fun IdrApp(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit,
           source: InputSource = InputSource.SIMULATION, capture: CaptureState = CaptureState(),
           liveGnss: LiveGnssView = NO_LIVE_GNSS,
           onSource: (InputSource) -> Unit = {}, onPermission: () -> Unit = {}, onSettings: () -> Unit = {},
           recording: RecorderState = RecorderState(), onStartRecording: () -> Unit = {},
           onStopRecording: () -> Unit = {}, library: SessionPage = SessionPage(), libraryError: String? = null,
           currentSession: SavedSession? = null, elapsedNs: Long? = null, export: ExportState = ExportState(),
           onRefreshSessions: (String?) -> Unit = {}, onExport: (String) -> Unit = {},
           replay: ReplayState = ReplayState(), replayVisible: Boolean = false, onReplay: (String) -> Unit = {},
           onPauseReplay: () -> Unit = {}, onResumeReplay: () -> Unit = {}, onStopReplay: () -> Unit = {}) {
    var sessionsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    if (sessionsOpen) SessionDialog(library, libraryError, recording.busy || export.busy || replay.busy, export.message,
        onRefreshSessions, onExport, { sessionsOpen = false }, onReplay)
    var selected by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    BackHandler(enabled = selected != Screen.DASHBOARD) { selected = Screen.DASHBOARD }
    Scaffold(
        containerColor = IdrPalette.background,
        // The dock owns its own inset space instead of floating over content: scrolled controls
        // stay clickable and the navigation surface never occludes the last row of a page.
        bottomBar = {
            IdrDock(
                items = dockItems,
                selectedId = selected.id,
                onSelect = { id -> selected = Screen.entries.first { it.id == id } },
                modifier = Modifier.padding(horizontal = IdrSpace.lg, vertical = IdrSpace.md),
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            AppHeader(source, onSource, replayVisible, replay)
            // Each page scrolls independently, including on small displays / larger font settings.
            key(selected) {
                // The measured page area is what a full-bleed hero may occupy: the window height
                // also counts the header and the dock, which no page ever gets to draw on.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val pageArea = maxHeight - IdrSpace.sm - IdrSpace.xxl
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = IdrSpace.xl)
                            .padding(top = IdrSpace.sm, bottom = IdrSpace.xxl),
                        verticalArrangement = Arrangement.spacedBy(IdrSpace.xl),
                    ) {
                        if (selected != Screen.ABOUT && selected != Screen.MAP) {
                            RecordingPanel(recording, capture, source, replayVisible, replay.busy,
                                onStartRecording, onStopRecording, detailsOpen, { detailsOpen = !detailsOpen },
                                currentSession, elapsedNs, export,
                                { sessionsOpen = true; onRefreshSessions(null) })
                        }
                        if (replayVisible && selected != Screen.ABOUT && selected != Screen.MAP) {
                            EntranceFade { ReplayPanel(replay, onPauseReplay, onResumeReplay, onStopReplay) }
                        } else {
                            when (selected) {
                                Screen.DASHBOARD -> EntranceFade {
                                    if (source == InputSource.REAL) {
                                        RealDashboard(capture, onStart, onStop, onPermission, onSettings)
                                    } else {
                                        Dashboard(state, onStart, onStop)
                                    }
                                }
                                Screen.DIAGNOSTICS -> EntranceFade {
                                    if (source == InputSource.REAL) {
                                        RealDiagnostics(capture, onStart, onStop, onPermission, onSettings)
                                    } else {
                                        Diagnostics(state, onStart, onStop)
                                    }
                                }
                                Screen.MAP -> EntranceFade {
                                    OfflineMapScreen(
                                        pageHeight = pageArea,
                                        source = source,
                                        capture = capture,
                                        liveGnss = liveGnss,
                                        // A live position cannot come from the synthetic source, so
                                        // this selects phone sensors first rather than silently
                                        // starting the scripted demo the user asked to leave.
                                        onStart = {
                                            if (source != InputSource.REAL) onSource(InputSource.REAL)
                                            onStart()
                                        },
                                        onPermission = onPermission,
                                    )
                                }
                                Screen.ABOUT -> EntranceFade { About() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen entrance. Alpha-only, so semantics and hit targets are stable for the whole
 * transition and an automated test clock always reaches idle.
 */
@Composable
private fun EntranceFade(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalIdrReducedMotion.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = IdrMotion.tweenSpec(IdrMotion.pageMs, reduced),
        label = "entrance",
    )
    Box(modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) { content() }
}

// ---------------------------------------------------------------------------------------------
// Header: brand, source-truth banner and the single source selector.
// ---------------------------------------------------------------------------------------------

private data class SourceBanner(val label: String, val detail: String, val tone: IdrTone, val glyph: IdrGlyph)

@Composable
private fun AppHeader(
    source: InputSource,
    onSource: (InputSource) -> Unit,
    replayVisible: Boolean,
    replay: ReplayState,
) {
    val banner = when {
        replayVisible -> SourceBanner(
            // Exact wire value: the replay identity must stay verbatim and machine-checkable.
            replay.source?.wire ?: "REPLAY",
            "Recorded playback · not live", IdrTone.INFO, IdrGlyph.RESET,
        )
        source == InputSource.SIMULATION -> SourceBanner(
            "SIMULATION", "Demo data · not live sensors", IdrTone.WARNING, IdrGlyph.PLAY,
        )
        else -> SourceBanner(
            "REAL PHONE", "Measured · foreground only", IdrTone.ACCENT, IdrGlyph.RECORD,
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = IdrSpace.xl).padding(top = IdrSpace.lg, bottom = IdrSpace.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(IdrPalette.accent, IdrShapes.control),
                contentAlignment = Alignment.Center,
            ) {
                IdrIcon(IdrGlyph.NAVIGATE, tint = IdrPalette.background, size = 21.dp, strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(IdrSpace.md))
            Column(Modifier.weight(1f)) {
                Text("DEAD RECKONING", color = IdrPalette.textPrimary, style = IdrType.brand)
                Text("GNSS-DENIED NAVIGATION · MOTION LAB 01", color = IdrPalette.textMuted, style = IdrType.labelSmall)
            }
        }
        Spacer(Modifier.height(IdrSpace.lg))
        Row(
            Modifier.fillMaxWidth()
                .background(banner.tone.color.copy(alpha = 0.10f), IdrShapes.control)
                .padding(horizontal = IdrSpace.md, vertical = IdrSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IdrIcon(banner.glyph, tint = banner.tone.color, size = 15.dp)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(banner.label, color = banner.tone.color, style = IdrType.label)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(
                banner.detail,
                color = IdrPalette.textSecondary,
                style = IdrType.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(IdrSpace.md))
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrChip("Simulation", selected = source == InputSource.SIMULATION, emphasized = true,
                glyph = IdrGlyph.PLAY, onClick = { onSource(InputSource.SIMULATION) },
                testTag = "source_simulation")
            IdrChip("Phone sensors", selected = source == InputSource.REAL, emphasized = true,
                glyph = IdrGlyph.SATELLITE, onClick = { onSource(InputSource.REAL) },
                testTag = "source_real")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Recording console (unchanged behaviour, restyled surface)
// ---------------------------------------------------------------------------------------------

@Composable
private fun RecordingPanel(
    recording: RecorderState,
    capture: CaptureState,
    source: InputSource,
    replayVisible: Boolean,
    replayBusy: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    detailsOpen: Boolean,
    onToggleDetails: () -> Unit,
    currentSession: SavedSession?,
    elapsedNs: Long?,
    export: ExportState,
    onOpenSessions: () -> Unit,
) {
    IdrCard(emphasis = IdrEmphasis.UTILITY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrSectionLabel("Local recording")
            IdrIcon(
                IdrGlyph.RECORD,
                tint = when (recording.phase) {
                    RecorderPhase.RECORDING -> IdrPalette.danger
                    RecorderPhase.COMPLETED -> IdrPalette.success
                    else -> IdrPalette.textMuted
                },
                size = IdrSize.iconSm,
            )
        }
        Text(
            "Local recording · ${recording.phase.name.lowercase()}",
            color = IdrPalette.textPrimary,
            style = IdrType.titleMedium,
            modifier = Modifier.testTag("recording_status"),
        )
        Text(recording.message, color = IdrPalette.textSecondary, style = IdrType.bodySmall)
        Text(
            "Written ${recording.written} · dropped ${recording.dropped} · write errors ${recording.writeErrors} · ID gaps ${recording.eventIdGaps}",
            color = IdrPalette.textMuted,
            style = IdrType.monoSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrButton(
                "Start recording",
                onStartRecording,
                enabled = !replayVisible && !replayBusy && source == InputSource.REAL && capture.running &&
                    capture.sensors.isNotEmpty() && !recording.busy && !export.busy,
                variant = IdrButtonVariant.PRIMARY,
                glyph = IdrGlyph.RECORD,
                testTag = "recording_start",
                minHeight = IdrSize.touchTarget,
                modifier = Modifier.weight(1f),
            )
            IdrButton(
                "Stop recording",
                onStopRecording,
                enabled = recording.phase in listOf(RecorderPhase.STARTING, RecorderPhase.RECORDING) && recording.recordingId != null,
                variant = IdrButtonVariant.SECONDARY,
                glyph = IdrGlyph.STOP,
                testTag = "recording_stop",
                minHeight = IdrSize.touchTarget,
                modifier = Modifier.weight(1f),
            )
        }
        if (source == InputSource.SIMULATION) {
            Text("Recording requires the Phone sensors acquisition stream.", color = IdrPalette.textMuted, style = IdrType.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrButton("Recording details", onToggleDetails, variant = IdrButtonVariant.GHOST,
                minHeight = IdrSize.touchTarget, glyph = IdrGlyph.SLIDERS)
            IdrButton("Saved sessions", onOpenSessions, variant = IdrButtonVariant.GHOST,
                minHeight = IdrSize.touchTarget, glyph = IdrGlyph.LAYERS, testTag = "saved_sessions")
        }
        if (detailsOpen) {
            Text(
                "Session: ${recording.recordingId ?: "none"}\nSource: ${currentSession?.metadata?.source ?: "unknown"}\n" +
                    "Elapsed: ${elapsedNs?.let { "${it / 1_000_000_000L} s" } ?: "unknown"}\n" +
                    "Private files: ${currentSession?.bytes?.let { "$it bytes (snapshot)" } ?: "unknown"}",
                color = IdrPalette.textMuted,
                style = IdrType.monoSmall,
            )
        }
        if (export.phase != ExportPhase.IDLE) {
            Text("Export copy · ${export.message}", color = IdrPalette.textSecondary, style = IdrType.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------------------------

@Composable
private fun Dashboard(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
            Text("Ready to explore.", style = IdrType.headlineLarge, color = IdrPalette.textPrimary)
            Text("A first look at motion, before the real drive.", color = IdrPalette.textSecondary, style = IdrType.bodyMedium)
        }
        IdrCard(emphasis = IdrEmphasis.PRIMARY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IdrSectionLabel("Motion preview")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (state.isRunning) IdrPalette.accent else IdrPalette.textMuted)
                    Spacer(Modifier.width(IdrSpace.sm))
                    Text(
                        statusLabel(state),
                        color = if (state.isRunning) IdrPalette.accent else IdrPalette.textSecondary,
                        style = IdrType.label,
                        modifier = Modifier.testTag("session_status"),
                    )
                }
            }
            SpeedGauge(state.measurement?.speedKmh)
            IdrDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
                StatTile(
                    "Session time",
                    duration(state.measurement?.elapsedMillis ?: 0),
                    modifier = Modifier.weight(1f),
                    caption = "Elapsed demo clock",
                )
                StatTile(
                    "Demo heading",
                    state.measurement?.let { "${decimal(it.headingDegrees, 0)}°" } ?: "—",
                    modifier = Modifier.weight(1f),
                    caption = "Scripted, not measured",
                )
            }
        }
        SessionControls(state, onStart, onStop)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
            UtilityCard("Data source", "Scripted demo", "No hardware accessed", Modifier.weight(1f), IdrGlyph.PLAY)
            UtilityCard("Navigation", "Not connected", "Core integration later", Modifier.weight(1f), IdrGlyph.NAVIGATE)
        }
        Text(
            "Made for GNSS-challenged journeys. This build previews the interface only; it cannot locate or navigate your vehicle.",
            color = IdrPalette.textMuted, style = IdrType.bodySmall,
        )
    }
}

@Composable
private fun UtilityCard(label: String, value: String, detail: String, modifier: Modifier, glyph: IdrGlyph) {
    IdrCard(modifier, emphasis = IdrEmphasis.UTILITY) {
        IdrIcon(glyph, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
        Text(label.uppercase(), color = IdrPalette.textMuted, style = IdrType.labelSmall)
        Text(value, color = IdrPalette.textPrimary, style = IdrType.titleMedium)
        Text(detail, color = IdrPalette.textMuted, style = IdrType.bodySmall)
    }
}

@Composable
private fun SessionControls(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.md), modifier = Modifier.fillMaxWidth()) {
            IdrButton(
                label = if (state.status == SessionStatus.STOPPED) "Start new demo" else "Start simulation",
                onClick = onStart,
                enabled = !state.isRunning,
                variant = IdrButtonVariant.PRIMARY,
                glyph = IdrGlyph.PLAY,
                testTag = "start_button",
                minHeight = IdrSize.rowHeight,
                modifier = Modifier.weight(1.45f),
            )
            IdrButton(
                label = "Stop",
                onClick = onStop,
                enabled = state.isRunning,
                variant = IdrButtonVariant.SECONDARY,
                glyph = IdrGlyph.STOP,
                testTag = "stop_button",
                minHeight = IdrSize.rowHeight,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            when {
                state.isRunning -> "Demo running · stops when you leave the app."
                state.stopReason == StopReason.BACKGROUND -> "Stopped in background. Start a new demo to continue."
                state.status == SessionStatus.STOPPED -> "Stopped · last values retained. A new demo resets them."
                else -> "No sensors, location access or recording."
            },
            color = IdrPalette.textMuted, style = IdrType.bodySmall,
            modifier = Modifier.testTag("session_message"),
        )
    }
}

@Composable
private fun SpeedGauge(speed: Double?) {
    Box(Modifier.fillMaxWidth().height(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(196.dp)) {
            val inset = 12.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                IdrPalette.surfaceHigh, 140f, 260f, false, Offset(inset, inset), arcSize,
                style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
            )
            val progress = ((speed ?: 0.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
            if (progress > 0) {
                drawArc(
                    IdrPalette.accent, 140f, 260f * progress, false, Offset(inset, inset), arcSize,
                    style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            for (tick in 0..30) {
                val angle = Math.toRadians(140.0 + tick * 260.0 / 30)
                val radius = size.width / 2 - 24.dp.toPx()
                val length = if (tick % 5 == 0) 8.dp.toPx() else 3.5.dp.toPx()
                val tickColor = if (tick % 5 == 0) IdrPalette.borderStrong else IdrPalette.borderSubtle
                drawLine(
                    tickColor,
                    center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat()),
                    center + Offset((cos(angle) * (radius - length)).toFloat(), (sin(angle) * (radius - length)).toFloat()),
                    1.dp.toPx(),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DEMO SPEED", color = IdrPalette.textMuted, style = IdrType.label)
            Text(
                speed?.let { decimal(it, 1) } ?: "—",
                color = IdrPalette.textPrimary,
                style = IdrType.gauge,
                modifier = Modifier
                    .testTag("demo_speed")
                    .semantics {
                        contentDescription = speed?.let {
                            "Demo speed ${decimal(it, 1)} kilometres per hour, scripted, not measured"
                        } ?: "Demo speed, no scripted sample yet"
                    },
            )
            Text("km/h", color = IdrPalette.accent, style = IdrType.titleMedium)
        }
        Text(
            "SCRIPTED · NOT MEASURED", color = IdrPalette.textMuted, style = IdrType.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------------------------

@Composable
private fun Diagnostics(state: SimulationState, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
            Text("Under the hood.", style = IdrType.headlineLarge, color = IdrPalette.textPrimary)
            Text(
                "Scripted values, with explicit units.\nNot a physical sensor model or real IMU data.",
                color = IdrPalette.textSecondary, style = IdrType.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
            UtilityCard("UI demo cadence", "10 Hz", "Not a measured sensor rate", Modifier.weight(1f), IdrGlyph.PULSE)
            UtilityCard("Demo samples", state.sampleCount.toString(), statusLabel(state), Modifier.weight(1f), IdrGlyph.LAYERS)
        }
        SensorCard("Accelerometer", "m/s²", state.measurement?.accelerometer, IdrPalette.accent, 20.0)
        SensorCard("Gyroscope", "rad/s", state.measurement?.gyroscope, IdrPalette.info, 5.0)
        SensorCard("Magnetometer", "µT", state.measurement?.magnetometer, IdrPalette.warning, 100.0)
        StatePanel(
            title = "GNSS & device sensors",
            message = "Not connected in this build. No real position, accuracy, satellite count or sensor accuracy is available.",
            tone = IdrStateTone.EMPTY,
        )
        SessionControls(state, onStart, onStop)
    }
}

@Composable
private fun SensorCard(title: String, unit: String, vector: Vector3?, accent: Color, scale: Double) {
    val magnitude = vector?.let { max(abs(it.x), max(abs(it.y), abs(it.z))) } ?: 0.0
    IdrCard(emphasis = IdrEmphasis.SECONDARY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(accent)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(title, color = IdrPalette.textPrimary, style = IdrType.titleMedium, modifier = Modifier.weight(1f))
            Text(unit, color = IdrPalette.textMuted, style = IdrType.monoSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
            listOf("X" to vector?.x, "Y" to vector?.y, "Z" to vector?.z).forEach { (axis, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IdrSpace.xxs)) {
                    Text(axis, color = IdrPalette.textMuted, style = IdrType.labelSmall)
                    Text(
                        value?.let { decimal(it, 3) } ?: "—",
                        color = if (value == null) IdrPalette.textMuted else IdrPalette.textPrimary,
                        style = IdrType.metricSmall,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "$title $axis: ${value?.let { decimal(it, 3) } ?: "no sample"} $unit, simulated"
                        },
                    )
                }
            }
        }
        IdrMeter(fraction = (magnitude / scale).toFloat(), tone = IdrTone.ACCENT)
        Text(
            if (vector == null) "No scripted sample yet." else "Peak axis amplitude · ${decimal(magnitude, 3)} $unit",
            color = IdrPalette.textMuted, style = IdrType.bodySmall,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Real (measured) dashboard
// ---------------------------------------------------------------------------------------------

@Composable
private fun RealDashboard(
    state: CaptureState,
    start: () -> Unit,
    stop: () -> Unit,
    permission: () -> Unit,
    settings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
            Text("Live measurements", style = IdrType.headlineLarge, color = IdrPalette.textPrimary)
            Text("Foreground phone sensors and permitted location.", color = IdrPalette.textSecondary, style = IdrType.bodyMedium)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
                StatTile(
                    "Location",
                    state.permission.name,
                    modifier = Modifier.weight(1f),
                    tone = if (state.permission.name == "PRECISE") IdrTone.SUCCESS else IdrTone.WARNING,
                    valueStyle = IdrType.titleMedium,
                    valueTag = "location_permission",
                )
                StatTile(
                    "GNSS",
                    state.quality.state.wire,
                    modifier = Modifier.weight(1f),
                    valueStyle = IdrType.titleMedium,
                )
            }
            IdrDivider()
            val availableSensors = Sensor.entries.count { state.sensors[it]?.available == true }
            KeyValueRow("Sensors available", "$availableSensors / ${Sensor.entries.size}", mono = true)
            KeyValueRow("Accepted", "${state.accepted} · dropped ${state.dropped} · invalid ${state.invalid}", mono = true)
            KeyValueRow("Queue", "${state.queueDepth}/256 · peak ${state.queueHighWater}", mono = true)
            IdrMeter(fraction = state.queueHighWater / 256f, tone = IdrTone.INFO)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
            IdrButton("Start sensors", start, enabled = !state.running, variant = IdrButtonVariant.PRIMARY,
                glyph = IdrGlyph.RECORD, testTag = "real_start", minHeight = IdrSize.rowHeight,
                modifier = Modifier.weight(1.4f))
            IdrButton("Stop", stop, enabled = state.running, variant = IdrButtonVariant.SECONDARY,
                glyph = IdrGlyph.STOP, testTag = "real_stop", minHeight = IdrSize.rowHeight,
                modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrButton("Allow location", permission, variant = IdrButtonVariant.SECONDARY,
                glyph = IdrGlyph.LOCATE, testTag = "grant_location")
            IdrButton("App settings", settings, variant = IdrButtonVariant.GHOST, glyph = IdrGlyph.SLIDERS)
        }
        IdrCard(emphasis = IdrEmphasis.UTILITY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IdrIcon(IdrGlyph.PULSE, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
                Spacer(Modifier.width(IdrSpace.sm))
                Text("Detailed diagnostics", color = IdrPalette.textPrimary, style = IdrType.titleMedium)
            }
            Text(
                "Open the Signals tab for per-sensor values, measured rates, timestamps, GNSS fields, queue statistics and diagnostic events.",
                color = IdrPalette.textSecondary, style = IdrType.bodySmall,
            )
        }
        Text(
            "Foreground acquisition only · recording off · navigation not running",
            color = IdrPalette.textMuted, style = IdrType.bodySmall,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

@Composable
private fun About() {
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            Text("Built for the\njourney ahead.", style = IdrType.headlineLarge, color = IdrPalette.textPrimary)
            Text(
                "Intelligent Dead Reckoning\nSIH problem statement #26168",
                color = IdrPalette.textSecondary, style = IdrType.bodyMedium,
            )
        }
        IdrCard(emphasis = IdrEmphasis.PRIMARY) {
            Milestone("01", "App foundation", "Available now",
                "Kotlin + Jetpack Compose, screen navigation and an in-memory demo.", true)
            IdrDivider()
            Milestone("02", "Real-world acquisition", "Available now",
                "Select Phone sensors for foreground IMU and location measurements. Recording remains planned.", true)
            IdrDivider()
            Milestone("03", "Navigation integration", "Future work",
                "A validated shared navigation core, calibration and eventually map display.", false)
        }
        IdrCard(emphasis = IdrEmphasis.SECONDARY) {
            IdrSectionLabel("Private by design")
            Text(
                "Simulation uses scripted values. Phone sensors mode reads IMU and permitted location while this app is visible. Data stays in bounded memory and is never recorded or uploaded. Leaving the app stops acquisition; returning requires Start.",
                color = IdrPalette.textSecondary, style = IdrType.bodyMedium,
            )
        }
        IdrCard(emphasis = IdrEmphasis.SECONDARY) {
            IdrSectionLabel("Scientific boundary")
            Text(
                "No INS, AI or fusion is running here. Dataset frame conventions are not automatically valid for this phone; real-device calibration and core validation remain separate work.",
                color = IdrPalette.textSecondary, style = IdrType.bodyMedium,
            )
        }
        IdrCard(emphasis = IdrEmphasis.UTILITY) {
            Text(
                "ANDROID IS THE MAIN PRODUCT\nPython supports offline research. Edge deployment is secondary.",
                color = IdrPalette.textMuted, style = IdrType.monoSmall,
            )
            Text("FOREGROUND ACQUISITION · NAVIGATION PLANNED", color = IdrPalette.accent, style = IdrType.label)
        }
    }
}

@Composable
private fun Milestone(number: String, title: String, stage: String, description: String, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
        Text(
            number,
            color = if (active) IdrPalette.accent else IdrPalette.textMuted,
            style = IdrType.mono,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
            Text(stage.uppercase(Locale.ROOT), color = if (active) IdrPalette.accent else IdrPalette.textMuted, style = IdrType.labelSmall)
            Text(title, style = IdrType.titleLarge, color = IdrPalette.textPrimary)
            Text(description, color = IdrPalette.textMuted, style = IdrType.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------

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
