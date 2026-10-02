/*
 * Where the Evaluation tab's reports come from.
 *
 * The store is the only thing that decides what the surface can show, so it is tested without a
 * device: a decodable document is named by its own evaluation id, an undecodable one is kept as a
 * refusal rather than dropped, the bundled document always leads, and the read stays bounded.
 */
package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.evaluation.EvaluationLibrary
import com.intelligentdeadreckoning.app.evaluation.EvaluationStore
import com.intelligentdeadreckoning.app.evaluation.ReportOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationStoreTest {

    private fun text(name: String) = javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
        .toString(Charsets.UTF_8)

    private val golden get() = text("golden_report.json")

    /** Same document, another run's identity: the store must name it by its own id. */
    private val otherGolden get() = golden.replace("scripted-outage-70s", "installed-run")

    /** Well-formed JSON that violates the contract: the version is not the one this codec speaks. */
    private val wrongVersion get() = golden.replace("\"evaluation_contract_version\": \"1.0.0\"",
        "\"evaluation_contract_version\": \"9.9.9\"")

    @Test fun theBundledReportLeadsAndInstalledReportsFollowByName() {
        val reports = EvaluationStore.reports(
            golden,
            listOf("b.json" to otherGolden, "a.json" to wrongVersion),
        )
        assertEquals(3, reports.size)
        assertEquals(ReportOrigin.BUNDLED, reports[0].origin)
        assertEquals("scripted-outage-70s", reports[0].name)
        assertTrue(reports[0].showable)
        assertEquals("a.json", reports[1].name)
        assertFalse(reports[1].showable)
        assertEquals("INVALID_VERSION", reports[1].refusal!!.substringBefore(" —"))
        assertEquals("installed-run", reports[2].name)
        assertTrue(reports[2].showable)
    }

    @Test fun anUndecodableDocumentIsKeptAsARefusalInsteadOfBeingDropped() {
        val reports = EvaluationStore.reports(null, listOf("broken.json" to "{ not json at all", "empty.json" to ""))
        assertEquals(2, reports.size)
        reports.forEach {
            assertFalse(it.showable)
            assertTrue(it.refusal!!.isNotBlank())
            assertTrue(it.refusal!!.endsWith("refused, not read leniently"))
        }
        assertEquals(listOf("broken.json", "empty.json"), reports.map { it.name })
    }

    @Test fun theReadIsBoundedAndAnEmptyDeviceStillShowsTheBundledReport() {
        val many = (1..12).map { "report-$it.json" to golden }
        assertEquals(1 + EvaluationStore.MAX_INSTALLED, EvaluationStore.reports(golden, many).size)
        assertEquals(EvaluationStore.MAX_INSTALLED, EvaluationStore.reports(golden, many).count { it.origin == ReportOrigin.INSTALLED })
        // With nothing installed and no bundled asset there is nothing to show, and no placeholder.
        assertEquals(0, EvaluationStore.reports(null, emptyList()).size)
    }

    @Test fun theLibraryCountsWhatItCanShowAndWhatItRefused() {
        val library = EvaluationLibrary(
            EvaluationStore.reports(golden, listOf("a.json" to wrongVersion, "b.json" to "{ not json at all")),
        )
        assertEquals(1, library.showable.size)
        assertEquals(2, library.refused.size)
        assertFalse(library.refused.any { it.showable })
    }
}
