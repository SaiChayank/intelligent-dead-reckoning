"""How good is the phone-grade aiding (tools/gsdc_wls_aiding.py) across the converted GSDC trips, split by development and held-out drive?

    python tools/gsdc_wls_quality.py [--root data/raw/gsdc2023] [--out reports/gsdc_wls_aiding_quality.json]

For every converted trip, position, speed and course error of the WLS and Doppler solution against the 0.1 m reference. Writes aggregate numbers per trip
and the percentiles across trips, per split; no position is written. The accuracy declared to the engine in `tools/gsdc_to_recording.py --aiding wls` is
taken from the DEVELOPMENT trips here (see `declared_accuracy_m`), never from the held-out ones.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gsdc_extract_gnss as ex  # noqa: E402
import gsdc_to_recording as conv  # noqa: E402
import gsdc_wls_aiding as wls  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT / "data" / "raw" / "gsdc2023")
    parser.add_argument("--processed", type=Path, default=ROOT / "data" / "processed" / "gsdc2023")
    parser.add_argument("--out", type=Path, default=ROOT / "reports" / "gsdc_wls_aiding_quality.json")
    args = parser.parse_args(argv)
    trips = []
    for drive, phone in ex.converted_trips(args.processed):
        folder = args.root / "train" / drive / phone
        if not (folder / "device_gnss.csv").is_file():
            continue
        q = wls.quality(wls.load_epochs(folder), wls.load_reference(folder))
        trips.append({"drive": drive, "phone": phone, "split": conv.split_of(drive), **q})
        print(f"{drive}/{phone}: position median {q['position_error_m']['median']} m, p95 {q['position_error_m']['p95']} m, speed p95 {q['speed_error_m_s']['p95_abs']} m/s",
              flush=True)

    def summarize(rows: list[dict]) -> dict:
        if not rows:
            return {}
        pick = lambda f: np.array([f(r) for r in rows if f(r) is not None], dtype=float)
        pct = lambda a: {"p10": round(float(np.percentile(a, 10)), 2), "median": round(float(np.median(a)), 2), "p90": round(float(np.percentile(a, 90)), 2)}
        return {"trips": len(rows), "position_error_median_m": pct(pick(lambda r: r["position_error_m"]["median"])),
                "position_error_p68_m": pct(pick(lambda r: r["position_error_m"]["p68"])), "position_error_p95_m": pct(pick(lambda r: r["position_error_m"]["p95"])),
                "speed_error_p95_m_s": pct(pick(lambda r: r["speed_error_m_s"]["p95_abs"] if r["speed_error_m_s"] else None)),
                "course_error_median_deg": pct(pick(lambda r: r["course_error_deg_moving"]["median_abs"] if r["course_error_deg_moving"] else None)),
                "doppler_solution_share": pct(pick(lambda r: r["doppler_solutions"] / r["epochs"]))}

    dev = [t for t in trips if t["split"] == "development"]
    out = {"dataset": "Google Smartphone Decimeter Challenge 2023-2024, train split (CC BY-NC-SA 3.0 IGO)",
           "aiding": "WLS position from the raw pseudoranges and a Doppler velocity solved per epoch (tools/gsdc_wls_aiding.py)",
           "development": summarize(dev), "held_out": summarize([t for t in trips if t["split"] == "held_out"]),
           "declared_accuracy_m": round(float(np.median([t["position_error_m"]["p68"] for t in dev])), 1) if dev else None,
           "declared_accuracy_rule": "median across development trips of each trip's 68th percentile horizontal error", "trips": trips}
    args.out.write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    print("declared accuracy for the engine:", out["declared_accuracy_m"], "m")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
