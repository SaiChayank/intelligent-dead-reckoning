package com.intelligentdeadreckoning.app.map

import com.intelligentdeadreckoning.app.matching.MapMatcher
import com.intelligentdeadreckoning.app.matching.MapMatchResult
import com.intelligentdeadreckoning.app.matching.MapMatchStatus
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source

/**
 * Engine output folded into map display state: the NavigationEngine → NavigationState →
 * [NavigationPresentation] → MapOverlay → MapLibreRenderer pipeline between the engine and
 * the renderer.
 *
 * What it does, and nothing else:
 * - pairs each published [NavigationState] with the [Confidence] record the engine published
 *   beside it (same measurement time) and hands both to [NavigationPresentation], which owns
 *   every validation and staleness rule;
 * - carries the engine's contract `localization_mode` into the presentation;
 * - when — and only when — the evaluation toggle supplies a [RoadGraph], runs the causal
 *   [MapMatcher] over the raw published position and records the map-matched claim as a
 *   parallel output beside the raw one. The raw position, trail and status are never
 *   modified: matching is an evaluation overlay on published positions, not navigation.
 *
 * Deliberately absent: no sensors, no propagation, no smoothing of the published sequence,
 * no map rendering. One engine session at a time: a record from another session restarts the
 * fold, and nothing is ever carried across sessions.
 */
class EngineSessionMap(private val capacity: Int = 512) {
    init { require(capacity in 2..4096) }

    private var presentation: NavigationPresentation? = null
    private var sessionHeader: Header? = null
    private var pendingNavigation: Record? = null
    private var lastAugmented: MapPresentation? = null
    private var matcher: MapMatcher? = null
    private var evaluationGraph: RoadGraph? = null
    private val matchedTrail = ArrayDeque<MapPoint>()
    private var lastMatch: MapMatchResult? = null
    var matchingIssue: String? = null
        set(value) {
            field = value
            lastAugmented = lastAugmented?.copy(matchingIssue = value)
        }

    /** The evaluation overlay: a road graph turns map matching on, null turns it off. */
    var evaluation: RoadGraph? = null
        set(value) {
            if (field !== value) {
                matcher = null
                matchedTrail.clear()
                lastMatch = null
                lastAugmented = null
                if (value != null) matchingIssue = null
            }
            field = value
        }

    /**
     * Fold one engine output record. A [NavigationState] is held until its [Confidence]
     * arrives (the engine publishes state first, confidence second, same measurement time);
     * if another navigation state arrives first, the held one is published without an
     * accuracy — a missing confidence is left missing, never invented.
     */
    fun accept(record: Record, nowNs: Long): MapPresentation {
        // The runtime forwards only records it has bound to its own session, so a different header
        // means the engine has moved on to another session: restart the fold there rather than
        // mixing two sessions into one trail. Nothing is carried over — not the trail, not the
        // held state, not the matched overlay — and the previous session's position leaves the
        // screen with it instead of standing in for the new one.
        if (sessionHeader != null && sessionHeader != record.header) restart()
        sessionHeader = record.header
        when (val data = record.event.data) {
            is Confidence -> {
                val pending = pendingNavigation
                if (pending != null && pending.event.t_ns == record.event.t_ns) {
                    pendingNavigation = null
                    return present(pending, nowNs, record)
                }
                return snapshot(nowNs)
            }
            is NavigationState -> {
                val pending = pendingNavigation
                pendingNavigation = record
                if (pending == null) return snapshot(nowNs)
                return present(pending, nowNs, null)
            }
            else -> return snapshot(nowNs)
        }
    }

    /**
     * The current display state. Expires exactly as [NavigationPresentation] decides:
     * a stale or stopped stream hides its position rather than holding it on screen, and an
     * expired state expires its map-matched overlay with it.
     */
    fun snapshot(nowNs: Long): MapPresentation {
        val source = sessionHeader?.source ?: Source.REAL
        val base = presentation?.snapshot(nowNs) ?: MapPresentation(source, status = "No engine output yet")
        if (base.point == null) {
            matchedTrail.clear()
            lastMatch = null
            lastAugmented = null
            return base.copy(matchingIssue = matchingIssue)
        }
        return (lastAugmented ?: base).copy(matchingIssue = matchingIssue)
    }

    /** Forget everything: the next record starts a fresh session fold. */
    fun reset() {
        restart()
        sessionHeader = null
    }

    private fun restart() {
        presentation = null
        pendingNavigation = null
        lastAugmented = null
        matcher = null
        matchedTrail.clear()
        lastMatch = null
        matchingIssue = null
    }

    private fun present(record: Record, nowNs: Long, confidence: Record?): MapPresentation {
        val nav = record.event.data as NavigationState
        val adapter = presentation ?: NavigationPresentation(record.header, nav.initialization_mode, capacity)
            .also { presentation = it }
        val state = adapter.accept(record, nowNs, confidence)
        val withMode = (if (state.point != null && nav.localization_mode != null) {
            state.copy(localizationMode = nav.localization_mode!!.wire)
        } else state).copy(matchingIssue = matchingIssue)
        return augment(withMode, record)
    }

    /**
     * Attach the evaluation-only map-matched claim to an accepted raw state. The raw fields
     * are read, never written: [MapPresentation.point] and [MapPresentation.trail] remain the
     * navigation truth whatever the matcher says. A fix without a published confidence has no
     * honest uncertainty to match with, so it is left unmatched rather than matched with an
     * invented one.
     */
    private fun augment(state: MapPresentation, record: Record): MapPresentation {
        val point = state.point
        if (point == null) {
            matchedTrail.clear()
            lastMatch = null
            lastAugmented = state
            return state
        }
        val graph = evaluation
        if (matchingIssue != null) {
            lastAugmented = state.copy(matchingIssue = matchingIssue)
            return lastAugmented!!
        }
        // The gate consumes whichever uncertainty the engine actually published: the calibrated
        // 95% accuracy when one exists, otherwise the covariance it published as UNVALIDATED.
        // It is never the platform's own fix radius (`fixRadiusMetres`), which is a provider
        // figure and not fused confidence, and it is never invented when neither exists.
        val accuracy = state.accuracy95Metres ?: state.unvalidatedAccuracy95Metres
        if (graph == null || accuracy == null) {
            lastAugmented = state
            return state
        }
        val matcher = matcher ?: MapMatcher(graph).also { matcher = it }
        val result = matcher.match(
            record.event.t_ns,
            point.latitude,
            point.longitude,
            state.headingDegrees?.let { Math.toRadians(it) },
            accuracy,
            state.speedMetresPerSecond,
        )
        lastMatch = result
        val matched = if (result.status == MapMatchStatus.MATCHED && result.matchedLatitudeDeg != null) {
            MapPoint(result.matchedLatitudeDeg, result.matchedLongitudeDeg!!)
        } else null
        if (matched != null) {
            if (matchedTrail.lastOrNull() != matched) {
                matchedTrail.addLast(matched)
                while (matchedTrail.size > capacity) matchedTrail.removeFirst()
            }
        }
        val augmented = state.copy(
            matchedPoint = matched,
            matchedTrail = if (matched != null) matchedTrail.toList() else emptyList(),
            matchConfidence = result.confidence.takeIf { matched != null || result.candidateCount > 0 },
            matchedEdgeId = result.matchedEdgeId,
            matcherVersion = result.matcherVersion,
        )
        lastAugmented = augmented
        return augmented
    }
}
