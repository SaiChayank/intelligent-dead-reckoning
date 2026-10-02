package com.intelligentdeadreckoning.app.ui

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intelligentdeadreckoning.app.acquisition.CaptureState
import com.intelligentdeadreckoning.app.acquisition.InputSource
import com.intelligentdeadreckoning.app.map.DemoOverlays
import com.intelligentdeadreckoning.app.map.DemoPlayback
import com.intelligentdeadreckoning.app.map.HyderabadMap
import com.intelligentdeadreckoning.app.map.InstalledMap
import com.intelligentdeadreckoning.app.map.LiveGnssView
import com.intelligentdeadreckoning.app.map.MapDemoController
import com.intelligentdeadreckoning.app.map.MapLibreRenderer
import com.intelligentdeadreckoning.app.map.MapPoint
import com.intelligentdeadreckoning.app.map.MapPresentation
import com.intelligentdeadreckoning.app.map.MapRenderer
import com.intelligentdeadreckoning.app.map.MarkerAnimation
import com.intelligentdeadreckoning.app.map.NO_ENGINE_VIEW
import com.intelligentdeadreckoning.app.map.NO_LIVE_GNSS
import com.intelligentdeadreckoning.app.security.SafeSecurityMessages
import com.intelligentdeadreckoning.app.map.OfflineMapPack
import com.intelligentdeadreckoning.app.map.OutageMark
import com.intelligentdeadreckoning.app.map.RecordedSessionMap
import com.intelligentdeadreckoning.app.map.currentOutageSeconds
import com.intelligentdeadreckoning.app.map.forConsoleHidden
import com.intelligentdeadreckoning.app.map.outageMarks
import com.intelligentdeadreckoning.app.navigation.NavigationRuntimeState
import com.intelligentdeadreckoning.app.replay.ReplayReader
import com.intelligentdeadreckoning.app.sessions.SavedSession
import com.intelligentdeadreckoning.app.sessions.SessionFiles
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrChip
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrIcon
import com.intelligentdeadreckoning.app.ui.design.IdrOverlayChip
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrSectionLabel
import com.intelligentdeadreckoning.app.ui.design.IdrShapes
import com.intelligentdeadreckoning.app.ui.design.IdrSize
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.IdrStateTone
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.StatePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.Locale

/**
 * Map-first screen. The offline renderer is the background of a full-width hero, with
 * floating glass chrome for camera control and the synthetic demo console, and the
 * disclosure/limits content placed in the scroll flow beneath it.
 *
 * The renderer is a local MapLibre vector pack: no network, no API key, no routing graph.
 * Every control here drives the real local demo controller or the real camera, never a
 * stand-in for a navigation engine that does not exist yet.
 */

/** What the map is drawing. The synthetic fixture is a UI fixture, a recording is real data from
 * the past, live is raw phone GNSS now, and engine is what the navigation engine published. They
 * are never blended, and the fixture is never shown as though it were the phone's own position. */
private enum class MapMode { SYNTHETIC, RECORDED, LIVE, ENGINE }

@Composable
fun OfflineMapScreen(
    pageHeight: Dp,
    source: InputSource = InputSource.SIMULATION,
    capture: CaptureState = CaptureState(),
    liveGnss: LiveGnssView = NO_LIVE_GNSS,
    engineMap: MapPresentation = NO_ENGINE_VIEW,
    navigation: NavigationRuntimeState = NavigationRuntimeState(),
    evaluation: Boolean = false,
    onEvaluation: (Boolean) -> Unit = {},
    onStart: () -> Unit = {},
    onPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    var pack by remember { mutableStateOf<InstalledMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderer by remember { mutableStateOf<MapRenderer?>(null) }
    var ready by remember { mutableStateOf(false) }
    val demo = remember { MapDemoController() }
    var snapshot by remember { mutableStateOf(demo.state) }
    var overlays by remember { mutableStateOf(DemoOverlays()) }
    var showControls by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(MapMode.SYNTHETIC) }
    var recordedView by remember { mutableStateOf<RecordedView?>(null) }
    var recordedBusy by remember { mutableStateOf(false) }
    var recordedError by remember { mutableStateOf<String?>(null) }
    // Armed by every mode change: the live and engine cameras take the first position they see and
    // are then left alone. Keeping the camera on a moving track belongs to the user, not the
    // screen, and no camera motion ever implies motion nothing published.
    var liveFocusArmed by remember(mode) { mutableStateOf(true) }
    var engineFocusArmed by remember(mode) { mutableStateOf(true) }
    // Presentation-only easing of the engine marker: the drawn marker interpolates between two
    // published positions and holds at the newest one. Published state is never modified — the
    // console values, the trail and the status stay exactly what the engine published — and when
    // published state expires the marker is gone rather than extrapolated.
    val marker = remember { MarkerAnimation() }
    var easedEnginePoint by remember { mutableStateOf<MapPoint?>(null) }
    fun update(action: () -> Unit) { action(); snapshot = demo.state }
    // The overlay switches live in the synthetic console, which is on screen only for the fixture.
    // A switch the user can no longer see or reach must not decide what real data draws.
    fun drawnOverlays() = if (mode == MapMode.SYNTHETIC) overlays else overlays.forConsoleHidden()
    fun shownPresentation() = when (mode) {
        MapMode.RECORDED -> recordedView?.presentation ?: NO_RECORDED_VIEW
        MapMode.LIVE -> liveGnss.presentation
        MapMode.SYNTHETIC -> snapshot.presentation
        // Only the drawn point is eased, and only between points the engine really published. An
        // expired (null) published point stays nothing: the marker does not linger.
        MapMode.ENGINE -> if (engineMap.point == null) engineMap
        else engineMap.copy(point = easedEnginePoint ?: engineMap.point)
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                update { demo.stop() }
                // This observer is registered once per lifecycle owner, so the state it reads
                // here is the current one, not the one captured when it was created.
                renderer?.present(shownPresentation(), drawnOverlays())
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(snapshot.playback) {
        while (demo.state.playback == DemoPlayback.RUNNING) {
            snapshot = demo.tick(SystemClock.elapsedRealtimeNanos())
            delay(50)
        }
    }
    // Recorded mode streams one saved session on the I/O dispatcher and draws exactly what it
    // contains: no propagation, dead reckoning, fusion, road matching or routing is applied.
    LaunchedEffect(mode) {
        if (mode != MapMode.RECORDED) {
            recordedView = null
            recordedError = null
            return@LaunchedEffect
        }
        recordedBusy = true
        recordedError = null
        try {
            recordedView = withContext(Dispatchers.IO) { loadRecordedView(context) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A bad recording is a source-local failure: report it in this panel, do not crash
            // the map or replace it with a partial trail. Live GNSS and engine views are separate.
            recordedView = null
            recordedError = "Recording refused (${SafeSecurityMessages.code(error, "INVALID_RECORDING")}). No data was changed."
        } finally {
            recordedBusy = false
        }
    }
    val shown = shownPresentation()
    val drawn = drawnOverlays()
    LaunchedEffect(shown, drawn, renderer) { renderer?.present(shown, drawn) }
    // A recording the user cannot see has not been shown. The camera is the one thing this screen
    // moves on its own, and it moves only onto fixes the recording really contains.
    LaunchedEffect(mode, recordedView, renderer) {
        val points = recordedView?.presentation?.trail.orEmpty()
        if (points.isNotEmpty()) renderer?.frame(points)
    }
    // Live GNSS gets the camera once, on the first fix, and only onto a position the phone actually
    // reported. No propagation is involved, and no fix is invented to keep the view moving.
    LaunchedEffect(mode, liveGnss.presentation.point, renderer) {
        val point = liveGnss.presentation.point
        if (mode == MapMode.LIVE && liveFocusArmed && point != null) {
            renderer?.focus(point)
            liveFocusArmed = false
        }
    }
    // Engine output gets the camera once, onto the first published position, and never again.
    LaunchedEffect(mode, engineMap.point, renderer) {
        val point = engineMap.point
        if (mode == MapMode.ENGINE && engineFocusArmed && point != null) {
            renderer?.focus(point)
            engineFocusArmed = false
        }
    }
    // Each published engine position eases the marker from where it currently is. The loop ends the
    // moment the ease settles, so a stationary or stopped engine costs no work and the marker holds
    // exactly on the last published point instead of drifting past it.
    LaunchedEffect(mode, engineMap.point) {
        if (mode != MapMode.ENGINE) {
            marker.reset()
            easedEnginePoint = null
            return@LaunchedEffect
        }
        marker.publish(engineMap.point, SystemClock.elapsedRealtimeNanos())
        if (engineMap.point == null) {
            easedEnginePoint = null
            return@LaunchedEffect
        }
        while (true) {
            val now = SystemClock.elapsedRealtimeNanos()
            easedEnginePoint = marker.display(now)
            if (marker.settled(now)) break
            delay(16)
        }
    }
    LaunchedEffect(Unit) {
        try { pack = withContext(Dispatchers.IO) { OfflineMapPack(context.applicationContext).install() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Offline map unavailable (${SafeSecurityMessages.code(e, "MAP_PACK_REJECTED")})." }
    }

    // The hero must fit the visible page area. 70% of the window is the intended proportion, but
    // the header, the dock and the page's own vertical padding leave a page less room than the
    // window has, and a hero taller than its page clips the console and the attribution that
    // float on its bottom edge.
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val heroHeight = minOf((windowHeight * 0.70f).coerceAtLeast(480.dp), pageHeight.coerceAtLeast(280.dp))
    val active = snapshot.playback in listOf(DemoPlayback.RUNNING, DemoPlayback.PAUSED)

    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = heroHeight)
                .clip(IdrShapes.cardLarge)
                .background(IdrPalette.surface),
        ) {
            // ---- background layer: the real offline renderer, or its load/failure state ----
            when {
                error != null -> Box(Modifier.matchParentSize().padding(IdrSpace.xxl), contentAlignment = Alignment.Center) {
                    StatePanel(
                        title = "Offline map unavailable",
                        message = "$error — no network fallback is attempted and no tiles are fetched.",
                        tone = IdrStateTone.ERROR,
                        actionLabel = null,
                        onAction = null,
                        testTag = "map_error",
                    )
                }
                pack == null -> Box(Modifier.matchParentSize().padding(IdrSpace.xxl), contentAlignment = Alignment.Center) {
                    StatePanel(
                        title = "Preparing offline map",
                        message = "Verifying bundled Hyderabad vector tiles in private storage.",
                        tone = IdrStateTone.LOADING,
                        testTag = "map_loading",
                    )
                }
                else -> Box(Modifier.matchParentSize()) {
                    NativeOfflineMap(pack!!, { renderer = it; ready = true }, {
                        ready = false
                        update { demo.stop() }
                        renderer = null
                        error = it
                    })
                    // Interactive-surface marker for the open upper map area. The renderer fills
                    // the whole hero, but pan/rotate gestures are addressed here so they can
                    // never land on the floating console on a short display.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.6f)
                            .semantics { contentDescription = "Interactive offline map of central Hyderabad. Pan, rotate and zoom." }
                            .testTag("offline_map"),
                    )
                }
            }

            // ---- floating chrome layer ----
            // Deliberately not matchParentSize: when the console needs more room than the
            // minimum, this column is what sizes the hero, so the chrome can never be clipped
            // away by its own background box.
            Column(
                Modifier.fillMaxWidth().heightIn(min = heroHeight).padding(IdrSpace.md),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm),
                        verticalArrangement = Arrangement.spacedBy(IdrSpace.sm),
                    ) {
                        IdrChip(
                            label = "Synthetic demo",
                            selected = mode == MapMode.SYNTHETIC,
                            onClick = { mode = MapMode.SYNTHETIC },
                            enabled = !recordedBusy,
                            glyph = IdrGlyph.SATELLITE,
                            testTag = "map_source_synthetic",
                        )
                        IdrChip(
                            label = "Recorded session",
                            selected = mode == MapMode.RECORDED,
                            onClick = { update { demo.stop() }; mode = MapMode.RECORDED },
                            enabled = !recordedBusy,
                            glyph = IdrGlyph.LAYERS,
                            testTag = "map_source_recorded",
                        )
                        IdrChip(
                            label = "Live GNSS",
                            selected = mode == MapMode.LIVE,
                            onClick = { update { demo.stop() }; mode = MapMode.LIVE },
                            enabled = !recordedBusy,
                            glyph = IdrGlyph.LOCATE,
                            testTag = "map_source_live",
                        )
                        IdrChip(
                            label = "Navigation engine",
                            selected = mode == MapMode.ENGINE,
                            onClick = { update { demo.stop() }; mode = MapMode.ENGINE },
                            enabled = !recordedBusy,
                            glyph = IdrGlyph.NAVIGATE,
                            testTag = "map_source_engine",
                        )
                    }
                    IdrOverlayChip(
                        text = when (mode) {
                            MapMode.LIVE -> "Live GNSS only · no dead reckoning, fusion or routing in this view"
                            MapMode.ENGINE -> "Navigation engine output · published fused state, not raw sensor estimates"
                            else -> "Preview only · no live position, DR, routing or navigation"
                        },
                        tone = when (mode) {
                            MapMode.LIVE -> IdrTone.INFO
                            MapMode.ENGINE -> IdrTone.ACCENT
                            else -> IdrTone.NEUTRAL
                        },
                        glyph = if (mode == MapMode.LIVE) IdrGlyph.SATELLITE else IdrGlyph.NAVIGATE,
                        testTag = "map_mode",
                    )
                    IdrOverlayChip(
                        text = when (mode) {
                            MapMode.RECORDED -> "MAP SOURCE: RECORDED SESSION — raw GNSS fixes as recorded, no fusion or DR"
                            MapMode.LIVE -> "MAP SOURCE: LIVE PHONE GNSS — raw fixes as reported, no fusion or DR"
                            MapMode.ENGINE -> "MAP SOURCE: NAVIGATION ENGINE — fused output as published NavigationState"
                            MapMode.SYNTHETIC -> "MAP SOURCE: SYNTHETIC UI FIXTURE — independent of acquisition"
                        },
                        tone = if (mode == MapMode.SYNTHETIC) IdrTone.WARNING else IdrTone.INFO,
                        glyph = if (mode == MapMode.SYNTHETIC) IdrGlyph.WARNING else IdrGlyph.LAYERS,
                        testTag = "map_source",
                    )
                    if (error == null) {
                        IdrOverlayChip(
                            text = if (ready && pack != null) "Offline map loaded · ${pack!!.tiles} tiles"
                            else "Loading local style…",
                            tone = if (ready) IdrTone.ACCENT else IdrTone.INFO,
                            glyph = if (ready) IdrGlyph.CHECK else IdrGlyph.SATELLITE,
                            testTag = "map_status",
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                    if (mode == MapMode.SYNTHETIC) IdrCard(emphasis = IdrEmphasis.GLASS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(6.dp)
                                    .height(6.dp)
                                    .background(
                                        when (snapshot.playback) {
                                            DemoPlayback.RUNNING -> IdrPalette.accent
                                            DemoPlayback.PAUSED -> IdrPalette.warning
                                            else -> IdrPalette.textMuted
                                        },
                                        IdrShapes.pill,
                                    ),
                            )
                            Spacer(Modifier.width(IdrSpace.sm))
                            Text(
                                snapshot.label,
                                color = IdrPalette.textPrimary,
                                style = IdrType.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).testTag("map_demo_status"),
                            )
                        }
                        Text(
                            "${snapshot.playback} · ${snapshot.rate}× playback · ${snapshot.elapsedMs / 1000}s / 30s",
                            color = IdrPalette.textSecondary,
                            style = IdrType.monoSmall,
                            maxLines = 1,
                            modifier = Modifier.testTag("demo_clock"),
                        )
                        if (snapshot.playback != DemoPlayback.IDLE) {
                            Text(
                                "SYNTHETIC HUD · distance ${
                                    String.format(Locale.ROOT, "%.1f", snapshot.distanceM)
                                } m · outage total ${snapshot.outageMs / 1000.0}s / ${
                                    String.format(Locale.ROOT, "%.1f", snapshot.outageDistanceM)
                                } m · current ${snapshot.currentOutageMs / 1000.0}s · heading ${
                                    snapshot.presentation.headingDegrees?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
                                }° · speed ${
                                    snapshot.presentation.speedMetresPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
                                } m/s",
                                color = IdrPalette.textMuted,
                                style = IdrType.monoSmall,
                                modifier = Modifier.testTag("demo_stats"),
                            )
                        }
                        // The demo's own scripted losses, over its own clock. Same bar, same rule;
                        // only the window it is drawn over is different.
                        if (snapshot.elapsedMs > 0) {
                            OutageTimeline(
                                snapshot.elapsedMs * 1_000_000L,
                                snapshot.outageMarks(),
                                Modifier.testTag("demo_timeline"),
                                "Scripted GNSS availability: lime marks a scripted fix and amber a scripted loss.",
                            )
                            Text(
                                "Scripted GNSS availability over the demo clock so far: lime is a " +
                                    "scripted fix and amber a scripted loss. Phone GNSS is untouched.",
                                color = IdrPalette.textMuted,
                                style = IdrType.bodySmall,
                                modifier = Modifier.testTag("demo_timeline_label"),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                            IdrButton(
                                "Start demo",
                                onClick = {
                                    update { demo.start(SystemClock.elapsedRealtimeNanos()) }
                                    snapshot.presentation.point?.let { renderer?.focus(it) }
                                },
                                enabled = ready && !active,
                                variant = IdrButtonVariant.PRIMARY,
                                glyph = IdrGlyph.PLAY,
                                testTag = "map_demo_start",
                                minHeight = IdrSize.touchTarget,
                                modifier = Modifier.weight(1.4f),
                            )
                            IdrButton(
                                "Stop demo",
                                onClick = { update { demo.stop() } },
                                enabled = active,
                                variant = IdrButtonVariant.SECONDARY,
                                glyph = IdrGlyph.STOP,
                                testTag = "map_demo_stop",
                                minHeight = IdrSize.touchTarget,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                            IdrChip(
                                label = if (snapshot.playback == DemoPlayback.PAUSED) "Resume" else "Pause",
                                selected = snapshot.playback == DemoPlayback.PAUSED,
                                onClick = {
                                    update {
                                        if (snapshot.playback == DemoPlayback.PAUSED) {
                                            demo.resume(SystemClock.elapsedRealtimeNanos())
                                        } else {
                                            demo.pause(SystemClock.elapsedRealtimeNanos())
                                        }
                                    }
                                },
                                enabled = active,
                                glyph = if (snapshot.playback == DemoPlayback.PAUSED) IdrGlyph.PLAY else IdrGlyph.PAUSE,
                                testTag = "demo_pause",
                            )
                            IdrChip(
                                label = "Reset",
                                selected = false,
                                onClick = { update { demo.reset() } },
                                glyph = IdrGlyph.RESET,
                                testTag = "demo_reset",
                            )
                            IdrChip(
                                label = if (showControls) "Hide options" else "Options",
                                selected = showControls,
                                onClick = { showControls = !showControls },
                                glyph = IdrGlyph.SLIDERS,
                                testTag = "demo_options",
                            )
                        }
                        // Compact camera cluster. Labels are the real control names, not icons alone.
                        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                            IdrChip("Hyderabad", selected = false, onClick = { renderer?.recenter() }, enabled = ready,
                                glyph = IdrGlyph.LOCATE)
                            IdrChip("Zoom +", selected = false, onClick = { renderer?.zoomBy(1.0) }, enabled = ready,
                                glyph = IdrGlyph.PLUS)
                            IdrChip("Zoom −", selected = false, onClick = { renderer?.zoomBy(-1.0) }, enabled = ready,
                                glyph = IdrGlyph.MINUS)
                            IdrChip("North", selected = false, onClick = { renderer?.northUp() }, enabled = ready,
                                glyph = IdrGlyph.ARROW_NORTH)
                        }
                    }
                    if (mode == MapMode.RECORDED) RecordedPanel(recordedView, recordedBusy, recordedError, renderer, ready)
                    if (mode == MapMode.LIVE) LivePanel(capture, source, liveGnss, renderer, ready, onStart, onPermission)
                    if (mode == MapMode.ENGINE) EnginePanel(
                        capture, navigation, engineMap, evaluation, onEvaluation, renderer, ready,
                        onStart, onPermission,
                    )
                    Text(
                        "OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors · openstreetmap.org/copyright",
                        color = IdrPalette.textSecondary,
                        style = IdrType.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(IdrShapes.chip)
                            .background(IdrPalette.glass)
                            .padding(horizontal = IdrSpace.sm, vertical = 3.dp)
                            .testTag("map_attribution"),
                    )
                }
            }
        }

        if (showControls && mode == MapMode.SYNTHETIC) {
            MapDemoControls(
                snapshot, overlays,
                onScenario = { update { demo.select(it) } },
                onRate = { update { demo.rate(it, SystemClock.elapsedRealtimeNanos()) } },
                onSignal = { update { demo.signal(it, SystemClock.elapsedRealtimeNanos()) } },
                onOverlays = { overlays = it },
            )
        }

        MapLimitsCard()
    }
}

/** What the recorded-session reader produced, so the UI can state it instead of implying more. */
private data class RecordedView(
    val sessionId: String,
    val presentation: MapPresentation,
    val stats: RecordedSessionMap.Stats,
    val scanned: Int,
    val truncated: Boolean,
)

/** Shown while recorded mode has nothing loaded yet: a real source with no fix drawn. */
private val NO_RECORDED_VIEW = RecordedSessionMap().snapshot()

/** Reads one saved recording off the main thread. Chooses the recording with the most records. */
private fun loadRecordedView(context: Context, maxScan: Int = 200_000): RecordedView? {
    val files = SessionFiles { File(context.noBackupFilesDir, "recordings") }
    val candidates = ArrayList<SavedSession>()
    var after: String? = null
    do {
        val page = files.list(after)
        candidates += page.sessions.filter { it.replayable }
        after = page.next
    } while (after != null && candidates.size < 64)
    val chosen = candidates.maxWithOrNull(compareBy({ it.metadata?.recordCount ?: 0L }, { it.id })) ?: return null
    val (metadata, input) = files.openReplay(chosen.id)
    val map = RecordedSessionMap()
    var scanned = 0
    input.use { stream ->
        val reader = ReplayReader(stream, metadata)
        while (scanned < maxScan) {
            map.accept(reader.next() ?: break)
            scanned++
        }
    }
    return RecordedView(chosen.id, map.snapshot(), map.stats(), scanned, scanned >= maxScan)
}

/** What the live phone stream is actually reporting, and what this screen is not doing with it. */
@Composable
private fun LivePanel(
    capture: CaptureState,
    source: InputSource,
    live: LiveGnssView,
    renderer: MapRenderer?,
    ready: Boolean,
    onStart: () -> Unit,
    onPermission: () -> Unit,
) {
    val quality = capture.quality
    val running = source == InputSource.REAL && capture.running
    val point = live.presentation.point
    IdrCard(emphasis = IdrEmphasis.GLASS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.LOCATE, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(
                when {
                    !running -> "Live position is off"
                    point == null -> "Waiting for the first fix"
                    else -> "Live GNSS · ${quality.state.wire}"
                },
                color = IdrPalette.textPrimary,
                style = IdrType.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("live_status"),
            )
        }
        if (running) {
            Text(
                "GNSS quality ${quality.state.wire}" +
                    (quality.fix_age_s?.let { " · fix age ${String.format(Locale.ROOT, "%.1f", it)} s" }
                        ?: " · no fix yet") +
                    (quality.satellites_used?.let { " · $it satellites" } ?: "") +
                    (quality.reasons.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString()}" } ?: ""),
                color = IdrPalette.textSecondary,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("live_quality"),
            )
            Text(
                "${live.stats.fixes} fixes · ${live.stats.lines} segments · ${live.stats.points} isolated · " +
                    "${live.stats.gaps} gaps · span ${live.stats.spanNs / 1_000_000_000}s" +
                    (live.stats.outsideCoverage.takeIf { it > 0 }?.let { " · $it outside coverage" } ?: "") +
                    (live.stats.malformed.takeIf { it > 0 }?.let { " · $it malformed" } ?: ""),
                color = IdrPalette.textSecondary,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("live_stats"),
            )
            val currentLoss = currentOutageSeconds(quality.fix_age_s)
            Text(
                "GNSS loss · longest " +
                    String.format(Locale.ROOT, "%.0f", live.stats.longestGapNs / 1_000_000_000.0) + " s" +
                    (currentLoss?.let { " · no current fix for ${String.format(Locale.ROOT, "%.1f", it)} s" }
                        ?: " · fix is current"),
                color = if (currentLoss == null) IdrPalette.textSecondary else IdrPalette.warning,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("live_outages"),
            )
            // Drawn from the window rather than from the losses: a window the fixes did cover
            // reads as an unbroken bar even when nothing was lost.
            OutageTimeline(
                live.stats.spanNs,
                live.stats.outageMarks(),
                Modifier.testTag("live_timeline"),
                "Raw GNSS availability: lime marks a reported fix and amber a loss.",
            )
        }
        Text(
            if (running)
                "The phone's own raw GNSS fixes, drawn exactly as the platform reported them. No dead " +
                    "reckoning, fusion, correction, road matching or routing runs yet, so when GNSS is " +
                    "unavailable this marker stops: nothing is drawn in its place and no position is " +
                    "estimated. Providers: ${live.stats.providers.joinToString().ifEmpty { "none" }}" +
                    (live.stats.lastFixRadiusMetres?.let {
                        " · last reported fix radius ${String.format(Locale.ROOT, "%.0f", it)} m (68%)"
                    } ?: "")
            else
                "Start phone sensors to see your own position here. Nothing is drawn until the phone " +
                    "reports a fix, and location permission is required for any position at all.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
            modifier = Modifier.testTag("live_disclaimer"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            if (running) {
                IdrChip(
                    label = "My position",
                    selected = false,
                    onClick = { point?.let { renderer?.focus(it) } },
                    enabled = ready && point != null,
                    glyph = IdrGlyph.LOCATE,
                    testTag = "live_focus",
                )
                IdrChip("Zoom +", selected = false, onClick = { renderer?.zoomBy(1.0) }, enabled = ready,
                    glyph = IdrGlyph.PLUS)
                IdrChip("Zoom −", selected = false, onClick = { renderer?.zoomBy(-1.0) }, enabled = ready,
                    glyph = IdrGlyph.MINUS)
            } else {
                IdrChip("Allow location", selected = false, onClick = onPermission, glyph = IdrGlyph.LOCATE,
                    testTag = "live_permission")
                IdrChip("Start sensors", selected = false, onClick = onStart, glyph = IdrGlyph.SATELLITE,
                    testTag = "live_start")
            }
        }
    }
}

/**
 * What the navigation engine published, and what this screen did with it.
 *
 * Every value shown is a field the engine itself published: position, heading, speed and the
 * travelled trail come from `NavigationState`, the confidence radius and speed sigma from the
 * `Confidence` record paired with it — labelled calibrated only when that record's state is
 * `CALIBRATED`, and otherwise as the UNVALIDATED model covariance it is — and the localization
 * mode from the 1.1.0 `localization_mode` field. A value the engine did not publish stays `—`, and the heading legitimately stays `—`
 * until a calibration record supplies the vehicle attitude. GNSS quality is the acquisition
 * stream's own report and is labelled as acquisition, not engine, output: the engine publishes no
 * quality record yet and this screen does not invent one. The evaluation toggle adds the
 * map-matched claim beside the raw position — a parallel evaluation output, never a replacement
 * for navigation truth.
 */
@Composable
private fun EnginePanel(
    capture: CaptureState,
    navigation: NavigationRuntimeState,
    engine: MapPresentation,
    evaluation: Boolean,
    onEvaluation: (Boolean) -> Unit,
    renderer: MapRenderer?,
    ready: Boolean,
    onStart: () -> Unit,
    onPermission: () -> Unit,
) {
    val quality = capture.quality
    val point = engine.point
    val mode = engine.localizationMode?.uppercase(Locale.ROOT) ?: "—"
    val matched = engine.matchedPoint
    val trail = engine.trail.size
    IdrCard(emphasis = IdrEmphasis.GLASS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.NAVIGATE, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(
                if (point == null) "No engine position" else "Fused navigation · $mode",
                color = IdrPalette.textPrimary,
                style = IdrType.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("engine_status"),
            )
        }
        Text(
            "Navigation ${navigation.engineStatus.wire} · session ${navigation.phase.name.lowercase(Locale.ROOT)} · " +
                "accepted ${navigation.acceptedImu + navigation.acceptedGnss} · rejected ${navigation.rejected}",
            color = IdrPalette.textSecondary,
            style = IdrType.monoSmall,
            modifier = Modifier.testTag("engine_runtime"),
        )
        Text(
            navigation.message,
            color = IdrPalette.textSecondary,
            style = IdrType.bodySmall,
            modifier = Modifier.testTag("engine_message"),
        )
        Text(
            "Position " + (point?.let { String.format(Locale.ROOT, "%.5f, %.5f", it.latitude, it.longitude) } ?: "—") +
                " · heading " + (engine.headingDegrees?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—") +
                "° · speed " + (engine.speedMetresPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—") +
                " m/s · trail $trail ${if (trail == 1) "point" else "points"}",
            color = IdrPalette.textSecondary,
            style = IdrType.monoSmall,
            modifier = Modifier.testTag("engine_values"),
        )
        Text(
            "Confidence radius " + engineConfidenceText(engine),
            color = IdrPalette.textSecondary,
            style = IdrType.monoSmall,
            modifier = Modifier.testTag("engine_confidence"),
        )
        Text(
            "Published status ${engine.status} · localization mode $mode · acquisition GNSS quality ${quality.state.wire}" +
                (quality.fix_age_s?.let { " · fix age ${String.format(Locale.ROOT, "%.1f", it)} s" } ?: " · no fix yet"),
            color = IdrPalette.textSecondary,
            style = IdrType.monoSmall,
            modifier = Modifier.testTag("engine_quality"),
        )
        IdrChip(
            label = if (evaluation) "Map matching on" else "Map matching off",
            selected = evaluation,
            onClick = { onEvaluation(!evaluation) },
            glyph = IdrGlyph.LAYERS,
            testTag = "map_evaluation",
        )
        Text(
            when {
                !evaluation ->
                    "Evaluation overlay off: the position and trail drawn are exactly what the engine published."
                engine.matchingIssue != null -> "Evaluation overlay unavailable: ${engine.matchingIssue}"
                matched == null ->
                    "Evaluation overlay on: no map-matched claim for the latest fix yet" +
                        (engine.matchConfidence?.let {
                            " · last match confidence ${String.format(Locale.ROOT, "%.2f", it)}"
                        } ?: "")
                else ->
                    "Evaluation overlay on: ${engine.matchedTrail.size} matched positions on " +
                        (engine.matchedEdgeId ?: "an unnamed edge") + " · confidence " +
                        (engine.matchConfidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—") +
                        " · " + (engine.matcherVersion ?: "matcher") +
                        " · the raw position is still the navigation truth"
            },
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
            modifier = Modifier.testTag("engine_evaluation"),
        )
        Text(
            "Navigation engine output only: this screen reads no sensor and estimates nothing. A stream that " +
                "stops publishing leaves the screen within three seconds rather than being held or extrapolated, " +
                "and the heading stays — until a calibration record supplies the vehicle attitude. The confidence " +
                "radius is the engine's own covariance: it is labelled calibrated only when the engine's confidence " +
                "state says CALIBRATED, and it stays UNVALIDATED — a model claim, drawn as a dashed ring — until an " +
                "independent reference has shown it matches real error.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
            modifier = Modifier.testTag("engine_disclaimer"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrChip(
                label = "My position",
                selected = false,
                onClick = { point?.let { renderer?.focus(it) } },
                enabled = ready && point != null,
                glyph = IdrGlyph.LOCATE,
                testTag = "engine_focus",
            )
            IdrChip(
                label = "Fit trail",
                selected = false,
                onClick = { engine.trail.takeIf { it.size >= 2 }?.let { renderer?.frame(it) } },
                enabled = ready && trail >= 2,
                glyph = IdrGlyph.LAYERS,
                testTag = "engine_fit",
            )
            IdrChip("Zoom +", selected = false, onClick = { renderer?.zoomBy(1.0) }, enabled = ready,
                glyph = IdrGlyph.PLUS)
            IdrChip("Zoom −", selected = false, onClick = { renderer?.zoomBy(-1.0) }, enabled = ready,
                glyph = IdrGlyph.MINUS)
        }
        if (!capture.running) {
            Text(
                "The engine session follows the phone's measured stream: start sensors to give it something to publish.",
                color = IdrPalette.textMuted,
                style = IdrType.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                IdrChip("Allow location", selected = false, onClick = onPermission, glyph = IdrGlyph.LOCATE,
                    testTag = "engine_permission")
                IdrChip("Start sensors", selected = false, onClick = onStart, glyph = IdrGlyph.SATELLITE,
                    testTag = "engine_start")
            }
        }
    }
}

/**
 * The confidence line, stated in the terms the engine has actually earned.
 *
 * A calibrated 95% accuracy is named calibrated. The engine's own covariance while its paired
 * confidence state is `UNVALIDATED` says so and is never rounded up into a 95% accuracy claim,
 * and `probability` is never read: the contract forbids it unless the state is calibrated, so a
 * consumer here cannot manufacture a probability the engine did not publish. The platform's fix
 * accuracy is not a candidate at all — it belongs to a different record and this line never reads
 * it, so a provider figure can never be substituted for fused confidence.
 */
private fun engineConfidenceText(engine: MapPresentation): String {
    val speedStd = engine.speedStdMetresPerSecond
        ?.let { String.format(Locale.ROOT, " · speed σ %.2f m/s", it) } ?: ""
    val calibrated = engine.accuracy95Metres
    val unvalidated = engine.unvalidatedAccuracy95Metres
    return when {
        calibrated != null -> String.format(Locale.ROOT, "%.0f m (95%, CALIBRATED)", calibrated) + speedStd
        unvalidated != null -> String.format(Locale.ROOT, "%.0f m", unvalidated) +
            " (filter covariance, UNVALIDATED — not a calibrated accuracy)" + speedStd
        engine.confidenceState != null ->
            "not published · paired confidence state ${engine.confidenceState.uppercase(Locale.ROOT)}"
        else -> "not published · no paired confidence record"
    }
}

/** Honest summary of the recorded session on the map. Nothing shown here is inferred. */
@Composable
private fun RecordedPanel(view: RecordedView?, busy: Boolean, error: String?, renderer: MapRenderer?, ready: Boolean) {
    IdrCard(emphasis = IdrEmphasis.GLASS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.LAYERS, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(
                when {
                    busy -> "Reading saved session…"
                    error != null -> "Recording unavailable"
                    view == null -> "No replayable recording on this device"
                    else -> "Recorded session ${view.sessionId.take(8)}"
                },
                color = IdrPalette.textPrimary,
                style = IdrType.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("recorded_status"),
            )
        }
        if (error != null) {
            Text(error, color = IdrPalette.warning, style = IdrType.bodySmall, modifier = Modifier.testTag("recorded_error"))
        }
        if (view != null) {
            Text(
                "${view.stats.fixes} fixes · ${view.stats.lines} segments · ${view.stats.points} isolated · " +
                    "${view.stats.gaps} gaps · longest ${view.stats.longestGapNs / 1_000_000_000}s · span ${view.stats.spanNs / 1_000_000_000}s" +
                    (view.stats.outsideCoverage.takeIf { it > 0 }?.let { " · $it outside coverage" } ?: "") +
                    (view.stats.malformed.takeIf { it > 0 }?.let { " · $it malformed" } ?: "") +
                    (if (view.truncated) " · first ${view.scanned} records" else ""),
                color = IdrPalette.textSecondary,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("recorded_stats"),
            )
            // The same intervals that split the trail, drawn over the session's own observed window.
            OutageTimeline(
                view.stats.spanNs,
                view.stats.outageMarks(),
                Modifier.testTag("recorded_timeline"),
                "Recorded raw GNSS availability: lime marks a recorded fix and amber a loss.",
            )
            Text(
                "Raw GNSS fixes exactly as recorded. No dead reckoning, fusion, road matching, routing or " +
                    "live position exists yet, so no DR or comparison line is drawn, and the trail breaks " +
                    "wherever the recording has no fix. Providers: ${view.stats.providers.joinToString().ifEmpty { "none" }}" +
                    (view.stats.lastFixRadiusMetres?.let {
                        " · last reported fix radius ${String.format(Locale.ROOT, "%.0f", it)} m (68%)"
                    } ?: ""),
                color = IdrPalette.textMuted,
                style = IdrType.bodySmall,
                modifier = Modifier.testTag("recorded_disclaimer"),
            )
            // The camera is not part of the recording, but it still has to be reachable: a
            // recording drawn off-screen is a recording the user cannot check.
            Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                IdrChip(
                    label = "Fit recording",
                    selected = false,
                    onClick = { view.presentation.trail.takeIf { it.isNotEmpty() }?.let { renderer?.frame(it) } },
                    enabled = ready && view.presentation.trail.isNotEmpty(),
                    glyph = IdrGlyph.LOCATE,
                    testTag = "recorded_fit",
                )
                IdrChip("Zoom +", selected = false, onClick = { renderer?.zoomBy(1.0) }, enabled = ready,
                    glyph = IdrGlyph.PLUS)
                IdrChip("Zoom −", selected = false, onClick = { renderer?.zoomBy(-1.0) }, enabled = ready,
                    glyph = IdrGlyph.MINUS)
            }
        }
    }
}

/** The GNSS loss history over an observed window. Lime is a fix and amber an interval with none.
 *
 * Drawn from the window rather than from the losses, so an unbroken lime bar means a fix was there
 * for the whole window, and no bar at all means there is no window to draw over yet — never a window
 * whose history is unknown. A window that is only now beginning therefore stays blank instead of
 * pretending to be continuous. Time only: a loss has no distance to report and none is drawn. */
@Composable
private fun OutageTimeline(
    spanNs: Long,
    marks: List<OutageMark>,
    modifier: Modifier = Modifier,
    description: String = "GNSS availability: lime marks a fix and amber a loss.",
) {
    if (spanNs <= 0L) return
    // Data visualisation: spoken as one sentence instead of an unlabelled bar.
    Canvas(modifier.semantics { contentDescription = description }.fillMaxWidth().height(10.dp).clip(IdrShapes.pill)) {
        drawRect(color = IdrPalette.accent)
        marks.forEach { mark ->
            drawRect(
                color = IdrPalette.warning,
                topLeft = Offset(mark.startFraction * size.width, 0f),
                // A loss far shorter than the window is drawn thin rather than rounded up into a
                // wide block: it has to stay visible, and it has to stay honest about its length.
                size = Size(
                    ((mark.endFraction - mark.startFraction) * size.width).coerceAtLeast(2f),
                    size.height,
                ),
            )
        }
    }
}

@Composable
private fun MapLimitsCard() {
    IdrCard(emphasis = IdrEmphasis.UTILITY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.LAYERS, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            IdrSectionLabel("What this map is")
        }
        Text(                "Bundled central Hyderabad vector tiles only: 17.30–17.55° N, 78.35–78.60° E. Not the whole city and " +
                "no routing. Purple marks drawn positions and trails in every mode (synthetic fixture, recorded " +
                "session, live phone fixes, or the positions the navigation engine published). The blue ring is the " +
                "95% confidence radius a calibrated record supplied, and the blue dashed ring is the engine's own " +
                "covariance while its confidence is UNVALIDATED — a model claim, not a validated accuracy. Amber is " +
                "the automatic outage segment, and the red dashed line and dot are the evaluation-only map-matched " +
                "claim, drawn beside the raw position and never instead of it. The fixture's red comparison line is " +
                "an illustration, not measured INS or AI output.",
            color = IdrPalette.textSecondary,
            style = IdrType.bodySmall,
        )
        Text(
            "English/Latin labels · detail to tile zoom 14 · renderer only. The navigation engine view reads published " +
                "navigation state and never a sensor: GNSS loss, dead reckoning and recovery come from the engine, not " +
                "this screen.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
    }
}

@Composable
private fun NativeOfflineMap(pack: InstalledMap, onReady: (MapRenderer) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val created = remember(context) { runCatching {
        MapLibre.getInstance(context)
        MapLibre.setConnected(false)
        MapView(context).apply { onCreate(null) }
    } }
    val view = created.getOrNull()
    if (view == null) {
        LaunchedEffect(created) { onError("MAP_RENDERER_UNAVAILABLE") }
        return
    }
    DisposableEffect(view, owner) {
        var started = false
        var resumed = false
        var disposed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { if (resumed) view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { if (started) view.onStop(); started = false }
                else -> Unit
            }
        }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { view.onLowMemory() }
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) view.onLowMemory() }
        }
        context.registerComponentCallbacks(memory)
        owner.lifecycle.addObserver(observer)
        val failure = MapView.OnDidFailLoadingMapListener { if (!disposed) onError("MAP_RENDERER_FAILED") }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { map ->
            if (!disposed) {
                map.uiSettings.isAttributionEnabled = false // attribution is always visible outside the map
                map.uiSettings.isLogoEnabled = false
                map.setMinZoomPreference(10.0); map.setMaxZoomPreference(18.0)
                map.setLatLngBoundsForCameraTarget(
                    LatLngBounds.from(HyderabadMap.NORTH, HyderabadMap.EAST, HyderabadMap.SOUTH, HyderabadMap.WEST),
                )
                val renderer = MapLibreRenderer(map)
                renderer.recenter()
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(pack.style)) { if (!disposed) onReady(renderer) }
            }
        }
        onDispose {
            disposed = true
            owner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            view.removeOnDidFailLoadingMapListener(failure)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    // Fills the hero layer it is placed in; the hero Box owns the testTag and size.
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
