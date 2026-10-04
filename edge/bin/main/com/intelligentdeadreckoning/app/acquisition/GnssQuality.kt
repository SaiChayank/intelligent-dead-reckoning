package com.intelligentdeadreckoning.app.acquisition

import com.intelligentdeadreckoning.contracts.v1.GnssQualityState
import com.intelligentdeadreckoning.contracts.v1.GnssState
import com.intelligentdeadreckoning.contracts.v1.Severity

/**
 * Deterministic GNSS quality and outage state machine.
 *
 * The state set is the frozen contract's `GnssState`; this file adds no new states and
 * no new contract field. The machine answers one question only: may the newest fix on
 * the newest channel be used as a GNSS reference right now, and if not, which named
 * check failed. It is a pure function of its inputs apart from the transition
 * bookkeeping in [GnssQualityManager], so the same input always yields the same
 * decision and every decision is reproducible from recorded bytes.
 *
 * ## Where the thresholds come from
 *
 * `docs/PS26168_Non_ML_Baseline_Navigation_System.md` fixes the shape of this machine
 * and states that the exact thresholds require validation. They are therefore not
 * chosen here: each one is derived from a measurement over the 45 real recordings in
 * `mobile/artifacts/`, produced read-only by `tools/gnss_quality_thresholds.py` and
 * published in `reports/gnss_quality_thresholds_2026_09_30.md`. The measured facts the
 * defaults rely on:
 *
 * - `gps` fixes arrived every 0.998666-1.001026 s (1,659 intervals, p50 1.000007 s).
 * - `network` fixes arrived every 1.002625-20.135143 s (118 intervals, p50 20.103928 s),
 *   i.e. 0.05 Hz, not the 1 Hz the subscription requests.
 * - Horizontal accuracy was present on all 1,782 fixes; 1,202 of the 1,660 GPS values
 *   were the identical number 9.935046 m, and 119 of the 122 network values were the
 *   identical 100.0 m. The provider repeats a cached estimate, so the field cannot
 *   discriminate quality on its own.
 * - GPS horizontal accuracy p90 11.297 m, p99 13.836 m, max 16.065 m; vertical p99
 *   9.814 m, max 13.069 m. 5 m would reject 96.8% of healthy recorded GPS fixes.
 * - 4 of 45 sessions carried GNSS at all, and 1,660 of 1,782 fixes were on one channel,
 *   so no fix below 5 satellites was ever recorded.
 * - `bearing_deg` was null on all 1,782 fixes and `speed_m_s` null on 122 of them.
 *
 * ## What this machine deliberately does not do
 *
 * - It does not claim an accuracy figure. `GOOD` means the fix passed every named
 *   check below; it is not a statement about navigation error, and no threshold here
 *   is presented as a real-world error bound.
 * - It does not smooth, interpolate or fill anything. A missing optional field stays
 *   missing and is named in the reason codes.
 * - It does not read a clock. `nowNs` is an input, so age is reproducible.
 * - It does not consult the navigation engine. See [GnssInnovationTrust].
 */
enum class GnssReason(val code: String, val decisive: Boolean) {
    /** No location grant was ever requested; IMU acquisition is unaffected. */
    LOCATION_NOT_REQUESTED("LOCATION_NOT_REQUESTED", true),

    /** The user refused the request. */
    PERMISSION_DENIED("PERMISSION_DENIED", true),

    /** A previous grant was withdrawn; in-memory permission history proved it. */
    PERMISSION_REVOKED("PERMISSION_REVOKED", true),

    /** Location is switched off on the device. */
    PROVIDER_DISABLED("PROVIDER_DISABLED", true),

    /** A channel reported a provider this policy has no profile for. */
    PROVIDER_UNKNOWN("PROVIDER_UNKNOWN", true),

    /** The caller's validation, or the timestamp arithmetic here, rejected the times. */
    TIMESTAMP_INVALID("TIMESTAMP_INVALID", true),

    /** Precise/approximate access is held but no fix has arrived yet. */
    NO_FIX("NO_FIX", true),

    /** The fix predates this acquisition session; it is cached, not new evidence. */
    PRE_SESSION_FIX("PRE_SESSION_FIX", true),

    /** The newest fix is older than this provider's stale bound. */
    STALE_FIX("STALE_FIX", true),

    /** Approximate access caps trust: a network fix cannot reach a navigation-ready state. */
    APPROXIMATE_LOCATION("APPROXIMATE_LOCATION", true),

    /** The provider reported no horizontal accuracy, so nothing can be checked. */
    HORIZONTAL_ACCURACY_UNKNOWN("HORIZONTAL_ACCURACY_UNKNOWN", true),

    /** Reported horizontal accuracy exceeds the bound. */
    HORIZONTAL_ACCURACY_POOR("HORIZONTAL_ACCURACY_POOR", true),

    /** Satellites are expected from this provider and none were reported. */
    SATELLITES_UNKNOWN("SATELLITES_UNKNOWN", true),

    /** Fewer satellites than a 3D solution needs. */
    SATELLITES_LOW("SATELLITES_LOW", true),

    /** An externally supplied corroboration rejected this fix. See [GnssInnovationTrust]. */
    INNOVATION_REJECTED("INNOVATION_REJECTED", true),

    /** Supplementary: no speed. Course/velocity aiding is unavailable, position is not. */
    SPEED_UNAVAILABLE("SPEED_UNAVAILABLE", false),

    /** Supplementary: no bearing. Recorded GNSS course has never been available. */
    BEARING_UNAVAILABLE("BEARING_UNAVAILABLE", false),

    /** Supplementary: no vertical accuracy to report. */
    VERTICAL_ACCURACY_UNKNOWN("VERTICAL_ACCURACY_UNKNOWN", false),

    /** Supplementary: vertical accuracy exceeds the bound. Reported, never decisive. */
    VERTICAL_ACCURACY_POOR("VERTICAL_ACCURACY_POOR", false),
}

/** Stable diagnostic codes for quality transitions. */
enum class GnssTransitionCode(val code: String, val severity: Severity) {
    /** Entering a state in which no usable fix exists. */
    GNSS_OUTAGE("GNSS_OUTAGE", Severity.WARNING),

    /** Leaving such a state. */
    GNSS_RECOVERED("GNSS_RECOVERED", Severity.INFO),

    /** Moving between two usable states, for example DEGRADED to GOOD. */
    GNSS_QUALITY_CHANGED("GNSS_QUALITY_CHANGED", Severity.INFO),
}

/** A provider's measured cadence and whether it is expected to report satellite counts. */
data class GnssProviderProfile(
    /** Measured p50 inter-fix interval. Used only to derive the stale bound. */
    val nominalFixIntervalNs: Long,
    /** False for providers that structurally never report satellites, such as `network`. */
    val satellitesExpected: Boolean,
)

/**
 * The validated thresholds. Construct with defaults; override only to test a different
 * policy explicitly. Every default cites its source in the file header.
 */
data class GnssQualityPolicy(
    val providers: Map<String, GnssProviderProfile> = DEFAULT_PROVIDERS,
    /**
     * Consecutive nominal intervals of silence tolerated before a channel is stale.
     *
     * Two tolerates exactly one lost fix: the age at the next arrival is one nominal
     * interval over the bound, and two consecutive losses cross it. Measured cost of
     * two on this corpus is zero false-STALE results: 0 of 1,659 GPS intervals exceed
     * 2.0 s and 0 of 118 network intervals exceed 40.2 s, while one shared 5 s bound
     * would have called the healthy 0.05 Hz network channel stale in 117 of 118.
     */
    val staleMissedIntervals: Int = 2,
    /**
     * Largest reported horizontal accuracy still treated as usable.
     *
     * `Location.getAccuracy()` is the provider's own 68% radius, not a guaranteed
     * error. 15 m sits above the corpus's measured GPS p99 of 13.836 m and its 16.065 m
     * maximum, so healthy recorded fixes stay usable, and below the network provider's
     * repeated 100.0 m placeholder.
     */
    val maximumHorizontalAccuracyM: Double = 15.0,
    /**
     * Largest reported vertical accuracy still reported as usable. Supplementary only:
     * it never decides the state, because no altitude reference exists in the corpus.
     */
    val maximumVerticalAccuracyM: Double = 15.0,
    /**
     * Fewest satellites for a 3D solution. Four is the arithmetic minimum for four
     * unknowns, a stated requirement rather than a measurement; the corpus never
     * recorded fewer than five, so it is consistent with every recorded fix.
     */
    val minimumSatellites: Int = 4,
) {
    fun profile(provider: String?): GnssProviderProfile? = provider?.let { providers[it] }

    /** Stale bound in ns for a known provider, or null when the provider is unknown. */
    fun staleAfterNs(provider: String?): Long? =
        profile(provider)?.let { it.nominalFixIntervalNs * staleMissedIntervals }

    companion object {
        val DEFAULT_PROVIDERS: Map<String, GnssProviderProfile> = mapOf(
            "gps" to GnssProviderProfile(1_000_000_000L, satellitesExpected = true),
            "network" to GnssProviderProfile(20_100_000_000L, satellitesExpected = false),
        )
    }
}

/** The newest fix on the newest channel, reduced to what the policy consumes. */
data class GnssFixEvidence(
    /** Measurement timestamp on the monotonic elapsed-realtime clock. */
    val tNs: Long,
    /** Receipt timestamp on the same clock; must not precede [tNs]. */
    val receivedNs: Long,
    val horizontalAccuracyM: Double?,
    val verticalAccuracyM: Double?,
    val satellitesUsed: Int?,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
)

/**
 * An optional, externally supplied corroboration of one fix, intended for a later
 * EKF innovation test.
 *
 * The direction is the whole point. This is an *input*: the quality manager never
 * queries a navigation engine, holds no reference to one, and emits nothing the
 * engine reads to compute this value. A caller that owns both pushes it in. It can
 * only ever *lower* trust, never raise it, so no cycle can form between a quality
 * decision and the innovation test that consumes it.
 */
data class GnssInnovationTrust(
    /** False when the fix disagreed with prediction. True is corroboration, not a promotion. */
    val accepted: Boolean,
    /** Optional short note for diagnostics, for example a residual summary. */
    val detail: String? = null,
)

/** Everything the policy reads. All of it is observation, none of it is policy. */
data class GnssQualityInput(
    /** Clock reading to age the newest fix against. Supplied, never sampled internally. */
    val nowNs: Long,
    /** Session origin, so a cached fix from before this session can be named as such. */
    val originNs: Long,
    val permission: LocationAccess,
    /** Whether the platform reports the location provider as enabled. */
    val providerEnabled: Boolean,
    /** Channel provider label, or null when no channel exists yet. */
    val provider: String?,
    /** Newest fix on the newest channel, or null when none has arrived. */
    val lastFix: GnssFixEvidence?,
    /** The caller's timestamp validation, for example a codec round-trip check. */
    val timestampsValid: Boolean = true,
    /** Optional one-way corroboration. Omitted by default; never required. */
    val innovation: GnssInnovationTrust? = null,
)

/** The decision. [reasons] is ordered: the decisive code first, then supplementary notes. */
data class GnssQualityDecision(
    val state: GnssState,
    val fixAgeS: Double?,
    val satellitesUsed: Long?,
    val reasons: List<String>,
    val provider: String?,
) {
    /**
     * True when no fix may be used as a GNSS reference. DEGRADED is deliberately not an
     * outage: a degraded fix is still evidence, just not a trusted one.
     */
    val outage: Boolean
        get() = state == GnssState.UNAVAILABLE || state == GnssState.ACQUIRING ||
            state == GnssState.STALE || state == GnssState.DENIED

    /** The frozen contract payload. No field is invented and none is left unset. */
    fun toContract(): GnssQualityState = GnssQualityState(state, fixAgeS, satellitesUsed, reasons)
}

/** A change of state, carrying exactly one diagnostic code to publish. */
data class GnssQualityTransition(
    val from: GnssState,
    val to: GnssState,
    val code: GnssTransitionCode,
    val outageStarted: Boolean,
    val outageEnded: Boolean,
) {
    val message: String
        get() = when (code) {
            GnssTransitionCode.GNSS_OUTAGE ->
                "GNSS quality entered ${to.wire}; no usable fix is available."
            GnssTransitionCode.GNSS_RECOVERED ->
                "GNSS quality recovered from ${from.wire} to ${to.wire}."
            GnssTransitionCode.GNSS_QUALITY_CHANGED ->
                "GNSS quality changed from ${from.wire} to ${to.wire}."
        }
}

/** The pure state machine. No state, no clock, no side effects. */
object GnssQuality {
    /**
     * Evaluate one decision. Deterministic: identical inputs always produce an identical
     * decision, and the two phases below are the whole specification.
     *
     * **Phase one, preconditions.** Permission, provider, presence and timestamp. These
     * short-circuit in that order: without access or a provider nothing else can be
     * checked, and nothing below is meaningful unless a fix arrived carrying usable
     * timestamps. A failing precondition returns immediately, reporting no age and no
     * satellite count, because no fix was evaluated as evidence.
     *
     * **Phase two, evidence.** Once a fix is accepted as evidence, every check runs and
     * every finding is reported; the first finding in precedence order decides the state.
     * Order matters: a cached or stale fix is named before its metadata is judged, so an
     * old fix is never re-reported as merely imprecise.
     */
    fun evaluate(
        input: GnssQualityInput,
        policy: GnssQualityPolicy = GnssQualityPolicy(),
    ): GnssQualityDecision {
        val profile = policy.profile(input.provider)
        val staleAfterNs = policy.staleAfterNs(input.provider)
        val fix = input.lastFix

        if (input.permission == LocationAccess.NOT_REQUESTED) {
            return failure(GnssState.DENIED, GnssReason.LOCATION_NOT_REQUESTED, input)
        }
        if (input.permission == LocationAccess.DENIED) {
            return failure(GnssState.DENIED, GnssReason.PERMISSION_DENIED, input)
        }
        if (input.permission == LocationAccess.REVOKED) {
            return failure(GnssState.DENIED, GnssReason.PERMISSION_REVOKED, input)
        }
        if (!input.providerEnabled) {
            return failure(GnssState.UNAVAILABLE, GnssReason.PROVIDER_DISABLED, input)
        }
        if (fix == null) {
            return failure(GnssState.ACQUIRING, GnssReason.NO_FIX, input)
        }
        // A provider label is judged only once a fix exists on that channel, so "granted
        // but nothing has arrived yet" stays ACQUIRING rather than unknown provider.
        if (profile == null) {
            return failure(GnssState.UNAVAILABLE, GnssReason.PROVIDER_UNKNOWN, input)
        }
        if (!input.timestampsValid || fix.receivedNs < fix.tNs) {
            return failure(GnssState.DEGRADED, GnssReason.TIMESTAMP_INVALID, input)
        }
        // A measurement ahead of the clock cannot be aged, and is not evidence.
        if (input.nowNs < fix.tNs) {
            return failure(GnssState.DEGRADED, GnssReason.TIMESTAMP_INVALID, input)
        }
        val ageNs = input.nowNs - fix.tNs

        val findings = ArrayList<GnssReason>(5)
        if (fix.tNs < input.originNs) findings.add(GnssReason.PRE_SESSION_FIX)
        if (staleAfterNs != null && ageNs > staleAfterNs) findings.add(GnssReason.STALE_FIX)
        if (input.permission == LocationAccess.APPROXIMATE) findings.add(GnssReason.APPROXIMATE_LOCATION)
        val horizontal = fix.horizontalAccuracyM
        when {
            horizontal == null -> findings.add(GnssReason.HORIZONTAL_ACCURACY_UNKNOWN)
            horizontal > policy.maximumHorizontalAccuracyM -> findings.add(GnssReason.HORIZONTAL_ACCURACY_POOR)
        }
        if (profile.satellitesExpected) {
            val satellites = fix.satellitesUsed
            when {
                satellites == null -> findings.add(GnssReason.SATELLITES_UNKNOWN)
                satellites < policy.minimumSatellites -> findings.add(GnssReason.SATELLITES_LOW)
            }
        }
        if (input.innovation?.accepted == false) findings.add(GnssReason.INNOVATION_REJECTED)

        val decisive = findings.firstOrNull()
        val state = when {
            decisive == null -> GnssState.GOOD
            decisive == GnssReason.STALE_FIX -> GnssState.STALE
            else -> GnssState.DEGRADED
        }
        val codes = ArrayList<String>(findings.size + 3)
        findings.forEach { codes.add(it.code) }
        if (!fix.hasSpeed) codes.add(GnssReason.SPEED_UNAVAILABLE.code)
        if (!fix.hasBearing) codes.add(GnssReason.BEARING_UNAVAILABLE.code)
        val vertical = fix.verticalAccuracyM
        when {
            vertical == null -> codes.add(GnssReason.VERTICAL_ACCURACY_UNKNOWN.code)
            vertical > policy.maximumVerticalAccuracyM -> codes.add(GnssReason.VERTICAL_ACCURACY_POOR.code)
        }
        return GnssQualityDecision(
            state = state,
            fixAgeS = ageNs.toDouble() / 1_000_000_000.0,
            satellitesUsed = fix.satellitesUsed?.toLong(),
            reasons = codes,
            provider = input.provider,
        )
    }

    /** A decision that never evaluated a fix, so no age and no satellite count is claimed. */
    private fun failure(
        state: GnssState,
        reason: GnssReason,
        input: GnssQualityInput,
    ): GnssQualityDecision {
        val codes = ArrayList<String>(2)
        codes.add(reason.code)
        // Approximate access is reported even when no fix has arrived, because it already
        // caps what any future fix can reach and the operator needs to see why.
        if (input.permission == LocationAccess.APPROXIMATE) {
            codes.add(GnssReason.APPROXIMATE_LOCATION.code)
        }
        return GnssQualityDecision(state, null, null, codes, input.provider)
    }
}

/**
 * Transition bookkeeping for one channel consumer.
 *
 * Holds only the previous decision, so it stays deterministic: the same sequence of
 * inputs produces the same sequence of transitions. It publishes at most one diagnostic
 * per real change and never invents a transition for a repeated state.
 */
class GnssQualityManager(
    val policy: GnssQualityPolicy = GnssQualityPolicy(),
) {
    var decision: GnssQualityDecision? = null
        private set
    var transitions: Long = 0L
        private set

    /** Evaluate and record. Returns the decision, with any transition left in [lastTransition]. */
    fun update(input: GnssQualityInput): GnssQualityDecision {
        val next = GnssQuality.evaluate(input, policy)
        val previous = decision
        decision = next
        lastTransition = when {
            previous == null || previous.state == next.state -> null
            else -> {
                transitions++
                GnssQualityTransition(
                    from = previous.state,
                    to = next.state,
                    code = when {
                        next.outage -> GnssTransitionCode.GNSS_OUTAGE
                        previous.outage -> GnssTransitionCode.GNSS_RECOVERED
                        else -> GnssTransitionCode.GNSS_QUALITY_CHANGED
                    },
                    outageStarted = next.outage,
                    outageEnded = previous.outage && !next.outage,
                )
            }
        }
        return next
    }

    /** Set by [update]; null when the state did not change or there was no previous state. */
    var lastTransition: GnssQualityTransition? = null
        private set

    /** Forget the previous state. The next [update] reports no transition. */
    fun reset() {
        decision = null
        lastTransition = null
    }
}
