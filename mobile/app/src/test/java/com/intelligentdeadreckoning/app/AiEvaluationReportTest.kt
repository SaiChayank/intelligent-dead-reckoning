package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.evaluation.EvaluationSurface
import com.intelligentdeadreckoning.app.evaluation.ReportOrigin
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmId
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmStatus
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiEvaluationReportTest {

    @Test
    fun testHyderabadAiEvaluationReportValidatesAndDecodes() {
        // Load the generated AI evaluation report asset
        val assetFile = File("../../mobile/app/src/main/assets/sample_drive/hyderabad-demo-drive/evaluation_report.json")
            .takeIf { it.exists() }
            ?: File("src/main/assets/sample_drive/hyderabad-demo-drive/evaluation_report.json")

        assertTrue("Asset file must exist at ${assetFile.absolutePath}", assetFile.exists())
        val jsonText = assetFile.readText()

        val report = EvaluationCodec.decode(jsonText)
        assertEquals("1.0.0", report.evaluationContractVersion)
        assertEquals("hyderabad-ai-dead-reckoning-70s", report.evaluationId)
        assertEquals(5, report.arms.size)

        val aiArm = report.arms.first { it.armId == ArmId.FUSION_AI }
        assertEquals(ArmStatus.EVALUATED, aiArm.status)
        val accuracy = aiArm.accuracy!!

        // The numbers are measured by SyntheticOutageMeasurementTest, so check consistency, not constants:
        // drift is end error over outage distance, and the outage is the scripted 20 s / 240 m.
        assertEquals(240.0, accuracy.outageDistanceM!!, 0.01)
        assertEquals(20.0, accuracy.outageDurationS!!, 0.01)
        assertEquals(100.0 * accuracy.finalPositionErrorM!! / accuracy.outageDistanceM!!, accuracy.driftPercent!!, 0.01)
        // A drift this low would mean the measurement window included the first returning GNSS fix again.
        assertTrue("drift ${accuracy.driftPercent}% is implausibly low", accuracy.driftPercent!! > 10.0)

        // Unmeasured values stay absent: no inference latency or memory is claimed.
        val timing = aiArm.timing!!
        assertNull(timing.inferenceLatencyP50Ms)
        assertNull(timing.memoryPeakMb)
        assertEquals(10.0, timing.outputHz!!, 1.0)

        // Map matching was not scored on this drive and must say so rather than show a number.
        val mapArm = report.arms.first { it.armId == ArmId.FUSION_MAP_MATCH }
        assertEquals(ArmStatus.NOT_RUN, mapArm.status)
        assertNull(mapArm.accuracy)

        // Verify EvaluationSurface table rendering
        val table = EvaluationSurface.table(report)
        assertTrue(table.isNotEmpty())
        val driftRow = table.first { it.label.contains("Drift") }
        val aiDriftReading = driftRow.readings[ArmId.FUSION_AI]
        assertNotNull(aiDriftReading)
        assertTrue("Rendered reading should show the measured drift", aiDriftReading!!.contains("%.1f".format(accuracy.driftPercent!!).take(2)))

        // Verify EvaluationSurface report reading
        val available = EvaluationSurface.read("hyderabad_ai_evaluation.json", ReportOrigin.INSTALLED, jsonText)
        assertTrue("Report must be showable", available.showable)
        assertEquals("hyderabad_ai_evaluation.json", available.name)
        assertEquals("hyderabad-ai-dead-reckoning-70s", available.report?.evaluationId)
    }
}
