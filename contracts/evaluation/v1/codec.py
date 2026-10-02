"""Strict codec for the evaluation report document.

Every defined key is required; an unavailable value is an explicit JSON `null`. Unknown or missing
keys are rejected, the version must match exactly, numbers must be finite and non-negative, and the
cross-field rules that keep the document honest are invariants rather than advice:
`contracts/evaluation/v1/README.md` states them. Nothing here opens a file.
"""
from __future__ import annotations

import json
import math
import re

from .models import (
    EVALUATION_CONTRACT_VERSION,
    AccuracyMetrics,
    Arm,
    ArmId,
    ArmImplementation,
    ArmStatus,
    EvaluationReport,
    PlatformDescriptor,
    ReferenceDescriptor,
    ReferenceKind,
    Segment,
    SegmentKind,
    SessionDescriptor,
    TimingMetrics,
)

MAX_BYTES = 262_144
DECIMAL = re.compile(r"(?:0|[1-9][0-9]*)\Z")
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+\Z")
TOKEN = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")
#: Tokens no JSON number can be, but which a lenient reader hands over as one.
_NON_FINITE_TOKENS = frozenset({"NaN", "Infinity", "-Infinity", "+Infinity"})


class EvaluationError(ValueError):
    """Machine-readable diagnostic. The code names the rule that was broken."""

    def __init__(self, code: str, path: str = "$"):
        self.code, self.path = code, path
        super().__init__(f"{code} at {path}")


def _fail(code, path):
    raise EvaluationError(code, path) from None


def _keys(value, expected, path):
    if type(value) is not dict:
        _fail("INVALID_TYPE", path)
    if set(value) != set(expected):
        _fail("INVALID_KEYS", path)


def _text(value, path):
    if type(value) is not str:
        _fail("INVALID_TYPE", path)
    if not value.strip() or len(value) > 2048:
        _fail("OUT_OF_RANGE", path)
    return value


def _token(value, path):
    text = _text(value, path)
    if not TOKEN.fullmatch(text):
        _fail("OUT_OF_RANGE", path)
    return text


def _version(value, path):
    text = _text(value, path)
    if not VERSION.fullmatch(text):
        _fail("OUT_OF_RANGE", path)
    return text


def _decimal(value, path):
    if type(value) is not str:
        _fail("INVALID_TYPE", path)
    if len(value) > 19 or not DECIMAL.fullmatch(value):
        _fail("OUT_OF_RANGE", path)
    return int(value)


def _number(value, path):
    # A lenient parser (this one) turns a bare `NaN` into a float, while a strict one hands over the
    # token as a string. Both mean the same thing, and the Kotlin codec names it the same way, so a
    # non-finite token is NONFINITE whether it arrives bare or quoted.
    if isinstance(value, str) and value in _NON_FINITE_TOKENS:
        _fail("NONFINITE", path)
    if type(value) not in (int, float):
        _fail("INVALID_TYPE", path)
    result = float(value)
    if not math.isfinite(result) or result < 0.0:
        _fail("NONFINITE" if not math.isfinite(result) else "OUT_OF_RANGE", path)
    return result


def _integer(value, path):
    if type(value) is not int:
        _fail("INVALID_TYPE", path)
    if not 0 <= value <= (1 << 63) - 1:
        _fail("OUT_OF_RANGE", path)
    return value


def _boolean(value, path):
    if type(value) is not bool:
        _fail("INVALID_TYPE", path)
    return value


def _enum(value, enum, path):
    if type(value) is not str:
        _fail("INVALID_TYPE", path)
    try:
        return enum(value)
    except ValueError:
        _fail("INVALID_ENUM", path)


def _nullable(value, read, path):
    return None if value is None else read(value, path)


ACCURACY_KEYS = (
    "reference_consumed", "outage_duration_s", "outage_distance_m", "final_position_error_m",
    "drift_percent", "position_rmse_m", "speed_mae_m_s", "speed_rmse_m_s", "heading_error_deg",
    "recovery_convergence_s", "recovery_threshold_m", "samples",
)
TIMING_KEYS = (
    "output_hz", "inference_latency_p50_ms", "inference_latency_p95_ms", "end_to_end_p50_ms",
    "end_to_end_p95_ms", "queue_high_water", "drops", "errors", "memory_peak_mb", "samples",
)


def _decode_accuracy(value, path) -> AccuracyMetrics:
    _keys(value, ACCURACY_KEYS, path)
    metrics = AccuracyMetrics(
        reference_consumed=_boolean(value["reference_consumed"], f"{path}.reference_consumed"),
        outage_duration_s=_nullable(value["outage_duration_s"], _number, f"{path}.outage_duration_s"),
        outage_distance_m=_nullable(value["outage_distance_m"], _number, f"{path}.outage_distance_m"),
        final_position_error_m=_nullable(value["final_position_error_m"], _number, f"{path}.final_position_error_m"),
        drift_percent=_nullable(value["drift_percent"], _number, f"{path}.drift_percent"),
        position_rmse_m=_nullable(value["position_rmse_m"], _number, f"{path}.position_rmse_m"),
        speed_mae_m_s=_nullable(value["speed_mae_m_s"], _number, f"{path}.speed_mae_m_s"),
        speed_rmse_m_s=_nullable(value["speed_rmse_m_s"], _number, f"{path}.speed_rmse_m_s"),
        heading_error_deg=_nullable(value["heading_error_deg"], _number, f"{path}.heading_error_deg"),
        recovery_convergence_s=_nullable(value["recovery_convergence_s"], _number, f"{path}.recovery_convergence_s"),
        recovery_threshold_m=_nullable(value["recovery_threshold_m"], _number, f"{path}.recovery_threshold_m"),
        samples=_nullable(value["samples"], _integer, f"{path}.samples"),
    )
    if metrics.reference_consumed:
        _fail("INVARIANT", f"{path}.reference_consumed")
    if metrics.recovery_convergence_s is not None and metrics.recovery_threshold_m is None:
        _fail("INVARIANT", f"{path}.recovery_threshold_m")
    if all(
        getattr(metrics, name) is None
        for name in ACCURACY_KEYS if name not in ("reference_consumed",)
    ):
        _fail("INVARIANT", path)
    return metrics


def _decode_timing(value, path) -> TimingMetrics:
    _keys(value, TIMING_KEYS, path)
    metrics = TimingMetrics(
        output_hz=_nullable(value["output_hz"], _number, f"{path}.output_hz"),
        inference_latency_p50_ms=_nullable(value["inference_latency_p50_ms"], _number, f"{path}.inference_latency_p50_ms"),
        inference_latency_p95_ms=_nullable(value["inference_latency_p95_ms"], _number, f"{path}.inference_latency_p95_ms"),
        end_to_end_p50_ms=_nullable(value["end_to_end_p50_ms"], _number, f"{path}.end_to_end_p50_ms"),
        end_to_end_p95_ms=_nullable(value["end_to_end_p95_ms"], _number, f"{path}.end_to_end_p95_ms"),
        queue_high_water=_nullable(value["queue_high_water"], _integer, f"{path}.queue_high_water"),
        drops=_nullable(value["drops"], _integer, f"{path}.drops"),
        errors=_nullable(value["errors"], _integer, f"{path}.errors"),
        memory_peak_mb=_nullable(value["memory_peak_mb"], _number, f"{path}.memory_peak_mb"),
        samples=_nullable(value["samples"], _integer, f"{path}.samples"),
    )
    if metrics.output_hz is not None and metrics.output_hz <= 0.0:
        _fail("OUT_OF_RANGE", f"{path}.output_hz")
    for low, high in (
        (metrics.inference_latency_p50_ms, metrics.inference_latency_p95_ms),
        (metrics.end_to_end_p50_ms, metrics.end_to_end_p95_ms),
    ):
        if low is not None and high is not None and high < low:
            _fail("INVARIANT", path)
    if all(getattr(metrics, name) is None for name in TIMING_KEYS):
        _fail("INVARIANT", path)
    return metrics


def _decode_arm(value, path) -> Arm:
    _keys(value, ("arm_id", "label", "implementation", "status", "reason", "accuracy", "timing"), path)
    implementation = value["implementation"]
    _keys(implementation, ("name", "version"), f"{path}.implementation")
    arm = Arm(
        arm_id=_enum(value["arm_id"], ArmId, f"{path}.arm_id"),
        label=_text(value["label"], f"{path}.label"),
        implementation=ArmImplementation(
            name=_text(implementation["name"], f"{path}.implementation.name"),
            version=_text(implementation["version"], f"{path}.implementation.version"),
        ),
        status=_enum(value["status"], ArmStatus, f"{path}.status"),
        reason=_nullable(value["reason"], _text, f"{path}.reason"),
        accuracy=None if value["accuracy"] is None else _decode_accuracy(value["accuracy"], f"{path}.accuracy"),
        timing=None if value["timing"] is None else _decode_timing(value["timing"], f"{path}.timing"),
    )
    if arm.status is ArmStatus.EVALUATED:
        if arm.accuracy is None and arm.timing is None:
            _fail("INVARIANT", path)
    else:
        # "Not run" and "not implemented" are answers, so they must say why and carry no numbers.
        if arm.reason is None:
            _fail("INVARIANT", f"{path}.reason")
        if arm.accuracy is not None or arm.timing is not None:
            _fail("INVARIANT", path)
    return arm


def _decode_segment(value, path) -> Segment:
    _keys(value, ("kind", "start_ns", "end_ns"), path)
    segment = Segment(
        kind=_enum(value["kind"], SegmentKind, f"{path}.kind"),
        start_ns=_decimal(value["start_ns"], f"{path}.start_ns"),
        end_ns=_decimal(value["end_ns"], f"{path}.end_ns"),
    )
    if segment.end_ns <= segment.start_ns:
        _fail("OUT_OF_RANGE", f"{path}.end_ns")
    return segment


def decode_report(document, *, path: str = "$") -> EvaluationReport:
    """Validate and type one report document. Raises [EvaluationError] on the first rule broken."""
    _keys(
        document,
        ("evaluation_contract_version", "evaluation_id", "created_utc_ms", "session", "platform",
         "reference", "segments", "arms"),
        path,
    )
    if document["evaluation_contract_version"] != EVALUATION_CONTRACT_VERSION:
        _fail("INVALID_VERSION", f"{path}.evaluation_contract_version")

    session_value = document["session"]
    _keys(session_value, ("session_id", "source", "contract_version", "duration_s", "records", "description"),
          f"{path}.session")
    session = SessionDescriptor(
        session_id=_text(session_value["session_id"], f"{path}.session.session_id"),
        source=_token(session_value["source"], f"{path}.session.source"),
        contract_version=_version(session_value["contract_version"], f"{path}.session.contract_version"),
        duration_s=_number(session_value["duration_s"], f"{path}.session.duration_s"),
        records=_integer(session_value["records"], f"{path}.session.records"),
        description=_text(session_value["description"], f"{path}.session.description"),
    )
    if session.duration_s <= 0.0:
        _fail("OUT_OF_RANGE", f"{path}.session.duration_s")

    platform_value = document["platform"]
    _keys(platform_value, ("host", "device_model", "android_release", "note"), f"{path}.platform")
    platform = PlatformDescriptor(
        host=_boolean(platform_value["host"], f"{path}.platform.host"),
        device_model=_nullable(platform_value["device_model"], _text, f"{path}.platform.device_model"),
        android_release=_nullable(platform_value["android_release"], _text, f"{path}.platform.android_release"),
        note=_text(platform_value["note"], f"{path}.platform.note"),
    )
    if platform.host and (platform.device_model is not None or platform.android_release is not None):
        _fail("INVARIANT", f"{path}.platform")

    reference_value = document["reference"]
    _keys(reference_value, ("kind", "independent", "description"), f"{path}.reference")
    reference = ReferenceDescriptor(
        kind=_enum(reference_value["kind"], ReferenceKind, f"{path}.reference.kind"),
        independent=_boolean(reference_value["independent"], f"{path}.reference.independent"),
        description=_text(reference_value["description"], f"{path}.reference.description"),
    )
    if reference.kind is ReferenceKind.NONE and reference.independent:
        _fail("INVARIANT", f"{path}.reference.independent")

    segments_value = document["segments"]
    if type(segments_value) is not list or len(segments_value) > 64:
        _fail("INVALID_TYPE", f"{path}.segments")
    segments = tuple(
        _decode_segment(item, f"{path}.segments[{index}]") for index, item in enumerate(segments_value)
    )
    for previous, current in zip(segments, segments[1:]):
        if current.start_ns < previous.end_ns:
            _fail("INVARIANT", f"{path}.segments")

    arms_value = document["arms"]
    if type(arms_value) is not list or not arms_value or len(arms_value) > len(ArmId):
        _fail("INVALID_TYPE", f"{path}.arms")
    arms = tuple(_decode_arm(item, f"{path}.arms[{index}]") for index, item in enumerate(arms_value))
    if len({arm.arm_id for arm in arms}) != len(arms):
        _fail("DUPLICATE_ARM", f"{path}.arms")

    for index, arm in enumerate(arms):
        # Accuracy is a claim about error against a reference. Without a declared independent
        # reference the claim cannot exist, whatever the run measured.
        if arm.accuracy is not None and (
            reference.kind is ReferenceKind.NONE or not reference.independent
        ):
            _fail("INVARIANT", f"{path}.arms[{index}].accuracy")

    return EvaluationReport(
        evaluation_contract_version=EVALUATION_CONTRACT_VERSION,
        evaluation_id=_text(document["evaluation_id"], f"{path}.evaluation_id"),
        created_utc_ms=_decimal(document["created_utc_ms"], f"{path}.created_utc_ms"),
        session=session,
        platform=platform,
        reference=reference,
        segments=segments,
        arms=arms,
    )


def decode_json(text: str) -> EvaluationReport:
    if not isinstance(text, str) or len(text.encode("utf-8")) > MAX_BYTES:
        _fail("OUT_OF_RANGE", "$")
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        _fail("INVALID_JSON", "$")
    return decode_report(document)


def _encode_accuracy(metrics: AccuracyMetrics) -> dict:
    return {name: getattr(metrics, name) for name in ACCURACY_KEYS}


def _encode_timing(metrics: TimingMetrics) -> dict:
    return {name: getattr(metrics, name) for name in TIMING_KEYS}


def encode_report(report: EvaluationReport) -> dict:
    """The canonical document. Decoding what this returns reproduces the same typed report."""
    return {
        "evaluation_contract_version": EVALUATION_CONTRACT_VERSION,
        "evaluation_id": report.evaluation_id,
        "created_utc_ms": str(report.created_utc_ms),
        "session": {
            "session_id": report.session.session_id,
            "source": report.session.source,
            "contract_version": report.session.contract_version,
            "duration_s": report.session.duration_s,
            "records": report.session.records,
            "description": report.session.description,
        },
        "platform": {
            "host": report.platform.host,
            "device_model": report.platform.device_model,
            "android_release": report.platform.android_release,
            "note": report.platform.note,
        },
        "reference": {
            "kind": report.reference.kind.value,
            "independent": report.reference.independent,
            "description": report.reference.description,
        },
        "segments": [
            {"kind": segment.kind.value, "start_ns": str(segment.start_ns), "end_ns": str(segment.end_ns)}
            for segment in report.segments
        ],
        "arms": [
            {
                "arm_id": arm.arm_id.value,
                "label": arm.label,
                "implementation": {"name": arm.implementation.name, "version": arm.implementation.version},
                "status": arm.status.value,
                "reason": arm.reason,
                "accuracy": None if arm.accuracy is None else _encode_accuracy(arm.accuracy),
                "timing": None if arm.timing is None else _encode_timing(arm.timing),
            }
            for arm in report.arms
        ],
    }


def encode_json(report: EvaluationReport) -> str:
    return json.dumps(encode_report(report), indent=2, sort_keys=False) + "\n"
