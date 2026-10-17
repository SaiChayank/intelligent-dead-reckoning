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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.sessions.DemoScenario
import com.intelligentdeadreckoning.app.sessions.SampleDriveInstaller
import com.intelligentdeadreckoning.app.sessions.SavedSession
import com.intelligentdeadreckoning.app.sessions.SessionPage
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
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
    delete: (String) -> Unit = {},
    installDemo: (String) -> Unit = {},
    installAndReplay: (String) -> Unit = {},
    installAllDemos: () -> Unit = {},
) {
    var deleteConfirmationId by rememberSaveable { mutableStateOf<String?>(null) }
    var showScenarioPicker by rememberSaveable { mutableStateOf(false) }

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
                    Text(
                        "Working… session actions stay disabled until it finishes.",
                        color = IdrPalette.textMuted,
                        style = IdrType.bodySmall,
                    )
                }
                LazyColumn(
                    Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(IdrSpace.lg),
                ) {
                    if (page.sessions.isEmpty()) {
                        item {
                            StatePanel(
                                title = "No saved sessions",
                                message = "Record with Phone sensors, or tap 'Demo Scenarios' below to load a benchmark driving challenge.",
                                tone = IdrStateTone.EMPTY,
                                testTag = "sessions_empty",
                            )
                        }
                    }
                    items(page.sessions, key = { it.id }) { session ->
                        SessionRow(session, busy, export, replay, close) { deleteConfirmationId = it }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                    IdrButton(
                        "Refresh / first",
                        { refresh(null) },
                        variant = IdrButtonVariant.SECONDARY,
                        modifier = Modifier.weight(1f),
                    )
                    IdrButton(
                        "Demo Scenarios",
                        { showScenarioPicker = true },
                        enabled = !busy,
                        variant = IdrButtonVariant.PRIMARY,
                        modifier = Modifier.weight(1.1f),
                        testTag = "install_demo_drive",
                    )
                    IdrButton(
                        "Next page",
                        { refresh(page.next) },
                        enabled = page.next != null,
                        variant = IdrButtonVariant.GHOST,
                        modifier = Modifier.weight(0.9f),
                    )
                }
            }
        },
        confirmButton = { IdrButton("Close", close, variant = IdrButtonVariant.GHOST) },
    )

    if (showScenarioPicker) {
        DemoScenarioPickerDialog(
            installedSessionIds = page.sessions.map { it.id }.toSet(),
            busy = busy,
            onSelectScenario = { scenarioId, shouldReplay ->
                if (shouldReplay) {
                    installAndReplay(scenarioId)
                    replay(scenarioId)
                    showScenarioPicker = false
                    close()
                } else {
                    installDemo(scenarioId)
                }
            },
            onInstallAll = {
                installAllDemos()
            },
            onDismiss = { showScenarioPicker = false },
        )
    }

    deleteConfirmationId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmationId = null },
            containerColor = IdrPalette.surfaceElevated,
            titleContentColor = IdrPalette.textPrimary,
            textContentColor = IdrPalette.textSecondary,
            title = { Text("Delete session permanently?", style = IdrType.headlineMedium) },
            text = { Text("This permanently removes the private recording for $id. Exported copies are not affected.", style = IdrType.bodySmall) },
            confirmButton = {
                IdrButton("Delete permanently", {
                    deleteConfirmationId = null
                    delete(id)
                }, variant = IdrButtonVariant.DANGER)
            },
            dismissButton = {
                IdrButton("Keep session", { deleteConfirmationId = null }, variant = IdrButtonVariant.GHOST)
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: SavedSession,
    busy: Boolean,
    export: (String) -> Unit,
    replay: (String) -> Unit,
    close: () -> Unit,
    requestDelete: (String) -> Unit,
) {
    val m = session.metadata
    val benchmarkInfo = SampleDriveInstaller.getScenario(session.id)

    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(IdrGlyph.LAYERS, tint = IdrPalette.textSecondary, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(session.id, color = IdrPalette.textPrimary, style = IdrType.titleMedium,
                modifier = Modifier.weight(1f))
            if (benchmarkInfo != null) {
                StatusPill(benchmarkInfo.badge, tone = IdrTone.ACCENT, detail = benchmarkInfo.durationText)
            }
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
        IdrButton(
            "Delete session",
            { requestDelete(session.id) },
            enabled = !busy && session.error != "SESSION_NOT_FOUND",
            variant = IdrButtonVariant.DANGER,
            testTag = "delete_${session.id}",
            modifier = Modifier.fillMaxWidth(),
        )
        IdrDivider()
    }
}

/**
 * Interactive multi-scenario picker for selecting and replaying benchmark driving challenges.
 */
@Composable
fun DemoScenarioPickerDialog(
    installedSessionIds: Set<String>,
    busy: Boolean,
    onSelectScenario: (String, Boolean) -> Unit,
    onInstallAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = IdrPalette.surfaceElevated,
        titleContentColor = IdrPalette.textPrimary,
        textContentColor = IdrPalette.textSecondary,
        shape = IdrShapes.cardLarge,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IdrIcon(IdrGlyph.NAVIGATE, tint = IdrPalette.accent, size = IdrSize.iconLg)
                Spacer(Modifier.width(IdrSpace.sm))
                Column {
                    Text("Benchmark Scenarios", style = IdrType.headlineMedium, color = IdrPalette.textPrimary)
                    Text("Evaluate 15-state EKF Dead Reckoning", style = IdrType.bodySmall, color = IdrPalette.textSecondary)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.md)) {
                Text(
                    "Select a driving challenge to evaluate tunnel GNSS outages, urban multipath jump gating, or stop-and-go zero velocity updates (ZUPT).",
                    color = IdrPalette.textSecondary,
                    style = IdrType.bodySmall,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(IdrSpace.md),
                ) {
                    items(SampleDriveInstaller.DEMO_SCENARIOS, key = { it.id }) { scenario ->
                        ScenarioCard(
                            scenario = scenario,
                            isInstalled = installedSessionIds.contains(scenario.id),
                            busy = busy,
                            onSelect = onSelectScenario,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                IdrButton(
                    "Install All",
                    onInstallAll,
                    enabled = !busy,
                    variant = IdrButtonVariant.SECONDARY,
                    testTag = "install_all_scenarios",
                )
                IdrButton("Back", onDismiss, variant = IdrButtonVariant.GHOST)
            }
        },
    )
}

@Composable
private fun ScenarioCard(
    scenario: DemoScenario,
    isInstalled: Boolean,
    busy: Boolean,
    onSelect: (String, Boolean) -> Unit,
) {
    IdrCard(
        emphasis = IdrEmphasis.SECONDARY,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(scenario.title, style = IdrType.titleMedium, color = IdrPalette.textPrimary)
            StatusPill(scenario.durationText, tone = IdrTone.INFO)
        }
        Text(scenario.subtitle, style = IdrType.label, color = IdrPalette.accent)
        Text(scenario.description, style = IdrType.bodySmall, color = IdrPalette.textSecondary)

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            scenario.keyFeatures.forEach { feature ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdrIcon(IdrGlyph.CHECK, tint = IdrPalette.success, size = 12.dp)
                    Spacer(Modifier.width(IdrSpace.xs))
                    Text(feature, style = IdrType.bodySmall, color = IdrPalette.textMuted)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isInstalled) {
                StatusPill("INSTALLED", tone = IdrTone.SUCCESS, modifier = Modifier.weight(0.9f))
                IdrButton(
                    label = "Replay",
                    onClick = { onSelect(scenario.id, true) },
                    enabled = !busy,
                    variant = IdrButtonVariant.PRIMARY,
                    modifier = Modifier.weight(1.1f),
                    testTag = "replay_scenario_${scenario.id}",
                )
            } else {
                IdrButton(
                    label = "Load & Replay",
                    onClick = { onSelect(scenario.id, true) },
                    enabled = !busy,
                    variant = IdrButtonVariant.PRIMARY,
                    modifier = Modifier.weight(1.2f),
                    testTag = "load_replay_${scenario.id}",
                )
                IdrButton(
                    label = "Install",
                    onClick = { onSelect(scenario.id, false) },
                    enabled = !busy,
                    variant = IdrButtonVariant.SECONDARY,
                    modifier = Modifier.weight(0.8f),
                    testTag = "install_${scenario.id}",
                )
            }
        }
    }
}
