"""Explicit golden-fixture bridge, not a trip recorder or dataset converter.

python -m contracts.v1.interop export PATH [--corpus 1.0.0|1.1.0]  # Python writes; Kotlin tests read
python -m contracts.v1.interop verify PATH [--corpus 1.0.0|1.1.0]  # Verify Kotlin output with Python

One corpus is one self-describing fixture stream (same session identity and contract
version throughout), so the two versions are bridged as separate files.
"""
import argparse
from pathlib import Path
from .codec import read_jsonl, write_jsonl

CORPORA = {
    "1.0.0": ("golden_records.jsonl", "edge_records.jsonl"),
    "1.1.0": ("edge_records_1_1.jsonl",),
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("export", "verify"))
    p.add_argument("path", type=Path)
    p.add_argument("--corpus", choices=sorted(CORPORA), default="1.0.0",
                   help="contract version whose fixture corpus to bridge (default 1.0.0)")
    args = p.parse_args()
    expected = []
    for fixture in CORPORA[args.corpus]:
        with (Path(__file__).parent / fixture).open("rb") as stream:
            expected.extend(read_jsonl(stream))
    if args.action == "export":
        args.path.parent.mkdir(parents=True, exist_ok=True)
        with args.path.open("wb") as stream:
            write_jsonl(expected, stream)
        print(f"Python exported {len(expected)} typed synthetic records ({args.corpus} corpus)")
    else:
        with args.path.open("rb") as stream:
            actual = list(read_jsonl(stream))
        if actual != expected:
            raise SystemExit("Cross-language contract value mismatch")
        print(f"Python verified {len(actual)} Kotlin records; all typed values identical")


if __name__ == "__main__":
    main()
