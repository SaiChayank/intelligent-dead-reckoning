package com.intelligentdeadreckoning.app.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var demoRunning by remember { mutableStateOf(false) }
    var demoLabel by remember { mutableStateOf("Synthetic demo stopped — not your location") }
    var presentation by remember { mutableStateOf(MapPresentation(Source.SIMULATION)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) {
                demoRunning = false
                demoLabel = "Synthetic demo stopped in background — restart explicitly"
                presentation = MapPresentation(Source.SIMULATION)
                renderer?.present(presentation)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(demoRunning,renderer) {
        if(!demoRunning) {
            presentation = MapPresentation(Source.SIMULATION)
            renderer?.present(presentation)
            return@LaunchedEffect
        }
        val adapter = NavigationPresentation(SyntheticMapDemo.header,InitializationMode.DEPLOYABLE)
        val start = SystemClock.elapsedRealtimeNanos()
        var first = true
        while(demoRunning) {
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsed = (now-start)/1_000_000L
            if(elapsed > SyntheticMapDemo.DURATION_MS) {
                demoRunning = false; demoLabel = "Synthetic demo completed — no navigation running"
                break
            }
            val record = SyntheticMapDemo.record(elapsed,start)
            // Deliberately synthetic uncertainty, not a calibrated sensor accuracy claim.
            val confidence = Record(record.header,record.event.copy(event_id = "${elapsed+100000}",
                data = Confidence(ConfidenceState.CALIBRATED,null,if(elapsed in 10000..<20000) 35.0 else 8.0,null)))
            presentation = adapter.accept(record,now,confidence)
            demoLabel = SyntheticMapDemo.scenario(elapsed)
            renderer?.present(presentation)
            if(first) { presentation.point?.let { renderer?.focus(it) }; first = false }
            delay(50) // bounded 20 Hz display target; delayed ticks are skipped, never queued
        }
    }
    LaunchedEffect(Unit) {
        try { pack = withContext(Dispatchers.IO) { OfflineMapPack(context.applicationContext).install() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { error = e.message ?: "Offline map unavailable" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hyderabad · offline map", style = MaterialTheme.typography.titleLarge)
        Text("Optional map preview · no live position, DR, routing or navigation", modifier = Modifier.testTag("map_mode"))
        Text("MAP SOURCE: SYNTHETIC UI FIXTURE — independent of acquisition", Modifier.testTag("map_source"))
        Text(demoLabel, Modifier.testTag("map_demo_status"))
        Row {
            TextButton(onClick = { demoRunning = true },enabled = ready && !demoRunning,
                modifier = Modifier.testTag("map_demo_start")) { Text("Start synthetic demo") }
            TextButton(onClick = { demoRunning = false; demoLabel = "Synthetic demo stopped — not your location" },
                enabled = demoRunning,modifier = Modifier.testTag("map_demo_stop")) { Text("Stop demo") }
        }
        if(demoRunning) Text("${presentation.status} · heading ${presentation.headingDegrees?.toInt() ?: "unavailable"}° · speed ${presentation.speedMetresPerSecond?.toInt() ?: "unavailable"} m/s · synthetic 95% radius ${presentation.accuracy95Metres ?: "unavailable"} m")
        Text("Central coverage: 17.30–17.55° N, 78.35–78.60° E. Not the whole city.", style = MaterialTheme.typography.bodySmall)
        when {
            error != null -> Text("Map unavailable: $error. No network fallback.", Modifier.testTag("map_error"))
            pack == null -> Text("Checking/installing bundled map…", Modifier.testTag("map_loading"))
            else -> {
                Text(if (ready) "Offline map loaded · ${pack!!.tiles} tiles" else "Loading local style…", Modifier.testTag("map_status"))
                NativeOfflineMap(pack!!, { renderer = it; ready = true }, {
                    ready = false
                    demoRunning = false
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
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(380.dp).testTag("offline_map"))
}
