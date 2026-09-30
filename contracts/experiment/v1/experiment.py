"""Read-only Python access to experiment directories.

An experiment is the evaluation unit above recording sessions:

    <experiment_id>/
      experiment.json                 manifest, contract 1.0.0
      annotations.jsonl               ordered motion/scenario/gnss_state/calibration intervals
      integrity.sha256                SHA-256 of every artifact below, and of the manifest
      masks/<mask_id>.json            software GNSS outage masks (optional, declared)
      reference/reference.jsonl       independent reference (only when declared)
      sessions/<session_id>/          recording sessions, exactly as exported by the app
        metadata.json
        measurements.jsonl

Strictly read-only, like `contracts.recording.v1.session`. Nothing here truncates,
repairs, rewrites, renames or deletes anything, and the recording sessions are opened
through that same read-only reader — this module never parses a recording itself, so an
experiment cannot accept a session the replay reader would refuse.

Every experiment artifact is bound together by `integrity.sha256`, which covers the
manifest too: the manifest is written first, then hashed, so there is no circularity and
no way to edit the manifest after the fact without the digest disagreeing.

Consistency checks that no single artifact can make on its own:

- the manifest's `experiment_id` must equal its directory name (`ID_MISMATCH`);
- every session's recording clock must share the experiment clock's domain, carry a
  non-null boot identity, and match the manifest's boot ID exactly (`CLOCK_MISMATCH`) —
  without that, two sessions' monotonic timestamps are not comparable and the experiment
  would be building a timeline out of unrelated clocks;
- every annotation and mask interval must name a declared session
  (`UNKNOWN_SESSION`) and lie inside that session's recorded span
  (`INTERVAL_OUT_OF_SESSION`);
- each session's `measurements_sha256` must agree with the digest of the file it names
  (`SESSION_HASH_MISMATCH`), which is checked against the integrity manifest rather than
  hashing the recording a second time.

Error codes raised here:

    MISSING_EXPERIMENT, UNSAFE_PATH, MISSING_ARTIFACT, IO_ERROR, ID_MISMATCH,
    MISSING_INTEGRITY, MALFORMED_INTEGRITY, HASH_MISMATCH, UNLISTED_FILE,
    UNKNOWN_SESSION, UNKNOWN_MASK, MASK_ID_MISMATCH, INTERVAL_OUT_OF_SESSION,
    MISSING_REFERENCE, INVALID_REFERENCE_RECORD, MISSING_SESSION,
    SESSION_NOT_REPLAYABLE, CLOCK_MISMATCH, SESSION_HASH_MISMATCH,
    DUPLICATE_EXPERIMENT_ID, RESOURCE_LIMIT

Codes from the codec (`contracts.experiment.v1.codec.ExperimentContractError`) and the
recording reader (`contracts.recording.v1.session.SessionError`) surface unchanged, so a
caller can switch on one vocabulary across the three layers.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Mapping

from contracts.v1.codec import ContractError, decode_json as decode_measurement_json
from contracts.recording.v1.models import RecordingMetadata
from contracts.recording.v1.session import (
    MEASUREMENTS_NAME,
    METADATA_NAME,
    SessionContent,
    SessionSummary,
    inspect_session,
    open_session,
)

from .codec import (
    ExperimentContractError,
    decode_annotations,
    decode_manifest,
    decode_mask,
)
from .integrity import (
    INTEGRITY_NAME,
    IntegrityError,
    decode_integrity,
    sha256_file,
    verify_integrity,
)
from .masking import GNSS_TYPE, MaskedReplay, mask_duration_ns
from .models import (
    AnnotationInterval,
    EXPERIMENT_CLOCK_DOMAIN,
    ExperimentManifest,
    ExperimentSession,
    IntervalKind,
    OutageMask,
    ReferenceDescriptor,
    SessionRole,
)

MANIFEST_NAME = "experiment.json"
ANNOTATIONS_NAME = "annotations.jsonl"
MASKS_DIRNAME = "masks"
REFERENCE_DIRNAME = "reference"
REFERENCE_NAME = "reference.jsonl"
SESSIONS_DIRNAME = "sessions"
MASK_SUFFIX = ".json"

#: The digest recorded in the manifest must be the digest of the file that actually
#: holds the measurements, so the filename is fixed by the experiment structure.
_SESSION_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")

#: The reference stream is bounded like a measurement stream: a reference is a position
#: source, not an unbounded log.
MAX_REFERENCE_RECORDS = 1_000_000


class ExperimentError(ValueError):
    """An experiment that cannot be read, or an artifact that contradicts another."""

    def __init__(self, code: str, path: str = "", detail: str = ""):
        self.code = code
        self.path = path
        self.detail = detail
        parts = [code]
        if path:
            parts.append(path)
        text = ": ".join(parts)
        if detail:
            text = f"{text}: {detail}"
        super().__init__(text)


def _fail(code: str, path: str = "", detail: str = ""):
    raise ExperimentError(code, path, detail) from None


def _regular(path: Path, name: str) -> Path:
    """Reject symlinks and non-regular entries; an experiment artifact is a plain file."""
    if path.is_symlink():
        _fail("UNSAFE_PATH", name)
    if not path.is_file():
        _fail("MISSING_ARTIFACT", name)
    return path


def _read(path: Path, name: str) -> bytes:
    try:
        return _regular(path, name).read_bytes()
    except OSError:
        _fail("IO_ERROR", name)


def _inside(root: Path, relative: str) -> Path:
    """Resolve a canonical location, refusing anything that escapes the experiment."""
    target = (root / relative).resolve()
    if not target.is_relative_to(root.resolve()):
        _fail("UNSAFE_PATH", relative)
    return target


@dataclass(frozen=True, slots=True)
class ExperimentSummary:
    """Bounded inspection: manifest only, no hashing and no session streaming."""

    experiment_id: str
    manifest: ExperimentManifest | None
    bytes: int | None
    error: str | None = None
    error_code: str | None = None
    extra_entries: tuple[str, ...] = ()

    @property
    def readable(self) -> bool:
        return self.error is None and self.manifest is not None

    @property
    def session_count(self) -> int:
        return 0 if self.manifest is None else len(self.manifest.sessions)

    @property
    def mask_count(self) -> int:
        return 0 if self.manifest is None else len(self.manifest.masks)


@dataclass(frozen=True, slots=True)
class ExperimentSessionInfo:
    """One declared session, tied to its inspected recording directory."""

    entry: ExperimentSession
    directory: Path
    summary: SessionSummary

    @property
    def session_id(self) -> str:
        return self.entry.session_id

    @property
    def role(self) -> SessionRole:
        return self.entry.role

    @property
    def metadata(self) -> RecordingMetadata:
        assert self.summary.metadata is not None, "declared sessions are always inspected"
        return self.summary.metadata

    @property
    def origin_ns(self) -> int:
        return self.metadata.clock.origin_ns

    @property
    def started_ns(self) -> int:
        return self.metadata.clock.started_ns

    @property
    def ended_ns(self) -> int | None:
        return self.metadata.clock.ended_ns


@dataclass(frozen=True, slots=True)
class ReferenceInfo:
    """Declared reference, plus what reading it actually found."""

    descriptor: ReferenceDescriptor
    records: int
    first_ns: int | None
    last_ns: int | None


class ExperimentReplay:
    """A masked, read-only view of one session's records.

    The recording stays on disk and is never modified: masking only decides which
    already-decoded records are yielded. `close()` releases the underlying file handle.
    """

    def __init__(self, session_id: str, mask_id: str | None, stream: MaskedReplay,
                 content: SessionContent):
        self.session_id = session_id
        self.mask_id = mask_id
        self._stream = stream
        self._content = content

    def __iter__(self) -> Iterator:
        return iter(self._stream)

    def close(self) -> None:
        self._content.close()

    @property
    def yielded(self) -> int:
        return self._stream.yielded

    @property
    def total_gnss(self) -> int:
        return self._stream.total_gnss

    @property
    def suppressed_gnss(self) -> int:
        return self._stream.suppressed_gnss

    @property
    def metadata(self) -> RecordingMetadata:
        return self._content.metadata


class Experiment:
    """A fully verified experiment. Reading it can never change it."""

    def __init__(self, root: Path, manifest: ExperimentManifest,
                 annotations: tuple[AnnotationInterval, ...],
                 masks: Mapping[str, OutageMask], integrity: Mapping[str, str],
                 sessions: Mapping[str, ExperimentSessionInfo],
                 reference: ReferenceInfo | None, require_replayable: bool = True):
        self.root = root
        self.manifest = manifest
        self.annotations = annotations
        self.masks = dict(masks)
        self.integrity = dict(integrity)
        self.sessions = dict(sessions)
        self.reference = reference
        # The level of guarantee this experiment was opened with. Later reads honour it, so
        # an experiment opened for inspection cannot quietly start streaming a session the
        # strict reader would refuse.
        self.require_replayable = require_replayable

    # -- sessions ----------------------------------------------------------------------

    def primary(self) -> ExperimentSessionInfo:
        for info in self.sessions.values():
            if info.role is SessionRole.PRIMARY:
                return info
        _fail("UNKNOWN_SESSION", "primary")

    def session_info(self, session_id: str) -> ExperimentSessionInfo:
        info = self.sessions.get(session_id)
        if info is None:
            _fail("UNKNOWN_SESSION", session_id)
        return info

    def open_session(self, session_id: str, *,
                     require_replayable: bool | None = None) -> SessionContent:
        """Open one session through the read-only recording reader."""
        return open_session(
            self.session_info(session_id).directory,
            require_replayable=(self.require_replayable if require_replayable is None
                                else require_replayable),
        )

    def replay_input(self, session_id: str, mask_id: str | None = None) -> ExperimentReplay:
        """The engine input for one session, optionally under a software GNSS mask.

        This is the only supported way to evaluate a degraded-GNSS condition: the
        recording is opened read-only and GNSS measurements inside the mask's intervals
        are withheld from the returned stream. `mask_id=None` is the unmasked baseline.
        """
        if mask_id is not None:
            if mask_id not in self.masks:
                _fail("UNKNOWN_MASK", mask_id)
            mask = self.masks[mask_id]
            if mask.session_id != session_id:
                # A mask is authored against one drive; applying it elsewhere would be a
                # silent reinterpretation of timestamps.
                _fail("MASK_ID_MISMATCH", mask_id, f"mask targets {mask.session_id}")
        else:
            mask = None
        content = self.open_session(session_id)
        return ExperimentReplay(
            session_id, mask_id, MaskedReplay(content.records, mask, session_id), content)

    # -- annotations -------------------------------------------------------------------

    def intervals(self, kind: IntervalKind | None = None,
                  session_id: str | None = None) -> tuple[AnnotationInterval, ...]:
        return tuple(
            interval for interval in self.annotations
            if (kind is None or interval.kind is kind)
            and (session_id is None or interval.session_id == session_id)
        )

    def gnss_state_intervals(self, session_id: str) -> tuple[AnnotationInterval, ...]:
        return self.intervals(IntervalKind.GNSS_STATE, session_id)

    def mask_duration_ns(self, mask_id: str) -> int:
        mask = self.masks.get(mask_id)
        if mask is None:
            _fail("UNKNOWN_MASK", mask_id)
        return mask_duration_ns(mask)

    def reference_records(self) -> Iterator:
        """Stream the reference, decoding each row as a canonical measurement record."""
        if self.manifest.reference is None:
            return iter(())
        return _iter_reference(_inside(self.root, f"{REFERENCE_DIRNAME}/{REFERENCE_NAME}"),
                               self.manifest)


# --------------------------------------------------------------------------------------
# Reading
# --------------------------------------------------------------------------------------


def _resolve(directory: str | Path) -> Path:
    path = Path(directory)
    if path.is_symlink():
        _fail("UNSAFE_PATH", path.name)
    if not path.is_dir():
        _fail("MISSING_EXPERIMENT", path.name)
    return path


def _read_manifest(root: Path) -> ExperimentManifest:
    try:
        return decode_manifest(_read(root / MANIFEST_NAME, MANIFEST_NAME))
    except ExperimentContractError as exc:
        _fail(exc.code, exc.path)


def inspect_experiment(directory: str | Path) -> ExperimentSummary:
    """Inspect one experiment's manifest. Never raises for bad data."""
    path = Path(directory)
    name = path.name
    try:
        if not _SESSION_ID.fullmatch(name):
            _fail("UNSAFE_PATH", name)
        root = _resolve(path)
        manifest = _read_manifest(root)
        if manifest.experiment_id != name:
            _fail("ID_MISMATCH", MANIFEST_NAME, f"declares {manifest.experiment_id}")
        total = (root / MANIFEST_NAME).stat().st_size
        for relative in (ANNOTATIONS_NAME, INTEGRITY_NAME):
            candidate = root / relative
            if candidate.is_file() and not candidate.is_symlink():
                total += candidate.stat().st_size
        known = {MANIFEST_NAME, ANNOTATIONS_NAME, INTEGRITY_NAME}
        extras = tuple(sorted(entry.name for entry in root.iterdir()
                              if entry.name not in known))
        return ExperimentSummary(name, manifest, total, None, None, extras)
    except ExperimentError as exc:
        return ExperimentSummary(name, None, None, str(exc), error_code=exc.code)
    except OSError as exc:
        return ExperimentSummary(name, None, None,
                                 f"IO_ERROR: {exc.__class__.__name__}", error_code="IO_ERROR")


def _check_clock(manifest: ExperimentManifest, info: ExperimentSessionInfo) -> None:
    """Every session must be time-comparable with the experiment clock."""
    clock = info.metadata.clock
    if clock.domain.value != manifest.clock.domain:
        _fail("CLOCK_MISMATCH", info.session_id, f"domain {clock.domain.value}")
    if clock.domain.value != EXPERIMENT_CLOCK_DOMAIN:
        _fail("CLOCK_MISMATCH", info.session_id, f"unsupported domain {clock.domain.value}")
    if clock.boot_id is None:
        # Without a boot identity, monotonic timestamps from two sessions are unrelated.
        _fail("CLOCK_MISMATCH", info.session_id, "recording has no boot identity")
    if clock.boot_id != manifest.clock.boot_id:
        _fail("CLOCK_MISMATCH", info.session_id,
              f"boot {clock.boot_id} is not the experiment boot {manifest.clock.boot_id}")


def _check_bounds(sessions: Mapping[str, ExperimentSessionInfo],
                  label: str, session_id: str, start_ns: int, end_ns: int) -> None:
    info = sessions.get(session_id)
    if info is None:
        _fail("UNKNOWN_SESSION", label, session_id)
    if start_ns < info.origin_ns:
        _fail("INTERVAL_OUT_OF_SESSION", label, "starts before the session origin")
    ended_ns = info.ended_ns
    if ended_ns is not None and end_ns > ended_ns:
        _fail("INTERVAL_OUT_OF_SESSION", label, "ends after the session was finalized")


def _iter_reference(path: Path, manifest: ExperimentManifest) -> Iterator:
    """Stream a reference file, decoding rows with the canonical measurement codec."""
    descriptor = manifest.reference
    assert descriptor is not None
    handle = _regular(path, REFERENCE_NAME).open("rb")
    count = 0
    try:
        for number, raw in enumerate(handle, 1):
            if not raw.strip():
                _fail("INVALID_REFERENCE_RECORD", REFERENCE_NAME, f"line {number}")
            if count >= MAX_REFERENCE_RECORDS:
                _fail("RESOURCE_LIMIT", REFERENCE_NAME)
            try:
                record = decode_measurement_json(raw)
            except ContractError as exc:
                _fail("INVALID_REFERENCE_RECORD", REFERENCE_NAME, f"{exc.code} line {number}")
            if record.event.data.TYPE != GNSS_TYPE:
                # A reference is a position stream; anything else is not a reference.
                _fail("INVALID_REFERENCE_RECORD", REFERENCE_NAME,
                      f"line {number} is {record.event.data.TYPE}")
            if record.header.source is not descriptor.source:
                _fail("INVALID_REFERENCE_RECORD", REFERENCE_NAME,
                      f"line {number} source {record.header.source.value}")
            count += 1
            yield record
    except OSError:
        _fail("IO_ERROR", REFERENCE_NAME)
    finally:
        handle.close()


def open_experiment(directory: str | Path, *, verify_integrity_manifest: bool = True,
                    require_replayable: bool = True) -> Experiment:
    """Open one experiment directory, fully verified. Raises on the first violation.

    Order matters: the manifest is decoded, then `integrity.sha256` is checked over the
    whole directory, and only then are the annotations, masks and sessions trusted. A
    directory whose bytes do not match its manifest is never partially read —
    `HASH_MISMATCH` is raised instead, because every later conclusion would be built on
    artifacts nobody committed to.
    """
    root = _resolve(directory)
    if not _SESSION_ID.fullmatch(root.name):
        _fail("UNSAFE_PATH", root.name)
    manifest = _read_manifest(root)
    if manifest.experiment_id != root.name:
        _fail("ID_MISMATCH", MANIFEST_NAME, f"declares {manifest.experiment_id}")

    integrity: dict[str, str] = {}
    if verify_integrity_manifest:
        raw = _read(root / INTEGRITY_NAME, INTEGRITY_NAME)
        try:
            integrity = decode_integrity(raw)
            verify_integrity(root, integrity)
        except IntegrityError as exc:
            _fail(exc.code, exc.path)

    try:
        annotations = decode_annotations(_read(root / ANNOTATIONS_NAME, ANNOTATIONS_NAME))
    except ExperimentContractError as exc:
        _fail(exc.code, exc.path)

    masks: dict[str, OutageMask] = {}
    for mask_id in manifest.masks:
        relative = f"{MASKS_DIRNAME}/{mask_id}{MASK_SUFFIX}"
        try:
            mask = decode_mask(_read(_inside(root, relative), relative))
        except ExperimentContractError as exc:
            _fail(exc.code, exc.path)
        if mask.mask_id != mask_id:
            _fail("MASK_ID_MISMATCH", relative, f"declares {mask.mask_id}")
        if manifest.session(mask.session_id) is None:
            _fail("UNKNOWN_SESSION", relative, mask.session_id)
        masks[mask_id] = mask

    sessions: dict[str, ExperimentSessionInfo] = {}
    for entry in manifest.sessions:
        relative = f"{SESSIONS_DIRNAME}/{entry.session_id}"
        summary = inspect_session(_inside(root, relative))
        if summary.error_code is not None or summary.metadata is None:
            raise ExperimentError(summary.error_code or "MISSING_SESSION", relative,
                                  summary.error or "")
        if require_replayable and not summary.replayable:
            _fail("SESSION_NOT_REPLAYABLE", relative,
                  f"completion={summary.metadata.completion_state.value}")
        info = ExperimentSessionInfo(entry, _inside(root, relative), summary)
        _check_clock(manifest, info)
        # The per-session commitment is always honoured. When the integrity manifest is
        # available it has already hashed this file, so no recording is hashed twice.
        declared = integrity.get(f"{relative}/{MEASUREMENTS_NAME}")
        if declared is None:
            declared = sha256_file(root / relative / MEASUREMENTS_NAME)
        if declared != entry.measurements_sha256:
            _fail("SESSION_HASH_MISMATCH", entry.session_id)
        sessions[entry.session_id] = info

    for index, interval in enumerate(annotations):
        _check_bounds(sessions, f"{ANNOTATIONS_NAME}[{index}]",
                      interval.session_id, interval.start_ns, interval.end_ns)
    for mask_id, mask in masks.items():
        for index, interval in enumerate(mask.intervals):
            _check_bounds(sessions, f"{MASKS_DIRNAME}/{mask_id}[{index}]",
                          mask.session_id, interval.start_ns, interval.end_ns)

    reference: ReferenceInfo | None = None
    if manifest.reference is not None:
        path = _inside(root, f"{REFERENCE_DIRNAME}/{REFERENCE_NAME}")
        if not path.is_file() or path.is_symlink():
            # Declared but absent is a rejection, not an optional extra.
            _fail("MISSING_REFERENCE", f"{REFERENCE_DIRNAME}/{REFERENCE_NAME}")
        count = 0
        first_ns: int | None = None
        last_ns: int | None = None
        for record in _iter_reference(path, manifest):
            if first_ns is None:
                first_ns = record.event.t_ns
            last_ns = record.event.t_ns
            count += 1
        reference = ReferenceInfo(manifest.reference, count, first_ns, last_ns)

    return Experiment(root, manifest, annotations, masks, integrity, sessions, reference,
                      require_replayable=require_replayable)


def load_experiment(directory: str | Path, **kwargs) -> Experiment:
    """Alias for `open_experiment`, for readers who expect `read_*`/`load_*` naming."""
    return open_experiment(directory, **kwargs)


def corpus_label(corpus: str | Path, directory: Path) -> str:
    """Corpus-relative POSIX label, so a finding names the campaign as well as the ID."""
    try:
        return directory.resolve().relative_to(Path(corpus).resolve()).as_posix()
    except ValueError:
        return directory.name


def iter_experiments(corpus: str | Path) -> Iterator[tuple[Path, ExperimentSummary]]:
    """Yield every experiment directory under a corpus, at any depth, in a stable order.

    Discovery is by the presence of `experiment.json`, so a corpus may group experiments
    into campaigns (`experiments/<campaign>/<experiment_id>/`) or hold them directly.
    Every discovered directory is yielded, including one whose manifest is missing or
    invalid, so a caller reports it rather than silently skipping it. Directories with no
    manifest are not experiments and are not yielded.
    """
    root = Path(_resolve(corpus)).resolve()
    found = {path.parent for path in root.rglob(MANIFEST_NAME)}
    if (root / MANIFEST_NAME).is_file():
        # A single experiment is a corpus of one, so the root is eligible too.
        found.add(root)
    for directory in sorted(found):
        yield directory, inspect_experiment(directory)


def find_duplicate_ids(corpus: str | Path) -> dict[str, list[str]]:
    """Experiment IDs claimed by more than one directory in a corpus.

    Two experiments with one ID make any result keyed by that ID ambiguous, so it is a
    corpus-level failure no single directory can detect on its own. It is reachable
    because a corpus may hold several campaigns, each with its own `exp-alpha`.
    """
    seen: dict[str, list[str]] = {}
    for directory, summary in iter_experiments(corpus):
        if summary.manifest is not None:
            seen.setdefault(summary.manifest.experiment_id, []).append(
                corpus_label(corpus, directory))
    return {key: names for key, names in seen.items() if len(names) > 1}


def check_corpus(corpus: str | Path) -> list[ExperimentError]:
    """Validate every experiment in a corpus, including cross-experiment ID uniqueness.

    Returns every finding rather than the first: a collector fixing a corpus needs the
    whole list, and a partially valid corpus is exactly what this catches.
    """
    findings: list[ExperimentError] = []
    for directory, summary in iter_experiments(corpus):
        if summary.manifest is None:
            findings.append(ExperimentError(summary.error_code or "MISSING_EXPERIMENT",
                                            corpus_label(corpus, directory),
                                            summary.error or ""))
            continue
        try:
            open_experiment(directory)
        except ExperimentError as exc:
            findings.append(ExperimentError(exc.code, corpus_label(corpus, directory),
                                            exc.detail))
    for experiment_id, names in sorted(find_duplicate_ids(corpus).items()):
        findings.append(ExperimentError(
            "DUPLICATE_EXPERIMENT_ID", experiment_id, ", ".join(sorted(names))))
    return findings


__all__ = [
    "ANNOTATIONS_NAME",
    "Experiment",
    "ExperimentError",
    "ExperimentReplay",
    "ExperimentSessionInfo",
    "ExperimentSummary",
    "INTEGRITY_NAME",
    "MANIFEST_NAME",
    "MASKS_DIRNAME",
    "REFERENCE_NAME",
    "REFERENCE_DIRNAME",
    "ReferenceInfo",
    "SESSIONS_DIRNAME",
    "check_corpus",
    "corpus_label",
    "find_duplicate_ids",
    "inspect_experiment",
    "iter_experiments",
    "load_experiment",
    "open_experiment",
]
