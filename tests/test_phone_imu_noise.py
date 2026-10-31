import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import phone_imu_noise  # noqa: E402


def write_recording(directory: Path, accel_std: float, gyro_std: float, gyro_bias: float, rate=100.0, seconds=60.0) -> None:
    rng = np.random.default_rng(7)
    n = int(rate * seconds)
    lines = []
    for i in range(n):
        t_ns = int(i / rate * 1e9)
        for sensor, unit, centre, std in (("accelerometer", "m/s^2", [0.0, 0.0, 9.8], accel_std),
                                          ("gyroscope", "rad/s", [gyro_bias] * 3, gyro_std)):
            xyz = (np.array(centre) + rng.normal(0, std, 3)).tolist()
            lines.append(json.dumps({"event": {"type": "imu", "t_ns": str(t_ns),
                                               "data": {"sensor": sensor, "unit": unit, "xyz": xyz}}}))
    (directory / "measurements.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


class PhoneImuNoiseTest(unittest.TestCase):
    def test_recovers_known_noise_and_bias_from_synthetic_white_noise(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_recording(Path(tmp), accel_std=0.01, gyro_std=0.001, gyro_bias=0.0005)
            result = phone_imu_noise.characterise(phone_imu_noise.load(Path(tmp)))
        self.assertAlmostEqual(result["accel"]["rate_hz"], 100.0, delta=0.5)
        for std in result["accel"]["std"]:
            self.assertAlmostEqual(std, 0.01, delta=0.001)
        for density in result["gyro"]["white_noise_density_per_sqrt_hz"]:
            self.assertAlmostEqual(density, 0.001 * 0.01 ** 0.5, delta=0.00002)
        for bias in result["gyro_bias_deg_per_min"]:
            self.assertAlmostEqual(bias, np.degrees(0.0005) * 60, delta=0.2)

    def test_allan_deviation_of_white_noise_falls_as_one_over_root_tau(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_recording(Path(tmp), accel_std=0.02, gyro_std=0.002, gyro_bias=0.0)
            allan = phone_imu_noise.characterise(phone_imu_noise.load(Path(tmp)))["accel"]["allan_deviation"]
        ratio = np.array(allan["0.1"]) / np.array(allan["1"])
        for r in ratio:
            self.assertAlmostEqual(r, 10 ** 0.5, delta=0.8)


if __name__ == "__main__":
    unittest.main()
