"""Typed local-recording metadata contract v1.0.0.

This module describes recording/session metadata only. Measurement JSONL rows remain
exactly contracts.v1 Record envelopes and are not redefined here.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from contracts.v1.models import Sensor, Source

RECORDING_CONTRACT_VERSION = "1.0.0"
MEASUREMENT_CONTRACT_VERSION = "1.0.0"


class RecordingStartState(str, Enum):
    RECORDING = "recording"


class RecordingEndState(str, Enum):
    STOPPED = "stopped"
    INTERRUPTED = "interrupted"
    FAILED = "failed"


class CompletionState(str, Enum):
    OPEN = "open"
    COMPLETED = "completed"
    INCOMPLETE = "incomplete"
    FAILED = "failed"


class RecoveryState(str, Enum):
    NONE = "none"
    REQUIRED = "required"
    RECOVERED = "recovered"
    UNRECOVERABLE = "unrecoverable"


class CalibrationApplication(str, Enum):
    UNAVAILABLE = "unavailable"
    NOT_APPLIED = "not_applied"
    APPLIED = "applied"


class LocationPermissionState(str, Enum):
    NOT_REQUESTED = "not_requested"
    DENIED = "denied"
    APPROXIMATE = "approximate"
    PRECISE = "precise"
    REVOKED = "revoked"


class ClockDomain(str, Enum):
    ANDROID_ELAPSED_REALTIME_NS = "android_elapsed_realtime_ns"


@dataclass(frozen=True, slots=True)
class ClockIdentity:
    domain: ClockDomain
    boot_id: str | None
    session_clock_id: str
    origin_ns: int
    started_ns: int
    ended_ns: int | None


@dataclass(frozen=True, slots=True)
class DeviceInfo:
    manufacturer: str | None
    model: str | None
    os_name: str | None
    os_version: str | None
    api_level: int | None


@dataclass(frozen=True, slots=True)
class ApplicationInfo:
    package_name: str | None
    version_name: str | None
    version_code: int | None
    source_version: str | None


@dataclass(frozen=True, slots=True)
class SensorDescriptor:
    sensor: Sensor
    name: str | None
    vendor: str | None
    available: bool
    requested_hz: float | None
    measured_hz: float | None


@dataclass(frozen=True, slots=True)
class SourceConfiguration:
    location_permission: LocationPermissionState
    location_service_enabled: bool | None
    gps_provider_enabled: bool | None
    network_provider_enabled: bool | None
    satellite_status_enabled: bool | None
    foreground_only: bool


@dataclass(frozen=True, slots=True)
class CalibrationInfo:
    state: CalibrationApplication
    calibration_id: str | None


@dataclass(frozen=True, slots=True)
class ChannelCount:
    channel: str
    count: int


@dataclass(frozen=True, slots=True)
class RecordingMetadata:
    recording_id: str
    acquisition_session_id: str
    source: Source
    start_state: RecordingStartState
    end_state: RecordingEndState | None
    completion_state: CompletionState
    recovery_state: RecoveryState
    created_utc_ms: int | None
    started_utc_ms: int | None
    ended_utc_ms: int | None
    clock: ClockIdentity
    device: DeviceInfo
    application: ApplicationInfo
    sensors: tuple[SensorDescriptor, ...]
    source_configuration: SourceConfiguration
    calibration: CalibrationInfo
    record_count: int | None
    channel_counts: tuple[ChannelCount, ...] | None
    recording_contract_version: str = RECORDING_CONTRACT_VERSION
    measurement_contract_version: str = MEASUREMENT_CONTRACT_VERSION


def replay_source(source: Source) -> Source:
    """Map an original recorded source to an explicit replay source."""
    if source is Source.REAL:
        return Source.REPLAY_REAL
    if source is Source.SIMULATION:
        return Source.REPLAY_SIMULATION
    raise ValueError("Only original real/simulation sources may be recorded")
