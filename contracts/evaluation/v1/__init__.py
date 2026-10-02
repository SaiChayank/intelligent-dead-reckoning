"""Evaluation contract 1.0.0: the document an evaluation run produces.

An evaluation report is not a measurement and not navigation output. It records what was
evaluated, against which reference, which arms ran, and which metrics came out — and the codec
enforces that an accuracy figure can only exist when the reference is a declared, independent one
the arm did not consume. See README.md.
"""
from .codec import (
    EvaluationError,
    decode_json,
    decode_report,
    encode_json,
    encode_report,
)
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

__all__ = [
    "EVALUATION_CONTRACT_VERSION",
    "AccuracyMetrics",
    "Arm",
    "ArmId",
    "ArmImplementation",
    "ArmStatus",
    "EvaluationError",
    "EvaluationReport",
    "PlatformDescriptor",
    "ReferenceDescriptor",
    "ReferenceKind",
    "Segment",
    "SegmentKind",
    "SessionDescriptor",
    "TimingMetrics",
    "decode_json",
    "decode_report",
    "encode_json",
    "encode_report",
]
