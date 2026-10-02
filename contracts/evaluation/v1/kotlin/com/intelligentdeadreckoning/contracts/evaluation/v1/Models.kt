package com.intelligentdeadreckoning.contracts.evaluation.v1

/** What the error metrics were measured against. See `contracts/evaluation/v1/README.md`. */
enum class ReferenceKind(val wire: String) {
    NONE("none"),

    /** The trajectory the synthetic inputs were generated from: independent of every arm by
     * construction, and not field data. */
    SCRIPTED_TRUTH("scripted_truth"),

    /** Recorded GNSS withheld from the arm for the measured interval, so it never consumed it. */
    HELD_OUT_GNSS("held_out_gnss"),
    SURVEYED_TRACK("surveyed_track"),
    RTK("rtk"),
    VBOX("vbox"),
}

enum class SegmentKind(val wire: String) {
    GNSS_GOOD("gnss_good"), DEGRADED("degraded"), DENIED("denied"), RECOVERY("recovery"),
}

enum class ArmId(val wire: String) {
    CLASSICAL_INS("classical_ins"),
    CLASSICAL_FUSION("classical_fusion"),
    FUSION_CONSTRAINTS("fusion_constraints"),
    FUSION_AI("fusion_ai"),
    FUSION_MAP_MATCH("fusion_map_match"),
}

/** `NOT_RUN` and `NOT_IMPLEMENTED` are answers: they carry a reason and no metrics. */
enum class ArmStatus(val wire: String) {
    EVALUATED("evaluated"), NOT_RUN("not_run"), NOT_IMPLEMENTED("not_implemented"),
}

data class SessionDescriptor(
    val sessionId: String,
    val source: String,
    val contractVersion: String,
    val durationS: Double,
    val records: Long,
    val description: String,
)

/** Where the run happened. A host run may not carry a device model or an Android release. */
data class PlatformDescriptor(
    val host: Boolean,
    val deviceModel: String?,
    val androidRelease: String?,
    val note: String,
)

data class ReferenceDescriptor(val kind: ReferenceKind, val independent: Boolean, val description: String)

data class Segment(val kind: SegmentKind, val startNs: Long, val endNs: Long)

data class ArmImplementation(val name: String, val version: String)

/** Error against the declared reference: every field measured, or explicitly absent. */
data class AccuracyMetrics(
    /** True when this arm consumed the reference during the measured interval; the codec refuses
     * the whole group then, because an arm cannot be scored against its own input. */
    val referenceConsumed: Boolean,
    val outageDurationS: Double?,
    val outageDistanceM: Double?,
    val finalPositionErrorM: Double?,
    val driftPercent: Double?,
    val positionRmseM: Double?,
    val speedMaeMS: Double?,
    val speedRmseMS: Double?,
    /** Mean absolute heading error in degrees over samples that published a heading. */
    val headingErrorDeg: Double?,
    val recoveryConvergenceS: Double?,
    /** The error bound convergence was measured against; required whenever convergence is reported. */
    val recoveryThresholdM: Double?,
    val samples: Long?,
)

/** Runtime measurements of the run. Absent values are absent, never zero. */
data class TimingMetrics(
    val outputHz: Double?,
    val inferenceLatencyP50Ms: Double?,
    val inferenceLatencyP95Ms: Double?,
    val endToEndP50Ms: Double?,
    val endToEndP95Ms: Double?,
    val queueHighWater: Long?,
    /** Records the runtime dropped (ingress + output), and records it counted as errors. */
    val drops: Long?,
    val errors: Long?,
    val memoryPeakMb: Double?,
    val samples: Long?,
)

data class Arm(
    val armId: ArmId,
    val label: String,
    val implementation: ArmImplementation,
    val status: ArmStatus,
    val reason: String?,
    val accuracy: AccuracyMetrics?,
    val timing: TimingMetrics?,
)

data class EvaluationReport(
    val evaluationContractVersion: String,
    val evaluationId: String,
    val createdUtcMs: Long,
    val session: SessionDescriptor,
    val platform: PlatformDescriptor,
    val reference: ReferenceDescriptor,
    val segments: List<Segment>,
    val arms: List<Arm>,
) {
    companion object { const val VERSION = "1.0.0" }
}
