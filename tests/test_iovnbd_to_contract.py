"""IO-VNBD -> contract converter: synthetic fixtures with known answers, no dataset needed."""
from __future__ import annotations

import math
import shutil
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from contracts.recording.v1.session import read_session
from contracts.v1.models import GnssMeasurement, ImuMeasurement
from tools import iovnbd_to_contract as conv


def straight_then_turns(duration_s: float = 600.0, hz: float = 10.0, fix_every_s: float = 1.0):
    """Planar drive at varying speed with alternating gentle turns; returns per-sample truth."""
    t = np.arange(0.0, duration_s, 1.0 / hz)
    speed = 12.0 + 4.0 * np.sin(2 * math.pi * t / 47.0)                  # m/s, always moving
    yaw_rate = 0.08 * np.sin(2 * math.pi * t / 83.0)                     # rad/s, CCW positive
    heading = np.cumsum(yaw_rate) / hz                                   # rad, CCW from east
    a_long = np.gradient(speed, t)
    a_lat = speed * yaw_rate
    east = np.cumsum(speed * np.cos(heading)) / hz
    north = np.cumsum(speed * np.sin(heading)) / hz
    lat = 52.4 + np.degrees(north / 6_371_000.0)
    lon = -1.5 + np.degrees(east / (6_371_000.0 * math.cos(math.radians(52.4))))
    course_deg = (90.0 - np.degrees(heading)) % 360.0                    # clockwise from north
    fix_idx = np.arange(0, len(t), int(round(fix_every_s * hz)))
    return {"t": t, "speed": speed, "yaw_rate": yaw_rate, "a_long": a_long, "a_lat": a_lat,
            "lat": lat, "lon": lon, "course": course_deg, "fix_idx": fix_idx}


def fixes_from(d: dict) -> list:
    return [conv.Fix(int(i), float(d["lat"][i]), float(d["lon"][i]), 100.0,
                     float(d["speed"][i]), float(d["course"][i]), 3.0, 12)
            for i in d["fix_idx"]]


class ParsingTest(unittest.TestCase):
    def test_satellites_keep_used_count(self):
        self.assertEqual(conv.parse_satellites("18 / 19"), 18)
        self.assertEqual(conv.parse_satellites("7"), 7)
        self.assertIsNone(conv.parse_satellites("n/a"))

    def test_fixes_are_deduplicated_and_speed_scaled(self):
        df = pd.DataFrame({
            "gps_latitude": [52.0, 52.0, 52.0001, 0.0, 52.0002],
            "gps_longitude": [-1.0, -1.0, -1.0, 0.0, -1.0],
            "gps_altitude": [100.0] * 5,
            "gps_speed_raw": [36.0, 36.0, 36.0, 36.0, 36.0],
            "gps_accuracy": [4.0] * 5,
            "gps_course": [370.0, 370.0, 10.0, 10.0, 10.0],
            "gps_satellites_raw": ["9 / 12"] * 5,
        })
        fixes = conv.extract_fixes(df, speed_scale=1 / 3.6)
        self.assertEqual([f.index for f in fixes], [0, 2, 4])   # repeats and (0, 0) dropped
        self.assertAlmostEqual(fixes[0].speed_m_s, 10.0)
        self.assertAlmostEqual(fixes[0].course_deg, 10.0)
        self.assertEqual(fixes[0].satellites_used, 9)


class SpeedUnitTest(unittest.TestCase):
    def test_metres_per_second_and_kmh_are_told_apart(self):
        d = straight_then_turns(duration_s=200.0)
        t = d["t"]
        fixes = fixes_from(d)
        scale, ratio, n = conv.infer_speed_scale(t, fixes)
        self.assertEqual(scale, 1.0)
        self.assertAlmostEqual(ratio, 1.0, delta=0.05)
        kmh = [conv.Fix(f.index, f.latitude_deg, f.longitude_deg, f.altitude_m, f.speed_m_s * 3.6,
                        f.course_deg, f.accuracy_m, f.satellites_used) for f in fixes]
        self.assertAlmostEqual(conv.infer_speed_scale(t, kmh)[0], 1 / 3.6)

    def test_unexplained_ratio_is_refused(self):
        d = straight_then_turns(duration_s=200.0)
        odd = [conv.Fix(f.index, f.latitude_deg, f.longitude_deg, f.altitude_m, f.speed_m_s * 2.0,
                        f.course_deg, f.accuracy_m, f.satellites_used) for f in fixes_from(d)]
        with self.assertRaises(conv.ConversionError) as ctx:
            conv.infer_speed_scale(d["t"], odd)
        self.assertEqual(ctx.exception.code, "SPEED_UNIT")


class YawAxisTest(unittest.TestCase):
    def test_tilted_mount_axis_and_sign_are_recovered(self):
        d = straight_then_turns(duration_s=900.0, fix_every_s=9.0)
        rng = np.random.default_rng(1)
        # Vehicle yaw appears mostly on the second column, negated, tilted into the third.
        axis = np.array([0.0, -0.94, 0.34])
        noise = rng.normal(0.0, 0.01, (len(d["t"]), 3))
        samples = np.outer(d["yaw_rate"], axis) + noise
        gyro = dict(zip(conv.GYRO_CHANNELS, samples.T))
        yaw = conv.select_yaw_axis(d["t"], gyro, fixes_from(d))
        recovered = samples @ np.array(yaw.axis)
        self.assertGreater(np.corrcoef(recovered, d["yaw_rate"])[0, 1], 0.95)   # right sign too
        self.assertEqual(yaw.dominant_column, "gyro_channel_pitch")


class BatchCalibrationTest(unittest.TestCase):
    def test_known_mount_angle_and_bias_are_recovered(self):
        d = straight_then_turns(duration_s=600.0)
        theta_true = math.radians(37.0)
        veh = d["a_long"] + 1j * d["a_lat"]
        dev = np.exp(-1j * theta_true) * veh                  # v_vehicle = e^{i theta} v_device
        accel = np.column_stack([dev.real, dev.imag, np.full(len(d["t"]), 9.81)])
        bias = 0.004
        yaw_z = d["yaw_rate"] + bias
        gnss = [(conv.ORIGIN_NS + int(round(d["t"][i] * 1e9)), GnssMeasurement(
            float(d["lat"][i]), float(d["lon"][i]), None, None, float(d["speed"][i]),
            float(d["course"][i]), 2.5, None, 10, "gps", None)) for i in d["fix_idx"]]
        cal = conv.batch_mount_calibration(d["t"], accel, yaw_z, gnss)
        self.assertEqual(cal["status"], "valid")
        self.assertAlmostEqual(cal["theta_deg"], 37.0, delta=2.0)
        self.assertAlmostEqual(cal["gyro_bias_rad_s"][2], bias, delta=0.003)
        w, x, y, z = cal["q_vehicle_from_device_wxyz"]
        self.assertAlmostEqual(w * w + x * x + y * y + z * z, 1.0, places=9)
        self.assertEqual(cal["available_s"], 300.0)            # released at the window end

    def test_unrelated_accelerometer_is_refused(self):
        d = straight_then_turns(duration_s=600.0)
        rng = np.random.default_rng(2)
        accel = np.column_stack([rng.normal(0, 1, len(d["t"])), rng.normal(0, 1, len(d["t"])),
                                 np.full(len(d["t"]), 9.81)])
        gnss = [(conv.ORIGIN_NS + int(round(d["t"][i] * 1e9)), GnssMeasurement(
            float(d["lat"][i]), float(d["lon"][i]), None, None, float(d["speed"][i]),
            float(d["course"][i]), 2.5, None, 10, "gps", None)) for i in d["fix_idx"]]
        self.assertEqual(conv.batch_mount_calibration(d["t"], accel, d["yaw_rate"], gnss)["status"],
                         "failed")


class RecordingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_recording_round_trips_with_gnss_merged_in_time_order(self):
        n = 50
        t_ns = conv.ORIGIN_NS + np.arange(n, dtype=np.int64) * 100_000_000
        accel = np.tile([0.1, 0.2, 9.8], (n, 1))
        yaw = np.linspace(-0.1, 0.1, n)
        fix = GnssMeasurement(52.0, -1.0, None, None, 10.0, 90.0, 2.5, None, 8, "gps", None)
        gnss = [(int(t_ns[10]), fix), (int(t_ns[20]) + 50_000_000, fix)]   # on and between rows
        written = conv.write_recording(self.tmp, "x", "iovnbd-x", t_ns, accel, yaw, gnss, 10.0, "test")
        self.assertEqual(written, 2)
        records = list(read_session(self.tmp / "x" / "iovnbd-x"))
        self.assertEqual(len(records), 2 * n + 2)
        times = [r.event.t_ns for r in records]
        self.assertEqual(times, sorted(times))
        gyro = [r.event.data for r in records
                if isinstance(r.event.data, ImuMeasurement) and r.event.data.sensor.value == "gyroscope"]
        self.assertTrue(all(g.xyz.x == 0.0 and g.xyz.y == 0.0 for g in gyro))   # planar gyroscope
        on_row = [i for i, r in enumerate(records) if isinstance(r.event.data, GnssMeasurement)][0]
        self.assertEqual(records[on_row - 1].event.t_ns, int(t_ns[10]))          # after that row's IMU

    def test_vbox_fixes_are_decimated_to_one_hertz_on_the_phone_clock(self):
        t_vbox = np.arange(0.0, 10.0, 0.1)
        vb = pd.DataFrame({
            "reference_latitude": np.full(len(t_vbox), 52.0),
            "reference_longitude": np.full(len(t_vbox), -1.0),
            "reference_speed": np.full(len(t_vbox), 36.0),
            "reference_heading": np.full(len(t_vbox), 370.0),
            "reference_satellites_raw": np.full(len(t_vbox), 11),
        })
        events = conv.vbox_gnss_events(vb, t_vbox, lag_s=2.0, t_phone_end_s=100.0)
        times_s = [(t - conv.ORIGIN_NS) / 1e9 for t, _ in events]
        self.assertEqual(times_s, [float(s) - 2.0 for s in range(2, 10)])   # t_phone = t_vbox - lag
        self.assertAlmostEqual(events[0][1].speed_m_s, 10.0)
        self.assertAlmostEqual(events[0][1].bearing_deg, 10.0)
        self.assertEqual(events[0][1].horizontal_accuracy_m, conv.VBOX_DECLARED_ACCURACY_M)


class VehicleInertialTest(unittest.TestCase):
    def frame(self, d, yaw_scale=1.0, lat_scale=1.0):
        return pd.DataFrame({
            "reference_speed": d["speed"] * 3.6,
            "reference_heading": d["course"],
            "vehicle_yaw_rate": np.degrees(d["yaw_rate"]) * yaw_scale,
            "vehicle_accel_long": d["a_long"] / 9.80665,
            "vehicle_accel_lat": d["a_lat"] / 9.80665 * lat_scale,
        })

    def test_channels_become_a_vehicle_frame_imu_with_signs_fixed_from_vbox_only(self):
        d = straight_then_turns(duration_s=300.0)
        for yaw_scale, lat_scale in [(1.0, 1.0), (-1.0, -1.0)]:       # opposite-convention exports
            out = conv.vehicle_frame_inertial(d["t"], d["t"], 0.0, self.frame(d, yaw_scale, lat_scale))
            self.assertAlmostEqual(float(np.corrcoef(out["yaw_z"], d["yaw_rate"])[0, 1]), 1.0, places=2)
            self.assertAlmostEqual(float(np.corrcoef(out["accel"][:, 1], d["a_lat"])[0, 1]), 1.0, places=2)
            np.testing.assert_allclose(out["accel"][:, 0], d["a_long"], atol=1e-6)
            np.testing.assert_allclose(out["accel"][:, 2], 9.80665)

    def test_lag_moves_samples_onto_the_phone_clock(self):
        d = straight_then_turns(duration_s=300.0)
        out = conv.vehicle_frame_inertial(d["t"][:2000], d["t"], 5.0, self.frame(d))
        np.testing.assert_allclose(out["accel"][:, 0], np.interp(d["t"][:2000] + 5.0, d["t"], d["a_long"]), atol=1e-6)

    def test_unrelated_yaw_channel_is_refused(self):
        d = straight_then_turns(duration_s=300.0)
        bad = self.frame(d)
        bad["vehicle_yaw_rate"] = np.random.default_rng(3).normal(0, 5, len(d["t"]))
        with self.assertRaises(conv.ConversionError) as ctx:
            conv.vehicle_frame_inertial(d["t"], d["t"], 0.0, bad)
        self.assertEqual(ctx.exception.code, "VEHICLE_IMU")

    def test_calibration_is_identity_with_standstill_bias(self):
        t = np.arange(0.0, 200.0, 0.1)
        yaw = np.full(len(t), 0.003)
        fix = lambda sec, v: (conv.ORIGIN_NS + int(sec * 1e9),
                              GnssMeasurement(52.0, -1.0, None, None, v, 0.0, 2.5, None, 9, "gps", None))
        gnss = [fix(s, 0.0) for s in range(1, 20)] + [fix(s, 10.0) for s in range(20, 200)]
        cal = conv.vehicle_imu_calibration(t, yaw, gnss)
        self.assertEqual(cal["q_vehicle_from_device_wxyz"], [1.0, 0.0, 0.0, 0.0])
        self.assertAlmostEqual(cal["gyro_bias_rad_s"][2], 0.003, places=6)
        self.assertEqual(cal["gyro_bias_method"], "median at GNSS standstill")


if __name__ == "__main__":
    unittest.main()
