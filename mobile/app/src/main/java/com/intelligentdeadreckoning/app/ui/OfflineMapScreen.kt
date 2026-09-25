package com.intelligentdeadreckoning.app.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intelligentdeadreckoning.app.map.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.os.SystemClock
import com.intelligentdeadreckoning.contracts.v1.*
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView

@Composable
fun OfflineMapScreen() {
    val context = LocalContext.current
    var pack by remember { mutableStateOf<InstalledMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderer by remember { mutableStateOf<MapRenderer?>(null) }
    var ready by remember { mutableStateOf(false) }
    val demo = remember { MapDemoController() }
    var snapshot by remember { mutableStateOf(demo.state) }
    var overlays by remember { mutableStateOf(DemoOverlays()) }
    var showControls by remember { mutableStateOf(false) }
    fun update(action: () -> Unit) { action(); snapshot = demo.state }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) {
                update { demo.stop() }
                renderer?.present(snapshot.presentation,overlays)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(snapshot.playback) {
        while(demo.state.playback == DemoPlayback.RUNNING) {
            snapshot = demo.tick(SystemClock.elapsedRealtimeNanos())
            delay(50)
        }
    }
    LaunchedEffect(snapshot,overlays,renderer) { renderer?.present(snapshot.presentation,overlays) }
    LaunchedEffect(Unit) {
        try { pack = withContext(Dispatchers.IO) { OfflineMapPack(context.applicationContext).install() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { error = e.message ?: "Offline map unavailable" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hyderabad · offline map", style = MaterialTheme.typography.titleLarge)
        Text("Optional map preview · no live position, DR, routing or navigation", modifier = Modifier.testTag("map_mode"))
        Text("MAP SOURCE: SYNTHETIC UI FIXTURE — independent of acquisition", Modifier.testTag("map_source"))
        Text(snapshot.label, Modifier.testTag("map_demo_status"))
        Text("${snapshot.playback} · ${snapshot.rate}× playback · ${snapshot.elapsedMs/1000}s / 30s",Modifier.testTag("demo_clock"))
        Row {
            TextButton(onClick = {
                update { demo.start(SystemClock.elapsedRealtimeNanos()) }
                snapshot.presentation.point?.let { renderer?.focus(it) }
            },enabled = ready && snapshot.playback !in listOf(DemoPlayback.RUNNING,DemoPlayback.PAUSED),
                modifier = Modifier.testTag("map_demo_start")) { Text("Start synthetic demo") }
            TextButton(onClick = { update { demo.stop() } },
                enabled = snapshot.playback in listOf(DemoPlayback.RUNNING,DemoPlayback.PAUSED),modifier = Modifier.testTag("map_demo_stop")) { Text("Stop demo") }
        }
        Row {
            TextButton(onClick = { update {
                if(snapshot.playback == DemoPlayback.PAUSED) demo.resume(SystemClock.elapsedRealtimeNanos())
                else demo.pause(SystemClock.elapsedRealtimeNanos())
            } },enabled = snapshot.playback in listOf(DemoPlayback.RUNNING,DemoPlayback.PAUSED),modifier = Modifier.testTag("demo_pause")) {
                Text(if(snapshot.playback == DemoPlayback.PAUSED) "Resume" else "Pause")
            }
            TextButton(onClick = { update { demo.reset() } },modifier = Modifier.testTag("demo_reset")) { Text("Reset") }
            TextButton(onClick = { showControls = !showControls },modifier = Modifier.testTag("demo_options")) { Text(if(showControls) "Hide options" else "Demo options") }
        }
        if(showControls) MapDemoControls(snapshot,overlays,
            onScenario = { update { demo.select(it) } },
            onRate = { update { demo.rate(it,SystemClock.elapsedRealtimeNanos()) } },
            onSignal = { update { demo.signal(it,SystemClock.elapsedRealtimeNanos()) } },
            onOverlays = { overlays = it })
        if(snapshot.playback != DemoPlayback.IDLE) {
            Text("SYNTHETIC HUD · distance ${"%.1f".format(java.util.Locale.ROOT,snapshot.distanceM)} m · outage total ${snapshot.outageMs/1000.0}s / ${"%.1f".format(java.util.Locale.ROOT,snapshot.outageDistanceM)} m",Modifier.testTag("demo_stats"))
            Text("Current outage ${snapshot.currentOutageMs/1000.0}s · heading ${snapshot.presentation.headingDegrees?.let { "%.1f".format(java.util.Locale.ROOT,it) } ?: "—"}° · speed ${snapshot.presentation.speedMetresPerSecond?.let { "%.1f".format(java.util.Locale.ROOT,it) } ?: "—"} m/s")
        }
        Text("Purple: scripted reference · Red: illustrative drift, NOT measured INS/AI results · Amber: automatic outage segment",style = MaterialTheme.typography.bodySmall)
        Text("Central coverage: 17.30–17.55° N, 78.35–78.60° E. Not the whole city.", style = MaterialTheme.typography.bodySmall)
        when {
            error != null -> Text("Map unavailable: $error. No network fallback.", Modifier.testTag("map_error"))
            pack == null -> Text("Checking/installing bundled map…", Modifier.testTag("map_loading"))
            else -> {
                Text(if (ready) "Offline map loaded · ${pack!!.tiles} tiles" else "Loading local style…", Modifier.testTag("map_status"))
                NativeOfflineMap(pack!!, { renderer = it; ready = true }, {
                    ready = false
                    update { demo.stop() }
                    renderer = null
                    error = it
                })
            }
        }
        Row {
            TextButton(onClick = { renderer?.recenter() }, enabled = ready) { Text("Hyderabad") }
            TextButton(onClick = { renderer?.zoomBy(1.0) }, enabled = ready) { Text("Zoom +") }
            TextButton(onClick = { renderer?.zoomBy(-1.0) }, enabled = ready) { Text("Zoom −") }
            TextButton(onClick = { renderer?.northUp() }, enabled = ready) { Text("North") }
        }
        Text("OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors\nOSM data: ODbL 1.0 · openstreetmap.org/copyright", style = MaterialTheme.typography.bodySmall)
        Text("English/Latin labels · detail to tile zoom 14. Purple marker/trail are synthetic, not road-matched. No planned route or real navigation is connected.", style = MaterialTheme.typography.bodySmall)
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
    DisposableEffect(view,owner) {
        var started = false
        var resumed = false
        var disposed = false
        val observer = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_START -> { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { if(resumed) view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { if(started) view.onStop(); started = false }
                else -> Unit
            }
        }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { view.onLowMemory() }
            override fun onTrimMemory(level: Int) { if(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) view.onLowMemory() }
        }
        context.registerComponentCallbacks(memory)
        owner.lifecycle.addObserver(observer)
        val failure = MapView.OnDidFailLoadingMapListener { if (!disposed) onError(it) }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { map ->
            if (!disposed) {
                map.uiSettings.isAttributionEnabled = false // attribution is always visible outside map
                map.uiSettings.isLogoEnabled = false
                map.setMinZoomPreference(10.0); map.setMaxZoomPreference(18.0)
                map.setLatLngBoundsForCameraTarget(LatLngBounds.from(HyderabadMap.NORTH,HyderabadMap.EAST,HyderabadMap.SOUTH,HyderabadMap.WEST))
                val renderer = MapLibreRenderer(map)
                renderer.recenter()
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(pack.style)) { if(!disposed) onReady(renderer) }
            }
        }
        onDispose {
            disposed = true
            owner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            view.removeOnDidFailLoadingMapListener(failure)
            if(resumed) view.onPause()
            if(started) view.onStop()
            view.onDestroy()
        }
    }
    Box(Modifier.fillMaxWidth().height(380.dp).testTag("offline_map")) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize())
        Surface(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Text("OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors\nopenstreetmap.org/copyright",
                modifier = Modifier.padding(4.dp).testTag("map_attribution"),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
