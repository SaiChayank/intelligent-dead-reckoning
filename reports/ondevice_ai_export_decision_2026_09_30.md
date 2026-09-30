# On-device AI export and ModelRuntime — **NOT RUN: precondition failure**

Date: 2026-09-30. Stage requested: export the accepted AI model to TFLite (and
ONNX in parallel), add a `ModelRuntime` abstraction (loading, manifest/hash
verification, tensor preparation, inference, output validation, latency), create
golden training-framework-vs-runtime fixtures within explicit tolerance, implement
model failure behaviors, benchmark p50/p95 on the OnePlus, and stop after on-device
AI integration is verified.

## Verdict

**No export or integration was performed, because there is no accepted model to
export.** Stage 9 ended with *no model selected — training was not run*
([ml_training_decision_2026_09_30.md](ml_training_decision_2026_09_30.md)), because
the approved dataset does not exist ([ml_dataset_readiness_2026_09_30.md](ml_dataset_readiness_2026_09_30.md)).
This stage's first line — "the accepted AI model" — therefore names an artifact that
does not exist, and its stop criterion — "on-device AI integration is verified" —
cannot be met without one.

Fresh verification this stage, all read-only:

| Check | Result |
|---|---|
| Weight files anywhere on disk (incl. gitignored, excluding `.venv`/`.git`) | **none** (only `test-result.pb` protobufs under `mobile/app/build/` — test reports, not models) |
| TFLite / ONNX / ModelRuntime tooling in `training/`, `tools/`, `mobile/` | **none** |
| `models/` | contains only `README.md`: "No model has been trained or approved... Do not add weights here before that gate opens" |
| Stage-9 record | present: "No model trained. No weights written." |
| git HEAD | `e122ccc` — unchanged |

## Requested item → status

| Requested | Status | Blocked by |
|---|---|---|
| TFLite export | **not run** | no source model to convert |
| ONNX export | **not run** | same |
| `ModelRuntime` abstraction | **not built** | see below — building it now would guess the contract |
| Golden inference fixtures (framework vs runtime, explicit tolerance) | **not created** | there is no training-framework output to compare against |
| Failure behaviors (invalid hash / missing model / invalid tensor / inference exception) | **not implemented** | only "missing model" could ever trigger; the other three cannot be exercised or tested without a real artifact, so the behavior would be unverified by construction |
| p50/p95 latency benchmark on OnePlus CPH2585 | **not run** | nothing to time |
| On-device AI integration verification | **not possible** | all of the above |

## Why `ModelRuntime` was not built speculatively

The abstraction's contract is dictated by the model that stage 9 will eventually
select: tensor shapes and window length define tensor preparation, dtype and
quantization define output validation, the training framework defines the golden
values and therefore the tolerance, and manifest/hash format defines verification.
Designing those against a hypothetical TCN that does not exist would be guessing at
exactly the interfaces this project's rules forbid inventing, and any real export
would then force a rewrite. The failure paths deserve the same standard as the
success path: a `ModelRuntime` whose only exercised behavior is "missing model"
would report green while having verified nothing about inference.

Scaffolding is deliberately available as an explicit alternative (see follow-ups)
if it is wanted for interface review — it just cannot be *verified*, and this stage's
stop criterion is verification.

## Classical navigation path

**Remains fully available and untouched.** No AI code exists anywhere in the tree,
so nothing could have degraded the stage-7 classical fusion path, the acquisition
path, or the navigation runtime. When a model does land, `ModelRuntime` must sit
beside — not inside — the navigation pipeline, and the failure behaviors specified
here are exactly the ones that keep the classical path authoritative whenever the
model is absent or unhealthy.

## What unblocks this stage

The chain from stages 8 and 9, unchanged:

1. IO-VNBD frame/export contract recovered **or** controlled calibration run.
2. Passing device-to-vehicle mounting gates (or ground-truthed Android drives).
3. Exact IMU-to-label alignment.
4. `training_admission.json` approves ≥ 1 sequence.
5. Stage 8 re-run → manifests; stage 9 runs → four-model comparison → held-out
   selection decision → an *accepted* model with a recorded experiment ID and hash.
6. Then this stage runs verbatim: export → `ModelRuntime` → golden fixtures within
   stated tolerance → failure-path tests → on-device p50/p95 benchmark.

## Confirmations

- **No model exported, no runtime code written, no app code modified.**
- **No weights on disk; `models/` untouched; raw data untouched.**
- The only file added by this stage is this record.
