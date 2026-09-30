"""Software GNSS masking for repeatable evaluation.

Evaluating dead reckoning needs the same drive under several GNSS conditions. Masking is
how that is done without touching the recording: a mask names intervals, and this module
*withholds* GNSS measurement records that fall inside them, exactly as if the receiver
had never produced them. Nothing is edited, resampled, shifted or synthesized.

Properties this guarantees, because an evaluation result is only as trustworthy as these:

- **Read-only.** Only `contracts.recording.v1.session` ever opens a recording, and it
  opens it read-only. Masking happens on the already-decoded record stream.
- **Non-fabricating.** A masked record is dropped, never altered. No invented fix, no
  interpolated position, no quality field rewritten. A degraded-fix mask would require
  synthesizing measurements, which this project does not do; that is why
  `MaskPolicy` has a single member.
- **Deterministic.** The same records and the same mask always yield the same stream, in
  the same order, so two evaluations of one experiment are comparable.
- **Input-side only.** Masking applies to `gnss` *input* measurements. IMU, `navigation`,
  `gnss_quality`, `confidence`, `diagnostic` and `calibration` records pass through
  untouched, so a replayed session's recorded outputs are never mistaken for inputs.
- **Accounted for.** Every mask application reports how many GNSS records it withheld, so
  a report can state the condition it evaluated instead of implying it.

Interval containment is half-open, `[start_ns, end_ns)`, and is decided on the
measurement timestamp `event.t_ns` — when the fix applies — not on receipt time. A record
exactly at `end_ns` is therefore kept.
"""

from __future__ import annotations

from bisect import bisect_right
from typing import Iterable, Iterator

from contracts.v1.models import Record

from .models import MaskPolicy, OutageMask

GNSS_TYPE = "gnss"


def _starts(mask: OutageMask) -> list[int]:
    return [interval.start_ns for interval in mask.intervals]


def suppressed_at(starts: list[int], intervals: tuple, t_ns: int) -> bool:
    """Half-open containment test over sorted, non-overlapping intervals."""
    index = bisect_right(starts, t_ns) - 1
    if index < 0:
        return False
    return t_ns < intervals[index].end_ns


def mask_duration_ns(mask: OutageMask | None) -> int:
    """Total masked duration. Reported so a coverage figure cannot be guessed."""
    if mask is None:
        return 0
    return sum(interval.end_ns - interval.start_ns for interval in mask.intervals)


class MaskedReplay:
    """One session's records with a mask's GNSS measurement records withheld.

    Iterate it once, like the stream it wraps. After iteration the counters describe what
    happened: `total_gnss` GNSS measurements were seen and `suppressed_gnss` were withheld.
    Counters are read after the stream is exhausted; this class never buffers records.
    """

    def __init__(self, records: Iterable[Record], mask: OutageMask | None = None,
                 session_id: str | None = None):
        if mask is not None and mask.policy is not MaskPolicy.SUPPRESS_GNSS:
            raise ValueError("unsupported mask policy")
        self._records = records
        self._mask = mask
        self._starts = [] if mask is None else _starts(mask)
        self.session_id = session_id if session_id is not None else (
            None if mask is None else mask.session_id)
        self.yielded = 0
        self.total_gnss = 0
        self.suppressed_gnss = 0

    @property
    def mask_id(self) -> str | None:
        return None if self._mask is None else self._mask.mask_id

    def __iter__(self) -> Iterator[Record]:
        intervals = () if self._mask is None else self._mask.intervals
        for record in self._records:
            if record.event.data.TYPE != GNSS_TYPE:
                self.yielded += 1
                yield record
                continue
            self.total_gnss += 1
            if suppressed_at(self._starts, intervals, record.event.t_ns):
                self.suppressed_gnss += 1
                continue
            self.yielded += 1
            yield record


def mask_records(records: Iterable[Record], mask: OutageMask | None = None) -> MaskedReplay:
    """Convenience wrapper: `list(mask_records(records, mask))` is the masked input stream."""
    return MaskedReplay(records, mask)
