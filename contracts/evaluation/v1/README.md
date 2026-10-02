# Evaluation contract 1.0.0

This directory defines the versioned **evaluation report**: the document an evaluation run
produces. It records what session was evaluated, against which reference, which arms ran, and which
metrics came out. It implements no navigation, produces no metrics of its own, and is not a
measurement stream — the numbers in it were measured by the run that wrote it.

It exists because a comparison table is where fabrication is easiest and most tempting. A missing
number in a table tends to get filled in; this document makes absence explicit, typed and checked,
and it makes the two rules that matter structural rather than stylistic:

- **An accuracy figure may only exist when the reference is declared independent** *and* the arm did
  not consume it. An arm cannot be scored against its own input.
- **`not_run` and `not_implemented` are answers.** They must carry a reason and must carry no
  metrics, so a table cannot show a number for something that never ran.

Layers: `contracts/v1` carries measurement payloads, `contracts/recording/v1` one session's
metadata, `contracts/experiment/v1` a drive's context and reference, and this document one
evaluation's *results*. It references a session by ID and never redefines a measurement or a
session field.

## Document

All defined keys are required. An unavailable value is an explicit JSON `null`; a missing key is
invalid and so is an invented sentinel. 64-bit counts are JSON numbers, timestamps are decimal
strings, and every metric is a finite, non-negative number.

```json
{
  "evaluation_contract_version": "1.0.0",
  "evaluation_id": "scripted-outage-70s",
  "created_utc_ms": "1790812800000",
  "session": { "session_id": "scripted-drive-70s", "source": "simulation",
               "contract_version": "1.1.0", "duration_s": 70.0, "records": 14070,
               "description": "…" },
  "platform": { "host": true, "device_model": null, "android_release": null, "note": "…" },
  "reference": { "kind": "scripted_truth", "independent": true, "description": "…" },
  "segments": [ { "kind": "denied", "start_ns": "30000000000", "end_ns": "50000000000" } ],
  "arms": [ { "arm_id": "classical_fusion", "label": "…",
              "implementation": { "name": "…", "version": "…" },
              "status": "evaluated", "reason": null,
              "accuracy": { "…": "see below" }, "timing": { "…": "see below" } } ]
}
```

### Session and platform

| Key | Meaning |
|---|---|
| `session.session_id`, `source`, `contract_version` | Which session was evaluated, as its own recording declares it |
| `session.duration_s`, `records` | Its extent, so a metric's denominator is visible |
| `platform.host` | `true` for a host replay, `false` for an on-device run |
| `platform.device_model`, `android_release` | Required when `host` is false; must be `null` when it is true |
| `platform.note` | What the platform could and could not measure. This is where "no device memory, no sensor-to-display path" belongs |

### Reference

| `reference.kind` | Meaning |
|---|---|
| `none` | No reference: accuracy metrics are forbidden outright |
| `scripted_truth` | The trajectory synthetic inputs were generated from — independent of every arm, and not field data |
| `held_out_gnss` | Recorded GNSS withheld from the arm for the measured interval, so it never consumed it |
| `surveyed_track`, `rtk`, `vbox` | Field references of increasing authority |

`reference.independent` must be `true` for any accuracy metric to exist, and `false`/`none` cannot
be paired with one. A *consumed* reference is refused per arm by `reference_consumed`.

### Segments

`gnss_good`, `degraded`, `denied`, `recovery`, in measurement time relative to the session often.
Ordered and non-overlapping; each `end_ns` is greater than its `start_ns`. They describe the drive's
conditions, not an arm's behaviour — an arm that ignores GNSS entirely still has a `denied` segment
describing when it was unavailable.

### Arms

The five arm IDs are closed: `classical_ins`, `classical_fusion`, `fusion_constraints`, `fusion_ai`,
`fusion_map_match`. At most one entry per ID. `status` is `evaluated`, `not_run` or
`not_implemented`:

- `evaluated` — at least one of `accuracy` / `timing` is present;
- otherwise — `reason` is present and both groups are `null`.

## Accuracy metrics (per arm, against the declared reference)

Every field is `null` when it was not measured. The definitions are part of the contract, because a
number whose definition is private is not evidence.

| Field | Definition |
|---|---|
| `reference_consumed` | `true` if the arm consumed the reference during the measured interval. Always `false` in a valid document |
| `outage_duration_s` | Length of the declared `denied` segment |
| `outage_distance_m` | Reference distance travelled during that segment |
| `final_position_error_m` | Horizontal error at the last published sample of the run |
| `drift_percent` | `100 ×` horizontal error at the last sample **inside** the denied segment, over `outage_distance_m`. The run-final error is a recovery figure, not the drift |
| `position_rmse_m` | Root-mean-square horizontal error over every published sample from alignment onward |
| `speed_mae_m_s`, `speed_rmse_m_s` | Mean-absolute and root-mean-square speed error over samples that published a speed |
| `heading_error_deg` | Mean absolute heading error (wrapped to ±180°) over samples that published a heading |
| `recovery_convergence_s` | Time from the end of the denied segment until the error first falls to `recovery_threshold_m`; `null` if it never does |
| `recovery_threshold_m` | The bound that convergence was measured against. Required whenever `recovery_convergence_s` is present |
| `samples` | Published samples the figures were computed over |

An accuracy group with every field `null` is invalid, and so is one whose `reference_consumed` is
`true`.

## Timing metrics (per arm)

| Field | Definition |
|---|---|
| `output_hz` | Published navigation states per second of session, measured over the run |
| `inference_latency_p50_ms`, `p95_ms` | Per-inference latency of a model, when the arm runs one. `null` when no model exists |
| `end_to_end_p50_ms`, `p95_ms` | Sensor-to-publication latency, measured on the platform that produced the run. A host replay without a sensor path leaves these `null` |
| `queue_high_water` | Deepest the ingress queue ever got: admitted records not yet handed to the engine |
| `drops` | Records the runtime dropped (ingress and output) |
| `errors` | Records the runtime refused to route, plus the refusals and rejections the engine counted |
| `memory_peak_mb` | Peak process memory, when the platform can measure it |
| `samples` | Publications the timing figures were computed over |

`p95` is never below `p50`, `output_hz` is positive, and a group with every field `null` is invalid.

## Failure codes

`INVALID_JSON`, `INVALID_KEYS`, `INVALID_TYPE`, `INVALID_VERSION`, `INVALID_ENUM`, `OUT_OF_RANGE`,
`NONFINITE`, `DUPLICATE_ARM`, `INVARIANT` — the last is the honesty rules above. The shared negative
corpus is `invalid_reports.json`; `golden_report.json` is the checked-in document the harness
regenerates (`mobile/EVALUATION.md` states how, and the Kotlin suite requires the two to match).

A non-finite numeric token (`NaN`, `Infinity`, `-Infinity`) is `NONFINITE` in *both* codecs whether it
arrives unquoted — which Python's `json` turns into a float and Gson hands over as a string — or
quoted, which both parsers see as a string. JSON has no such number, so the two languages meet the
token at different parse layers; the code that refuses it must not depend on which layer that is.
No other string is ever accepted where a number is required.

## What this contract does not do

It does not compute a metric, decide which arms exist, or let a document claim a reference on
another document's behalf: a report is produced by the run that measured it, and the app renders
what is there. An empty cell on the surface is an absent measurement, never a zero.
