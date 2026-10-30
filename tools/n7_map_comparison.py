"""Paired comparison of the map-matching arms against the same engine without a map (N7, REAL drives on REAL OSM roads).

    python tools/n7_map_comparison.py

Reads reports/iovnbd_eval/vehicle/outages.csv. The graph covers only the areas around the drives (OpenStreetMap, ODbL; built by tools/build_road_graph.py
into data/processed/roadgraph/uk-v1 with a coverage.json listing the boxes; not committed), so only the IO-VNBD drives that lie wholly inside it have map arms. Outages
are paired (same drive, scenario and start time) with the arm without a map; a positive difference means the map helps. The interval is a 95 %
bootstrap of the mean paired difference in drift percentage points. Writes reports/n7_map_comparison.json.
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

import numpy as np

from t5_trust_comparison import DEV, HELD, bootstrap_mean_ci

ROOT = Path(__file__).resolve().parents[1]
BASE = "ekf_nhc_v3_heldout_vel_nhcout"
ARMS = {"heading_only": BASE + "_map", "heading_and_cross_track": BASE + "_mapx", "cross_track_variance_9": "mapx_var9",
        "cross_track_variance_4": "mapx_var4", "cross_track_confidence_0.6": "mapx_conf06",
        "cross_track_confidence_0.4": "mapx_conf04"}
SCENARIOS = ("d50", "t30", "t60", "d1000")


def pick(rows, arm, scenario, drives, column="drift_pct"):
    return {(r["sequence"], r["start_ns"]): float(r[column]) for r in rows
            if r["arm"] == arm and r["scenario"] == scenario and r["sequence"] in drives and r["outcome"] == "scored"
            and r.get(column) not in (None, "")}


def main() -> int:
    rows = list(csv.DictReader(open(ROOT / "reports" / "iovnbd_eval" / "vehicle" / "outages.csv", encoding="utf-8")))
    drives = sorted({r["sequence"] for r in rows if r["arm"] == ARMS["heading_only"]})
    out = {"arm_without_map": BASE, "arms_with_map": ARMS, "drives": drives,
           "source": "OpenStreetMap highway ways (ODbL) around the drives (boxes in the road-graph coverage.json), real IO-VNBD drives, forced outages",
           "results": {}}
    for name, arm in ARMS.items():
        out["results"][name] = {}
        for split, split_drives in (("all", set(drives)), ("development", set(DEV)), ("held_out", set(HELD))):
            out["results"][name][split] = {}
            for sc in SCENARIOS:
                a, b = pick(rows, BASE, sc, split_drives), pick(rows, arm, sc, split_drives)
                keys = sorted(set(a) & set(b))
                if not keys:
                    continue
                off, on = np.array([a[k] for k in keys]), np.array([b[k] for k in keys])
                d = off - on
                lo, hi = bootstrap_mean_ci(d)
                out["results"][name][split][sc] = {
                    "paired_outages": len(keys),
                    "drives": sorted({k[0] for k in keys}),
                    "median_drift_pct_without_map": round(float(np.median(off)), 1),
                    "median_drift_pct_with_map": round(float(np.median(on)), 1),
                    "mean_paired_difference_pct_points": round(float(d.mean()), 2),
                    "median_paired_difference_pct_points": round(float(np.median(d)), 2),
                    "mean_difference_95pct_interval": [round(lo, 2), round(hi, 2)],
                    "interval_excludes_zero": bool(lo > 0 or hi < 0),
                    "fraction_outages_improved": round(float(np.mean(d > 0)), 3),
                    "fraction_outages_worsened": round(float(np.mean(d < 0)), 3),
                }
    path = ROOT / "reports" / "n7_map_comparison.json"
    path.write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    print("wrote", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
