# Experiment contract 1.0.0

This directory defines the versioned **experiment manifest** for collecting and
evaluating real driving sessions. It implements no navigation and trains nothing.
It is the layer above the recording contract, and it exists because a recording
session on its own cannot answer the questions an evaluation has to answer: where
was the phone and how was that known, which intervals were calibration or a
specific manoeuvre, when was GNSS actually good, degraded, lost or recovered, and
what independent reference was flown alongside.

## What this layer is, and is not

Three layers now exist, and they compose rather than compete:

| Layer | Defines | Never defines |
|---|---|---|
| `contracts/v1` | measurement payloads and `Record` envelopes | sessions, drives, annotations |
| `contracts/recording/v1` | one session's metadata (`metadata.json`) | anything across sessions |
| `contracts/experiment/v1` (this) | a drive: mount, clock, annotations, masks, reference, integrity | measurement payloads, session metadata |

An experiment never redefines a measurement row or a session's metadata. It
references recording sessions by ID, opens them through
`contracts.recording.v1.session` (strictly read-only), and adds the context that
makes the drive reproducible. There is no second measurement schema, and no
scratch file that contains measurements in another shape.

## Canonical layout

An experiment is a directory whose name **is** its `experiment_id`:

```text
<experiment_id>/
  experiment.json               manifest, contract 1.0.0 (required)
  annotations.jsonl             ordered interval stream (required; may be empty)
  integrity.sha256              SHA-256 of every artifact below, and of the manifest
  masks/<mask_id>.json          software GNSS outage masks (declared in `masks`)
  reference/reference.jsonl     independent reference (only when `reference` is set)
  sessions/<session_id>/        recording sessions, exactly as the app produced them
    metadata.json
    measurements.jsonl
```

Locations are fixed, not configured. A session's directory name is its
`session_id`, which is the `recording_id` the app already assigned, so an
experiment is self-contained: one directory holds everything needed to reproduce
the evaluation, and nothing points outside it. An experiment whose recording lives
somewhere else is not an experiment yet — copy the session directory in and seal it.

## `experiment.json`

All defined keys are required. Unavailable values are explicit JSON `null`; a
missing key is invalid and an invented sentinel is invalid. Monotonic nanoseconds
and UTC milliseconds are decimal strings so they never pass through binary64.

| Key | Meaning |
|---|---|
| `experiment_contract_version` | `1.0.0` |
| `experiment_id` | Safe directory name; must equal the directory it sits in |
| `created_utc_ms` | When the manifest was authored |
| `description` | What the drive was |
| `operator`, `vehicle` | Nullable provenance |
| `mount` | Phone mounting and the orientation it implies |
| `clock` | The one clock every session must share |
| `sessions` | Declared recording sessions, each pinned by digest |
| `reference` | Nullable independent RTK/VBOX-class reference descriptor |
| `masks` | IDs of the outage masks that exist under `masks/` |

### Mounting orientation

`mount` carries `position`, `orientation_method`, `roll_deg`, `pitch_deg`,
`yaw_deg`, `uncertainty_deg` and a nullable `note`, in the **vehicle frame**.

- `position` has no `unknown` member. A mounting that was not chosen deliberately
  cannot be declared, because a guessed mounting is what silently corrupts every
  later attitude conclusion.
- `orientation_method` is `measured`, `surveyed` or `assumed`.
  `assumed` **requires** a note, so a guess can never be mistaken for a
  measurement months later.
- Angles use `(-180, 180]` degrees, so one orientation has one spelling.

This contract records the mounting. It does not claim the phone's own axes are
aligned with the vehicle's; that transformation is navigation-core work.

### Clock identity

`clock` carries the domain (`android_elapsed_realtime_ns`), a **required**
`boot_id`, and a nullable `reference_alignment`.

Unlike `contracts/recording/v1`, where `boot_id` may be null, an experiment
requires it. Two sessions' monotonic timestamps are comparable only when they
share a clock domain and a trusted boot identity — elapsed realtime is
boot-relative. Without a boot identity an experiment could assemble a timeline out
of unrelated clocks while looking perfectly well-formed. So the requirement is
enforced at load: every referenced session must be on the manifest's boot, or the
experiment is refused with `CLOCK_MISMATCH`.

`reference_alignment` says how the reference stream was placed in the experiment
clock. If a reference declares `timebase = experiment_clock`, an alignment is
required — claiming a stream is already in this clock is a claim that needs a
method, an offset and an uncertainty.

### Sessions

Each entry is `session_id`, `role` (`primary`/`reference`/`supplementary`) and
`measurements_sha256`. **Exactly one session is `primary`**: one experiment has one
evaluation target, so a report has one timeline.

`measurements_sha256` commits the experiment to the exact bytes it was evaluated
against. It is not a substitute for `integrity.sha256`; it is the author's
declaration, checked against it. `tools/seal_experiment.py` computes it, so
collectors never hand-write a digest.

### Reference

Optional, and its absence is meaningful: a report states that no independent
reference was flown rather than leaving accuracy implied. The descriptor records
`equipment_class` (`rtk`/`vbox`/`survey_grade`/`other`), `equipment`,
`horizontal_accuracy_m`, `rate_hz`, `timebase` and `source`. `source` must be
`real` — reference equipment observes reality; it is never a replay.

Reference rows are ordinary `contracts/v1` `Record` envelopes carrying `gnss`
measurements. There is no reference-specific record schema. A row of any other
type is refused as `INVALID_REFERENCE_RECORD`.

## `annotations.jsonl`

One JSON object per line, one line per interval, all keys present:

```json
{"kind":"gnss_state","session_id":"rec-primary","start_ns":"...","end_ns":"...",
 "note":null,"calibration_id":null,"motion":null,"scenario":null,"gnss_state":"lost"}
```

Exactly one payload field is populated, selected by `kind`:

| `kind` | Payload | Values |
|---|---|---|
| `calibration` | `calibration_id` | A safe ID naming the calibration procedure |
| `motion` | `motion` | `stationary`, `straight`, `turning_left`, `turning_right`, `roundabout`, `lane_change`, `accelerating`, `braking`, `reversing` |
| `scenario` | `scenario` | `open_sky`, `suburban`, `urban_canyon`, `tree_cover`, `tunnel`, `underpass`, `parking_garage`, `bridge`, `highway`, `rural` |
| `gnss_state` | `gnss_state` | `good`, `degraded`, `lost`, `recovered` |

Invariants:

- `start_ns < end_ns`. A zero-length or reversed interval is a real recording
  mistake, so it is rejected (`INVALID_INTERVAL`) rather than ignored.
- Within one `(session_id, kind)` group, intervals are in increasing start order
  (`OUT_OF_ORDER`), do not overlap (`OVERLAPPING_INTERVALS`), and two touching
  intervals carrying the same value are rejected (`DUPLICATE_INTERVAL`) because
  they are one interval written twice.
- Touching intervals with **different** values are legal: that is exactly how a
  GNSS state transition is expressed.
- Groups are independent, so motion and scenario may run in parallel and a
  collector may lay the stream out kind by kind.
- Every interval must name a declared session (`UNKNOWN_SESSION`) and lie inside
  that session's recorded span (`INTERVAL_OUT_OF_SESSION`). Bounds are checked
  against the session's real `origin_ns`/`ended_ns`, not against a remembered number.

`gnss_state` describes what was **observed**, not what an evaluator hopes for.
`recovered` is the reacquisition interval: a fix is back but not yet settled into
`good`. Deliberately **not** enforced: a physical plausibility model of which state
may follow which. That is a judgement about a drive, and a validator that guessed
it would reject real drives; coverage and ordering are what can be checked
mechanically.

## Outage masks

A mask is a designed GNSS condition for repeatable evaluation, not a description of
reality. It is a separate artifact precisely because the same drive must be
evaluated under several conditions:

```json
{"mask_id":"tunnel_observed","session_id":"rec-primary","description":"...",
 "provenance":"observed","policy":"suppress_gnss",
 "intervals":[{"start_ns":"...","end_ns":"...","note":"underpass"}],
 "note":"derived from the rec-primary gnss_state timeline"}
```

- `provenance` is `observed` (derived from this experiment's `gnss_state`
  intervals, and then a note is required saying so) or `synthetic` (designed).
- `policy` has exactly one member, `suppress_gnss`: GNSS measurement records
  inside the intervals are withheld from the engine input, as if the receiver had
  never produced them. **Quality-degradation masking is deliberately absent**:
  synthesizing a degraded fix would mean fabricating measurements, which this
  project does not do. Adding a policy requires a new contract version.
- Interval rules mirror the annotation stream: sorted, non-overlapping,
  `start_ns < end_ns`, inside the target session's span.
- A mask names the session it was authored against. Applying it to a different
  session is refused, because the intervals would be silently reinterpreted.

Masking is applied at **read time only** (`contracts.experiment.v1.masking`).
It is deterministic, drops rather than alters records, and touches only `gnss`
input measurements: IMU, `navigation`, `gnss_quality`, `confidence`, `diagnostic`
and `calibration` records pass through untouched. Interval containment is
half-open, `[start_ns, end_ns)`, decided on the measurement time `event.t_ns`.

Every mask application reports how many GNSS records it withheld, so a report can
state the condition it evaluated instead of implying it.

## `integrity.sha256`

One artifact per line, two spaces between digest and path, LF line endings, sorted
by path:

```text
<64-lowercase-hex>  <POSIX-relative-path>
```

Every regular file in the experiment directory is covered **except**
`integrity.sha256` itself, and `experiment.json` **is** covered. That is not
circular: the manifest is written first and hashed after, so the manifest cannot be
edited afterwards without the digest disagreeing. A file that is present but
unlisted fails as hard as a listed file that is missing (`UNLISTED_FILE`), because
an artifact nobody committed to is an artifact nobody can reproduce.

The hash covers the recordings too, so a tampered or truncated
`measurements.jsonl` is caught (`HASH_MISMATCH`) instead of being read as data.

Seal, then verify:

```text
python tools/seal_experiment.py <experiment-directory>       # compute digests, write integrity.sha256
python tools/validate_experiment.py --experiment <dir>       # read-only acceptance
python tools/validate_experiment.py --corpus <dir>           # + experiment-ID uniqueness
```

The sealer writes only inside the experiment directory and never writes to a
recording; it opens `measurements.jsonl` read-only to hash it. Afterwards it
re-opens the result **through the reader**, so what it produced is verified by the
code that will consume it, not by the writer's own reasoning.

An all-zero digest (`"0000…"`) is the documented "not yet computed" placeholder for
`measurements_sha256` before sealing. No real file hashes to it, so an unsealed
manifest fails validation instead of passing quietly.

## Python API (read-only)

```text
contracts.experiment.v1.codec      decode_manifest / encode_manifest
                                   decode_annotations / encode_annotations
                                   decode_mask / encode_mask
contracts.experiment.v1.integrity  build_integrity / encode_integrity / decode_integrity
                                   sha256_file / verify_integrity
contracts.experiment.v1.masking    MaskedReplay / mask_records / mask_duration_ns
contracts.experiment.v1.experiment inspect_experiment / open_experiment / load_experiment
                                   Experiment (sessions, masks, annotations, replay_input)
                                   iter_experiments / find_duplicate_ids / check_corpus
                                   corpus_label
```

`inspect_experiment` is bounded (manifest only) and never raises.
`open_experiment` raises on the first violation. `Experiment.replay_input(session,
mask_id)` is the only supported way to evaluate a degraded-GNSS condition; it
returns an iterable whose `suppressed_gnss`/`total_gnss` counters describe what the
mask withheld.

## Error codes

Codes are stable identifiers; callers switch on them. Corrections never reuse a
code for a new meaning inside 1.0.0.

| Code | Raised when |
|---|---|
| `INVALID_VERSION` | Contract version is not `1.0.0` |
| `INVALID_KEYS` | A defined key is missing, or an unknown key is present |
| `INVALID_TYPE`, `OUT_OF_RANGE`, `INVALID_UNICODE` | A value has the wrong shape or range |
| `INVALID_ENUM`, `INVALID_SHAPE` | An unknown member, or a payload that does not match its kind |
| `INVALID_ID`, `INVALID_PATH`, `UNSAFE_PATH` | A name or path that is not safe to resolve |
| `MALFORMED_JSON`, `DUPLICATE_KEY` | The artifact is not strictly decodable |
| `RESOURCE_LIMIT`, `INVALID_UTF8` | Size or encoding beyond the contract |
| `INVARIANT` | A cross-field rule is violated (one primary session, an `assumed` mount without a note, an alignment that is claimed but absent, an empty mask) |
| `INVALID_INTERVAL`, `OUT_OF_ORDER`, `OVERLAPPING_INTERVALS`, `DUPLICATE_INTERVAL` | Interval rules |
| `DUPLICATE_SESSION`, `DUPLICATE_MASK` | A repeated ID inside one manifest |
| `MISSING_EXPERIMENT`, `MISSING_ARTIFACT`, `IO_ERROR` | A required artifact is absent or unreadable |
| `ID_MISMATCH` | `experiment_id` does not match the directory name |
| `MALFORMED_INTEGRITY`, `DUPLICATE_ENTRY` | The integrity manifest is not strictly decodable |
| `HASH_MISMATCH`, `UNLISTED_FILE` | An artifact does not match the manifest, or is not covered by it |
| `UNKNOWN_SESSION`, `UNKNOWN_MASK`, `MASK_ID_MISMATCH` | An interval or mask references something undeclared, or a mask is applied elsewhere |
| `INTERVAL_OUT_OF_SESSION` | An interval falls outside its session's recorded span |
| `MISSING_REFERENCE` | A reference is declared but its file is absent |
| `INVALID_REFERENCE_RECORD` | A reference row is not a canonical `gnss` measurement of the declared source |
| `MISSING_SESSION`, `SESSION_NOT_REPLAYABLE`, `SESSION_HASH_MISMATCH` | A declared recording is absent, not replayable, or not the bytes that were pinned |
| `CLOCK_MISMATCH` | A session is on another clock domain, another boot, or has no boot identity |
| `DUPLICATE_EXPERIMENT_ID` | Two directories in one corpus claim the same experiment ID |

Codes from the codec (`ExperimentContractError`) and the recording reader
(`SessionError`) surface through the loader unchanged, so one vocabulary spans the
three layers.

## Committed fixtures

- `golden_experiment.json`, `golden_annotations.jsonl`, `golden_mask.json` — a
  canonical, self-consistent schema fixture. They are **not** a runnable
  experiment: they carry no recordings, which are never committed. Tests build
  complete experiments synthetically, through the real recording codec, so a
  fixture session is a session the app could have produced.
- `invalid_manifests.json` — a list of `{name, manifest, code}`, every entry a
  mutation of the golden manifest with the code it must produce. Each case is
  verified when the file is regenerated and asserted in the test suite.

## Explicit non-goals for experiment contract 1.0.0

Not implemented here and not implied by any field: an experiment registry or
database, a campaign/trip hierarchy above one experiment, quality-degradation
masking, reference trajectory fusion, INS/EKF/AI, training, benchmark or accuracy
claims of any kind, cloud or network storage, and any writer that modifies a
recording. The manifest records what was collected; it does not evaluate
navigation, and it must never be read as evidence that a drive is suitable for
training. Suitability is a separate, later judgement.
