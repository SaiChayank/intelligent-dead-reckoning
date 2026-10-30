"""Write the Python-to-Kotlin parity fixture for the learned GNSS trust model and sync the app asset.

    python tools/make_gnss_trust_golden.py

Outputs:
  mobile/app/src/main/assets/models/gnss_trust_weights.json   byte copy of models/gnss_trust_weights.json
  mobile/app/src/test/resources/gnss_trust_golden.json        fixture read by GnssTrustParityTest

The fixture is a fix sequence (a stretch of a real drive, then hand-built edge cases: stationary, missing speed, missing
course, a gap over 15 s, an out-of-order time) with, for every fix, the feature vector and predicted error that the
Python code (`training/gnss_trust.py`) gives, or null where it cannot form a feature vector.
"""
from __future__ import annotations

import json
import math
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training import gnss_trust as gt  # noqa: E402
from training.speed_v3 import Mlp  # noqa: E402

WEIGHTS = ROOT / "models" / "gnss_trust_weights.json"
ASSET = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "models" / "gnss_trust_weights.json"
GOLDEN = ROOT / "mobile" / "app" / "src" / "test" / "resources" / "gnss_trust_golden.json"


def real_fixes(seq: str = "m", count: int = 60) -> list[dict]:
    out = []
    with open(gt.DATA / seq / f"iovnbd-{seq}" / "measurements.jsonl", encoding="utf-8") as fh:
        for line in fh:
            if '"type":"gnss"' not in line:
                continue
            e = json.loads(line)["event"]
            g = e["data"]
            out.append({"t_ns": int(e["t_ns"]), "lat": g["latitude_deg"], "lon": g["longitude_deg"],
                        "accuracy_m": g["horizontal_accuracy_m"], "satellites": g["satellites_used"],
                        "speed_m_s": g["speed_m_s"], "course_deg": g["bearing_deg"]})
            if len(out) >= count:
                break
    return out


def edge_cases(last: dict) -> list[dict]:
    t = last["t_ns"]
    lat, lon = last["lat"], last["lon"]
    step = lambda dt_s, dn_m, de_m, **kw: {  # noqa: E731
        "t_ns": t + int(dt_s * 1e9), "lat": lat + dn_m / 111_000.0, "lon": lon + de_m / (111_000.0 * math.cos(math.radians(lat))),
        "accuracy_m": 4.0, "satellites": 9, "speed_m_s": 10.0, "course_deg": 0.0, **kw}
    cases = [
        step(1.0, 10.0, 0.0),                                    # moving north, course agrees
        step(2.0, 20.0, 0.0, course_deg=90.0),                   # moving north, course 90 degrees off
        step(3.0, 20.0, 0.0, speed_m_s=0.0, course_deg=10.0),    # stationary: displacement under 1 m/s
        step(4.0, 20.0, 0.0, speed_m_s=None),                    # missing speed: no features
        step(5.0, 30.0, 0.0, course_deg=None),                   # missing course: no features
        step(6.0, 40.0, 0.0, accuracy_m=None),                   # missing accuracy: no features
        step(7.0, 50.0, 0.0, satellites=None),                   # missing satellites counts as 0
        step(30.0, 60.0, 0.0),                                   # gap over 15 s: no features
        step(31.0, 70.0, 5.0, accuracy_m=0.05),                  # accuracy under 0.1 m: log clamps at 0.1
        step(30.5, 80.0, 5.0),                                   # time goes backwards: no features
    ]
    # make the later cases chain from each other in time (step() is relative to the last real fix)
    return cases


def main() -> int:
    j = json.loads(WEIGHTS.read_text(encoding="utf-8"))
    model = Mlp.from_json(j)
    model.y_mean = j["y_mean"]   # type: ignore[attr-defined]
    fixes = real_fixes()
    fixes += edge_cases(fixes[-1])
    prev = None
    rows = []
    for f in fixes:
        cur = (f["t_ns"], f["lat"], f["lon"])
        feats = gt.feature_row(prev, cur, f["accuracy_m"], f["satellites"], f["speed_m_s"], f["course_deg"])
        entry = dict(f)
        if feats is None:
            entry["features"] = None
            entry["predicted_error_m"] = None
        else:
            x = np.array(feats, dtype=float).reshape(1, -1)
            entry["features"] = feats
            entry["predicted_error_m"] = float(gt.predict_error_m(model, x)[0])
        rows.append(entry)
        prev = cur
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(WEIGHTS, ASSET)
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text(json.dumps({"fixes": rows}, indent=1) + "\n", encoding="utf-8")
    formed = sum(1 for r in rows if r["features"] is not None)
    print(f"wrote {GOLDEN.relative_to(ROOT)} ({len(rows)} fixes, {formed} with features) and the app asset")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
