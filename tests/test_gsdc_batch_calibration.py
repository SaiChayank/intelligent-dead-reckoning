import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import gsdc_batch_calibration as cal  # noqa: E402

G = 9.80665


def rotation(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    """R_vehicle_from_device built as Rz(yaw) Ry(pitch) Rx(roll): an arbitrary, awkward phone mount."""
    y, p, r = np.radians([yaw_deg, pitch_deg, roll_deg])
    rz = np.array([[np.cos(y), -np.sin(y), 0], [np.sin(y), np.cos(y), 0], [0, 0, 1]])
    ry = np.array([[np.cos(p), 0, np.sin(p)], [0, 1, 0], [-np.sin(p), 0, np.cos(p)]])
    rx = np.array([[1, 0, 0], [0, np.cos(r), -np.sin(r)], [0, np.sin(r), np.cos(r)]])
    return rz @ ry @ rx


def matrix_from_quaternion(q) -> np.ndarray:
    w, x, y, z = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def write_drive(directory: Path, r_true: np.ndarray, bias_device: np.ndarray, stop_s: float = 12.0, seconds: float = 300.0, hz: float = 50.0,
                seed: int = 3) -> None:
    """Straight-ish driving with speed changes and turns; the phone sits at r_true (vehicle <- device) with a gyro bias."""
    rng = np.random.default_rng(seed)
    t = np.arange(0.0, seconds, 1.0 / hz)
    ramp = np.clip((t - stop_s) / 8.0, 0.0, 1.0)
    v = ramp * (12.0 + 6.0 * np.sin(2 * np.pi * (t - stop_s) / 40.0))
    a_long = np.gradient(v, t)
    yaw_rate = ramp * 0.25 * np.sin(2 * np.pi * (t - stop_s) / 27.0)
    a_vehicle = np.column_stack([a_long, v * yaw_rate, np.full_like(t, G)])        # specific force: gravity shows as +up
    accel = a_vehicle @ r_true + rng.normal(0, 0.05, (len(t), 3))                    # v_device = R^T v_vehicle -> row form: v_vehicle @ R
    gyro = np.column_stack([np.zeros_like(t), np.zeros_like(t), yaw_rate]) @ r_true + bias_device + rng.normal(0, 0.003, (len(t), 3))
    lines = []
    for i in range(len(t)):
        t_ns = 1_000_000_000 + int(round(t[i] * 1e9))
        for sensor, xyz in (("accelerometer", accel[i]), ("gyroscope", gyro[i])):
            lines.append(json.dumps({"event": {"type": "imu", "t_ns": str(t_ns), "data": {"sensor": sensor, "xyz": xyz.tolist()}}}))
    for k in range(int(seconds)):
        i = min(int(k * hz), len(t) - 1)
        lines.append(json.dumps({"event": {"type": "gnss", "t_ns": str(1_000_000_000 + k * 1_000_000_000),
                                           "data": {"provider": "gps", "speed_m_s": float(v[i])}}}))
    (directory / "measurements.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


class BatchCalibrationTest(unittest.TestCase):
    def recover(self, r_true: np.ndarray, bias: np.ndarray, **kwargs) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            write_drive(Path(tmp), r_true, bias, **kwargs)
            return cal.calibrate(Path(tmp))

    def test_an_awkwardly_mounted_phone_is_recovered_to_within_two_degrees(self):
        r_true = rotation(yaw_deg=40.0, pitch_deg=25.0, roll_deg=-15.0)
        result = self.recover(r_true, np.array([0.002, -0.001, 0.003]))
        self.assertEqual(result["status"], "valid", result)
        r_est = matrix_from_quaternion(result["q_vehicle_from_device_wxyz"])
        # r_true maps device vectors to vehicle vectors, as the estimate does; compare them as rotations
        error_deg = np.degrees(np.arccos(np.clip((np.trace(r_est.T @ r_true) - 1) / 2, -1, 1)))
        self.assertLess(error_deg, 2.0, f"rotation error {error_deg:.2f} deg")
        self.assertGreater(result["r_long"], 0.9)
        self.assertGreater(result["r_lat"], 0.8)

    def test_the_forward_direction_and_up_are_recovered_for_a_phone_mounted_backwards(self):
        r_true = rotation(yaw_deg=180.0, pitch_deg=-10.0, roll_deg=5.0)
        result = self.recover(r_true, np.zeros(3))
        self.assertEqual(result["status"], "valid", result)
        r_est = matrix_from_quaternion(result["q_vehicle_from_device_wxyz"])
        for name, rest in (("forward", r_est[0]), ("left", r_est[1]), ("up", r_est[2])):
            truth_axis = r_true.T[:, "xyz".index({"forward": "x", "left": "y", "up": "z"}[name])]
            self.assertLess(np.degrees(np.arccos(np.clip(rest @ truth_axis, -1, 1))), 2.0, name)

    def test_gyro_bias_is_the_gyro_mean_while_stopped(self):
        bias = np.array([0.004, -0.002, 0.006])
        result = self.recover(rotation(20.0, 5.0, 0.0), bias)
        self.assertEqual(result["status"], "valid", result)
        np.testing.assert_allclose(result["gyro_bias_rad_s"], bias, atol=0.001)

    def test_a_drive_that_never_accelerates_is_not_calibrated(self):
        with tempfile.TemporaryDirectory() as tmp:
            r_true = rotation(10.0, 0.0, 0.0)
            write_drive(Path(tmp), r_true, np.zeros(3), seconds=200.0)
            lines = Path(tmp, "measurements.jsonl").read_text(encoding="utf-8").splitlines()
            # constant 15 m/s cruise: replace every fix speed, so the speed carries no direction information
            flat = [json.dumps({**json.loads(l), "event": {**json.loads(l)["event"], "data": {**json.loads(l)["event"]["data"], "speed_m_s": 15.0}}})
                    if '"gnss"' in l else l for l in lines]
            Path(tmp, "measurements.jsonl").write_text("\n".join(flat) + "\n", encoding="utf-8")
            result = cal.calibrate(Path(tmp))
        self.assertEqual(result["status"], "invalid")

    def test_quaternion_round_trip_for_all_branches(self):
        for angles in ((0, 0, 0), (179, 0, 0), (10, 170, 0), (0, 0, 170), (120, 60, -100), (-90, -80, 30)):
            r = rotation(*angles)
            np.testing.assert_allclose(matrix_from_quaternion(cal.quaternion_from_matrix(r)), r, atol=1e-9)


if __name__ == "__main__":
    unittest.main()
