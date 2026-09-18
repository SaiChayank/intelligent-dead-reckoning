"""Strict, bounded single-record JSON and fail-fast streaming JSONL codecs.

Streams are caller-owned binary streams. Nothing opens files at import time.
Each record is validated before it is yielded/written; a later stream error does
not roll back an already-consumed valid prefix. See README for limits and framing.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, fields
from enum import Enum
from typing import BinaryIO, Iterable, Iterator

from .models import *

MAX_INT64 = (1 << 63) - 1
DECIMAL = re.compile(r"(?:0|[1-9][0-9]*)\Z")
CODE = re.compile(r"[A-Z][A-Z0-9_]*\Z")


class ContractError(ValueError):
    """Machine-readable, payload-redacted diagnostic. Line is one-based for JSONL."""
    def __init__(self, code: str, path: str = "$", line: int | None = None):
        self.code, self.path, self.line = code, path, line
        super().__init__(f"{code} at {path}" + (f" (line {line})" if line else ""))


@dataclass(frozen=True)
class Limits:
    max_record_bytes: int = 65536
    max_records: int = 1_000_000

    def __post_init__(self):
        if any(type(v) is not int or v <= 0 for v in (self.max_record_bytes, self.max_records)):
            raise ValueError("Limits must be positive integers")


def _fail(code, path):
    raise ContractError(code, path) from None


def _keys(value, expected, path):
    if type(value) is not dict:
        _fail("INVALID_TYPE", path)
    if set(value) != set(expected):
        _fail("INVALID_KEYS", path)


def _string(v, path):
    if type(v) is not str:
        _fail("INVALID_TYPE", path)
    try:
        v.encode("utf-8", "strict")
    except UnicodeEncodeError:
        _fail("INVALID_UNICODE", path)
    if not v.strip() or len(v.encode("utf-16-le")) // 2 > 2048:
        _fail("OUT_OF_RANGE", path)
    return v


def _integer(v, path):
    if type(v) is not int:
        _fail("INVALID_TYPE", path)
    if not 0 <= v <= MAX_INT64:
        _fail("OUT_OF_RANGE", path)
    return v


def _decimal(v, path):
    if type(v) is not str:
        _fail("INVALID_TYPE", path)
    if len(v) > 19 or not DECIMAL.fullmatch(v):
        _fail("OUT_OF_RANGE", path)
    return _integer(int(v), path)


def _number(v, path):
    if type(v) not in (int, float):
        _fail("INVALID_TYPE", path)
    try:
        result = float(v)
    except OverflowError:
        _fail("NONFINITE", path)
    if not math.isfinite(result):
        _fail("NONFINITE", path)
    return result


def _array(v, length, path):
    if type(v) is not list or len(v) != length:
        _fail("INVALID_SHAPE", path)
    return [_number(x, path) for x in v]


def validate_rotation(matrix) -> None:
    """Reject reflections/nonrotations; never project or silently repair them."""
    p = "$.rotation"
    if not isinstance(matrix, (list, tuple)) or len(matrix) != 3:
        _fail("INVALID_SHAPE", p)
    m = [_array(list(row) if isinstance(row, (list, tuple)) else row, 3, p) for row in matrix]
    if any(abs(sum(m[k][i]*m[k][j] for k in range(3)) - (1 if i == j else 0)) > 1e-6
           for i in range(3) for j in range(3)):
        _fail("INVALID_ROTATION", p)
    a, b, c = m
    det = a[0]*(b[1]*c[2]-b[2]*c[1])-a[1]*(b[0]*c[2]-b[2]*c[0])+a[2]*(b[0]*c[1]-b[1]*c[0])
    if abs(det-1) > 1e-6:
        _fail("INVALID_ROTATION", p)


def _field(v, rule, path):
    if rule.endswith("?"):
        if v is None:
            return None
        rule = rule[:-1]
    enum_type = globals().get(rule)
    if isinstance(enum_type, type) and issubclass(enum_type, Enum):
        if type(v) is not str:
            _fail("INVALID_TYPE", path)
        try:
            return enum_type(v)
        except ValueError:
            _fail("INVALID_ENUM", path)
    if rule in ("str", "code"):
        v = _string(v, path)
        if rule == "code" and not CODE.fullmatch(v):
            _fail("OUT_OF_RANGE", path)
        return v
    if rule == "int":
        return _integer(v, path)
    if rule == "bool":
        if type(v) is not bool:
            _fail("INVALID_TYPE", path)
        return v
    if rule == "strings":
        if type(v) is not list or len(v) > 64:
            _fail("INVALID_SHAPE", path)
        return tuple(_field(x, "code", path) for x in v)
    if rule in ("vec", "quat", "origin"):
        a = _array(v, 4 if rule == "quat" else 3, path)
        if rule == "quat" and abs(math.hypot(*a) - 1) > 1e-6:
            _fail("INVALID_ROTATION", path)
        if rule == "origin" and not (-90 <= a[0] <= 90 and -180 <= a[1] <= 180):
            _fail("OUT_OF_RANGE", path)
        return {"vec": Vector3, "quat": Quaternion, "origin": GeoOrigin}[rule](*a)
    n = _number(v, path)
    valid = {"num": True, "nonneg": n >= 0, "prob": 0 <= n <= 1,
             "lat": -90 <= n <= 90, "lon": -180 <= n <= 180, "heading": 0 <= n < 360}
    if not valid[rule]:
        _fail("OUT_OF_RANGE", path)
    return n


# Per-event declarations are intentionally explicit and mirrored in the Kotlin codec.
SPECS = {
    "imu": (ImuMeasurement, {"sensor":"Sensor","frame":"DeviceFrame","unit":"ImuUnit","xyz":"vec","accuracy":"SensorAccuracy"}),
    "gnss": (GnssMeasurement, {"latitude_deg":"lat","longitude_deg":"lon","altitude_m":"num?","altitude_reference":"AltitudeReference?","speed_m_s":"nonneg?","bearing_deg":"heading?","horizontal_accuracy_m":"nonneg?","vertical_accuracy_m":"nonneg?","satellites_used":"int?","provider":"str","utc_ms":"int?"}),
    "calibration": (CalibrationResult, {"id":"str","status":"CalibrationStatus","q_vehicle_from_device_wxyz":"quat?","gyro_bias_rad_s":"vec?","accelerometer_bias_m_s2":"vec?","confidence":"prob?"}),
    "navigation": (NavigationState, {"status":"NavigationStatus","initialization_mode":"InitializationMode","origin_wgs84_deg_m":"origin?","position_enu_m":"vec?","velocity_enu_m_s":"vec?","q_enu_from_vehicle_wxyz":"quat?","heading_deg":"heading?","calibration_id":"str?","gnss_used_after_initialization":"bool"}),
    "gnss_quality": (GnssQualityState, {"state":"GnssState","fix_age_s":"nonneg?","satellites_used":"int?","reasons":"strings"}),
    "confidence": (Confidence, {"state":"ConfidenceState","probability":"prob?","horizontal_accuracy_95_m":"nonneg?","speed_std_m_s":"nonneg?"}),
    "diagnostic": (DiagnosticEvent, {"severity":"Severity","code":"code","message":"str","dropped_count":"int"}),
}


def _payload(kind, raw):
    if type(kind) is not str or kind not in SPECS:
        _fail("INVALID_ENUM", "$.event.type")
    model, rules = SPECS[kind]
    _keys(raw, rules, "$.event.data")
    values = {k: _field(raw[k], rule, "$.event.data."+k) for k, rule in rules.items()}
    p = "$.event.data"
    if kind == "imu":
        expected = {Sensor.ACCELEROMETER: ImuUnit.METRES_PER_SECOND_SQUARED,
                    Sensor.GRAVITY: ImuUnit.METRES_PER_SECOND_SQUARED,
                    Sensor.GYROSCOPE: ImuUnit.RADIANS_PER_SECOND,
                    Sensor.MAGNETOMETER: ImuUnit.MICROTESLA}[values["sensor"]]
        if values["unit"] != expected:
            _fail("INVARIANT", p+".unit")
    if kind == "gnss" and ((values["altitude_m"] is None) != (values["altitude_reference"] is None)):
        _fail("INVARIANT", p+".altitude_reference")
    if kind == "calibration" and values["status"] == CalibrationStatus.VALID:
        if values["q_vehicle_from_device_wxyz"] is None or values["gyro_bias_rad_s"] is None:
            _fail("INVARIANT", p)
    if kind == "navigation":
        spatial = ("origin_wgs84_deg_m", "position_enu_m", "velocity_enu_m_s", "q_enu_from_vehicle_wxyz", "calibration_id")
        if values["status"] == NavigationStatus.TRACKING and any(values[k] is None for k in spatial):
            _fail("INVARIANT", p)
        if (values["position_enu_m"] is not None or values["velocity_enu_m_s"] is not None) and values["origin_wgs84_deg_m"] is None:
            _fail("INVARIANT", p+".origin_wgs84_deg_m")
        if values["status"] in (NavigationStatus.UNINITIALIZED, NavigationStatus.CALIBRATING, NavigationStatus.FAILED):
            if any(values[k] is not None for k in spatial + ("heading_deg",)):
                _fail("INVARIANT", p)
    if kind == "confidence" and values["state"] != ConfidenceState.CALIBRATED and values["probability"] is not None:
        _fail("INVARIANT", p+".probability")
    return model(**values)


def _record(raw):
    _keys(raw, ("contract_version", "session_id", "source", "event"), "$")
    if raw["contract_version"] != "1.0.0":
        _fail("INVALID_VERSION", "$.contract_version")
    header = Header(_string(raw["session_id"], "$.session_id"), _field(raw["source"], "Source", "$.source"))
    e = raw["event"]
    _keys(e, ("event_id", "type", "t_ns", "received_ns", "data"), "$.event")
    _decimal(e["event_id"], "$.event.event_id")
    t, received = _decimal(e["t_ns"], "$.event.t_ns"), _decimal(e["received_ns"], "$.event.received_ns")
    if received < t:
        _fail("INVARIANT", "$.event.received_ns")
    return Record(header, Event(e["event_id"], t, received, _payload(e["type"], e["data"])))


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("DUPLICATE_KEY", "$")
        result[key] = value
    return result


def _tree(v, depth=0):
    if depth > 16:
        _fail("RESOURCE_LIMIT", "$")
    if isinstance(v, (dict, list)):
        for x in (v.values() if isinstance(v, dict) else v):
            _tree(x, depth+1)
    elif isinstance(v, str):
        try:
            v.encode("utf-8", "strict")
        except UnicodeEncodeError:
            _fail("INVALID_UNICODE", "$")


def decode_json(data: bytes, limits: Limits = Limits()) -> Record:
    if not isinstance(data, bytes):
        _fail("INVALID_TYPE", "$")
    if len(data) > limits.max_record_bytes:
        _fail("RESOURCE_LIMIT", "$")
    try:
        text = data.decode("utf-8", "strict")
        raw = json.loads(text, object_pairs_hook=_pairs,
                         parse_constant=lambda _: _fail("MALFORMED_JSON", "$"))
        _tree(raw)
        return _record(raw)
    except UnicodeDecodeError:
        _fail("INVALID_UTF8", "$")
    except (json.JSONDecodeError, ValueError) as exc:
        if isinstance(exc, ContractError):
            raise
        _fail("MALFORMED_JSON", "$")
    except RecursionError:
        _fail("RESOURCE_LIMIT", "$")


def _wire_value(value):
    if isinstance(value, Enum):
        return value.value
    if type(value) in (Vector3, Quaternion, GeoOrigin):
        return [getattr(value, f.name) for f in fields(value)]
    if type(value) is tuple:
        return list(value)
    return value


def encode_json(record: Record, limits: Limits = Limits()) -> bytes:
    if type(record) is not Record or type(record.header) is not Header or type(record.event) is not Event:
        _fail("INVALID_MODEL", "$")
    h, e = record.header, record.event
    if type(e.data) not in [s[0] for s in SPECS.values()] or type(h.source) is not Source:
        _fail("INVALID_MODEL", "$")
    # Reject wrong Python runtime field types even though annotations alone cannot.
    for key, rule in SPECS[e.data.TYPE][1].items():
        v = getattr(e.data, key)
        base = rule.rstrip("?")
        expected = globals().get(base, {"vec": Vector3, "quat": Quaternion, "origin": GeoOrigin}.get(base))
        if v is not None and expected is not None and type(v) is not expected:
            _fail("INVALID_MODEL", "$.event.data."+key)
    _integer(e.t_ns, "$.event.t_ns")
    _integer(e.received_ns, "$.event.received_ns")
    raw = dict(contract_version=h.contract_version, session_id=h.session_id, source=h.source.value,
               event=dict(event_id=e.event_id, type=e.data.TYPE, t_ns=str(e.t_ns), received_ns=str(e.received_ns),
                          data={f.name: _wire_value(getattr(e.data, f.name)) for f in fields(e.data)}))
    _record(raw)  # Identical constraints on read and write; never normalize a quaternion.
    try:
        out = json.dumps(raw, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, UnicodeError):
        _fail("INVALID_MODEL", "$")
    if len(out) > limits.max_record_bytes:
        _fail("RESOURCE_LIMIT", "$")
    return out


class _Guard:
    def __init__(self, limits):
        self.limits, self.ids, self.header, self.mode = limits, set(), None, None

    def accept(self, record):
        if self.header is not None and record.header != self.header:
            _fail("SESSION_MISMATCH", "$")
        if record.event.event_id in self.ids:
            _fail("DUPLICATE_EVENT", "$.event.event_id")
        if len(self.ids) >= self.limits.max_records:
            _fail("RESOURCE_LIMIT", "$")
        if isinstance(record.event.data, NavigationState):
            mode = record.event.data.initialization_mode
            if self.mode is not None and mode != self.mode:
                _fail("INVARIANT", "$.event.data.initialization_mode")
            self.mode = mode
        self.header = record.header
        self.ids.add(record.event.event_id)


def read_json(stream: BinaryIO, limits: Limits = Limits()) -> Record:
    data = bytearray()
    try:
        while len(data) <= limits.max_record_bytes:
            chunk = stream.read(min(4096, limits.max_record_bytes+1-len(data)))
            if chunk is None:
                _fail("IO_ERROR", "$")
            if not chunk:
                break
            data.extend(chunk)
    except OSError:
        _fail("IO_ERROR", "$")
    return decode_json(bytes(data), limits)


def _write_all(stream, data):
    try:
        offset = 0
        while offset < len(data):
            n = stream.write(data[offset:])
            if n is None or n <= 0:
                _fail("IO_ERROR", "$")
            offset += n
    except OSError:
        _fail("IO_ERROR", "$")


def write_json(record: Record, stream: BinaryIO, limits: Limits = Limits()) -> None:
    _write_all(stream, encode_json(record, limits))


def read_jsonl(stream: BinaryIO, limits: Limits = Limits()) -> Iterator[Record]:
    guard = _Guard(limits)
    line = 0
    while True:
        line += 1
        try:
            raw = stream.readline(limits.max_record_bytes+3)
            if raw is None:
                _fail("IO_ERROR", "$")
            if not raw:
                return
            if raw.endswith(b"\n"):
                raw = raw[:-1]
                if raw.endswith(b"\r"):
                    raw = raw[:-1]
            record = decode_json(raw, limits)
            guard.accept(record)
        except ContractError as exc:
            raise ContractError(exc.code, exc.path, line) from None
        except OSError:
            raise ContractError("IO_ERROR", "$", line) from None
        yield record


def write_jsonl(records: Iterable[Record], stream: BinaryIO, limits: Limits = Limits()) -> None:
    guard = _Guard(limits)
    for line, record in enumerate(records, 1):
        try:
            raw = encode_json(record, limits)
            guard.accept(record)
            _write_all(stream, raw+b"\n")
        except ContractError as exc:
            raise ContractError(exc.code, exc.path, line) from None
