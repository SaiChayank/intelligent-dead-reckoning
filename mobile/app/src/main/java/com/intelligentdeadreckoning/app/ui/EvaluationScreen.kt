package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.evaluation.AvailableReport
import com.intelligentdeadreckoning.app.evaluation.EvaluationLibrary
import com.intelligentdeadreckoning.app.evaluation.EvaluationSurface
import com.intelligentdeadreckoning.app.evaluation.MetricRow
import com.intelligentdeadreckoning.app.evaluation.MetricSection
import com.intelligentdeadreckoning.app.evaluation.NOT_EVALUATED
import com.intelligentdeadreckoning.app.evaluation.NOT_IMPLEMENTED
import com.intelligentdeadreckoning.app.evaluation.NOT_IN_REPORT
import com.intelligentdeadreckoning.app.evaluation.NOT_MEASURED
import com.intelligentdeadreckoning.app.ui.design.IdrButton
import com.intelligentdeadreckoning.app.ui.design.IdrButtonVariant
import com.intelligentdeadreckoning.app.ui.design.IdrCard
import com.intelligentdeadreckoning.app.ui.design.IdrChip
import com.intelligentdeadreckoning.app.ui.design.IdrDivider
import com.intelligentdeadreckoning.app.ui.design.IdrEmphasis
import com.intelligentdeadreckoning.app.ui.design.IdrGlyph
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrSectionLabel
import com.intelligentdeadreckoning.app.ui.design.IdrSize
import com.intelligentdeadreckoning.app.ui.design.IdrSpace
import com.intelligentdeadreckoning.app.ui.design.IdrStateTone
import com.intelligentdeadreckoning.app.ui.design.IdrTone
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.KeyValueRow
import com.intelligentdeadreckoning.app.ui.design.StatePanel
import com.intelligentdeadreckoning.app.ui.design.StatusDot
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmId
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmStatus
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationReport
import java.util.Locale

/**
 * The Evaluation tab: engineering evidence, deliberately outside the driver's flow.
 *
 * It exists to answer one question — what did each configuration actually produce on this session,
 * and against what — and it is built so that it cannot answer it dishonestly:
 *
 * - the session is a *selected report*, and the screen states which document it is, where it came
 *   from and what its reference is;
 * - a document that fails the strict evaluation contract is refused whole, with its code;
 * - a metric a report does not carry reads "not measured", an arm the report omits reads "not in
 *   this report", and an arm that did not run gives its reason. Nothing is estimated, averaged or
 *   carried over between arms;
 * - every limit the report declares about itself (host rather than device, scripted rather than
 *   field reference) is printed with the numbers rather than left in a file.
 */
private val labelColumnForArms = 176.dp
private val metricColumn = 104.dp

/** Short column headings; the full arm labels and implementations are listed underneath. */
private val armHeading = mapOf(
    ArmId.CLASSICAL_INS to "Classical INS",
    ArmId.CLASSICAL_FUSION to "Classical fusion",
    ArmId.FUSION_CONSTRAINTS to "+ Constraints",
    ArmId.FUSION_AI to "+ AI correction",
    ArmId.FUSION_MAP_MATCH to "+ Map matching",
)

/** The four ways a cell can decline to be a number. */
private val absentWording = setOf(NOT_MEASURED, NOT_EVALUATED, NOT_IN_REPORT, NOT_IMPLEMENTED)

@Composable
fun EvaluationScreen(library: EvaluationLibrary, onReload: () -> Unit = {}) {
    var choice by rememberSaveable { mutableIntStateOf(0) }
    val count = library.reports.size
    val index = if (count == 0) 0 else choice.coerceIn(0, count - 1)
    Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
            Text("Evaluation", style = IdrType.headlineLarge, color = IdrPalette.textPrimary)
            Text(
                "What each configuration actually produced on one session, scored against the " +
                    "reference that session was measured with. This is engineering evidence, not " +
                    "driver output, and a value no run produced is shown as absent.",
                color = IdrPalette.textSecondary, style = IdrType.bodyMedium,
            )
        }
        val problem = library.problem
        if (problem != null) {
            StatePanel(
                title = "Evaluation report unavailable",
                message = problem,
                tone = IdrStateTone.ERROR,
                testTag = "evaluation_problem",
            )
        }
        if (library.reports.isEmpty()) {
            StatePanel(
                title = "No evaluation report",
                message = "No report is bundled with this build and none is installed under " +
                    "${library.directory.ifEmpty { "the app's evaluation directory" }}.",
                tone = IdrStateTone.EMPTY,
                actionLabel = "Reload",
                onAction = onReload,
                testTag = "evaluation_empty",
            )
        } else {
            if (count > 1) {
                ReportPicker(library.reports, index) { choice = it }
            }
            val available = library.reports[index]
            val report = available.report
            if (report == null) {
                RefusalCard(available, onReload)
            } else {
                ProvenanceCard(available, report)
                ComparisonTable(report)
                ArmsCard(report)
                AbsentCard(report)
                SessionCard(report)
                LimitsCard(report)
            }
            ReloadRow(library, onReload)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Report selection and refusals
// ---------------------------------------------------------------------------------------------

@Composable
private fun ReportPicker(reports: List<AvailableReport>, index: Int, onSelect: (Int) -> Unit) {
    IdrCard(emphasis = IdrEmphasis.UTILITY) {
        IdrSectionLabel("Selected report")
        Row(horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
            reports.forEachIndexed { position, available ->
                IdrChip(
                    label = if (available.showable) available.name else "${available.name} · refused",
                    selected = position == index,
                    onClick = { onSelect(position) },
                    glyph = if (available.showable) IdrGlyph.CHECK else IdrGlyph.WARNING,
                    testTag = "evaluation_report_${position}",
                )
            }
        }
    }
}

@Composable
private fun RefusalCard(available: AvailableReport, onReload: () -> Unit) {
    IdrCard(emphasis = IdrEmphasis.SECONDARY, accentEdge = true) {
        IdrSectionLabel("Refused document", tone = IdrPalette.danger)
        Text(available.name, color = IdrPalette.textPrimary, style = IdrType.titleMedium,
            modifier = Modifier.testTag("evaluation_refused"))
        Text(
            available.refusal ?: "The document did not pass the evaluation contract.",
            color = IdrPalette.danger, style = IdrType.monoSmall,
        )
        Text(
            "The report contract refuses this document rather than rendering the parts that happen " +
                "to parse: a partially read report would put numbers on screen that no run " +
                "produced. Nothing from this file is shown.",
            color = IdrPalette.textSecondary, style = IdrType.bodySmall,
        )
        IdrButton("Reload reports", onReload, variant = IdrButtonVariant.GHOST, glyph = IdrGlyph.RESET,
            testTag = "evaluation_reload")
    }
}

// ---------------------------------------------------------------------------------------------
// Provenance, session and limits
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProvenanceCard(available: AvailableReport, report: EvaluationReport) {
    IdrCard(emphasis = IdrEmphasis.PRIMARY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrSectionLabel("Provenance")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (report.reference.independent) IdrPalette.success else IdrPalette.warning)
                Spacer(Modifier.width(IdrSpace.sm))
                Text(
                    if (report.reference.independent) "independent reference" else "reference NOT independent",
                    color = if (report.reference.independent) IdrPalette.success else IdrPalette.warning,
                    style = IdrType.label,
                    modifier = Modifier.testTag("evaluation_reference_state"),
                )
            }
        }
        Text(report.evaluationId, color = IdrPalette.textPrimary, style = IdrType.titleLarge,
            modifier = Modifier.testTag("evaluation_report_name"))
        Text(EvaluationSurface.provenance(report), color = IdrPalette.textSecondary, style = IdrType.bodySmall)
        KeyValueRow("Reference kind", report.reference.kind.wire, mono = true)
        KeyValueRow("Reference independence", if (report.reference.independent) "independent of every arm" else "not independent", mono = true)
        KeyValueRow("Platform", if (report.platform.host) "host run" else "device run", mono = true)
        KeyValueRow("Origin", available.origin.label, mono = true)
        KeyValueRow("Report contract", report.evaluationContractVersion, mono = true)
        KeyValueRow("Created (UTC ms)", report.createdUtcMs.toString(), mono = true)
    }
}

@Composable
private fun SessionCard(report: EvaluationReport) {
    IdrCard(emphasis = IdrEmphasis.SECONDARY) {
        IdrSectionLabel("Session")
        KeyValueRow("Session id", report.session.sessionId, mono = true)
        KeyValueRow("Source", report.session.source, mono = true)
        KeyValueRow("Output contract", report.session.contractVersion, mono = true)
        KeyValueRow("Duration", String.format(Locale.ROOT, "%.1f s", report.session.durationS), mono = true)
        KeyValueRow("Records", report.session.records.toString(), mono = true)
        KeyValueRow("GNSS timeline", EvaluationSurface.segments(report), mono = true)
        Text(report.session.description, color = IdrPalette.textMuted, style = IdrType.bodySmall)
    }
}

@Composable
private fun LimitsCard(report: EvaluationReport) {
    IdrCard(emphasis = IdrEmphasis.UTILITY) {
        IdrSectionLabel("What these numbers are not", tone = IdrPalette.warning)
        Text(report.platform.note, color = IdrPalette.textSecondary, style = IdrType.bodySmall,
            modifier = Modifier.testTag("evaluation_platform_note"))
        IdrDivider()
        Text(
            if (report.reference.independent) {
                "Reference · ${report.reference.kind.wire}: ${report.reference.description}"
            } else {
                "The reference is not independent of the arms, so no accuracy row above is a valid score."
            },
            color = IdrPalette.textSecondary, style = IdrType.bodySmall,
        )
        Text(
            "No recorded phone session in this repository has an independent reference, so no report " +
                "for one exists yet. Scripted truth is independent of every arm by construction, but " +
                "it is a simulation, not field data: it cannot stand in for a surveyed or held-out " +
                "GNSS evaluation of a real drive.",
            color = IdrPalette.textMuted, style = IdrType.bodySmall,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The comparison itself
// ---------------------------------------------------------------------------------------------

@Composable
private fun ComparisonTable(report: EvaluationReport) {
    val sections = EvaluationSurface.sections(report)
    val width: Dp = labelColumnForArms + metricColumn * ArmId.entries.size
    IdrCard(emphasis = IdrEmphasis.PRIMARY) {
        IdrSectionLabel("Arm comparison")
        Text(
            "One column per configuration, in the order they build on each other. Every cell is " +
                "what that arm's own document carried for that metric.",
            color = IdrPalette.textSecondary, style = IdrType.bodySmall,
        )
        Column(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("evaluation_table"),
        ) {
            Column(Modifier.width(width)) {
                Row(Modifier.fillMaxWidth().background(IdrPalette.surfaceHigh)) {
                    ColumnHeaderCell("Metric", labelColumnForArms, IdrPalette.textMuted)
                    ArmId.entries.forEach { id ->
                        ColumnHeaderCell(armHeading[id] ?: id.wire, metricColumn, armTone(report, id))
                    }
                }
                sections.forEach { section ->
                    SectionHeaderRow(section, width)
                    section.rows.forEach { row -> MetricRowView(row) }
                }
            }
        }
        Text(
            "Values are printed to three decimals, exactly as the report states them; units are in " +
                "the row label. Anything an arm did not measure is named, not zeroed.",
            color = IdrPalette.textMuted, style = IdrType.monoSmall,
        )
    }
}

@Composable
private fun ColumnHeaderCell(title: String, width: Dp, tone: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.width(width).padding(horizontal = IdrSpace.sm, vertical = IdrSpace.sm),
        verticalArrangement = Arrangement.spacedBy(IdrSpace.xxs),
    ) {
        Text(title, color = tone, style = IdrType.label, maxLines = 2)
    }
}

@Composable
private fun SectionHeaderRow(section: MetricSection, width: Dp) {
    Column(
        Modifier.width(width).padding(horizontal = IdrSpace.sm).padding(top = IdrSpace.md, bottom = IdrSpace.xs),
        verticalArrangement = Arrangement.spacedBy(IdrSpace.xxs),
    ) {
        IdrSectionLabel(section.title)
        Text(section.note, color = IdrPalette.textMuted, style = IdrType.bodySmall)
    }
}

@Composable
private fun MetricRowView(row: MetricRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            row.label,
            color = IdrPalette.textSecondary,
            style = IdrType.bodySmall,
            modifier = Modifier.width(labelColumnForArms).padding(horizontal = IdrSpace.sm, vertical = IdrSpace.sm),
        )
        ArmId.entries.forEach { id ->
            val reading = row.readings[id] ?: NOT_IN_REPORT
            val absent = reading in absentWording
            Text(
                reading,
                color = if (absent) IdrPalette.textMuted else IdrPalette.textPrimary,
                style = IdrType.monoSmall,
                modifier = Modifier.width(metricColumn).padding(horizontal = IdrSpace.sm, vertical = IdrSpace.sm)
                    .testTag("evaluation_cell_${row.label}_${id.wire}"),
            )
        }
    }
}

/** What each column actually is, and why an arm that did not run has no numbers. */
@Composable
private fun ArmsCard(report: EvaluationReport) {
    IdrCard(emphasis = IdrEmphasis.SECONDARY) {
        IdrSectionLabel("Arms in this report")
        ArmId.entries.forEach { id ->
            val arm = report.arms.firstOrNull { it.armId == id }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                StatusDot(armTone(report, id), Modifier.padding(top = 5.dp))
                Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.xxs)) {
                    Text(
                        arm?.label ?: "${armHeading[id] ?: id.wire} — not in this report",
                        color = IdrPalette.textPrimary, style = IdrType.bodyMedium,
                    )
                    Text(
                        if (arm == null) {
                            "The report carries no arm for ${id.wire}."
                        } else {
                            "${arm.implementation.name} · ${arm.implementation.version} · ${arm.status.wire}"
                        },
                        color = IdrPalette.textMuted, style = IdrType.monoSmall,
                    )
                }
            }
        }
    }
}

/** Why a column is empty, in the arm's own words. */
@Composable
private fun AbsentCard(report: EvaluationReport) {
    val reasons = EvaluationSurface.absentReasons(report)
    if (reasons.isEmpty()) return
    IdrCard(emphasis = IdrEmphasis.UTILITY) {
        IdrSectionLabel("Why a column is empty")
        reasons.forEach { reason ->
            Text(reason, color = IdrPalette.textSecondary, style = IdrType.bodySmall,
                modifier = Modifier.testTag("evaluation_absent"))
        }
    }
}

@Composable
private fun ReloadRow(library: EvaluationLibrary, onReload: () -> Unit) {
    IdrCard(emphasis = IdrEmphasis.UTILITY) {
        IdrSectionLabel("Reports")
        Text(
            "This build ships one bundled report. A report produced elsewhere can be dropped into " +
                "${library.directory.ifEmpty { "the app's evaluation directory" }} and picked up here.",
            color = IdrPalette.textMuted, style = IdrType.bodySmall,
        )
        KeyValueRow("Showable", library.showable.size.toString(), mono = true, tone = IdrTone.SUCCESS)
        KeyValueRow("Refused", library.refused.size.toString(), mono = true,
            tone = if (library.refused.isEmpty()) IdrTone.NEUTRAL else IdrTone.DANGER)
        IdrButton("Reload reports", onReload, variant = IdrButtonVariant.SECONDARY, glyph = IdrGlyph.RESET,
            testTag = "evaluation_reload", minHeight = IdrSize.touchTarget)
    }
}

private fun armTone(report: EvaluationReport, id: ArmId): androidx.compose.ui.graphics.Color =
    when (report.arms.firstOrNull { it.armId == id }?.status) {
        ArmStatus.EVALUATED -> IdrPalette.success
        ArmStatus.NOT_RUN -> IdrPalette.warning
        ArmStatus.NOT_IMPLEMENTED -> IdrPalette.info
        null -> IdrPalette.textMuted
    }
