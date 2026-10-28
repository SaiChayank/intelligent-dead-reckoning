"""Learned GNSS trust: predict the horizontal error of a phone GPS fix from what the engine can see.

The filter weights a fix by a variance. Today that variance comes from the fix's reported
accuracy. This model predicts the actual error, so the variance (and the gate) can follow it.

Label: horizontal distance between the phone's fix and the VBOX position at the fix time (the
truth file written by tools/iovnbd_to_contract.py). Used as a label only.

Features: everything is available at fix time to the engine, with no truth and no future data:
    reported accuracy (m), satellites used, reported speed, seconds since the previous fix,
    speed consistency  |reported speed - displacement/dt| (m/s) against the previous fix,
    course consistency angle between the reported course and the displacement bearing (degrees,
    0 when nearly stationary), and the reported accuracy's log.

Model: log(1 + error) regressed by a small MLP (numpy inference, torch training), so the output
is a positive error estimate. Evaluation is leave-one-drive-out against the baseline the filter
uses now (error proportional to reported accuracy, with the scale fitted on the training drives).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training.speed_v3 import Mlp  # noqa: E402  (same tanh MLP container)

DATA = ROOT / "data" / "processed" / "iovnbd_eval"
EARTH_R = 6_371_000.0
FEATURES = ("accuracy_m", "log_accuracy", "satellites", "speed_m_s", "dt_s", "speed_consistency_m_s",
            "course_consistency_deg", "moving")
SEED = 42


def fixes_with_error(seq: str) -> tuple[np.ndarray, np.ndarray]:
    """(feature matrix, horizontal error in metres) for every usable phone fix of a drive."""
    truth = np.genfromtxt(DATA / seq / "truth.csv", delimiter=",", names=True)
    tt = truth["t_ns"].astype(np.int64)
    rows, errors = [], []
    prev = None
    with open(DATA / seq / f"iovnbd-{seq}" / "measurements.jsonl", encoding="utf-8") as fh:
        for line in fh:
            if '"type":"gnss"' not in line:
                continue
            e = json.loads(line)["event"]
            g = e["data"]
            t = int(e["t_ns"])
            cur = (t, g["latitude_deg"], g["longitude_deg"])
            acc, sats, spd, crs = g["horizontal_accuracy_m"], g["satellites_used"], g["speed_m_s"], g["bearing_deg"]
            if prev is not None and acc is not None and spd is not None and crs is not None \
                    and tt[0] <= t <= tt[-1]:
                dt = (t - prev[0]) / 1e9
                if 0.0 < dt <= 15.0:
                    d_n = math.radians(cur[1] - prev[1]) * EARTH_R
                    d_e = math.radians(cur[2] - prev[2]) * EARTH_R * math.cos(math.radians(prev[1]))
                    dist = math.hypot(d_n, d_e)
                    moving = dist / dt > 1.0
                    bearing = (math.degrees(math.atan2(d_e, d_n)) + 360.0) % 360.0
                    dcourse = abs((crs - bearing + 540.0) % 360.0 - 180.0) if moving else 0.0
                    lat_t = np.interp(t, tt, truth["latitude_deg"]); lon_t = np.interp(t, tt, truth["longitude_deg"])
                    err = math.hypot(math.radians(cur[1] - lat_t) * EARTH_R,
                                     math.radians(cur[2] - lon_t) * EARTH_R * math.cos(math.radians(lat_t)))
                    rows.append([acc, math.log(max(acc, 0.1)), float(sats or 0), spd, dt,
                                 abs(spd - dist / dt), dcourse, 1.0 if moving else 0.0])
                    errors.append(err)
            prev = cur
    return np.array(rows, dtype=float).reshape(-1, len(FEATURES)), np.array(errors, dtype=float)


def train(x: np.ndarray, err: np.ndarray, hidden: int = 12, epochs: int = 80, seed: int = SEED) -> Mlp:
    import torch
    torch.manual_seed(seed)
    y = np.log1p(err)
    mean, std = x.mean(0), x.std(0) + 1e-6
    y_mean, y_scale = float(y.mean()), float(y.std()) + 1e-6
    xt = torch.tensor((x - mean) / std, dtype=torch.float32)
    yt = torch.tensor((y - y_mean) / y_scale, dtype=torch.float32).reshape(-1, 1)
    net = torch.nn.Sequential(torch.nn.Linear(x.shape[1], hidden), torch.nn.Tanh(),
                              torch.nn.Linear(hidden, hidden), torch.nn.Tanh(), torch.nn.Linear(hidden, 1))
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=1e-3)
    g = torch.Generator().manual_seed(seed)
    for _ in range(epochs):
        perm = torch.randperm(len(xt), generator=g)
        for i in range(0, len(xt), 128):
            idx = perm[i:i + 128]
            opt.zero_grad()
            torch.nn.functional.huber_loss(net(xt[idx]), yt[idx]).backward()
            opt.step()
    w = [p.detach().numpy().astype(float) for p in net.parameters()]
    model = Mlp(mean, std, w[0], w[1], w[2], w[3], w[4], w[5], y_scale)
    model.y_mean = y_mean   # type: ignore[attr-defined]
    return model


def predict_error_m(model: Mlp, x: np.ndarray) -> np.ndarray:
    return np.expm1(model.predict(x) + model.y_mean) .clip(min=0.0)   # type: ignore[attr-defined]


def spearman(a: np.ndarray, b: np.ndarray) -> float:
    ra = np.argsort(np.argsort(a)); rb = np.argsort(np.argsort(b))
    return float(np.corrcoef(ra, rb)[0, 1])


def evaluate(names: list[str], hidden: int, epochs: int) -> dict:
    data = {n: fixes_with_error(n) for n in names}
    data = {n: v for n, v in data.items() if len(v[1]) >= 30}
    folds, learned_all, base_all, err_all = [], [], [], []
    for held, (xh, eh) in data.items():
        xs = np.vstack([v[0] for n, v in data.items() if n != held])
        es = np.concatenate([v[1] for n, v in data.items() if n != held])
        scale = float(np.median(es / np.maximum(xs[:, 0], 0.1)))          # baseline: error = k * reported accuracy
        model = train(xs, es, hidden, epochs)
        learned = predict_error_m(model, xh); base = scale * xh[:, 0]
        learned_all.append(learned); base_all.append(base); err_all.append(eh)
        folds.append({"drive": held, "fixes": int(len(eh)), "median_error_m": round(float(np.median(eh)), 2),
                      "mae_learned_m": round(float(np.mean(abs(learned - eh))), 2),
                      "mae_reported_accuracy_m": round(float(np.mean(abs(base - eh))), 2)})
    L, B, E = map(np.concatenate, (learned_all, base_all, err_all))
    out = {"fixes": int(len(E)), "folds": folds,
           "mae_m": {"learned": round(float(np.mean(abs(L - E))), 3), "reported_accuracy": round(float(np.mean(abs(B - E))), 3)},
           "spearman": {"learned": round(spearman(L, E), 3), "reported_accuracy": round(spearman(B, E), 3)}}
    # Does flagging the worst predicted fixes catch the worst real ones? Top-10 % recall.
    k = max(int(0.1 * len(E)), 1)
    worst = set(np.argsort(E)[-k:])
    out["top10pct_recall"] = {"learned": round(len(worst & set(np.argsort(L)[-k:])) / k, 3),
                              "reported_accuracy": round(len(worst & set(np.argsort(B)[-k:])) / k, 3)}
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("command", choices=["evaluate", "export"])
    p.add_argument("--out", type=Path, default=ROOT / "reports" / "gnss_trust_eval.json")
    p.add_argument("--weights", type=Path, default=ROOT / "models" / "gnss_trust_weights.json")
    args = p.parse_args(argv)
    names = json.loads((DATA / "index.json").read_text(encoding="utf-8"))["converted"]
    if args.command == "evaluate":
        res = evaluate(names, 12, 80)
        args.out.write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({k: res[k] for k in ("fixes", "mae_m", "spearman", "top10pct_recall")}, indent=1))
    else:
        data = [fixes_with_error(n) for n in names]
        data = [d for d in data if len(d[1]) >= 30]
        m = train(np.vstack([d[0] for d in data]), np.concatenate([d[1] for d in data]))
        j = m.to_json(); j["features"] = list(FEATURES); j["y_mean"] = m.y_mean   # type: ignore[attr-defined]
        args.weights.write_text(json.dumps(j) + "\n", encoding="utf-8")
        print("wrote", args.weights)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
