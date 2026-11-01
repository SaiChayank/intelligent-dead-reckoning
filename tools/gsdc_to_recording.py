"""Convert Google Smartphone Decimeter Challenge 2023-2024 training trips into app-format recordings that the engine replays like a phone session.

    python tools/gsdc_to_recording.py --split held_out            # one best phone per held-out drive
    python tools/gsdc_to_recording.py --drives 2023-09-07-19-33-us-ca --all-phones

Per trip, under --out (default data/processed/gsdc2023, untracked), `<drive>/<recording-id>/` holds `metadata.json` and `measurements.jsonl`
(recording contract 1.0.0, source "real"), plus `conversion.json` per drive with every choice and its evidence.

What goes in, and what is stated rather than hidden:
* IMU: the phone's own accelerometer and gyroscope rows, one record per row at the row's own time, device frame. The gyroscope is the
  uncalibrated reading minus its logged bias when one is logged (what Android's calibrated sensor reports). Nothing is levelled or rotated; the
  mount is left to the engine's calibration.
* GNSS aiding: the 1 Hz reference fixes (NovAtel SPAN, 0.1 m) as provider "gps" with a DECLARED accuracy of 2.5 m, the same stated assumption the
  IO-VNBD harness makes for VBOX, and a declared satellite count of 12 (the engine rejects fixes with fewer than 4 and the reference has none). The
  phone's own GNSS position is not part of this dataset. So this tests outage drift with good aiding before
  and after the outage, not the phone receiver's errors.
* Clock: the phone timestamps (UTC ms after an NTP sync) are shifted onto the reference clock by a lag found from the phone gyro norm against the
  reference yaw rate (sensor-to-sensor, no position involved). Trips whose match is weak are reported and skipped.
* Split: held-out drives are fixed by a hash of the drive name BEFORE any result was looked at (see `split_of`), and a drive contributes one phone so
  that phones riding in the same car are not counted as independent trips.
The raw data is CC BY-NC-SA 3.0 IGO; cite Fu, Khider, Gulati, van Diggelen, "Google Smartphone Decimeter Challenge" (2023),
https://kaggle.com/competitions/smartphone-decimeter-2023/data, and Fu, Khider, van Diggelen, ION GNSS+ 2020, https://doi.org/10.33012/2020.17628.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from contracts.recording.v1.codec import encode_metadata, encode_record  # noqa: E402
from contracts.recording.v1.models import (  # noqa: E402
    ApplicationInfo, CalibrationApplication, CalibrationInfo, ChannelCount, ClockDomain, ClockIdentity, CompletionState, DeviceInfo,
    LocationPermissionState, RecordingEndState, RecordingMetadata, RecordingStartState, RecoveryState, SensorDescriptor, SourceConfiguration,
)
from contracts.recording.v1.session import read_session  # noqa: E402
from contracts.v1.models import (  # noqa: E402
    AltitudeReference, DeviceFrame, Event, GnssMeasurement, Header, ImuMeasurement, ImuUnit, Record, Sensor, SensorAccuracy, Source, Vector3,
)

DEFAULT_ROOT = ROOT / "data" / "raw" / "gsdc2023"
DEFAULT_OUT = ROOT / "data" / "processed" / "gsdc2023"
ORIGIN_NS = 1_000_000_000
DECLARED_ACCURACY_M = 2.5
DECLARED_SATELLITES = 12        # the reference track has no satellite count; the engine refuses fixes with fewer than 4, so a plausible value is declared
LAG_MAX_S = 8.0
LAG_STEP_S = 0.05
LAG_MIN_CORRELATION = 0.5
LAG_MIN_SPEED_M_S = 3.0
HELD_OUT_MODULUS = 3          # a drive is held out when sha256(drive) mod 3 == 0


def split_of(drive: str) -> str:
    """'held_out' or 'development', fixed by the drive name alone."""
    return "held_out" if int(hashlib.sha256(drive.encode("utf-8")).hexdigest(), 16) % HELD_OUT_MODULUS == 0 else "development"


def load_imu(phone_dir: Path) -> dict[str, np.ndarray]:
    """Per sensor, an array of [utc_s, x, y, z], sorted, strictly increasing in time (duplicate timestamps dropped)."""
    imu = pd.read_csv(phone_dir / "device_imu.csv", usecols=["MessageType", "utcTimeMillis", "MeasurementX", "MeasurementY", "MeasurementZ",
                                                             "BiasX", "BiasY", "BiasZ"])
    out = {}
    for kind, name in (("UncalAccel", "accelerometer"), ("UncalGyro", "gyroscope")):
        rows = imu[imu.MessageType == kind]
        xyz = rows[["MeasurementX", "MeasurementY", "MeasurementZ"]].to_numpy(float)
        if kind == "UncalGyro":
            bias = rows[["BiasX", "BiasY", "BiasZ"]].to_numpy(float)
            xyz = xyz - np.where(np.isfinite(bias), bias, 0.0)
        t = rows.utcTimeMillis.to_numpy(float) / 1000.0
        order = np.argsort(t, kind="stable")
        t, xyz = t[order], xyz[order]
        keep = np.concatenate([[True], np.diff(t) > 0]) & np.isfinite(xyz).all(axis=1)
        out[name] = np.column_stack([t[keep], xyz[keep]])
    return out


def load_truth(phone_dir: Path) -> dict[str, np.ndarray]:
    truth = pd.read_csv(phone_dir / "ground_truth.csv")
    t = truth.UnixTimeMillis.to_numpy(float) / 1000.0
    keep = np.concatenate([[True], np.diff(t) > 0])
    pick = lambda column: truth[column].to_numpy(float)[keep]
    return {"t": t[keep], "lat": pick("LatitudeDegrees"), "lon": pick("LongitudeDegrees"), "alt": pick("AltitudeMeters"),
            "speed": pick("SpeedMps"), "bearing": pick("BearingDegrees")}


def estimate_lag(gyro: np.ndarray, truth: dict[str, np.ndarray]) -> dict:
    """Shift to ADD to phone times so they sit on the reference clock, from phone gyro norm against reference yaw rate."""
    t, b, v = truth["t"], truth["bearing"], truth["speed"]
    dt = np.diff(t)
    yaw = np.abs((np.diff(b) + 180.0) % 360.0 - 180.0) / dt * (np.pi / 180.0)
    mid = 0.5 * (t[1:] + t[:-1])
    moving = (0.5 * (v[1:] + v[:-1]) > LAG_MIN_SPEED_M_S) & (dt < 2.5) & np.isfinite(yaw)
    g_t, g_n = gyro[:, 0], np.linalg.norm(gyro[:, 1:], axis=1)
    csum = np.concatenate([[0.0], np.cumsum(g_n)])
    best = {"lag_s": 0.0, "correlation": -1.0}
    scan = []
    for lag in np.arange(-LAG_MAX_S, LAG_MAX_S + 1e-9, LAG_STEP_S):
        lo = np.searchsorted(g_t, mid[moving] - lag - 0.5)
        hi = np.searchsorted(g_t, mid[moving] - lag + 0.5)
        ok = hi > lo
        if ok.sum() < 30:
            scan.append(np.nan)
            continue
        mean = (csum[hi[ok]] - csum[lo[ok]]) / (hi[ok] - lo[ok])
        c = float(np.corrcoef(mean, yaw[moving][ok])[0, 1])
        scan.append(c)
        if c > best["correlation"]:
            best = {"lag_s": round(float(lag), 3), "correlation": round(c, 3)}
    scan = np.array(scan)
    best["correlation_at_zero_lag"] = round(float(scan[int(round(LAG_MAX_S / LAG_STEP_S))]), 3) if np.isfinite(scan).any() else None
    best["moving_intervals"] = int(moving.sum())
    return best


def build_records(session_id: str, imu: dict[str, np.ndarray], truth: dict[str, np.ndarray], lag_s: float) -> tuple[list[Record], int]:
    header = Header(session_id, Source.REAL, "1.0.0")
    base = min(imu["accelerometer"][0, 0], imu["gyroscope"][0, 0]) + lag_s
    base = min(base, truth["t"][0])
    last = max(imu["accelerometer"][-1, 0], imu["gyroscope"][-1, 0]) + lag_s
    events = []     # (t_ns, order, payload); order keeps IMU before an equal-time fix
    for name, sensor, unit in (("accelerometer", Sensor.ACCELEROMETER, ImuUnit.METRES_PER_SECOND_SQUARED),
                               ("gyroscope", Sensor.GYROSCOPE, ImuUnit.RADIANS_PER_SECOND)):
        for row in imu[name]:
            t_ns = ORIGIN_NS + int(round((row[0] + lag_s - base) * 1e9))
            events.append((t_ns, 0, ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE, unit, Vector3(float(row[1]), float(row[2]), float(row[3])),
                                                  SensorAccuracy.UNKNOWN)))
    fixes = 0
    for k in range(len(truth["t"])):
        if not (base <= truth["t"][k] <= last):
            continue
        speed = truth["speed"][k]
        bearing = truth["bearing"][k]
        events.append((ORIGIN_NS + int(round((truth["t"][k] - base) * 1e9)), 1, GnssMeasurement(
            float(truth["lat"][k]), float(truth["lon"][k]), float(truth["alt"][k]) if np.isfinite(truth["alt"][k]) else None,
            AltitudeReference.ELLIPSOID if np.isfinite(truth["alt"][k]) else None,
            float(speed) if np.isfinite(speed) and speed >= 0 else None, float(bearing) % 360.0 if np.isfinite(bearing) else None,
            DECLARED_ACCURACY_M, None, DECLARED_SATELLITES, "gps", None)))
        fixes += 1
    events.sort(key=lambda e: (e[0], e[1]))
    return [Record(header, Event(str(i + 1), t, t, payload)) for i, (t, _, payload) in enumerate(events)], fixes


def write_recording(out_dir: Path, session_id: str, device: str, records: list[Record], fixes: int, rates: dict[str, float]) -> Path:
    t_last = records[-1].event.t_ns
    metadata = RecordingMetadata(
        recording_id=session_id, acquisition_session_id=session_id, source=Source.REAL, start_state=RecordingStartState.RECORDING,
        end_state=RecordingEndState.STOPPED, completion_state=CompletionState.COMPLETED, recovery_state=RecoveryState.NONE,
        created_utc_ms=None, started_utc_ms=None, ended_utc_ms=None,
        clock=ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS, None, f"{session_id}-clock", ORIGIN_NS, ORIGIN_NS, t_last),
        device=DeviceInfo(None, device, None, None, None), application=ApplicationInfo(None, None, None, "gsdc_to_recording"),
        sensors=(SensorDescriptor(Sensor.ACCELEROMETER, "GSDC device_imu UncalAccel", None, True, None, rates["accelerometer"]),
                 SensorDescriptor(Sensor.GYROSCOPE, "GSDC device_imu UncalGyro minus logged bias", None, True, None, rates["gyroscope"])),
        source_configuration=SourceConfiguration(LocationPermissionState.PRECISE, True, True, None, None, True),
        calibration=CalibrationInfo(CalibrationApplication.NOT_APPLIED, None), record_count=len(records),
        channel_counts=(ChannelCount("imu", len(records) - fixes), ChannelCount("gnss", fixes)))
    session_dir = out_dir / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    (session_dir / "metadata.json").write_bytes(encode_metadata(metadata))
    with open(session_dir / "measurements.jsonl", "wb") as handle:
        for record in records:
            handle.write(encode_record(record, metadata) + b"\n")
    if sum(1 for _ in read_session(session_dir)) != len(records):
        raise SystemExit(f"{session_id}: read-back count differs")
    return session_dir


def ranked_phones(drive_dir: Path) -> list[str]:
    """The phones of a drive, highest IMU rate first (ties: name order). Needs reports/gsdc_survey.json."""
    survey = json.loads((ROOT / "reports" / "gsdc_survey.json").read_text(encoding="utf-8"))["trips"]
    mine = [t for t in survey if t["drive"] == drive_dir.name]
    mine.sort(key=lambda t: (-min(t["accelerometer"]["rate_hz"] or 0, t["gyroscope"]["rate_hz"] or 0), t["phone"]))
    return [t["phone"] for t in mine]


def convert_trip(drive: str, phone: str, root: Path, out: Path) -> dict:
    phone_dir = root / "train" / drive / phone
    imu, truth = load_imu(phone_dir), load_truth(phone_dir)
    lag = estimate_lag(imu["gyroscope"], truth)
    info = {"drive": drive, "phone": phone, "split": split_of(drive), "alignment": lag}
    if lag["correlation"] < LAG_MIN_CORRELATION or abs(lag["lag_s"]) >= LAG_MAX_S - LAG_STEP_S:
        return {**info, "converted": False, "reason": "clock alignment is weak or at the edge of the search window"}
    session_id = f"gsdc-{drive}-{phone}"
    records, fixes = build_records(session_id, imu, truth, lag["lag_s"])
    rates = {k: round((len(v) - 1) / (v[-1, 0] - v[0, 0]), 2) for k, v in imu.items()}
    write_recording(out / drive, session_id, phone, records, fixes, rates)
    return {**info, "converted": True, "recording_id": session_id, "records": len(records), "gnss_fixes": fixes, "imu_rates_hz": rates,
            "declared_gnss_accuracy_m": DECLARED_ACCURACY_M}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--split", choices=("held_out", "development", "all"), default="held_out")
    parser.add_argument("--drives", nargs="*", help="only these drive folder names")
    parser.add_argument("--all-phones", action="store_true", help="every phone of each drive (default: the best one)")
    args = parser.parse_args(argv)
    drives = sorted(p.name for p in (args.root / "train").iterdir() if p.is_dir())
    if args.drives:
        drives = [d for d in drives if d in set(args.drives)]
    elif args.split != "all":
        drives = [d for d in drives if split_of(d) == args.split]
    report = []
    for drive in drives:
        phones = sorted(p.name for p in (args.root / "train" / drive).iterdir() if p.is_dir()) if args.all_phones else ranked_phones(args.root / "train" / drive)
        for phone in phones:
            result = convert_trip(drive, phone, args.root, args.out)
            report.append(result)
            print(f"{drive}/{phone}: " + (f"{result['records']} records, lag {result['alignment']['lag_s']} s (r={result['alignment']['correlation']})"
                                          if result["converted"] else f"SKIPPED ({result['reason']}; r={result['alignment']['correlation']})"), flush=True)
            if result["converted"] and not args.all_phones:
                break       # one phone per drive: the best one that aligns; a weaker phone is tried only when the better one is skipped
    args.out.mkdir(parents=True, exist_ok=True)
    name = "conversion_" + ("drives" if args.drives else args.split) + ".json"
    (args.out / name).write_text(json.dumps({"trips": report}, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
