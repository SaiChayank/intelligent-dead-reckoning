"""Turn an external IMU log into a contract 1.1.0 stream the edge engine accepts.

    python tools/external_imu_to_contract.py log.csv out.jsonl \
        --accel-unit g --gyro-unit deg/s --mount-quat 1 0 0 0 --gyro-bias 0 0 0.0001

CSV columns (header required, any order, extra columns ignored):
    t_s                      seconds on one clock, strictly increasing
    ax ay az                 specific force
    gx gy gz                 angular rate
    lat lon speed_m_s course_deg accuracy_m satellites   optional GNSS fix; a row carries a fix only when lat and lon are filled.
                             The engine's quality gate refuses a GPS fix without accuracy and a satellite count.

Units are declared, never guessed: --accel-unit m/s^2|g, --gyro-unit rad/s|deg/s. Values are converted to the
contract's m/s^2 and rad/s. The mount is the unit quaternion (w x y z) of the rotation from the sensor frame to
the vehicle frame (x forward, y left, z up); the contract's only frame label is `android_device`, which here
means "the sensor's own axes". The output starts with one VALID calibration record carrying that mount and the
declared gyro bias (the edge engine requires one before any measurement), then IMU and GNSS records.

This adapter does not infer a mount, a bias or a unit. A log whose mount is unknown must be calibrated first.
"""
from __future__ import annotations

import argparse
import csv
import math
import sys
from pathlib import Path
from typing import Iterator, Sequence

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from contracts.v1.codec import write_jsonl  # noqa: E402
from contracts.v1.models import (  # noqa: E402
    AltitudeReference, CalibrationResult, CalibrationStatus, DeviceFrame, Event, GnssMeasurement, Header,
    ImuMeasurement, ImuUnit, Quaternion, Record, Sensor, SensorAccuracy, Source, Vector3,
)

G = 9.80665
ACCEL_UNITS = {"m/s^2": 1.0, "g": G}
GYRO_UNITS = {"rad/s": 1.0, "deg/s": math.pi / 180.0}
NS = 1_000_000_000


class AdapterError(ValueError):
    pass


def _unit_quaternion(q: Sequence[float]) -> Quaternion:
    w, x, y, z = q
    if abs(math.sqrt(w * w + x * x + y * y + z * z) - 1.0) > 1e-6:
        raise AdapterError("mount quaternion must have unit norm")
    return Quaternion(w, x, y, z)


def records(rows: Iterator[dict], session_id: str, accel_scale: float, gyro_scale: float, mount: Quaternion,
            gyro_bias_rad_s: Vector3, origin_ns: int | None = None,
            accel_bias_m_s2: Vector3 | None = None) -> Iterator[Record]:
    header = Header(session_id, Source.REAL, "1.1.0")
    last_t = None
    event_id = 1
    first = True
    for line, row in enumerate(rows, start=2):
        try:
            t_ns = int(round(float(row["t_s"]) * NS))
            a = [float(row[k]) * accel_scale for k in ("ax", "ay", "az")]
            w = [float(row[k]) * gyro_scale for k in ("gx", "gy", "gz")]
        except (KeyError, ValueError) as exc:
            raise AdapterError(f"line {line}: {exc}") from None
        if not all(math.isfinite(v) for v in a + w):
            raise AdapterError(f"line {line}: non-finite sample")
        if last_t is not None and t_ns <= last_t:
            raise AdapterError(f"line {line}: time must increase")
        last_t = t_ns
        if first:
            origin = t_ns if origin_ns is None else origin_ns
            cal = CalibrationResult("external-imu-declared", CalibrationStatus.VALID, mount, gyro_bias_rad_s,
                                    accel_bias_m_s2, None)
            yield Record(header, Event("0", origin, origin, cal))
            first = False

        def event(payload):
            nonlocal event_id
            r = Record(header, Event(str(event_id), t_ns, t_ns, payload))
            event_id += 1
            return r

        yield event(ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE, ImuUnit.METRES_PER_SECOND_SQUARED,
                                   Vector3(*a), SensorAccuracy.HIGH))
        yield event(ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE, ImuUnit.RADIANS_PER_SECOND,
                                   Vector3(*w), SensorAccuracy.HIGH))
        if row.get("lat") not in (None, "") and row.get("lon") not in (None, ""):
            def opt(name):
                v = row.get(name)
                return float(v) if v not in (None, "") else None
            sats = opt("satellites")
            yield event(GnssMeasurement(float(row["lat"]), float(row["lon"]), None, None, opt("speed_m_s"),
                                        opt("course_deg"), opt("accuracy_m"), None,
                                        None if sats is None else int(sats), "gps", None))


def convert(csv_path: Path, out_path: Path, session_id: str, accel_unit: str, gyro_unit: str,
            mount: Sequence[float], gyro_bias: Sequence[float], accel_bias: Sequence[float] | None = None) -> int:
    if accel_unit not in ACCEL_UNITS or gyro_unit not in GYRO_UNITS:
        raise AdapterError("units must be declared: accel m/s^2|g, gyro rad/s|deg/s")
    q = _unit_quaternion(mount)
    bias = Vector3(*[float(b) * GYRO_UNITS[gyro_unit] for b in gyro_bias])
    accel_bias = None if accel_bias is None else Vector3(*[float(b) * ACCEL_UNITS[accel_unit] for b in accel_bias])
    with open(csv_path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        missing = {"t_s", "ax", "ay", "az", "gx", "gy", "gz"} - set(reader.fieldnames or [])
        if missing:
            raise AdapterError(f"missing columns: {sorted(missing)}")
        recs = list(records(reader, session_id, ACCEL_UNITS[accel_unit], GYRO_UNITS[gyro_unit], q, bias,
                            accel_bias_m_s2=accel_bias))
    if len(recs) < 3:
        raise AdapterError("no samples")
    with open(out_path, "wb") as out:
        write_jsonl(recs, out)
    return len(recs)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("csv", type=Path)
    p.add_argument("out", type=Path)
    p.add_argument("--session-id", default="external-imu")
    p.add_argument("--accel-unit", required=True, choices=sorted(ACCEL_UNITS))
    p.add_argument("--gyro-unit", required=True, choices=sorted(GYRO_UNITS))
    p.add_argument("--mount-quat", nargs=4, type=float, required=True, metavar=("W", "X", "Y", "Z"))
    p.add_argument("--gyro-bias", nargs=3, type=float, default=[0.0, 0.0, 0.0], metavar=("X", "Y", "Z"),
                   help="in the declared gyro unit")
    p.add_argument("--accel-bias", nargs=3, type=float, default=None, metavar=("X", "Y", "Z"),
                   help="declared accelerometer bias in the declared accel unit; omitted means unknown, which the engine "
                        "treats with a ten times looser prior (see reports/gap_closure_log.md, T2)")
    a = p.parse_args(argv)
    try:
        n = convert(a.csv, a.out, a.session_id, a.accel_unit, a.gyro_unit, a.mount_quat, a.gyro_bias, a.accel_bias)
    except AdapterError as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2
    print(f"wrote {n} records to {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
