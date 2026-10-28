"""Write the Python-to-Kotlin parity fixture for Model A v3 and sync the app asset.

    python tools/make_speed_v3_golden.py

Outputs:
  mobile/app/src/main/assets/models/speed_estimator_v3_weights.json   byte copy of models/...
  mobile/app/src/test/resources/speed_v3_golden.json                  fixture read by SpeedV3ParityTest

The fixture has two parts. `model`: feature vectors with the residual the Python model predicts.
`tracker`: a short synthetic vehicle-frame stream, one GNSS anchor on the sample grid, and the
feature vector and speed estimate the Python code computes at several later sample times.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training import speed_v3 as sv  # noqa: E402

WEIGHTS = ROOT / "models" / "speed_estimator_v3_weights.json"
ASSET = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "models" / "speed_estimator_v3_weights.json"
GOLDEN = ROOT / "mobile" / "app" / "src" / "test" / "resources" / "speed_v3_golden.json"
ANCHOR_INDEX = 50
QUERY_INDICES = (60, 80, 120, 200, 300, 399)


def main() -> int:
    model = sv.Mlp.from_json(json.loads(WEIGHTS.read_text(encoding="utf-8")))
    rng = np.random.default_rng(7)

    # Stream on a 10 Hz grid with integer-nanosecond timestamps, as the engine sees them.
    n = 400
    t_ns = 5_000_000_000 + np.arange(n, dtype=np.int64) * 100_000_000
    t = t_ns / 1e9
    a_long = 0.6 * np.sin(t / 7.0) + rng.normal(0, 0.15, n)
    a_lat = 0.8 * np.sin(t / 11.0) + rng.normal(0, 0.2, n)
    yaw = 0.07 * np.sin(t / 13.0) + rng.normal(0, 0.01, n)
    drive = sv.Drive("golden", t, a_long, a_lat, yaw, t, np.zeros(n), np.zeros(n), np.zeros(n), np.zeros(n),
                     np.array([]), np.array([]), np.array([]))
    series = sv.Series(drive)
    v0 = 13.4
    t0 = float(t[ANCHOR_INDEX])
    queries = []
    for i in QUERY_INDICES:
        f = sv.features_at(series, v0, t0, float(t[i]))
        residual = float(model.predict(f.reshape(1, -1))[0])
        queries.append({"index": i, "features": f.tolist(), "residual": residual,
                        "speed": max(f[-1] + residual, 0.0), "integrated": max(f[-1], 0.0)})

    # Free-standing vectors spanning the feature ranges seen in training.
    x = rng.normal(size=(24, len(sv.FEATURES))) * model.std * 0.8 + model.mean
    vectors = [{"features": row.tolist(), "residual": float(model.predict(row.reshape(1, -1))[0])} for row in x]

    fixture = {
        "weights_sha256_note": "residuals were computed from models/speed_estimator_v3_weights.json",
        "model": vectors,
        "tracker": {
            "t_ns": t_ns.tolist(), "a_long": a_long.tolist(), "a_lat": a_lat.tolist(), "yaw": yaw.tolist(),
            "anchor_index": ANCHOR_INDEX, "anchor_speed": v0, "queries": queries,
        },
    }
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text(json.dumps(fixture) + "\n", encoding="utf-8", newline="\n")
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(WEIGHTS, ASSET)
    print(f"wrote {GOLDEN.relative_to(ROOT)} and {ASSET.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
