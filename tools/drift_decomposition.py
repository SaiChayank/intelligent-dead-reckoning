"""Break the residual GNSS-outage drift of one engine arm into heading, speed and starting-offset parts.

    python tools/drift_decomposition.py --arm ekf_nhc_v3_heldout_vel_nhcout --drives m s2 s4 vta16 vta8 y1

For each outage the engine's own output (heading and speed, as published) is compared with the VBOX truth:

* heading part   - dead-reckon with the ENGINE heading and the TRUTH speed from the TRUE position at the outage start; the
                   distance of that track's end from the true end is the error the heading alone would cause.
* speed part     - the same with the TRUTH heading and the ENGINE speed.
* start offset   - the engine's position error when the outage begins.
* actual error   - the engine's real end-of-outage error, split along and across the true direction of travel.

The two counterfactual parts are not additive (they interact), so shares are reported as squares normalised to one. Truth is
used only to score; nothing here changes the engine. Outages come from the evaluation plan, so the drive list should be the
pre-registered development drives (see reports/gap_closure_log.md, T4), not the held-out ones.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "processed" / "iovnbd_eval"
EARTH_R = 6_371_000.0


def local_xy(lat, lon, lat0, lon0):
    """East, north metres of lat/lon relative to an origin (small-area equirectangular)."""
    east = np.radians(np.asarray(lon) - lon0) * EARTH_R * np.cos(np.radians(lat0))
    north = np.radians(np.asarray(lat) - lat0) * EARTH_R
    return east, north


def dead_reckon(start_xy, headings_deg, speeds, dt):
    """Integrate speed along heading (degrees clockwise from north) from a start point; returns the end point."""
    h = np.radians(headings_deg)
    return start_xy[0] + float(np.sum(speeds * np.sin(h) * dt)), start_xy[1] + float(np.sum(speeds * np.cos(h) * dt))


def decompose_outage(t, truth_xy, truth_heading_deg, truth_speed, eng_xy, eng_heading_deg, eng_speed):
    """All arguments sampled on the same increasing time grid t (seconds) across one outage.

    Returns a dict of metres: heading_part, speed_part, start_offset, actual, along, across (signed along/across of the
    engine's end error relative to the true direction of travel)."""
    dt = np.gradient(t)
    start = (truth_xy[0][0], truth_xy[1][0])
    end_true = (truth_xy[0][-1], truth_xy[1][-1])
    # speed and heading in each interval are applied to the interval that follows the sample
    end_h = dead_reckon(start, eng_heading_deg, truth_speed, dt)
    end_s = dead_reckon(start, truth_heading_deg, eng_speed, dt)
    end_ref = dead_reckon(start, truth_heading_deg, truth_speed, dt)       # integration error of the truth channels themselves
    shift = (end_true[0] - end_ref[0], end_true[1] - end_ref[1])           # removed so only the channel difference remains
    heading_part = float(np.hypot(end_h[0] + shift[0] - end_true[0], end_h[1] + shift[1] - end_true[1]))
    speed_part = float(np.hypot(end_s[0] + shift[0] - end_true[0], end_s[1] + shift[1] - end_true[1]))
    start_offset = float(np.hypot(eng_xy[0][0] - truth_xy[0][0], eng_xy[1][0] - truth_xy[1][0]))
    de, dn = eng_xy[0][-1] - end_true[0], eng_xy[1][-1] - end_true[1]
    th = np.radians(truth_heading_deg[-1])
    along = float(de * np.sin(th) + dn * np.cos(th))
    across = float(de * np.cos(th) - dn * np.sin(th))
    return {"heading_part": heading_part, "speed_part": speed_part, "start_offset": start_offset,
            "actual": float(np.hypot(de, dn)), "along": along, "across": across,
            "distance": float(np.sum(truth_speed * dt)),
            # signed: engine minus truth speed, as a percentage of the truth speed, averaged over the outage
            "speed_bias_pct": float(100.0 * (np.sum(eng_speed * dt) - np.sum(truth_speed * dt)) / max(np.sum(truth_speed * dt), 1e-9)),
            "speed_error_at_end": float(eng_speed[-1] - truth_speed[-1])}


def _unwrap_deg(h):
    return np.degrees(np.unwrap(np.radians(h)))


def load_truth(seq):
    raw = np.genfromtxt(DATA / seq / "truth.csv", delimiter=",", names=True)
    return raw["t_ns"].astype(np.int64), raw["latitude_deg"], raw["longitude_deg"], raw["speed_m_s"], raw["heading_deg"]


def load_nav(path):
    t, lat, lon, spd, hdg = [], [], [], [], []
    with open(path, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["latitude_deg"] and r["heading_deg"] and r["speed_m_s"]:
                t.append(int(r["t_ns"])); lat.append(float(r["latitude_deg"])); lon.append(float(r["longitude_deg"]))
                spd.append(float(r["speed_m_s"])); hdg.append(float(r["heading_deg"]))
    return np.array(t, np.int64), np.array(lat), np.array(lon), np.array(spd), _unwrap_deg(np.array(hdg))


def rows_for_drive(seq, arm, scenarios, plan):
    tt, tlat, tlon, tspd, thdg = load_truth(seq)
    thdg = _unwrap_deg(thdg)
    lat0, lon0 = tlat[0], tlon[0]
    out = []
    for sc in scenarios:
        path = DATA / seq / "runs" / "vehicle" / f"{sc}__{arm}.csv"
        if not path.is_file() or sc not in plan.get(seq, {}):
            continue
        nt, nlat, nlon, nspd, nhdg = load_nav(path)
        nx, ny = local_xy(nlat, nlon, lat0, lon0)
        tx, ty = local_xy(tlat, tlon, lat0, lon0)
        for start_ns, end_ns in plan[seq][sc]:
            m = (tt >= start_ns) & (tt <= end_ns)
            if m.sum() < 5 or nt.min() > start_ns or nt.max() < end_ns:
                continue
            grid = tt[m]
            t = (grid - grid[0]) / 1e9
            interp = lambda ts, v: np.interp(grid, ts, v)
            rec = decompose_outage(
                t, (tx[m], ty[m]), thdg[m], tspd[m],
                (interp(nt, nx), interp(nt, ny)), interp(nt, nhdg), interp(nt, nspd))
            rec.update({"drive": seq, "scenario": sc, "start_ns": int(start_ns), "has_stop": bool(tspd[m].min() < 1.0)})
            if rec["distance"] > 1.0:
                out.append(rec)
    return out


def _med(values):
    return float(np.median(values)) if values else None


def summarise(rows):
    summary = {}
    for sc in sorted({r["scenario"] for r in rows}):
        rs = [r for r in rows if r["scenario"] == sc]
        h2 = np.array([r["heading_part"] ** 2 for r in rs])
        s2 = np.array([r["speed_part"] ** 2 for r in rs])
        o2 = np.array([r["start_offset"] ** 2 for r in rs])
        tot = h2 + s2 + o2
        pct = lambda k: float(np.median([100.0 * r[k] / r["distance"] for r in rs]))
        summary[sc] = {
            "outages": len(rs),
            "median_drift_pct": pct("actual"),
            "median_heading_part_pct_of_distance": pct("heading_part"),
            "median_speed_part_pct_of_distance": pct("speed_part"),
            "median_start_offset_pct_of_distance": pct("start_offset"),
            "median_abs_along_pct": float(np.median([100.0 * abs(r["along"]) / r["distance"] for r in rs])),
            "median_abs_across_pct": float(np.median([100.0 * abs(r["across"]) / r["distance"] for r in rs])),
            "outages_with_stop": int(sum(r["has_stop"] for r in rs)),
            "median_drift_pct_with_stop": _med([100.0 * r["actual"] / r["distance"] for r in rs if r["has_stop"]]),
            "median_drift_pct_without_stop": _med([100.0 * r["actual"] / r["distance"] for r in rs if not r["has_stop"]]),
            "median_signed_speed_bias_pct": float(np.median([r["speed_bias_pct"] for r in rs])),
            "median_abs_speed_bias_pct": float(np.median([abs(r["speed_bias_pct"]) for r in rs])),
            "fraction_speed_too_high": float(np.mean([r["speed_bias_pct"] > 0 for r in rs])),
            "share_heading": float(np.median(h2 / tot)),
            "share_speed": float(np.median(s2 / tot)),
            "share_start_offset": float(np.median(o2 / tot)),
        }
    return summary


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--arm", required=True)
    p.add_argument("--drives", nargs="+", required=True)
    p.add_argument("--scenarios", nargs="+", default=["d50", "t30", "t60", "d1000"])
    p.add_argument("--out", type=Path, default=ROOT / "reports" / "drift_decomposition.json")
    a = p.parse_args(argv)
    plan = json.loads((DATA / "plan_vehicle.json").read_text(encoding="utf-8"))
    rows = []
    for seq in a.drives:
        rows += rows_for_drive(seq, a.arm, a.scenarios, plan)
    if not rows:
        print("no outages found for those drives/arm", file=sys.stderr)
        return 2
    result = {"arm": a.arm, "drives": a.drives, "summary": summarise(rows)}
    a.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for sc, s in result["summary"].items():
        print(f"{sc:6s} n={s['outages']:3d} drift {s['median_drift_pct']:5.1f}%  heading {s['median_heading_part_pct_of_distance']:5.1f}%  "
              f"speed {s['median_speed_part_pct_of_distance']:5.1f}%  start {s['median_start_offset_pct_of_distance']:5.1f}%  "
              f"| along {s['median_abs_along_pct']:5.1f}% across {s['median_abs_across_pct']:5.1f}%  "
              f"| share h/s/o {s['share_heading']:.2f}/{s['share_speed']:.2f}/{s['share_start_offset']:.2f}"
              f" | speed bias median {s['median_signed_speed_bias_pct']:+.1f}% |bias| {s['median_abs_speed_bias_pct']:.1f}% too-high {s['fraction_speed_too_high']:.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
