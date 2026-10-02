/*
 * The Evaluation tab's data rules, tested without a device and without a UI.
 *
 * The surface has exactly two jobs: refuse a document that does not pass the evaluation contract,
 * and show a number only where the report carries one. These tests pin both down against the
 * checked-in golden report and against the checked-in 30-case negative corpus, so the screen can
 * never quietly invent a metric, borrow one from another arm, or render half of an invalid file.
 */
package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.evaluation.EvaluationSurface
import com.intelligentdeadreckoning.app.evaluation.NOT_EVALUATED
import com.intelligentdeadreckoning.app.evaluation.NOT_IMPLEMENTED
import com.intelligentdeadreckoning.app.evaluation.NOT_IN_REPORT
import com.intelligentdeadreckoning.app.evaluation.NOT_MEASURED
import com.intelligentdeadreckoning.app.evaluation.ReportOrigin
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmId
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmStatus
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationCodec
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationSurfaceTest {

    private fun text(name: String) = javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
        .toString(Charsets.UTF_8)

    private fun golden(): EvaluationReport = EvaluationCodec.decode(text("golden_report.json"))

    private fun cells(report: EvaluationReport) =
        EvaluationSurface.table(report).flatMap { row -> ArmId.entries.map { row.readings.getValue(it) } }

    /** Every metric the request names is a row, in one place, in a fixed order. */
    @Test fun everyRequestedMetricIsARowOfTheComparison() {
        assertEquals(
            listOf(
                "Samples scored",
                "Outage duration (s)",
                "Outage distance (m)",
                "Final position error (m)",
                "Drift (% of outage distance)",
                "Position RMSE (m)",
                "Speed MAE (m/s)",
                "Speed RMSE (m/s)",
                "Heading error, mean abs (deg)",
                "Recovery convergence (s)",
                "Recovery bound (m)",
                "Navigation output (Hz)",
                "Inference latency p50 (ms)",
                "Inference latency p95 (ms)",
                "End-to-end p50 (ms)",
                "End-to-end p95 (ms)",
                "Queue high-water",
                "Drops",
                "Errors",
                "Memory peak (MB)",
            ),
            EvaluationSurface.table(golden()).map { it.label },
        )
    }

    /** The numbers on screen are the report's own, per arm, not a summary of them. */
    @Test fun everyArmIsShownWithItsOwnReportedNumbers() {
        val rows = EvaluationSurface.table(golden()).associateBy { it.label }
        assertEquals("691", rows.getValue("Samples scored").readings[ArmId.CLASSICAL_INS])
        assertEquals("692", rows.getValue("Samples scored").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("20.000", rows.getValue("Outage duration (s)").readings[ArmId.CLASSICAL_INS])
        assertEquals("240.000", rows.getValue("Outage distance (m)").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("540.065", rows.getValue("Final position error (m)").readings[ArmId.CLASSICAL_INS])
        assertEquals("1.098", rows.getValue("Final position error (m)").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("130.452", rows.getValue("Drift (% of outage distance)").readings[ArmId.CLASSICAL_INS])
        assertEquals("258.181", rows.getValue("Position RMSE (m)").readings[ArmId.CLASSICAL_INS])
        assertEquals("25.463", rows.getValue("Position RMSE (m)").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("7.164", rows.getValue("Speed MAE (m/s)").readings[ArmId.CLASSICAL_INS])
        assertEquals("8.693", rows.getValue("Speed RMSE (m/s)").readings[ArmId.CLASSICAL_INS])
        assertEquals("2.507", rows.getValue("Heading error, mean abs (deg)").readings[ArmId.CLASSICAL_INS])
        assertEquals("1.100", rows.getValue("Recovery convergence (s)").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("5.000", rows.getValue("Recovery bound (m)").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("9.886", rows.getValue("Navigation output (Hz)").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("3", rows.getValue("Queue high-water").readings[ArmId.CLASSICAL_FUSION])
        assertEquals("0", rows.getValue("Drops").readings[ArmId.CLASSICAL_FUSION])
        // The classical INS arm converges to nothing, and the cell says so rather than reading 0.
        assertEquals("not measured", rows.getValue("Recovery convergence (s)").readings[ArmId.CLASSICAL_INS])
    }

    /** A cell is a number the report carried or one of four named absences. Nothing else. */
    @Test fun aCellIsEitherAReportedNumberOrANamedAbsence() {
        val absent = setOf(NOT_MEASURED, NOT_EVALUATED, NOT_IN_REPORT, NOT_IMPLEMENTED)
        val number = Regex("-?\\d+(\\.\\d{3})?")
        cells(golden()).forEach { cell ->
            assertTrue("\"$cell\" is neither a reported number nor a named absence", cell in absent || number.matches(cell))
        }
        // Absent values stay absent: the host run measured no memory, and no model exists to time.
        val timingRows = EvaluationSurface.table(golden()).filter {
            it.label.startsWith("Memory") || it.label.startsWith("End-to-end") || it.label.startsWith("Inference")
        }
        timingRows.forEach { row ->
            ArmId.entries.forEach { id ->
                if (id == ArmId.FUSION_AI) assertEquals(NOT_IMPLEMENTED, row.readings.getValue(id))
                else assertEquals(NOT_MEASURED, row.readings.getValue(id))
            }
        }
    }

    /** An arm that did not run gives its reason, and its column carries no number at all. */
    @Test fun anArmThatDidNotRunExplainsEveryOneOfItsCells() {
        val report = golden()
        EvaluationSurface.table(report).forEach { row ->
            assertEquals(NOT_IMPLEMENTED, row.readings.getValue(ArmId.FUSION_AI))
        }
        val reasons = EvaluationSurface.absentReasons(report)
        assertTrue(reasons.toString(), reasons.any {
            it.startsWith("Fusion + AI correction:") && it.contains("No trained error-correction model")
        })
        // A report that says an arm was not run at all reads "not evaluated", and still explains why.
        val notRun = report.copy(arms = report.arms.map {
            if (it.armId == ArmId.FUSION_AI) it.copy(status = ArmStatus.NOT_RUN, reason = "Skipped on this run.") else it
        })
        EvaluationSurface.table(notRun).forEach { row ->
            assertEquals(NOT_EVALUATED, row.readings.getValue(ArmId.FUSION_AI))
        }
        assertTrue(EvaluationSurface.absentReasons(notRun).contains("Fusion + AI correction: Skipped on this run."))
    }

    /** An arm the document omits still gets a column, and the column says it is not in the report. */
    @Test fun anArmTheReportOmitsIsNamedRatherThanLeftBlankOrFilledIn() {
        val report = golden()
        val reduced = report.copy(arms = report.arms.filterNot { it.armId == ArmId.FUSION_MAP_MATCH })
        EvaluationSurface.table(reduced).forEach { row ->
            assertEquals(NOT_IN_REPORT, row.readings.getValue(ArmId.FUSION_MAP_MATCH))
        }
        assertTrue(
            EvaluationSurface.absentReasons(reduced).contains("fusion_map_match: not in this report"),
        )
        // The arms that are present keep their own columns untouched by the missing one.
        val rows = EvaluationSurface.table(reduced).associateBy { it.label }
        assertEquals("258.181", rows.getValue("Position RMSE (m)").readings[ArmId.CLASSICAL_INS])
    }

    /** An evaluated arm with no metrics reads "not measured" everywhere, never zero. */
    @Test fun anEvaluatedArmWithoutMetricsShowsNoNumbersAtAll() {
        val report = golden()
        val stripped = report.copy(arms = report.arms.map {
            if (it.armId == ArmId.CLASSICAL_FUSION) it.copy(accuracy = null, timing = null) else it
        })
        EvaluationSurface.table(stripped).forEach { row ->
            assertEquals(NOT_MEASURED, row.readings.getValue(ArmId.CLASSICAL_FUSION))
        }
        // It is still listed as an evaluated arm: the absence is a measurement gap, not a status.
        assertTrue(EvaluationSurface.absentReasons(stripped).none { it.startsWith("Classical fusion") })
    }

    /** Every case of the negative corpus is refused, with the code that refused it. */
    @Test fun aDocumentThatFailsTheContractIsRefusedRatherThanPartlyRead() {
        val cases = JsonParser.parseString(text("invalid_reports.json")).asJsonArray
        assertEquals(30, cases.size())
        val refused = mutableListOf<String>()
        cases.forEach { element ->
            val case = element.asJsonObject
            val name = case["name"].asString
            val available = EvaluationSurface.read(name, ReportOrigin.INSTALLED, case["input"].asString)
            assertFalse("$name was read", available.showable)
            assertNull("$name produced a report", available.report)
            assertEquals(name, case["code"].asString, available.refusal!!.substringBefore(" —"))
            refused += available.refusal!!
        }
        assertTrue(refused.all { it.endsWith("refused, not read leniently") })
    }

    /**
     * A non-finite token is refused as NONFINITE here too, quoted or bare, because JSON has no such
     * number and the two codecs meet the token at different parse layers. Python's `json` turns a
     * bare `NaN` into a float; Gson hands the same token over as a string. `tests/
     * test_evaluation_contract.py` pins the same rule from the other side.
     */
    @Test fun aNonFiniteNumberIsRefusedTheSameWayAsThePythonCodec() {
        val golden = text("golden_report.json")
        val measured = "\"position_rmse_m\": 258.180908"
        assertTrue(measured in golden)
        listOf("NaN", "Infinity", "-Infinity").forEach { token ->
            listOf("\"$token\"", token).forEach { spelling ->
                val available = EvaluationSurface.read(
                    "nonfinite.json",
                    ReportOrigin.INSTALLED,
                    golden.replace(measured, "\"position_rmse_m\": $spelling"),
                )
                assertFalse("$spelling was read", available.showable)
                assertEquals("NONFINITE", available.refusal!!.substringBefore(" —"))
            }
        }
        // An ordinary string where a number belongs is still a type error, not a non-finite one.
        val wrongType = EvaluationSurface.read(
            "wrong-type.json", ReportOrigin.INSTALLED,
            golden.replace(measured, "\"position_rmse_m\": \"258.18\""),
        )
        assertEquals("INVALID_TYPE", wrongType.refusal!!.substringBefore(" —"))
    }

    /** The surface states what the reference is, which session it scored, and how it was run. */
    @Test fun provenanceAndTheGnssTimelineAreStatedWithTheNumbers() {
        val report = golden()
        val provenance = EvaluationSurface.provenance(report)
        assertTrue(provenance, provenance.contains("scripted_truth"))
        assertTrue(provenance, provenance.contains("independent"))
        assertTrue(provenance, provenance.contains("host run"))
        assertTrue(provenance, provenance.contains("scripted-drive-70s"))
        assertTrue(provenance, provenance.contains("70 s"))
        assertEquals("gnss_good 30.0 s → denied 20.0 s → recovery 20.0 s", EvaluationSurface.segments(report))
    }

    /** The bundled document is the checked-in golden, and it decodes. */
    @Test fun theBundledDocumentIsTheCheckedInGoldenReport() {
        val available = EvaluationSurface.read("golden_report.json", ReportOrigin.BUNDLED, text("golden_report.json"))
        assertTrue(available.showable)
        assertNull(available.refusal)
        assertEquals(ReportOrigin.BUNDLED, available.origin)
        assertEquals("scripted-outage-70s", available.report!!.evaluationId)
        assertEquals(5, available.report!!.arms.size)
    }
}
