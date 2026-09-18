"""Explicit golden-fixture bridge, not a trip recorder or dataset converter.

python -m contracts.v1.interop export PATH  # Python writes; Kotlin tests read
python -m contracts.v1.interop verify PATH  # Verify Kotlin output with Python
"""
import argparse
from pathlib import Path
from .codec import read_jsonl, write_jsonl


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("export", "verify"))
    p.add_argument("path", type=Path)
    args = p.parse_args()
    expected = []
    for fixture in ("golden_records.jsonl", "edge_records.jsonl"):
        with (Path(__file__).parent / fixture).open("rb") as stream:
            expected.extend(read_jsonl(stream))
    if args.action == "export":
        args.path.parent.mkdir(parents=True, exist_ok=True)
        with args.path.open("wb") as stream:
            write_jsonl(expected, stream)
        print(f"Python exported {len(expected)} typed synthetic records")
    else:
        with args.path.open("rb") as stream:
            actual = list(read_jsonl(stream))
        if actual != expected:
            raise SystemExit("Cross-language contract value mismatch")
        print(f"Python verified {len(actual)} Kotlin records; all typed values identical")


if __name__ == "__main__":
    main()
