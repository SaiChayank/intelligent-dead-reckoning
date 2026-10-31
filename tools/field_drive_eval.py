"""Score the navigation engine on a drive recorded with the app: forced GNSS outages, drift measured against the fixes withheld.

    python tools/field_drive_eval.py <recording-dir> [<recording-dir> ...] [--out reports/field/<name>] [--arms app,ekf_nhc,ins]

A recording directory is what the app writes (`metadata.json` + `measurements.jsonl`, contract 1.0.0); pull it with the steps in
docs/FIELD_TEST_PROTOCOL.md. The engine replays the drive with the app's own calibrator settings (job profile "phone"), outages are cut
out of the GNSS stream after the warm-up, and the position the engine holds at the end of each outage is compared with the phone's
own GPS track.

WHAT THE NUMBERS ARE: the reference is the phone's GPS fixes (provider "gps"), not an independent receiver, so a drift figure carries
the phone GPS error (a few metres, more in an urban canyon) on top of the engine error. The report states the fixes' median reported
accuracy. For a defensible result record a second, better GNSS logger on the same vehicle and score against that instead.
Nothing here reads or prints positions beyond the summary statistics; the raw recording stays where it is.
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
import iovnbd_outage_eval as ev  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ARMS = ("app", "ekf_nhc", "ins")
WARMUP_S = 120.0
MIN_FIXES = 30


def load_fixes(recording: Path) -> dict[str, np.ndarray]:
    """GPS-provider fixes of a recording as a truth-like track: t (ns), lat, lon, speed, dist, plus the reported accuracy."""
    t, lat, lon, speed, acc = [], [], [], [], []
    with open(recording / "measurements.jsonl", encoding="utf-8") as handle:
        for line in handle:
            if "gnss" not in line:
                continue
            record = json.loads(line)
            data = record["event"]["data"]
            if record["event"]["type"] != "gnss" or data.get("provider") != "gps":
                continue
            t.append(int(record["event"]["t_ns"]))
            lat.append(data["latitude_deg"])
            lon.append(data["longitude_deg"])
            speed.append(np.nan if data.get("speed_m_s") is None else data["speed_m_s"])
            acc.append(np.nan if data.get("horizontal_accuracy_m") is None else data["horizontal_accuracy_m"])
    if len(t) < MIN_FIXES:
        raise SystemExit(f"{recording}: {len(t)} GPS fixes; need at least {MIN_FIXES} to score a drive "
                         "(a drive indoors or with location denied cannot be scored)")
    order = np.argsort(t)
    t, lat, lon, speed, acc = (np.array(x, dtype=float)[order] for x in (t, lat, lon, speed, acc))
    t = t.astype(np.int64)
    step = np.hypot(np.radians(np.diff(lat)) * ev.EARTH_R, np.radians(np.diff(lon)) * ev.EARTH_R * np.cos(np.radians(lat[:-1])))
    dist = np.concatenate([[0.0], np.cumsum(step)])
    dt = np.diff(t) / 1e9
    derived = np.concatenate([[0.0], np.where(dt > 0, step / np.where(dt > 0, dt, 1.0), 0.0)])
    speed = np.where(np.isfinite(speed), speed, derived)       # a fix without a Doppler speed falls back to the track speed
    return {"t": t, "lat": lat, "lon": lon, "speed": speed, "dist": dist, "accuracy": acc}


def plan(truth: dict, warmup_s: float) -> dict[str, list[tuple[int, int]]]:
    start_after = int(truth["t"][0]) + int(warmup_s * 1e9)
    return {sc.name: ev.plan_outages(truth, truth["t"], sc, start_after) for sc in ev.SCENARIOS}


def jobs_for(recording: Path, out: Path, planned: dict, arms: tuple[str, ...], calibration: dict | None = None,
             profile: str = "phone") -> list[dict]:
    jobs = []
    for sc_name, outages in planned.items():
        for arm in arms:
            jobs.append({"session": recording.as_posix(), "output": (out / "runs" / f"{sc_name}__{arm}.csv").as_posix(),
                         "arm": arm, "outages_ns": [[str(s), str(e)] for s, e in outages], "calibration": calibration,
                         "profile": profile})
    return jobs


def score(out: Path, truth: dict, planned: dict, arms: tuple[str, ...]) -> list[dict]:
    rows = []
    for sc in ev.SCENARIOS:
        for arm in arms:
            path = out / "runs" / f"{sc.name}__{arm}.csv"
            if not path.is_file():
                continue
            nav = ev.read_nav(path)
            for start, end in planned[sc.name]:
                rows.append({"scenario": sc.name, "arm": arm, **ev.score_outage(nav, truth, start, end)})
    return rows


def engine_report(results: list[dict], arms: tuple[str, ...], origin_ns: int) -> dict:
    """Why an arm did or did not navigate: when the first position appeared and the diagnostics the engine raised."""
    out = {}
    for arm in arms:
        mine = [r for r in results if r["arm"] == arm]
        if not mine:
            continue
        first_ns = mine[0].get("first_position_ns")
        out[arm] = {"first_position_s_after_first_fix": None if first_ns is None else round((int(first_ns) - origin_ns) / 1e9, 1),
                    "calibrations": mine[0].get("calibrations"), "diagnostics": mine[0].get("diagnostics"),
                    "first_messages": mine[0].get("first_messages")}
    return out


def write_outputs(out: Path, recording: Path, truth: dict, planned: dict, rows: list[dict], arms: tuple[str, ...], warmup_s: float,
                  results: list[dict] | None = None) -> dict:
    metadata = json.loads((recording / "metadata.json").read_text(encoding="utf-8")) if (recording / "metadata.json").is_file() else {}
    summary = {
        "data_kind": "FIELD: a drive recorded with the app" if metadata.get("source") == "real" else f"NOT A FIELD DRIVE (source={metadata.get('source')})",
        "recording_id": metadata.get("recording_id", recording.name),
        "device": " ".join(str(metadata.get("device", {}).get(k, "")) for k in ("manufacturer", "model")).strip() or None,
        "reference": "the phone's own GPS fixes (provider gps); not an independent receiver",
        "reference_fixes": int(len(truth["t"])),
        "reference_reported_accuracy_median_m": None if not np.isfinite(truth["accuracy"]).any() else round(float(np.nanmedian(truth["accuracy"])), 1),
        "duration_s": round(float(truth["t"][-1] - truth["t"][0]) / 1e9, 1),
        "distance_km": round(float(truth["dist"][-1]) / 1000, 2),
        "warmup_s": warmup_s,
        "planned_outages": {name: len(o) for name, o in planned.items()},
        "scenarios": {sc.name: {arm: ev.summarize([r for r in rows if r["scenario"] == sc.name and r["arm"] == arm], sc) for arm in arms}
                      for sc in ev.SCENARIOS},
        "mandate": "drift under 10 percent of distance travelled in the outage (PS 26168)",
        "engine": engine_report(results or [], arms, int(truth["t"][0])),
    }
    out.mkdir(parents=True, exist_ok=True)
    keys = ["scenario", "arm", "outcome", "start_ns", "end_ns", "duration_s", "distance_m", "error_start_m", "error_end_m", "error_max_m",
            "drift_pct", "dr_declared_after_s", "recovery_error_5s_m", "recovery_error_max10s_m", "recovery_time_s"]
    with open(out / "outages.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k) for k in keys})
    (out / "summary.json").write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    (out / "summary.md").write_text(markdown(summary, arms), encoding="utf-8")
    return summary


def markdown(summary: dict, arms: tuple[str, ...]) -> str:
    lines = [f"# Field drive {summary['recording_id']}", "", f"**{summary['data_kind']}**. Device: {summary['device']}.",
             f"{summary['duration_s']} s, {summary['distance_km']} km, {summary['reference_fixes']} GPS fixes as the reference",
             f"(median reported accuracy {summary['reference_reported_accuracy_median_m']} m).", "",
             f"Reference: {summary['reference']}. Drift below includes the reference's own error.", "",
             "| Scenario | Arm | Scored of planned | Median drift % | 90th pct drift % | Under 10 % | Failed | Not navigating at start |",
             "|---|---|---|---|---|---|---|---|"]
    for name, by_arm in summary["scenarios"].items():
        for arm in arms:
            s = by_arm[arm]
            lines.append(f"| {name} | {arm} | {s['scored']} of {s['planned']} | {s.get('drift_pct_median', '-')} | {s.get('drift_pct_p90', '-')} "
                         f"| {s.get('pass_rate_incl_failures', '-')} | {s['engine_failed']} | {s['not_navigating_at_start']} |")
    for arm, info in summary.get("engine", {}).items():
        if info["first_position_s_after_first_fix"] is None:
            lines += ["", f"**{arm}: the engine never produced a position.** Calibration messages:"]
            lines += [f"- `{code}`: {text}" for code, text in (info.get("first_messages") or {}).items() if code.startswith("CALIBRATION")]
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("recordings", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, help="default reports/field/<recording-id>")
    parser.add_argument("--arms", default=",".join(DEFAULT_ARMS))
    parser.add_argument("--warmup-s", type=float, default=WARMUP_S, help="driving time before the first outage, for the calibrator to lock")
    parser.add_argument("--calibration", type=Path, help="JSON {id, q_vehicle_from_device_wxyz, gyro_bias_rad_s, available_ns}: use this offline "
                        "mount calibration instead of the app's in-drive calibrator (for a drive where that calibrator never locks)")
    parser.add_argument("--profile", choices=("phone", "iovnbd"), default="phone",
                        help="phone: the app's own calibrator and stationary settings; iovnbd: the settings tuned for the IO-VNBD phone")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--plan-only", action="store_true", help="print the outage plan and stop (no engine run)")
    args = parser.parse_args(argv)
    arms = tuple(a for a in args.arms.split(",") if a)
    for recording in (r.resolve() for r in args.recordings):
        truth = load_fixes(recording)
        planned = plan(truth, args.warmup_s)
        print(f"{recording.name}: {len(truth['t'])} GPS fixes, {truth['dist'][-1] / 1000:.2f} km, planned outages "
              + ", ".join(f"{n}={len(o)}" for n, o in planned.items()))
        if args.plan_only:
            continue
        if not any(planned.values()):
            raise SystemExit("no outage can be planned (the drive is too short or too slow after the warm-up); lower --warmup-s or drive longer")
        out = (args.out or ROOT / "reports" / "field" / recording.name).resolve()
        calibration = json.loads(args.calibration.read_text(encoding="utf-8")) if args.calibration else None
        jobs = jobs_for(recording, out, planned, arms, calibration, args.profile)
        out.mkdir(parents=True, exist_ok=True)
        jobs_path, results_path = out / "jobs.json", out / "results.json"
        jobs_path.write_text(json.dumps({"jobs": jobs}), encoding="utf-8")
        ev.run_edge(jobs_path, results_path, args.java_home)
        results = json.loads(results_path.read_text(encoding="utf-8"))["results"]
        write_outputs(out, recording, truth, planned, score(out, truth, planned, arms), arms, args.warmup_s, results)
        print((out / "summary.md").read_text(encoding="utf-8"))
        print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
