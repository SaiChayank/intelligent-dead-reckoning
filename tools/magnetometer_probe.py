"""Is the in-vehicle phone magnetometer usable as a heading aid? Measured on IO-VNBD.

    python tools/magnetometer_probe.py

For every phone log (S-*.csv) with magnetic field and gravity columns, a tilt-compensated magnetic heading of one
device axis is compared with the phone GPS course (accurate to about 0.4 degrees against VBOX in these drives) at
speeds above 5 m/s. The mount yaw is unknown, so each of the four horizontal device axes is tried and the best
one is kept, with a single constant offset fitted per drive. That is generous to the magnetometer: it gets the mount
for free. The residual after the offset is what a heading aid would have to live with.

A second pass keeps only samples whose field magnitude is within 20 % of the drive's median (a crude interference
gate). Output: reports/magnetometer_probe.json and reports/magnetometer_probe.md. Declination is ignored; it is
small here and absorbed by the fitted offset.
"""
from __future__ import annotations

import glob
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw" / "iovnbd" / "Synchronised V abd S datasets"
OUT = ROOT / "reports"
AXES = {"+x": (1, 0, 0), "-x": (-1, 0, 0), "+y": (0, 1, 0), "-y": (0, -1, 0)}
MIN_SPEED_M_S = 5.0
GATE = 0.20


def horizontal_heading_deg(gravity: np.ndarray, field: np.ndarray, axis: np.ndarray) -> np.ndarray:
    """Heading (degrees clockwise from magnetic north) of a device axis, tilt-compensated with the gravity vector."""
    up = gravity / np.linalg.norm(gravity, axis=1)[:, None]
    east = np.cross(field, up)
    east /= np.linalg.norm(east, axis=1)[:, None]
    north = np.cross(up, east)
    return np.degrees(np.arctan2(east @ axis, north @ axis)) % 360.0


def residual_deg(course: np.ndarray, heading: np.ndarray) -> np.ndarray:
    """Course minus heading after removing the best constant offset (circular mean), wrapped to +-180."""
    offset = np.angle(np.mean(np.exp(1j * np.radians(course - heading))))
    return (course - heading - np.degrees(offset) + 180.0) % 360.0 - 180.0


def best_axis(gravity, field, course) -> tuple[str, np.ndarray]:
    best = None
    for name, axis in AXES.items():
        res = residual_deg(course, horizontal_heading_deg(gravity, field, np.array(axis, float)))
        if best is None or res.std() < best[1].std():
            best = (name, res)
    return best


def stats(res: np.ndarray) -> dict:
    return {"n": int(len(res)), "std_deg": float(res.std()), "median_abs_deg": float(np.median(np.abs(res))),
            "p90_abs_deg": float(np.percentile(np.abs(res), 90))}


def probe_file(path: Path) -> dict | None:
    import pandas as pd
    d = pd.read_csv(path, encoding="latin-1")
    d.columns = [c.strip() for c in d.columns]

    def col(prefix):
        hits = [c for c in d.columns if c.startswith(prefix)]
        return hits[0] if hits else None

    names = ["GRAVITY X", "GRAVITY Y", "GRAVITY Z", "MAGNETIC FIELD X", "MAGNETIC FIELD Y", "MAGNETIC FIELD Z",
             "GPS ORIENTATION", "GPS SPEED"]
    cols = [col(n) for n in names]
    if any(c is None for c in cols):
        return None
    g = d[cols[0:3]].to_numpy(float)
    m = d[cols[3:6]].to_numpy(float)
    course = d[cols[6]].to_numpy(float)
    speed = d[cols[7]].to_numpy(float) / 3.6
    ok = (np.isfinite(g).all(1) & np.isfinite(m).all(1) & np.isfinite(course) & np.isfinite(speed) &
          (speed > MIN_SPEED_M_S) & (np.linalg.norm(m, axis=1) > 0) & (np.linalg.norm(g, axis=1) > 5))
    g, m, course = g[ok], m[ok], course[ok]
    if len(g) < 200:
        return None
    axis, res = best_axis(g, m, course)
    mag = np.linalg.norm(m, axis=1)
    keep = np.abs(mag - np.median(mag)) <= GATE * np.median(mag)
    gated = None
    if keep.sum() >= 200:
        axis_g, res_g = best_axis(g[keep], m[keep], course[keep])
        gated = stats(res_g) | {"axis": axis_g, "kept_fraction": float(keep.mean())}
    return {"file": path.name, "axis": axis, **stats(res), "gated": gated}


def aggregate(rows: list[dict]) -> dict:
    n = np.array([r["n"] for r in rows], float)
    std = np.array([r["std_deg"] for r in rows])
    gated = [r for r in rows if r["gated"]]
    return {
        "files": len(rows),
        "median_of_file_std_deg": float(np.median(std)),
        "sample_weighted_std_deg": float(np.sqrt((n * std ** 2).sum() / n.sum())),
        "files_with_std_under_10_deg": int((std < 10).sum()),
        "gated_median_of_file_std_deg": float(np.median([r["gated"]["std_deg"] for r in gated])) if gated else None,
        "gated_files_with_std_under_10_deg": int(sum(r["gated"]["std_deg"] < 10 for r in gated)),
    }


def main() -> int:
    files = sorted(Path(p) for p in glob.glob(str(RAW / "**" / "S-*.csv"), recursive=True))
    rows = [r for r in (probe_file(f) for f in files) if r]
    if not rows:
        print("no usable phone logs found", file=sys.stderr)
        return 2
    summary = aggregate(rows)
    OUT.mkdir(exist_ok=True)
    (OUT / "magnetometer_probe.json").write_text(json.dumps({"summary": summary, "files": rows}, indent=2) + "\n", encoding="utf-8")
    lines = ["# Magnetometer heading probe (IO-VNBD phone logs)", "",
             "Residual of a tilt-compensated magnetic heading against phone GPS course, speed > 5 m/s, best of four device",
             "axes and one constant offset fitted per drive (the mount is given for free).", "",
             f"- Drives: {summary['files']}", f"- Median over drives of the residual std: {summary['median_of_file_std_deg']:.1f} deg",
             f"- Sample-weighted residual std: {summary['sample_weighted_std_deg']:.1f} deg",
             f"- Drives with residual std under 10 deg: {summary['files_with_std_under_10_deg']}",
             f"- With a field-magnitude gate (+-20 %): median {summary['gated_median_of_file_std_deg']:.1f} deg, "
             f"{summary['gated_files_with_std_under_10_deg']} drives under 10 deg", "",
             "| File | n | axis | std deg | median abs | p90 abs | gated std |", "|---|---:|---|---:|---:|---:|---:|"]
    for r in rows:
        g = f"{r['gated']['std_deg']:.1f}" if r["gated"] else "-"
        lines.append(f"| {r['file']} | {r['n']} | {r['axis']} | {r['std_deg']:.1f} | {r['median_abs_deg']:.1f} | {r['p90_abs_deg']:.1f} | {g} |")
    (OUT / "magnetometer_probe.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
