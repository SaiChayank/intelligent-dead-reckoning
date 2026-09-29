"""Read-only acceptance report for one real recording.

Two sources funnel into the same validator, so a session cannot pass on this machine and
fail on the phone:

    python tools/validate_phone_recording.py <serial> <recording-id>
    python tools/validate_phone_recording.py --local path/to/rec-1

The device form streams the app's private JSONL through `adb exec-out run-as cat`; it
requires a debuggable build and never writes to, repairs or deletes anything. The report
carries counts, per-sensor spans and rates, receipt delays, diagnostics and GNSS field
coverage. Positions are never printed.

Exit status: 0 accepted, 2 usage (argparse), 3 the session violates the contract, 4 the
device could not be read.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Iterable

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from contracts.v1.models import Record  # noqa: E402
from contracts.recording.v1.codec import decode_metadata  # noqa: E402
from contracts.recording.v1.models import RecordingMetadata  # noqa: E402
from contracts.recording.v1.session import (  # noqa: E402
    MEASUREMENTS_NAME,
    METADATA_NAME,
    SessionError,
    open_session,
    open_stream,
)

APP_ID = "com.intelligentdeadreckoning.app"
REMOTE_ROOT = "no_backup/recordings"
ID_CHARS = "0123456789abcdef-"
GNSS_OPTIONAL_FIELDS = ("speed_m_s", "bearing_deg", "altitude_m", "horizontal_accuracy_m")
#: The reorder window acquisition applies (LATE_NS in Acquisition.kt, stated in
#: contracts/v1/README.md): a measurement received later than this is reported as
#: LATE_MEASUREMENT. Counting the rows that actually exceed it is what makes that
#: proposed policy checkable against real recordings rather than assumed.
REORDER_WINDOW_NS = 100_000_000


def remote_prefix(adb: str, serial: str) -> list[str]:
    return [adb, "-s", serial, "exec-out", "run-as", APP_ID, "cat"]


def read_device_metadata(prefix: list[str], recording_id: str) -> RecordingMetadata:
    return decode_metadata(
        subprocess.check_output(prefix + [f"{REMOTE_ROOT}/{recording_id}/metadata.json"])
    )


def summarize(records: Iterable[Record], metadata: RecordingMetadata, **extra: object) -> dict:
    """Fold one validated stream into a printable report. Pure; prints nothing."""
    counts: Counter[str] = Counter()
    diagnostics: Counter[str] = Counter()
    modes: Counter[str] = Counter()
    nulls: Counter[str] = Counter()
    channels: dict[str, dict] = {}
    for record in records:
        data = record.event.data
        kind = data.TYPE
        counts[kind] += 1
        if kind == "diagnostic":
            diagnostics[data.code] += 1
            continue
        if kind == "navigation":
            modes[data.initialization_mode.value] += 1
            continue
        if kind == "imu":
            key = data.sensor.value
        elif kind == "gnss":
            key = f"gnss_{data.provider}"
            for field in GNSS_OPTIONAL_FIELDS:
                if getattr(data, field) is None:
                    nulls[field] += 1
        else:
            continue
        t_ns = record.event.t_ns
        stats = channels.setdefault(
            key,
            {"count": 0, "min_ns": t_ns, "max_ns": t_ns,
             "max_receipt_delay_ms": 0.0, "late_count": 0},
        )
        stats["count"] += 1
        stats["min_ns"] = min(stats["min_ns"], t_ns)
        stats["max_ns"] = max(stats["max_ns"], t_ns)
        delay_ns = record.event.received_ns - t_ns
        if delay_ns > REORDER_WINDOW_NS:
            stats["late_count"] += 1
        stats["max_receipt_delay_ms"] = max(stats["max_receipt_delay_ms"], delay_ns / 1e6)
    for stats in channels.values():
        span_s = (stats.pop("max_ns") - stats.pop("min_ns")) / 1e9
        stats["span_s"] = span_s
        stats["rate_hz"] = round((stats["count"] - 1) / span_s, 3) if span_s else None
    clock = metadata.clock
    report = {
        "metadata": {
            "recording_id": metadata.recording_id,
            "session": metadata.acquisition_session_id,
            "source": metadata.source.value,
            "completion": metadata.completion_state.value,
            "recovery": metadata.recovery_state.value,
            "record_count": metadata.record_count,
            "duration_s": None if clock.ended_ns is None else (clock.ended_ns - clock.started_ns) / 1e9,
            "sensor_descriptors": len(metadata.sensors),
            "calibration": metadata.calibration.state.value,
        },
        "counts": dict(sorted(counts.items())),
        "channels": channels,
        "diagnostics": dict(sorted(diagnostics.items())),
        "navigation_modes": dict(sorted(modes.items())),
        "gnss_null_counts": dict(sorted(nulls.items())),
    }
    report.update(extra)
    return report


def device_report(adb: str, serial: str, recording_id: str) -> dict:
    if not recording_id or any(c not in ID_CHARS for c in recording_id):
        raise ValueError("recording ID must be a lowercase hex/UUID style directory name")
    prefix = remote_prefix(adb, serial)
    metadata = read_device_metadata(prefix, recording_id)
    process = subprocess.Popen(prefix + [f"{REMOTE_ROOT}/{recording_id}/measurements.jsonl"],
                               stdout=subprocess.PIPE)
    assert process.stdout is not None
    try:
        content = open_stream(process.stdout, metadata)
    except SessionError:
        process.stdout.close()
        process.wait()
        raise
    try:
        report = summarize(content.records, metadata, bytes=None)
    finally:
        content.close()
        process.stdout.close()
    if process.wait() != 0:
        raise OSError("the device stream ended abnormally")
    return report


def local_report(directory: Path) -> dict:
    content = open_session(directory)
    try:
        total = sum(
            (Path(directory) / name).stat().st_size for name in (METADATA_NAME, MEASUREMENTS_NAME)
        )
        return summarize(content.records, content.metadata, bytes=total)
    finally:
        content.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate one recording, read-only.")
    parser.add_argument("serial", nargs="?", help="adb device serial")
    parser.add_argument("recording_id", nargs="?", help="session directory name on the device")
    parser.add_argument("--local", type=Path, help="validate a session directory on this machine")
    parser.add_argument("--adb", default="adb", help="adb executable (default: adb)")
    args = parser.parse_args(argv)
    if (args.serial is None) == (args.local is None):
        parser.error("pass either a device serial and recording ID, or --local")
    try:
        if args.local is not None:
            report = local_report(args.local)
        else:
            report = device_report(args.adb, args.serial, args.recording_id)
    except SessionError as exc:
        print(json.dumps({"rejected": exc.code, "line": exc.line, "detail": exc.message}), flush=True)
        return 3
    except ValueError as exc:
        print(json.dumps({"usage_error": str(exc)}), flush=True)
        return 3
    except (OSError, subprocess.SubprocessError) as exc:
        print(json.dumps({"device_error": exc.__class__.__name__}), flush=True)
        return 4
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
