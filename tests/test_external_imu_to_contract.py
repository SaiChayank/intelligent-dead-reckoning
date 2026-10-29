"""External-IMU adapter and the synthetic FOG generator, on tiny fixtures."""
from __future__ import annotations

import io
import math
import tempfile
import unittest
from pathlib import Path

from contracts.v1.codec import read_jsonl
from contracts.v1.models import CalibrationResult, CalibrationStatus, GnssMeasurement, ImuMeasurement
from tools import external_imu_to_contract as ad

HEADER = "t_s,ax,ay,az,gx,gy,gz,lat,lon,speed_m_s,course_deg,accuracy_m,satellites\n"


def write_csv(dir_: Path, rows: list[str]) -> Path:
    p = dir_ / "log.csv"
    p.write_text(HEADER + "\n".join(rows) + "\n", encoding="utf-8")
    return p


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def convert(self, rows, **kw):
        out = self.tmp / "out.jsonl"
        args = dict(session_id="x", accel_unit="g", gyro_unit="deg/s", mount=[1, 0, 0, 0], gyro_bias=[0, 0, 0.5])
        args.update(kw)
        n = ad.convert(write_csv(self.tmp, rows), out, **args)
        return n, list(read_jsonl(io.BytesIO(out.read_bytes())))

    def test_units_are_converted_and_a_valid_calibration_comes_first(self):
        n, recs = self.convert(["0.0,0.1,0.2,1.0,10,20,30,,,,,,", "0.005,0.1,0.2,1.0,10,20,30,,,,,,"])
        cal = recs[0].event.data
        self.assertIsInstance(cal, CalibrationResult)
        self.assertEqual(cal.status, CalibrationStatus.VALID)
        self.assertAlmostEqual(cal.gyro_bias_rad_s.z, math.radians(0.5))
        accel = [r.event.data for r in recs if isinstance(r.event.data, ImuMeasurement) and r.event.data.sensor.value == "accelerometer"]
        gyro = [r.event.data for r in recs if isinstance(r.event.data, ImuMeasurement) and r.event.data.sensor.value == "gyroscope"]
        self.assertAlmostEqual(accel[0].xyz.z, 9.80665)
        self.assertAlmostEqual(gyro[0].xyz.z, math.radians(30.0))
        self.assertEqual(n, 1 + 2 * 2)
        self.assertLessEqual(recs[0].event.t_ns, recs[1].event.t_ns)

    def test_gnss_is_emitted_only_when_filled_and_event_ids_are_unique(self):
        _, recs = self.convert(["0.0,0,0,1,0,0,0,17.4,78.4,12.0,90,2.5,12", "0.005,0,0,1,0,0,0,,,,,,"])
        fixes = [r.event.data for r in recs if isinstance(r.event.data, GnssMeasurement)]
        self.assertEqual(len(fixes), 1)
        self.assertEqual(fixes[0].speed_m_s, 12.0)
        self.assertEqual(fixes[0].satellites_used, 12)
        ids = [r.event.event_id for r in recs]
        self.assertEqual(len(ids), len(set(ids)))

    def test_refuses_what_it_cannot_trust(self):
        with self.assertRaises(ad.AdapterError):                       # non-increasing time
            self.convert(["0.1,0,0,1,0,0,0,,,,,,", "0.1,0,0,1,0,0,0,,,,,,"])
        with self.assertRaises(ad.AdapterError):                       # not a unit quaternion
            self.convert(["0.0,0,0,1,0,0,0,,,,,,", "0.1,0,0,1,0,0,0,,,,,,"], mount=[2, 0, 0, 0])
        with self.assertRaises(ad.AdapterError):                       # undeclared unit
            self.convert(["0.0,0,0,1,0,0,0,,,,,,", "0.1,0,0,1,0,0,0,,,,,,"], accel_unit="mg")


class GeneratorTest(unittest.TestCase):
    def test_generated_log_round_trips_through_the_adapter_at_200_hz(self):
        from tools import generate_fog_200hz as gen
        tmp = Path(tempfile.mkdtemp())
        csv_path = tmp / "fog.csv"
        import sys
        old = sys.argv
        sys.argv = ["gen", str(csv_path), "--seconds", "12"]
        try:
            gen.main()
        finally:
            sys.argv = old
        out = tmp / "fog.jsonl"
        ad.convert(csv_path, out, "fog", "g", "deg/s", [1, 0, 0, 0], [0, 0, 0])
        recs = list(read_jsonl(io.BytesIO(out.read_bytes())))
        imu_t = [r.event.t_ns for r in recs if isinstance(r.event.data, ImuMeasurement)
                 and r.event.data.sensor.value == "accelerometer"]
        self.assertAlmostEqual(1e9 / (imu_t[1] - imu_t[0]), 200.0, places=3)
        self.assertEqual(sum(isinstance(r.event.data, GnssMeasurement) for r in recs), 12)


if __name__ == "__main__":
    unittest.main()
