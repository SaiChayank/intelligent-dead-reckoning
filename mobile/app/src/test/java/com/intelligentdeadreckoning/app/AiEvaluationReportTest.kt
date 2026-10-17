package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.evaluation.EvaluationSurface
import com.intelligentdeadreckoning.app.evaluation.ReportOrigin
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmId
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmStatus
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertNotNull(aiArm.accuracy)
        val accuracy = aiArm.accuracy!!

        // Problem Statement mandate: dead reckoning drift < 10%
        assertTrue("Drift must be < 10%, was ${accuracy.driftPercent}%", accuracy.driftPercent!! < 10.0)
        assertEquals(1.44, accuracy.driftPercent!!, 0.01)
        assertEquals(3.46, accuracy.finalPositionErrorM!!, 0.01)
        assertEquals(240.0, accuracy.outageDistanceM!!, 0.01)
        assertEquals(20.0, accuracy.outageDurationS!!, 0.01)

        // Verify timing
        assertNotNull(aiArm.timing)
        val timing = aiArm.timing!!
        assertTrue("Inference latency p50 must be sub-millisecond (< 1.0 ms), was ${timing.inferenceLatencyP50Ms} ms",
            timing.inferenceLatencyP50Ms!! < 1.0)

        // Verify EvaluationSurface table rendering
        val table = EvaluationSurface.table(report)
        assertTrue(table.isNotEmpty())
        val driftRow = table.first { it.label.contains("Drift") }
        val aiDriftReading = driftRow.readings[ArmId.FUSION_AI]
        assertNotNull(aiDriftReading)
        assertTrue("Rendered reading should contain 1.44%", aiDriftReading!!.contains("1.44"))

        // Verify EvaluationSurface report reading
        val available = EvaluationSurface.read("hyderabad_ai_evaluation.json", ReportOrigin.INSTALLED, jsonText)
        assertTrue("Report must be showable", available.showable)
        assertEquals("hyderabad_ai_evaluation.json", available.name)
        assertEquals("hyderabad-ai-dead-reckoning-70s", available.report?.evaluationId)
    }
}
