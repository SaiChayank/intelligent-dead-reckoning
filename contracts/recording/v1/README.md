# Local recording contract 1.0.0

This directory defines the versioned **session metadata contract** for optional local
recording. It does not implement the recorder, storage UI, export, or replay engine.
The verified Prompt 2 acquisition pipeline remains unchanged.

## Canonical layout

A session is a directory with exactly these logical artifacts:

```text
session/
  metadata.json
  measurements.jsonl
```

`metadata.json` uses recording-contract version `1.0.0`.
`measurements.jsonl` does **not** introduce a second measurement schema: every line
is exactly one existing `contracts/v1` `Record` envelope with measurement contract
version `1.0.0`. Recording code must use the existing strict `Codec` to encode each
line, then append LF. Arrival order is preserved. No sorting, resampling, unit
conversion, interpolation, or timestamp replacement is permitted.

The metadata codec additionally verifies that every measurement record has the same
`acquisition_session_id`, original `source`, and `measurement_contract_version` as
the session metadata.

## Versions and source identity

Metadata always contains:

- `recording_contract_version`: `1.0.0`.
- `measurement_contract_version`: `1.0.0`.
- application version/build fields where known, plus nullable `source_version` for a
  source-control/build revision when the build provides one.

Only original `real` or `simulation` sources may be recorded. Stored rows keep that
original source. Replay later maps the session source without changing stored rows:

| recorded source | replay source |
|---|---|
| `real` | `replay_real` |
| `simulation` | `replay_simulation` |

A replay must never present itself as live hardware or live simulation.

## Clock identity and timestamps

Measurement `t_ns` and `received_ns` remain the exact signed-Int64 decimal strings
defined by `contracts/v1`: Android elapsed-realtime nanoseconds since boot.
Metadata uses decimal strings for monotonic nanoseconds, UTC milliseconds, record
counts, and channel counts so consumers never pass them through binary64.

`clock` contains:

- `domain`: `android_elapsed_realtime_ns`.
- nullable `boot_id`: an OS boot identity when safely available.
- `session_clock_id`: a nonempty identity owned by the acquisition/recording session.
- `origin_ns`: acquisition-session origin.
- `started_ns` and nullable `ended_ns`.

Consumers may compare monotonic values freely **within one acquisition session**.
Across recordings they must not assume comparability unless clock-domain and a
non-null trusted boot identity match. When boot identity is unavailable,
`session_clock_id` deliberately prevents consumers from inferring cross-session or
cross-boot equivalence. UTC fields are provenance metadata and never replace the
monotonic measurement clock.

## Metadata fields

All defined JSON keys are required. Fields that are unavailable are explicit JSON
`null`; omission and invented sentinel values are invalid.

Top-level metadata contains:

- recording ID and original acquisition session ID;
- original source;
- `start_state`, nullable `end_state`, `completion_state`, and `recovery_state`;
- nullable creation/start/end UTC milliseconds;
- clock identity;
- device manufacturer/model/OS/API where available;
- app package/version/version code/source revision where available;
- sensor descriptors: sensor kind, real name/vendor, availability, requested Hz,
  nullable measured Hz;
- location/GNSS source configuration and location-permission state;
- project calibration application state;
- nullable finalized record count and nullable per-channel counts.

Project calibration state is intentionally separate from Android/vendor sensor
accuracy. `not_applied` means raw Prompt 2 measurements were not transformed by a
project calibration. `unavailable` means the metadata producer cannot determine the
project-calibration state. `applied` requires a non-null calibration ID.

This contract does not claim that Android vendor/firmware calibration is absent.

## State invariants

### Open session

While a session is actively recording:

- `start_state = recording`;
- `completion_state = open`;
- `end_state = null`;
- end timestamps, counts, and channel counts are null;
- `recovery_state = none`.

### Cleanly completed session

A clean explicit stop finalizes:

- `completion_state = completed`;
- `end_state = stopped`;
- monotonic end time and `record_count`;
- optional `channel_counts` when computed;
- `recovery_state = none`.

A completed zero-record session is valid and has `record_count = "0"`.
If channel counts are present, channels are unique and their sum must equal the
record count.

### Interrupted/incomplete session

If process/lifecycle interruption prevents clean finalization, an `open` metadata
file discovered on a later load is an interrupted-session candidate. Recovery code
implemented in the recorder stage must convert its status explicitly rather than
pretending it completed normally.

An incomplete finalized metadata record uses:

- `completion_state = incomplete`;
- `end_state = interrupted`;
- `recovery_state = required`, `recovered`, or `unrecoverable`.

A `recovered` session has a finalized valid-prefix `record_count`. Recovery does not
change the session into `completed`.

### Failed session

A writer/finalization failure may use `completion_state = failed` and
`end_state = failed`. The recorder stage must preserve the error separately in its
runtime diagnostics; this metadata schema does not include private filesystem paths
or raw exception text.

## JSONL corruption policy

The existing measurement codec remains strict:

- a complete final row without LF is valid;
- a malformed/truncated measurement row is rejected;
- blank rows, corrupt UTF-8, duplicate keys, unknown fields/types and unsupported
  measurement versions are rejected.

The later recorder recovery layer may discard **only one incomplete trailing byte
fragment** when it can prove all preceding JSONL rows are complete and valid. The
session must then be marked `incomplete` + `recovered`; the trim is not silent.
A corrupt complete interior line is never skipped and makes recovery unrecoverable.

Unsupported future `recording_contract_version` or `measurement_contract_version`
is rejected before replay/analysis. There is no best-effort version guessing.

## Bounds and strictness

Metadata is UTF-8 JSON with unique object keys and is bounded to 262,144 bytes.
Strings are nonblank and bounded. Sampling rates are finite positive numbers when
present. Sensor entries and channel-count entries must be unique. The v1
measurement codec retains its own independent limits.

`foreground_only` is required to be `true` in recording contract 1.0.0. This stage
does not authorize background recording or a foreground service.

## Implemented APIs in this stage

Python:

```text
contracts.recording.v1.models
contracts.recording.v1.codec.encode_metadata/decode_metadata
contracts.recording.v1.codec.encode_record/decode_record
contracts.recording.v1.models.replay_source
```

Kotlin:

```text
com.intelligentdeadreckoning.contracts.recording.v1.RecordingMetadata
com.intelligentdeadreckoning.contracts.recording.v1.RecordingCodec
com.intelligentdeadreckoning.contracts.recording.v1.replaySource
```

The record helpers delegate measurement serialization to `contracts/v1`; they only
add recording-membership checks. They do not read/write files.

## Explicit non-goals for Prompt 3A

Not implemented here: disk recording, writer queues, Android recording controls,
Storage Access Framework export, replay pacing/UI, Python session-directory reader,
INS, EKF/UKF, AI, map matching, project calibration, cloud upload, or background
recording.

Fresh phone recordings created in later stages do not validate or redefine the
historical IO-VNBD exporter.
