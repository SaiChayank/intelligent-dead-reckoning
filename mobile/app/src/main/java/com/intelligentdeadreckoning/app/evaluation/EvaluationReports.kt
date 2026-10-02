package com.intelligentdeadreckoning.app.evaluation

import com.intelligentdeadreckoning.contracts.evaluation.v1.Arm
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmId
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmStatus
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationCodec
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationContractException
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationReport
import java.util.Locale

/** What the surface is allowed to say about a table cell. A missing value is stated, never zero. */
const val NOT_MEASURED = "not measured"
const val NOT_EVALUATED = "not evaluated"
const val NOT_IN_REPORT = "not in this report"
const val NOT_IMPLEMENTED = "not implemented"

/** Where a report came from, so the screen can say how it got here. */
enum class ReportOrigin(val label: String) {
    /** Staged into the app's assets at build time from `contracts/evaluation/v1/golden_report.json`. */
    BUNDLED("shipped with this build"),
    /** A document found in the app's own `evaluation` directory. */
    INSTALLED("installed on this device"),
}

/** One report the app can show, or the reason it cannot be shown at all. */
data class AvailableReport(
    val name: String,
    val origin: ReportOrigin,
    val report: EvaluationReport?,
    val refusal: String?,
) {
    val showable: Boolean get() = report != null
}

/** One comparison row: a metric and what each arm reported for it. */
data class MetricRow(val label: String, val readings: Map<ArmId, String>)

/** One labelled block of rows. Accuracy and runtime are different kinds of evidence. */
data class MetricSection(val title: String, val note: String, val rows: List<MetricRow>)

/**
 * The evaluation surface's data rules, separated from its rendering so they can be tested without a
 * device or a UI.
 *
 * Two rules, and nothing else:
 * - a document that does not pass the strict evaluation codec is **refused**, with the code that
 *   refused it, rather than read leniently;
 * - a number is shown only when the report contains it. A metric an arm did not measure reads
 *   "not measured"; an arm that is not in the report reads "not in this report"; an arm that did not
 *   run reads why. Nothing is filled in, averaged, or borrowed from another arm.
 */
object EvaluationSurface {

    fun read(name: String, origin: ReportOrigin, text: String): AvailableReport = try {
        AvailableReport(name, origin, EvaluationCodec.decode(text), null)
    } catch (error: Exception) {
        val code = (error as? EvaluationContractException)?.code ?: "UNREADABLE"
        AvailableReport(name, origin, null, "$code — refused, not read leniently")
    }

    /** The wording for one arm's cell in a row it did not report. */
    private fun absent(arm: Arm?): String = when {
        arm == null -> NOT_IN_REPORT
        arm.status == ArmStatus.NOT_IMPLEMENTED -> NOT_IMPLEMENTED
        arm.status == ArmStatus.NOT_RUN -> NOT_EVALUATED
        else -> NOT_MEASURED
    }

    private fun value(arm: Arm?, read: (Arm) -> Double?): String {
        val metric = arm?.let(read) ?: return absent(arm)
        return String.format(Locale.ROOT, "%.3f", metric)
    }

    private fun integer(arm: Arm?, read: (Arm) -> Long?): String =
        arm?.let(read)?.toString() ?: absent(arm)

    /**
     * The comparison table over every known arm, in the order the request names them. An arm the
     * report does not carry still gets a column, saying exactly that.
     *
     * Accuracy rows and runtime rows are separated because they answer different questions and
     * carry different limits — one is scored against a reference, the other is not scored at all.
     */
    fun sections(report: EvaluationReport): List<MetricSection> {
        val arms = report.arms.associateBy { it.armId }
        fun row(label: String, read: (Arm) -> Double?): MetricRow =
            MetricRow(label, ArmId.entries.associateWith { value(arms[it], read) })
        fun rowInt(label: String, read: (Arm) -> Long?): MetricRow =
            MetricRow(label, ArmId.entries.associateWith { integer(arms[it], read) })
        return listOf(
            MetricSection(
                "Accuracy against the reference",
                "Error of each arm's published positions against ${report.reference.description.trimEnd('.')}.",
                listOf(
                    rowInt("Samples scored") { it.accuracy?.samples },
                    row("Outage duration (s)") { it.accuracy?.outageDurationS },
                    row("Outage distance (m)") { it.accuracy?.outageDistanceM },
                    row("Final position error (m)") { it.accuracy?.finalPositionErrorM },
                    row("Drift (% of outage distance)") { it.accuracy?.driftPercent },
                    row("Position RMSE (m)") { it.accuracy?.positionRmseM },
                    row("Speed MAE (m/s)") { it.accuracy?.speedMaeMS },
                    row("Speed RMSE (m/s)") { it.accuracy?.speedRmseMS },
                    row("Heading error, mean abs (deg)") { it.accuracy?.headingErrorDeg },
                    row("Recovery convergence (s)") { it.accuracy?.recoveryConvergenceS },
                    row("Recovery bound (m)") { it.accuracy?.recoveryThresholdM },
                ),
            ),
            MetricSection(
                "Runtime",
                "Measured on the run's own clock. A value no run produced is absent, not zero.",
                listOf(
                    row("Navigation output (Hz)") { it.timing?.outputHz },
                    row("Inference latency p50 (ms)") { it.timing?.inferenceLatencyP50Ms },
                    row("Inference latency p95 (ms)") { it.timing?.inferenceLatencyP95Ms },
                    row("End-to-end p50 (ms)") { it.timing?.endToEndP50Ms },
                    row("End-to-end p95 (ms)") { it.timing?.endToEndP95Ms },
                    rowInt("Queue high-water") { it.timing?.queueHighWater },
                    rowInt("Drops") { it.timing?.drops },
                    rowInt("Errors") { it.timing?.errors },
                    row("Memory peak (MB)") { it.timing?.memoryPeakMb },
                ),
            ),
        )
    }

    /** Every row of every section, in order: the whole table without its headings. */
    fun table(report: EvaluationReport): List<MetricRow> = sections(report).flatMap { it.rows }

    /** The one-line provenance of a report: what the numbers are, and what they are not. */
    fun provenance(report: EvaluationReport): String {
        val platform = if (report.platform.host) "host run" else "device run"
        val independence = if (report.reference.independent) "independent" else "NOT independent"
        return "Reference ${report.reference.kind.wire} ($independence) · $platform · session " +
            "${report.session.sessionId} · ${report.session.durationS.toInt()} s"
    }

    /** The session's GNSS timeline, in the order it happened, so the segments are readable. */
    fun segments(report: EvaluationReport): String = report.segments.joinToString(" → ") { segment ->
        val seconds = (segment.endNs - segment.startNs) / 1_000_000_000.0
        String.format(Locale.ROOT, "%s %.1f s", segment.kind.wire, seconds)
    }

    /** One line per arm that did not run, so an empty column is never left unexplained. */
    fun absentReasons(report: EvaluationReport): List<String> =
        report.arms.filter { it.status != ArmStatus.EVALUATED }
            .map { "${it.label}: ${it.reason ?: "no reason recorded"}" } +
            ArmId.entries.filter { id -> report.arms.none { it.armId == id } }
                .map { "${it.wire}: not in this report" }
}
