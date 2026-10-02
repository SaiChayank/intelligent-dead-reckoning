"""Typed evaluation-report values. No data access and no navigation of any kind.

An evaluation report is the document a run produces: what session was evaluated, against which
reference, which arms were run, and which metrics came out. The types here carry the honesty rules
the codec enforces, in particular that an accuracy figure may only exist when the reference is a
declared, independent one the arm did not consume.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class ReferenceKind(str, Enum):
    """What the error metrics were measured against."""

    NONE = "none"
    #: The trajectory the synthetic inputs were generated from: independent of every arm by
    #: construction, and not field data.
    SCRIPTED_TRUTH = "scripted_truth"
    #: Recorded GNSS withheld from the arm for the measured interval, so the arm never consumed it.
    HELD_OUT_GNSS = "held_out_gnss"
    SURVEYED_TRACK = "surveyed_track"
    RTK = "rtk"
    VBOX = "vbox"


class SegmentKind(str, Enum):
    GNSS_GOOD = "gnss_good"
    DEGRADED = "degraded"
    DENIED = "denied"
    RECOVERY = "recovery"


class ArmId(str, Enum):
    CLASSICAL_INS = "classical_ins"
    CLASSICAL_FUSION = "classical_fusion"
    FUSION_CONSTRAINTS = "fusion_constraints"
    FUSION_AI = "fusion_ai"
    FUSION_MAP_MATCH = "fusion_map_match"


class ArmStatus(str, Enum):
    """`not_run` and `not_implemented` are answers, not blanks: they carry a reason and no metrics."""

    EVALUATED = "evaluated"
    NOT_RUN = "not_run"
    NOT_IMPLEMENTED = "not_implemented"


@dataclass(frozen=True)
class SessionDescriptor:
    session_id: str
    source: str
    contract_version: str
    duration_s: float
    records: int
    description: str


@dataclass(frozen=True)
class PlatformDescriptor:
    """Where the run happened. A host run may not carry device memory or a sensor-path latency."""

    host: bool
    device_model: str | None
    android_release: str | None
    note: str


@dataclass(frozen=True)
class ReferenceDescriptor:
    kind: ReferenceKind
    independent: bool
    description: str


@dataclass(frozen=True)
class Segment:
    kind: SegmentKind
    start_ns: int
    end_ns: int


@dataclass(frozen=True)
class ArmImplementation:
    name: str
    version: str


@dataclass(frozen=True)
class AccuracyMetrics:
    """Error against the declared reference. Every field is either measured or explicitly null."""

    #: False only when this arm consumed the reference during the measured interval. A `true`
    #: here makes the whole group invalid: an arm cannot be scored against its own input.
    reference_consumed: bool
    outage_duration_s: float | None
    outage_distance_m: float | None
    final_position_error_m: float | None
    drift_percent: float | None
    position_rmse_m: float | None
    speed_mae_m_s: float | None
    speed_rmse_m_s: float | None
    #: Mean absolute heading error in degrees over samples that published a heading.
    heading_error_deg: float | None
    recovery_convergence_s: float | None
    #: The error bound convergence was measured against; required whenever convergence is reported.
    recovery_threshold_m: float | None
    samples: int | None


@dataclass(frozen=True)
class TimingMetrics:
    """Runtime measurements of the run. Absent values are absent, never zero."""

    output_hz: float | None
    inference_latency_p50_ms: float | None
    inference_latency_p95_ms: float | None
    end_to_end_p50_ms: float | None
    end_to_end_p95_ms: float | None
    queue_high_water: int | None
    #: Records the runtime dropped (ingress + output), and records it counted as errors.
    drops: int | None
    errors: int | None
    memory_peak_mb: float | None
    samples: int | None


@dataclass(frozen=True)
class Arm:
    arm_id: ArmId
    label: str
    implementation: ArmImplementation
    status: ArmStatus
    reason: str | None
    accuracy: AccuracyMetrics | None
    timing: TimingMetrics | None


@dataclass(frozen=True)
class EvaluationReport:
    evaluation_contract_version: str
    evaluation_id: str
    created_utc_ms: int
    session: SessionDescriptor
    platform: PlatformDescriptor
    reference: ReferenceDescriptor
    segments: tuple[Segment, ...]
    arms: tuple[Arm, ...]


EVALUATION_CONTRACT_VERSION = "1.0.0"
