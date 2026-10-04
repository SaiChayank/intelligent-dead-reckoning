package com.intelligentdeadreckoning.app.map

/**
 * Presentation-only easing of the drawn position marker between two published positions.
 *
 * This is a display effect and nothing else: it never estimates, extends or corrects a
 * navigation state. The trail, status, values and every other element always show published
 * truth, and the eased marker never moves past the newest published point — between
 * publications it interpolates, after them it holds. When the published point disappears
 * (engine stopped, state expired) the marker stops with it: there is no extrapolation after
 * engine stop, only an ease inside the span two real publications already bracket.
 */
class MarkerAnimation(private val easeMs: Long = 300L) {
    init { require(easeMs > 0) }

    private var from: MapPoint? = null
    private var to: MapPoint? = null
    private var sinceNs = 0L

    /** Publish the next displayed position. Null publishes "nothing is drawn". */
    fun publish(point: MapPoint?, nowNs: Long) {
        val current = display(nowNs)
        from = current
        to = point
        sinceNs = nowNs
    }

    /** The eased display position at [nowNs]: inside [from, to], holding at [to], never beyond. */
    fun display(nowNs: Long): MapPoint? {
        val target = to ?: return null
        val origin = from ?: return target
        val span = easeMs * 1_000_000L
        val progress = ((nowNs - sinceNs).coerceIn(0L, span)).toDouble() / span
        return MapPoint(
            origin.latitude + (target.latitude - origin.latitude) * progress,
            origin.longitude + (target.longitude - origin.longitude) * progress,
        )
    }

    /** True once the eased marker has reached its target (or has none): drawing can idle. */
    fun settled(nowNs: Long): Boolean = to == null || nowNs - sinceNs >= easeMs * 1_000_000L

    /** Forget everything (a new session or source). */
    fun reset() {
        from = null
        to = null
        sinceNs = 0L
    }
}
