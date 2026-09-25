package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.intelligentdeadreckoning.app.map.*

@Composable
fun MapDemoControls(state: DemoSnapshot, overlays: DemoOverlays,
    onScenario: (DemoScenario) -> Unit, onRate: (Double) -> Unit,
    onSignal: (DemoSignal) -> Unit, onOverlays: (DemoOverlays) -> Unit) {
    val active = state.playback in listOf(DemoPlayback.RUNNING,DemoPlayback.PAUSED)
    Column {
        Text("Synthetic paths — not planned routes. Stop/reset to select.")
        Row { DemoScenario.entries.forEach { scenario ->
            TextButton(onClick = { onScenario(scenario) },enabled = !active,
                modifier = Modifier.testTag("scenario_${scenario.name}")) {
                Text((if(state.scenario == scenario) "✓ " else "")+scenario.label)
            }
        } }
        Row { listOf(0.5,1.0,2.0).forEach { rate ->
            TextButton(onClick = { onRate(rate) },modifier = Modifier.testTag("rate_$rate")) {
                Text((if(state.rate == rate) "✓ " else "")+"${rate}×")
            }
        } }
        Text("Signal override changes ONLY this demo; never phone GNSS.")
        Row { DemoSignal.entries.forEach { signal ->
            TextButton(onClick = { onSignal(signal) },enabled = active,
                modifier = Modifier.testTag("signal_${signal.name}")) {
                Text((if(state.signal == signal) "✓ " else "")+when(signal) {
                    DemoSignal.AUTOMATIC -> "Auto"; DemoSignal.BLACKOUT -> "Blackout"; DemoSignal.AVAILABLE -> "Recover"
                })
            }
        } }
        fun label(kind: String) = "overlay_$kind"
        Row {
            FilterChip(selected = overlays.comparison,onClick = { onOverlays(overlays.copy(comparison = !overlays.comparison)) },label = { Text("Comparison") },modifier = Modifier.testTag(label("comparison")))
            FilterChip(selected = overlays.trail,onClick = { onOverlays(overlays.copy(trail = !overlays.trail)) },label = { Text("Trail") },modifier = Modifier.testTag(label("trail")))
        }
        Row {
            FilterChip(selected = overlays.uncertainty,onClick = { onOverlays(overlays.copy(uncertainty = !overlays.uncertainty)) },label = { Text("Uncertainty") },modifier = Modifier.testTag(label("uncertainty")))
            FilterChip(selected = overlays.scenario,onClick = { onOverlays(overlays.copy(scenario = !overlays.scenario)) },label = { Text("Scenario path") },modifier = Modifier.testTag(label("scenario")))
        }
        FilterChip(selected = overlays.roads,onClick = { onOverlays(overlays.copy(roads = !overlays.roads)) },label = { Text("Road overlay") },modifier = Modifier.testTag(label("roads")))
        Text("Amber always marks the automatic 10–20s segment; manual overrides need not match it. Recovery convergence is scripted, not a filter correction.")
    }
}
