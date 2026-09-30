"""Strict codec for experiment manifests, annotation streams and outage masks.

Same discipline as `contracts.v1.codec` and `contracts.recording.v1.codec`: exact key
sets, explicit JSON `null` for unavailable fields, decimal strings for values that must
never pass through binary64, stable error codes on `ExperimentContractError`, and a
writer that decodes its own output so reader and writer cannot disagree.

Error codes raised here:

    INVALID_TYPE, INVALID_KEYS, DUPLICATE_KEY, INVALID_UTF8, MALFORMED_JSON,
    RESOURCE_LIMIT, INVALID_VERSION, INVALID_ID, INVALID_ENUM,
    INVALID_SHAPE, INVALID_UNICODE, OUT_OF_RANGE, INVARIANT, INVALID_INTERVAL,
    OUT_OF_ORDER, OVERLAPPING_INTERVALS, DUPLICATE_INTERVAL, DUPLICATE_SESSION,
    DUPLICATE_MASK, INVALID_MODEL

Codes are stable identifiers: callers switch on them, so a code is never reused for a
different meaning within contract 1.0.0.
"""

from __future__ import annotations

import json
import math
import re
from dataclasses import asdict
from enum import Enum

from contracts.v1.models import Source

from .models import (
    AnnotationInterval,
    EXPERIMENT_CLOCK_DOMAIN,
    EXPERIMENT_CONTRACT_VERSION,
    ExperimentClock,
    ExperimentManifest,
    ExperimentSession,
    GnssState,
    IntervalKind,
    MaskInterval,
    MaskPolicy,
    MaskProvenance,
    MotionState,
    Mount,
    MountPosition,
    OrientationMethod,
    OutageMask,
    ReferenceAlignment,
    ReferenceClass,
    ReferenceDescriptor,
    ReferenceTimebase,
    ScenarioClass,
    SessionRole,
)

MAX_INT64 = (1 << 63) - 1

#: Same bound as recording metadata: a manifest is provenance, not a data channel.
MAX_MANIFEST_BYTES = 262_144
MAX_MASK_BYTES = 131_072
MAX_ANNOTATION_BYTES = 8 * 1024 * 1024
MAX_ANNOTATION_LINE_BYTES = 4_096
MAX_ANNOTATIONS = 100_000

_TEXT_LIMIT = 2048
#: Safe as a directory name and as a file name: no separator, no leading dot.
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


class ExperimentContractError(ValueError):
    """A manifest, annotation stream or mask that violates contract 1.0.0.

    `code` is a stable identifier, `path` is a JSON pointer into the offending artifact,
    and `line` is a one-based line number for line-oriented artifacts.
    """

    def __init__(self, code: str, path: str = "$", line: int | None = None):
        self.code = code
        self.path = path
        self.line = line
        parts = [code, path]
        if line is not None:
            parts.append(f"line {line}")
        super().__init__(" at ".join(parts))


def _fail(code: str, path: str = "$", line: int | None = None):
    raise ExperimentContractError(code, path, line) from None


def _pairs(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            _fail("DUPLICATE_KEY")
        out[key] = value
    return out


def _load(data: bytes, limit: int, path: str = "$"):
    if not isinstance(data, bytes):
        _fail("INVALID_TYPE", path)
    if len(data) > limit:
        _fail("RESOURCE_LIMIT", path)
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError:
        _fail("INVALID_UTF8", path)
    if text.startswith("\ufeff"):
        _fail("MALFORMED_JSON", path)
    try:
        return json.loads(text, object_pairs_hook=_pairs,
                          parse_constant=lambda _: _fail("MALFORMED_JSON", path))
    except ExperimentContractError:
        raise
    except (json.JSONDecodeError, ValueError, RecursionError):
        _fail("MALFORMED_JSON", path)


def _keys(value, expected, path):
    if type(value) is not dict:
        _fail("INVALID_TYPE", path)
    if set(value) != set(expected):
        _fail("INVALID_KEYS", path)


def _string(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) is not str:
        _fail("INVALID_TYPE", path)
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError:
        _fail("INVALID_UNICODE", path)
    if not value.strip() or len(value.encode("utf-16-le")) // 2 > _TEXT_LIMIT:
        _fail("OUT_OF_RANGE", path)
    return value


def _safe_id(value, path):
    text = _string(value, path)
    if not _ID.fullmatch(text):
        _fail("INVALID_ID", path)
    return text


def _decimal(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) is not str or not re.fullmatch(r"0|[1-9][0-9]*", value) or len(value) > 19:
        _fail("INVALID_TYPE", path)
    parsed = int(value)
    if not 0 <= parsed <= MAX_INT64:
        _fail("OUT_OF_RANGE", path)
    return parsed


def _signed_decimal(value, path, nullable=False):
    """Signed decimal string, for a clock offset that may legitimately be negative."""
    if value is None and nullable:
        return None
    if type(value) is not str or not re.fullmatch(r"0|-?[1-9][0-9]*", value) or len(value) > 20:
        _fail("INVALID_TYPE", path)
    parsed = int(value)
    if not -MAX_INT64 <= parsed <= MAX_INT64:
        _fail("OUT_OF_RANGE", path)
    return parsed


def _finite(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) not in (int, float) or isinstance(value, bool):
        _fail("INVALID_TYPE", path)
    value = float(value)
    if not math.isfinite(value):
        _fail("OUT_OF_RANGE", path)
    return value


def _angle_deg(value, path):
    """An orientation angle in degrees. Half-open `(-180, 180]`, so one angle has one spelling."""
    value = _finite(value, path)
    if not -180.0 < value <= 180.0:
        _fail("OUT_OF_RANGE", path)
    return value


def _positive(value, path):
    value = _finite(value, path)
    if value <= 0:
        _fail("OUT_OF_RANGE", path)
    return value


def _non_negative(value, path):
    value = _finite(value, path)
    if value < 0:
        _fail("OUT_OF_RANGE", path)
    return value


def _sha256(value, path):
    if type(value) is not str or not _SHA256.fullmatch(value):
        _fail("INVALID_TYPE", path)
    return value


def _enum(value, cls, path):
    if type(value) is not str:
        _fail("INVALID_TYPE", path)
    try:
        return cls(value)
    except ValueError:
        _fail("INVALID_ENUM", path)


def _nullable_enum(value, cls, path):
    return None if value is None else _enum(value, cls, path)


# --------------------------------------------------------------------------------------
# Manifest
# --------------------------------------------------------------------------------------


def _mount(raw) -> Mount:
    p = "$.mount"
    _keys(raw, ("position", "orientation_method", "roll_deg", "pitch_deg", "yaw_deg",
                "uncertainty_deg", "note"), p)
    value = Mount(
        position=_enum(raw["position"], MountPosition, p + ".position"),
        orientation_method=_enum(raw["orientation_method"], OrientationMethod,
                                 p + ".orientation_method"),
        roll_deg=_angle_deg(raw["roll_deg"], p + ".roll_deg"),
        pitch_deg=_angle_deg(raw["pitch_deg"], p + ".pitch_deg"),
        yaw_deg=_angle_deg(raw["yaw_deg"], p + ".yaw_deg"),
        uncertainty_deg=_non_negative(raw["uncertainty_deg"], p + ".uncertainty_deg"),
        note=_string(raw["note"], p + ".note", True),
    )
    if value.orientation_method is OrientationMethod.ASSUMED and value.note is None:
        # An assumption must be documented, or it can be mistaken for a measurement later.
        _fail("INVARIANT", p + ".note")
    return value


def _reference_alignment(raw) -> ReferenceAlignment:
    p = "$.clock.reference_alignment"
    _keys(raw, ("method", "offset_ns", "uncertainty_ns", "note"), p)
    return ReferenceAlignment(
        method=_string(raw["method"], p + ".method"),
        offset_ns=_signed_decimal(raw["offset_ns"], p + ".offset_ns"),
        uncertainty_ns=_decimal(raw["uncertainty_ns"], p + ".uncertainty_ns"),
        note=_string(raw["note"], p + ".note", True),
    )


def _clock(raw) -> ExperimentClock:
    p = "$.clock"
    _keys(raw, ("domain", "boot_id", "reference_alignment"), p)
    domain = _string(raw["domain"], p + ".domain")
    if domain != EXPERIMENT_CLOCK_DOMAIN:
        _fail("INVALID_ENUM", p + ".domain")
    # Unlike recording metadata, the experiment layer requires a boot identity: it is the
    # only thing that makes two sessions' monotonic timestamps comparable.
    return ExperimentClock(
        domain=domain,
        boot_id=_string(raw["boot_id"], p + ".boot_id"),
        reference_alignment=(None if raw["reference_alignment"] is None
                             else _reference_alignment(raw["reference_alignment"])),
    )


def _session(raw, index) -> ExperimentSession:
    p = f"$.sessions[{index}]"
    _keys(raw, ("session_id", "role", "measurements_sha256"), p)
    return ExperimentSession(
        session_id=_safe_id(raw["session_id"], p + ".session_id"),
        role=_enum(raw["role"], SessionRole, p + ".role"),
        measurements_sha256=_sha256(raw["measurements_sha256"], p + ".measurements_sha256"),
    )


def _reference(raw) -> ReferenceDescriptor:
    p = "$.reference"
    _keys(raw, ("equipment_class", "equipment", "horizontal_accuracy_m",
                "rate_hz", "timebase", "source", "note"), p)
    source = _enum(raw["source"], Source, p + ".source")
    if source is not Source.REAL:
        # Reference equipment observes reality; it is never a replay or a simulation.
        _fail("INVALID_ENUM", p + ".source")
    return ReferenceDescriptor(
        equipment_class=_enum(raw["equipment_class"], ReferenceClass, p + ".equipment_class"),
        equipment=_string(raw["equipment"], p + ".equipment"),
        horizontal_accuracy_m=_positive(raw["horizontal_accuracy_m"], p + ".horizontal_accuracy_m"),
        rate_hz=_positive(raw["rate_hz"], p + ".rate_hz"),
        timebase=_enum(raw["timebase"], ReferenceTimebase, p + ".timebase"),
        source=source,
        note=_string(raw["note"], p + ".note", True),
    )


def _validate_manifest(value: ExperimentManifest) -> ExperimentManifest:
    if value.experiment_contract_version != EXPERIMENT_CONTRACT_VERSION:
        _fail("INVALID_VERSION", "$.experiment_contract_version")
    _safe_id(value.experiment_id, "$.experiment_id")
    _string(value.description, "$.description")
    _string(value.operator, "$.operator", True)
    _string(value.vehicle, "$.vehicle", True)
    if not 0 <= value.created_utc_ms <= MAX_INT64:
        _fail("OUT_OF_RANGE", "$.created_utc_ms")
    if value.clock.domain != EXPERIMENT_CLOCK_DOMAIN:
        _fail("INVALID_ENUM", "$.clock.domain")
    _string(value.clock.boot_id, "$.clock.boot_id")
    if (value.mount.orientation_method is OrientationMethod.ASSUMED
            and value.mount.note is None):
        _fail("INVARIANT", "$.mount.note")

    if not value.sessions:
        _fail("INVARIANT", "$.sessions")
    ids = [entry.session_id for entry in value.sessions]
    if len(set(ids)) != len(ids):
        _fail("DUPLICATE_SESSION", "$.sessions")
    primaries = [entry for entry in value.sessions if entry.role is SessionRole.PRIMARY]
    if len(primaries) != 1:
        # One experiment has exactly one evaluation target, so a report has one timeline.
        _fail("INVARIANT", "$.sessions")
    for entry in value.sessions:
        _safe_id(entry.session_id, "$.sessions.session_id")
        _sha256(entry.measurements_sha256, "$.sessions.measurements_sha256")

    masks = list(value.masks)
    if len(set(masks)) != len(masks):
        _fail("DUPLICATE_MASK", "$.masks")
    for mask in masks:
        _safe_id(mask, "$.masks")

    if value.reference is not None:
        reference = value.reference
        if reference.timebase is ReferenceTimebase.EXPERIMENT_CLOCK:
            if value.clock.reference_alignment is None:
                # Claiming the reference is already in this clock requires saying how.
                _fail("INVARIANT", "$.clock.reference_alignment")
        if (reference.equipment_class is ReferenceClass.OTHER and reference.note is None):
            _fail("INVARIANT", "$.reference.note")
    return value


def decode_manifest(data: bytes) -> ExperimentManifest:
    raw = _load(data, MAX_MANIFEST_BYTES)
    _keys(raw, (
        "experiment_contract_version", "experiment_id", "created_utc_ms", "description",
        "operator", "vehicle", "mount", "clock", "sessions", "reference", "masks",
    ), "$")
    if raw["experiment_contract_version"] != EXPERIMENT_CONTRACT_VERSION:
        _fail("INVALID_VERSION", "$.experiment_contract_version")
    if type(raw["sessions"]) is not list:
        _fail("INVALID_TYPE", "$.sessions")
    if type(raw["masks"]) is not list:
        _fail("INVALID_TYPE", "$.masks")
    value = ExperimentManifest(
        experiment_id=_safe_id(raw["experiment_id"], "$.experiment_id"),
        created_utc_ms=_decimal(raw["created_utc_ms"], "$.created_utc_ms"),
        description=_string(raw["description"], "$.description"),
        operator=_string(raw["operator"], "$.operator", True),
        vehicle=_string(raw["vehicle"], "$.vehicle", True),
        mount=_mount(raw["mount"]),
        clock=_clock(raw["clock"]),
        sessions=tuple(_session(v, i) for i, v in enumerate(raw["sessions"])),
        reference=None if raw["reference"] is None else _reference(raw["reference"]),
        masks=tuple(_safe_id(v, f"$.masks[{i}]") for i, v in enumerate(raw["masks"])),
    )
    return _validate_manifest(value)


def _enum_wire(value):
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, (tuple, list)):
        return [_enum_wire(v) for v in value]
    if isinstance(value, dict):
        return {k: _enum_wire(v) for k, v in value.items()}
    return value


def encode_manifest(value: ExperimentManifest) -> bytes:
    if not isinstance(value, ExperimentManifest):
        _fail("INVALID_MODEL")
    _validate_manifest(value)
    raw = {
        "experiment_contract_version": value.experiment_contract_version,
        "experiment_id": value.experiment_id,
        "created_utc_ms": str(value.created_utc_ms),
        "description": value.description,
        "operator": value.operator,
        "vehicle": value.vehicle,
        "mount": _enum_wire(asdict(value.mount)),
        # Built field by field rather than via `asdict`: the alignment's nanosecond
        # values must be decimal strings, and `asdict` would emit them as JSON numbers.
        "clock": {
            "domain": value.clock.domain,
            "boot_id": value.clock.boot_id,
            "reference_alignment": (None if value.clock.reference_alignment is None else {
                "method": value.clock.reference_alignment.method,
                "offset_ns": str(value.clock.reference_alignment.offset_ns),
                "uncertainty_ns": str(value.clock.reference_alignment.uncertainty_ns),
                "note": value.clock.reference_alignment.note,
            }),
        },
        "sessions": [
            {
                "session_id": entry.session_id,
                "role": entry.role.value,
                "measurements_sha256": entry.measurements_sha256,
            } for entry in value.sessions
        ],
        "reference": None if value.reference is None else {
            **_enum_wire(asdict(value.reference)),
        },
        "masks": list(value.masks),
    }
    try:
        encoded = json.dumps(raw, ensure_ascii=False, allow_nan=False,
                             separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError):
        _fail("INVALID_MODEL")
    if len(encoded) > MAX_MANIFEST_BYTES:
        _fail("RESOURCE_LIMIT")
    # Decode what we emit, so writer and reader enforce identical invariants.
    decode_manifest(encoded)
    return encoded


# --------------------------------------------------------------------------------------
# Annotation stream
# --------------------------------------------------------------------------------------

_PAYLOAD_BY_KIND = {
    IntervalKind.CALIBRATION: "calibration_id",
    IntervalKind.MOTION: "motion",
    IntervalKind.SCENARIO: "scenario",
    IntervalKind.GNSS_STATE: "gnss_state",
}


def _annotation(raw, line: int) -> AnnotationInterval:
    p = f"$[line {line}]"
    _keys(raw, ("kind", "session_id", "start_ns", "end_ns", "note", "calibration_id",
                "motion", "scenario", "gnss_state"), p)
    kind = _enum(raw["kind"], IntervalKind, p + ".kind")
    populated = [name for name in _PAYLOAD_BY_KIND.values() if raw[name] is not None]
    expected = _PAYLOAD_BY_KIND[kind]
    if populated != [expected]:
        # Exactly one payload, matching the kind: a line cannot be two things at once.
        _fail("INVALID_SHAPE", p)
    start = _decimal(raw["start_ns"], p + ".start_ns")
    end = _decimal(raw["end_ns"], p + ".end_ns")
    if start >= end:
        # A zero-length or reversed interval is a real recording mistake, not a harmless one.
        _fail("INVALID_INTERVAL", p, line)
    calibration_id = (None if raw["calibration_id"] is None
                      else _safe_id(raw["calibration_id"], p + ".calibration_id"))
    return AnnotationInterval(
        kind=kind,
        session_id=_safe_id(raw["session_id"], p + ".session_id"),
        start_ns=start,
        end_ns=end,
        note=_string(raw["note"], p + ".note", True),
        calibration_id=calibration_id,
        motion=_nullable_enum(raw["motion"], MotionState, p + ".motion"),
        scenario=_nullable_enum(raw["scenario"], ScenarioClass, p + ".scenario"),
        gnss_state=_nullable_enum(raw["gnss_state"], GnssState, p + ".gnss_state"),
    )


def _payload_of(interval: AnnotationInterval):
    return {
        IntervalKind.CALIBRATION: interval.calibration_id,
        IntervalKind.MOTION: interval.motion,
        IntervalKind.SCENARIO: interval.scenario,
        IntervalKind.GNSS_STATE: interval.gnss_state,
    }[interval.kind]


def _check_interval_order(intervals: tuple[AnnotationInterval, ...]) -> None:
    """Ordering, overlap and duplicate rules over one decoded annotation stream.

    Checks apply within a (session, kind) group, not across the whole file: motion and
    scenario intervals legitimately run in parallel, so a group is the only set whose
    members describe the same thing at the same time. Within a group intervals must be
    in increasing start order and must not overlap. Touching intervals are legal and are
    how a GNSS state transition is expressed; two touching intervals carrying the *same*
    value are a mistake, because they are one interval that was written twice.

    Groups are independent, so a collector can lay the stream out kind by kind.
    """
    latest: dict[tuple[str, str], AnnotationInterval] = {}
    for index, interval in enumerate(intervals):
        key = (interval.session_id, interval.kind.value)
        seen = latest.get(key)
        if seen is not None:
            if interval.start_ns < seen.start_ns:
                _fail("OUT_OF_ORDER", f"$[{index}]")
            if interval.start_ns < seen.end_ns:
                _fail("OVERLAPPING_INTERVALS", f"$[{index}]")
            if interval.start_ns == seen.end_ns and _payload_of(interval) == _payload_of(seen):
                _fail("DUPLICATE_INTERVAL", f"$[{index}]")
        latest[key] = interval


def decode_annotations(data: bytes) -> tuple[AnnotationInterval, ...]:
    if not isinstance(data, bytes):
        _fail("INVALID_TYPE")
    if len(data) > MAX_ANNOTATION_BYTES:
        _fail("RESOURCE_LIMIT")
    lines = data.split(b"\n")
    if lines and not lines[-1]:
        # A single trailing newline is the normal writer output, not a blank line.
        lines.pop()
    intervals: list[AnnotationInterval] = []
    for number, raw_line in enumerate(lines, 1):
        if not raw_line.strip():
            _fail("MALFORMED_JSON", "$", number)
        if len(raw_line) > MAX_ANNOTATION_LINE_BYTES:
            _fail("RESOURCE_LIMIT", "$", number)
        if len(intervals) >= MAX_ANNOTATIONS:
            _fail("RESOURCE_LIMIT", "$", number)
        intervals.append(_annotation(_load(raw_line, MAX_ANNOTATION_LINE_BYTES, "$"), number))
    result = tuple(intervals)
    _check_interval_order(result)
    return result


def encode_annotations(intervals) -> bytes:
    lines = []
    for interval in intervals:
        if not isinstance(interval, AnnotationInterval):
            _fail("INVALID_MODEL")
        raw = {
            "kind": interval.kind.value,
            "session_id": interval.session_id,
            "start_ns": str(interval.start_ns),
            "end_ns": str(interval.end_ns),
            "note": interval.note,
            "calibration_id": interval.calibration_id,
            "motion": None if interval.motion is None else interval.motion.value,
            "scenario": None if interval.scenario is None else interval.scenario.value,
            "gnss_state": None if interval.gnss_state is None else interval.gnss_state.value,
        }
        lines.append(json.dumps(raw, ensure_ascii=False, allow_nan=False, separators=(",", ":")))
    encoded = ("\n".join(lines) + ("\n" if lines else "")).encode("utf-8")
    if len(encoded) > MAX_ANNOTATION_BYTES:
        _fail("RESOURCE_LIMIT")
    decode_annotations(encoded)
    return encoded


# --------------------------------------------------------------------------------------
# Outage mask
# --------------------------------------------------------------------------------------


def _mask_interval(raw, index) -> MaskInterval:
    p = f"$.intervals[{index}]"
    _keys(raw, ("start_ns", "end_ns", "note"), p)
    start = _decimal(raw["start_ns"], p + ".start_ns")
    end = _decimal(raw["end_ns"], p + ".end_ns")
    if start >= end:
        _fail("INVALID_INTERVAL", p)
    return MaskInterval(start, end, _string(raw["note"], p + ".note", True))


def _validate_mask(value: OutageMask) -> OutageMask:
    _safe_id(value.mask_id, "$.mask_id")
    _safe_id(value.session_id, "$.session_id")
    _string(value.description, "$.description")
    if value.provenance is MaskProvenance.OBSERVED and value.note is None:
        # An observed mask claims its intervals came from this experiment; say which run.
        _fail("INVARIANT", "$.note")
    if not value.intervals:
        _fail("INVARIANT", "$.intervals")
    previous_start = -1
    previous_end = -1
    for index, interval in enumerate(value.intervals):
        if interval.start_ns < previous_start:
            _fail("OUT_OF_ORDER", f"$.intervals[{index}]")
        if interval.start_ns < previous_end:
            _fail("OVERLAPPING_INTERVALS", f"$.intervals[{index}]")
        if interval.start_ns >= interval.end_ns:
            _fail("INVALID_INTERVAL", f"$.intervals[{index}]")
        previous_start, previous_end = interval.start_ns, interval.end_ns
    return value


def decode_mask(data: bytes) -> OutageMask:
    raw = _load(data, MAX_MASK_BYTES)
    _keys(raw, ("mask_id", "session_id", "description", "provenance", "policy",
                "intervals", "note"), "$")
    if type(raw["intervals"]) is not list:
        _fail("INVALID_TYPE", "$.intervals")
    value = OutageMask(
        mask_id=_safe_id(raw["mask_id"], "$.mask_id"),
        session_id=_safe_id(raw["session_id"], "$.session_id"),
        description=_string(raw["description"], "$.description"),
        provenance=_enum(raw["provenance"], MaskProvenance, "$.provenance"),
        policy=_enum(raw["policy"], MaskPolicy, "$.policy"),
        intervals=tuple(_mask_interval(v, i) for i, v in enumerate(raw["intervals"])),
        note=_string(raw["note"], "$.note", True),
    )
    return _validate_mask(value)


def encode_mask(value: OutageMask) -> bytes:
    if not isinstance(value, OutageMask):
        _fail("INVALID_MODEL")
    _validate_mask(value)
    raw = {
        "mask_id": value.mask_id,
        "session_id": value.session_id,
        "description": value.description,
        "provenance": value.provenance.value,
        "policy": value.policy.value,
        "intervals": [
            {"start_ns": str(i.start_ns), "end_ns": str(i.end_ns), "note": i.note}
            for i in value.intervals
        ],
        "note": value.note,
    }
    try:
        encoded = json.dumps(raw, ensure_ascii=False, allow_nan=False,
                             separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError):
        _fail("INVALID_MODEL")
    if len(encoded) > MAX_MASK_BYTES:
        _fail("RESOURCE_LIMIT")
    decode_mask(encoded)
    return encoded
