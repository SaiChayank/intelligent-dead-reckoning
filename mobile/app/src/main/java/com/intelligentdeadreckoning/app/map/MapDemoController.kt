package com.intelligentdeadreckoning.app.map

import com.intelligentdeadreckoning.contracts.v1.*

enum class DemoScenario(val label: String) { CURVE("Left curve"), STRAIGHT("Straight"), RIGHT_CURVE("Right curve") }
enum class DemoPlayback { IDLE, RUNNING, PAUSED, STOPPED, COMPLETED }
enum class DemoSignal { AUTOMATIC, BLACKOUT, AVAILABLE }
data class DemoOverlays(val trail: Boolean = true, val comparison: Boolean = true,
    val uncertainty: Boolean = true, val scenario: Boolean = true, val roads: Boolean = true)
data class DemoSnapshot(
    val playback: DemoPlayback = DemoPlayback.IDLE, val scenario: DemoScenario = DemoScenario.CURVE,
    val signal: DemoSignal = DemoSignal.AUTOMATIC, val rate: Double = 1.0,
    val elapsedMs: Long = 0, val distanceM: Double = 0.0, val outageMs: Long = 0,
    val outageDistanceM: Double = 0.0, val currentOutageMs: Long = 0,
    val label: String = "Synthetic demo stopped — not your location",
    val presentation: MapPresentation = MapPresentation(Source.SIMULATION),
)

/** UI-only virtual clock and scripted comparison; NOT a NavigationEngine implementation.
 * All operations owned by UI dispatcher; unit tests supply a deterministic monotonic clock.
 * No sensor subscriptions, recording writes, API calls or unbounded history.
 */
class MapDemoController {
    var state = DemoSnapshot(); private set
    private var previousNow = 0L
    private var virtualNs = 0L
    private var outageNs = 0L
    private var currentOutageNs = 0L
    private var redOffset = 0.0
    private var renderedMs = -1L
    private var adapter = NavigationPresentation(SyntheticMapDemo.header,InitializationMode.DEPLOYABLE)
    private val comparison = ArrayDeque<MapPoint>()
    private var path = emptyList<MapPoint>()
    private var deniedPath = emptyList<MapPoint>()
    fun select(scenario: DemoScenario) {
        check(state.playback != DemoPlayback.RUNNING && state.playback != DemoPlayback.PAUSED) { "Stop/reset before changing scenario" }
        reset(); state = state.copy(scenario = scenario)
    }
    fun reset() {
        val scenario = state.scenario; val rate = state.rate
        state = DemoSnapshot(scenario = scenario, rate = rate)
        virtualNs = 0; outageNs = 0; currentOutageNs = 0; redOffset = 0.0; renderedMs = -1
        adapter = NavigationPresentation(SyntheticMapDemo.header,InitializationMode.DEPLOYABLE)
        comparison.clear(); path = emptyList(); deniedPath = emptyList()
    }
    fun start(nowNs: Long) {
        require(nowNs >= 0)
        check(state.playback != DemoPlayback.RUNNING && state.playback != DemoPlayback.PAUSED)
        reset(); previousNow = nowNs
        state = state.copy(playback = DemoPlayback.RUNNING)
        fun point(ms: Long): MapPoint {
            val nav = SyntheticMapDemo.record(ms,0,state.scenario).event.data as NavigationState
            return MapCoordinates.fromEnu(nav.origin_wgs84_deg_m!!,nav.position_enu_m!!)
        }
        path = (0L..30_000L step 500).map(::point)
        deniedPath = (10_000L..20_000L step 500).map(::point)
        render()
    }
    fun pause(nowNs: Long) { check(state.playback == DemoPlayback.RUNNING); tick(nowNs); if(state.playback == DemoPlayback.RUNNING) state = state.copy(playback = DemoPlayback.PAUSED) }
    fun resume(nowNs: Long) { require(nowNs >= previousNow); check(state.playback == DemoPlayback.PAUSED); previousNow = nowNs; state = state.copy(playback = DemoPlayback.RUNNING) }
    fun stop() { state = state.copy(playback = DemoPlayback.STOPPED,label = "Synthetic demo stopped — not your location",presentation = MapPresentation(Source.SIMULATION)); comparison.clear() }
    fun rate(value: Double, nowNs: Long) {
        require(value in listOf(0.5,1.0,2.0)); tick(nowNs); state = state.copy(rate = value)
    }
    fun signal(value: DemoSignal, nowNs: Long) {
        check(state.playback == DemoPlayback.RUNNING || state.playback == DemoPlayback.PAUSED)
        tick(nowNs)
        if(state.playback == DemoPlayback.COMPLETED) return
        state = state.copy(signal = value)
        if(!denied(virtualNs)) currentOutageNs = 0
        // No duplicate navigation record on a same-tick control change.
        state = state.copy(label = phase(),currentOutageMs = currentOutageNs/1_000_000,
            presentation = state.presentation.copy(status = if(denied(virtualNs)) "degraded" else "tracking",
                accuracy95Metres = if(denied(virtualNs)) 35.0 else 8.0))
    }
    private fun denied(time: Long): Boolean = when(state.signal) {
        DemoSignal.BLACKOUT -> true; DemoSignal.AVAILABLE -> false
        DemoSignal.AUTOMATIC -> time in 10_000_000_000L..<20_000_000_000L
    }
    private fun phase() = when {
        denied(virtualNs) -> "SYNTHETIC DR scenario (no INS running)"
        outageNs > 0 -> "SYNTHETIC GNSS recovery scenario (no fusion running)"
        else -> "SYNTHETIC GNSS scenario"
    }
    fun tick(nowNs: Long): DemoSnapshot {
        if(state.playback != DemoPlayback.RUNNING) return state
        require(nowNs >= previousNow) { "Monotonic clock moved backwards" }
        val delta = nowNs-previousNow; previousNow = nowNs
        // Bound before arithmetic, preserving Int64 clock origin and fractional playback speed.
        val increment = (delta.coerceAtMost(60_000_000_000L)*state.rate).toLong()
        val end = (virtualNs+increment).coerceAtMost(30_000_000_000L)
        // Split at automatic outage boundaries, so delayed ticks do not miscount an outage.
        val boundaries = (listOf(virtualNs,end,10_000_000_000L,20_000_000_000L).filter { it in virtualNs..end }).distinct().sorted()
        for((a,b) in boundaries.zipWithNext()) {
            val dt = b-a
            if(denied(a)) { outageNs += dt; currentOutageNs += dt; redOffset += dt/1e9*3.0 }
            else { currentOutageNs = 0; redOffset = (redOffset-dt/1e9*6.0).coerceAtLeast(0.0) }
        }
        virtualNs = end
        if(!denied(end)) currentOutageNs = 0
        render()
        if(end == 30_000_000_000L) state = state.copy(playback = DemoPlayback.COMPLETED,label = "Synthetic demo completed — no navigation running")
        return state
    }
    private fun render() {
        val ms = virtualNs/1_000_000
        if(ms == renderedMs) return
        renderedMs = ms
        val record = SyntheticMapDemo.record(ms,0,state.scenario)
        val nav = record.event.data as NavigationState
        val adjusted = record.copy(event = record.event.copy(data = nav.copy(status = if(denied(virtualNs)) NavigationStatus.DEGRADED else NavigationStatus.TRACKING)))
        val confidence = Record(record.header,record.event.copy(event_id = "${ms+100000}",data =
            Confidence(ConfidenceState.CALIBRATED,null,if(denied(virtualNs)) 35.0 else 8.0,null)))
        val position = nav.position_enu_m!!
        val red = MapCoordinates.fromEnu(nav.origin_wgs84_deg_m!!,position.copy(y = position.y-redOffset))
        if(comparison.lastOrNull() != red) { comparison.addLast(red); if(comparison.size > 512) comparison.removeFirst() }
        val view = adapter.accept(adjusted,record.event.t_ns,confidence).copy(
            comparisonPoint = red,comparisonTrail = comparison.toList(),scenarioPath = path,outagePath = deniedPath)
        state = state.copy(elapsedMs = ms,distanceM = ms/100.0,outageMs = outageNs/1_000_000,
            outageDistanceM = outageNs/1e9*10,currentOutageMs = currentOutageNs/1_000_000,
            label = phase(),presentation = view)
    }
}
