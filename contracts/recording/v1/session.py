"""Read-only Python access to on-device recording sessions.

Standard library only, and strictly read-only. This module never truncates, repairs,
rewrites, renames or deletes session data: recovery belongs to the recorder running on
the device, and a reader that silently repaired bytes would destroy the evidence that
recovery decision depends on.    Entry points: `inspect_session` (bounded metadata-only look, never raises),
    `read_session` (strict streaming of a replayable session directory) and `open_stream`
    (the same validation for a stream that is not a directory, e.g. a device pipe).

    The semantics deliberately mirror `mobile/.../replay/ReplayReader.kt`, so one phone
    session can be validated identically from Kotlin and Python:

    RECORD_TOO_LARGE, DUPLICATE_EVENT, PRE_SESSION_RECORD, INITIALIZATION_MODE_CHANGED,
    RECORD_COUNT_MISMATCH, CHANNEL_COUNT_MISMATCH, REPLAY_RECORD_LIMIT, END_TIME_MISMATCH

Every measurement line is decoded by the canonical recording codec, which delegates to
the frozen measurement codec and then checks recording membership (acquisition session,
original source, measurement contract version).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from pathlib import Path
from typing import BinaryIO, Iterator

from contracts.v1.codec import ContractError, Limits
from contracts.v1.models import Record
from contracts.recording.v1.codec import RecordingContractError, decode_metadata, decode_record
from contracts.recording.v1.models import (
    CompletionState,
    RecoveryState,
    RecordingMetadata,
    replay_source,
)

METADATA_NAME = "metadata.json"
MEASUREMENTS_NAME = "measurements.jsonl"

#: Mirrors REPLAY_RECORD_LIMIT on the device. Raising it needs a bounded-memory
#: duplicate-ID plan on both sides, so it stays a fixed, documented cap.
MAX_RECORDS = 1_000_000

#: Mirrors the metadata bound enforced by the Android session store.
MAX_METADATA_BYTES = 262_144

#: Session directory names are opaque IDs, never free-form text or a path fragment.
_SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")


class SessionError(ValueError):
    """A session that cannot be read, or a record that violates the contract.

    `code` is a stable identifier and `line` is a one-based measurement line number.
    Payload values are never included in the message.
    """

    def __init__(self, code: str, message: str = "", line: int | None = None):
        self.code = code
        self.message = message
        self.line = line
        parts = [code]
        if line is not None:
            parts.append(f"line {line}")
        text = ": ".join(parts)
        if message:
            text = f"{text}: {message}"
        super().__init__(text)


@dataclass(frozen=True, slots=True)
class SessionSummary:
    """Bounded inspection of one session directory.

    Mirrors the device-side `SavedSession` eligibility rules, so a Python consumer refuses
    a session for exactly the reasons the app would.
    """

    recording_id: str
    metadata: RecordingMetadata | None
    bytes: int | None
    error: str | None = None
    extra_entries: tuple[str, ...] = ()
    error_code: str | None = None

    @property
    def replayable(self) -> bool:
        """Only cleanly completed, or explicitly recovered-incomplete, sessions qualify."""
        return (
            self.error is None
            and self.metadata is not None
            and (
                self.metadata.completion_state is CompletionState.COMPLETED
                or (
                    self.metadata.completion_state is CompletionState.INCOMPLETE
                    and self.metadata.recovery_state is RecoveryState.RECOVERED
                )
            )
        )

    @property
    def exportable(self) -> bool:
        return (
            self.error is None
            and self.metadata is not None
            and self.metadata.completion_state is not CompletionState.OPEN
            and self.metadata.recovery_state is not RecoveryState.REQUIRED
        )

    @property
    def incomplete(self) -> bool:
        return (
            self.metadata is not None
            and self.metadata.completion_state is CompletionState.INCOMPLETE
        )

    @property
    def duration_ns(self) -> int | None:
        if self.metadata is None or self.metadata.clock.ended_ns is None:
            return None
        return self.metadata.clock.ended_ns - self.metadata.clock.started_ns


def _close(handle: BinaryIO) -> None:
    """Closing twice is harmless, and a failure to close must not mask a real error."""
    try:
        handle.close()
    except OSError:
        pass


def _regular_file(path: Path, name: str) -> Path:
    """Reject symlinks and non-regular entries; a session must be plain local files."""
    if path.is_symlink():
        raise SessionError("UNSAFE_PATH", f"{name} must not be a symbolic link")
    if not path.is_file():
        raise SessionError("MISSING_ARTIFACT", f"{name} is missing or not a regular file")
    return path


def _read_metadata(root: Path) -> RecordingMetadata:
    path = _regular_file(root / METADATA_NAME, METADATA_NAME)
    try:
        size = path.stat().st_size
        if size > MAX_METADATA_BYTES:
            raise SessionError("RESOURCE_LIMIT", f"{METADATA_NAME} exceeds the contract limit")
        data = path.read_bytes()
    except OSError:
        raise SessionError("IO_ERROR", f"{METADATA_NAME} could not be read") from None
    try:
        return decode_metadata(data)
    except RecordingContractError as exc:
        raise SessionError(exc.code, exc.path) from None


def _resolve(directory: str | Path) -> Path:
    path = Path(directory)
    if path.is_symlink():
        raise SessionError("UNSAFE_PATH", "session directory must not be a symbolic link")
    if not path.is_dir():
        raise SessionError("MISSING_SESSION", "session directory does not exist")
    return path


def inspect_session(directory: str | Path) -> SessionSummary:
    """Inspect one session without reading its measurements. Never raises for bad data."""
    path = Path(directory)
    recording_id = path.name
    try:
        if not _SESSION_ID.fullmatch(recording_id):
            raise SessionError("UNSAFE_PATH", "session ID is not a safe directory name")
        root = _resolve(path)
        metadata = _read_metadata(root)
        if metadata.recording_id != recording_id:
            raise SessionError("ID_MISMATCH", "metadata recording ID does not match its directory")
        measurements = _regular_file(root / MEASUREMENTS_NAME, MEASUREMENTS_NAME)
        total = (root / METADATA_NAME).stat().st_size + measurements.stat().st_size
        extras = tuple(
            sorted(
                entry.name
                for entry in root.iterdir()
                if entry.name not in (METADATA_NAME, MEASUREMENTS_NAME)
            )
        )
        return SessionSummary(recording_id, metadata, total, None, extras)
    except SessionError as exc:
        return SessionSummary(recording_id, None, None, str(exc), error_code=exc.code)
    except OSError as exc:
        return SessionSummary(
            recording_id, None, None, f"IO_ERROR: {exc.__class__.__name__}", error_code="IO_ERROR"
        )


class RecordStreamValidator:
    """Strict, arrival-order validation of one session's measurement stream.

    Bounded memory: it retains event IDs (duplicate rejection) and per-channel counters,
    never measurement payloads and never the whole session.
    """

    def __init__(self, handle: BinaryIO, metadata: RecordingMetadata, limits: Limits | None = None):
        self._handle = handle
        self._limits = limits or Limits()
        self.metadata = metadata
        declared = metadata.record_count
        if declared is not None and declared > MAX_RECORDS:
            raise SessionError("REPLAY_RECORD_LIMIT", f"finalized count exceeds {MAX_RECORDS}")
        self._declared: int | None = declared
        self.count = 0
        self.max_received_ns = metadata.clock.started_ns
        self.channels: dict[str, int] = {}
        self._ids: set[str] = set()
        self._mode: str | None = None
        self._finished = False

    def __iter__(self) -> Iterator[Record]:
        if self._declared is None:
            # An unfinalized session declares neither a count nor an end, so arrival order
            # and completeness cannot be checked. Its metadata is still inspectable.
            self._abort()
            raise SessionError("SESSION_NOT_FINALIZED", "metadata declares no finalized record count")
        while True:
            record = self._next()
            if record is None:
                return
            yield record

    @property
    def closed(self) -> bool:
        """True once the stream has ended, been closed, or been rejected."""
        return self._handle.closed

    def close(self) -> None:
        """Idempotent. Also called automatically at end of stream."""
        _close(self._handle)

    def _abort(self) -> None:
        """A rejected stream is dead: release the handle instead of leaving it suspended."""
        self._finished = True
        self.close()

    def _read_line(self) -> bytes | None:
        """One LF-terminated line, or the final partial line, with a hard size cap."""
        buffer = bytearray()
        limit = self._limits.max_record_bytes
        while True:
            chunk = self._handle.readline(limit + 1)
            if not chunk:
                return bytes(buffer) if buffer else None
            buffer.extend(chunk)
            if chunk.endswith(b"\n"):
                return bytes(buffer)
            if len(buffer) > limit:
                raise SessionError("RECORD_TOO_LARGE", "measurement line exceeds the record limit")

    def _next(self) -> Record | None:
        line = self.count + 1
        try:
            raw = self._read_line()
            if raw is None:
                self._finish()
                return None
            if raw.endswith(b"\n"):
                raw = raw[:-1]
                if raw.endswith(b"\r"):
                    raw = raw[:-1]
            if len(raw) > self._limits.max_record_bytes:
                raise SessionError("RECORD_TOO_LARGE", "measurement line exceeds the record limit")
            if self.count >= MAX_RECORDS:
                raise SessionError("REPLAY_RECORD_LIMIT", f"more than {MAX_RECORDS} records")
            record = decode_record(raw, self.metadata)
            if record.event.event_id in self._ids:
                raise SessionError("DUPLICATE_EVENT", "event ID repeats within the session")
            if record.event.t_ns < self.metadata.clock.origin_ns:
                raise SessionError("PRE_SESSION_RECORD", "measurement predates the session origin")
            data = record.event.data
            if data.TYPE == "navigation":
                mode = data.initialization_mode.value
                if self._mode is not None and mode != self._mode:
                    raise SessionError(
                        "INITIALIZATION_MODE_CHANGED",
                        "a stream must not switch initialization mode",
                    )
                self._mode = mode
            self._ids.add(record.event.event_id)
            self.count += 1
            if self.count > self._declared:
                raise SessionError("RECORD_COUNT_MISMATCH", "more records than the metadata declares")
            self.channels[data.TYPE] = self.channels.get(data.TYPE, 0) + 1
            self.max_received_ns = max(self.max_received_ns, record.event.received_ns)
            return replace(
                record,
                header=replace(record.header, source=replay_source(self.metadata.source)),
            )
        except SessionError as exc:
            self._abort()
            raise SessionError(exc.code, exc.message, line) from None
        except (RecordingContractError, ContractError) as exc:
            self._abort()
            raise SessionError(exc.code, exc.path, line) from None
        except OSError:
            self._abort()
            raise SessionError("IO_ERROR", "", line) from None

    def _finish(self) -> None:
        """End-of-stream checks, then release the handle."""
        if self._finished:
            return
        self._finished = True
        try:
            if self._declared is not None and self.count != self._declared:
                raise SessionError(
                    "RECORD_COUNT_MISMATCH",
                    f"stream holds {self.count} records, metadata declares {self._declared}",
                )
            declared = self.metadata.channel_counts
            if declared is not None:
                expected = {entry.channel: entry.count for entry in declared if entry.count != 0}
                if expected != self.channels:
                    raise SessionError(
                        "CHANNEL_COUNT_MISMATCH", "per-channel counts do not match metadata"
                    )
            ended_ns = self.metadata.clock.ended_ns
            if ended_ns is not None and self.max_received_ns > ended_ns:
                raise SessionError(
                    "END_TIME_MISMATCH", "a receipt time follows the declared session end"
                )
        finally:
            self.close()


@dataclass(frozen=True, slots=True)
class SessionContent:
    """A validated session stream plus the validator that owns its file handle."""

    metadata: RecordingMetadata
    records: Iterator[Record]
    validator: RecordStreamValidator

    def close(self) -> None:
        self.validator.close()


def open_session(
    directory: str | Path,
    limits: Limits | None = None,
    *,
    require_replayable: bool = True,
) -> SessionContent:
    """Open one session directory for strict, read-only iteration.

    With `require_replayable` (the default) only completed sessions and explicitly
    recovered incomplete sessions open, exactly like device replay. Pass False to inspect
    a failed or unfinished session, which is still validated and never repaired. A session
    that was never finalized has no declared end, so its metadata can be read but its
    stream still refuses to iterate (`SESSION_NOT_FINALIZED`).
    """
    summary = inspect_session(directory)
    if summary.error_code is not None or summary.metadata is None:
        raise SessionError(summary.error_code or "MISSING_SESSION", summary.error or "")
    if require_replayable and not summary.replayable:
        raise SessionError(
            "SESSION_NOT_REPLAYABLE",
            f"completion={summary.metadata.completion_state.value} "
            f"recovery={summary.metadata.recovery_state.value}",
        )
    root = _resolve(directory)
    measurements = _regular_file(root / MEASUREMENTS_NAME, MEASUREMENTS_NAME)
    try:
        handle = measurements.open("rb")
    except OSError:
        raise SessionError("IO_ERROR", "measurement stream could not be opened") from None
    return open_stream(handle, summary.metadata, limits)


def open_stream(
    handle: BinaryIO,
    metadata: RecordingMetadata,
    limits: Limits | None = None,
) -> SessionContent:
    """Validate a measurement stream that is not a local session directory.

    For a device stream pulled over adb, or JSONL already unpacked from an export. The
    same validator runs either way, so a stream cannot pass here and fail on a directory.
    Ownership transfers on success: `SessionContent.close()` releases the handle.
    """
    try:
        validator = RecordStreamValidator(handle, metadata, limits)
    except SessionError:
        _close(handle)
        raise
    return SessionContent(metadata, iter(validator), validator)


def read_session(directory: str | Path, limits: Limits | None = None) -> Iterator[Record]:
    """Convenience iterator. The file closes when the stream is exhausted or closed."""
    return open_session(directory, limits).records
