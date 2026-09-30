"""Typed experiment-manifest contract v1.0.0.

An *experiment* is the evaluation unit that sits above recording sessions. It does not
redefine anything that already exists:

- measurement rows stay exactly `contracts.v1` `Record` envelopes;
- session metadata stays exactly `contracts.recording.v1` `RecordingMetadata`;
- a recording session is referenced by ID and relative path, never copied or rewritten.

What the experiment layer adds is the context that makes a drive reusable: where the
phone was mounted and how that was determined, which clock the sessions share, which
intervals were calibration/motion/scenario, when GNSS was observed good/degraded/lost/
recovered, which optional independent reference was flown alongside, and which software
GNSS masks may be applied during replay for repeatable evaluation.

Nothing here is a claim about navigation accuracy. This module describes how a drive was
collected, not what a filter measured from it.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from contracts.v1.models import Source

EXPERIMENT_CONTRACT_VERSION = "1.0.0"

#: UTC milliseconds are provenance only and never replace the monotonic clock.
#: Cross-session comparability requires the domain below plus a shared boot identity.
EXPERIMENT_CLOCK_DOMAIN = "android_elapsed_realtime_ns"


class MountPosition(str, Enum):
    """Where the phone was fixed. There is deliberately no `unknown` member."""

    WINDSCREEN_CENTRE = "windscreen_centre"
    DASH_CENTRE = "dash_centre"
    DASH_LEFT = "dash_left"
    DASH_RIGHT = "dash_right"
    CUPHOLDER = "cupholder"
    ROOF = "roof"
    OTHER = "other"


class OrientationMethod(str, Enum):
    """How the mounting orientation was established.

    `assumed` is permitted but always requires a note, so a guess can never be mistaken
    for a measurement when the manifest is read months later.
    """

    MEASURED = "measured"
    SURVEYED = "surveyed"
    ASSUMED = "assumed"


class SessionRole(str, Enum):
    PRIMARY = "primary"
    REFERENCE = "reference"
    SUPPLEMENTARY = "supplementary"


class ReferenceClass(str, Enum):
    """Class of optional independent reference equipment."""

    RTK = "rtk"
    VBOX = "vbox"
    SURVEY_GRADE = "survey_grade"
    OTHER = "other"


class ReferenceTimebase(str, Enum):
    """How the reference stream's timestamps relate to the experiment clock."""

    #: Already expressed in the experiment clock; `clock.reference_alignment` is required.
    EXPERIMENT_CLOCK = "experiment_clock"
    #: The equipment's own UTC/GPS timebase; alignment is optional and, if absent,
    #: cross-domain comparison is not permitted.
    GPS_UTC = "gps_utc"


class IntervalKind(str, Enum):
    CALIBRATION = "calibration"
    MOTION = "motion"
    SCENARIO = "scenario"
    GNSS_STATE = "gnss_state"


class MotionState(str, Enum):
    STATIONARY = "stationary"
    STRAIGHT = "straight"
    TURNING_LEFT = "turning_left"
    TURNING_RIGHT = "turning_right"
    ROUNDABOUT = "roundabout"
    LANE_CHANGE = "lane_change"
    ACCELERATING = "accelerating"
    BRAKING = "braking"
    REVERSING = "reversing"


class ScenarioClass(str, Enum):
    OPEN_SKY = "open_sky"
    SUBURBAN = "suburban"
    URBAN_CANYON = "urban_canyon"
    TREE_COVER = "tree_cover"
    TUNNEL = "tunnel"
    UNDERPASS = "underpass"
    PARKING_GARAGE = "parking_garage"
    BRIDGE = "bridge"
    HIGHWAY = "highway"
    RURAL = "rural"


class GnssState(str, Enum):
    """Observed GNSS availability, not a target the evaluator hopes for.

    `recovered` is the reacquisition interval: a fix is back but not yet settled into
    `good`. It is never the first state after `good`.
    """

    GOOD = "good"
    DEGRADED = "degraded"
    LOST = "lost"
    RECOVERED = "recovered"


class MaskProvenance(str, Enum):
    """Where a mask's intervals came from."""

    #: Derived from observed `gnss_state` intervals in this experiment.
    OBSERVED = "observed"
    #: Designed for evaluation, independent of what the drive actually saw.
    SYNTHETIC = "synthetic"


class MaskPolicy(str, Enum):
    """What applying a mask does to the engine input.

    1.0.0 has exactly one policy: withhold GNSS measurements inside the mask's intervals,
    exactly as if the receiver had never produced them. Quality-degradation masking is
    deliberately absent — synthesizing degraded fixes would fabricate measurements, which
    this project does not do. Adding a policy requires a new contract version.
    """

    SUPPRESS_GNSS = "suppress_gnss"


@dataclass(frozen=True, slots=True)
class Mount:
    """Phone mounting and the orientation it implies, in the vehicle frame."""

    position: MountPosition
    orientation_method: OrientationMethod
    roll_deg: float
    pitch_deg: float
    yaw_deg: float
    uncertainty_deg: float
    note: str | None


@dataclass(frozen=True, slots=True)
class ReferenceAlignment:
    """Declared relationship between the reference stream and the experiment clock."""

    method: str
    offset_ns: int
    uncertainty_ns: int
    note: str | None


@dataclass(frozen=True, slots=True)
class ExperimentClock:
    """The one clock every session in the experiment must share."""

    domain: str
    boot_id: str
    reference_alignment: ReferenceAlignment | None


@dataclass(frozen=True, slots=True)
class ExperimentSession:
    """A reference to one recording session, pinned to exact bytes.

    The session lives at the canonical location `sessions/<session_id>/` inside the
    experiment, so there is no path to get wrong and the experiment is self-contained:
    one directory holds everything needed to reproduce the evaluation.

    `measurements_sha256` commits the experiment to the recording it was evaluated
    against. The experiment never edits that file; the digest is how a later reader
    proves the bytes are unchanged.
    """

    session_id: str
    role: SessionRole
    measurements_sha256: str


@dataclass(frozen=True, slots=True)
class ReferenceDescriptor:
    """Optional independent RTK/VBOX-class reference flown alongside the drive.

    Records live at the canonical location `reference/reference.jsonl` as ordinary
    `contracts.v1` `Record` envelopes carrying `gnss` measurements, so no second schema
    is introduced for reference data.
    """

    equipment_class: ReferenceClass
    equipment: str
    horizontal_accuracy_m: float
    rate_hz: float
    timebase: ReferenceTimebase
    source: Source
    note: str | None


@dataclass(frozen=True, slots=True)
class AnnotationInterval:
    """One interval in the experiment's ordered annotation stream.

    Exactly one payload field is populated, selected by `kind`; the codec enforces that.
    A flat record keeps calibration, motion, scenario and observed GNSS state in one
    ordered stream, so one code path validates all of them.
    """

    kind: IntervalKind
    session_id: str
    start_ns: int
    end_ns: int
    note: str | None
    calibration_id: str | None
    motion: MotionState | None
    scenario: ScenarioClass | None
    gnss_state: GnssState | None


@dataclass(frozen=True, slots=True)
class MaskInterval:
    start_ns: int
    end_ns: int
    note: str | None


@dataclass(frozen=True, slots=True)
class OutageMask:
    """A software GNSS outage mask. Applied at read time; never written to a recording."""

    mask_id: str
    session_id: str
    description: str
    provenance: MaskProvenance
    policy: MaskPolicy
    intervals: tuple[MaskInterval, ...]
    note: str | None


@dataclass(frozen=True, slots=True)
class ExperimentManifest:
    """`experiment.json`, contract version 1.0.0."""

    experiment_id: str
    created_utc_ms: int
    description: str
    operator: str | None
    vehicle: str | None
    mount: Mount
    clock: ExperimentClock
    sessions: tuple[ExperimentSession, ...]
    reference: ReferenceDescriptor | None
    masks: tuple[str, ...]
    experiment_contract_version: str = EXPERIMENT_CONTRACT_VERSION

    def session(self, session_id: str) -> ExperimentSession | None:
        for entry in self.sessions:
            if entry.session_id == session_id:
                return entry
        return None

    @property
    def primary(self) -> ExperimentSession:
        """The single evaluation target. The codec guarantees exactly one exists."""
        return next(entry for entry in self.sessions if entry.role is SessionRole.PRIMARY)
