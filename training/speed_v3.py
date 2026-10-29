"""Model A v3: learned speed correction for GNSS-denied dead reckoning, anchored to the last fix.

Why this form. IO-VNBD data is 10 Hz and its phone accelerometer export is not a usable inertial
signal (reports/gap_closure_log.md), so a vibration-based speed estimator cannot be trained on it.
What an outage actually needs is the speed change since the last GNSS fix. Integrated longitudinal
acceleration gives a first estimate, v_dr = v0 + integral(a_long); the model learns the residual
from quantities that exist at run time and nothing else:

    features at time t, outage start t0 (the last delivered fix), h = t - t0
        v0                 speed of the last fix (m/s)
        h                  seconds since that fix
        I_long             integral of longitudinal acceleration over [t0, t]
        I_long / h         mean acceleration over the outage so far
        mean |yaw rate|    over [t0, t]: cornering raises the error of the longitudinal estimate
        std(a_long, 3 s)   recent longitudinal activity
        mean(a_long, 3 s)  recent longitudinal acceleration
        std(a_lat, 3 s)    recent lateral activity
        v_dr               v0 + I_long
    target: v_true(t) - v_dr   (VBOX Doppler speed, used as a label only)

Inertial input is the vehicle-frame yaw rate and longitudinal/lateral acceleration (the external-IMU
case, tools/iovnbd_to_contract.py "vbox_vehicle_imu"). Everything is causal: tests change future
samples and require identical features.

Evaluation is leave-one-drive-out. Beyond speed error it scores what the PS cares about, position
drift over an outage: speed from each variant is integrated along the heading obtained from the last
fix's course plus the integrated yaw rate, and compared with truth. Variants: hold last speed,
integrated acceleration, and the learned correction.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

DATA = ROOT / "data" / "processed" / "iovnbd_eval"
EARTH_R = 6_371_000.0
FEATURES = ("v0", "h", "i_long", "i_long_per_s", "mean_abs_yaw", "std_a_long_3s", "mean_a_long_3s",
            "std_a_lat_3s", "v_dr")
HORIZONS_S = (2, 5, 10, 15, 20, 30, 45, 60)
RECENT_S = 3.0
MIN_START_SPEED_M_S = 3.0
SEED = 42


@dataclass(frozen=True)
class Drive:
    name: str
    t: np.ndarray            # s, phone clock, 10 Hz
    a_long: np.ndarray
    a_lat: np.ndarray
    yaw: np.ndarray          # rad/s, counter-clockwise, bias removed
    truth_t: np.ndarray
    truth_speed: np.ndarray
    truth_lat: np.ndarray
    truth_lon: np.ndarray
    truth_heading: np.ndarray    # degrees clockwise from north
    fix_t: np.ndarray        # s, GNSS fixes delivered to the engine
    fix_speed: np.ndarray
    fix_course: np.ndarray   # degrees


def load_drive(name: str) -> Drive:
    base = DATA / name
    conv = json.loads((base / "conversion.json").read_text(encoding="utf-8"))["sessions"]["vbox_vehicle_imu"]
    if "batch_calibration" not in conv:
        raise FileNotFoundError(f"{name}: no vehicle-IMU recording")
    bias = conv["batch_calibration"]["gyro_bias_rad_s"][2]
    t, ax, ay, yaw = [], [], [], []
    ft, fv, fc = [], [], []
    with open(base / f"iovnbd-{name}-vehimu" / "measurements.jsonl", encoding="utf-8") as fh:
        for line in fh:
            e = json.loads(line)["event"]
            d = e["data"]
            if e["type"] == "imu":
                if d["sensor"] == "accelerometer":
                    t.append(int(e["t_ns"]) / 1e9); ax.append(d["xyz"][0]); ay.append(d["xyz"][1])
                else:
                    yaw.append(d["xyz"][2] - bias)
            elif e["type"] == "gnss" and d["speed_m_s"] is not None and d["bearing_deg"] is not None:
                ft.append(int(e["t_ns"]) / 1e9); fv.append(d["speed_m_s"]); fc.append(d["bearing_deg"])
    truth = np.genfromtxt(base / "truth.csv", delimiter=",", names=True)
    origin = 1.0
    return Drive(name, np.array(t) - origin, np.array(ax), np.array(ay), np.array(yaw),
                 truth["t_ns"] / 1e9 - origin, truth["speed_m_s"], truth["latitude_deg"],
                 truth["longitude_deg"], truth["heading_deg"],
                 np.array(ft) - origin, np.array(fv), np.array(fc))


def _cumtrapz(t: np.ndarray, y: np.ndarray) -> np.ndarray:
    return np.concatenate([[0.0], np.cumsum(0.5 * (y[1:] + y[:-1]) * np.diff(t))])


class Series:
    """Cumulative integrals of one drive, so any [t0, t] integral is two lookups."""

    def __init__(self, d: Drive):
        self.d = d
        self.c_long = _cumtrapz(d.t, d.a_long)
        self.c_yaw = _cumtrapz(d.t, d.yaw)
        self.c_abs_yaw = _cumtrapz(d.t, np.abs(d.yaw))

    def integral(self, cum: np.ndarray, t0: float, t1: float) -> float:
        return float(np.interp(t1, self.d.t, cum) - np.interp(t0, self.d.t, cum))


def features_at(s: Series, v0: float, t0: float, t: float) -> np.ndarray:
    """Feature vector for time t given the outage began at t0. Reads only samples with time <= t."""
    d = s.d
    h = max(t - t0, 1e-3)
    i_long = s.integral(s.c_long, t0, t)
    i_hi = int(np.searchsorted(d.t, t, side="right"))
    i_lo = int(np.searchsorted(d.t, t - RECENT_S, side="left"))
    a = d.a_long[i_lo:i_hi]
    b = d.a_lat[i_lo:i_hi]
    return np.array([
        v0, h, i_long, i_long / h, s.integral(s.c_abs_yaw, t0, t) / h,
        float(np.std(a)) if len(a) > 1 else 0.0, float(np.mean(a)) if len(a) else 0.0,
        float(np.std(b)) if len(b) > 1 else 0.0, v0 + i_long,
    ])


def build_samples(d: Drive, stride_fixes: int = 4) -> dict:
    s = Series(d)
    xs, ys, meta = [], [], []
    for k in range(0, len(d.fix_t), stride_fixes):
        t0, v0 = d.fix_t[k], d.fix_speed[k]
        if v0 < MIN_START_SPEED_M_S or t0 < 5.0:
            continue
        for h in HORIZONS_S:
            t = t0 + h
            if t > d.t[-1] or t > d.truth_t[-1]:
                continue
            f = features_at(s, v0, t0, t)
            v_true = float(np.interp(t, d.truth_t, d.truth_speed))
            xs.append(f); ys.append(v_true - f[-1]); meta.append((t0, h, v_true))
    return {"x": np.array(xs), "y": np.array(ys), "meta": np.array(meta)}


# ---- model -------------------------------------------------------------------------------------

class Mlp:
    """Two-hidden-layer tanh MLP with standardised inputs; numpy inference, torch training."""

    def __init__(self, mean, std, w1, b1, w2, b2, w3, b3, y_scale):
        self.mean, self.std = mean, std
        self.w1, self.b1, self.w2, self.b2, self.w3, self.b3 = w1, b1, w2, b2, w3, b3
        self.y_scale = y_scale

    def predict(self, x: np.ndarray) -> np.ndarray:
        z = (x - self.mean) / self.std
        z = np.tanh(z @ self.w1.T + self.b1)
        z = np.tanh(z @ self.w2.T + self.b2)
        return (z @ self.w3.T + self.b3).reshape(-1) * self.y_scale

    def to_json(self) -> dict:
        return {"features": list(FEATURES), "mean": self.mean.tolist(), "std": self.std.tolist(),
                "y_scale": float(self.y_scale),
                "w1": self.w1.tolist(), "b1": self.b1.tolist(), "w2": self.w2.tolist(),
                "b2": self.b2.tolist(), "w3": self.w3.tolist(), "b3": self.b3.tolist()}

    @staticmethod
    def from_json(j: dict) -> "Mlp":
        a = lambda k: np.array(j[k], dtype=float)
        return Mlp(a("mean"), a("std"), a("w1"), a("b1"), a("w2"), a("b2"), a("w3"), a("b3"), j["y_scale"])


def train_mlp(x: np.ndarray, y: np.ndarray, hidden: int = 16, epochs: int = 60, seed: int = SEED) -> Mlp:
    import torch
    torch.manual_seed(seed)
    mean, std = x.mean(0), x.std(0) + 1e-6
    y_scale = float(np.std(y)) + 1e-6
    xt = torch.tensor((x - mean) / std, dtype=torch.float32)
    yt = torch.tensor(y / y_scale, dtype=torch.float32).reshape(-1, 1)
    net = torch.nn.Sequential(torch.nn.Linear(x.shape[1], hidden), torch.nn.Tanh(),
                              torch.nn.Linear(hidden, hidden), torch.nn.Tanh(), torch.nn.Linear(hidden, 1))
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=1e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
    loss_fn = torch.nn.HuberLoss(delta=1.0)
    g = torch.Generator().manual_seed(seed)
    for _ in range(epochs):
        perm = torch.randperm(len(xt), generator=g)
        for i in range(0, len(xt), 256):
            idx = perm[i:i + 256]
            opt.zero_grad()
            loss_fn(net(xt[idx]), yt[idx]).backward()
            opt.step()
        sched.step()
    w = [p.detach().numpy().astype(float) for p in net.parameters()]
    return Mlp(mean, std, w[0], w[1], w[2], w[3], w[4], w[5], y_scale)


# ---- evaluation ----------------------------------------------------------------------------------

def dead_reckon_error(s: Series, model: Mlp | None, variant: str, v0: float, course0: float,
                      t0: float, h: float) -> float | None:
    """Horizontal error at t0+h of a position built from this variant's speed and yaw-integrated heading."""
    d = s.d
    taus = np.arange(0.0, h + 1e-9, 1.0)
    if model is not None and variant == "learned":
        feats = np.array([features_at(s, v0, t0, t0 + tau) if tau > 0 else
                          np.array([v0, 1e-3, 0, 0, 0, 0, 0, 0, v0]) for tau in taus])
        v = np.maximum(feats[:, -1] + model.predict(feats), 0.0)
    elif variant == "integrated":
        v = np.array([max(v0 + s.integral(s.c_long, t0, t0 + tau), 0.0) for tau in taus])
    else:
        v = np.full(len(taus), v0)
    heading = math.radians(course0) - np.array([s.integral(s.c_yaw, t0, t0 + tau) for tau in taus])
    east = np.sum(0.5 * (v[1:] * np.sin(heading[1:]) + v[:-1] * np.sin(heading[:-1])) * np.diff(taus))
    north = np.sum(0.5 * (v[1:] * np.cos(heading[1:]) + v[:-1] * np.cos(heading[:-1])) * np.diff(taus))
    lat0 = np.interp(t0, d.truth_t, d.truth_lat); lon0 = np.interp(t0, d.truth_t, d.truth_lon)
    lat1 = np.interp(t0 + h, d.truth_t, d.truth_lat); lon1 = np.interp(t0 + h, d.truth_t, d.truth_lon)
    # The outage starts at the last fix, whose own error is not part of the dead-reckoned drift.
    t_n = math.radians(lat1 - lat0) * EARTH_R
    t_e = math.radians(lon1 - lon0) * EARTH_R * math.cos(math.radians(lat0))
    dist = math.hypot(t_n, t_e)
    if dist < 5.0:
        return None
    return 100.0 * math.hypot(east - t_e, north - t_n) / dist


def evaluate(drives: list[Drive], hidden: int, epochs: int, drift_horizons=(30, 60), stride=10) -> dict:
    series = {d.name: Series(d) for d in drives}
    data = {d.name: build_samples(d) for d in drives}
    out = {"speed_rmse": {}, "drift_pct_median": {}, "drift_pct_pass10": {}, "folds": []}
    pooled = {"hold": [], "integrated": [], "learned": [], "h": []}
    drift = {(h, v): [] for h in drift_horizons for v in ("hold", "integrated", "learned")}
    for held in drives:
        train_names = [d.name for d in drives if d.name != held.name]
        x = np.vstack([data[n]["x"] for n in train_names if len(data[n]["x"])])
        y = np.concatenate([data[n]["y"] for n in train_names if len(data[n]["y"])])
        model = train_mlp(x, y, hidden=hidden, epochs=epochs)
        te = data[held.name]
        if not len(te["x"]):
            continue
        v_true = te["meta"][:, 2]
        hold = te["x"][:, 0]
        integ = np.maximum(te["x"][:, -1], 0.0)
        learned = np.maximum(te["x"][:, -1] + model.predict(te["x"]), 0.0)
        pooled["hold"].append(hold - v_true); pooled["integrated"].append(integ - v_true)
        pooled["learned"].append(learned - v_true); pooled["h"].append(te["meta"][:, 1])
        out["folds"].append({"drive": held.name, "samples": int(len(v_true)),
                             "rmse_hold": round(float(np.sqrt(np.mean((hold - v_true) ** 2))), 3),
                             "rmse_integrated": round(float(np.sqrt(np.mean((integ - v_true) ** 2))), 3),
                             "rmse_learned": round(float(np.sqrt(np.mean((learned - v_true) ** 2))), 3)})
        sr = series[held.name]
        for k in range(0, len(held.fix_t), stride):
            t0, v0, c0 = held.fix_t[k], held.fix_speed[k], held.fix_course[k]
            if v0 < MIN_START_SPEED_M_S or t0 < 5.0:
                continue
            for hz in drift_horizons:
                if t0 + hz > held.t[-1] or t0 + hz > held.truth_t[-1]:
                    continue
                for variant in ("hold", "integrated", "learned"):
                    e = dead_reckon_error(sr, model, variant, v0, c0, t0, float(hz))
                    if e is not None:
                        drift[(hz, variant)].append(e)
    h = np.concatenate(pooled["h"])
    for variant in ("hold", "integrated", "learned"):
        err = np.concatenate(pooled[variant])
        out["speed_rmse"][variant] = {"all": round(float(np.sqrt(np.mean(err ** 2))), 3)}
        for hz in (10, 30, 60):
            m = h == hz
            out["speed_rmse"][variant][f"h{hz}"] = round(float(np.sqrt(np.mean(err[m] ** 2))), 3)
    for (hz, variant), vals in drift.items():
        v = np.array(vals)
        out["drift_pct_median"][f"h{hz}_{variant}"] = round(float(np.median(v)), 2) if len(v) else None
        out["drift_pct_pass10"][f"h{hz}_{variant}"] = round(float(np.mean(v < 10.0)), 3) if len(v) else None
    out["drift_samples"] = {f"h{hz}": len(drift[(hz, 'hold')]) for hz in drift_horizons}
    return out


def train_without(drives: list[Drive], held_out: str, hidden: int, epochs: int) -> Mlp:
    """Weights trained on every drive except `held_out`, for an engine run on that drive."""
    rest = [d for d in drives if d.name != held_out]
    if len(rest) == len(drives):
        raise ValueError(f"{held_out} is not among the drives")
    data = [build_samples(d) for d in rest]
    return train_mlp(np.vstack([d["x"] for d in data if len(d["x"])]),
                     np.concatenate([d["y"] for d in data if len(d["y"])]), hidden, epochs)


def export_heldout(drives: list[Drive], out_dir: Path, hidden: int, epochs: int) -> dict[str, Path]:
    """One weights file per drive, trained without that drive. Written outside the tracked models/ folder."""
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {}
    for d in drives:
        path = out_dir / f"speed_v3_without_{d.name}.json"
        path.write_text(json.dumps(train_without(drives, d.name, hidden, epochs).to_json()) + "\n", encoding="utf-8")
        paths[d.name] = path
    return paths


def available_drives() -> list[str]:
    idx = json.loads((DATA / "index.json").read_text(encoding="utf-8"))["converted"]
    names = []
    for s in idx:
        c = json.loads((DATA / s / "conversion.json").read_text(encoding="utf-8"))["sessions"]["vbox_vehicle_imu"]
        if "batch_calibration" in c:
            names.append(s)
    return names


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("command", choices=["evaluate", "export", "export-heldout"])
    p.add_argument("--hidden", type=int, default=16)
    p.add_argument("--epochs", type=int, default=60)
    p.add_argument("--out", type=Path, default=ROOT / "reports" / "speed_v3_eval.json")
    p.add_argument("--weights", type=Path, default=ROOT / "models" / "speed_estimator_v3_weights.json")
    p.add_argument("--heldout-dir", type=Path, default=DATA / "heldout_weights")
    args = p.parse_args(argv)
    drives = [load_drive(n) for n in available_drives()]
    if args.command == "evaluate":
        result = evaluate(drives, args.hidden, args.epochs)
        result["drives"] = [d.name for d in drives]
        result["protocol"] = "leave-one-drive-out; vehicle-frame IMU input; VBOX speed as label only"
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({k: result[k] for k in ("speed_rmse", "drift_pct_median", "drift_pct_pass10")}, indent=1))
    elif args.command == "export-heldout":
        for name, path in export_heldout(drives, args.heldout_dir, args.hidden, args.epochs).items():
            print("wrote", path)
    else:
        data = [build_samples(d) for d in drives]
        model = train_mlp(np.vstack([d["x"] for d in data]), np.concatenate([d["y"] for d in data]),
                          args.hidden, args.epochs)
        args.weights.write_text(json.dumps(model.to_json()) + "\n", encoding="utf-8")
        print("wrote", args.weights)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
