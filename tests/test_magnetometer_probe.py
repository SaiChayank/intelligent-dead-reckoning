import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import magnetometer_probe as mp  # noqa: E402


def synthetic(heading_deg: np.ndarray, noise_deg: float = 0.0, seed: int = 0):
    """Phone lying flat, top (+y) pointing along the heading; field 20 uT north, 40 uT down."""
    rng = np.random.default_rng(seed)
    h = np.radians(heading_deg + rng.normal(0, noise_deg, len(heading_deg)))
    g = np.tile([0.0, 0.0, 9.81], (len(h), 1))
    # north/east unit vectors expressed in the device frame (x right, y forward, z up): forward = heading
    north = np.stack([-np.sin(h), np.cos(h), np.zeros_like(h)], 1)
    field = 20.0 * north + np.array([0.0, 0.0, -40.0])
    return g, field


class MagnetometerProbeTest(unittest.TestCase):
    def test_a_clean_field_gives_the_true_heading_up_to_a_constant(self):
        course = np.linspace(0, 340, 400)
        g, field = synthetic(course)
        _, res = mp.best_axis(g, field, course)
        self.assertLess(res.std(), 1e-6)

    def test_heading_noise_shows_up_as_residual_of_the_same_size(self):
        course = np.linspace(0, 340, 2000)
        g, field = synthetic(course, noise_deg=15.0)
        _, res = mp.best_axis(g, field, course)
        self.assertAlmostEqual(res.std(), 15.0, delta=1.5)

    def test_offset_is_removed_by_the_circular_mean_across_the_wrap(self):
        course = np.array([350.0, 355.0, 0.0, 5.0, 10.0])
        res = mp.residual_deg(course, course - 30.0)
        self.assertTrue(np.allclose(res, 0.0, atol=1e-9))


if __name__ == "__main__":
    unittest.main()
