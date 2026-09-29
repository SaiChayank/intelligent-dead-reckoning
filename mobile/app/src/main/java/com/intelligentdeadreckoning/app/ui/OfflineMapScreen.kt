package com.intelligentdeadreckoning.app.ui

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intelligentdeadreckoning.app.map.DemoOverlays
import com.intelligentdeadreckoning.app.map.DemoPlayback
import com.intelligentdeadreckoning.app.map.HyderabadMap
import com.intelligentdeadreckoning.app.map.InstalledMap
import com.intelligentdeadreckoning.app.map.MapDemoController
import com.intelligentdeadreckoning.app.map.MapLibreRenderer
import com.intelligentdeadreckoning.app.map.MapRenderer
import com.intelligentdeadreckoning.app.map.MapPresentation
import com.intelligentdeadreckoning.app.map.OfflineMapPack
import com.intelligentdeadreckoning.app.map.RecordedSessionMap
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
@Composable
fun OfflineMapScreen(pageHeight: Dp) {
    val context = LocalContext.current
    var pack by remember { mutableStateOf<InstalledMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderer by remember { mutableStateOf<MapRenderer?>(null) }
    var ready by remember { mutableStateOf(false) }
    val demo = remember { MapDemoController() }
    var snapshot by remember { mutableStateOf(demo.state) }
    var overlays by remember { mutableStateOf(DemoOverlays()) }
    var showControls by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(false) }
    var recordedView by remember { mutableStateOf<RecordedView?>(null) }
    var recordedBusy by remember { mutableStateOf(false) }
    fun update(action: () -> Unit) { action(); snapshot = demo.state }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                update { demo.stop() }
                // This observer is registered once per lifecycle owner, so the state it reads
                // here is the current one, not the one captured when it was created.
                renderer?.present(
                    if (recorded) recordedView?.presentation ?: NO_RECORDED_VIEW else snapshot.presentation,
                    overlays,
                )
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
    LaunchedEffect(recorded) {
        if (!recorded) { recordedView = null; return@LaunchedEffect }
        recordedBusy = true
        recordedView = withContext(Dispatchers.IO) { loadRecordedView(context) }
        recordedBusy = false
    }
    val shown = if (recorded) recordedView?.presentation ?: NO_RECORDED_VIEW else snapshot.presentation
    LaunchedEffect(shown, overlays, renderer) { renderer?.present(shown, overlays) }
    LaunchedEffect(Unit) {
        try { pack = withContext(Dispatchers.IO) { OfflineMapPack(context.applicationContext).install() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Offline map unavailable" }
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
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f).testTag("offline_map"))
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
                    Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                        IdrChip(
                            label = "Synthetic demo",
                            selected = !recorded,
                            onClick = { if (recorded) { update { demo.stop() }; recorded = false } },
                            enabled = !recordedBusy,
                            glyph = IdrGlyph.SATELLITE,
                            testTag = "map_source_synthetic",
                        )
                        IdrChip(
                            label = "Recorded session",
                            selected = recorded,
                            onClick = { update { demo.stop() }; recorded = true },
                            enabled = !recordedBusy,
                            glyph = IdrGlyph.LAYERS,
                            testTag = "map_source_recorded",
                        )
                    }
                    MapChip(
                        text = "Preview only · no live position, DR, routing or navigation",
                        tone = IdrTone.NEUTRAL,
                        glyph = IdrGlyph.NAVIGATE,
                        testTag = "map_mode",
                    )
                    MapChip(
                        text = if (recorded) "MAP SOURCE: RECORDED SESSION — real GNSS fixes, no fusion or DR"
                        else "MAP SOURCE: SYNTHETIC UI FIXTURE — independent of acquisition",
                        tone = if (recorded) IdrTone.INFO else IdrTone.WARNING,
                        glyph = if (recorded) IdrGlyph.LAYERS else IdrGlyph.WARNING,
                        testTag = "map_source",
                    )
                    if (error == null) {
                        MapChip(
                            text = if (ready && pack != null) "Offline map loaded · ${pack!!.tiles} tiles"
                            else "Loading local style…",
                            tone = if (ready) IdrTone.ACCENT else IdrTone.INFO,
                            glyph = if (ready) IdrGlyph.CHECK else IdrGlyph.SATELLITE,
                            testTag = "map_status",
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                    if (!recorded) IdrCard(emphasis = IdrEmphasis.GLASS) {
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
                                minHeight = 42.dp,
                                modifier = Modifier.weight(1.4f),
                            )
                            IdrButton(
                                "Stop demo",
                                onClick = { update { demo.stop() } },
                                enabled = active,
                                variant = IdrButtonVariant.SECONDARY,
                                glyph = IdrGlyph.STOP,
                                testTag = "map_demo_stop",
                                minHeight = 42.dp,
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
                    if (recorded) RecordedPanel(recordedView, recordedBusy)
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

        if (showControls && !recorded) {
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

/** Honest summary of the recorded session on the map. Nothing shown here is inferred. */
@Composable
private fun RecordedPanel(view: RecordedView?, busy: Boolean) {
    IdrCard(emphasis = IdrEmphasis.GLASS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.LAYERS, tint = IdrPalette.textMuted, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(
                when {
                    busy -> "Reading saved session…"
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
        if (view != null) {
            Text(
                "${view.stats.fixes} fixes · ${view.stats.drawn} drawn · ${view.stats.gaps} gaps · " +
                    "longest ${view.stats.longestGapNs / 1_000_000_000}s · span ${view.stats.spanNs / 1_000_000_000}s" +
                    (view.stats.outsideCoverage.takeIf { it > 0 }?.let { " · $it outside coverage" } ?: "") +
                    (if (view.truncated) " · first ${view.scanned} records" else ""),
                color = IdrPalette.textSecondary,
                style = IdrType.monoSmall,
                modifier = Modifier.testTag("recorded_stats"),
            )
            Text(
                "Real recorded GNSS fixes only. No dead reckoning, fusion, road matching, routing or " +
                    "live position exists yet, so no DR or comparison line is drawn, and the trail breaks " +
                    "wherever the recording has no fix. Providers: ${view.stats.providers.joinToString().ifEmpty { "none" }}" +
                    (view.stats.lastFixRadiusMetres?.let {
                        " · last reported fix radius ${String.format(Locale.ROOT, "%.0f", it)} m (68%)"
                    } ?: ""),
                color = IdrPalette.textMuted,
                style = IdrType.bodySmall,
                modifier = Modifier.testTag("recorded_disclaimer"),
            )
        }
    }
}

/** Compact translucent status chip for map-overlay information. */
@Composable
private fun MapChip(text: String, tone: IdrTone, glyph: IdrGlyph, testTag: String) {
    Row(
        Modifier
            .clip(IdrShapes.pill)
            .background(IdrPalette.glass)
            .border(1.dp, IdrPalette.border, IdrShapes.pill)
            .padding(horizontal = IdrSpace.md, vertical = IdrSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdrIcon(glyph, tint = tone.color, size = 13.dp)
        Spacer(Modifier.width(IdrSpace.sm))
        Text(
            text,
            color = if (tone == IdrTone.NEUTRAL) IdrPalette.textSecondary else tone.color,
            style = IdrType.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(testTag),
        )
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
        Text(
            "Bundled central Hyderabad vector tiles only: 17.30–17.55° N, 78.35–78.60° E. Not the whole city, " +
                "no routing graph, no live position. Purple marks a scripted reference trace and amber the automatic " +
                "outage segment; the red comparison line is an illustration, not measured INS or AI output.",
            color = IdrPalette.textSecondary,
            style = IdrType.bodySmall,
        )
        Text(
            "English/Latin labels · detail to tile zoom 14 · renderer only. GNSS loss and recovery must eventually come " +
                "from the reviewed navigation pipeline, not this screen.",
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
        LaunchedEffect(created) { onError(created.exceptionOrNull()?.message ?: "Renderer unavailable") }
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
        val failure = MapView.OnDidFailLoadingMapListener { if (!disposed) onError(it) }
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
