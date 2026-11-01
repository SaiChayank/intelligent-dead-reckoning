"""GNSS-outage drift of the navigation engine on real phone-in-car trips (Google Smartphone Decimeter Challenge 2023-2024), scored against the
reference track.

    python tools/gsdc_outage_eval.py --split development
    python tools/gsdc_outage_eval.py --split held_out --configs <names chosen on development>

Per converted trip (tools/gsdc_to_recording.py): an offline mount calibration from the first minutes (tools/gsdc_batch_calibration.py, aiding
stream only), forced outages of 50 m, 1 km, 30 s and 60 s starting at least 120 s after that calibration is released, and the position the engine
holds at the end of each outage compared with the 0.1 m reference. Each (configuration, outage) is scored once; results go to
reports/gsdc_eval_<split>/ as outages.csv and summary.json. No position is written.

What this is, and is not: a real phone in a real car with a good reference, but GNSS aiding (the same reference at 1 Hz, declared 2.5 m) is better than a
phone receiver gives, the mount calibration is offline rather than the app's own (which does not lock on these trips), and the roads are in
California. The dataset is CC BY-NC-SA 3.0 IGO; see tools/gsdc_to_recording.py for the citations.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import field_drive_eval as fd  # noqa: E402
import gsdc_batch_calibration as bc  # noqa: E402
import gsdc_to_recording as conv  # noqa: E402
import iovnbd_outage_eval as ev  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SETTLE_S = 120.0

# name -> (engine arm id, job profile). "phone" is the app's own stationary settings; "iovnbd" relaxes them (min samples 15, accel std 0.25).
CONFIGS: dict[str, tuple[str, str]] = {
    "app": ("app", "phone"),
    "app_relaxed_stationary": ("app", "iovnbd"),
    "ekf_nhc": ("ekf_nhc", "phone"),
    "ekf_nhc_ai": ("ekf_nhc_ai", "phone"),
    "ekf_nhc_vel": ("ekf_nhc_vel", "phone"),
    "ekf_nhc_vel_relaxed_stationary": ("ekf_nhc_vel", "iovnbd"),
    "ins": ("ins", "phone"),
    "app_no_ai": ("app_no_ai", "phone"),
    "app_no_adaptive_noise": ("app_no_adaptive_noise", "phone"),
    "app_no_mount_slip": ("app_no_mount_slip", "phone"),
    "app_no_decimation": ("app_no_decimation", "phone"),
    "app_no_slip_no_ai": ("app_no_slip_no_ai", "phone"),
    "ekf_nhc_nhcout": ("ekf_nhc_nhcout", "phone"),
    "ekf_nhc_smooth1": ("ekf_nhc_smooth1", "phone"),
    "ekf_nhc_smooth1_nhcout": ("ekf_nhc_smooth1_nhcout", "phone"),
    "app_current": ("app_current", "phone"),
}
DEFAULT_CONFIGS = ("app", "ekf_nhc", "ekf_nhc_ai", "ekf_nhc_vel", "ekf_nhc_vel_relaxed_stationary", "ins")


def trips(root: Path, split: str) -> list[Path]:
    out = []
    for drive in sorted(p for p in root.iterdir() if p.is_dir()):
        if split != "all" and conv.split_of(drive.name) != split:
            continue
        out.extend(sorted(p for p in drive.iterdir() if (p / "measurements.jsonl").is_file()))
    return out


def plan_trip(recording: Path, work: Path, configs: tuple[str, ...], in_engine: bool = False) -> tuple[list[dict], dict]:
    """Jobs for one trip, plus what is known about it (calibration, planned outages).

    With `in_engine` the engine finds the mount itself (the in-app batch calibrator beside the in-drive one) instead of being handed the offline one;
    outages are still planned from the offline calibration's release time, which uses the same windows, so the two runs are comparable."""
    calibration = bc.calibrate(recording)
    info = {"recording": recording.name, "drive": recording.parent.name, "calibration_status": calibration["status"], "_recording": recording.as_posix()}
    if calibration["status"] != "valid":
        return [], {**info, "reasons": calibration.get("reasons")}
    truth = fd.load_fixes(recording)
    start_after = bc.ORIGIN_NS + int((calibration["available_s"] + SETTLE_S) * 1e9)
    planned = {sc.name: ev.plan_outages(truth, truth["t"], sc, start_after) for sc in ev.SCENARIOS}
    job_calibration = {"id": calibration["id"], "q_vehicle_from_device_wxyz": calibration["q_vehicle_from_device_wxyz"],
                       "gyro_bias_rad_s": calibration["gyro_bias_rad_s"], "available_ns": calibration["available_ns"]}
    jobs = []
    for sc_name, outages in planned.items():
        if not outages:
            continue
        for name in configs:
            arm, profile = CONFIGS[name]
            jobs.append({"session": recording.as_posix(), "output": (work / name / f"{sc_name}__{recording.name}.csv").as_posix(), "arm": arm,
                         "outages_ns": [[str(s), str(e)] for s, e in outages], "calibration": None if in_engine else job_calibration,
                         "profile": profile, **({"batch_fallback": True} if in_engine else {})})
    info.update({"calibration": {k: calibration[k] for k in ("available_s", "r_long", "r_lat", "moving_seconds", "up_method")},
                 "planned_outages": {k: len(v) for k, v in planned.items()},
                 "distance_km": round(float(truth["dist"][-1]) / 1000, 1), "duration_s": round(float(truth["t"][-1] - truth["t"][0]) / 1e9, 0)})
    info["_planned"] = {k: [[int(s), int(e)] for s, e in v] for k, v in planned.items()}
    info["_truth"] = truth
    return jobs, info


def score_all(infos: list[dict], work: Path, configs: tuple[str, ...]) -> list[dict]:
    rows = []
    for info in infos:
        if info["calibration_status"] != "valid":
            continue
        for sc_name, outages in info["_planned"].items():
            for name in configs:
                path = work / name / f"{sc_name}__{info['recording']}.csv"
                if not path.is_file() or not outages:
                    continue
                nav = ev.read_nav(path)
                for start, end in outages:
                    rows.append({"drive": info["drive"], "recording": info["recording"], "scenario": sc_name, "config": name,
                                 **ev.score_outage(nav, info["_truth"], start, end)})
    return rows


def quaternion_angle_deg(a: list[float], b: list[float]) -> float:
    dot = abs(float(np.clip(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)), -1.0, 1.0)))
    return float(np.degrees(2.0 * np.arccos(dot)))


def calibration_parity(infos: list[dict], work: Path, results_path: Path) -> dict:
    """How the Kotlin calibrator's mount compares with the Python estimate on the same trips, and when it was released."""
    results = json.loads(results_path.read_text(encoding="utf-8"))["results"]
    angles, lags_s, missing = [], [], []
    for info in infos:
        if info["calibration_status"] != "valid":
            continue
        mine = [r for r in results if f"__{info['recording']}.csv" in r["output"].replace("\\", "/") and r.get("calibration_q")]
        if not mine:
            missing.append(info["recording"])
            continue
        python = bc.calibrate(Path(info["_recording"]))
        angles.append(quaternion_angle_deg(python["q_vehicle_from_device_wxyz"], mine[0]["calibration_q"]))
        lags_s.append((int(mine[0]["calibration_ns"]) - int(python["available_ns"])) / 1e9)
    return {"trips_compared": len(angles), "trips_without_kotlin_calibration": missing,
            "angle_deg_median": round(float(np.median(angles)), 3) if angles else None, "angle_deg_max": round(float(np.max(angles)), 3) if angles else None,
            "release_minus_python_s_median": round(float(np.median(lags_s)), 2) if lags_s else None,
            "release_minus_python_s_max": round(float(np.max(lags_s)), 2) if lags_s else None}


def summarize(rows: list[dict], configs: tuple[str, ...]) -> dict:
    out = {}
    for sc in ev.SCENARIOS:
        out[sc.name] = {}
        for name in configs:
            out[sc.name][name] = ev.summarize([r for r in rows if r["scenario"] == sc.name and r["config"] == name], sc)
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=conv.DEFAULT_OUT)
    parser.add_argument("--split", choices=("development", "held_out", "all"), default="development")
    parser.add_argument("--configs", default=",".join(DEFAULT_CONFIGS), help=f"any of: {', '.join(CONFIGS)}")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--skip-run", action="store_true", help="score the engine outputs already on disk")
    parser.add_argument("--in-engine-calibration", action="store_true",
                        help="let the engine calibrate itself with the in-app batch calibrator instead of handing it the offline calibration")
    parser.add_argument("--tag", default="", help="suffix for the output folders, so a second experiment does not overwrite the first")
    args = parser.parse_args(argv)
    configs = tuple(c for c in args.configs.split(",") if c)
    unknown = [c for c in configs if c not in CONFIGS]
    if unknown:
        raise SystemExit(f"unknown configuration(s): {unknown}")
    root = args.root.resolve()
    suffix = f"_{args.tag}" if args.tag else ""
    work = ROOT / "data" / "processed" / "gsdc2023_eval_runs" / f"{args.split}{suffix}"
    out = ROOT / "reports" / f"gsdc_eval_{args.split}{suffix}"
    jobs, infos = [], []
    for recording in trips(root, args.split):
        j, info = plan_trip(recording, work, configs, args.in_engine_calibration)
        jobs += j
        infos.append(info)
        status = info["calibration_status"] if info["calibration_status"] != "valid" else f"calibrated at {info['calibration']['available_s']:.0f} s, outages {info['planned_outages']}"
        print(f"{info['recording']}: {status}", flush=True)
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    jobs_path, results_path = work / "jobs.json", work / "results.json"
    if not args.skip_run:
        jobs_path.write_text(json.dumps({"jobs": jobs}), encoding="utf-8")
        ev.run_edge(jobs_path, results_path, args.java_home)
    rows = score_all(infos, work, configs)
    keys = ["drive", "recording", "scenario", "config", "outcome", "start_ns", "end_ns", "duration_s", "distance_m", "error_start_m", "error_end_m",
            "error_max_m", "drift_pct", "dr_declared_after_s", "recovery_error_5s_m", "recovery_error_max10s_m", "recovery_time_s"]
    with open(out / "outages.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k) for k in keys})
    clean = [{k: v for k, v in i.items() if not k.startswith("_")} for i in infos]
    calibrated = [i for i in clean if i["calibration_status"] == "valid"]
    parity = calibration_parity(infos, work, results_path) if args.in_engine_calibration else None
    summary = {"dataset": "Google Smartphone Decimeter Challenge 2023-2024, train split (CC BY-NC-SA 3.0 IGO)", "split": args.split,
               "calibration_source": "in-app batch calibrator (Kotlin), found by the engine" if args.in_engine_calibration else "offline (Python), handed to the engine",
               "calibration_parity_with_python": parity,
               "trips": len(clean), "calibrated": len(calibrated), "drives_calibrated": len({i["drive"] for i in calibrated}),
               "reference": "NovAtel SPAN, 0.1 m; also used at 1 Hz as GNSS aiding with a declared 2.5 m accuracy",
               "mount_calibration": "offline, from the first minutes of each trip (tools/gsdc_batch_calibration.py)", "settle_s_after_calibration": SETTLE_S,
               "configs": {c: {"arm": CONFIGS[c][0], "profile": CONFIGS[c][1]} for c in configs},
               "scenarios": summarize(rows, configs), "trip_info": clean}
    (out / "summary.json").write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    for sc in ev.SCENARIOS:
        for c in configs:
            s = summary["scenarios"][sc.name][c]
            print(f"{sc.name:6s} {c:34s} scored {s['scored']:4d}  median {s.get('drift_pct_median', '-')}  p90 {s.get('drift_pct_p90', '-')}  "
                  f"under10 {s.get('pass_rate_incl_failures', '-')}  failed {s['engine_failed']}  not_nav {s['not_navigating_at_start']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
