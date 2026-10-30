"""The N2 extra features read only samples at or before the evaluated time."""
from __future__ import annotations

import unittest

import numpy as np

from training import speed_v3 as v3
from training import speed_v4_experiment as x


def drive(seed: int = 0, n: int = 1200) -> v3.Drive:
    rng = np.random.default_rng(seed)
    t = np.arange(n) / 10.0
    ft = np.arange(0.0, t[-1], 1.0)
    return v3.Drive("syn", t, rng.normal(0, 0.5, n), rng.normal(0, 0.5, n), rng.normal(0, 0.05, n), t,
                    np.full(n, 10.0), np.full(n, 17.0), np.full(n, 78.0), np.zeros(n), ft,
                    10.0 + rng.normal(0, 0.3, len(ft)), np.zeros(len(ft)))


class CausalityTest(unittest.TestCase):
    def test_features_ignore_samples_after_the_evaluated_time(self):
        a, b = drive(), drive()
        t0, t = 40.0, 52.0
        cut = np.searchsorted(b.t, t, side="right")
        b.a_long[cut:] += 50.0; b.a_lat[cut:] += 50.0; b.yaw[cut:] += 5.0
        fcut = np.searchsorted(b.fix_t, t0, side="right")
        b.fix_speed[fcut:] += 30.0
        for variant in ("v3", "bias", "full"):
            fa = x.features(v3.Series(a), 10.0, t0, t, variant)
            fb = x.features(v3.Series(b), 10.0, t0, t, variant)
            np.testing.assert_allclose(fa, fb, err_msg=variant)

    def test_a_constant_accelerometer_bias_is_recovered(self):
        d = drive()
        d.a_long[:] = 0.2                       # fix speeds stay flat, so the whole 0.2 is bias
        d.fix_speed[:] = 10.0
        f = x.extra_features(v3.Series(d), v3.features_at(v3.Series(d), 10.0, 40.0, 50.0), 40.0, 10.0)
        self.assertAlmostEqual(f[0], 0.2, places=2)


if __name__ == "__main__":
    unittest.main()
