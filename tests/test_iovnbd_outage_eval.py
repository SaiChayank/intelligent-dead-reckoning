"""Outage planning and scoring on synthetic tracks with known answers; no dataset or engine."""
from __future__ import annotations

import math
import unittest

import numpy as np

from tools import iovnbd_outage_eval as ev

LAT0, LON0 = 52.0, -1.0
M_PER_DEG_LAT = math.radians(1) * ev.EARTH_R
M_PER_DEG_LON = M_PER_DEG_LAT * math.cos(math.radians(LAT0))


def eastbound_truth(duration_s: float = 1200.0, speed: float = 15.0, hz: float = 10.0) -> dict:
    t_s = np.arange(0.0, duration_s, 1.0 / hz)
    east = speed * t_s
    lat = np.full(len(t_s), LAT0)
    lon = LON0 + east / M_PER_DEG_LON
    t = ev.ORIGIN_NS + (t_s * 1e9).astype(np.int64)
    return {"t": t, "lat": lat, "lon": lon, "speed": np.full(len(t_s), speed), "dist": east}


def nav_from(truth: dict, north_offset_m: np.ndarray, mode: np.ndarray, status: np.ndarray | None = None) -> dict:
    return {"t": truth["t"], "lat": truth["lat"] + north_offset_m / M_PER_DEG_LAT, "lon": truth["lon"].copy(),
            "mode": mode, "status": status if status is not None else np.full(len(truth["t"]), "tracking")}


class PlanningTest(unittest.TestCase):
    def test_distance_outages_end_at_the_distance_and_keep_recovery_gaps(self):
        truth = eastbound_truth()
        fixes = truth["t"][::10]                                  # 1 Hz
        start_after = ev.ORIGIN_NS + 60_000_000_000
        sc = ev.Scenario("d1000", distance_m=1000.0, max_s=150.0)
        outages = ev.plan_outages(truth, fixes, sc, start_after)
        self.assertGreater(len(outages), 2)
        for s, e in outages:
            self.assertGreaterEqual(s, start_after)
            self.assertAlmostEqual((e - s) / 1e9, 1000.0 / 15.0, delta=0.2)
        for (s0, e0), (s1, _) in zip(outages, outages[1:]):
            self.assertGreaterEqual(s1 - e0, int(ev.RECOVERY_S * 1e9))

    def test_too_slow_for_the_distance_is_not_planned(self):
        truth = eastbound_truth(speed=5.0)
        sc = ev.Scenario("d1000", distance_m=1000.0, max_s=150.0)      # 200 s at 5 m/s
        self.assertEqual(ev.plan_outages(truth, truth["t"][::10], sc, ev.ORIGIN_NS), [])


class ScoringTest(unittest.TestCase):
    def setUp(self):
        self.truth = eastbound_truth()
        self.start = int(self.truth["t"][3000])                   # 300 s
        self.end = int(self.truth["t"][3600])                     # 360 s, 900 m

    def test_known_lateral_drift_is_scored_as_a_percentage_of_distance(self):
        t_s = (self.truth["t"] - self.start) / 1e9
        offset = np.where((t_s > 0) & (t_s < 60), 0.5 * t_s, 0.0)    # 30 m after 60 s
        mode = np.where((t_s > 2) & (t_s < 60), "dr", "fused")
        r = ev.score_outage(nav_from(self.truth, offset, mode), self.truth, self.start, self.end)
        self.assertEqual(r["outcome"], "scored")
        self.assertAlmostEqual(r["distance_m"], 900.0, delta=1.0)
        self.assertAlmostEqual(r["error_end_m"], 0.5 * 59.9, delta=0.2)
        self.assertAlmostEqual(r["drift_pct"], 100 * 0.5 * 59.9 / 900.0, delta=0.05)
        self.assertAlmostEqual(r["dr_declared_after_s"], 2.1, delta=0.11)

    def test_failure_inside_an_outage_is_counted_not_dropped(self):
        n = len(self.truth["t"])
        status = np.full(n, "tracking")
        status[3300:] = "failed"
        nav = nav_from(self.truth, np.zeros(n), np.full(n, "fused"), status)
        r = ev.score_outage(nav, self.truth, self.start, self.end)
        self.assertEqual(r["outcome"], "engine_failed")
        summary = ev.summarize([r, {**r, "outcome": "scored", "drift_pct": 2.0, "error_end_m": 18.0,
                                    "dr_declared_after_s": None}], ev.SCENARIOS[1])
        self.assertEqual(summary["engine_failed"], 1)
        self.assertEqual(summary["pass_rate_drift_lt_10pct"], 1.0)
        self.assertEqual(summary["pass_rate_incl_failures"], 0.5)

    def test_outage_without_gnss_navigation_at_start_is_not_scored(self):
        n = len(self.truth["t"])
        nav = nav_from(self.truth, np.zeros(n), np.full(n, "dr"))
        self.assertEqual(ev.score_outage(nav, self.truth, self.start, self.end)["outcome"],
                         "not_navigating_at_start")


class BaselineTest(unittest.TestCase):
    def test_hold_gnss_is_exact_on_a_straight_constant_speed_track(self):
        truth = eastbound_truth()
        fixes = np.array([[float(truth["t"][i]), LAT0, float(truth["lon"][i]), 15.0, 90.0]
                          for i in range(0, 6000, 10)])
        start, end = int(truth["t"][3005]), int(truth["t"][3605])
        r = ev.score_hold_gnss(fixes, truth, start, end)
        self.assertEqual(r["outcome"], "scored")
        self.assertLess(r["error_end_m"], 0.5)

    def test_hold_gnss_error_grows_with_a_course_error(self):
        truth = eastbound_truth()
        fixes = np.array([[float(truth["t"][i]), LAT0, float(truth["lon"][i]), 15.0, 80.0]   # 10 degrees off
                          for i in range(0, 6000, 10)])
        start, end = int(truth["t"][3005]), int(truth["t"][3605])
        r = ev.score_hold_gnss(fixes, truth, start, end)
        self.assertAlmostEqual(r["error_end_m"], 2 * 900.0 * math.sin(math.radians(5)), delta=15.0)

    def test_no_fix_before_the_outage_is_not_scored(self):
        truth = eastbound_truth()
        fixes = np.array([[float(truth["t"][3000]), LAT0, float(truth["lon"][3000]), 15.0, 90.0]])
        self.assertEqual(ev.score_hold_gnss(fixes, truth, int(truth["t"][10]), int(truth["t"][700]))["outcome"],
                         "not_navigating_at_start")


class RecoveryMetricsTest(unittest.TestCase):
    def _run(self, offsets_m):
        truth = eastbound_truth(duration_s=100.0)
        n = len(truth["t"])
        nav = nav_from(truth, offsets_m(truth), np.array(["fused"] * n))
        end = ev.ORIGIN_NS + int(50e9)
        after = np.flatnonzero((nav["t"] >= end) & (nav["t"] <= end + int(ev.RECOVERY_WINDOW_S * 1e9)))
        return ev.recovery_metrics(nav, truth, end, after)

    def test_an_error_that_decays_to_zero_settles_at_the_time_it_crosses_five_metres(self):
        # 40 m at the outage end, falling 10 m per second: above 5 m until t = 3.5 s after the end
        r = self._run(lambda tr: np.clip(40.0 - 10.0 * np.maximum((tr["t"] - ev.ORIGIN_NS) / 1e9 - 50.0, 0.0), 0.0, 40.0))
        self.assertAlmostEqual(r["recovery_time_s"], 3.5, delta=0.15)
        self.assertAlmostEqual(r["recovery_error_5s_m"], 0.0, places=1)
        self.assertAlmostEqual(r["recovery_error_max10s_m"], 40.0, delta=1.0)

    def test_an_error_that_never_settles_is_capped_at_the_window(self):
        r = self._run(lambda tr: np.full(len(tr["t"]), 20.0))
        self.assertEqual(r["recovery_time_s"], ev.RECOVERY_WINDOW_S)

    def test_no_solutions_after_the_outage_give_empty_values(self):
        truth = eastbound_truth(duration_s=60.0)
        nav = nav_from(truth, np.zeros(len(truth["t"])), np.array(["fused"] * len(truth["t"])))
        r = ev.recovery_metrics(nav, truth, ev.ORIGIN_NS, np.array([], dtype=int))
        self.assertEqual(r, {"recovery_error_5s_m": None, "recovery_error_max10s_m": None, "recovery_time_s": None})


if __name__ == "__main__":
    unittest.main()


class MapArmsTest(unittest.TestCase):
    """The map arms run only on drives wholly inside the area the road graph was queried for."""

    def setUp(self):
        import json
        import tempfile
        from pathlib import Path
        self.tmp = tempfile.TemporaryDirectory()
        (Path(self.tmp.name) / "coverage.json").write_text(
            json.dumps({"boxes_west_south_east_north": [[-1.6, 52.3, -1.2, 52.5], [-0.8, 52.0, -0.7, 52.1]]}), encoding="utf-8")
        self.saved = ev.ROAD_GRAPH_DIR
        ev.ROAD_GRAPH_DIR = Path(self.tmp.name)

    def tearDown(self):
        ev.ROAD_GRAPH_DIR = self.saved
        self.tmp.cleanup()

    @staticmethod
    def drive(lat, lon):
        return {"lat": np.array(lat), "lon": np.array(lon)}

    def test_a_drive_inside_a_box_is_covered(self):
        self.assertTrue(ev.within_graph(self.drive([52.35, 52.45], [-1.5, -1.3])))
        self.assertTrue(ev.within_graph(self.drive([52.02, 52.05], [-0.78, -0.72])))

    def test_a_drive_that_leaves_every_box_is_not_covered(self):
        self.assertFalse(ev.within_graph(self.drive([52.35, 52.55], [-1.5, -1.3])))       # north of the first box
        self.assertFalse(ev.within_graph(self.drive([52.35, 52.05], [-1.5, -0.75])))      # spans two boxes: no single box holds it

    def test_every_arm_has_a_label_and_a_colour_and_map_arms_are_held_out_arms(self):
        for arm in ev.ARMS:
            self.assertIn(arm, ev.ARM_LABELS)
            self.assertIn(arm, ev.ARM_COLORS)
        for arm in ev.MAP_ARMS:
            if arm != "app_map":                       # the app arm uses the packaged speed model, not a held-out file
                self.assertIn(arm, ev.HELDOUT_ARMS)
        self.assertNotIn("app_map", ev.HELDOUT_ARMS)
