package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.intelligentdeadreckoning.app.map.DemoOverlays
import com.intelligentdeadreckoning.app.map.DemoPlayback
import com.intelligentdeadreckoning.app.map.DemoScenario
import com.intelligentdeadreckoning.app.map.DemoSignal
import com.intelligentdeadreckoning.app.map.DemoSnapshot
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrChip
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrSectionLabel
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.IdrType

/**
 * Synthetic demo options. Every control here changes only the local scripted fixture:
 * paths, playback rate, the simulated signal override and overlay visibility. Nothing
 * reaches acquisition, recordings, replay or a navigation engine.
 */
@Composable
fun MapDemoControls(state: DemoSnapshot, overlays: DemoOverlays,
    onScenario: (DemoScenario) -> Unit, onRate: (Double) -> Unit,
    onSignal: (DemoSignal) -> Unit, onOverlays: (DemoOverlays) -> Unit) {
    val active = state.playback in listOf(DemoPlayback.RUNNING, DemoPlayback.PAUSED)
    IdrCard(emphasis = IdrEmphasis.SECONDARY) {
        IdrSectionLabel("Synthetic path")
        Text(
            "Mathematical curves, not planned routes. Stop or reset before selecting another path.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            DemoScenario.entries.forEach { scenario ->
                IdrChip(
                    label = scenario.label,
                    selected = state.scenario == scenario,
                    onClick = { onScenario(scenario) },
                    enabled = !active,
                    testTag = "scenario_${scenario.name}",
                )
            }
        }
        IdrDivider()
        IdrSectionLabel("Playback rate")
        Text(
            "Changes display time only. The fixture keeps its own 10 m/s physical speed label.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            listOf(0.5, 1.0, 2.0).forEach { rate ->
                IdrChip(
                    label = "${rate}×",
                    selected = state.rate == rate,
                    onClick = { onRate(rate) },
                    testTag = "rate_$rate",
                )
            }
        }
        IdrDivider()
        IdrSectionLabel("Signal override")
        Text(
            "Affects ONLY this demo; it never suppresses or fakes phone GNSS.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            DemoSignal.entries.forEach { signal ->
                IdrChip(
                    label = when (signal) {
                        DemoSignal.AUTOMATIC -> "Auto"
                        DemoSignal.BLACKOUT -> "Blackout"
                        DemoSignal.AVAILABLE -> "Recover"
                    },
                    selected = state.signal == signal,
                    onClick = { onSignal(signal) },
                    enabled = active,
                    testTag = "signal_${signal.name}",
                )
            }
        }
        IdrDivider()
        IdrSectionLabel("Map overlays")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrChip("Comparison", overlays.comparison, { onOverlays(overlays.copy(comparison = !overlays.comparison)) },
                glyph = IdrGlyph.NAVIGATE, testTag = "overlay_comparison")
            IdrChip("Trail", overlays.trail, { onOverlays(overlays.copy(trail = !overlays.trail)) },
                glyph = IdrGlyph.PULSE, testTag = "overlay_trail")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrChip("Uncertainty", overlays.uncertainty, { onOverlays(overlays.copy(uncertainty = !overlays.uncertainty)) },
                glyph = IdrGlyph.LOCATE, testTag = "overlay_uncertainty")
            IdrChip("Scenario path", overlays.scenario, { onOverlays(overlays.copy(scenario = !overlays.scenario)) },
                glyph = IdrGlyph.LAYERS, testTag = "overlay_scenario")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrChip("Road overlay", overlays.roads, { onOverlays(overlays.copy(roads = !overlays.roads)) },
                glyph = IdrGlyph.LAYERS, testTag = "overlay_roads")
        }
        Text(
            "Amber always marks the automatic 10–20s segment; a manual override need not match it. " +
                "Recovery convergence is scripted, not a filter correction.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
    }
}
