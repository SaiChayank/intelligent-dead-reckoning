package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.sessions.SavedSession
import com.intelligentdeadreckoning.app.sessions.SessionPage
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrIcon
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrShapes
import com.intelligentdeadreckoning.app.ui.design.IdrSize
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.IdrStateTone
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.KeyValueRow
import com.intelligentdeadreckoning.app.ui.design.StatePanel
import com.intelligentdeadreckoning.app.ui.design.StatusPill
import java.time.Instant

/**
 * Saved sessions (recorded data). Provenance is stated up front — these are recorded rows
 * from the past, never live acquisition — and every session row shows its stored completion
 * and recovery state verbatim instead of presenting an incomplete recording as clean.
 */
@Composable
fun SessionDialog(
    page: SessionPage,
    error: String?,
    busy: Boolean,
    exportMessage: String,
    refresh: (String?) -> Unit,
    export: (String) -> Unit,
    close: () -> Unit,
    replay: (String) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = close,
        containerColor = IdrPalette.surfaceElevated,
        titleContentColor = IdrPalette.textPrimary,
        textContentColor = IdrPalette.textSecondary,
        shape = IdrShapes.cardLarge,
        title = { Text("Local saved sessions", style = IdrType.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
                StatusPill("RECORDED DATA", tone = IdrTone.INFO, detail = "replay is not live", testTag = "sessions_provenance")
                Text(
                    "Saved recordings · replay is not live acquisition or navigation. Export creates a copy.",
                    color = IdrPalette.textSecondary,
                    style = IdrType.bodySmall,
                )
                Text(exportMessage, color = IdrPalette.textSecondary, style = IdrType.bodySmall)
                error?.let {
                    Text(it, color = IdrPalette.danger, style = IdrType.bodySmall)
                }
                if (busy) {
                    Text("Working… export and replay stay disabled until it finishes.",
                        color = IdrPalette.textMuted, style = IdrType.bodySmall)
                }
                LazyColumn(
                    Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(IdrSpace.lg),
                ) {
                    if (page.sessions.isEmpty()) {
                        item {
                            StatePanel(
                                title = "No saved sessions",
                                message = "Record with Phone sensors; sessions appear here after recording stops.",
                                tone = IdrStateTone.EMPTY,
                                testTag = "sessions_empty",
                            )
                        }
                    }
                    items(page.sessions, key = { it.id }) { session ->
                        SessionRow(session, busy, export, replay, close)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                    IdrButton("Refresh / first", { refresh(null) }, variant = IdrButtonVariant.SECONDARY,
                        modifier = Modifier.weight(1f))
                    IdrButton("Next page", { refresh(page.next) }, enabled = page.next != null,
                        variant = IdrButtonVariant.GHOST, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { IdrButton("Close", close, variant = IdrButtonVariant.GHOST) },
    )
}

@Composable
private fun SessionRow(
    session: SavedSession,
    busy: Boolean,
    export: (String) -> Unit,
    replay: (String) -> Unit,
    close: () -> Unit,
) {
    val m = session.metadata
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.LAYERS, tint = IdrPalette.textSecondary, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(session.id, color = IdrPalette.textPrimary, style = IdrType.titleMedium,
                modifier = Modifier.weight(1f))
        }
        KeyValueRow("Time", m?.startedUtcMs?.let { Instant.ofEpochMilli(it).toString() } ?: "unknown", mono = true)
        KeyValueRow("Source", m?.source?.wire ?: "unknown", mono = true)
        KeyValueRow("Duration", session.durationNs?.let { "${it / 1_000_000_000L} s" } ?: "unknown / open", mono = true)
        KeyValueRow("Records", m?.recordCount?.toString() ?: "unknown / open", mono = true)
        KeyValueRow("State", "${m?.completionState ?: "invalid"} / ${m?.recoveryState ?: "unknown"}", mono = true)
        KeyValueRow("Size", session.bytes?.let { "$it bytes" } ?: "unknown", mono = true)
        session.error?.let { Text(it, color = IdrPalette.danger, style = IdrType.bodySmall) }
        if (m != null && m.completionState.name != "COMPLETED") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IdrIcon(IdrGlyph.WARNING, tint = IdrPalette.warning, size = IdrSize.iconSm)
                Spacer(Modifier.width(IdrSpace.sm))
                Text(
                    "Not a clean completed session; export preserves its status and bytes.",
                    color = IdrPalette.warning,
                    style = IdrType.bodySmall,
                )
            }
        }
        IdrButton(
            "Export copy (.zip)",
            { export(session.id) },
            enabled = !busy && session.exportable,
            variant = IdrButtonVariant.SECONDARY,
            testTag = "export_${session.id}",
            modifier = Modifier.fillMaxWidth(),
        )
        IdrButton(
            "Replay session",
            { replay(session.id); close() },
            enabled = !busy && session.replayable,
            variant = IdrButtonVariant.PRIMARY,
            testTag = "replay_${session.id}",
            modifier = Modifier.fillMaxWidth(),
        )
        IdrDivider()
    }
}
