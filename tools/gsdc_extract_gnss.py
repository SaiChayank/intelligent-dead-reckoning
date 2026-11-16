"""Extract the raw GNSS file (`device_gnss.csv`) of the GSDC trips that were converted, from the downloaded zip.

    python tools/gsdc_extract_gnss.py <path to smartphone-decimeter-2023.zip> [--root data/raw/gsdc2023]

Finds the recordings made by tools/gsdc_to_recording.py (data/processed/gsdc2023/<drive>/gsdc-<drive>-<phone>), and for each one writes
`<root>/train/<drive>/<phone>/device_gnss.csv` if it is not there yet. Only those files are extracted (about 2.5 GB for 64 trips); every path is checked
to stay inside the destination. The data is CC BY-NC-SA 3.0 IGO and stays out of the repository.
"""
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def converted_trips(processed: Path) -> list[tuple[str, str]]:
    """(drive, phone) of every converted recording, found from the folders (`<drive>/gsdc-<drive>-<phone>/measurements.jsonl`)."""
    trips = []
    for drive in sorted(p for p in processed.iterdir() if p.is_dir()):
        prefix = f"gsdc-{drive.name}-"
        for recording in sorted(p for p in drive.iterdir() if (p / "measurements.jsonl").is_file() and p.name.startswith(prefix)):
            trips.append((drive.name, recording.name[len(prefix):]))
    return trips


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("zip", type=Path)
    parser.add_argument("--root", type=Path, default=ROOT / "data" / "raw" / "gsdc2023")
    parser.add_argument("--processed", type=Path, default=ROOT / "data" / "processed" / "gsdc2023")
    args = parser.parse_args(argv)
    destination = args.root.resolve()
    archive = zipfile.ZipFile(args.zip)
    names = set(archive.namelist())
    done = skipped = 0
    for drive, phone in converted_trips(args.processed):
        member = f"sdc2023/train/{drive}/{phone}/device_gnss.csv"
        target = (destination / "train" / drive / phone / "device_gnss.csv").resolve()
        if destination not in target.parents:
            raise SystemExit(f"refusing a path outside the destination: {member}")
        if member not in names:
            print(f"missing in the zip: {member}")
            continue
        if target.is_file():
            skipped += 1
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(member) as src, open(target, "wb") as out:
            while chunk := src.read(1 << 20):
                out.write(chunk)
        done += 1
        print(f"{drive}/{phone}: extracted", flush=True)
    print(f"{done} extracted, {skipped} already present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
