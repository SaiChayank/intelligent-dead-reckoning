package com.intelligentdeadreckoning.app.evaluation

import android.content.Context
import java.io.File

/**
 * The reports the Evaluation tab can show, and how they got here.
 *
 * The surface never invents a report. It lists exactly two kinds of document:
 *
 * - the one staged into the app's assets at build time from
 *   `contracts/evaluation/v1/golden_report.json` — the document the Kotlin reproducibility suite
 *   requires the harness to reproduce, so the app displays the checked-in evidence itself;
 * - whatever `.json` documents exist in the app's own `evaluation` directory, for a report produced
 *   on another device or by a later run.
 *
 * A document that fails the strict codec is listed as a refusal, with its code. It is never read
 * leniently and never partially rendered.
 */
data class EvaluationLibrary(
    val reports: List<AvailableReport> = emptyList(),
    /** Where an installed report goes, so the screen can say it instead of implying a directory. */
    val directory: String = "",
    /** Set only when the bundled document could not be read at all (a broken build, not a metric). */
    val problem: String? = null,
) {
    val showable: List<AvailableReport> get() = reports.filter { it.showable }
    val refused: List<AvailableReport> get() = reports.filter { !it.showable }
}

object EvaluationStore {
    /** The asset path of the report staged from `contracts/evaluation/v1` at build time. */
    const val BUNDLED_ASSET = "evaluation/golden_report.json"

    /** Installed reports live one level under the app's private directory. */
    const val INSTALLED_DIRECTORY = "evaluation"

    /** Bounded reads: this surface lists a handful of reports, never a scanned tree. */
    const val MAX_INSTALLED = 8
    const val MAX_BYTES = 1_000_000L

    /**
     * The pure part: turn the documents into a list of showable reports and refusals. The bundled
     * document is first, so the shipped evidence is what the surface opens on.
     */
    fun reports(bundled: String?, installed: List<Pair<String, String>>): List<AvailableReport> {
        val out = mutableListOf<AvailableReport>()
        if (bundled != null) {
            out += EvaluationSurface.read("golden_report.json", ReportOrigin.BUNDLED, bundled)
        }
        installed.sortedBy { it.first }.take(MAX_INSTALLED).forEach { (name, text) ->
            out += EvaluationSurface.read(name, ReportOrigin.INSTALLED, text)
        }
        // A document that decoded is named by its own evaluation id, never by its file name.
        return out.map { if (it.report != null) it.copy(name = it.report.evaluationId) else it }
    }

    /**
     * Android edge: read the staged asset and any report installed on the device. Failures to read
     * are reported as a problem line, never as a made-up report.
     */
    fun load(context: Context): EvaluationLibrary {
        val bundled = try {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes().decodeToString() }
        } catch (_: Exception) {
            null
        }
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, INSTALLED_DIRECTORY)
        val installed = try {
            (directory.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.endsWith(".json") }
                .sortedBy { it.name }
                .take(MAX_INSTALLED)
                .filter { it.length() in 1..MAX_BYTES }
                .map { it.name to it.readText() }
        } catch (_: Exception) {
            emptyList()
        }
        return EvaluationLibrary(
            reports = reports(bundled, installed),
            directory = directory.absolutePath,
            problem = if (bundled == null) {
                "The bundled evaluation report is missing from this build's assets."
            } else {
                null
            },
        )
    }
}
