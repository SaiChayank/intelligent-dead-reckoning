"""Python half of the Android↔Python replay-reader parity check.

Given one session directory, print a JSON verdict computed by the exact reader that
consumes exported phone recordings, so a Kotlin test can assert that both
implementations agree on the same bytes:

    python tools/parity_probe.py <session-directory>

The verdict carries the summary decision, the streamed record identity and replay
source, and a stable refusal code. It never carries payload values.
Exit status mirrors the verdict: 0 accepted, 3 refused, 4 the probe itself failed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from contracts.v1.models import Record  # noqa: E402
from contracts.recording.v1.session import SessionError, open_session  # noqa: E402


def verdict(directory: Path) -> dict:
    try:
        content = open_session(directory)
    except SessionError as exc:
        return {"accepted": False, "code": exc.code, "line": exc.line, "records": []}
    try:
        records = [
            {
                "event_id": record.event.event_id,
                "t_ns": record.event.t_ns,
                "received_ns": record.event.received_ns,
                "type": record.event.data.TYPE,
                "source": record.header.source.value,
            }
            for record in content.records
        ]
    except SessionError as exc:
        return {"accepted": False, "code": exc.code, "line": exc.line, "records": []}
    finally:
        content.close()
    return {"accepted": True, "code": None, "line": None, "records": records}


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: parity_probe.py <session-directory>", file=sys.stderr)
        return 4
    try:
        result = verdict(Path(argv[1]))
    except (OSError, ValueError) as exc:
        print(json.dumps({"probe_error": exc.__class__.__name__}))
        return 4
    print(json.dumps(result))
    return 0 if result["accepted"] else 3


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
