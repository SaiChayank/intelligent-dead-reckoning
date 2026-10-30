"""N6: is the 10 % drift target limited by the model or by the information in the inputs? (IO-VNBD, vehicle-frame IMU.)

The held-out drives are scored by models trained ON THEM (in-sample), with the richest feature set from N2 and larger networks. In-sample scoring is
deliberately unfair: it gives each model the answer's own drives, so it is an optimistic bound on what these inputs can support for this kind of model.
If even this stays well above 10 %, a better architecture on the same inputs cannot reach the target; the missing information has to come from elsewhere
(wheel speed, a 100 Hz vibration signal, a map). Proxy dead reckoning as in speed_v4_experiment.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training import speed_v3 as v3  # noqa: E402
from training import speed_v4_experiment as n2  # noqa: E402


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, default=ROOT / "reports" / "n6_information_bound.json")
    p.add_argument("--hidden", default="16,64")
    p.add_argument("--epochs", default="60,300")
    a = p.parse_args(argv)
    drives = [v3.load_drive(n) for n in n2.HELD]
    res = {"drives": n2.HELD, "protocol": "trained and scored on the same drives (in-sample bound); N2 'full' features; proxy dead reckoning"}
    for hidden, epochs in zip((int(x) for x in a.hidden.split(",")), (int(x) for x in a.epochs.split(","))):
        r = n2.score(drives, drives, "full", hidden, epochs)
        r.pop("drift_values")
        res[f"hidden{hidden}_epochs{epochs}"] = r
        print(hidden, epochs, r["speed_rmse"], r["drift_median_pct"], flush=True)
    a.out.write_text(json.dumps(res, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
