"""Convert IO-VNBD synchronized phone/VBOX pairs into contract recordings plus truth.

The navigation engine consumes contract measurement records, so IO-VNBD is evaluated by
turning each smartphone CSV into an ordinary `real` recording that the engine replays
exactly like a phone session. The VBOX stream becomes a separate truth file that is never
part of the recording.

    python tools/iovnbd_to_contract.py                    # all synchronized pairs
    python tools/iovnbd_to_contract.py --sequences m vta24

Per sequence, under --out (default data/processed/iovnbd_eval, gitignored):

    <seq>/iovnbd-<seq>/          recording: phone IMU + phone GPS as logged
    <seq>/iovnbd-<seq>-vbox1hz/  recording: phone IMU + VBOX GPS fixes at 1 Hz
    <seq>/truth.csv              VBOX position/speed/heading on the phone clock
    <seq>/conversion.json        every choice below, with its evidence

Each recording holds metadata.json (recording contract 1.0.0) and measurements.jsonl.

GNSS sources. The phone logger recorded GPS only about every 9 s, so the phone-GPS recording
cannot satisfy the engine's mount calibration (it needs fixes no more than 5 s apart). The
second recording uses the VBOX receiver's own fixes, decimated to the 1 Hz a phone receiver
delivers; this is the usual way GNSS outages are studied on IO-VNBD. Outage drift is fair
either way: inside an outage no GNSS of any source reaches the engine.

Input reconstruction (stated, not hidden). The phone accelerometer columns are an
AndroSensor export that is already levelled: gravity sits on +z. Their horizontal axes are
body-fixed in some recordings and Earth-aligned in others (the Driver A/B drives); an
Earth-aligned export is rotated back with the phone's logged azimuth, chosen per drive by
which representation calibrates better in the warm-up window. The gyroscope columns are raw
device axes, so accelerometer and gyroscope are not one frame as logged. The recording therefore uses the levelled accelerometer frame
as the device frame and carries a planar gyroscope: the measured yaw rate on z and zero
roll/pitch rate. The yaw axis is the gyro's dominant rotation axis and its sign comes from
phone-GPS course rate; VBOX is never used to build an input. Mount yaw, gyro bias and
everything else are left for the engine's own calibration. The GPS speed column is labelled
km/h but is converted with a unit inferred per sequence from fix displacement.

Truth alignment. VBOX and phone clocks are both anchored at their first sample (as in
training/iovnbd_dataset.py) and refined by cross-correlating the selected phone yaw rate
with the VBOX yaw-rate channel. That is a sensor-to-sensor match, so phone GNSS latency
does not leak into the truth timing.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from contracts.recording.v1.codec import encode_metadata, encode_record  # noqa: E402
from contracts.recording.v1.models import (  # noqa: E402
    ApplicationInfo, CalibrationApplication, CalibrationInfo, ChannelCount, ClockDomain,
    ClockIdentity, CompletionState, DeviceInfo, LocationPermissionState, RecordingEndState,
    RecordingMetadata, RecordingStartState, RecoveryState, SensorDescriptor, SourceConfiguration,
)
from contracts.recording.v1.session import SessionError, read_session  # noqa: E402
from contracts.v1.models import (  # noqa: E402
    AltitudeReference, DeviceFrame, Event, GnssMeasurement, Header, ImuMeasurement, ImuUnit,
    Record, Sensor, SensorAccuracy, Source, Vector3,
)
from training.iovnbd_dataset import load_candidate_filepaths  # noqa: E402
from training.phase0.audit import read_frame, time_array  # noqa: E402

DEFAULT_DATA_ROOT = ROOT / "data" / "raw" / "iovnbd"
DEFAULT_OUT = ROOT / "data" / "processed" / "iovnbd_eval"

ORIGIN_NS = 1_000_000_000          # session origin on a synthetic elapsed-realtime clock
GYRO_CHANNELS = ("gyro_channel_yaw", "gyro_channel_pitch", "gyro_channel_roll")

# Yaw-axis selection from phone GPS course rate.
COURSE_MIN_SPEED_M_S = 5.0
COURSE_MAX_ACCURACY_M = 15.0
COURSE_MAX_GAP_S = 12.0       # IO-VNBD phone fixes arrive roughly every 9 s
YAW_MIN_INTERVALS = 20
YAW_MIN_REGRESSION_R = 0.45   # course-rate fit that makes the regression axis trustworthy
YAW_MIN_DOMINANCE = 2.0       # largest gyro-covariance eigenvalue over the second largest
YAW_MIN_SIGN_Z = 4.0          # |r| * sqrt(n) for the course-rate sign test

# Truth alignment search.
ALIGN_GRID_HZ = 10.0
ALIGN_MAX_LAG_S = 10.0
ALIGN_MIN_ABS_CORR = 0.5
TRUTH_MAX_MEDIAN_GNSS_GAP_M = 10.0   # phone fixes vs aligned truth, median
VBOX_DECLARED_ACCURACY_M = 2.5       # VBOX reports no accuracy; stated assumption, not measured

# VBOX vehicle-frame inertial channels used as an external IMU.
VEHICLE_IMU_MIN_R = 0.5
VEHICLE_IMU_WARMUP_S = 60.0

# Batch mount calibration (stand-in for the in-motion calibrator on this data).
CALIBRATION_WINDOWS_S = (300.0, 600.0, 900.0, 1200.0, 1800.0)
CALIBRATION_MIN_MOVING_S = 60
CALIBRATION_MIN_R_LONG = 0.5


class ConversionError(ValueError):
    """A sequence that cannot be converted honestly; the reason is reported, not hidden."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class Fix:
    index: int                  # phone row where this fix first appears
    latitude_deg: float
    longitude_deg: float
    altitude_m: float | None
    speed_m_s: float | None
    course_deg: float | None
    accuracy_m: float | None
    satellites_used: int | None


@dataclass(frozen=True)
class YawAxis:
    method: str                              # "course_regression" or "gyro_pca"
    axis: tuple[float, float, float]         # unit vector over GYRO_CHANNELS, sign applied
    eigenvalues: tuple[float, float, float]  # ascending gyro covariance eigenvalues
    dominant_column: str
    course_rate_correlation: float           # of the chosen projection, after the sign
    course_rate_z_score: float               # correlation * sqrt(intervals)
    regression_correlation: float            # fit quality of the regression candidate
    intervals: int


@dataclass(frozen=True)
class TruthAlignment:
    signal: str                 # VBOX signal the phone yaw rate was matched against
    lag_s: float                # t_vbox_rel = t_phone_rel + lag_s
    correlation: float          # of phone z yaw rate with that signal, after the sign below
    vbox_sign: int              # +1 if the signal is counter-clockwise positive
    overlap_s: float


def _finite(value) -> float | None:
    try:
        x = float(value)
    except (TypeError, ValueError):
        return None
    return x if math.isfinite(x) else None


def parse_satellites(raw) -> int | None:
    """AndroSensor writes 'used / in range'; keep the used count."""
    text = str(raw).strip()
    head = text.split("/")[0].strip()
    return int(head) if head.isdigit() else None


def load_phone(path: Path):
    """Phone rows with strictly increasing millisecond timestamps and finite IMU values.

    The clock is the wall-clock DATE column, not the elapsed counter: the counter restarts
    when the logger starts a new session inside one file (sequence M does this once), while
    the date column stays monotonic.
    """
    df = read_frame(path)
    t_ms = np.round(time_array(df, source="smartphone") * 1000.0)
    imu_cols = ["accel_x", "accel_y", "accel_z", *GYRO_CHANNELS]
    imu = df[imu_cols].apply(lambda c: c.astype(float)).to_numpy()
    keep = np.isfinite(t_ms) & np.all(np.isfinite(imu), axis=1)
    last = -math.inf
    for i in range(len(t_ms)):
        if keep[i]:
            if t_ms[i] <= last:
                keep[i] = False
            else:
                last = t_ms[i]
    dropped = int(len(df) - keep.sum())
    df = df.loc[keep].reset_index(drop=True)
    return df, t_ms[keep].astype(np.int64), dropped


def extract_fixes(df, speed_scale: float = 1.0) -> list[Fix]:
    """A fix is a row whose GNSS fields differ from the previous row's (the export repeats
    the last fix on every 10 Hz row). `speed_scale` converts the raw speed column to m/s."""
    cols = ["gps_latitude", "gps_longitude", "gps_altitude", "gps_speed_raw", "gps_accuracy",
            "gps_course"]
    values = df[cols].to_numpy(dtype=object)
    sats = df["gps_satellites_raw"].to_numpy(dtype=object)
    fixes: list[Fix] = []
    previous = None
    for i, row in enumerate(values):
        lat, lon, alt, spd_kmh, acc, course = (_finite(v) for v in row)
        key = (lat, lon, alt, spd_kmh, acc, course)
        if key == previous:
            continue
        previous = key
        if lat is None or lon is None or (lat == 0.0 and lon == 0.0):
            continue
        if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
            continue
        speed = None if spd_kmh is None or spd_kmh < 0 else spd_kmh * speed_scale
        bearing = None if course is None else course % 360.0
        accuracy = acc if acc is not None and acc > 0 else None
        fixes.append(Fix(i, lat, lon, alt, speed, bearing, accuracy, parse_satellites(sats[i])))
    return fixes


def infer_speed_scale(t_s: np.ndarray, fixes: Sequence[Fix]) -> tuple[float, float, int]:
    """Factor that turns the raw GPS speed column into m/s, from phone evidence only.

    The header says km/h, but Phase 0 found the values behave like m/s in most pairs. The
    ratio of reported speed to displacement-derived speed between consecutive fixes decides:
    about 1 means m/s, about 3.6 means km/h. Anything else is refused.
    """
    ratios = []
    for f0, f1 in zip(fixes, fixes[1:]):
        dt = t_s[f1.index] - t_s[f0.index]
        if not 0.0 < dt <= COURSE_MAX_GAP_S or f0.speed_m_s is None or f1.speed_m_s is None:
            continue
        lat0 = math.radians(f0.latitude_deg)
        d_n = math.radians(f1.latitude_deg - f0.latitude_deg) * 6_371_000.0
        d_e = math.radians(f1.longitude_deg - f0.longitude_deg) * 6_371_000.0 * math.cos(lat0)
        moved = math.hypot(d_n, d_e) / dt
        if moved >= 3.0:
            ratios.append(0.5 * (f0.speed_m_s + f1.speed_m_s) / moved)
    if len(ratios) < 10:
        raise ConversionError("SPEED_UNIT", f"only {len(ratios)} moving fix pairs")
    median = float(np.median(ratios))
    if 0.75 <= median <= 1.33:
        return 1.0, median, len(ratios)
    if 2.7 <= median <= 4.8:
        return 1.0 / 3.6, median, len(ratios)
    raise ConversionError("SPEED_UNIT", f"reported/displacement speed ratio {median:.2f}")


def _interval_means(t_s: np.ndarray, signal: np.ndarray, a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Mean of a sampled signal over [a, b] from its trapezoidal running integral."""
    integral = np.concatenate([[0.0], np.cumsum(0.5 * (signal[1:] + signal[:-1]) * np.diff(t_s))])
    return (np.interp(b, t_s, integral) - np.interp(a, t_s, integral)) / (b - a)


def select_yaw_axis(t_s: np.ndarray, gyro: dict[str, np.ndarray], fixes: Sequence[Fix]) -> YawAxis:
    """Find the vehicle yaw axis in the gyro frame, from phone data only.

    Course is clockwise from north and the recording's z axis points up, so the target is the
    counter-clockwise rate -d(course)/dt, averaged over each pair of consecutive fixes.

    1. Course regression: least squares of that rate on the three interval-mean gyro
       channels. The fitted direction is the yaw axis, sign included, and handles a tilted
       mount. Used when the fit is clearly real (R >= YAW_MIN_REGRESSION_R).
    2. Otherwise gyro PCA: a car rotates mostly about the vertical, so the dominant principal
       axis is yaw when it clearly dominates; its sign comes from the course rate, gated by a
       z-score because ~9 s fix spacing keeps that correlation modest even when correct.

    Neither step reads VBOX. The truth alignment later checks the result independently.
    """
    a, b, rate = [], [], []
    for f0, f1 in zip(fixes, fixes[1:]):
        if None in (f0.speed_m_s, f1.speed_m_s, f0.course_deg, f1.course_deg):
            continue
        if min(f0.speed_m_s, f1.speed_m_s) < COURSE_MIN_SPEED_M_S:
            continue
        if any(f.accuracy_m is None or f.accuracy_m > COURSE_MAX_ACCURACY_M for f in (f0, f1)):
            continue
        t0, t1 = t_s[f0.index], t_s[f1.index]
        dt = t1 - t0
        if not 0.0 < dt <= COURSE_MAX_GAP_S:
            continue
        d_course = (f1.course_deg - f0.course_deg + 540.0) % 360.0 - 180.0
        a.append(t0); b.append(t1); rate.append(-math.radians(d_course) / dt)
    if len(rate) < YAW_MIN_INTERVALS:
        raise ConversionError("YAW_EVIDENCE", f"only {len(rate)} usable GPS course intervals")
    names = list(gyro)
    samples = np.stack([gyro[n] for n in names], axis=1)
    a_arr, b_arr, r_arr = np.array(a), np.array(b), np.array(rate)
    means = np.stack([_interval_means(t_s, samples[:, k], a_arr, b_arr) for k in range(3)], axis=1)
    eigenvalues, vectors = np.linalg.eigh(np.cov((samples - samples.mean(axis=0)).T))

    def correlation(axis: np.ndarray) -> float:
        projected = means @ axis
        return float(np.corrcoef(projected, r_arr)[0, 1]) if np.std(projected) > 0 else 0.0

    beta, *_ = np.linalg.lstsq(np.column_stack([means, np.ones(len(r_arr))]), r_arr, rcond=None)
    regression_axis = beta[:3] / np.linalg.norm(beta[:3]) if np.linalg.norm(beta[:3]) > 0 else beta[:3]
    regression_r = correlation(regression_axis) if np.linalg.norm(beta[:3]) > 0 else 0.0

    if regression_r >= YAW_MIN_REGRESSION_R:
        method, axis = "course_regression", regression_axis
    else:
        if eigenvalues[-1] < YAW_MIN_DOMINANCE * eigenvalues[-2]:
            raise ConversionError("YAW_AXIS", f"regression R={regression_r:.2f} and no dominant "
                                              f"gyro axis (eigenvalues {np.round(eigenvalues, 5)})")
        method, axis = "gyro_pca", vectors[:, -1]
        if correlation(axis) < 0:
            axis = -axis
    corr = correlation(axis)
    z = abs(corr) * math.sqrt(len(rate))
    if z < YAW_MIN_SIGN_Z:
        raise ConversionError("YAW_SIGN", f"{method} course-rate r={corr:+.2f} over {len(rate)} intervals")
    return YawAxis(method, tuple(float(x) for x in axis), tuple(float(x) for x in eigenvalues),
                   names[int(np.argmax(np.abs(axis)))], round(corr, 4), round(z, 2),
                   round(regression_r, 4), len(rate))


def vbox_heading_rate(t_vbox: np.ndarray, heading_deg: np.ndarray, speed_m_s: np.ndarray) -> np.ndarray:
    """Counter-clockwise turn rate from VBOX heading, smoothed over 1 s, zero below 3 m/s
    (heading is meaningless when nearly stopped)."""
    ok = np.isfinite(t_vbox) & np.isfinite(heading_deg)
    rate = np.zeros(len(t_vbox))
    if ok.sum() < 20:
        return rate
    h = np.unwrap(np.radians(heading_deg[ok]))
    kernel = np.ones(10) / 10.0
    smooth = np.convolve(h, kernel, mode="same")
    t = t_vbox[ok]
    dt = np.gradient(t)
    rate_ok = np.where(dt > 0, -np.gradient(smooth) / np.where(dt > 0, dt, 1.0), 0.0)
    rate[ok] = np.where(np.nan_to_num(speed_m_s[ok]) >= 3.0, rate_ok, 0.0)
    return rate


def align_truth(t_phone: np.ndarray, yaw_z: np.ndarray, t_vbox: np.ndarray,
                candidates: dict[str, np.ndarray]) -> TruthAlignment:
    """Lag that best matches phone yaw rate to a VBOX turn-rate signal on a 10 Hz grid.

    Several VBOX signals are tried because some vehicles' yaw-rate channel barely varies;
    the best-correlated one wins and is recorded.
    """
    results = []
    failures = []
    for name, signal in candidates.items():
        try:
            results.append(_align_one(name, t_phone, yaw_z, t_vbox, signal))
        except ConversionError as exc:
            failures.append(f"{name}: {exc.detail}")
    if not results:
        raise ConversionError("TRUTH_ALIGNMENT", "; ".join(failures))
    return max(results, key=lambda r: r.correlation)


def _align_one(name: str, t_phone: np.ndarray, yaw_z: np.ndarray, t_vbox: np.ndarray,
               vbox_rate: np.ndarray) -> TruthAlignment:
    ok = np.isfinite(t_vbox) & np.isfinite(vbox_rate)
    t_v, y_v = t_vbox[ok], vbox_rate[ok]
    if len(t_v) < 100:
        raise ConversionError("TRUTH_SHORT", "too few VBOX samples")
    dt = 1.0 / ALIGN_GRID_HZ
    grid = np.arange(max(t_phone[0], t_v[0]), min(t_phone[-1], t_v[-1]), dt)
    if len(grid) < 2 * ALIGN_MAX_LAG_S * ALIGN_GRID_HZ:
        raise ConversionError("TRUTH_OVERLAP", f"only {len(grid) * dt:.1f} s overlap")
    p = np.interp(grid, t_phone, yaw_z)
    best = (0.0, 0.0)
    max_lag = int(ALIGN_MAX_LAG_S * ALIGN_GRID_HZ)
    scores = []
    for k in range(-max_lag, max_lag + 1):
        v = np.interp(grid + k * dt, t_v, y_v)
        r = float(np.corrcoef(p, v)[0, 1]) if np.std(v) > 0 and np.std(p) > 0 else 0.0
        scores.append(r)
        if abs(r) > abs(best[1]):
            best = (k, r)
    k, r = best
    lag = k * dt
    i = k + max_lag
    if 0 < i < len(scores) - 1:  # parabolic refinement on |r|
        y0, y1, y2 = abs(scores[i - 1]), abs(scores[i]), abs(scores[i + 1])
        denom = y0 - 2 * y1 + y2
        if denom < 0:
            lag += 0.5 * (y0 - y2) / denom * dt
    if abs(r) < ALIGN_MIN_ABS_CORR:
        raise ConversionError("TRUTH_ALIGNMENT", f"best |r|={abs(r):.2f} at {lag:+.2f} s")
    return TruthAlignment(name, lag, abs(r), 1 if r > 0 else -1, float(grid[-1] - grid[0]))


def batch_mount_calibration(t_s: np.ndarray, accel: np.ndarray, yaw_z: np.ndarray,
                            gnss: Sequence[tuple[int, GnssMeasurement]]) -> dict:
    """Mount yaw and z gyro bias from the start of a drive: phone IMU + the GNSS aiding stream.

    This stands in for the app's in-motion calibrator, which does not reach VALID on this
    10 Hz data (reports/iovnbd_eval explains why). It reads only what the engine itself
    receives, never VBOX truth, and the result is released to the engine at `available_s`,
    the end of the window it was estimated from, so nothing earlier can use it.

    Vehicle frame: x forward, y left, z up. The recording's device frame is the levelled
    accelerometer frame, so the mount is a rotation about z by theta with
    v_vehicle = e^{i theta} v_device for horizontal vectors written as complex numbers.
    """
    tg = np.array([e[0] for e in gnss], dtype=float)
    tg = (tg - ORIGIN_NS) / 1e9
    v = np.array([np.nan if e[1].speed_m_s is None else e[1].speed_m_s for e in gnss])
    course = np.array([np.nan if e[1].bearing_deg is None else e[1].bearing_deg for e in gnss])
    reasons = []
    for window_s in CALIBRATION_WINDOWS_S:
        k = np.flatnonzero((tg > 1.0) & (tg < window_s - 1.0))
        if len(k) < 3:
            reasons.append(f"{window_s:.0f} s: too few fixes")
            continue
        inner = k[(k > 0) & (k < len(tg) - 1)]
        dt2 = tg[inner + 1] - tg[inner - 1]
        a_long = (v[inner + 1] - v[inner - 1]) / dt2
        lo, hi = tg[inner] - 0.5, tg[inner] + 0.5
        ax = _interval_means(t_s, accel[:, 0], lo, hi)
        ay = _interval_means(t_s, accel[:, 1], lo, hi)
        w = _interval_means(t_s, yaw_z, lo, hi)
        a_lat = v[inner] * w
        moving = (v[inner] >= 3.0) & np.isfinite(a_long) & np.isfinite(a_lat) & (dt2 > 0) & (dt2 <= 3.0)
        if moving.sum() < CALIBRATION_MIN_MOVING_S:
            reasons.append(f"{window_s:.0f} s: {int(moving.sum())} moving seconds")
            continue
        dev = (ax[moving] - ax[moving].mean()) + 1j * (ay[moving] - ay[moving].mean())
        veh = (a_long[moving] - a_long[moving].mean()) + 1j * (a_lat[moving] - a_lat[moving].mean())
        theta = float(np.angle(np.sum(veh * np.conj(dev))))
        rotated = np.exp(1j * theta) * dev
        r_long = float(np.corrcoef(rotated.real, veh.real)[0, 1])
        r_lat = float(np.corrcoef(rotated.imag, veh.imag)[0, 1])
        if r_long < CALIBRATION_MIN_R_LONG:
            reasons.append(f"{window_s:.0f} s: longitudinal r={r_long:.2f}")
            continue

        # Gyro bias: median gyro while GNSS reports a stop, else gyro minus GNSS course rate.
        stopped = np.flatnonzero((tg < window_s) & (v < 0.2))
        if len(stopped) >= 3:
            mask = np.zeros(len(t_s), dtype=bool)
            for j in stopped:
                mask |= (t_s >= tg[j] - 0.5) & (t_s <= tg[j] + 0.5)
            bias = float(np.median(yaw_z[mask]))
            bias_method = "median at GNSS standstill"
        else:
            ok = moving & (v[inner] >= 5.0) & np.isfinite(course[inner - 1]) & np.isfinite(course[inner + 1])
            d_course = (course[inner + 1] - course[inner - 1] + 540.0) % 360.0 - 180.0
            ccw = -np.radians(d_course) / dt2
            bias = float(np.median((w - ccw)[ok])) if ok.sum() >= 30 else 0.0
            bias_method = "median of gyro minus GNSS course rate" if ok.sum() >= 30 else "none (assumed 0)"
        return {
            "status": "valid",
            "available_s": float(window_s),
            "theta_deg": round(math.degrees(theta), 3),
            "q_vehicle_from_device_wxyz": [math.cos(theta / 2), 0.0, 0.0, math.sin(theta / 2)],
            "gyro_bias_rad_s": [0.0, 0.0, bias],
            "gyro_bias_method": bias_method,
            "longitudinal_r": round(r_long, 3),
            "lateral_r": round(r_lat, 3),
            "moving_seconds": int(moving.sum()),
        }
    return {"status": "failed", "reasons": reasons}


def vehicle_frame_inertial(t_phone_s: np.ndarray, t_vbox: np.ndarray, lag_s: float, vb) -> dict:
    """VBOX vehicle-frame yaw rate and longitudinal/lateral acceleration as an external IMU.

    The vehicle's own inertial channels are already in a vehicle frame, so they need no mount
    calibration; they stand in for an external IMU (PS #26168 asks the engine to accept one).
    Specific force is built as x = forward, y = left, z = +g (level road assumed); the gyroscope
    carries yaw rate only, counter-clockwise positive. Channel signs are fixed from VBOX's own
    kinematics (never from the phone): the yaw-rate sign from the heading rate, the lateral sign
    from v * yaw_rate. Returns arrays on the phone clock plus the evidence for each choice.
    """
    ok = np.isfinite(t_vbox)
    tv = t_vbox[ok]
    col = lambda name: vb[name].to_numpy(float)[ok]
    speed = col("reference_speed") / 3.6
    yaw_raw = np.radians(col("vehicle_yaw_rate"))
    a_long = col("vehicle_accel_long") * 9.80665
    a_lat_raw = col("vehicle_accel_lat") * 9.80665
    heading_rate = vbox_heading_rate(tv, col("reference_heading"), speed)
    moving = speed >= 3.0
    if moving.sum() < 100:
        raise ConversionError("VEHICLE_IMU", "too little motion")

    def corr(a, b):
        return float(np.corrcoef(a[moving], b[moving])[0, 1]) if np.std(a[moving]) > 0 and np.std(b[moving]) > 0 else 0.0

    r_yaw = corr(yaw_raw, heading_rate)
    if abs(r_yaw) < VEHICLE_IMU_MIN_R:
        raise ConversionError("VEHICLE_IMU", f"VBOX yaw rate vs heading rate r={r_yaw:+.2f}")
    yaw_sign = 1.0 if r_yaw > 0 else -1.0
    yaw_ccw = yaw_sign * yaw_raw
    r_lat = corr(a_lat_raw, speed * yaw_ccw)
    if abs(r_lat) < VEHICLE_IMU_MIN_R:
        raise ConversionError("VEHICLE_IMU", f"VBOX lateral acceleration vs v*yaw r={r_lat:+.2f}")
    lat_sign = 1.0 if r_lat > 0 else -1.0
    q = t_phone_s + lag_s
    interp = lambda y: np.interp(q, tv, y)
    return {
        "accel": np.column_stack([interp(a_long), interp(lat_sign * a_lat_raw),
                                  np.full(len(q), 9.80665)]),
        "yaw_z": interp(yaw_ccw),
        "evidence": {"yaw_sign": int(yaw_sign), "yaw_vs_heading_rate_r": round(r_yaw, 3),
                     "lat_sign": int(lat_sign), "lat_vs_v_yaw_r": round(r_lat, 3)},
    }


def vehicle_imu_calibration(t_phone_s: np.ndarray, yaw_z: np.ndarray,
                            gnss: Sequence[tuple[int, GnssMeasurement]]) -> dict:
    """Identity mount (already vehicle frame) and a z gyro bias from the first GNSS standstills."""
    tg = (np.array([e[0] for e in gnss], dtype=float) - ORIGIN_NS) / 1e9
    v = np.array([np.nan if e[1].speed_m_s is None else e[1].speed_m_s for e in gnss])
    stopped = np.flatnonzero((tg < VEHICLE_IMU_WARMUP_S) & (v < 0.2))
    mask = np.zeros(len(t_phone_s), dtype=bool)
    for j in stopped:
        mask |= (t_phone_s >= tg[j] - 0.5) & (t_phone_s <= tg[j] + 0.5)
    bias = float(np.median(yaw_z[mask])) if mask.sum() >= 10 else 0.0
    return {"status": "valid", "available_s": VEHICLE_IMU_WARMUP_S, "theta_deg": 0.0,
            "q_vehicle_from_device_wxyz": [1.0, 0.0, 0.0, 0.0], "gyro_bias_rad_s": [0.0, 0.0, bias],
            "gyro_bias_method": "median at GNSS standstill" if mask.sum() >= 10 else "none (assumed 0)",
            "moving_seconds": None}


def _record(header: Header, event_id: int, t_ns: int, payload) -> Record:
    return Record(header, Event(str(event_id), t_ns, t_ns, payload))


def phone_gnss_events(t_ns: np.ndarray, fixes: Sequence[Fix]) -> list[tuple[int, GnssMeasurement]]:
    return [(int(t_ns[f.index]), GnssMeasurement(
        f.latitude_deg, f.longitude_deg, f.altitude_m,
        None if f.altitude_m is None else AltitudeReference.UNKNOWN,
        f.speed_m_s, f.course_deg, f.accuracy_m, None, f.satellites_used, "gps", None))
        for f in fixes]


def vbox_gnss_events(vb, t_vbox: np.ndarray, lag_s: float, t_phone_end_s: float
                     ) -> list[tuple[int, GnssMeasurement]]:
    """VBOX receiver fixes decimated to 1 Hz on the phone clock (t_phone = t_vbox - lag).

    Real fixes from a real receiver, nothing interpolated. VBOX reports no accuracy, so the
    declared VBOX_DECLARED_ACCURACY_M is an assumption stated in conversion.json.
    """
    lat = vb["reference_latitude"].to_numpy(float)
    lon = vb["reference_longitude"].to_numpy(float)
    spd = vb["reference_speed"].to_numpy(float) / 3.6
    hdg = vb["reference_heading"].to_numpy(float)
    sats = vb["reference_satellites_raw"].to_numpy(dtype=object)
    events = []
    last_second = None
    for i in range(len(t_vbox)):
        tv = t_vbox[i]
        if not math.isfinite(tv):
            continue
        second = math.floor(tv)
        if second == last_second:
            continue
        last_second = second
        tp = tv - lag_s
        if tp < 0.0 or tp > t_phone_end_s:
            continue
        if not (math.isfinite(lat[i]) and math.isfinite(lon[i])) or (lat[i] == 0.0 and lon[i] == 0.0):
            continue
        n_sats = _finite(sats[i])
        events.append((ORIGIN_NS + int(round(tp * 1e9)), GnssMeasurement(
            float(lat[i]), float(lon[i]), None, None,
            float(spd[i]) if math.isfinite(spd[i]) and spd[i] >= 0 else None,
            float(hdg[i]) % 360.0 if math.isfinite(hdg[i]) else None,
            VBOX_DECLARED_ACCURACY_M, None, None if n_sats is None else int(n_sats), "gps", None)))
    return events


def write_recording(out_dir: Path, seq: str, session_id: str, t_ns: np.ndarray, accel: np.ndarray,
                    yaw_z: np.ndarray, gnss: list[tuple[int, GnssMeasurement]], imu_hz: float,
                    gnss_name: str) -> int:
    """One `real` recording: per phone row an accelerometer and a planar gyroscope record, with
    GNSS events merged in measurement-time order (after the IMU records of an equal time)."""
    header = Header(session_id, Source.REAL, "1.0.0")
    gnss = sorted(gnss, key=lambda e: e[0])
    records: list[Record] = []
    eid = 1
    g = 0
    for i in range(len(t_ns)):
        t = int(t_ns[i])
        while g < len(gnss) and gnss[g][0] < t:
            records.append(_record(header, eid, gnss[g][0], gnss[g][1])); eid += 1; g += 1
        records.append(_record(header, eid, t, ImuMeasurement(
            Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE, ImuUnit.METRES_PER_SECOND_SQUARED,
            Vector3(*map(float, accel[i])), SensorAccuracy.UNKNOWN)))
        eid += 1
        records.append(_record(header, eid, t, ImuMeasurement(
            Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE, ImuUnit.RADIANS_PER_SECOND,
            Vector3(0.0, 0.0, float(yaw_z[i])), SensorAccuracy.UNKNOWN)))
        eid += 1
        while g < len(gnss) and gnss[g][0] == t:
            records.append(_record(header, eid, gnss[g][0], gnss[g][1])); eid += 1; g += 1
    gnss_count = g   # fixes after the last IMU row are dropped: nothing would consume them

    metadata = RecordingMetadata(
        recording_id=session_id, acquisition_session_id=session_id, source=Source.REAL,
        start_state=RecordingStartState.RECORDING, end_state=RecordingEndState.STOPPED,
        completion_state=CompletionState.COMPLETED, recovery_state=RecoveryState.NONE,
        created_utc_ms=None, started_utc_ms=None, ended_utc_ms=None,
        clock=ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS, None,
                            f"iovnbd-{seq}-phone-clock", ORIGIN_NS, ORIGIN_NS, int(t_ns[-1])),
        device=DeviceInfo(None, None, None, None, None),
        application=ApplicationInfo(None, None, None, f"iovnbd_to_contract ({gnss_name})"),
        sensors=(
            SensorDescriptor(Sensor.ACCELEROMETER, "IO-VNBD AndroSensor levelled export", None,
                             True, None, imu_hz),
            SensorDescriptor(Sensor.GYROSCOPE, "IO-VNBD planar yaw (dominant gyro axis)", None,
                             True, None, imu_hz),
        ),
        source_configuration=SourceConfiguration(LocationPermissionState.PRECISE, True, True,
                                                 None, None, True),
        calibration=CalibrationInfo(CalibrationApplication.NOT_APPLIED, None),
        record_count=len(records),
        channel_counts=(ChannelCount("imu", 2 * len(t_ns)), ChannelCount("gnss", gnss_count)),
    )
    session_dir = out_dir / seq / session_id   # the reader requires directory name == recording ID
    session_dir.mkdir(parents=True, exist_ok=True)
    (session_dir / "metadata.json").write_bytes(encode_metadata(metadata))
    with open(session_dir / "measurements.jsonl", "wb") as fh:
        for record in records:
            fh.write(encode_record(record, metadata) + b"\n")
    readback = sum(1 for _ in read_session(session_dir))
    if readback != len(records):
        raise ConversionError("READBACK", f"{session_id}: wrote {len(records)}, read {readback}")
    return gnss_count


def convert_sequence(seq: str, phone_path: Path, vbox_path: Path, out_dir: Path) -> dict:
    df, t_ms, dropped = load_phone(phone_path)
    if len(t_ms) < 600:
        raise ConversionError("TOO_SHORT", f"{len(t_ms)} usable phone rows")
    t_rel_s = (t_ms - t_ms[0]) / 1000.0
    speed_scale, speed_ratio, speed_pairs = infer_speed_scale(t_rel_s, extract_fixes(df))
    fixes = extract_fixes(df, speed_scale)
    gyro = {c: df[c].to_numpy(float) for c in GYRO_CHANNELS}
    yaw = select_yaw_axis(t_rel_s, gyro, fixes)
    yaw_z = np.stack([gyro[c] for c in GYRO_CHANNELS], axis=1) @ np.array(yaw.axis)

    vb = read_frame(vbox_path)
    t_vbox = time_array(vb, source="vbox")
    alignment = align_truth(t_rel_s, yaw_z, t_vbox, {
        "vbox_yaw_rate": np.radians(vb["vehicle_yaw_rate"].to_numpy(float)),
        "vbox_heading_rate": vbox_heading_rate(t_vbox, vb["reference_heading"].to_numpy(float),
                                               vb["reference_speed"].to_numpy(float) / 3.6),
    })

    # ---- accelerometer frame ---------------------------------------------------------------
    # AndroSensor exported some recordings with Earth-aligned horizontal axes (East/North-like)
    # and others body-fixed. An Earth-aligned export is rotated back into a heading-following
    # frame with the phone's own azimuth: d = e^{i az} (a_x + i a_y). Which representation the
    # recording uses is decided by which calibrates better inside the warm-up window, from phone
    # sensors and the GNSS aiding stream only, so the choice is causal and never reads truth.
    t_ns = ORIGIN_NS + (t_ms - t_ms[0]) * 1_000_000
    logged = df[["accel_x", "accel_y", "accel_z"]].to_numpy(float)
    azimuth = np.radians(df["orientation_yaw"].to_numpy(float))
    horizontal = np.exp(1j * azimuth) * (logged[:, 0] + 1j * logged[:, 1])
    derotated = np.column_stack([horizontal.real, horizontal.imag, logged[:, 2]])
    duration_s = float(t_rel_s[-1])
    imu_hz = round((len(t_ns) - 1) / duration_s, 3)
    fix_at = {f.index: f for f in fixes}
    phone_events = phone_gnss_events(t_ns, fixes)
    vbox_events = vbox_gnss_events(vb, t_vbox, alignment.lag_s, duration_s)
    frame_trials = {
        "as_logged": batch_mount_calibration(t_rel_s, logged, yaw_z, vbox_events),
        "earth_aligned_derotated_by_azimuth": (
            batch_mount_calibration(t_rel_s, derotated, yaw_z, vbox_events)
            if np.all(np.isfinite(azimuth)) else {"status": "failed", "reasons": ["azimuth missing"]}),
    }
    valid = {k: v for k, v in frame_trials.items() if v["status"] == "valid"}
    accel_frame = max(valid, key=lambda k: valid[k]["longitudinal_r"]) if valid else "as_logged"
    accel = derotated if accel_frame == "earth_aligned_derotated_by_azimuth" else logged

    # ---- recordings ------------------------------------------------------------------------
    sessions = {
        "phone_gnss": {
            "session_id": f"iovnbd-{seq}",
            "gnss_fixes": write_recording(out_dir, seq, f"iovnbd-{seq}", t_ns, accel, yaw_z,
                                          phone_events, imu_hz, "phone GNSS"),
            "gnss_source": "phone GPS as logged (about every 9 s)",
            "batch_calibration": batch_mount_calibration(t_rel_s, accel, yaw_z, phone_events),
        },
        "vbox_gnss_1hz": {
            "session_id": f"iovnbd-{seq}-vbox1hz",
            "gnss_fixes": write_recording(out_dir, seq, f"iovnbd-{seq}-vbox1hz", t_ns, accel, yaw_z,
                                          vbox_events, imu_hz, "VBOX GNSS 1 Hz"),
            "gnss_source": "VBOX receiver fixes decimated to 1 Hz, moved to the phone clock",
            "declared_horizontal_accuracy_m": VBOX_DECLARED_ACCURACY_M,
            "batch_calibration": frame_trials[accel_frame],
        },
    }
    try:
        veh = vehicle_frame_inertial(t_rel_s, t_vbox, alignment.lag_s, vb)
        sessions["vbox_vehicle_imu"] = {
            "session_id": f"iovnbd-{seq}-vehimu",
            "gnss_fixes": write_recording(out_dir, seq, f"iovnbd-{seq}-vehimu", t_ns, veh["accel"],
                                          veh["yaw_z"], vbox_events, imu_hz, "VBOX GNSS 1 Hz + vehicle IMU"),
            "gnss_source": "VBOX receiver fixes decimated to 1 Hz",
            "inertial_source": "VBOX vehicle-frame yaw rate and longitudinal/lateral acceleration "
                               "(external-IMU case; no phone sensors)",
            "declared_horizontal_accuracy_m": VBOX_DECLARED_ACCURACY_M,
            "channel_evidence": veh["evidence"],
            "batch_calibration": vehicle_imu_calibration(t_rel_s, veh["yaw_z"], vbox_events),
        }
    except ConversionError as exc:
        sessions["vbox_vehicle_imu"] = {"status": "unavailable", "code": exc.code, "detail": exc.detail}

    # ---- truth on the phone clock ---------------------------------------------------------
    ok = np.isfinite(t_vbox)
    tv = t_vbox[ok]
    q = t_rel_s + alignment.lag_s
    inside = (q >= tv[0]) & (q <= tv[-1])
    lat = np.interp(q, tv, vb["reference_latitude"].to_numpy(float)[ok])
    lon = np.interp(q, tv, vb["reference_longitude"].to_numpy(float)[ok])
    spd = np.interp(q, tv, vb["reference_speed"].to_numpy(float)[ok] / 3.6)
    hdg_unwrapped = np.degrees(np.unwrap(np.radians(vb["reference_heading"].to_numpy(float)[ok])))
    hdg = np.interp(q, tv, hdg_unwrapped) % 360.0
    with open(out_dir / seq / "truth.csv", "w", encoding="utf-8", newline="\n") as fh:
        fh.write("t_ns,latitude_deg,longitude_deg,speed_m_s,heading_deg\n")
        for i in np.flatnonzero(inside):
            fh.write(f"{int(t_ns[i])},{lat[i]:.8f},{lon[i]:.8f},{spd[i]:.3f},{hdg[i]:.2f}\n")

    # Independent check of the truth timing: the phone's own fixes must sit at GNSS-level
    # distances from the aligned truth. A wrong lag shows up as tens of metres at road speed.
    fix_idx = np.array([f.index for f in fixes if inside[f.index]], dtype=int)
    if len(fix_idx) < 10:
        raise ConversionError("TRUTH_CHECK", f"only {len(fix_idx)} fixes inside the truth span")
    f_lat = np.array([fix_at[i].latitude_deg for i in fix_idx])
    f_lon = np.array([fix_at[i].longitude_deg for i in fix_idx])
    gap = np.hypot(np.radians(f_lat - lat[fix_idx]) * 6_371_000.0,
                   np.radians(f_lon - lon[fix_idx]) * 6_371_000.0 * np.cos(np.radians(lat[fix_idx])))
    gnss_vs_truth = {"median_m": round(float(np.median(gap)), 2),
                     "p90_m": round(float(np.percentile(gap, 90)), 2), "fixes": int(len(gap))}
    if gnss_vs_truth["median_m"] > TRUTH_MAX_MEDIAN_GNSS_GAP_M:
        raise ConversionError("TRUTH_CHECK", f"phone GNSS median {gnss_vs_truth['median_m']} m from truth")

    # Distance from truth, for context only.
    lat_r = np.radians(lat[inside])
    d_n = np.diff(lat_r) * 6_371_000.0
    d_e = np.diff(np.radians(lon[inside])) * 6_371_000.0 * np.cos(lat_r[:-1])
    summary = {
        "sequence": seq,
        "phone_file": phone_path.relative_to(phone_path.parents[3]).as_posix(),
        "vbox_file": vbox_path.relative_to(vbox_path.parents[3]).as_posix(),
        "phone_rows": int(len(t_ms)),
        "phone_rows_dropped": dropped,
        "duration_s": round(duration_s, 2),
        "imu_rate_hz": imu_hz,
        "gnss_fixes": len(fixes),
        "gnss_fix_rate_hz": round(len(fixes) / duration_s, 3),
        "accelerometer_frame": accel_frame,
        "accelerometer_frame_trials": frame_trials,
        "sessions": sessions,
        "gps_speed_unit": {"scale_to_m_s": speed_scale, "reported_over_displacement": round(speed_ratio, 3),
                           "fix_pairs": speed_pairs},
        "yaw_axis": asdict(yaw),
        "truth_alignment": asdict(alignment),
        "phone_gnss_vs_truth": gnss_vs_truth,
        "truth_rows": int(inside.sum()),
        "truth_distance_m": round(float(np.sum(np.hypot(d_n, d_e))), 1),
        "truth_stopped_s": round(float(np.sum(spd[inside] < 0.2)) * duration_s / max(len(t_ms) - 1, 1), 1),
        "input_reconstruction": "levelled accelerometer (as logged, or Earth-aligned export "
                                "rotated back by phone azimuth) as device frame; planar "
                                "gyroscope (measured yaw rate on z, zero roll/pitch rate)",
    }
    (out_dir / seq / "conversion.json").write_text(json.dumps(summary, indent=2) + "\n",
                                                  encoding="utf-8")
    return summary


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--data-root", type=Path, default=DEFAULT_DATA_ROOT)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--sequences", nargs="*", help="sequence ids (default: all pairs)")
    args = parser.parse_args(argv)

    vbox_files, phone_files = load_candidate_filepaths(args.data_root)
    pairs = sorted(set(vbox_files) & set(phone_files))
    if not pairs:
        print(f"no synchronized pairs under {args.data_root}", file=sys.stderr)
        return 2
    wanted = [s.lower() for s in args.sequences] if args.sequences else pairs
    unknown = sorted(set(wanted) - set(pairs))
    if unknown:
        print(f"unknown sequences: {', '.join(unknown)}", file=sys.stderr)
        return 2

    args.out.mkdir(parents=True, exist_ok=True)
    converted, refused = [], []
    for seq in wanted:
        try:
            s = convert_sequence(seq, phone_files[seq], vbox_files[seq], args.out)
            converted.append(s)
            print(f"{seq:7s} ok   {s['duration_s']:8.1f} s  fixes {s['gnss_fixes']:5d}  "
                  f"yaw~{s['yaw_axis']['dominant_column']} z={s['yaw_axis']['course_rate_z_score']:.1f}  "
                  f"truth lag {s['truth_alignment']['lag_s']:+.2f} s "
                  f"r={s['truth_alignment']['correlation']:.2f}")
        except ConversionError as exc:
            refused.append({"sequence": seq, "code": exc.code, "detail": exc.detail})
            print(f"{seq:7s} REFUSED {exc.code}: {exc.detail}")
        except SessionError as exc:
            refused.append({"sequence": seq, "code": f"READBACK_{exc.code}", "detail": str(exc)})
            print(f"{seq:7s} REFUSED readback {exc.code}: {exc}")
    index = {"converted": [s["sequence"] for s in converted], "refused": refused}
    (args.out / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(f"converted {len(converted)}, refused {len(refused)} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
