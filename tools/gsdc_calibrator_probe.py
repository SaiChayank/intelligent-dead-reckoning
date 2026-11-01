"""How often does the app's in-drive mount calibrator lock on real phone-in-car trips, and with which thresholds?

    python tools/gsdc_calibrator_probe.py --split development      # choose thresholds here
    python tools/gsdc_calibrator_probe.py --split held_out         # then confirm, with the thresholds chosen on development

Replays each converted GSDC trip (tools/gsdc_to_recording.py) through the real engine with no outage and one calibrator threshold setting at a time,
and records whether the engine ever started navigating (the calibrator reached VALID and a first fix arrived) and when. Only aggregate lock
statistics and calibrator diagnostic counts are written, never a position.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gsdc_to_recording as conv  # noqa: E402
import iovnbd_outage_eval as ev  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
ORIGIN_S = conv.ORIGIN_NS / 1e9

# Ordered by how much each relaxes the app's defaults; every step adds to the previous one.
SETTINGS: dict[str, dict] = {
    "default": {},
    "stationary_relaxed": {"stationary_accel_std": 0.30, "stationary_gyro_std": 0.02},
    "plus_straightness": {"stationary_accel_std": 0.30, "stationary_gyro_std": 0.02, "gravity_spread": 0.10, "yaw_rate": 0.08},
    "plus_excitation": {"stationary_accel_std": 0.30, "stationary_gyro_std": 0.02, "gravity_spread": 0.10, "yaw_rate": 0.08,
                        "excitation": 0.15, "straight_fraction": 0.80},
    "plus_sparse_gnss": {"stationary_accel_std": 0.30, "stationary_gyro_std": 0.02, "gravity_spread": 0.10, "yaw_rate": 0.08,
                         "excitation": 0.15, "straight_fraction": 0.80, "speed_slope": 0.10, "min_gnss_fixes": 2.0},
}


def recordings(root: Path, split: str) -> list[Path]:
    out = []
    for drive in sorted(p for p in root.iterdir() if p.is_dir()):
        if split != "all" and conv.split_of(drive.name) != split:
            continue
        out.extend(sorted(p for p in drive.iterdir() if (p / "measurements.jsonl").is_file()))
    return out


def summarize(results: list[dict]) -> dict:
    """Lock statistics for one setting from the runner's per-trip results."""
    locked = [r for r in results if r.get("first_position_ns") is not None]
    times = [(int(r["first_position_ns"]) / 1e9 - ORIGIN_S) for r in locked]
    reasons: dict[str, int] = {}
    for r in results:
        for code, count in (r.get("diagnostics") or {}).items():
            if code.startswith("CALIBRATION_") and r.get("first_position_ns") is None:
                reasons[code] = reasons.get(code, 0) + 1
    return {"trips": len(results), "locked": len(locked), "lock_rate": round(len(locked) / len(results), 3) if results else None,
            "median_seconds_to_first_position": round(float(np.median(times)), 1) if times else None,
            "p90_seconds_to_first_position": round(float(np.percentile(times, 90)), 1) if times else None,
            "calibrator_codes_seen_on_trips_that_never_locked": dict(sorted(reasons.items(), key=lambda kv: -kv[1]))}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=conv.DEFAULT_OUT, help="converted trips (tools/gsdc_to_recording.py output)")
    parser.add_argument("--split", choices=("development", "held_out", "all"), default="development")
    parser.add_argument("--settings", default=",".join(SETTINGS))
    parser.add_argument("--out", type=Path)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args(argv)
    names = [n for n in args.settings.split(",") if n]
    trips = recordings(args.root.resolve(), args.split)
    if not trips:
        raise SystemExit(f"no converted trips for split {args.split} under {args.root}")
    work = ROOT / "data" / "processed" / "gsdc2023_probe_runs" / args.split
    jobs = [{"session": trip.as_posix(), "output": (work / name / f"{trip.name}.csv").as_posix(), "arm": "app", "outages_ns": [],
             "calibration": None, "profile": "phone", "calibrator": SETTINGS[name]}
            for name in names for trip in trips]
    work.mkdir(parents=True, exist_ok=True)
    jobs_path, results_path = work / "jobs.json", work / "results.json"
    jobs_path.write_text(json.dumps({"jobs": jobs}), encoding="utf-8")
    ev.run_edge(jobs_path, results_path, args.java_home)
    results = json.loads(results_path.read_text(encoding="utf-8"))["results"]
    summary = {"split": args.split, "trips": len(trips), "settings": {}}
    per_trip: dict[str, dict] = {}
    for name in names:
        mine = [r for r in results if f"{os.sep}{name}{os.sep}" in r["output"] or f"/{name}/" in r["output"].replace("\\", "/")]
        summary["settings"][name] = {"thresholds": SETTINGS[name], **summarize(mine)}
        for r in mine:
            drive = Path(r["output"]).stem
            per_trip.setdefault(drive, {})[name] = None if r.get("first_position_ns") is None else round(int(r["first_position_ns"]) / 1e9 - ORIGIN_S, 1)
        print(f"{name:20s} locked {summary['settings'][name]['locked']:3d} of {summary['settings'][name]['trips']} "
              f"(median {summary['settings'][name]['median_seconds_to_first_position']} s)", flush=True)
    summary["seconds_to_first_position_by_trip"] = per_trip
    out = args.out or ROOT / "reports" / f"gsdc_calibrator_probe_{args.split}.json"
    out.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
