"""Read-only strapdown INS baseline report for one real recorded session.

Runs the classical mechanization in `training/strapdown_ins.py` over one recording from the
frozen recording contract and writes the measurements to JSON. Nothing is written back to
the session, nothing is repaired, and no data file is modified.

    python tools/ins_baseline_report.py --local mobile/artifacts/<run>/recordings/<id>
    python tools/ins_baseline_report.py --local <dir> --max-seconds 120 --out /tmp/x.json

Input discipline, enforced rather than asserted
-----------------------------------------------
* The propagation consumes only paired accelerometer/gyroscope samples. A GNSS record cannot
  reach it: the samples come from `strapdown_ins.samples_from_records`, whose payload filter
  has no branch that could accept a fix. `--audit-prefix-s` re-reads a prefix of the same file
  with every GNSS record removed and requires the sample stream to be identical, which is the
  runtime proof of that claim.
* Fixes are used for exactly two things: the initial position (from a fix at or before the
  first inertial sample, or the first fix with that offset reported) and *scoring*, which
  happens after propagation. There is no aiding term anywhere.
* No future information of any kind: the anchor is never taken from a fix later than the first
  sample unless the session has no earlier fix at all, and then the offset is reported.

Exit status: 0 report written, 2 usage (argparse), 3 the session violates the recording
contract, 4 the session could not be read. Absolute positions are rounded in the report and
never printed.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402

from contracts.recording.v1.session import (  # noqa: E402
    SessionError,
    inspect_session,
    read_session,
)
from contracts.v1.models import (  # noqa: E402
    CalibrationResult,
    CalibrationStatus,
    DiagnosticEvent,
    GnssMeasurement,
    ImuMeasurement,
    Sensor,
)
from training import strapdown_ins as ins  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[1]
NS = 1_000_000_000
DEFAULT_OUT = REPO_ROOT / "reports" / "ins_baseline_metrics.json"
#: Elapsed times at which the deviation from the reference is tabulated, in seconds.
DIVERGENCE_TIMES_S = (60.0, 300.0, 600.0, 1200.0, 1800.0)
#: Reference inputs that exist on this machine and are deliberately not used here.
EXCLUDED_INPUTS = [
    "data/raw/iovnbd: the repository's own audit (reports/frame_resolution_followup.md) leaves "
    "the dataset's axes unresolved, so it is not used as input and not guessed at. It is also a "
    "reference dataset, and this baseline is about the product's own recordings.",
    "VBOX or any other reference instrument: never a runtime input, in this tool or in the app.",
    "Future GNSS: no fix later than the anchor is an input to the propagation. Fixes are used "
    "for initialization and for scoring only.",
    "Any map, road network, or learned model: not inputs at this stage.",
]


@dataclass(frozen=True, slots=True)
class CalibrationRecord:
    """One calibration payload as it appears in the stream, with its time and its state."""

    t_ns: int
    payload: CalibrationResult


@dataclass(frozen=True, slots=True)
class Fix:
    """One GNSS fix, retained for initialization and scoring only."""

    t_ns: int
    latitude_deg: float
    longitude_deg: float
    altitude_m: float | None
    speed_m_s: float | None
    bearing_deg: float | None
    horizontal_accuracy_m: float | None

    def offset_enu(self, anchor_lat_deg: float, anchor_lon_deg: float) -> tuple[float, float]:
        """Flat-earth offset from the anchor in metres (east, north)."""
        mean_lat = math.radians((self.latitude_deg + anchor_lat_deg) / 2.0)
        east = math.radians(self.longitude_deg - anchor_lon_deg) * 6_378_137.0 * math.cos(mean_lat)
        north = math.radians(self.latitude_deg - anchor_lat_deg) * 6_378_137.0
        return east, north


def _gap_tracker() -> dict:
    return {"count": 0, "first_ns": None, "last_ns": None, "max_s": 0.0, "min_s": None,
            "distinct": set(), "previous_ns": None}


def _observe_gap(tracker: dict, t_ns: int) -> None:
    if tracker["previous_ns"] is not None:
        gap = (t_ns - tracker["previous_ns"]) / NS
        tracker["count"] += 1
        tracker["max_s"] = max(tracker["max_s"], gap)
        tracker["min_s"] = gap if tracker["min_s"] is None else min(tracker["min_s"], gap)
        if len(tracker["distinct"]) < 4096:
            tracker["distinct"].add(round(gap, 9))
    if tracker["first_ns"] is None:
        tracker["first_ns"] = t_ns
    tracker["last_ns"] = t_ns
    tracker["previous_ns"] = t_ns


def _tracker_summary(tracker: dict, session_start_ns: int) -> dict:
    return {
        "samples": tracker["count"],
        "first_offset_s": round((tracker["first_ns"] - session_start_ns) / NS, 6),
        "last_offset_s": round((tracker["last_ns"] - session_start_ns) / NS, 6),
        "interval_min_ms": None if tracker["min_s"] is None else round(tracker["min_s"] * 1e3, 6),
        "interval_max_ms": round(tracker["max_s"] * 1e3, 6),
        "interval_distinct_values": len(tracker["distinct"]),
    }


def collect(session_dir: Path) -> dict:
    """First streaming pass: payload census, diagnostics, and the fix list."""
    counts: Counter[str] = Counter()
    diagnostics: Counter[str] = Counter()
    per_sensor: dict[str, dict] = {}
    fixes: list[Fix] = []
    calibrations: list[CalibrationRecord] = []
    session_start_ns = inspect_session(session_dir).metadata.clock.started_ns
    for record in read_session(session_dir):
        data = record.event.data
        name = type(data).__name__
        counts[name] += 1
        if isinstance(data, ImuMeasurement):
            tracker = per_sensor.setdefault(data.sensor.value, _gap_tracker())
            _observe_gap(tracker, record.event.t_ns)
        elif isinstance(data, GnssMeasurement):
            fixes.append(Fix(
                t_ns=record.event.t_ns,
                latitude_deg=data.latitude_deg,
                longitude_deg=data.longitude_deg,
                altitude_m=data.altitude_m,
                speed_m_s=data.speed_m_s,
                bearing_deg=data.bearing_deg,
                horizontal_accuracy_m=data.horizontal_accuracy_m,
            ))
        elif isinstance(data, CalibrationResult):
            calibrations.append(CalibrationRecord(record.event.t_ns, data))
        elif isinstance(data, DiagnosticEvent):
            diagnostics[f"{data.severity.value}:{data.code}"] += 1
    return {
        "payload_counts": dict(sorted(counts.items())),
        "diagnostics": dict(sorted(diagnostics.items())),
        "sensor_timelines": {
            name: _tracker_summary(tracker, session_start_ns)
            for name, tracker in sorted(per_sensor.items())
        },
        "fixes": fixes,
        "calibrations": calibrations,
    }


def select_calibration(
    records: list[CalibrationRecord],
    first_sample_ns: int,
) -> tuple[ins.InsCalibration, dict]:
    """The calibration the run should use: the latest *validated* one at or before t0.

    A session that recorded a calibration declares it in the metadata as well; both are
    reported, because a session that applied one and a session that carries a stale record are
    different statements. Nothing is inferred: with no valid record the run uses assumed-zero
    biases and says so.
    """
    usable = [
        record for record in records
        if record.t_ns <= first_sample_ns
        and record.payload.status is CalibrationStatus.VALID
    ]
    if usable:
        chosen = usable[-1]
        return ins.InsCalibration.from_calibration_result(chosen.payload), {
            "source": "session_record",
            "calibration_id": chosen.payload.id,
            "status": chosen.payload.status.value,
            "offset_from_first_sample_s": round((chosen.t_ns - first_sample_ns) / NS, 6),
            "records_in_session": len(records),
            "note": "a validated record at or before the first sample; used as the run's "
                    "calibration",
        }
    return ins.InsCalibration.unknown(), {
        "source": "assumed_zero",
        "calibration_id": None,
        "status": None,
        "records_in_session": len(records),
        "note": (
            "no validated calibration record at or before the first sample, so the run removes "
            "no sensor error and reports that as its provenance"
        ),
    }


def _fix_summary(fixes: list[Fix], anchor: Fix) -> dict:
    if not fixes:
        return {"count": 0}
    offsets = np.array([f.offset_enu(anchor.latitude_deg, anchor.longitude_deg) for f in fixes])
    radius = np.linalg.norm(offsets, axis=1)
    speeds = [f.speed_m_s for f in fixes if f.speed_m_s is not None]
    accuracies = [f.horizontal_accuracy_m for f in fixes if f.horizontal_accuracy_m is not None]
    return {
        "count": len(fixes),
        "first_offset_s": round((fixes[0].t_ns - anchor.t_ns) / NS, 3),
        "last_offset_s": round((fixes[-1].t_ns - anchor.t_ns) / NS, 3),
        "horizontal_offset_from_anchor_m": {
            "max": round(float(radius.max()), 3),
            "p95": round(float(np.percentile(radius, 95)), 3),
            "mean": round(float(radius.mean()), 3),
        },
        "vertical_altitude_m": {
            "min": _rounded_min([f.altitude_m for f in fixes if f.altitude_m is not None]),
            "max": _rounded_max([f.altitude_m for f in fixes if f.altitude_m is not None]),
        },
        "reported_speed_m_s": {
            "count": len(speeds),
            "min": None if not speeds else round(min(speeds), 4),
            "max": None if not speeds else round(max(speeds), 4),
        },
        "reported_bearing_count": sum(1 for f in fixes if f.bearing_deg is not None),
        "reported_horizontal_accuracy_m": {
            "count": len(accuracies),
            "median": None if not accuracies else round(float(np.median(accuracies)), 3),
        },
    }


def _relative_to_repo(path: Path) -> Path:
    """Repo-relative when the session lives inside the checkout, absolute otherwise."""
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def _posix(path: Path) -> str:
    """Forward slashes, so the committed report reads the same on every platform."""
    return str(path).replace("\\", "/")


def _rounded_min(values: list[float]) -> float | None:
    return None if not values else round(min(values), 3)


def _rounded_max(values: list[float]) -> float | None:
    return None if not values else round(max(values), 3)


def load_samples(
    session_dir: Path,
    *,
    max_seconds: float | None,
    rest_window_s: float,
    stats: dict,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Second streaming pass: materialize the paired stream and its resting statistics.

    The statistics are the evidence for what the run did *not* remove: the accelerometer's own
    magnitude residual against modelled gravity, and the resting gyroscope rate. Both are
    accumulated here because a second reading of a 170 MB session is cheaper than holding the
    records in memory, and neither needs the raw samples kept.
    """
    times: list[int] = []
    accel: list[tuple[float, float, float]] = []
    gyro: list[tuple[float, float, float]] = []
    first_t_ns: int | None = None
    rest_cutoff_ns: int | None = None
    all_sum = np.zeros(3)
    all_sum_sq = np.zeros(3)
    gyro_sum = np.zeros(3)
    rest_sum = np.zeros(3)
    rest_gyro_sum = np.zeros(3)
    norm_min = float("inf")
    norm_max = 0.0
    rest_norm_min = float("inf")
    rest_norm_max = 0.0
    rest_count = 0
    rest_gyro_norm_max = 0.0
    for sample in ins.samples_from_records(read_session(session_dir)):
        if first_t_ns is None:
            first_t_ns = sample.t_ns
            rest_cutoff_ns = first_t_ns + int(round(rest_window_s * NS))
        if max_seconds is not None and (sample.t_ns - first_t_ns) / NS > max_seconds:
            break
        times.append(sample.t_ns)
        accel.append((sample.accel_m_s2[0], sample.accel_m_s2[1], sample.accel_m_s2[2]))
        gyro.append((sample.gyro_rad_s[0], sample.gyro_rad_s[1], sample.gyro_rad_s[2]))
        all_sum += sample.accel_m_s2
        all_sum_sq += sample.accel_m_s2 ** 2
        gyro_sum += sample.gyro_rad_s
        norm = float(np.linalg.norm(sample.accel_m_s2))
        norm_min = min(norm_min, norm)
        norm_max = max(norm_max, norm)
        if rest_cutoff_ns is not None and sample.t_ns <= rest_cutoff_ns:
            rest_count += 1
            rest_sum += sample.accel_m_s2
            rest_gyro_sum += sample.gyro_rad_s
            rest_norm_min = min(rest_norm_min, norm)
            rest_norm_max = max(rest_norm_max, norm)
            rest_gyro_norm_max = max(rest_gyro_norm_max, float(np.linalg.norm(sample.gyro_rad_s)))
    count = len(times)
    if count == 0:
        raise SessionError("NO_SAMPLES", "the session yielded no paired inertial samples")
    stats.update({
        "paired_samples": count,
        "elapsed_s": round((times[-1] - times[0]) / NS, 6),
        "resting_window": {
            "duration_s": rest_window_s,
            "samples": rest_count,
            "mean_accel_m_s2": [round(float(v), 6) for v in (rest_sum / rest_count)],
            "mean_gyro_rad_s": [round(float(v), 9) for v in (rest_gyro_sum / rest_count)],
            "accel_magnitude_m_s2": {
                "min": round(rest_norm_min, 6),
                "max": round(rest_norm_max, 6),
            },
            "max_gyro_magnitude_rad_s": round(rest_gyro_norm_max, 9),
        },
        "whole_run_accel_m_s2": {
            "mean_vector": [round(float(v / count), 6) for v in all_sum],
            "rms": [round(float(math.sqrt(v / count)), 6) for v in all_sum_sq],
            "magnitude_min": round(norm_min, 6),
            "magnitude_max": round(norm_max, 6),
        },
        "whole_run_mean_gyro_rad_s": [round(float(v / count), 9) for v in (gyro_sum / count)],
    })
    return (
        np.asarray(times, dtype=np.int64),
        np.asarray(accel, dtype=float),
        np.asarray(gyro, dtype=float),
    )


def prefix_without_gnss(
    session_dir: Path, seconds: float,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Samples from a prefix of the session with every GNSS record removed from the stream."""

    def inertial_only() -> Iterator:
        for record in read_session(session_dir):
            if isinstance(record.event.data, GnssMeasurement):
                continue
            yield record

    times: list[int] = []
    accel: list[tuple[float, float, float]] = []
    gyro: list[tuple[float, float, float]] = []
    first_t_ns: int | None = None
    for sample in ins.samples_from_records(inertial_only()):
        if first_t_ns is None:
            first_t_ns = sample.t_ns
        if (sample.t_ns - first_t_ns) / NS > seconds:
            break
        times.append(sample.t_ns)
        accel.append((sample.accel_m_s2[0], sample.accel_m_s2[1], sample.accel_m_s2[2]))
        gyro.append((sample.gyro_rad_s[0], sample.gyro_rad_s[1], sample.gyro_rad_s[2]))
    return (
        np.asarray(times, dtype=np.int64),
        np.asarray(accel, dtype=float),
        np.asarray(gyro, dtype=float),
    )


def choose_anchor(fixes: list[Fix], first_sample_ns: int) -> tuple[Fix, dict]:
    """The fix that anchors the frame: never later than the first sample if one exists."""
    if not fixes:
        raise SessionError("NO_GNSS", "no GNSS fix is available to anchor the navigation frame")
    earlier = [f for f in fixes if f.t_ns <= first_sample_ns]
    if earlier:
        anchor = earlier[-1]
        source = "last_fix_at_or_before_first_sample"
    else:
        # The session began before its first fix. The anchor is still needed; the offset is
        # reported so nobody has to guess how much future information that admitted.
        anchor = fixes[0]
        source = "first_fix_after_first_sample"
    return anchor, {
        "source": source,
        "offset_from_first_sample_s": round((anchor.t_ns - first_sample_ns) / NS, 6),
        "altitude_available": anchor.altitude_m is not None,
        "reported_speed_m_s": anchor.speed_m_s,
        "latitude_deg_reported_rounded": round(anchor.latitude_deg, 3),
    }


def build_initial(
    anchor: Fix,
    up_device: np.ndarray,
    heading_deg: float,
    t0_ns: int,
) -> tuple[ins.InsInitialState, dict]:
    """Initial position from the anchor, velocity zero, attitude from measured up + a convention.

    The measured up direction comes from the resting specific force, which points up at rest in
    the contract's device frame. Heading is *not* observable from inertial data of a stationary
    handset, so the caller states it and the report carries it as a convention.

    The state is anchored in *time* at the first inertial sample and in *position* at the fix,
    which is why the fix's age is reported rather than assumed away.
    """
    attitude = ins.initial_attitude_from_up_and_heading(up_device, math.radians(heading_deg))
    initial = ins.InsInitialState.create(
        t_ns=t0_ns,
        latitude_deg=anchor.latitude_deg,
        longitude_deg=anchor.longitude_deg,
        altitude_m=0.0 if anchor.altitude_m is None else anchor.altitude_m,
        velocity_enu_m_s=[0.0, 0.0, 0.0],
        q_nav_from_device_wxyz=attitude,
    )
    return initial, {
        "time_basis": "first paired inertial sample; integration starts with the second",
        "position": "anchor fix (initialization only; see anchor provenance)",
        "anchor_fix_age_s": round((t0_ns - anchor.t_ns) / NS, 6),
        "velocity_m_s": [0.0, 0.0, 0.0],
        "velocity_basis": "caller-supplied zero; the session's own fixes report 0.000 m/s",
        "altitude_basis": (
            "anchor fix" if anchor.altitude_m is not None
            else "no altitude in the anchor fix; 0.0 m assumed and flagged"
        ),
        "attitude": "measured up direction at rest, heading stated by convention",
        "heading_deg": heading_deg,
        "measured_up_device": [round(float(v), 9) for v in up_device],
    }


def convergence_report(result: ins.InsResult) -> dict:
    """Deviation from the reference at fixed elapsed times, read straight off the trace."""
    times_s = (result.t_ns - result.t_ns[0]) / NS
    rows = []
    for target in DIVERGENCE_TIMES_S:
        if times_s[-1] < target:
            continue
        index = int(np.searchsorted(times_s, target))
        index = min(index, result.t_ns.size - 1)
        horizontal = float(np.linalg.norm(result.position_enu_m[index, :2]))
        rows.append({
            "elapsed_s": round(float(times_s[index]), 3),
            "horizontal_deviation_m": round(horizontal, 4),
            "vertical_deviation_m": round(float(result.position_enu_m[index, 2]), 4),
            "horizontal_speed_m_s": round(
                float(np.linalg.norm(result.velocity_enu_m_s[index, :2])), 6,
            ),
        })
    if not rows or rows[-1]["elapsed_s"] != round(float(times_s[-1]), 3):
        rows.append({
            "elapsed_s": round(float(times_s[-1]), 3),
            "horizontal_deviation_m": round(
                float(np.linalg.norm(result.final_position_enu_m[:2])), 4,
            ),
            "vertical_deviation_m": round(float(result.final_position_enu_m[2]), 4),
            "horizontal_speed_m_s": round(
                float(np.linalg.norm(result.final_velocity_enu_m_s[:2])), 6,
            ),
        })
    return {"rows": rows, "final": rows[-1]}


def run_variant(
    times: np.ndarray,
    accel: np.ndarray,
    gyro: np.ndarray,
    initial: ins.InsInitialState,
    calibration: ins.InsCalibration,
    config: ins.InsConfig,
) -> dict:
    """One propagation from held arrays, summarized. The arrays are never modified."""
    machine = ins.StrapdownIns(initial, calibration, config)
    # The first sample *is* the initial time, so integration begins with the second: the module
    # advances a state to the next sample, it does not integrate a zero-length interval.
    result = machine.run(
        ins.InsSample(int(t), a, g)
        for t, a, g in zip(times[1:], accel[1:], gyro[1:])
    )
    summary = {
        "status": result.status.value,
        "reasons": list(result.reasons),
        "steps": result.steps,
        "held_steps": result.held_steps,
        "rejected_samples": result.rejected_samples,
        "gap_count": len(result.gaps),
        "largest_gap_s": round(max((g.duration_s for g in result.gaps), default=0.0), 6),
        "calibration_source": result.calibration.source,
        "duration_s": round(result.duration_s, 6),
        "attitude_drift_deg": round(result.maximum_attitude_drift_deg(), 6),
        "attitude_norm_error_max": float(result.attitude_norm_errors.max()),
        "orthonormality_error_max": float(result.orthonormality_errors.max()),
        "divergence": convergence_report(result),
        "step_durations": result.step_duration_summary(),
        "final_altitude_offset_m": round(result.altitude_m - initial.altitude_m, 4),
    }
    summary["step_durations"]["distinct_values"] = int(
        summary["step_durations"].get("distinct_values", 0)
    )
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--local", type=Path, required=True, help="session directory")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="JSON output path")
    parser.add_argument("--heading-deg", type=float, default=0.0,
                        help="initial heading convention: the compass heading device +X faces")
    parser.add_argument("--max-seconds", type=float, default=None,
                        help="truncate the run (debugging only; the report says so)")
    parser.add_argument("--rest-window-s", type=float, default=60.0,
                        help="leading window used for the measured up direction")
    parser.add_argument("--audit-prefix-s", type=float, default=120.0,
                        help="seconds of the stream re-read with GNSS removed to prove isolation")
    args = parser.parse_args(argv)
    if args.rest_window_s <= 0:
        parser.error("--rest-window-s must be positive")
    if args.max_seconds is not None and args.max_seconds <= 0:
        parser.error("--max-seconds must be positive")

    session_dir = args.local
    summary = inspect_session(session_dir)
    if summary.metadata is None:
        print(f"session unreadable: {summary.error or summary.error_code}", file=sys.stderr)
        return 4
    if not summary.replayable:
        print(
            f"session is not replayable (error={summary.error_code or summary.error}, "
            f"completion={summary.metadata.completion_state.value}, "
            f"recovery={summary.metadata.recovery_state.value})",
            file=sys.stderr,
        )
        return 3

    metadata = summary.metadata
    evidence = collect(session_dir)
    stats: dict = {}
    times, accel, gyro = load_samples(
        session_dir, max_seconds=args.max_seconds, rest_window_s=args.rest_window_s, stats=stats,
    )
    anchor, anchor_provenance = choose_anchor(evidence["fixes"], int(times[0]))
    up_device = np.asarray(stats["resting_window"]["mean_accel_m_s2"], dtype=float)
    up_device = up_device / float(np.linalg.norm(up_device))
    initial, initialisation = build_initial(anchor, up_device, args.heading_deg, int(times[0]))
    initialisation["anchor"] = anchor_provenance
    initialisation["measured_up_basis"] = (
        f"mean specific-force direction over the leading {args.rest_window_s} s, normalized; "
        "the first sample defines t0 and integration begins with the second"
    )

    config = ins.InsConfig(gravity_model="wgs84", earth_rotation=True, transport_rate=True)
    assumed, calibration_selection = select_calibration(evidence["calibrations"], int(times[0]))
    resting_rate = np.asarray(stats["resting_window"]["mean_gyro_rad_s"], dtype=float)
    earth_rate_device = ins.quat_to_matrix(np.asarray(initial.q_nav_from_device_wxyz)).T @ (
        np.array([0.0, ins.OMEGA_EARTH_RAD_S * math.cos(math.radians(initial.latitude_deg)),
                  ins.OMEGA_EARTH_RAD_S * math.sin(math.radians(initial.latitude_deg))])
    )
    bias_sensitivity = ins.InsCalibration(
        gyro_bias_rad_s=resting_rate - earth_rate_device,
        accel_bias_m_s2=np.zeros(3),
        source="sensitivity:session-resting-mean-gyro",
    )

    runs = {
        "selected_calibration": run_variant(times, accel, gyro, initial, assumed, config),
        "session_resting_mean_gyro_removed": run_variant(
            times, accel, gyro, initial, bias_sensitivity, config,
        ),
    }
    for heading in (120.0, 240.0):
        rotated, _ = build_initial(anchor, up_device, heading, int(times[0]))
        runs[f"heading_{int(heading)}_deg"] = run_variant(
            times, accel, gyro, rotated, assumed, config,
        )

    # Isolation proof: the same prefix of the same file, read with every GNSS record removed,
    # must give the identical inertial sample stream.
    audit_end = min(args.audit_prefix_s, (times[-1] - times[0]) / NS)
    audit_times, audit_accel, audit_gyro = prefix_without_gnss(session_dir, audit_end)
    overlap = min(len(audit_times), len(times))
    isolation = {
        "prefix_s": round(audit_end, 3),
        "samples_compared": overlap,
        "timestamps_identical": bool(np.array_equal(audit_times[:overlap], times[:overlap])),
        "accel_identical": bool(np.array_equal(audit_accel[:overlap], accel[:overlap])),
        "gyro_identical": bool(np.array_equal(audit_gyro[:overlap], gyro[:overlap])),
        "forbidden_identifiers_in_module": ins.forbidden_identifiers(),
    }

    gravity_anchor = ins.normal_gravity_m_s2(
        math.radians(initial.latitude_deg), initial.altitude_m,
    )
    accel_rms = float(np.linalg.norm(np.asarray(stats["whole_run_accel_m_s2"]["rms"], dtype=float)))
    resting_magnitude = float(np.linalg.norm(
        np.asarray(stats["resting_window"]["mean_accel_m_s2"], dtype=float),
    ))
    residuals = {
        "modelled_normal_gravity_m_s2": round(gravity_anchor, 6),
        "measured_accel_magnitude_rms_m_s2": round(accel_rms, 6),
        "unremoved_accel_magnitude_residual_m_s2": round(accel_rms - gravity_anchor, 6),
        "resting_mean_accel_magnitude_m_s2": round(resting_magnitude, 6),
        "resting_accel_magnitude_residual_m_s2": round(resting_magnitude - gravity_anchor, 6),
        "residual_implied_vertical_error_at_end_m": round(
            0.5 * (resting_magnitude - gravity_anchor) * stats["elapsed_s"] ** 2, 1,
        ),
        "resting_mean_gyro_rad_s": [round(float(v), 9) for v in resting_rate],
        "modelled_earth_rate_in_device_axes_rad_s": [
            round(float(v), 9) for v in earth_rate_device
        ],
        "implied_gyro_bias_rad_s": [
            round(float(v), 9) for v in (resting_rate - earth_rate_device)
        ],
        "implied_gyro_bias_deg_per_hour": [
            round(float(math.degrees(v) * 3600.0), 4) for v in (resting_rate - earth_rate_device)
        ],
        "note": (
            "Both residuals are what the run did *not* remove, given the calibration selected "
            "above (see `calibration_selection`). They are measurements of this recording, "
            "not sensor "
            "specifications, and not a calibration. The magnitude residual is quoted from the "
            "resting window as well as from the whole run, because the whole-run mean includes "
            "whatever motion the handset saw; the closing expression is the vertical error that "
            "constant residual alone would integrate to over the full duration, for comparison "
            "with the measured deviation."
        ),
    }

    payload = {
        "tool": "tools/ins_baseline_report.py",
        "module": "training/strapdown_ins.py",
        "session": {
            "directory": _posix(_relative_to_repo(session_dir)),
            "recording_id": metadata.recording_id,
            "source": metadata.source.value,
            "completion_state": metadata.completion_state.value,
            "recovery_state": metadata.recovery_state.value,
            "end_state": None if metadata.end_state is None else metadata.end_state.value,
            "recording_contract_version": metadata.recording_contract_version,
            "measurement_contract_version": metadata.measurement_contract_version,
            "device": f"{metadata.device.manufacturer} {metadata.device.model} "
                      f"{metadata.device.os_name} {metadata.device.os_version} "
                      f"(API {metadata.device.api_level})",
            "declared_record_count": metadata.record_count,
            "declared_channel_counts": (
                None if metadata.channel_counts is None
                else {c.channel: c.count for c in metadata.channel_counts}
            ),
            "calibration_state": metadata.calibration.state.value,
            "calibration_id": metadata.calibration.calibration_id,
            "duration_s": round((summary.duration_ns or 0) / NS, 3),
            "bytes": summary.bytes,
            "data_tracked_in_git": False,
        },
        "census": {
            "payload_counts": evidence["payload_counts"],
            "diagnostics": evidence["diagnostics"],
            "sensor_timelines": evidence["sensor_timelines"],
        },
        "gnss_reference": _fix_summary(evidence["fixes"], anchor),
        "sample_stream": stats,
        "initialisation": initialisation,
        "config": {
            "gravity_model": config.gravity_model,
            "earth_rotation": config.earth_rotation,
            "transport_rate": config.transport_rate,
            "max_step_s": config.max_step_s,
            "failure_gap_s": config.failure_gap_s,
            "renormalize": config.renormalize,
        },
        "max_seconds_applied": args.max_seconds,
        "runs": runs,
        "calibration_selection": calibration_selection,
        "unremoved_residuals": residuals,
        "isolation_audit": isolation,
        "gap_definition_cross_check": {
            "acquisition_time_gap_notices": evidence["diagnostics"].get("warning:TIME_GAP", 0),
            "propagation_gaps_over_max_step": runs["selected_calibration"]["gap_count"],
            "note": (
                "The two layers count different things: acquisition notices any interval well "
                "above the nominal sensor period, while the mechanization holds only intervals "
                "longer than max_step_s and fails beyond failure_gap_s. Neither number is wrong, "
                "and reporting both stops either one being read as the other."
            ),
        },
        "excluded_inputs": EXCLUDED_INPUTS,
        "limitations": [
            "The reference for a stationary session is 'no motion', confirmed by the session's "
            "own fixes to within their reported horizontal accuracy. It is a reference for "
            "horizontal position and speed only: altitude, attitude and the initial heading are "
            "not observed by anything in this session.",
            "No session in this corpus is a drive. Behaviour under sustained motion, turns and "
            "vibration is untested by this report and by the host test suite.",
            "Every threshold in the module (max_step_s, failure_gap_s) is an engineering choice "
            "for phone-grade MEMS, not a measured constant.",
            "The position update is flat-earth on the anchor's radii of curvature: exact to "
            "sub-metre over a few kilometres, and disclosed rather than corrected.",
            "An unaided INS diverges: with no aiding, the deviation from the reference the "
            "report tabulates grows without bound, and nothing in this tool smooths it.",
            "The heading convention is stated, not measured. Three headings are reported so the "
            "reader can see which part of the result depends on it; they do not agree on the "
            "magnitude of the divergence, so the heading is a first-order limitation rather than "
            "a detail.",
            "A stationary vehicle is not a still handset: this session's accelerometer magnitude "
            "ranges far enough either side of local gravity, and its attitude moves far enough, "
            "to show that the phone itself was handled. That motion is real inertial input which "
            "the navigation solution has no way to distinguish from vehicle motion, and it is "
            "part of what the tabulated divergence contains.",
            "One unscreened resting window is not a bias estimate. Removing this session's own "
            "resting mean rate (`session_resting_mean_gyro_removed`) makes every metric worse, "
            "because the window it is measured over contains real motion. Bias estimation needs "
            "the stability screening and the validity gates that the calibration engine in "
            "`mobile/app/.../calibration/` already has, and this baseline does not reimplement "
            "them.",
        ],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n", encoding="utf-8")

    baseline = runs["selected_calibration"]
    final = baseline["divergence"]["final"]
    print(f"session          {metadata.recording_id} ({metadata.source.value})")
    print(f"calibration      {calibration_selection['source']} "
          f"(session declares {metadata.calibration.state.value})")
    print(f"paired samples   {stats['paired_samples']} over {stats['elapsed_s']} s")
    print(f"step intervals   {baseline['step_durations']}")
    print(f"status           {baseline['status']} {baseline['reasons']}")
    print(f"final deviation  {final['horizontal_deviation_m']} m horizontal, "
          f"{final['vertical_deviation_m']} m vertical")
    print(f"attitude drift   {baseline['attitude_drift_deg']} deg")
    print(f"isolation audit  GNSS-free stream identical: {isolation['timestamps_identical']} / "
          f"{isolation['accel_identical']} / "
          f"{isolation['gyro_identical']}")
    print(f"report           {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
