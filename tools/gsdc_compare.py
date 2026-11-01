"""Paired comparison of two configurations on the same GSDC outages, with a drive-clustered bootstrap.

    python tools/gsdc_compare.py --dir reports/gsdc_eval_held_out --baseline app --candidate app_no_slip_no_ai

Both configurations are scored on identical outages (same recording, scenario and start time). For each scenario the report gives the median drift of
each, the median and mean paired difference (baseline minus candidate, so positive means the candidate drifts LESS), the fraction of outages the
candidate improves, and a 95 % interval for the mean difference. The interval resamples whole DRIVES, not outages: outages from one drive share a
route, a phone and a car, so treating them as independent would claim more certainty than the data holds. Writes <dir>/comparison_<baseline>_vs_<candidate>.json.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np

SEED = 20261102
RESAMPLES = 4000
SCENARIOS = ("d50", "t30", "t60", "d1000")


def paired_rows(csv_path: Path, baseline: str, candidate: str, scenario: str) -> list[tuple[str, float, float]]:
    """(drive, baseline drift, candidate drift) for outages both scored."""
    pick = {baseline: {}, candidate: {}}
    with open(csv_path, encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["scenario"] != scenario or row["config"] not in pick or row["outcome"] != "scored" or row["drift_pct"] in ("", None):
                continue
            pick[row["config"]][(row["recording"], row["start_ns"])] = (row["drive"], float(row["drift_pct"]))
    return [(pick[baseline][k][0], pick[baseline][k][1], pick[candidate][k][1]) for k in sorted(set(pick[baseline]) & set(pick[candidate]))]


def clustered_interval(drives: np.ndarray, diff: np.ndarray, resamples: int = RESAMPLES) -> tuple[float, float]:
    """95 % interval of the mean paired difference, resampling drives with replacement."""
    rng = np.random.default_rng(SEED)
    names = np.unique(drives)
    sums = {n: diff[drives == n].sum() for n in names}
    counts = {n: int((drives == n).sum()) for n in names}
    means = np.empty(resamples)
    for i in range(resamples):
        chosen = rng.choice(names, size=len(names), replace=True)
        means[i] = sum(sums[n] for n in chosen) / sum(counts[n] for n in chosen)
    return float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def compare(csv_path: Path, baseline: str, candidate: str) -> dict:
    out = {"baseline": baseline, "candidate": candidate, "positive_difference_means": "the candidate drifts less than the baseline", "scenarios": {}}
    for scenario in SCENARIOS:
        rows = paired_rows(csv_path, baseline, candidate, scenario)
        if not rows:
            continue
        drives = np.array([r[0] for r in rows])
        a, b = np.array([r[1] for r in rows]), np.array([r[2] for r in rows])
        d = a - b
        lo, hi = clustered_interval(drives, d)
        out["scenarios"][scenario] = {
            "paired_outages": int(len(d)), "drives": int(len(np.unique(drives))),
            "median_drift_pct_baseline": round(float(np.median(a)), 2), "median_drift_pct_candidate": round(float(np.median(b)), 2),
            "share_under_10pct_baseline": round(float(np.mean(a < 10.0)), 3), "share_under_10pct_candidate": round(float(np.mean(b < 10.0)), 3),
            "median_paired_difference_pct_points": round(float(np.median(d)), 2), "mean_paired_difference_pct_points": round(float(d.mean()), 2),
            "mean_difference_95pct_interval_drive_clustered": [round(lo, 2), round(hi, 2)],
            "interval_excludes_zero": bool(lo > 0 or hi < 0), "fraction_outages_candidate_better": round(float(np.mean(d > 0)), 3)}
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dir", type=Path, required=True, help="a reports/gsdc_eval_* folder holding outages.csv")
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", required=True)
    args = parser.parse_args(argv)
    result = compare(args.dir / "outages.csv", args.baseline, args.candidate)
    (args.dir / f"comparison_{args.baseline}_vs_{args.candidate}.json").write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    for name, s in result["scenarios"].items():
        print(f"{name:6s} n={s['paired_outages']:4d} drives={s['drives']:2d}  median {s['median_drift_pct_baseline']:7.2f} -> {s['median_drift_pct_candidate']:7.2f}  "
              f"under10 {s['share_under_10pct_baseline']:.2f} -> {s['share_under_10pct_candidate']:.2f}  mean diff {s['mean_paired_difference_pct_points']:+8.2f} "
              f"CI {s['mean_difference_95pct_interval_drive_clustered']} {'*' if s['interval_excludes_zero'] else ' '}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
