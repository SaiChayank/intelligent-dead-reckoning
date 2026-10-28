"""Speed v3: causality, target definition and model round trip on a synthetic drive."""
from __future__ import annotations

import dataclasses
import unittest

import numpy as np

from training import speed_v3 as sv


def synthetic_drive(seed: int = 0, duration_s: float = 400.0) -> sv.Drive:
    rng = np.random.default_rng(seed)
    t = np.arange(0.0, duration_s, 0.1)
    speed = 14.0 + 4.0 * np.sin(2 * np.pi * t / 61.0)
    a_long = np.gradient(speed, t) + rng.normal(0, 0.05, len(t))
    yaw = 0.05 * np.sin(2 * np.pi * t / 90.0)
    fix_t = np.arange(1.0, duration_s, 1.0)
    return sv.Drive("syn", t, a_long, rng.normal(0, 0.2, len(t)), yaw, t.copy(), speed,
                    np.full(len(t), 52.0), -1.0 + np.cumsum(speed) * 0.1 / 68_460.0, np.full(len(t), 90.0),
                    fix_t, np.interp(fix_t, t, speed), np.full(len(fix_t), 90.0))


class CausalityTest(unittest.TestCase):
    def test_features_ignore_samples_after_the_query_time(self):
        d = synthetic_drive()
        base = sv.features_at(sv.Series(d), 14.0, 100.0, 130.0)
        k = int(np.searchsorted(d.t, 130.0, side="right"))
        a_long, a_lat, yaw = d.a_long.copy(), d.a_lat.copy(), d.yaw.copy()
        a_long[k:] += 50.0; a_lat[k:] -= 30.0; yaw[k:] += 2.0
        changed = dataclasses.replace(d, a_long=a_long, a_lat=a_lat, yaw=yaw)
        np.testing.assert_allclose(sv.features_at(sv.Series(changed), 14.0, 100.0, 130.0), base, atol=1e-9)

    def test_features_change_when_past_samples_change(self):
        d = synthetic_drive()
        base = sv.features_at(sv.Series(d), 14.0, 100.0, 130.0)
        a_long = d.a_long.copy(); a_long[int(115 / 0.1)] += 5.0
        other = sv.features_at(sv.Series(dataclasses.replace(d, a_long=a_long)), 14.0, 100.0, 130.0)
        self.assertFalse(np.allclose(base, other))


class SamplesTest(unittest.TestCase):
    def test_target_is_true_speed_minus_integrated_estimate(self):
        d = synthetic_drive()
        s = sv.build_samples(d, stride_fixes=20)
        self.assertGreater(len(s["x"]), 20)
        np.testing.assert_allclose(s["y"], s["meta"][:, 2] - s["x"][:, -1], atol=1e-9)
        # With a clean accelerometer the integrated estimate is close to truth at short horizons.
        short = s["meta"][:, 1] <= 5
        self.assertLess(float(np.sqrt(np.mean(s["y"][short] ** 2))), 0.6)

    def test_slow_starts_are_excluded(self):
        d = synthetic_drive()
        slow = dataclasses.replace(d, fix_speed=np.full(len(d.fix_speed), 1.0))
        self.assertEqual(len(sv.build_samples(slow)["x"]), 0)


class ModelTest(unittest.TestCase):
    def test_json_round_trip_gives_identical_predictions(self):
        rng = np.random.default_rng(1)
        x = rng.normal(size=(400, len(sv.FEATURES))) * [5, 20, 3, 1, 0.1, 0.3, 0.3, 0.3, 5] + [14, 20, 0, 0, 0.05, 0.3, 0, 0.2, 14]
        y = 0.3 * x[:, 2] - 0.05 * x[:, 1] + rng.normal(0, 0.1, 400)
        model = sv.train_mlp(x, y, hidden=8, epochs=300)
        restored = sv.Mlp.from_json(model.to_json())
        np.testing.assert_allclose(restored.predict(x), model.predict(x), atol=1e-12)
        self.assertLess(float(np.sqrt(np.mean((model.predict(x) - y) ** 2))), float(np.std(y)))

    def test_training_is_deterministic(self):
        rng = np.random.default_rng(2)
        x = rng.normal(size=(200, len(sv.FEATURES))); y = x[:, 0] + rng.normal(0, 0.1, 200)
        a = sv.train_mlp(x, y, hidden=4, epochs=5).predict(x)
        b = sv.train_mlp(x, y, hidden=4, epochs=5).predict(x)
        np.testing.assert_array_equal(a, b)


class DriftTest(unittest.TestCase):
    def test_perfect_speed_and_heading_give_near_zero_drift(self):
        d = synthetic_drive()
        d = dataclasses.replace(d, yaw=np.zeros(len(d.yaw)))       # truth is a straight eastbound track
        s = sv.Series(d)
        # Eastbound at the true speed: integrating the truth speed reproduces the truth displacement.
        truth_speed = d.truth_speed
        taus = np.arange(0, 31.0)
        v = np.interp(100.0 + taus, d.truth_t, truth_speed)
        east = float(np.sum(0.5 * (v[1:] + v[:-1])))
        lon0, lon1 = np.interp([100.0, 130.0], d.truth_t, d.truth_lon)
        expected = np.radians(lon1 - lon0) * sv.EARTH_R * np.cos(np.radians(52.0))
        self.assertAlmostEqual(east, expected, delta=0.02 * expected)
        err = sv.dead_reckon_error(s, None, "integrated", float(np.interp(100.0, d.t, d.truth_speed)), 90.0, 100.0, 30.0)
        self.assertIsNotNone(err)
        self.assertLess(err, 10.0)


if __name__ == "__main__":
    unittest.main()
