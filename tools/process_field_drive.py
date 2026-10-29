#!/usr/bin/env python3
"""Describe a recorded or replayed drive session using only what is in the recording.

    python tools/process_field_drive.py --session <session-dir-or-measurements.jsonl[.gz]>

Prints the session's real extent: duration, IMU and GNSS record counts, measured IMU rate, GNSS fix rate and
the longest gap between fixes (candidate natural outages). It does not evaluate navigation, does not
issue a pass/fail certificate and does not draw trajectories.

Why: an earlier version of this tool simulated its own fixed drive with an invented "Model A" speed error and
printed a qualification certificate for any input. Drift is evaluated by running the real engine and scoring it
against a reference: tools/iovnbd_outage_eval.py does that for IO-VNBD, and a field drive needs its withheld
GNSS fixes as the reference (docs/FIELD_TEST_PROTOCOL.md). That evaluation for field recordings is not
implemented yet.
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

DEFAULT_SESSION = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "sample_drive" / "hyderabad-demo-drive"


def load_session_records(session_path: Path) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """Session metadata and decoded measurement lines (JSON Lines, optionally gzipped)."""
    if session_path.is_dir():
        meta_file = session_path / "metadata.json"
        data_file = session_path / "measurements.jsonl.gz"
        if not data_file.exists():
            data_file = session_path / "measurements.jsonl"
    elif session_path.name.endswith((".gz", ".jsonl")):
        data_file = session_path
        meta_file = session_path.parent / "metadata.json"
    else:
        raise FileNotFoundError(f"Cannot identify session at: {session_path}")
    metadata = json.loads(meta_file.read_text(encoding="utf-8")) if meta_file.exists() else {}
    opener = (lambda p: gzip.open(p, "rt", encoding="utf-8")) if str(data_file).endswith(".gz") \
        else (lambda p: open(p, "r", encoding="utf-8"))
    records: List[Dict[str, Any]] = []
    with opener(data_file) as handle:
        for line in handle:
            if line.strip():
                records.append(json.loads(line))
    return metadata, records


def describe_session(records: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Counts, span, measured rates and GNSS gaps. Everything is read from the records."""
    if not records:
        raise ValueError("Empty session record list.")
    imu_t: List[int] = []
    gnss_t: List[int] = []
    for r in records:
        event = r.get("event", {})
        t = int(event.get("t_ns", 0))
        kind = event.get("type")
        if kind == "imu":
            imu_t.append(t)
        elif kind == "gnss":
            gnss_t.append(t)
    times = imu_t + gnss_t
    span_s = (max(times) - min(times)) / 1e9 if times else 0.0
    gaps = [(b - a) / 1e9 for a, b in zip(gnss_t, gnss_t[1:])]
    return {
        "records": len(records),
        "imu_records": len(imu_t),
        "gnss_records": len(gnss_t),
        "duration_s": span_s,
        "imu_records_per_s": (len(imu_t) / span_s) if span_s > 0 else None,
        "gnss_fixes_per_s": (len(gnss_t) / span_s) if span_s > 0 else None,
        "longest_gnss_gap_s": max(gaps) if gaps else None,
        "gnss_gaps_over_5s": sum(1 for g in gaps if g > 5.0),
    }


def print_report(session_name: str, facts: Dict[str, Any]) -> None:
    def fmt(v: Any, unit: str = "") -> str:
        return "n/a" if v is None else f"{v:.2f}{unit}" if isinstance(v, float) else f"{v}{unit}"

    print(f"Session            : {session_name}")
    print(f"Records            : {facts['records']} ({facts['imu_records']} IMU, {facts['gnss_records']} GNSS)")
    print(f"Duration           : {fmt(facts['duration_s'], ' s')}")
    print(f"IMU records per s  : {fmt(facts['imu_records_per_s'])} (both sensors together)")
    print(f"GNSS fixes per s   : {fmt(facts['gnss_fixes_per_s'])}")
    print(f"Longest GNSS gap   : {fmt(facts['longest_gnss_gap_s'], ' s')} ({facts['gnss_gaps_over_5s']} gaps over 5 s)")
    print("Navigation drift   : NOT EVALUATED by this tool. See tools/iovnbd_outage_eval.py and "
          "docs/FIELD_TEST_PROTOCOL.md; field-drive scoring is not implemented yet.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--session", type=Path, default=DEFAULT_SESSION,
                        help="Session folder or measurements .jsonl / .jsonl.gz file.")
    args = parser.parse_args()
    meta, records = load_session_records(args.session)
    print_report(meta.get("recording_id") or meta.get("session_id") or args.session.stem, describe_session(records))
    return 0


if __name__ == "__main__":
    sys.exit(main())
