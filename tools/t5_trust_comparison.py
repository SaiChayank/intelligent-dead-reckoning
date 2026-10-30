"""Paired comparison of the learned-GNSS-trust arm against the same arm without it (T5).

    python tools/t5_trust_comparison.py

Reads reports/iovnbd_eval/vehicle/outages.csv, pairs outages that BOTH arms scored (same drive, scenario and start time), and
reports the median drift of each arm, the median paired difference (off minus on, positive = trust helps) and a 95 % bootstrap
interval for the mean paired difference, separately for the pre-registered development and held-out drives (reports/gap_closure_log.md,
T4). Writes reports/t5_trust_comparison.json.
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OFF = "ekf_nhc_v3_heldout_vel_nhcout"
ON = OFF + "_trust"
DEV = ["m", "s2", "s4", "vta16", "vta8", "y1"]
HELD = ["s1", "s3c", "vfa01", "vta2", "vw3"]
SEED = 20261030


def paired(rows, drives, scenario):
    pick = lambda arm: {(r["sequence"], r["start_ns"]): float(r["drift_pct"]) for r in rows
                        if r["arm"] == arm and r["scenario"] == scenario and r["sequence"] in drives
                        and r["outcome"] == "scored" and r["drift_pct"]}
    a, b = pick(OFF), pick(ON)
    keys = sorted(set(a) & set(b))
    return np.array([a[k] for k in keys]), np.array([b[k] for k in keys])


def bootstrap_mean_ci(diff, n=4000):
    rng = np.random.default_rng(SEED)
    means = np.array([diff[rng.integers(0, len(diff), len(diff))].mean() for _ in range(n)])
    return float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def main() -> int:
    rows = list(csv.DictReader(open(ROOT / "reports" / "iovnbd_eval" / "vehicle" / "outages.csv", encoding="utf-8")))
    out = {"arm_without_trust": OFF, "arm_with_trust": ON, "development_drives": DEV, "held_out_drives": HELD, "results": {}}
    for split, drives in (("development", DEV), ("held_out", HELD)):
        out["results"][split] = {}
        for sc in ("d50", "t30", "t60", "d1000"):
            off, on = paired(rows, set(drives), sc)
            d = off - on
            lo, hi = bootstrap_mean_ci(d)
            out["results"][split][sc] = {
                "paired_outages": int(len(d)),
                "median_drift_pct_without_trust": round(float(np.median(off)), 1),
                "median_drift_pct_with_trust": round(float(np.median(on)), 1),
                "median_paired_difference_pct_points": round(float(np.median(d)), 2),
                "mean_paired_difference_pct_points": round(float(d.mean()), 2),
                "mean_difference_95pct_interval": [round(lo, 2), round(hi, 2)],
                "interval_excludes_zero": bool(lo > 0 or hi < 0),
                "fraction_outages_improved": round(float(np.mean(d > 0)), 3),
            }
    (ROOT / "reports" / "t5_trust_comparison.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    for split, byscen in out["results"].items():
        for sc, r in byscen.items():
            print(f"{split:11s} {sc:6s} n={r['paired_outages']:3d} off {r['median_drift_pct_without_trust']:5.1f} on {r['median_drift_pct_with_trust']:5.1f} "
                  f"mean diff {r['mean_paired_difference_pct_points']:+6.2f} CI {r['mean_difference_95pct_interval']} "
                  f"{'*' if r['interval_excludes_zero'] else ' '} improved {100*r['fraction_outages_improved']:.0f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
