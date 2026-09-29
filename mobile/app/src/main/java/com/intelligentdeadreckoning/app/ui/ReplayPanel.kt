package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.replay.ReplayPhase
import com.intelligentdeadreckoning.app.replay.ReplayState
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrSectionLabel
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.KeyValueRow
import com.intelligentdeadreckoning.app.ui.design.StatTile

/**
 * Read-only local replay. This surface never presents itself as live acquisition and never
 * mutates the stored session; it only paces already-validated records.
 */
@Composable
fun ReplayPanel(state: ReplayState, pause: () -> Unit, resume: () -> Unit, stop: () -> Unit) {
    IdrCard(emphasis = IdrEmphasis.GLASS) {
        IdrSectionLabel("Recorded playback")
        Text(
            "Replay · ${state.phase.name.lowercase()}",
            color = IdrPalette.textPrimary,
            style = IdrType.titleLarge,
            modifier = Modifier.testTag("replay_status"),
        )
        Text(state.message, color = IdrPalette.textSecondary, style = IdrType.bodySmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
            StatTile(
                "Records emitted",
                "${state.emitted} / ${state.total ?: "unknown"}",
                modifier = Modifier.weight(1f),
                valueStyle = IdrType.titleMedium,
            )
            StatTile(
                "Playback position",
                "${state.elapsedNs / 1_000_000_000L}",
                modifier = Modifier.weight(1f),
                unit = "s",
                valueStyle = IdrType.titleMedium,
            )
        }
        if (state.incomplete) {
            Text(
                "INCOMPLETE / RECOVERED — only the recovered prefix is available.",
                color = IdrPalette.warning,
                style = IdrType.labelSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            IdrButton("Pause", pause, enabled = state.phase == ReplayPhase.PLAYING,
                variant = IdrButtonVariant.SECONDARY, glyph = IdrGlyph.PAUSE,
                testTag = "replay_pause", minHeight = 44.dp, modifier = Modifier.weight(1f))
            IdrButton("Resume", resume, enabled = state.phase == ReplayPhase.PAUSED,
                variant = IdrButtonVariant.PRIMARY, glyph = IdrGlyph.PLAY,
                testTag = "replay_resume", minHeight = 44.dp, modifier = Modifier.weight(1f))
            IdrButton("Stop replay", stop, enabled = state.busy && state.phase != ReplayPhase.STOPPING,
                variant = IdrButtonVariant.GHOST, glyph = IdrGlyph.STOP,
                testTag = "replay_stop", minHeight = 44.dp, modifier = Modifier.weight(1f))
        }
        Text(
            "Select a live source explicitly to leave replay. Nothing restarts automatically.",
            color = IdrPalette.textMuted,
            style = IdrType.bodySmall,
        )
        IdrDivider()
        KeyValueRow("Session", state.id ?: "none", mono = true)
        // Kept as one composed sentence on purpose: the replay identity is also shown verbatim in
        // the header banner, and an exact duplicate text node would make the two indistinguishable.
        Text(
            "Original source ${state.source?.wire ?: "pending"} · stored rows keep their recorded source",
            color = IdrPalette.textSecondary,
            style = IdrType.monoSmall,
        )
        state.latest?.let { record ->
            KeyValueRow("Last event type", record.event.data.type, mono = true)
            KeyValueRow("Original event ID", record.event.event_id, mono = true)
            KeyValueRow("Original event ns", record.event.t_ns.toString(), mono = true)
            KeyValueRow("Original receipt ns", record.event.received_ns.toString(), mono = true)
            Text(
                record.event.data.toString(),
                color = IdrPalette.textMuted,
                style = IdrType.monoSmall,
                modifier = Modifier.padding(top = IdrSpace.xs),
            )
        }
    }
}
