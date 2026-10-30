"""N2 experiment: do richer causal features improve the anchored speed correction? (IO-VNBD, vehicle-frame IMU.)

Everything added here is known at the moment the outage starts (t0 = the last delivered fix) or later:
    bias_hat      accelerometer bias estimated over the GNSS-aided window before t0 as
                  (integral of a_long - change of fix speed) / window; the v3 features cannot see it
    v_dr_corr     v0 + I_long - bias_hat * h
    dv5, dv10     v0 minus the fix speed 5 s / 10 s earlier (recent trend)
    vmin10        lowest fix speed in the 10 s before t0 (stop-and-go proxy)
    pre_a5        mean longitudinal acceleration over the 5 s before t0

Protocol (fixed before looking at the held-out drives): feature sets are compared by leave-one-drive-out inside the
DEVELOPMENT drives only; the chosen set and the v3 baseline are then trained on the development drives and scored once on the
held-out drives. Proxy metrics only (speed RMSE and dead-reckoned drift along fix course + integrated yaw); the engine run is separate.
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

from training import speed_v3 as v3  # noqa: E402

DEV = ["m", "s2", "s4", "vta16", "vta8", "y1"]
HELD = ["s1", "s3c", "vfa01", "vta2", "vw3"]
BIAS_WINDOW_S = 20.0
EXTRA = ("bias_hat", "v_dr_corr", "dv5", "dv10", "vmin10", "pre_a5")


def _fix_speed_at(d: v3.Drive, t: float) -> float:
    k = int(np.searchsorted(d.fix_t, t, side="right")) - 1
    return float(d.fix_speed[max(k, 0)])


def extra_features(s: v3.Series, base: np.ndarray, t0: float, h: float) -> np.ndarray:
    d = s.d
    w = min(BIAS_WINDOW_S, t0 - d.t[0] - 1.0)
    if w >= 5.0:
        dv = _fix_speed_at(d, t0) - _fix_speed_at(d, t0 - w)
        bias = float(np.clip((s.integral(s.c_long, t0 - w, t0) - dv) / w, -0.5, 0.5))
    else:
        bias = 0.0
    v0 = base[0]
    lo = np.searchsorted(d.fix_t, t0 - 10.0, side="left")
    hi = np.searchsorted(d.fix_t, t0, side="right")
    vmin = float(np.min(d.fix_speed[lo:hi])) if hi > lo else v0
    pre = d.a_long[np.searchsorted(d.t, t0 - 5.0):np.searchsorted(d.t, t0, side="right")]
    return np.array([bias, base[-1] - bias * h, v0 - _fix_speed_at(d, t0 - 5.0), v0 - _fix_speed_at(d, t0 - 10.0), vmin,
                     float(np.mean(pre)) if len(pre) else 0.0])


def features(s: v3.Series, v0: float, t0: float, t: float, variant: str) -> np.ndarray:
    base = v3.features_at(s, v0, t0, t)
    if variant == "v3":
        return base
    ex = extra_features(s, base, t0, max(t - t0, 1e-3))
    if variant == "bias":                       # v3 + bias_hat + corrected integral
        return np.concatenate([base, ex[:2]])
    return np.concatenate([base, ex])           # "full"


def samples(d: v3.Drive, variant: str, stride_fixes: int = 4) -> dict:
    s = v3.Series(d)
    xs, ys, meta = [], [], []
    for k in range(0, len(d.fix_t), stride_fixes):
        t0, v0 = d.fix_t[k], d.fix_speed[k]
        if v0 < v3.MIN_START_SPEED_M_S or t0 < 5.0:
            continue
        for h in v3.HORIZONS_S:
            t = t0 + h
            if t > d.t[-1] or t > d.truth_t[-1]:
                continue
            f = features(s, v0, t0, t, variant)
            v_true = float(np.interp(t, d.truth_t, d.truth_speed))
            # target is the residual against the v3 anchor so the models are comparable
            xs.append(f); ys.append(v_true - (v0 + s.integral(s.c_long, t0, t))); meta.append((t0, h, v_true))
    return {"x": np.array(xs), "y": np.array(ys), "meta": np.array(meta)}


def _drift(d: v3.Drive, s: v3.Series, model: v3.Mlp | None, variant: str, v0: float, c0: float, t0: float, hz: float):
    taus = np.arange(0.0, hz + 1e-9, 1.0)
    if model is None:
        v = np.full(len(taus), v0)
    else:
        f = np.array([features(s, v0, t0, t0 + tau, variant) if tau > 0 else
                      np.concatenate([[v0, 1e-3, 0, 0, 0, 0, 0, 0, v0], np.zeros(len(model.mean) - 9)]) for tau in taus])
        anchor = np.array([v0 + s.integral(s.c_long, t0, t0 + tau) for tau in taus])
        v = np.maximum(anchor + model.predict(f), 0.0)
    heading = math.radians(c0) - np.array([s.integral(s.c_yaw, t0, t0 + tau) for tau in taus])
    east = np.sum(0.5 * (v[1:] * np.sin(heading[1:]) + v[:-1] * np.sin(heading[:-1])) * np.diff(taus))
    north = np.sum(0.5 * (v[1:] * np.cos(heading[1:]) + v[:-1] * np.cos(heading[:-1])) * np.diff(taus))
    la0 = np.interp(t0, d.truth_t, d.truth_lat); lo0 = np.interp(t0, d.truth_t, d.truth_lon)
    la1 = np.interp(t0 + hz, d.truth_t, d.truth_lat); lo1 = np.interp(t0 + hz, d.truth_t, d.truth_lon)
    tn = math.radians(la1 - la0) * v3.EARTH_R
    te = math.radians(lo1 - lo0) * v3.EARTH_R * math.cos(math.radians(la0))
    dist = math.hypot(tn, te)
    return None if dist < 5.0 else 100.0 * math.hypot(east - te, north - tn) / dist


def score(train: list[v3.Drive], test: list[v3.Drive], variant: str, hidden: int, epochs: int, stride: int = 10) -> dict:
    tr = [samples(d, variant) for d in train]
    model = v3.train_mlp(np.vstack([t["x"] for t in tr if len(t["x"])]), np.concatenate([t["y"] for t in tr if len(t["y"])]),
                         hidden, epochs)
    err, drift = [], {30: [], 60: []}
    for d in test:
        te = samples(d, variant)
        if not len(te["x"]):
            continue
        s = v3.Series(d)
        s_anchor = te["x"][:, 0] + 0  # placeholder to keep shapes obvious
        anchor = np.array([te["meta"][i, 2] - te["y"][i] for i in range(len(te["y"]))])
        pred = np.maximum(anchor + model.predict(te["x"]), 0.0)
        err.append(pred - te["meta"][:, 2])
        for k in range(0, len(d.fix_t), stride):
            t0, v0, c0 = d.fix_t[k], d.fix_speed[k], d.fix_course[k]
            if v0 < v3.MIN_START_SPEED_M_S or t0 < 5.0:
                continue
            for hz in (30, 60):
                if t0 + hz > d.t[-1] or t0 + hz > d.truth_t[-1]:
                    continue
                e = _drift(d, s, model, variant, v0, c0, t0, float(hz))
                if e is not None:
                    drift[hz].append(e)
    e = np.concatenate(err)
    return {"speed_rmse": round(float(np.sqrt(np.mean(e ** 2))), 3),
            "drift_median_pct": {str(h): round(float(np.median(v)), 2) for h, v in drift.items()},
            "drift_samples": {str(h): len(v) for h, v in drift.items()},
            "drift_values": {str(h): [round(x, 3) for x in v] for h, v in drift.items()}}


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("stage", choices=["dev", "held"])
    p.add_argument("--variants", default="v3,bias,full")
    p.add_argument("--hidden", type=int, default=16)
    p.add_argument("--epochs", type=int, default=60)
    p.add_argument("--chosen", default="")
    p.add_argument("--out", type=Path)
    a = p.parse_args(argv)
    drives = {n: v3.load_drive(n) for n in DEV + HELD}
    res = {}
    if a.stage == "dev":
        for variant in a.variants.split(","):
            folds = []
            for held in DEV:
                r = score([drives[n] for n in DEV if n != held], [drives[held]], variant, a.hidden, a.epochs)
                folds.append(r)
            pooled = {h: [x for f in folds for x in f["drift_values"][h]] for h in ("30", "60")}
            res[variant] = {"mean_fold_speed_rmse": round(float(np.mean([f["speed_rmse"] for f in folds])), 3),
                            "drift_median_pct": {h: round(float(np.median(v)), 2) for h, v in pooled.items()},
                            "drift_pass10": {h: round(float(np.mean(np.array(v) < 10.0)), 3) for h, v in pooled.items()}}
            print(variant, json.dumps(res[variant]), flush=True)
    else:
        for variant in ("v3", a.chosen):
            r = score([drives[n] for n in DEV], [drives[n] for n in HELD], variant, a.hidden, a.epochs)
            res[variant] = r
            print(variant, r["speed_rmse"], r["drift_median_pct"], flush=True)
    if a.out:
        a.out.write_text(json.dumps(res, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
