package com.intelligentdeadreckoning.app.map

/** One outage placed on the observed interval, as fractions of it. */
data class OutageMark(val startFraction: Float, val endFraction: Float, val durationNs: Long)

/**
 * Position outages over an observed window, oldest first, ready to draw.
 *
 * The window belongs to whoever observed it, not to a wall clock: real fixes give the first and the
 * last one, the scripted fixture gives its own elapsed clock. Either way a bar describes data that
 * exists rather than time that merely passed, so a window that is absent or of zero length yields
 * nothing at all — no bar is drawn, rather than an empty one. Every fraction is clamped into [0, 1]
 * so a mark can never be drawn outside the bar it belongs to, and a loss that would occupy no width
 * is dropped instead of being rounded up into one that looks real.
 */
fun outageMarks(observedStartNs: Long?, spanNs: Long, outages: List<Outage>): List<OutageMark> {
    val start = observedStartNs ?: return emptyList()
    if (spanNs <= 0L) return emptyList()
    return outages.mapNotNull { outage ->
        val from = ((outage.startNs - start).toDouble() / spanNs).coerceIn(0.0, 1.0)
        val to = ((outage.endNs - start).toDouble() / spanNs).coerceIn(0.0, 1.0)
        if (to <= from) null else OutageMark(from.toFloat(), to.toFloat(), outage.durationNs)
    }
}

/** The retained losses placed over the fixes that were actually observed. */
fun RecordedSessionMap.Stats.outageMarks(): List<OutageMark> =
    outageMarks(observedStartNs, spanNs, outages)

/** The scripted fixture's own losses over its own clock. This window ends at the present tick
 * rather than at a fix, so its last interval is allowed to still be open: it is drawn up to now,
 * which is time the fixture really has run, and never past it. */
fun DemoSnapshot.outageMarks(): List<OutageMark> =
    outageMarks(0L, elapsedMs * 1_000_000L, denials)

/**
 * How long the current loss has lasted, or null when the fix the processor last saw is still
 * current. The loss begins when that fix went stale, so the stale interval is subtracted: the raw
 * fix age would report a loss that had not started yet.
 */
fun currentOutageSeconds(
    fixAgeSeconds: Double?,
    staleNs: Long = RecordedSessionMap.DEFAULT_GAP_NS,
): Double? {
    val age = fixAgeSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val stale = staleNs / 1_000_000_000.0
    return if (age <= stale) null else age - stale
}
