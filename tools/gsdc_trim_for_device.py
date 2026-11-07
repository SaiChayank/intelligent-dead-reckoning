"""Cut the first minutes of converted GSDC trips so they can be pushed to a phone and replayed through the engine there.

    python tools/gsdc_trim_for_device.py [--seconds 700] [--phones pixel7pro,pixel6pro,sm-g988b] [--out data/processed/gsdc2023_device]

Reads the converted recordings made by tools/gsdc_to_recording.py (data/processed/gsdc2023, listed in a conversion*.json), keeps the first
N seconds of `measurements.jsonl` for up to one trip per requested phone model, and writes each to `<out>/<recording-id>/measurements.jsonl`.
Push them with `adb` as docs/GSDC_REAL_PHONE_DATA.md shows and run `RealDriveEngineDeviceTest`. The files are CC BY-NC-SA 3.0 IGO data and stay out
of the repository.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ORIGIN_NS = 1_000_000_000


def pick(conversion: Path, phones: list[str], min_seconds: float) -> list[dict]:
    trips = [t for t in json.loads(conversion.read_text(encoding="utf-8"))["trips"] if t["converted"]]
    chosen = []
    for phone in phones:
        for trip in trips:
            if trip["phone"] == phone and trip["gnss_fixes"] >= min_seconds:
                chosen.append(trip)
                break
    return chosen


def trim(source: Path, destination: Path, seconds: float) -> int:
    limit = ORIGIN_NS + int(seconds * 1e9)
    destination.parent.mkdir(parents=True, exist_ok=True)
    kept = 0
    with open(source, "rb") as src, open(destination, "wb") as dst:
        for line in src:
            marker = line.find(b'"t_ns":"')
            if marker < 0:
                continue
            start = marker + len(b'"t_ns":"')
            if int(line[start:line.index(b'"', start)]) > limit:
                break
            dst.write(line)
            kept += 1
    return kept


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT / "data" / "processed" / "gsdc2023")
    parser.add_argument("--out", type=Path, default=ROOT / "data" / "processed" / "gsdc2023_device")
    parser.add_argument("--seconds", type=float, default=700.0)
    parser.add_argument("--phones", default="pixel7pro,pixel6pro,sm-g988b")
    parser.add_argument("--conversion", type=Path, default=None, help="a conversion*.json from tools/gsdc_to_recording.py; default conversion_held_out.json, falling back to conversion.json")
    args = parser.parse_args(argv)
    conversion = args.conversion or next(p for p in (args.root / "conversion_held_out.json", args.root / "conversion.json") if p.is_file())
    trips = pick(conversion, [p for p in args.phones.split(",") if p], args.seconds + 100)
    if not trips:
        raise SystemExit("no matching converted trips")
    for trip in trips:
        source = args.root / trip["drive"] / trip["recording_id"] / "measurements.jsonl"
        kept = trim(source, args.out / trip["recording_id"] / "measurements.jsonl", args.seconds)
        print(f"{trip['recording_id']}: {kept} records kept ({trip['phone']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
