"""Typed contract values. No data access, Android assumptions or navigation implementation."""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum
from typing import ClassVar, Protocol, Iterable

class Source(str, Enum):
    REAL = "real"
    SIMULATION = "simulation"
    REPLAY_REAL = "replay_real"
    REPLAY_SIMULATION = "replay_simulation"

class Sensor(str, Enum):
    ACCELEROMETER = "accelerometer"
    GYROSCOPE = "gyroscope"
    GRAVITY = "gravity"
    MAGNETOMETER = "magnetometer"

class DeviceFrame(str, Enum):
    ANDROID_DEVICE = "android_device"

class ImuUnit(str, Enum):
    METRES_PER_SECOND_SQUARED = "m/s^2"
    RADIANS_PER_SECOND = "rad/s"
    MICROTESLA = "uT"

class SensorAccuracy(str, Enum):
    UNKNOWN = "unknown"
    UNRELIABLE = "unreliable"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"

class AltitudeReference(str, Enum):
    ELLIPSOID = "ellipsoid"
    MSL = "msl"
    UNKNOWN = "unknown"

class CalibrationStatus(str, Enum):
    PENDING = "pending"
    VALID = "valid"
    INVALID = "invalid"
    EXPIRED = "expired"

class NavigationStatus(str, Enum):
    UNINITIALIZED = "uninitialized"
    CALIBRATING = "calibrating"
    TRACKING = "tracking"
    DEGRADED = "degraded"
    FAILED = "failed"

class InitializationMode(str, Enum):
    EVALUATION = "evaluation"
    DEPLOYABLE = "deployable"

class GnssState(str, Enum):
    UNAVAILABLE = "unavailable"
    ACQUIRING = "acquiring"
    GOOD = "good"
    DEGRADED = "degraded"
    STALE = "stale"
    DENIED = "denied"

class ConfidenceState(str, Enum):
    UNAVAILABLE = "unavailable"
    UNVALIDATED = "unvalidated"
    CALIBRATED = "calibrated"

class Severity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"

@dataclass(frozen=True, slots=True)
class Vector3:
    x: float
    y: float
    z: float

@dataclass(frozen=True, slots=True)
class Quaternion:
    w: float
    x: float
    y: float
    z: float

@dataclass(frozen=True, slots=True)
class GeoOrigin:
    latitude_deg: float
    longitude_deg: float
    altitude_m: float

class Payload:
    TYPE: ClassVar[str]

@dataclass(frozen=True, slots=True)
class ImuMeasurement(Payload):
    TYPE: ClassVar[str] = "imu"
    sensor: Sensor
    frame: DeviceFrame
    unit: ImuUnit
    xyz: Vector3
    accuracy: SensorAccuracy

@dataclass(frozen=True, slots=True)
class GnssMeasurement(Payload):
    TYPE: ClassVar[str] = "gnss"
    latitude_deg: float
    longitude_deg: float
    altitude_m: float | None
    altitude_reference: AltitudeReference | None
    speed_m_s: float | None
    bearing_deg: float | None
    horizontal_accuracy_m: float | None
    vertical_accuracy_m: float | None
    satellites_used: int | None
    provider: str
    utc_ms: int | None

@dataclass(frozen=True, slots=True)
class CalibrationResult(Payload):
    TYPE: ClassVar[str] = "calibration"
    id: str
    status: CalibrationStatus
    q_vehicle_from_device_wxyz: Quaternion | None
    gyro_bias_rad_s: Vector3 | None
    accelerometer_bias_m_s2: Vector3 | None
    confidence: float | None

@dataclass(frozen=True, slots=True)
class NavigationState(Payload):
    TYPE: ClassVar[str] = "navigation"
    status: NavigationStatus
    initialization_mode: InitializationMode
    origin_wgs84_deg_m: GeoOrigin | None
    position_enu_m: Vector3 | None
    velocity_enu_m_s: Vector3 | None
    q_enu_from_vehicle_wxyz: Quaternion | None
    heading_deg: float | None
    calibration_id: str | None
    gnss_used_after_initialization: bool

@dataclass(frozen=True, slots=True)
class GnssQualityState(Payload):
    TYPE: ClassVar[str] = "gnss_quality"
    state: GnssState
    fix_age_s: float | None
    satellites_used: int | None
    reasons: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class Confidence(Payload):
    TYPE: ClassVar[str] = "confidence"
    state: ConfidenceState
    probability: float | None
    horizontal_accuracy_95_m: float | None
    speed_std_m_s: float | None

@dataclass(frozen=True, slots=True)
class DiagnosticEvent(Payload):
    TYPE: ClassVar[str] = "diagnostic"
    severity: Severity
    code: str
    message: str
    dropped_count: int

@dataclass(frozen=True, slots=True)
class Header:
    session_id: str
    source: Source
    contract_version: str = "1.0.0"

@dataclass(frozen=True, slots=True)
class Event:
    event_id: str
    t_ns: int
    received_ns: int
    data: Payload

@dataclass(frozen=True, slots=True)
class Record:
    header: Header
    event: Event

@dataclass(frozen=True, slots=True)
class EngineSession:
    header: Header
    boot_id: str
    origin_ns: int

class NavigationEngine(Protocol):
    """Interface ONLY. No concrete navigation engine is provided by this package.

    Implementations must validate inputs, enforce causal calibration/session ownership,
    never mix initialization modes, and cap/drain their output queue. No UI/map types.
    """
    def initialize(self, session: EngineSession, calibration: Record,
                   mode: InitializationMode) -> None: ...
    def accept_imu(self, measurement: Record) -> None: ...
    def accept_gnss(self, measurement: Record) -> None: ...
    def drain(self) -> Iterable[Record]: ...
    def stop(self) -> None: ...
    def reset(self) -> None: ...

