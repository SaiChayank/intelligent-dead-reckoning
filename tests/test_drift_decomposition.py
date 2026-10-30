import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import drift_decomposition as dd  # noqa: E402


def straight(heading_deg=90.0, speed=10.0, seconds=30.0, hz=10):
    t = np.arange(0.0, seconds + 1e-9, 1.0 / hz)
    h = np.full_like(t, heading_deg)
    v = np.full_like(t, speed)
    x = speed * np.sin(np.radians(heading_deg)) * t
    y = speed * np.cos(np.radians(heading_deg)) * t
    return t, (x, y), h, v


class DecompositionTest(unittest.TestCase):
    def test_a_perfect_engine_has_no_error_in_any_part(self):
        t, xy, h, v = straight()
        r = dd.decompose_outage(t, xy, h, v, xy, h, v)
        for k in ("heading_part", "speed_part", "start_offset", "actual"):
            self.assertLess(r[k], 1e-6, k)

    def test_a_constant_heading_error_is_all_heading(self):
        t, xy, h, v = straight()
        eng_h = h + 5.0
        # the engine's own track under that heading error
        ex = xy[0][0] + np.cumsum(v * np.sin(np.radians(eng_h)) * np.gradient(t))
        ey = xy[1][0] + np.cumsum(v * np.cos(np.radians(eng_h)) * np.gradient(t))
        r = dd.decompose_outage(t, xy, h, v, (ex, ey), eng_h, v)
        self.assertGreater(r["heading_part"], 20.0)
        self.assertLess(r["speed_part"], 1e-6)
        self.assertGreater(abs(r["across"]), 10.0 * abs(r["along"]))

    def test_a_speed_scale_error_is_all_speed_and_along_track(self):
        t, xy, h, v = straight()
        eng_v = v * 1.2
        ex = xy[0][0] + np.cumsum(eng_v * np.sin(np.radians(h)) * np.gradient(t))
        ey = xy[1][0] + np.cumsum(eng_v * np.cos(np.radians(h)) * np.gradient(t))
        r = dd.decompose_outage(t, xy, h, v, (ex, ey), h, eng_v)
        self.assertGreater(r["speed_part"], 40.0)
        self.assertLess(r["heading_part"], 1e-6)
        self.assertGreater(abs(r["along"]), 10.0 * abs(r["across"]))

    def test_a_starting_offset_is_reported_as_such(self):
        t, xy, h, v = straight()
        eng_xy = (xy[0] + 7.0, xy[1])
        r = dd.decompose_outage(t, xy, h, v, eng_xy, h, v)
        self.assertAlmostEqual(r["start_offset"], 7.0, places=6)
        self.assertAlmostEqual(r["actual"], 7.0, places=6)

    def test_summary_shares_sum_to_one(self):
        t, xy, h, v = straight()
        eng_h = h + 3.0
        ex = xy[0][0] + np.cumsum(v * np.sin(np.radians(eng_h)) * np.gradient(t))
        ey = xy[1][0] + np.cumsum(v * np.cos(np.radians(eng_h)) * np.gradient(t))
        r = dd.decompose_outage(t, xy, h, v, (ex, ey), eng_h, v)
        r.update({"drive": "x", "scenario": "t30", "has_stop": False})
        s = dd.summarise([r])["t30"]
        self.assertAlmostEqual(s["share_heading"] + s["share_speed"] + s["share_start_offset"], 1.0, places=9)


if __name__ == "__main__":
    unittest.main()
