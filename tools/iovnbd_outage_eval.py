"""GNSS-outage evaluation of the navigation engine on IO-VNBD, scored against VBOX truth.

    python tools/iovnbd_outage_eval.py run      # plan, replay through the engine, score, plot
    python tools/iovnbd_outage_eval.py score    # re-score existing engine outputs

Input is tools/iovnbd_to_contract.py output (data/processed/iovnbd_eval). For every drive
whose batch calibration is valid, outages are planned on its VBOX-1 Hz recording, the
recording is replayed through the app's calibrating engine once per (arm, scenario) by the
edge task `runIovnbdEval`, and every outage is scored.

Scenarios follow the PS #26168 benchmark:
  d50    GNSS withheld until the vehicle has covered 50 m   (PS: drift < 5 m over 50 m)
  d1000  ... until 1 km                                      (PS: drift < 100 m over 1 km)
  t30    30 s outage
  t60    60 s outage
The headline gate for every scenario is drift < 10 % of distance travelled in the outage.

Truth is used for three things only: choosing where outages go (distance-based ends need
the distance), scoring, and plotting. It is never fed to the engine.

An outage is scored only when the engine was navigating with GNSS when it began. One that
overlaps an engine failure is counted as failed, never dropped. Outputs go to
reports/iovnbd_eval/: summary.json, summary.md, outages.csv and plots/.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import platform
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "processed" / "iovnbd_eval"
REPORT_BASE = ROOT / "reports" / "iovnbd_eval"
REPORT = REPORT_BASE

# Input sources. Every run uses VBOX GNSS at 1 Hz; they differ in where the inertial data comes from.
INPUTS = {
    "phone": {"key": "vbox_gnss_1hz", "suffix": "vbox1hz",
              "label": "phone IMU export (levelled accelerometer, planar gyro) + VBOX GNSS 1 Hz"},
    "vehicle": {"key": "vbox_vehicle_imu", "suffix": "vehimu",
                "label": "VBOX vehicle-frame yaw rate and acceleration (external IMU) + VBOX GNSS 1 Hz"},
}
INPUT = "phone"


def use_input(name: str) -> None:
    global INPUT, REPORT
    INPUT = name
    REPORT = REPORT_BASE / name


def session_info(conv: dict) -> dict:
    """Session entry for the selected input, always with a batch_calibration status."""
    info = conv["sessions"].get(INPUTS[INPUT]["key"], {})
    if "batch_calibration" not in info:
        info = {**info, "batch_calibration": {"status": "failed", "reasons": [info.get("detail", "unavailable")]}}
    return info
ORIGIN_NS = 1_000_000_000

ARMS = ("ins", "ekf_nhc", "ekf_nhc_ai", "ekf_nhc_v3", "ekf_nhc_v3_heldout", "app")
BASELINE = "hold_gnss"
ARMS_REPORT = ARMS + (BASELINE,)
ARM_LABELS = {
    BASELINE: "Baseline: hold last GNSS velocity",
    "ins": "INS only (no constraints)",
    "ekf_nhc": "EKF + NHC/ZUPT",
    "ekf_nhc_ai": "EKF + NHC/ZUPT + Model A v1",
    "ekf_nhc_v3": "EKF + NHC/ZUPT + speed correction v3 (in-sample weights)",
    "ekf_nhc_v3_heldout": "EKF + NHC/ZUPT + speed correction v3 (held-out weights)",
    "app": "App configuration",
}


@dataclass(frozen=True)
class Scenario:
    name: str
    duration_s: float | None = None     # fixed-time outage
    distance_m: float | None = None     # outage ends when truth covers this distance
    max_s: float = 0.0                  # distance outages that take longer are not planned
    pass_abs_m: float | None = None     # PS absolute bound, where it gives one


SCENARIOS = (
    Scenario("d50", distance_m=50.0, max_s=60.0, pass_abs_m=5.0),
    Scenario("d1000", distance_m=1000.0, max_s=150.0, pass_abs_m=100.0),
    Scenario("t30", duration_s=30.0),
    Scenario("t60", duration_s=60.0),
)
PASS_DRIFT_PCT = 10.0
CONVERGENCE_S = 120.0         # after the calibration is released, before the first outage
RECOVERY_S = 180.0            # GNSS-aided time between consecutive outages
MIN_START_SPEED_M_S = 5.0
EARTH_R = 6_371_000.0


# ---- data ----------------------------------------------------------------------------------

def load_truth(seq: str) -> dict[str, np.ndarray]:
    raw = np.genfromtxt(DATA / seq / "truth.csv", delimiter=",", names=True)
    t = raw["t_ns"].astype(np.int64)
    lat, lon = raw["latitude_deg"], raw["longitude_deg"]
    step = np.hypot(np.radians(np.diff(lat)) * EARTH_R,
                    np.radians(np.diff(lon)) * EARTH_R * np.cos(np.radians(lat[:-1])))
    return {"t": t, "lat": lat, "lon": lon, "speed": raw["speed_m_s"],
            "dist": np.concatenate([[0.0], np.cumsum(step)])}


def gnss_times(session_dir: Path) -> np.ndarray:
    out = []
    with open(session_dir / "measurements.jsonl", encoding="utf-8") as fh:
        for line in fh:
            if '"type":"gnss"' in line:
                out.append(int(json.loads(line)["event"]["t_ns"]))
    return np.array(out, dtype=np.int64)


def horizontal_error(lat, lon, tlat, tlon) -> np.ndarray:
    return np.hypot(np.radians(lat - tlat) * EARTH_R,
                    np.radians(lon - tlon) * EARTH_R * np.cos(np.radians(tlat)))


def read_nav(path: Path) -> dict[str, np.ndarray]:
    t, status, mode, lat, lon = [], [], [], [], []
    with open(path, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            t.append(int(row["t_ns"]))
            status.append(row["status"])
            mode.append(row["localization_mode"])
            lat.append(float(row["latitude_deg"]) if row["latitude_deg"] else np.nan)
            lon.append(float(row["longitude_deg"]) if row["longitude_deg"] else np.nan)
    return {"t": np.array(t, dtype=np.int64), "status": np.array(status), "mode": np.array(mode),
            "lat": np.array(lat), "lon": np.array(lon)}


# ---- planning ------------------------------------------------------------------------------

def plan_outages(truth: dict, fixes_ns: np.ndarray, scenario: Scenario, start_after_ns: int
                 ) -> list[tuple[int, int]]:
    """Non-overlapping outages, each starting just after a delivered fix while moving."""
    planned: list[tuple[int, int]] = []
    earliest = start_after_ns
    t_end_truth = int(truth["t"][-1])
    for fix in fixes_ns:
        if fix < earliest:
            continue
        start = int(fix) + 1                       # the fix at `fix` is still delivered
        speed = float(np.interp(start, truth["t"], truth["speed"]))
        if speed < MIN_START_SPEED_M_S:
            continue
        if scenario.duration_s is not None:
            end = start + int(scenario.duration_s * 1e9)
        else:
            d0 = float(np.interp(start, truth["t"], truth["dist"]))
            k = int(np.searchsorted(truth["dist"], d0 + scenario.distance_m))
            if k >= len(truth["t"]):
                break
            end = int(truth["t"][k])
            if (end - start) / 1e9 > scenario.max_s:
                continue
        if end > t_end_truth:
            break
        planned.append((start, end))
        earliest = end + int(RECOVERY_S * 1e9)
    return planned


def calibration_job(seq: str, cal: dict) -> dict:
    return {"id": f"batch-{seq}", "q_vehicle_from_device_wxyz": cal["q_vehicle_from_device_wxyz"],
            "gyro_bias_rad_s": cal["gyro_bias_rad_s"],
            "available_ns": str(ORIGIN_NS + int(cal["available_s"] * 1e9))}


# ---- scoring -------------------------------------------------------------------------------

def score_outage(nav: dict, truth: dict, start: int, end: int) -> dict:
    t = nav["t"]
    before = np.flatnonzero(t <= start)
    inside = np.flatnonzero((t > start) & (t < end))
    row = {"start_ns": start, "end_ns": end, "duration_s": round((end - start) / 1e9, 2)}
    d0 = float(np.interp(start, truth["t"], truth["dist"]))
    d1 = float(np.interp(end, truth["t"], truth["dist"]))
    row["distance_m"] = round(d1 - d0, 1)
    window = np.flatnonzero((t >= start) & (t <= end))
    if len(window) and np.any(nav["status"][window] == "failed"):
        return {**row, "outcome": "engine_failed"}
    if not len(before) or nav["mode"][before[-1]] not in ("fused", "gnss", "recovery") \
            or not np.isfinite(nav["lat"][before[-1]]):
        return {**row, "outcome": "not_navigating_at_start"}
    if not len(inside) or not np.isfinite(nav["lat"][inside[-1]]):
        return {**row, "outcome": "no_solution_in_outage"}
    i0, i1 = before[-1], inside[-1]

    def err(i: int) -> float:
        tl = np.interp(t[i], truth["t"], truth["lat"])
        tn = np.interp(t[i], truth["t"], truth["lon"])
        return float(horizontal_error(nav["lat"][i], nav["lon"][i], tl, tn))

    e_in = horizontal_error(nav["lat"][inside], nav["lon"][inside],
                            np.interp(t[inside], truth["t"], truth["lat"]),
                            np.interp(t[inside], truth["t"], truth["lon"]))
    dr = inside[nav["mode"][inside] == "dr"]
    end_error = err(i1)
    return {**row, "outcome": "scored",
            "error_start_m": round(err(i0), 2),
            "error_end_m": round(end_error, 2),
            "error_max_m": round(float(np.nanmax(e_in)), 2),
            "drift_pct": round(100.0 * end_error / row["distance_m"], 2) if row["distance_m"] > 1 else None,
            "dr_declared_after_s": round((t[dr[0]] - start) / 1e9, 2) if len(dr) else None}


def summarize(rows: list[dict], scenario: Scenario) -> dict:
    scored = [r for r in rows if r["outcome"] == "scored" and r["drift_pct"] is not None]
    drift = np.array([r["drift_pct"] for r in scored])
    end_err = np.array([r["error_end_m"] for r in scored])
    out = {
        "planned": len(rows),
        "scored": len(scored),
        "engine_failed": sum(r["outcome"] == "engine_failed" for r in rows),
        "not_navigating_at_start": sum(r["outcome"] == "not_navigating_at_start" for r in rows),
        "no_solution_in_outage": sum(r["outcome"] == "no_solution_in_outage" for r in rows),
    }
    if len(scored):
        out.update({
            "median_distance_m": round(float(np.median([r["distance_m"] for r in scored])), 1),
            "median_duration_s": round(float(np.median([r["duration_s"] for r in scored])), 1),
            "drift_pct_median": round(float(np.median(drift)), 2),
            "drift_pct_p90": round(float(np.percentile(drift, 90)), 2),
            "end_error_m_median": round(float(np.median(end_err)), 2),
            "end_error_m_p90": round(float(np.percentile(end_err, 90)), 2),
            "pass_rate_drift_lt_10pct": round(float(np.mean(drift < PASS_DRIFT_PCT)), 3),
        })
        # Failures count against the gate: they were outages the engine did not survive.
        attempted = len(scored) + out["engine_failed"]
        out["pass_rate_incl_failures"] = round(float(np.sum(drift < PASS_DRIFT_PCT)) / attempted, 3)
        if scenario.pass_abs_m is not None:
            out[f"pass_rate_end_error_lt_{scenario.pass_abs_m:g}m"] = round(
                float(np.mean(end_err < scenario.pass_abs_m)), 3)
        dr = [r["dr_declared_after_s"] for r in scored if r["dr_declared_after_s"] is not None]
        if dr:
            out["dr_declared_after_s_median"] = round(float(np.median(dr)), 2)
    return out


def fused_accuracy(nav: dict, truth: dict, outages: list[tuple[int, int]]) -> np.ndarray:
    """Horizontal error while GNSS-aided and at least 30 s clear of any outage."""
    t = nav["t"]
    keep = (nav["mode"] == "fused") & np.isfinite(nav["lat"])
    for s, e in outages:
        keep &= ~((t >= s) & (t <= e + 30_000_000_000))
    idx = np.flatnonzero(keep)
    return horizontal_error(nav["lat"][idx], nav["lon"][idx],
                            np.interp(t[idx], truth["t"], truth["lat"]),
                            np.interp(t[idx], truth["t"], truth["lon"]))


def read_fixes(session_dir: Path) -> np.ndarray:
    """(t_ns, lat, lon, speed, course_deg) of every GNSS record in a recording, in stream order."""
    out = []
    with open(session_dir / "measurements.jsonl", encoding="utf-8") as fh:
        for line in fh:
            if '"type":"gnss"' in line:
                d = json.loads(line)["event"]
                g = d["data"]
                if g["speed_m_s"] is not None and g["bearing_deg"] is not None:
                    out.append((int(d["t_ns"]), g["latitude_deg"], g["longitude_deg"],
                                g["speed_m_s"], g["bearing_deg"]))
    return np.array(out, dtype=float).reshape(-1, 5)


def score_hold_gnss(fixes: np.ndarray, truth: dict, start: int, end: int) -> dict:
    """Non-inertial baseline: after the last delivered fix, keep its speed and course constant.

    Uses only what the engine also receives (the GNSS stream before the outage), so it is the
    bar any inertial or learned method has to clear.
    """
    d0 = float(np.interp(start, truth["t"], truth["dist"]))
    d1 = float(np.interp(end, truth["t"], truth["dist"]))
    row = {"start_ns": start, "end_ns": end, "duration_s": round((end - start) / 1e9, 2),
           "distance_m": round(d1 - d0, 1)}
    k = int(np.searchsorted(fixes[:, 0], start, side="right")) - 1 if len(fixes) else -1
    if k < 0:
        return {**row, "outcome": "not_navigating_at_start"}
    t0, lat0, lon0, v, course = fixes[k]
    heading = math.radians(course)

    def error_at(t_ns: float) -> float:
        dt = (t_ns - t0) / 1e9
        lat = lat0 + math.degrees(v * dt * math.cos(heading) / EARTH_R)
        lon = lon0 + math.degrees(v * dt * math.sin(heading) / (EARTH_R * math.cos(math.radians(lat0))))
        return float(horizontal_error(np.array([lat]), np.array([lon]),
                                      np.interp(t_ns, truth["t"], truth["lat"]),
                                      np.interp(t_ns, truth["t"], truth["lon"]))[0])

    samples = np.arange(start, end + 1, 1_000_000_000)
    end_error = error_at(end)
    return {**row, "outcome": "scored", "error_start_m": round(error_at(float(start)), 2),
            "error_end_m": round(end_error, 2),
            "error_max_m": round(max(error_at(float(t)) for t in samples), 2),
            "drift_pct": round(100.0 * end_error / row["distance_m"], 2) if row["distance_m"] > 1 else None,
            "dr_declared_after_s": None}


# ---- plots ---------------------------------------------------------------------------------

def _plt():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    return plt


ARM_COLORS = {BASELINE: "#7f7f7f", "ins": "#d1495b", "ekf_nhc": "#edae49", "ekf_nhc_ai": "#00798c", "ekf_nhc_v3": "#2a9d8f", "ekf_nhc_v3_heldout": "#6a4c93", "app": "#30638e"}


def plot_drive(seq: str, truth: dict, navs: dict[str, dict], outages: list[tuple[int, int]],
               scenario: str, out: Path) -> None:
    plt = _plt()
    lat0, lon0 = truth["lat"][0], truth["lon"][0]
    to_xy = lambda la, lo: (np.radians(lo - lon0) * EARTH_R * math.cos(math.radians(lat0)) / 1000,
                            np.radians(la - lat0) * EARTH_R / 1000)
    fig, ax = plt.subplots(figsize=(8.5, 7.5), dpi=110)
    x, y = to_xy(truth["lat"], truth["lon"])
    ax.plot(x, y, color="#9aa0a6", lw=1.0, label="VBOX truth")
    for arm, nav in navs.items():
        for k, (s, e) in enumerate(outages):
            m = (nav["t"] > s) & (nav["t"] < e) & np.isfinite(nav["lat"])
            if m.sum() < 2:
                continue
            ox, oy = to_xy(nav["lat"][m], nav["lon"][m])
            ax.plot(ox, oy, color=ARM_COLORS[arm], lw=1.6, label=ARM_LABELS[arm] if k == 0 else None)
    for s, e in outages:
        sel = (truth["t"] >= s) & (truth["t"] <= e)
        tx, ty = to_xy(truth["lat"][sel], truth["lon"][sel])
        ax.plot(tx, ty, color="black", lw=2.2, alpha=0.35)
    ax.set_aspect("equal")
    ax.set_xlabel("east (km)")
    ax.set_ylabel("north (km)")
    ax.set_title(f"IO-VNBD {seq}: dead reckoning through {len(outages)} simulated "
                 f"GNSS outages ({scenario})\nthick grey = truth inside outages; coloured = engine output while GNSS withheld",
                 fontsize=10)
    ax.legend(loc="best", fontsize=8)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)


def plot_drift_cdf(per_arm: dict[str, dict[str, list[float]]], out: Path) -> None:
    plt = _plt()
    fig, axes = plt.subplots(1, len(SCENARIOS), figsize=(15, 4), dpi=110, sharey=True)
    for ax, sc in zip(axes, SCENARIOS):
        for arm in ARMS_REPORT:
            d = np.sort(np.array(per_arm[sc.name].get(arm, [])))
            if not len(d):
                continue
            ax.step(d, np.arange(1, len(d) + 1) / len(d), where="post", color=ARM_COLORS[arm],
                    label=ARM_LABELS[arm])
        ax.axvline(PASS_DRIFT_PCT, color="black", ls="--", lw=1)
        ax.set_xscale("log")
        ax.set_title(sc.name)
        ax.set_xlabel("drift (% of outage distance)")
        ax.grid(alpha=0.3, which="both")
    axes[0].set_ylabel("fraction of outages")
    axes[-1].legend(fontsize=7, loc="lower right")
    fig.suptitle("IO-VNBD outage drift by arm (dashed: PS gate, 10 %)", fontsize=11)
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)


def plot_error_growth(curves: dict[str, list[tuple[np.ndarray, np.ndarray]]], out: Path) -> None:
    plt = _plt()
    grid = np.arange(0, 60.01, 1.0)
    fig, ax = plt.subplots(figsize=(8, 5), dpi=110)
    for arm in ARMS:
        rows = [np.interp(grid, s, e, left=np.nan, right=np.nan) for s, e in curves.get(arm, [])]
        if not rows:
            continue
        m = np.vstack(rows)
        ax.plot(grid, np.nanmedian(m, axis=0), color=ARM_COLORS[arm], lw=2, label=ARM_LABELS[arm])
        ax.fill_between(grid, np.nanpercentile(m, 25, axis=0), np.nanpercentile(m, 75, axis=0),
                        color=ARM_COLORS[arm], alpha=0.15)
    ax.set_xlabel("time since GNSS withheld (s)")
    ax.set_ylabel("horizontal error (m), median and IQR")
    ax.set_yscale("log")
    ax.set_title("Error growth during 60 s outages, IO-VNBD")
    ax.grid(alpha=0.3, which="both")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out)
    plt.close(fig)


# ---- orchestration -------------------------------------------------------------------------

def eligible_drives() -> list[tuple[str, dict]]:
    index = json.loads((DATA / "index.json").read_text(encoding="utf-8"))
    out = []
    for seq in index["converted"]:
        out.append((seq, json.loads((DATA / seq / "conversion.json").read_text(encoding="utf-8"))))
    return out


HELDOUT_ARM = "ekf_nhc_v3_heldout"


def heldout_weights(seq: str) -> Path:
    """Weights trained without this drive: `python training/speed_v3.py export-heldout`."""
    return DATA / "heldout_weights" / f"speed_v3_without_{seq}.json"


def plan_all(arms: tuple[str, ...] | None = None) -> tuple[list[dict], dict]:
    """Engine jobs. `arms` restricts the run to those arms (the app-calibrator probe is then skipped)."""
    jobs, plan = [], {}
    chosen = tuple(a for a in ARMS if arms is None or a in arms)
    for seq, conv in eligible_drives():
        cal = session_info(conv)["batch_calibration"]
        session = DATA / seq / f"iovnbd-{seq}-{INPUTS[INPUT]['suffix']}"
        run_dir = DATA / seq / "runs" / INPUT
        if not (session / "measurements.jsonl").is_file():
            plan[seq] = {"skipped": "no recording for this input",
                         "reasons": [session_info(conv).get("detail")]}
            continue
        # The app's own calibrator on every drive, no outages: does it reach VALID?
        if arms is None:
            jobs.append({"session": session.as_posix(), "output": (run_dir / "app_calibrator.csv").as_posix(),
                         "arm": "app", "outages_ns": [], "calibration": None})
        if cal["status"] != "valid":
            plan[seq] = {"skipped": "batch calibration failed", "reasons": cal.get("reasons")}
            continue
        truth = load_truth(seq)
        fixes = gnss_times(session)
        start_after = ORIGIN_NS + int((cal["available_s"] + CONVERGENCE_S) * 1e9)
        plan[seq] = {}
        for sc in SCENARIOS:
            outages = plan_outages(truth, fixes, sc, start_after)
            plan[seq][sc.name] = outages
            for arm in chosen:
                job = {"session": session.as_posix(),
                       "output": (run_dir / f"{sc.name}__{arm}.csv").as_posix(),
                       "arm": arm, "outages_ns": [[str(s), str(e)] for s, e in outages],
                       "calibration": calibration_job(seq, cal)}
                if arm == HELDOUT_ARM:
                    if INPUT != "vehicle":
                        continue                       # v3 needs the vehicle-frame input it was trained on
                    weights = heldout_weights(seq)
                    if not weights.is_file():
                        if arms is None:
                            print(f"note: no held-out weights for {seq}; skipping {HELDOUT_ARM} there")
                            continue
                        raise SystemExit(f"missing {weights}: run `python training/speed_v3.py export-heldout` first")
                    job["speed_model"] = weights.as_posix()
                jobs.append(job)
    return jobs, plan


def run_edge(jobs_path: Path, results_path: Path, java_home: str | None) -> None:
    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
    env.setdefault("GRADLE_USER_HOME", str(ROOT / "mobile" / ".gradle-user-home"))
    mobile = ROOT / "mobile"
    if platform.system() == "Windows":
        cmd = [str(mobile / "gradlew.bat")]
    else:
        cmd = ["bash", str(mobile / "gradlew")]
    cmd += ["-q", "-p", str(ROOT / "edge"), "runIovnbdEval", f"-Pjobs={jobs_path}",
            f"-Presults={results_path}", "--console=plain"]
    subprocess.run(cmd, cwd=mobile, env=env, check=True)


def score_all(plan: dict, results: list[dict]) -> dict:
    REPORT.mkdir(parents=True, exist_ok=True)
    (REPORT / "plots").mkdir(exist_ok=True)
    by_output = {Path(r["output"]).resolve(): r for r in results}
    outage_rows: list[dict] = []
    per_arm: dict[str, dict[str, list[float]]] = {sc.name: {} for sc in SCENARIOS}
    growth: dict[str, list] = {}
    fused_err: dict[str, list[np.ndarray]] = {}
    calibrator = {}
    for seq, conv in eligible_drives():
        app_cal = by_output.get((DATA / seq / "runs" / INPUT / "app_calibrator.csv").resolve())
        if app_cal:
            calibrator[seq] = {
                "reached_valid_and_navigated": app_cal["first_position_ns"] is not None,
                "calibration_records": app_cal["calibrations"],
                "segment_rejections": {k: v for k, v in app_cal["diagnostics"].items()
                                       if k.startswith("CALIBRATION_SEGMENT")},
            }
        if "skipped" in plan.get(seq, {}):
            continue
        truth = load_truth(seq)
        fixes = read_fixes(DATA / seq / f"iovnbd-{seq}-{INPUTS[INPUT]['suffix']}")
        for sc in SCENARIOS:
            outages = [tuple(o) for o in plan[seq][sc.name]]
            for s, e in outages:
                r = score_hold_gnss(fixes, truth, s, e)
                outage_rows.append({"sequence": seq, "scenario": sc.name, "arm": BASELINE, **r})
                if r["outcome"] == "scored" and r["drift_pct"] is not None:
                    per_arm[sc.name].setdefault(BASELINE, []).append(r["drift_pct"])
            navs = {}
            for arm in ARMS:
                path = DATA / seq / "runs" / INPUT / f"{sc.name}__{arm}.csv"
                if not path.exists():
                    continue
                nav = read_nav(path)
                navs[arm] = nav
                for s, e in outages:
                    r = score_outage(nav, truth, s, e)
                    outage_rows.append({"sequence": seq, "scenario": sc.name, "arm": arm, **r})
                    if r["outcome"] == "scored" and r["drift_pct"] is not None:
                        per_arm[sc.name].setdefault(arm, []).append(r["drift_pct"])
                    if sc.name == "t60" and r["outcome"] == "scored":
                        m = (nav["t"] > s) & (nav["t"] < e) & np.isfinite(nav["lat"])
                        tt = nav["t"][m]
                        err = horizontal_error(nav["lat"][m], nav["lon"][m],
                                               np.interp(tt, truth["t"], truth["lat"]),
                                               np.interp(tt, truth["t"], truth["lon"]))
                        growth.setdefault(arm, []).append(((tt - s) / 1e9, err))
                if sc.name == "t60":
                    fused_err.setdefault(arm, []).append(fused_accuracy(nav, truth, outages))
            if sc.name == "d1000" and navs:
                plot_drive(seq, truth, navs, outages, sc.name, REPORT / "plots" / f"{seq}_d1000.png")

    summary_scen = {}
    for sc in SCENARIOS:
        summary_scen[sc.name] = {}
        for arm in ARMS_REPORT:
            rows = [r for r in outage_rows if r["scenario"] == sc.name and r["arm"] == arm]
            summary_scen[sc.name][arm] = summarize(rows, sc)
    fused = {}
    for arm, parts in fused_err.items():
        e = np.concatenate(parts) if parts else np.array([])
        if len(e):
            fused[arm] = {"samples": int(len(e)), "median_m": round(float(np.median(e)), 2),
                          "p95_m": round(float(np.percentile(e, 95)), 2)}
    restarts = {}
    for r in results:
        restarts[r["arm"]] = restarts.get(r["arm"], 0) + r.get("restarts_after_failure", 0)

    plot_drift_cdf(per_arm, REPORT / "plots" / "drift_cdf.png")
    plot_error_growth(growth, REPORT / "plots" / "error_growth_t60.png")
    with open(REPORT / "outages.csv", "w", encoding="utf-8", newline="") as fh:
        keys = ["sequence", "scenario", "arm", "outcome", "start_ns", "end_ns", "duration_s",
                "distance_m", "error_start_m", "error_end_m", "error_max_m", "drift_pct",
                "dr_declared_after_s"]
        w = csv.DictWriter(fh, fieldnames=keys, lineterminator="\n")
        w.writeheader()
        for r in outage_rows:
            w.writerow({k: r.get(k) for k in keys})

    drives = []
    for seq, conv in eligible_drives():
        cal = session_info(conv)["batch_calibration"]
        drives.append({"sequence": seq, "duration_s": conv["duration_s"],
                       "distance_km": round(conv["truth_distance_m"] / 1000, 1),
                       "accelerometer_frame": conv["accelerometer_frame"],
                       "batch_calibration": cal["status"],
                       "calibration_available_s": cal.get("available_s")})
    summary = {
        "dataset": "IO-VNBD synchronized phone/VBOX pairs, converted by tools/iovnbd_to_contract.py",
        "input": INPUT,
        "input_description": INPUTS[INPUT]["label"],
        "gnss_aiding": "VBOX receiver fixes at 1 Hz (phone GPS was logged only every ~9 s)",
        "calibration": "batch mount calibration from the drive's warm-up window (phone IMU + GNSS "
                       "aiding stream, no truth), released at the window end",
        "engine": "app CalibratingFusionEngine + FusionNavigationEngine via edge runIovnbdEval, "
                  "IO-VNBD rest/straightness profile",
        "pass_gate": f"drift < {PASS_DRIFT_PCT:g} % of distance travelled during the outage",
        "drives": drives,
        "app_calibrator": calibrator,
        "scenarios": summary_scen,
        "gnss_aided_accuracy": fused,
        "engine_restarts_after_failure": restarts,
    }
    (REPORT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    write_markdown(summary)
    return summary


def write_markdown(s: dict) -> None:
    lines = ["# IO-VNBD GNSS-outage evaluation", "",
             "Generated by `tools/iovnbd_outage_eval.py`; every number below comes from "
             "`summary.json` and `outages.csv` in this folder.", "",
             f"- Input: {s['input_description']}", f"- GNSS aiding: {s['gnss_aiding']}", f"- Calibration: {s['calibration']}",
             f"- Engine: {s['engine']}", f"- Gate: {s['pass_gate']}", "",
             "## Drives", "", "| Drive | Hours | km | Accelerometer frame | Batch calibration |",
             "|---|---:|---:|---|---|"]
    for d in s["drives"]:
        lines.append(f"| {d['sequence']} | {d['duration_s'] / 3600:.2f} | {d['distance_km']} | "
                     f"{d['accelerometer_frame']} | {d['batch_calibration']} |")
    for name, arms in s["scenarios"].items():
        lines += ["", f"## Scenario {name}", "",
                  "| Arm | Scored / planned | Failed | Median drift % | p90 drift % | Median end error m | "
                  "Pass < 10 % | Pass incl. failures |", "|---|---|---:|---:|---:|---:|---:|---:|"]
        for arm, v in arms.items():
            if not v.get("scored"):
                lines.append(f"| {ARM_LABELS[arm]} | 0 / {v['planned']} | {v['engine_failed']} | – | – | – | – | – |")
                continue
            lines.append(f"| {ARM_LABELS[arm]} | {v['scored']} / {v['planned']} | {v['engine_failed']} | "
                         f"{v['drift_pct_median']} | {v['drift_pct_p90']} | {v['end_error_m_median']} | "
                         f"{v['pass_rate_drift_lt_10pct']:.0%} | {v['pass_rate_incl_failures']:.0%} |")
    lines += ["", "## GNSS-aided accuracy (fused, 30 s clear of outages)", "",
              "| Arm | Samples | Median m | p95 m |", "|---|---:|---:|---:|"]
    for arm, v in s["gnss_aided_accuracy"].items():
        lines.append(f"| {ARM_LABELS[arm]} | {v['samples']} | {v['median_m']} | {v['p95_m']} |")
    lines += ["", "## App in-motion calibrator on the same drives", "",
              "| Drive | Reached VALID and navigated |", "|---|---|"]
    for seq, v in s["app_calibrator"].items():
        lines.append(f"| {seq} | {'yes' if v['reached_valid_and_navigated'] else 'no'} |")
    lines += ["", "## Plots", "", "- `plots/drift_cdf.png`", "- `plots/error_growth_t60.png`",
              "- `plots/<drive>_d1000.png` (one per drive)", ""]
    (REPORT / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("command", choices=["run", "score"])
    p.add_argument("--input", choices=sorted(INPUTS), default="phone")
    p.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    p.add_argument("--arms", help="comma-separated arm ids to run (results are merged into the existing file)")
    args = p.parse_args(argv)
    use_input(args.input)
    jobs_path = DATA / f"jobs_{INPUT}.json"
    results_path = DATA / f"results_{INPUT}.json"
    plan_path = DATA / f"plan_{INPUT}.json"
    if args.command == "run":
        subset = tuple(a.strip() for a in args.arms.split(",")) if args.arms else None
        if subset and (unknown := set(subset) - set(ARMS)):
            raise SystemExit(f"unknown arms: {sorted(unknown)}")
        jobs, plan = plan_all(subset)
        jobs_path.write_text(json.dumps({"jobs": jobs}), encoding="utf-8")
        plan_path.write_text(json.dumps(plan), encoding="utf-8")
        print(f"{len(jobs)} engine runs planned")
        if subset and results_path.is_file():
            part = results_path.with_name(results_path.stem + "_part.json")
            run_edge(jobs_path, part, args.java_home)
            merged = {r["output"]: r for r in json.loads(results_path.read_text(encoding="utf-8"))["results"]}
            merged.update({r["output"]: r for r in json.loads(part.read_text(encoding="utf-8"))["results"]})
            results_path.write_text(json.dumps({"results": list(merged.values())}), encoding="utf-8")
        else:
            run_edge(jobs_path, results_path, args.java_home)
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    results = json.loads(results_path.read_text(encoding="utf-8"))["results"]
    summary = score_all(plan, results)
    for name, arms in summary["scenarios"].items():
        for arm, v in arms.items():
            print(f"{name:6s} {arm:11s} scored {v['scored']:4d}/{v['planned']:4d} failed {v['engine_failed']:3d} "
                  f"median drift {v.get('drift_pct_median', '-')}%  pass {v.get('pass_rate_drift_lt_10pct', '-')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
