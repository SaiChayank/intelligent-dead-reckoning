"""Strict codec for recording metadata and measurement-record binding.

Metadata uses its own 1.0.0 schema. `measurements.jsonl` rows are the existing
contracts.v1 Record wire format; this module only verifies that each row belongs
to the recording declared by metadata.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import asdict
from enum import Enum

from contracts.v1.codec import ContractError, decode_json as decode_measurement_json, encode_json as encode_measurement_json
from contracts.v1.models import Record, Sensor, Source
from .models import *

MAX_INT64 = (1 << 63) - 1
MAX_METADATA_BYTES = 262_144
_TEXT_LIMIT = 2048
_CHANNEL = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")


class RecordingContractError(ValueError):
    def __init__(self, code: str, path: str = "$"):
        self.code = code
        self.path = path
        super().__init__(f"{code} at {path}")


def _fail(code: str, path: str = "$"):
    raise RecordingContractError(code, path) from None


def _pairs(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            _fail("DUPLICATE_KEY", "$")
        out[key] = value
    return out


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


def _decimal(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) is not str or not re.fullmatch(r"0|[1-9][0-9]*", value) or len(value) > 19:
        _fail("INVALID_TYPE", path)
    parsed = int(value)
    if not 0 <= parsed <= MAX_INT64:
        _fail("OUT_OF_RANGE", path)
    return parsed


def _small_int(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) is not int or isinstance(value, bool) or not 0 <= value <= MAX_INT64:
        _fail("INVALID_TYPE" if type(value) is not int or isinstance(value, bool) else "OUT_OF_RANGE", path)
    return value


def _number(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) not in (int, float) or isinstance(value, bool):
        _fail("INVALID_TYPE", path)
    value = float(value)
    if not math.isfinite(value) or value <= 0:
        _fail("OUT_OF_RANGE", path)
    return value


def _boolean(value, path, nullable=False):
    if value is None and nullable:
        return None
    if type(value) is not bool:
        _fail("INVALID_TYPE", path)
    return value


def _enum(value, cls, path):
    if type(value) is not str:
        _fail("INVALID_TYPE", path)
    try:
        return cls(value)
    except ValueError:
        _fail("INVALID_ENUM", path)


def _load(data: bytes):
    if not isinstance(data, bytes):
        _fail("INVALID_TYPE")
    if len(data) > MAX_METADATA_BYTES:
        _fail("RESOURCE_LIMIT")
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError:
        _fail("INVALID_UTF8")
    if text.startswith("\ufeff"):
        _fail("MALFORMED_JSON")
    try:
        return json.loads(text, object_pairs_hook=_pairs,
                          parse_constant=lambda _: _fail("MALFORMED_JSON"))
    except RecordingContractError:
        raise
    except (json.JSONDecodeError, ValueError, RecursionError):
        _fail("MALFORMED_JSON")


def _nullable_enum(value, cls, path):
    return None if value is None else _enum(value, cls, path)


def _clock(raw):
    p = "$.clock"
    _keys(raw, ("domain", "boot_id", "session_clock_id", "origin_ns", "started_ns", "ended_ns"), p)
    value = ClockIdentity(
        domain=_enum(raw["domain"], ClockDomain, p + ".domain"),
        boot_id=_string(raw["boot_id"], p + ".boot_id", True),
        session_clock_id=_string(raw["session_clock_id"], p + ".session_clock_id"),
        origin_ns=_decimal(raw["origin_ns"], p + ".origin_ns"),
        started_ns=_decimal(raw["started_ns"], p + ".started_ns"),
        ended_ns=_decimal(raw["ended_ns"], p + ".ended_ns", True),
    )
    if value.started_ns < value.origin_ns:
        _fail("INVARIANT", p + ".started_ns")
    if value.ended_ns is not None and value.ended_ns < value.started_ns:
        _fail("INVARIANT", p + ".ended_ns")
    return value


def _device(raw):
    p = "$.device"
    _keys(raw, ("manufacturer", "model", "os_name", "os_version", "api_level"), p)
    return DeviceInfo(
        _string(raw["manufacturer"], p + ".manufacturer", True),
        _string(raw["model"], p + ".model", True),
        _string(raw["os_name"], p + ".os_name", True),
        _string(raw["os_version"], p + ".os_version", True),
        _small_int(raw["api_level"], p + ".api_level", True),
    )


def _application(raw):
    p = "$.application"
    _keys(raw, ("package_name", "version_name", "version_code", "source_version"), p)
    return ApplicationInfo(
        _string(raw["package_name"], p + ".package_name", True),
        _string(raw["version_name"], p + ".version_name", True),
        _small_int(raw["version_code"], p + ".version_code", True),
        _string(raw["source_version"], p + ".source_version", True),
    )


def _sensor(raw, index):
    p = f"$.sensors[{index}]"
    _keys(raw, ("sensor", "name", "vendor", "available", "requested_hz", "measured_hz"), p)
    return SensorDescriptor(
        _enum(raw["sensor"], Sensor, p + ".sensor"),
        _string(raw["name"], p + ".name", True),
        _string(raw["vendor"], p + ".vendor", True),
        _boolean(raw["available"], p + ".available"),
        _number(raw["requested_hz"], p + ".requested_hz", True),
        _number(raw["measured_hz"], p + ".measured_hz", True),
    )


def _source_configuration(raw):
    p = "$.source_configuration"
    _keys(raw, ("location_permission", "location_service_enabled", "gps_provider_enabled",
                "network_provider_enabled", "satellite_status_enabled", "foreground_only"), p)
    value = SourceConfiguration(
        _enum(raw["location_permission"], LocationPermissionState, p + ".location_permission"),
        _boolean(raw["location_service_enabled"], p + ".location_service_enabled", True),
        _boolean(raw["gps_provider_enabled"], p + ".gps_provider_enabled", True),
        _boolean(raw["network_provider_enabled"], p + ".network_provider_enabled", True),
        _boolean(raw["satellite_status_enabled"], p + ".satellite_status_enabled", True),
        _boolean(raw["foreground_only"], p + ".foreground_only"),
    )
    if not value.foreground_only:
        _fail("INVARIANT", p + ".foreground_only")
    return value


def _calibration(raw):
    p = "$.calibration"
    _keys(raw, ("state", "calibration_id"), p)
    value = CalibrationInfo(
        _enum(raw["state"], CalibrationApplication, p + ".state"),
        _string(raw["calibration_id"], p + ".calibration_id", True),
    )
    if (value.state is CalibrationApplication.APPLIED) != (value.calibration_id is not None):
        _fail("INVARIANT", p + ".calibration_id")
    return value


def _channel_count(raw, index):
    p = f"$.channel_counts[{index}]"
    _keys(raw, ("channel", "count"), p)
    channel = _string(raw["channel"], p + ".channel")
    if not _CHANNEL.fullmatch(channel):
        _fail("OUT_OF_RANGE", p + ".channel")
    return ChannelCount(channel, _decimal(raw["count"], p + ".count"))


def _validate_metadata(value: RecordingMetadata) -> RecordingMetadata:
    if value.recording_contract_version != RECORDING_CONTRACT_VERSION:
        _fail("INVALID_VERSION", "$.recording_contract_version")
    if value.measurement_contract_version != MEASUREMENT_CONTRACT_VERSION:
        _fail("INVALID_VERSION", "$.measurement_contract_version")
    _string(value.recording_id, "$.recording_id")
    _string(value.acquisition_session_id, "$.acquisition_session_id")
    if value.source not in (Source.REAL, Source.SIMULATION):
        _fail("INVALID_ENUM", "$.source")
    if value.start_state is not RecordingStartState.RECORDING:
        _fail("INVARIANT", "$.start_state")
    if value.clock.started_ns < value.clock.origin_ns:
        _fail("INVARIANT", "$.clock.started_ns")
    if value.clock.ended_ns is not None and value.clock.ended_ns < value.clock.started_ns:
        _fail("INVARIANT", "$.clock.ended_ns")
    if len({s.sensor for s in value.sensors}) != len(value.sensors):
        _fail("DUPLICATE_SENSOR", "$.sensors")
    for s in value.sensors:
        if s.requested_hz is not None and (not math.isfinite(s.requested_hz) or s.requested_hz <= 0):
            _fail("OUT_OF_RANGE", "$.sensors.requested_hz")
        if s.measured_hz is not None and (not math.isfinite(s.measured_hz) or s.measured_hz <= 0):
            _fail("OUT_OF_RANGE", "$.sensors.measured_hz")
    if not value.source_configuration.foreground_only:
        _fail("INVARIANT", "$.source_configuration.foreground_only")
    if (value.calibration.state is CalibrationApplication.APPLIED) != (value.calibration.calibration_id is not None):
        _fail("INVARIANT", "$.calibration.calibration_id")

    counts = value.channel_counts
    if counts is not None:
        if len({c.channel for c in counts}) != len(counts):
            _fail("DUPLICATE_CHANNEL", "$.channel_counts")
        for c in counts:
            if not _CHANNEL.fullmatch(c.channel) or not 0 <= c.count <= MAX_INT64:
                _fail("OUT_OF_RANGE", "$.channel_counts")
        if value.record_count is None or sum(c.count for c in counts) != value.record_count:
            _fail("INVARIANT", "$.channel_counts")
    if value.record_count is not None and not 0 <= value.record_count <= MAX_INT64:
        _fail("OUT_OF_RANGE", "$.record_count")

    if value.completion_state is CompletionState.OPEN:
        if any((value.end_state is not None, value.ended_utc_ms is not None,
                value.clock.ended_ns is not None, value.record_count is not None,
                value.channel_counts is not None, value.recovery_state is not RecoveryState.NONE)):
            _fail("INVARIANT", "$.completion_state")
    elif value.completion_state is CompletionState.COMPLETED:
        if value.end_state is not RecordingEndState.STOPPED or value.clock.ended_ns is None or value.record_count is None:
            _fail("INVARIANT", "$.completion_state")
        if value.recovery_state is not RecoveryState.NONE:
            _fail("INVARIANT", "$.recovery_state")
    elif value.completion_state is CompletionState.INCOMPLETE:
        if value.end_state is not RecordingEndState.INTERRUPTED:
            _fail("INVARIANT", "$.end_state")
        if value.recovery_state is RecoveryState.NONE:
            _fail("INVARIANT", "$.recovery_state")
        if value.recovery_state is RecoveryState.RECOVERED and value.record_count is None:
            _fail("INVARIANT", "$.record_count")
    elif value.completion_state is CompletionState.FAILED:
        if value.end_state is not RecordingEndState.FAILED:
            _fail("INVARIANT", "$.end_state")
    return value


def decode_metadata(data: bytes) -> RecordingMetadata:
    raw = _load(data)
    _keys(raw, (
        "recording_contract_version", "measurement_contract_version", "recording_id",
        "acquisition_session_id", "source", "start_state", "end_state",
        "completion_state", "recovery_state", "created_utc_ms", "started_utc_ms",
        "ended_utc_ms", "clock", "device", "application", "sensors",
        "source_configuration", "calibration", "record_count", "channel_counts",
    ), "$")
    if raw["recording_contract_version"] != RECORDING_CONTRACT_VERSION:
        _fail("INVALID_VERSION", "$.recording_contract_version")
    if raw["measurement_contract_version"] != MEASUREMENT_CONTRACT_VERSION:
        _fail("INVALID_VERSION", "$.measurement_contract_version")
    if type(raw["sensors"]) is not list:
        _fail("INVALID_TYPE", "$.sensors")
    sensors = tuple(_sensor(v, i) for i, v in enumerate(raw["sensors"]))
    cc_raw = raw["channel_counts"]
    if cc_raw is not None and type(cc_raw) is not list:
        _fail("INVALID_TYPE", "$.channel_counts")
    counts = None if cc_raw is None else tuple(_channel_count(v, i) for i, v in enumerate(cc_raw))
    value = RecordingMetadata(
        recording_id=_string(raw["recording_id"], "$.recording_id"),
        acquisition_session_id=_string(raw["acquisition_session_id"], "$.acquisition_session_id"),
        source=_enum(raw["source"], Source, "$.source"),
        start_state=_enum(raw["start_state"], RecordingStartState, "$.start_state"),
        end_state=_nullable_enum(raw["end_state"], RecordingEndState, "$.end_state"),
        completion_state=_enum(raw["completion_state"], CompletionState, "$.completion_state"),
        recovery_state=_enum(raw["recovery_state"], RecoveryState, "$.recovery_state"),
        created_utc_ms=_decimal(raw["created_utc_ms"], "$.created_utc_ms", True),
        started_utc_ms=_decimal(raw["started_utc_ms"], "$.started_utc_ms", True),
        ended_utc_ms=_decimal(raw["ended_utc_ms"], "$.ended_utc_ms", True),
        clock=_clock(raw["clock"]),
        device=_device(raw["device"]),
        application=_application(raw["application"]),
        sensors=sensors,
        source_configuration=_source_configuration(raw["source_configuration"]),
        calibration=_calibration(raw["calibration"]),
        record_count=_decimal(raw["record_count"], "$.record_count", True),
        channel_counts=counts,
    )
    return _validate_metadata(value)


def _wire(value):
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, tuple):
        return [_wire(v) for v in value]
    if isinstance(value, list):
        return [_wire(v) for v in value]
    if isinstance(value, dict):
        return {k: _wire(v) for k, v in value.items()}
    return value


def encode_metadata(value: RecordingMetadata) -> bytes:
    if not isinstance(value, RecordingMetadata):
        _fail("INVALID_MODEL")
    _validate_metadata(value)
    raw = {
        "recording_contract_version": value.recording_contract_version,
        "measurement_contract_version": value.measurement_contract_version,
        "recording_id": value.recording_id,
        "acquisition_session_id": value.acquisition_session_id,
        "source": value.source.value,
        "start_state": value.start_state.value,
        "end_state": None if value.end_state is None else value.end_state.value,
        "completion_state": value.completion_state.value,
        "recovery_state": value.recovery_state.value,
        "created_utc_ms": None if value.created_utc_ms is None else str(value.created_utc_ms),
        "started_utc_ms": None if value.started_utc_ms is None else str(value.started_utc_ms),
        "ended_utc_ms": None if value.ended_utc_ms is None else str(value.ended_utc_ms),
        "clock": {
            "domain": value.clock.domain.value,
            "boot_id": value.clock.boot_id,
            "session_clock_id": value.clock.session_clock_id,
            "origin_ns": str(value.clock.origin_ns),
            "started_ns": str(value.clock.started_ns),
            "ended_ns": None if value.clock.ended_ns is None else str(value.clock.ended_ns),
        },
        "device": asdict(value.device),
        "application": asdict(value.application),
        "sensors": [
            {
                "sensor": s.sensor.value,
                "name": s.name,
                "vendor": s.vendor,
                "available": s.available,
                "requested_hz": s.requested_hz,
                "measured_hz": s.measured_hz,
            } for s in value.sensors
        ],
        "source_configuration": {
            **asdict(value.source_configuration),
            "location_permission": value.source_configuration.location_permission.value,
        },
        "calibration": {
            "state": value.calibration.state.value,
            "calibration_id": value.calibration.calibration_id,
        },
        "record_count": None if value.record_count is None else str(value.record_count),
        "channel_counts": None if value.channel_counts is None else [
            {"channel": c.channel, "count": str(c.count)} for c in value.channel_counts
        ],
    }
    try:
        encoded = json.dumps(raw, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError):
        _fail("INVALID_MODEL")
    if len(encoded) > MAX_METADATA_BYTES:
        _fail("RESOURCE_LIMIT")
    # Decode the emitted bytes too, so writer and reader enforce identical invariants.
    decode_metadata(encoded)
    return encoded


def _validate_record_membership(record: Record, metadata: RecordingMetadata) -> Record:
    _validate_metadata(metadata)
    if record.header.contract_version != metadata.measurement_contract_version:
        _fail("INVALID_VERSION", "$.measurement_contract_version")
    if record.header.session_id != metadata.acquisition_session_id:
        _fail("SESSION_MISMATCH", "$.acquisition_session_id")
    if record.header.source != metadata.source:
        _fail("SOURCE_MISMATCH", "$.source")
    return record


def encode_record(record: Record, metadata: RecordingMetadata) -> bytes:
    _validate_record_membership(record, metadata)
    try:
        encoded = encode_measurement_json(record)
    except ContractError as exc:
        raise RecordingContractError(exc.code, exc.path) from None
    # Existing measurement codec is canonical; never wrap or reshape its JSON.
    return encoded


def decode_record(data: bytes, metadata: RecordingMetadata) -> Record:
    try:
        record = decode_measurement_json(data)
    except ContractError as exc:
        raise RecordingContractError(exc.code, exc.path) from None
    return _validate_record_membership(record, metadata)
