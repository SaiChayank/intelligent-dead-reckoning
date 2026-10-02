"""Experiment-layer tests: manifest, annotations, masks, integrity, loader and CLIs.

Synthetic fixtures only, built through the real recording codec, so a session directory
here is a session directory the app could have produced. No raw dataset and no device is
required.

The six cases the collection foundation must get right are named explicitly in
`ExperimentRejectionTest`: a valid experiment, a missing reference, a corrupt hash, an
invalid outage interval, a clock mismatch and a duplicated experiment ID.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from contracts.v1.codec import encode_json as encode_measurement_json
from contracts.v1.models import (
    DeviceFrame,
    Event,
    GnssMeasurement,
    Header,
    ImuMeasurement,
    ImuUnit,
    Record,
    Sensor,
    SensorAccuracy,
    Source,
    Vector3,
)
from contracts.experiment.v1.codec import (
    ExperimentContractError,
    decode_annotations,
    decode_manifest,
    decode_mask,
    encode_annotations,
    encode_manifest,
    encode_mask,
)
from contracts.experiment.v1.experiment import (
    ANNOTATIONS_NAME,
    MANIFEST_NAME,
    ExperimentError,
    check_corpus,
    find_duplicate_ids,
    inspect_experiment,
    iter_experiments,
    open_experiment,
)
from contracts.experiment.v1.integrity import (
    INTEGRITY_NAME,
    IntegrityError,
    build_integrity,
    decode_integrity,
    encode_integrity,
    sha256_file,
    verify_integrity,
)
from contracts.experiment.v1.masking import MaskedReplay, mask_duration_ns
from contracts.experiment.v1.models import (
    AnnotationInterval,
    ExperimentManifest,
    ExperimentSession,
    ExperimentClock,
    GnssState,
    IntervalKind,
    MaskInterval,
    MaskPolicy,
    MaskProvenance,
    MotionState,
    Mount,
    MountPosition,
    OrientationMethod,
    OutageMask,
    ReferenceAlignment,
    ReferenceClass,
    ReferenceDescriptor,
    ReferenceTimebase,
    ScenarioClass,
    SessionRole,
)
from contracts.recording.v1.codec import encode_metadata, encode_record
from contracts.recording.v1.session import SessionError
from contracts.recording.v1.models import (
    ApplicationInfo,
    CalibrationApplication,
    CalibrationInfo,
    ClockDomain as RecordingClockDomain,
    ClockIdentity,
    CompletionState,
    DeviceInfo,
    LocationPermissionState,
    RecordingEndState,
    RecordingMetadata,
    RecordingStartState,
    RecoveryState,
    SensorDescriptor,
    SourceConfiguration,
)

REPO_ROOT = Path(__file__).resolve().parents[1]

#: Above 2^53: monotonic nanoseconds must survive exactly, never via binary64.
BASE_NS = 9_007_199_254_740_993
SECOND_NS = 1_000_000_000
SPAN_NS = 60 * SECOND_NS
BOOT_ID = "boot-2026-09-30"

#: A digest that is never all zeros: the all-zero value means "not yet sealed".
FIXED_DIGEST = "a" * 64


def imu_record(event_id: str, t_ns: int, *, session: str = "rec-primary",
               source: Source = Source.REAL) -> Record:
    return Record(
        Header(session, source, "1.0.0"),
        Event(event_id, t_ns, t_ns, ImuMeasurement(
            Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
            ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(0.0, 0.0, 9.81),
            SensorAccuracy.HIGH,
        )),
    )


def gnss_record(event_id: str, t_ns: int, *, session: str = "rec-primary",
                source: Source = Source.REAL) -> Record:
    return Record(
        Header(session, source, "1.0.0"),
        Event(event_id, t_ns, t_ns, GnssMeasurement(
            17.4250, 78.4750, None, None, None, None, 8.0, None, 7, "gps", None,
        )),
    )


def session_metadata(records, *, recording_id: str, session: str, boot_id: str | None = BOOT_ID,
                     origin_ns: int = BASE_NS, ended_ns: int | None = BASE_NS + SPAN_NS,
                     completion: CompletionState = CompletionState.COMPLETED,
                     source: Source = Source.REAL) -> RecordingMetadata:
    """Finished, replayable session metadata consistent with `records`.

    An `open` session must carry no end, no count and no recovery state, exactly as the
    recording contract requires of an unfinalized session.
    """
    open_session = completion is CompletionState.OPEN
    return RecordingMetadata(
        recording_id=recording_id,
        acquisition_session_id=session,
        source=source,
        start_state=RecordingStartState.RECORDING,
        end_state=None if open_session else RecordingEndState.STOPPED,
        completion_state=completion,
        recovery_state=RecoveryState.NONE,
        created_utc_ms=1_789_000_000_000,
        started_utc_ms=None if open_session else 1_789_000_000_100,
        ended_utc_ms=None if open_session else 1_789_000_060_100,
        clock=ClockIdentity(
            domain=RecordingClockDomain.ANDROID_ELAPSED_REALTIME_NS,
            boot_id=boot_id,
            session_clock_id=f"clock-{recording_id}",
            origin_ns=origin_ns,
            started_ns=origin_ns,
            ended_ns=None if open_session else ended_ns,
        ),
        device=DeviceInfo("OnePlus", "CPH2585", "Android", "16", 36),
        application=ApplicationInfo("com.intelligentdeadreckoning.app", "0.1.0", 1, None),
        sensors=(
            SensorDescriptor(Sensor.ACCELEROMETER, "fixture accelerometer",
                             "fixture vendor", True, 100.0, 98.8),
        ),
        source_configuration=SourceConfiguration(
            LocationPermissionState.PRECISE, True, True, True, True, True),
        calibration=CalibrationInfo(CalibrationApplication.NOT_APPLIED, None),
        record_count=None if open_session else len(records),
        channel_counts=None,
    )


class ExperimentBuilder:
    """Builds one experiment directory from parts, then seals it."""

    def __init__(self, root: Path, experiment_id: str | None = None):
        self.root = root
        # The manifest must declare its own directory name, so that is the default ID.
        self.experiment_id = experiment_id or root.name
        self.root.mkdir(parents=True, exist_ok=True)
        self.sessions: list[tuple[str, SessionRole, str]] = []
        self.annotations: list[AnnotationInterval] = []
        self.masks: dict[str, OutageMask] = {}
        self.reference: ReferenceDescriptor | None = None
        self.reference_records: list[Record] = []
        self.manifest: ExperimentManifest | None = None

    # -- parts -------------------------------------------------------------------------

    def add_session(self, session_id: str, records, *, role: SessionRole = SessionRole.PRIMARY,
                    boot_id: str | None = BOOT_ID, digest: str | None = None,
                    completion: CompletionState = CompletionState.COMPLETED,
                    mutate_body=None, **overrides) -> str:
        directory = self.root / "sessions" / session_id
        directory.mkdir(parents=True, exist_ok=True)
        metadata = session_metadata(records, recording_id=session_id, session=session_id,
                                    boot_id=boot_id, completion=completion, **overrides)
        (directory / "metadata.json").write_bytes(encode_metadata(metadata))
        body = b"".join(encode_record(record, metadata) + b"\n" for record in records)
        if mutate_body is not None:
            body = mutate_body(body)
        (directory / "measurements.jsonl").write_bytes(body)
        self.sessions.append((session_id, role, digest or sha256_file(
            directory / "measurements.jsonl")))
        return session_id

    def add_annotations(self, intervals) -> None:
        self.annotations.extend(intervals)

    def add_mask(self, mask_id: str, session_id: str, intervals, *,
                 provenance: MaskProvenance = MaskProvenance.SYNTHETIC,
                 note: str | None = "designed for evaluation") -> None:
        self.masks[mask_id] = OutageMask(
            mask_id=mask_id,
            session_id=session_id,
            description=f"{mask_id} outage",
            provenance=provenance,
            policy=MaskPolicy.SUPPRESS_GNSS,
            intervals=tuple(intervals),
            note=note,
        )

    def add_reference(self, records, *, equipment_class: ReferenceClass = ReferenceClass.RTK,
                      timebase: ReferenceTimebase = ReferenceTimebase.GPS_UTC,
                      aligned: bool = False) -> None:
        self.reference = ReferenceDescriptor(
            equipment_class=equipment_class,
            equipment="fixture RTK receiver",
            horizontal_accuracy_m=0.02,
            rate_hz=5.0,
            timebase=timebase,
            source=Source.REAL,
            note=None,
        )
        self.reference_records = list(records)
        self.aligned = aligned

    # -- manifest and seal -------------------------------------------------------------

    def write_manifest(self, **overrides) -> ExperimentManifest:
        manifest = ExperimentManifest(
            experiment_id=overrides.pop("experiment_id", self.experiment_id),
            created_utc_ms=overrides.pop("created_utc_ms", 1_789_000_000_000),
            description=overrides.pop("description", "fixture urban drive"),
            operator=overrides.pop("operator", "fixture operator"),
            vehicle=overrides.pop("vehicle", "fixture car"),
            mount=overrides.pop("mount", Mount(
                MountPosition.DASH_CENTRE, OrientationMethod.MEASURED,
                roll_deg=0.0, pitch_deg=-3.5, yaw_deg=12.0, uncertainty_deg=1.5, note=None)),
            clock=overrides.pop("clock", ExperimentClock(
                domain="android_elapsed_realtime_ns",
                boot_id=BOOT_ID,
                reference_alignment=(ReferenceAlignment("cross_correlation", 1_000, 5_000_000, None)
                                     if getattr(self, "aligned", False) else None))),
            sessions=overrides.pop("sessions", tuple(
                ExperimentSession(session_id, role, digest)
                for session_id, role, digest in self.sessions)),
            masks=overrides.pop("masks", tuple(sorted(self.masks))),
            reference=overrides.pop("reference", self.reference),
        )
        self.manifest = manifest
        (self.root / MANIFEST_NAME).write_bytes(encode_manifest(manifest))
        (self.root / ANNOTATIONS_NAME).write_bytes(encode_annotations(self.annotations))
        if self.reference is not None and self.reference_records:
            directory = self.root / "reference"
            directory.mkdir(exist_ok=True)
            body = b"".join(encode_measurement_json(record) + b"\n"
                            for record in self.reference_records)
            (directory / "reference.jsonl").write_bytes(body)
        for mask_id, mask in self.masks.items():
            (self.root / "masks").mkdir(exist_ok=True)
            (self.root / "masks" / f"{mask_id}.json").write_bytes(encode_mask(mask))
        return manifest

    def seal(self, mutate=None) -> bytes:
        body = build_integrity(self.root)
        if mutate is not None:
            body = mutate(body)
        (self.root / INTEGRITY_NAME).write_bytes(body)
        return body


def standard_records(session_id: str = "rec-primary", *, gnss_count: int = 6) -> list[Record]:
    """Two IMU records per second for the span, plus GNSS at whole seconds.

    Event IDs are decimal strings and unique within the session, as the measurement
    contract requires.
    """
    records = [
        imu_record(str(index), BASE_NS + index * SECOND_NS // 2, session=session_id)
        for index in range(2 * (SPAN_NS // SECOND_NS))
    ]
    records += [
        gnss_record(str(10_000 + index), BASE_NS + (index + 1) * SECOND_NS,
                    session=session_id)
        for index in range(gnss_count)
    ]
    return sorted(records, key=lambda record: record.event.t_ns)


def observation_intervals(session_id: str = "rec-primary") -> list[AnnotationInterval]:
    """A coherent good/degraded/lost/recovered/good GNSS timeline, with motion and scenario."""
    def at(seconds: int) -> int:
        return BASE_NS + seconds * SECOND_NS

    def interval(kind, start_s, end_s, **payload):
        fields = dict(kind=kind, session_id=session_id, start_ns=at(start_s),
                      end_ns=at(end_s), note=None, calibration_id=None, motion=None,
                      scenario=None, gnss_state=None)
        fields.update(payload)
        return AnnotationInterval(**fields)

    return [
        interval(IntervalKind.SCENARIO, 0, 30, scenario=ScenarioClass.OPEN_SKY),
        interval(IntervalKind.SCENARIO, 30, 60, scenario=ScenarioClass.URBAN_CANYON),
        interval(IntervalKind.MOTION, 0, 10, motion=MotionState.STATIONARY),
        interval(IntervalKind.MOTION, 10, 45, motion=MotionState.STRAIGHT),
        interval(IntervalKind.MOTION, 45, 60, motion=MotionState.TURNING_RIGHT),
        interval(IntervalKind.GNSS_STATE, 0, 10, gnss_state=GnssState.GOOD),
        interval(IntervalKind.GNSS_STATE, 10, 20, gnss_state=GnssState.DEGRADED),
        interval(IntervalKind.GNSS_STATE, 20, 30, gnss_state=GnssState.LOST),
        interval(IntervalKind.GNSS_STATE, 30, 35, gnss_state=GnssState.RECOVERED),
        interval(IntervalKind.GNSS_STATE, 35, 60, gnss_state=GnssState.GOOD),
        interval(IntervalKind.CALIBRATION, 55, 58, calibration_id="cal-1"),
    ]


def valid_experiment(root: Path, *, with_reference: bool = False,
                     with_mask: bool = True) -> ExperimentBuilder:
    """A complete, sealed, valid experiment directory."""
    builder = ExperimentBuilder(root)
    builder.add_session("rec-primary", standard_records())
    builder.add_annotations(observation_intervals())
    if with_mask:
        builder.add_mask("tunnel_synthetic", "rec-primary", [
            MaskInterval(BASE_NS + 20 * SECOND_NS, BASE_NS + 30 * SECOND_NS, "tunnel"),
        ])
    if with_reference:
        builder.add_reference(
            [gnss_record(str(index), BASE_NS + (index + 1) * SECOND_NS,
                         session="reference-1")
             for index in range(6)])
    builder.write_manifest()
    builder.seal()
    return builder


class ExperimentFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def assertExperimentError(self, code, directory, **kwargs):
        with self.assertRaises(ExperimentError) as raised:
            open_experiment(directory, **kwargs)
        self.assertEqual(raised.exception.code, code, str(raised.exception))
        return raised.exception

    def assertContractError(self, code, callable_, *args, **kwargs):
        with self.assertRaises(ExperimentContractError) as raised:
            callable_(*args, **kwargs)
        self.assertEqual(raised.exception.code, code, str(raised.exception))
        return raised.exception


# --------------------------------------------------------------------------------------
# Codec and committed fixtures
# --------------------------------------------------------------------------------------


class ExperimentCodecTest(ExperimentFixture):
    def test_golden_manifest_round_trips_byte_for_byte(self):
        path = REPO_ROOT / "contracts/experiment/v1/golden_experiment.json"
        raw = path.read_bytes()
        manifest = decode_manifest(raw)
        self.assertEqual(manifest.experiment_id, "exp-golden-urban-01")
        self.assertEqual(manifest.primary.session_id, "rec-primary")
        self.assertEqual(len(manifest.sessions), 2)
        self.assertEqual(manifest.masks, ("tunnel_observed",))
        self.assertIsNotNone(manifest.reference)
        self.assertEqual(encode_manifest(manifest), raw)

    def test_golden_annotations_and_mask_decode(self):
        annotations = decode_annotations(
            (REPO_ROOT / "contracts/experiment/v1/golden_annotations.jsonl").read_bytes())
        self.assertEqual(encode_annotations(annotations)
                         , (REPO_ROOT / "contracts/experiment/v1/golden_annotations.jsonl")
                         .read_bytes())
        kinds = {interval.kind for interval in annotations}
        self.assertEqual(kinds, {IntervalKind.GNSS_STATE, IntervalKind.MOTION,
                                 IntervalKind.SCENARIO, IntervalKind.CALIBRATION})
        mask = decode_mask((REPO_ROOT / "contracts/experiment/v1/golden_mask.json").read_bytes())
        self.assertEqual(mask.mask_id, "tunnel_observed")
        self.assertEqual(mask.policy, MaskPolicy.SUPPRESS_GNSS)

    def test_invalid_manifest_fixtures_are_rejected_with_the_declared_code(self):
        cases = json.loads(
            (REPO_ROOT / "contracts/experiment/v1/invalid_manifests.json").read_text("utf-8"))
        self.assertTrue(cases)
        for case in cases:
            with self.subTest(case=case["name"]):
                data = json.dumps(case["manifest"], separators=(",", ":")).encode("utf-8")
                self.assertContractError(case["code"], decode_manifest, data)

    def test_unknown_and_missing_keys_are_rejected(self):
        raw = json.loads(
            (REPO_ROOT / "contracts/experiment/v1/golden_experiment.json").read_text("utf-8"))
        self.assertContractError("INVALID_KEYS", decode_manifest,
                                 json.dumps({**raw, "extra": 1}).encode())
        without = dict(raw)
        without.pop("mount")
        self.assertContractError("INVALID_KEYS", decode_manifest,
                                 json.dumps(without).encode())
        # An explicit null is the required spelling for an unavailable value.
        explicit = json.dumps({**raw, "operator": None}).encode()
        self.assertIsNone(decode_manifest(explicit).operator)
        # Omission is not a spelling of null: every defined key must be present.
        without_operator = dict(raw)
        without_operator.pop("operator")
        self.assertContractError("INVALID_KEYS", decode_manifest,
                                 json.dumps(without_operator).encode())

    def test_manifest_requires_exactly_one_primary_session(self):
        manifest = decode_manifest(
            (REPO_ROOT / "contracts/experiment/v1/golden_experiment.json").read_bytes())
        two_primaries = ExperimentManifest(
            manifest.experiment_id, manifest.created_utc_ms, manifest.description,
            manifest.operator, manifest.vehicle, manifest.mount, manifest.clock,
            tuple(ExperimentSession(entry.session_id, SessionRole.PRIMARY,
                                    entry.measurements_sha256)
                  for entry in manifest.sessions),
            manifest.reference, manifest.masks,
        )
        self.assertContractError("INVARIANT", encode_manifest, two_primaries)

    def test_assumed_mounting_must_carry_a_note(self):
        manifest = decode_manifest(
            (REPO_ROOT / "contracts/experiment/v1/golden_experiment.json").read_bytes())
        assumed = ExperimentManifest(
            manifest.experiment_id, manifest.created_utc_ms, manifest.description,
            manifest.operator, manifest.vehicle,
            Mount(MountPosition.ROOF, OrientationMethod.ASSUMED, 0.0, 0.0, 0.0, 10.0, None),
            manifest.clock, manifest.sessions, manifest.reference, manifest.masks,
        )
        self.assertContractError("INVARIANT", encode_manifest, assumed)

    def test_annotation_payload_must_match_its_kind(self):
        line = {
            "kind": "motion", "session_id": "rec-primary", "start_ns": "0", "end_ns": "1",
            "note": None, "calibration_id": None, "motion": None,
            "scenario": "urban_canyon", "gnss_state": None,
        }
        self.assertContractError("INVALID_SHAPE", decode_annotations,
                                 json.dumps(line).encode() + b"\n")

    def test_annotation_intervals_must_be_ordered_and_disjoint(self):
        def line(kind, start, end, payload):
            body = {
                "kind": kind, "session_id": "rec-primary", "start_ns": str(start),
                "end_ns": str(end), "note": None, "calibration_id": None,
                "motion": None, "scenario": None, "gnss_state": None,
            }
            body[payload[0]] = payload[1]
            return json.dumps(body).encode() + b"\n"

        overlapping = (line("gnss_state", 0, 10, ("gnss_state", "good"))
                       + line("gnss_state", 5, 20, ("gnss_state", "lost")))
        self.assertContractError("OVERLAPPING_INTERVALS", decode_annotations, overlapping)

        out_of_order = (line("gnss_state", 20, 30, ("gnss_state", "lost"))
                        + line("gnss_state", 0, 10, ("gnss_state", "good")))
        self.assertContractError("OUT_OF_ORDER", decode_annotations, out_of_order)

        touching_same = (line("gnss_state", 0, 10, ("gnss_state", "good"))
                         + line("gnss_state", 10, 20, ("gnss_state", "good")))
        self.assertContractError("DUPLICATE_INTERVAL", decode_annotations, touching_same)

        zero_length = line("gnss_state", 10, 10, ("gnss_state", "lost"))
        self.assertContractError("INVALID_INTERVAL", decode_annotations, zero_length)

        # Touching intervals with different states are the normal transition, not an error.
        transition = (line("gnss_state", 0, 10, ("gnss_state", "good"))
                      + line("gnss_state", 10, 20, ("gnss_state", "lost")))
        self.assertEqual(len(decode_annotations(transition)), 2)

    def test_mask_interval_order_is_enforced(self):
        def mask(intervals):
            return OutageMask("mask-1", "rec-primary", "d", MaskProvenance.SYNTHETIC,
                              MaskPolicy.SUPPRESS_GNSS,
                              tuple(MaskInterval(*interval, None) for interval in intervals),
                              None)

        self.assertContractError("INVALID_INTERVAL", encode_mask, mask([(10, 10)]))
        self.assertContractError("OUT_OF_ORDER", encode_mask, mask([(20, 30), (5, 8)]))
        self.assertContractError("OVERLAPPING_INTERVALS", encode_mask, mask([(0, 100), (5, 8)]))
        self.assertContractError("INVARIANT", encode_mask, mask([]))
        observed_without_note = OutageMask(
            "mask-1", "rec-primary", "d", MaskProvenance.OBSERVED, MaskPolicy.SUPPRESS_GNSS,
            (MaskInterval(0, 10, None),), None)
        self.assertContractError("INVARIANT", encode_mask, observed_without_note)


class IntegrityManifestTest(ExperimentFixture):
    def test_manifest_is_sorted_and_round_trips(self):
        entries = {"b.txt": "b" * 64, "a.txt": "a" * 64}
        body = encode_integrity(entries)
        self.assertEqual(body, b"a" * 64 + b"  a.txt\n" + b"b" * 64 + b"  b.txt\n")
        self.assertEqual(decode_integrity(body), entries)

    def test_malformed_and_duplicate_lines_are_rejected(self):
        for body, code in (
            (b"not-a-hash  file\n", "MALFORMED_INTEGRITY"),
            (b"a" * 64 + b" file\n", "MALFORMED_INTEGRITY"),
            (b"a" * 63 + b"  f\n", "MALFORMED_INTEGRITY"),
            ("é".encode() + b"  f\n", "MALFORMED_INTEGRITY"),
        ):
            with self.subTest(body=body[:20]):
                with self.assertRaises(IntegrityError) as raised:
                    decode_integrity(body)
                self.assertEqual(raised.exception.code, code)
        duplicate = encode_integrity({"f": "a" * 64}) * 2
        with self.assertRaises(IntegrityError) as raised:
            decode_integrity(duplicate)
        self.assertEqual(raised.exception.code, "DUPLICATE_ENTRY")

    def test_verification_covers_every_file_below_the_root(self):
        root = self.root / "exp"
        (root / "masks").mkdir(parents=True)
        (root / "a.txt").write_bytes(b"a")
        (root / "masks" / "m.json").write_bytes(b"m")
        declared = decode_integrity(build_integrity(root))
        self.assertEqual(set(declared), {"a.txt", "masks/m.json"})
        verify_integrity(root, declared)

        (root / "masks" / "extra.json").write_bytes(b"extra")
        with self.assertRaises(IntegrityError) as raised:
            verify_integrity(root, declared)
        self.assertEqual(raised.exception.code, "UNLISTED_FILE")

    def test_missing_and_corrupt_artifacts_are_distinguished(self):
        root = self.root / "exp"
        root.mkdir()
        (root / "a.txt").write_bytes(b"a")
        declared = decode_integrity(build_integrity(root))
        (root / "a.txt").write_bytes(b"b")
        with self.assertRaises(IntegrityError) as raised:
            verify_integrity(root, declared)
        self.assertEqual(raised.exception.code, "HASH_MISMATCH")
        (root / "a.txt").unlink()
        with self.assertRaises(IntegrityError) as raised:
            verify_integrity(root, declared)
        self.assertEqual(raised.exception.code, "MISSING_ARTIFACT")

    def test_escaping_paths_are_refused(self):
        root = self.root / "exp"
        root.mkdir()
        for name in ("/etc/passwd", "../escape", "a/../../b", "a\\b", "C:/Windows/win.ini",
                     "C:\\\\Windows\\\\win.ini", "a/./b", "a//b", "./metadata.json"):
            with self.subTest(name=name):
                with self.assertRaises(IntegrityError) as raised:
                    verify_integrity(root, {name: "a" * 64})
                self.assertEqual(raised.exception.code, "UNSAFE_PATH")
                with self.assertRaises(IntegrityError) as raised:
                    encode_integrity({name: "a" * 64})
                self.assertEqual(raised.exception.code, "UNSAFE_PATH")

    def test_symlinked_directory_roots_and_artifacts_are_refused(self):
        real = self.root / "real"
        real.mkdir()
        (real / "payload.json").write_bytes(b"fixture")
        entries = decode_integrity(build_integrity(real))
        try:
            linked_root = self.root / "linked-root"
            linked_root.symlink_to(real, target_is_directory=True)
            with self.assertRaises(IntegrityError) as raised:
                build_integrity(linked_root)
            self.assertEqual(raised.exception.code, "UNSAFE_PATH")
            with self.assertRaises(IntegrityError) as raised:
                verify_integrity(linked_root, entries)
            self.assertEqual(raised.exception.code, "UNSAFE_PATH")

            (real / "linked-payload.json").symlink_to(real / "payload.json")
            with self.assertRaises(IntegrityError) as raised:
                build_integrity(real)
            self.assertEqual(raised.exception.code, "UNSAFE_PATH")
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable in this environment")


# --------------------------------------------------------------------------------------
# Masking
# --------------------------------------------------------------------------------------


class MaskingTest(ExperimentFixture):
    def test_masking_withholds_only_in_interval_gnss_measurements(self):
        records = standard_records()
        mask = OutageMask("m", "rec-primary", "d", MaskProvenance.SYNTHETIC,
                          MaskPolicy.SUPPRESS_GNSS,
                          (MaskInterval(BASE_NS + 2 * SECOND_NS,
                                        BASE_NS + 4 * SECOND_NS, None),), None)
        replay = MaskedReplay(records, mask)
        kept = list(replay)
        self.assertEqual(replay.total_gnss, 6)
        # Half-open: the record at +2 s and +3 s go, the one at +4 s stays.
        self.assertEqual(replay.suppressed_gnss, 2)
        self.assertEqual(sum(1 for r in kept if r.event.data.TYPE == "imu"),
                         sum(1 for r in records if r.event.data.TYPE == "imu"))
        self.assertNotIn(BASE_NS + 2 * SECOND_NS,
                         [r.event.t_ns for r in kept if r.event.data.TYPE == "gnss"])
        self.assertIn(BASE_NS + 4 * SECOND_NS,
                      [r.event.t_ns for r in kept if r.event.data.TYPE == "gnss"])

    def test_masking_never_rewrites_a_record(self):
        records = standard_records()
        mask = OutageMask("m", "rec-primary", "d", MaskProvenance.SYNTHETIC,
                          MaskPolicy.SUPPRESS_GNSS,
                          (MaskInterval(BASE_NS, BASE_NS + SPAN_NS, None),), None)
        kept = list(MaskedReplay(records, mask))
        # A full-span mask removes GNSS and nothing else; no record is altered.
        self.assertEqual([r for r in kept if r.event.data.TYPE == "gnss"], [])
        self.assertEqual(len(kept), len(records) - 6)
        self.assertEqual(kept[0].event.data, records[0].event.data)

    def test_unmasked_replay_is_a_pass_through(self):
        records = standard_records()
        replay = MaskedReplay(records)
        self.assertEqual(list(replay), records)
        self.assertEqual(replay.suppressed_gnss, 0)
        self.assertEqual(replay.total_gnss, 6)
        self.assertIsNone(replay.mask_id)

    def test_masking_is_deterministic(self):
        records = standard_records()
        mask = OutageMask("m", "rec-primary", "d", MaskProvenance.SYNTHETIC,
                          MaskPolicy.SUPPRESS_GNSS,
                          (MaskInterval(BASE_NS + SECOND_NS, BASE_NS + 3 * SECOND_NS, None),),
                          None)
        first = [r.event.event_id for r in MaskedReplay(records, mask)]
        second = [r.event.event_id for r in MaskedReplay(records, mask)]
        self.assertEqual(first, second)

    def test_mask_duration_is_the_sum_of_its_intervals(self):
        mask = OutageMask("m", "rec-primary", "d", MaskProvenance.SYNTHETIC,
                          MaskPolicy.SUPPRESS_GNSS,
                          (MaskInterval(0, 10, None), MaskInterval(20, 25, None)), None)
        self.assertEqual(mask_duration_ns(mask), 15)
        self.assertEqual(mask_duration_ns(None), 0)


# --------------------------------------------------------------------------------------
# Loader: a valid experiment, and the six required failure modes
# --------------------------------------------------------------------------------------


class ExperimentLoaderTest(ExperimentFixture):
    def test_valid_experiment_opens_with_every_artifact_bound(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        experiment = open_experiment(directory)
        self.assertEqual(experiment.manifest.experiment_id, "exp-alpha")
        self.assertEqual(experiment.primary().session_id, "rec-primary")
        self.assertEqual(len(experiment.annotations), 11)
        self.assertEqual(sorted(experiment.masks), ["tunnel_synthetic"])
        self.assertEqual(experiment.integrity["experiment.json"],
                         sha256_file(directory / "experiment.json"))
        self.assertEqual(len(list(experiment.open_session("rec-primary").records)),
                         len(standard_records()))

    def test_valid_experiment_with_reference_streams_canonical_records(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory, with_reference=True)
        experiment = open_experiment(directory)
        self.assertEqual(experiment.reference.records, 6)
        records = list(experiment.reference_records())
        self.assertEqual(len(records), 6)
        self.assertTrue(all(record.event.data.TYPE == "gnss" for record in records))
        self.assertEqual(records[0].header.source, Source.REAL)

    def test_inspect_never_raises_and_reports_a_missing_manifest(self):
        directory = self.root / "exp-alpha"
        directory.mkdir()
        summary = inspect_experiment(directory)
        self.assertFalse(summary.readable)
        self.assertEqual(summary.error_code, "MISSING_ARTIFACT")
        self.assertEqual(summary.experiment_id, "exp-alpha")

    def test_directory_name_must_match_the_declared_experiment_id(self):
        valid_experiment(self.root / "exp-alpha")
        moved = self.root / "exp-beta"
        (self.root / "exp-alpha").rename(moved)
        self.assertExperimentError("ID_MISMATCH", moved)

    def test_session_records_are_read_only_and_map_to_the_replay_source(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        experiment = open_experiment(directory)
        before = (directory / "sessions/rec-primary/measurements.jsonl").read_bytes()
        replay = experiment.replay_input("rec-primary")
        try:
            records = list(replay)
        finally:
            replay.close()
        self.assertTrue(all(r.header.source is Source.REPLAY_REAL for r in records))
        self.assertEqual(
            (directory / "sessions/rec-primary/measurements.jsonl").read_bytes(), before)

    def test_replay_input_measures_what_a_mask_withholds(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        experiment = open_experiment(directory)
        baseline = experiment.replay_input("rec-primary")
        try:
            list(baseline)
        finally:
            baseline.close()
        self.assertEqual(baseline.suppressed_gnss, 0)
        masked = experiment.replay_input("rec-primary", "tunnel_synthetic")
        try:
            list(masked)
        finally:
            masked.close()
        # The mask covers +20 s..+30 s; GNSS is recorded at +1 s..+6 s, so nothing is withheld.
        self.assertEqual(masked.total_gnss, 6)
        self.assertEqual(masked.suppressed_gnss, 0)
        self.assertEqual(masked.mask_id, "tunnel_synthetic")

    def test_mask_authored_for_one_session_is_not_applied_to_another(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_session("rec-second", standard_records("rec-second", gnss_count=4),
                            role=SessionRole.SUPPLEMENTARY)
        builder.add_annotations(observation_intervals())
        builder.add_mask("second_only", "rec-second", [
            MaskInterval(BASE_NS + SECOND_NS, BASE_NS + 2 * SECOND_NS, None)])
        builder.write_manifest()
        builder.seal()
        # The mask itself is valid, so loading succeeds; applying it to a session it was
        # not authored against would silently reinterpret timestamps, so that is refused.
        experiment = open_experiment(directory)
        self.assertEqual(experiment.masks["second_only"].session_id, "rec-second")
        with self.assertRaises(ExperimentError) as raised:
            experiment.replay_input("rec-primary", "second_only")
        self.assertEqual(raised.exception.code, "MASK_ID_MISMATCH")
        masked = experiment.replay_input("rec-second", "second_only")
        try:
            list(masked)
        finally:
            masked.close()
        self.assertEqual(masked.total_gnss, 4)
        self.assertEqual(masked.suppressed_gnss, 1)

    def test_unknown_mask_and_session_ids_are_refused(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        experiment = open_experiment(directory)
        with self.assertRaises(ExperimentError) as raised:
            experiment.replay_input("rec-primary", "no-such-mask")
        self.assertEqual(raised.exception.code, "UNKNOWN_MASK")
        with self.assertRaises(ExperimentError) as raised:
            experiment.open_session("no-such-session")
        self.assertEqual(raised.exception.code, "UNKNOWN_SESSION")


class ExperimentRejectionTest(ExperimentFixture):
    """The six cases the collection foundation must decide correctly."""

    def test_1_valid_experiment_is_accepted(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory, with_reference=True)
        experiment = open_experiment(directory)
        self.assertEqual(experiment.manifest.experiment_id, "exp-alpha")

    def test_2_missing_reference_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = valid_experiment(directory, with_reference=True)
        (directory / "reference" / "reference.jsonl").unlink()
        # The file is part of the directory, so the integrity manifest notices first: a
        # deleted artifact is never silently treated as "no reference".
        with self.assertRaises(ExperimentError) as raised:
            open_experiment(directory)
        self.assertEqual(raised.exception.code, "MISSING_ARTIFACT")
        # With the index re-sealed around the missing file, the declared reference is the
        # thing that is missing, and that is the code the loader reports.
        builder.seal()
        self.assertExperimentError("MISSING_REFERENCE", directory)

    def test_3_corrupt_hash_is_rejected(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        body = (directory / ANNOTATIONS_NAME).read_bytes()
        (directory / ANNOTATIONS_NAME).write_bytes(body + b"\n")
        self.assertExperimentError("HASH_MISMATCH", directory)

    def test_3b_corrupt_recording_is_rejected_without_being_repaired(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        measurements = directory / "sessions/rec-primary/measurements.jsonl"
        original = measurements.read_bytes()
        row = original.split(b"\n")[0]
        measurements.write_bytes(original.replace(row, row + b" ", 1))
        self.assertExperimentError("HASH_MISMATCH", directory)
        # Neither the recording nor the integrity manifest was touched by the refusal.
        self.assertIn(b" ", measurements.read_bytes())
        self.assertNotEqual(measurements.read_bytes(), original)

    def test_3c_tampered_integrity_manifest_is_rejected(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        path = directory / INTEGRITY_NAME
        kept = [line for line in path.read_text("utf-8").splitlines()
                if not line.endswith("  masks/tunnel_synthetic.json")]
        path.write_text("\n".join(kept) + "\n", encoding="utf-8")
        self.assertExperimentError("UNLISTED_FILE", directory)

    def test_3d_a_line_stripped_of_its_path_is_malformed(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        path = directory / INTEGRITY_NAME
        path.write_bytes(path.read_bytes().replace(
            b"  masks/tunnel_synthetic.json\n", b""))
        self.assertExperimentError("MALFORMED_INTEGRITY", directory)

    def test_3e_session_digest_disagreeing_with_its_file_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), digest=FIXED_DIGEST)
        builder.add_annotations(observation_intervals())
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("SESSION_HASH_MISMATCH", directory)

    def test_4_invalid_outage_interval_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_annotations(observation_intervals())
        builder.add_mask("bad", "rec-primary", [
            MaskInterval(BASE_NS + 30 * SECOND_NS, BASE_NS + 20 * SECOND_NS, None)])
        # The codec refuses a reversed interval before any file is written.
        with self.assertRaises(ExperimentContractError) as raised:
            encode_mask(builder.masks["bad"])
        self.assertEqual(raised.exception.code, "INVALID_INTERVAL")

    def test_4b_outage_interval_outside_the_session_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_annotations(observation_intervals())
        builder.add_mask("too_late", "rec-primary", [
            MaskInterval(BASE_NS + 90 * SECOND_NS, BASE_NS + 95 * SECOND_NS, None)])
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("INTERVAL_OUT_OF_SESSION", directory)

    def test_4c_outage_interval_before_the_session_origin_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_annotations(observation_intervals())
        builder.add_mask("before", "rec-primary", [
            MaskInterval(BASE_NS - 5 * SECOND_NS, BASE_NS + SECOND_NS, None)])
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("INTERVAL_OUT_OF_SESSION", directory)

    def test_4d_overlapping_outage_intervals_are_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_annotations(observation_intervals())
        builder.add_mask("overlap", "rec-primary", [
            MaskInterval(BASE_NS, BASE_NS + 10 * SECOND_NS, None),
            MaskInterval(BASE_NS + 5 * SECOND_NS, BASE_NS + 15 * SECOND_NS, None)])
        with self.assertRaises(ExperimentContractError) as raised:
            encode_mask(builder.masks["overlap"])
        self.assertEqual(raised.exception.code, "OVERLAPPING_INTERVALS")

    def test_4e_mask_naming_an_undeclared_session_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_annotations(observation_intervals())
        # A declared mask that names a session nobody declared: the mask itself is
        # well-formed, so only the cross-artifact check can catch it.
        builder.add_mask("stray", "rec-nobody", [
            MaskInterval(BASE_NS + SECOND_NS, BASE_NS + 2 * SECOND_NS, None)])
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("UNKNOWN_SESSION", directory)

    def test_5_clock_mismatch_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), boot_id="boot-other")
        builder.add_annotations(observation_intervals())
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("CLOCK_MISMATCH", directory)

    def test_5b_missing_boot_identity_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), boot_id=None)
        builder.add_annotations(observation_intervals())
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("CLOCK_MISMATCH", directory)

    def test_5c_second_session_on_another_boot_is_rejected(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_session("rec-second", standard_records("rec-second", gnss_count=4),
                            role=SessionRole.SUPPLEMENTARY, boot_id="boot-other")
        builder.add_annotations(observation_intervals())
        builder.write_manifest()
        builder.seal()
        self.assertExperimentError("CLOCK_MISMATCH", directory)

    def test_6_duplicate_experiment_id_in_a_corpus_is_rejected(self):
        # Two campaigns each collected an `exp-alpha`. Every directory is individually
        # valid — its manifest matches its own name — so only a corpus-level check can
        # see that one ID now names two different drives.
        corpus = self.root / "corpus"
        valid_experiment(corpus / "campaign-a" / "exp-alpha")
        valid_experiment(corpus / "campaign-b" / "exp-alpha")
        duplicates = find_duplicate_ids(corpus)
        self.assertEqual(list(duplicates), ["exp-alpha"])
        self.assertEqual(duplicates["exp-alpha"],
                         ["campaign-a/exp-alpha", "campaign-b/exp-alpha"])
        findings = [finding.code for finding in check_corpus(corpus)]
        self.assertIn("DUPLICATE_EXPERIMENT_ID", findings)
        # Neither directory is itself invalid, which is exactly why the corpus check exists.
        self.assertEqual(check_corpus(corpus / "campaign-a"), [])

    def test_6b_corpus_reports_every_distinct_experiment_id_once(self):
        corpus = self.root / "corpus"
        valid_experiment(corpus / "campaign-a" / "exp-alpha")
        valid_experiment(corpus / "campaign-b" / "exp-gamma", with_reference=True)
        self.assertEqual(find_duplicate_ids(corpus), {})
        self.assertEqual(check_corpus(corpus), [])

    def test_6c_a_campaign_of_one_is_a_valid_corpus(self):
        valid_experiment(self.root / "exp-alpha")
        discovered = [directory.name for directory, _ in iter_experiments(self.root)]
        self.assertEqual(discovered, ["exp-alpha"])
        self.assertEqual(check_corpus(self.root), [])

    def test_an_unfinalized_session_is_refused_and_never_read_as_complete(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(),
                            completion=CompletionState.OPEN)
        builder.add_annotations(observation_intervals())
        builder.write_manifest()
        builder.seal()
        # Strict by default: an open session is not replayable, so the experiment is not.
        self.assertExperimentError("SESSION_NOT_REPLAYABLE", directory)
        # Asking for it anyway opens the experiment, and the recording reader still refuses
        # to stream a session that declares no end: inspection never becomes replay.
        experiment = open_experiment(directory, require_replayable=False)
        self.assertFalse(experiment.require_replayable)
        replay = experiment.replay_input("rec-primary")
        try:
            with self.assertRaises(SessionError) as raised:
                list(replay)
        finally:
            replay.close()
        self.assertEqual(raised.exception.code, "SESSION_NOT_FINALIZED")
        # The strict reader refuses the same session outright.
        with self.assertRaises(SessionError) as raised:
            experiment.open_session("rec-primary", require_replayable=True)
        self.assertEqual(raised.exception.code, "SESSION_NOT_REPLAYABLE")

    def test_integrity_verification_can_be_skipped_without_trusting_the_manifest(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        # Session digests are still enforced, so skipping the tree hash is a speed option
        # rather than a hole in the commitment.
        open_experiment(directory, verify_integrity_manifest=False)
        measurements = directory / "sessions/rec-primary/measurements.jsonl"
        measurements.write_bytes(measurements.read_bytes() + b" ")
        self.assertExperimentError("SESSION_HASH_MISMATCH", directory,
                                   verify_integrity_manifest=False)


# --------------------------------------------------------------------------------------
# Command-line tools
# --------------------------------------------------------------------------------------


class ExperimentToolTest(ExperimentFixture):
    def run_tool(self, tool: str, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, "-B", str(REPO_ROOT / "tools" / tool), *args],
            cwd=REPO_ROOT, capture_output=True, text=True, check=False)

    def test_validate_accepts_a_valid_experiment(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory, with_reference=True)
        result = self.run_tool("validate_experiment.py", "--experiment", str(directory))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report["accepted"])
        self.assertEqual(report["experiment"]["sessions"][0]["role"], "primary")
        self.assertEqual(report["experiment"]["reference"]["records"], 6)

    def test_validate_rejects_and_names_the_code(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        (directory / ANNOTATIONS_NAME).write_bytes(b'{"broken":1}\n')
        result = self.run_tool("validate_experiment.py", "--experiment", str(directory))
        self.assertEqual(result.returncode, 3)
        self.assertEqual(json.loads(result.stdout)["rejected"], "HASH_MISMATCH")

    def test_validate_corpus_flags_a_duplicated_experiment_id(self):
        corpus = self.root / "corpus"
        valid_experiment(corpus / "campaign-a" / "exp-alpha")
        valid_experiment(corpus / "campaign-b" / "exp-alpha")
        result = self.run_tool("validate_experiment.py", "--corpus", str(corpus))
        self.assertEqual(result.returncode, 3)
        report = json.loads(result.stdout)
        self.assertEqual([entry["experiment_id"] for entry in report["duplicate_experiment_ids"]],
                         ["exp-alpha"])
        self.assertEqual(report["duplicate_experiment_ids"][0]["directories"],
                         ["campaign-a/exp-alpha", "campaign-b/exp-alpha"])
        self.assertEqual(report["findings"], [])

    def test_validate_corpus_accepts_a_clean_corpus(self):
        corpus = self.root / "corpus"
        valid_experiment(corpus / "campaign-a" / "exp-alpha")
        valid_experiment(corpus / "campaign-b" / "exp-gamma")
        result = self.run_tool("validate_experiment.py", "--corpus", str(corpus))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue(json.loads(result.stdout)["accepted"])

    def test_validate_corpus_names_a_broken_experiment_by_its_path(self):
        corpus = self.root / "corpus"
        valid_experiment(corpus / "campaign-a" / "exp-alpha")
        broken = corpus / "campaign-b" / "exp-beta"
        valid_experiment(broken)
        (broken / ANNOTATIONS_NAME).write_bytes(b'{"broken":1}\n')
        result = self.run_tool("validate_experiment.py", "--corpus", str(corpus))
        self.assertEqual(result.returncode, 3)
        report = json.loads(result.stdout)
        self.assertEqual(report["findings"],
                         [{"experiment": "campaign-b/exp-beta",
                           "rejected": "HASH_MISMATCH", "path": "annotations.jsonl",
                           "detail": ""}])

    def test_report_renders_a_timeline_and_measures_a_named_mask(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records())
        builder.add_annotations(observation_intervals())
        builder.add_mask("tunnel_synthetic", "rec-primary", [
            MaskInterval(BASE_NS + 4 * SECOND_NS, BASE_NS + 6 * SECOND_NS, "tunnel")])
        builder.write_manifest()
        builder.seal()
        result = self.run_tool("experiment_report.py", "--experiment", str(directory),
                              "--mask", "tunnel_synthetic")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OBSERVED GNSS AVAILABILITY", result.stdout)
        self.assertIn("unannotated", result.stdout)
        self.assertIn("MASKED REPLAY EFFECT", result.stdout)
        self.assertIn("2 withheld", result.stdout)

    def test_report_json_reports_absence_of_a_reference_honestly(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        result = self.run_tool("experiment_report.py", "--experiment", str(directory), "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertIsNone(report["reference"])
        self.assertEqual(report["coverage"]["gnss_state"]["covered_s"], 60.0)
        self.assertEqual(report["coverage"]["gnss_state"]["unannotated_s"], 0.0)
        self.assertEqual(report["coverage"]["motion"]["covered_s"], 60.0)

    def test_report_rejects_an_unknown_mask(self):
        directory = self.root / "exp-alpha"
        valid_experiment(directory)
        result = self.run_tool("experiment_report.py", "--experiment", str(directory),
                              "--mask", "nope")
        self.assertEqual(result.returncode, 3)
        self.assertEqual(json.loads(result.stdout)["rejected"], "UNKNOWN_MASK")

    def test_seal_fills_digests_then_verifies_with_the_reader(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), digest="0" * 64)
        builder.add_annotations(observation_intervals())
        builder.write_manifest()
        # Deliberately not sealed: an all-zero digest must not be accepted.
        self.assertExperimentError("MISSING_ARTIFACT", directory)
        result = self.run_tool("seal_experiment.py", str(directory))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report["sessions"][0]["was_unsealed"])
        self.assertTrue(report["sessions"][0]["changed"])
        experiment = open_experiment(directory)
        self.assertEqual(experiment.manifest.primary.measurements_sha256,
                         sha256_file(directory / "sessions/rec-primary/measurements.jsonl"))

    def test_seal_dry_run_writes_nothing(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), digest="0" * 64)
        builder.write_manifest()
        before = {path.name for path in directory.iterdir()}
        result = self.run_tool("seal_experiment.py", str(directory), "--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({path.name for path in directory.iterdir()}, before)
        self.assertFalse((directory / INTEGRITY_NAME).exists())
        self.assertIn("0" * 64, (directory / MANIFEST_NAME).read_text("utf-8"))

    def test_seal_manifest_covers_only_the_final_experiment_files(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), digest="0" * 64)
        builder.write_manifest()
        result = self.run_tool("seal_experiment.py", str(directory))
        self.assertEqual(result.returncode, 0, result.stderr)
        integrity = decode_integrity((directory / INTEGRITY_NAME).read_bytes())
        self.assertEqual(set(integrity), {
            "experiment.json", "annotations.jsonl",
            "sessions/rec-primary/metadata.json",
            "sessions/rec-primary/measurements.jsonl",
        })
        self.assertFalse(any(name.endswith(".tmp") for name in integrity))
        self.assertEqual(integrity["experiment.json"], sha256_file(directory / MANIFEST_NAME))
        self.assertEqual(integrity, decode_integrity(build_integrity(directory)))

    def test_seal_refuses_symlinked_experiment_artifacts(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), digest="0" * 64)
        builder.write_manifest()
        outside = self.root / "outside.jsonl"
        outside.write_bytes((directory / ANNOTATIONS_NAME).read_bytes())
        try:
            (directory / ANNOTATIONS_NAME).unlink()
            (directory / ANNOTATIONS_NAME).symlink_to(outside)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable in this environment")
        result = self.run_tool("seal_experiment.py", str(directory))
        self.assertEqual(result.returncode, 3)
        self.assertEqual(json.loads(result.stdout)["rejected"], "UNSAFE_PATH")
        self.assertFalse((directory / INTEGRITY_NAME).exists())

    def test_seal_and_dataset_snapshot_reject_symlink_trees(self):
        from training.phase0.audit import snapshot

        real = self.root / "dataset-real"
        real.mkdir()
        (real / "capture.csv").write_text("time,value\\n0,1\\n", encoding="utf-8")
        link = self.root / "dataset-link"
        try:
            link.symlink_to(real, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "real directory"):
                snapshot(link)
            (real / "nested-link").symlink_to(real / "capture.csv")
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable in this environment")
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            snapshot(real)

    def test_integrity_manifest_rejects_symlinked_parent_directories(self):
        real = self.root / "real-parent"
        (real / "nested").mkdir(parents=True)
        (real / "nested/payload.json").write_bytes(b"fixture")
        entries = decode_integrity(build_integrity(real))
        linked = self.root / "linked-parent"
        try:
            linked.symlink_to(real, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable in this environment")
        with self.assertRaises(IntegrityError) as raised:
            verify_integrity(linked, entries)
        self.assertEqual(raised.exception.code, "UNSAFE_PATH")

    def test_dependency_audit_wrapper_requires_but_never_installs_pip_audit(self):
        result = self.run_tool("audit_dependencies.py", "--pip-audit", "definitely-not-installed-pip-audit")
        self.assertEqual(result.returncode, 2)
        self.assertIn("not installed", result.stderr)
        self.assertNotIn("requirements.txt", result.stdout)

    def test_hygiene_gate_scans_tracked_secret_policy_and_assets(self):
        result = subprocess.run(
            [sys.executable, "-B", str(REPO_ROOT / "tools" / "check_repo_hygiene.py")],
            cwd=REPO_ROOT, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("clean:", result.stdout)

    def test_model_policy_remains_explicit_and_never_deploys_or_mutates_online(self):
        readme = (REPO_ROOT / "models/README.md").read_text("utf-8").lower()
        architecture = (REPO_ROOT / "docs/PS26168_ML_Architecture_Decision.md").read_text("utf-8").lower()
        self.assertIn("no model has been trained or approved", readme)
        self.assertIn("no online learning", architecture)
        self.assertIn("model artifact hash/version traceability", architecture)
        self.assertFalse(any((REPO_ROOT / "mobile/app/src/main/assets").rglob("*.tflite")))
        self.assertFalse(any((REPO_ROOT / "mobile/app/src/main/assets").rglob("*.onnx")))

    def test_runtime_source_does_not_add_logging_calls(self):
        app = REPO_ROOT / "mobile/app/src/main/java/com/intelligentdeadreckoning/app"
        forbidden = re.compile(r"\b(?:android\.util\.)?Log\.(?:v|d|i|w|e)\s*\(|\bprintStackTrace\s*\(|\bTimber\.")
        for path in app.rglob("*.kt"):
            with self.subTest(path=path.relative_to(REPO_ROOT)):
                self.assertIsNone(forbidden.search(path.read_text("utf-8")), str(path))

    def test_installed_manifest_source_disallows_network_and_background_location(self):
        import xml.etree.ElementTree as ET
        manifest = ET.parse(REPO_ROOT / "mobile/app/src/main/AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        tools = "{http://schemas.android.com/tools}"
        declared = [(node.get(android + "name"), node.get(tools + "node"))
                    for node in manifest.findall("uses-permission")]
        granted = {name for name, action in declared if action != "remove"}
        self.assertEqual(granted, {
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        })
        self.assertTrue(all(action == "remove" for name, action in declared
                            if name in {"android.permission.INTERNET",
                                        "android.permission.ACCESS_NETWORK_STATE",
                                        "android.permission.ACCESS_WIFI_STATE"}))
        self.assertFalse(any("BACKGROUND_LOCATION" in (name or "") or "FOREGROUND_SERVICE" in (name or "")
                             for name in granted))
        application = manifest.find("application")
        self.assertEqual(application.get(android + "allowBackup"), "false")
        self.assertEqual(application.get(android + "usesCleartextTraffic"), "false")
        rules = ET.parse(REPO_ROOT / "mobile/app/src/main/res/xml/data_extraction_rules.xml").getroot()
        self.assertEqual({child.tag for child in rules}, {"cloud-backup", "device-transfer"})
        for section in rules:
            self.assertTrue(section.findall("exclude"))
            self.assertFalse(section.findall("include"))

    def test_seal_dry_run_writes_nothing(self):
        directory = self.root / "exp-alpha"
        builder = ExperimentBuilder(directory)
        builder.add_session("rec-primary", standard_records(), digest="0" * 64)
        builder.write_manifest()
        before = {path.name for path in directory.iterdir()}
        result = self.run_tool("seal_experiment.py", str(directory), "--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({path.name for path in directory.iterdir()}, before)
        self.assertFalse((directory / INTEGRITY_NAME).exists())
        self.assertIn("0" * 64, (directory / MANIFEST_NAME).read_text("utf-8"))


if __name__ == "__main__":
    unittest.main()
