# PS26168 — Verification Strategy
**Scope:** Full test plan for the already-designed Intelligent Dead Reckoning system. No new product architecture or algorithms are introduced here. This document translates the project requirements into falsifiable software, scientific, mobile-device and field-acceptance tests.

**Current baseline note:** acquisition, recording/export/replay and offline MapLibre/synthetic presentation already have substantial host/device evidence. Real calibration, corrected INS, AI inference, GNSS+INS fusion, map matching, true GNSS→DR→recovery and the edge engine remain future verification gates.

---

## 1. UNIT

### Contracts / Codecs

- Python and Kotlin golden fixtures must serialize to equivalent canonical JSON.
- Exact Int64 nanosecond values above `2^53` must round-trip exactly.
- Unsupported contract versions must fail.
- Unknown/invalid enum values must fail.
- Missing optional values remain `null`, never fabricated zeros.
- `received_ns < t_ns` must fail.
- invalid/nonfinite vectors and quaternions must fail.
- replay source mapping must preserve original lineage.

### Coordinate / Math Utilities

Test with known analytic references:

- ENU ↔ WGS84 conversion around fixed origins,
- quaternion normalization,
- quaternion composition/inversion,
- device→vehicle→ENU transform order,
- proper-rotation determinant,
- heading wrap at 0/360°,
- gravity sign/convention,
- distance calculation.

A reflected matrix must never be accepted as a quaternion/proper rotation.

### Calibration

Synthetic tests:

- phone perfectly aligned with vehicle,
- 90° portrait/landscape rotations,
- pitch/roll tilt,
- yaw offset,
- noisy stationary window,
- insufficient straight-motion excitation,
- remount after calibration.

Assertions:

- output rotation within tolerance,
- invalid calibration on insufficient evidence,
- remount invalidates previous calibration ID.

### INS Mechanization

Analytic physics fixtures:

1. stationary,
2. constant velocity,
3. constant acceleration,
4. constant-rate turn,
5. known gyro bias,
6. known accelerometer bias,
7. irregular dt,
8. missing sample/time gap.

Assertions:

- expected trajectory/velocity/attitude within numerical tolerance,
- no drift in ideal zero-bias stationary fixture beyond numerical tolerance,
- time-gap policy triggers diagnostics rather than silently integrating across arbitrary gaps.

### Feature Engineering

- causal trailing windows,
- no future sample in feature at time `t`,
- ring-buffer size bounded,
- exact missingness masks,
- resampling/hold policy consistent,
- jerk with repeated/invalid timestamps handled safely,
- vibration statistics checked against hand-computed fixtures.

### AI Wrapper

With a frozen fixture model:

- deterministic/tolerance-bounded output,
- correct tensor shape/order,
- preprocessing equivalence,
- failed load/hash mismatch produces explicit fallback/diagnostic,
- no blocking work on Android sensor callbacks.

### EKF / Fusion

Once implemented:

- prediction-only interval,
- perfect GNSS update,
- biased GNSS rejected by innovation gate,
- covariance stays symmetric/positive semidefinite within numerical handling policy,
- outage covariance grows appropriately,
- recovery update contracts error/covariance,
- state never becomes NaN silently.

### Vehicle Constraints

- stationary zero-velocity update,
- NHC when driving straight,
- NHC gated/disabled under invalid calibration or incompatible maneuver,
- no constraint application to synthetic invalid states.

### Map Matching

- single obvious road,
- parallel roads,
- intersection,
- heading disagreement,
- out-of-coverage,
- low-confidence/no-match.

Raw fused position must remain available even when matched output exists.

---

## 2. ML / SCIENTIFIC VALIDATION

### Model Validation

For the first speed/vibration model report:

- speed MAE,
- speed RMSE,
- bias,
- per-speed-regime error,
- per-motion-condition error,
- held-out route/recording performance,
- downstream navigation effect.

For any learned fusion/residual model:

- same held-out outage segments as the classical baseline,
- final position error,
- drift percentage,
- RMSE,
- recovery behavior.

### Pass principle

AI is accepted only if it improves the specific held-out metric it was designed to improve **without violating runtime or stability requirements**.

No model is accepted merely because training loss decreases.

### Leakage Tests

Automated checks:

- no drive/recording group in more than one split,
- duplicate payload groups remain in one split,
- normalization/preprocessing fit only from training groups,
- no VBOX/reference field present in deployable feature tensors,
- no future GNSS after outage start,
- no centered future-crossing windows,
- frozen final test split not used in hyperparameter selection.

### Export Equivalence

For TFLite/ONNX:

- fixed golden inputs,
- native-training-framework outputs,
- exported-runtime outputs,
- predefined tolerance,
- same preprocessing/version metadata.

### Condition Generalization

Gate separately:

- familiar-condition held-out trips,
- held-out route,
- held-out mount configuration where data exists,
- longer outage,
- high-vibration segment.

A good aggregate average cannot hide catastrophic one-condition failures.

---

## 3. INTEGRATION

### Acquisition → Recorder

Already implemented path remains regression-tested:

- sensor callbacks never perform disk I/O,
- exact typed records reach recorder,
- source/session match,
- bounded queue,
- write failure isolated from acquisition.

### Acquisition / Replay → NavigationRuntime

Once added:

- canonical IMU/GNSS events accepted,
- invalid events rejected before state mutation,
- real and replay paths use same navigation core,
- no raw Android `SensorEvent` leaks into shared/core logic,
- lifecycle stop closes engine cleanly.

### NavigationEngine → Contract

- output validates through strict codec,
- calibration ID references active calibration,
- origin/position/velocity/attitude invariants hold,
- confidence/quality timestamps align correctly,
- model version/provenance available once AI is deployed.

### NavigationEngine → Map

Golden integration:

```text
known NavigationState
        ↓
NavigationPresentation
        ↓
known WGS84 point / trail / heading
```

Assertions:

- renderer does not change estimator state,
- stale state hides/expires,
- no extrapolation if engine stops,
- missing heading stays missing,
- calibrated radius only appears when supported,
- real source never receives synthetic comparison overlays.

### Replay → Same Engine

- record session,
- replay same session,
- run same engine/version,
- compare output deterministically or within explicit numeric tolerance.

### Recording / Export / Python

- exported ZIP contents exact,
- Python reader/validator can consume it,
- counts/IDs/timestamps/nulls/provider fields match.

---

## 4. SYSTEM

### Android Application

Smoke tests for:

- Dashboard,
- Diagnostics,
- Map,
- About,
- recording/session controls,
- export,
- replay,
- future Calibration screen.

State tests:

- loading,
- not started / empty,
- degraded,
- error,
- background/foreground,
- activity recreation.

### Permissions

Physically/test-automated states:

- not requested,
- denied,
- approximate,
- precise,
- revoked.

Assertions:

- IMU remains available where permission model allows,
- GNSS clears when permission disappears,
- no stale/cached fix is promoted incorrectly,
- no silent restart.

### Offline Map

- pack checksum,
- SQLite integrity,
- tile/glyph presence,
- actual device rendering,
- attribution visible,
- no Internet permission,
- camera controls,
- out-of-coverage state,
- corrupt-pack test on disposable installation.

### Recording

- empty recording,
- normal stop/finalize,
- background stop,
- source switch,
- write failure,
- bounded slow writer,
- interrupted trailing fragment recovery,
- corrupt interior rejection,
- no original overwrite.

### Replay

- `real → replay_real`,
- `simulation → replay_simulation`,
- exact timestamps,
- no live hardware ownership,
- malformed/corrupt session rejection,
- lifecycle stop.

---

## 5. SECURITY / PRIVACY

Execute the controls from the focused security/privacy review:

- manifest has only intended permissions,
- no hidden Internet/network permission,
- no background-location/service permission for current prototype,
- app-private recording path,
- backup disabled,
- explicit export only,
- local destination policy,
- traversal-safe session IDs,
- corrupt model hash rejected,
- corrupt dataset/reference hash rejected,
- map checksum failure detected,
- dependency audit,
- no hard-coded secrets,
- logs avoid leaking precise location in general diagnostics.

### Offline-boundary test

Run the complete available core flow with ordinary Internet connectivity unavailable:

- app starts,
- map loads,
- acquisition works,
- recording works,
- replay works,
- real navigation works once implemented.

No cloud fallback may be required.

---

## 6. ARCHITECTURAL COMPLIANCE

Each core design invariant receives a falsifiable test.

| Property | Test |
|---|---|
| **No OBD/CAN dependency** | Run mobile navigation/evaluation without any vehicle-computer interface connected; inspect runtime dependencies and input schema for no required OBD field |
| **On-device inference** | Disable network; verify model loads and navigation updates continue locally |
| **Map is downstream, not localization** | Replace renderer with a fake/no-op renderer and confirm NavigationEngine output is unchanged |
| **Synthetic demo is isolated** | Start synthetic map demo and verify no acquisition/recording/navigation-engine stream is modified; synthetic overlays never appear on real/replay sources |
| **Replay preserves physical time** | Accelerated replay must produce identical physics timing from stored `t_ns`; wall-clock speed cannot change dt |
| **No reference leakage** | Static schema/test checks ensure VBOX/RTK/reference fields never enter deployable runtime model inputs |
| **Raw and map-matched positions remain distinguishable** | Matcher output cannot overwrite canonical raw fused state without provenance |
| **Lifecycle stops foreground capture** | Home/background removes listeners and returning does not auto-restart |
| **No unbounded queues** | Long-duration load test confirms every queue stays bounded and overflow is explicit |
| **Failure isolation** | Model/map/recorder failure cannot silently corrupt unrelated acquisition evidence |

---

## 7. PERFORMANCE

### Mobile metrics

| Metric | Method |
|---|---|
| IMU input rate | measured from canonical event timestamps |
| GNSS update rate | measured separately from repeated display values |
| Navigation output rate | count canonical navigation states per physical second |
| Model inference latency | p50/p95/p99 on target phone |
| INS/fusion latency | stage timing |
| End-to-end navigation latency | input event → emitted NavigationState |
| Map display frame rate | presentation metric only, separate from engine output |
| Queue depth/high water | sampled under normal and stress runs |
| Drops/invalid/late | categorized counters |
| Memory | PSS/RSS over extended run |
| CPU | sustained field/replay benchmark |
| Battery / thermal | long-run before production claims |

### Official mobile target

**Position/navigation output around 10 Hz.**

Pass only if the real NavigationEngine sustains the target on the target phone without unbounded backlog, unacceptable drops or unstable memory.

Raw 100 Hz IMU input alone does not count.

### Edge metrics

- input/output rate,
- model inference time,
- navigation cycle time,
- CPU/memory,
- drops/backlog,
- accuracy.

### Official edge target

Around **200 Hz** with FOG-based IMU data.

A throughput-only fast replay of 10 Hz phone data is not sufficient evidence.

---

## 8. NAVIGATION / FIELD TEST SCENARIOS

| Scenario | Input / environment | Expected outcome | Critical evidence |
|---|---|---|---|
| Open-sky GNSS | normal road, good GNSS | fused tracking stable | position/speed/heading vs reference |
| Short software-masked outage | held-out ground-truthed drive | continuous DR | drift <10% if final system passes |
| Long software-masked outage | longer tunnel-equivalent mask | continuous DR with growing uncertainty | drift vs distance/duration |
| Physical tunnel / covered parking | natural GNSS loss | quality → stale/denied; DR continues | actual Android provider behavior + trajectory |
| Urban canyon / degraded fixes | multipath-prone route | bad fixes gated | innovation / accepted-rejected reasons |
| Stationary | parked vehicle | near-zero velocity, no false travel | drift and zero-velocity behavior |
| Stop-go traffic | repeated stop/start | no heading/speed instability | speed/position errors |
| Left/right turns | curved road | correct yaw/heading | heading/trajectory error |
| Rough road / bumps | safe natural vibration | no false major displacement | speed model robustness |
| Alternate mount | portrait/landscape/tilted | calibration recovers vehicle frame | calibration error/validity |
| Controlled remount while stopped | change phone mount | old calibration invalidated | diagnostic + recalibration |
| GNSS recovery | outage then open sky | gated smooth reconvergence | convergence time, correction magnitude |
| Parallel-road ambiguity | map graph fixture/field route | no unjustified snap | raw vs matched + confidence |
| Map out of coverage | leave installed region / fixture | numeric nav survives; matcher/map degrades | explicit coverage status |

---

## 9. FALSE-POSITIVE / WRONG-CORRECTION TESTS

Navigation has "false corrections" rather than cybersecurity false alerts.

Test that the system does **not**:

- declare GNSS denied after one harmless fix delay,
- accept a stale cached fix as fresh,
- declare a slowly creeping vehicle stationary,
- interpret pothole shock as sustained forward speed,
- treat a normal turn as phone remount,
- apply NHC in an incompatible state,
- snap to the wrong parallel road with high confidence,
- call raw EKF covariance "calibrated 95% accuracy" without evidence,
- hide a large recovery correction using UI-only animation.

---

## 10. CURRENT FOUNDATION REGRESSION GATES

Preserve already-verified behavior after every navigation phase:

- Android acquisition permissions/lifecycle,
- bounded acquisition queues,
- recording,
- recovery/finalization,
- local export,
- replay source/timestamps,
- offline MapLibre pack,
- synthetic map label/isolation,
- saved recordings.

A new navigation feature fails acceptance if it breaks a previously frozen subsystem.

---

## 11. MVP ACCEPTANCE CRITERIA

The full PS26168 prototype is accepted only when all applicable criteria hold.

1. **Calibration:** automatic phone-to-vehicle alignment produces valid, physically tested transforms and handles remount invalidation.
2. **Classical baseline:** synthetic mechanization tests pass; no unresolved frame/sign bug remains.
3. **AI:** the deployed AI speed/vibration model uses causal deployable inputs and shows held-out improvement over the defined non-ML baseline.
4. **Fusion:** GNSS+INS fusion runs continuously and rejects obviously inconsistent/stale GNSS measurements.
5. **GNSS outage:** during defined held-out blackout tests, navigation continues without fresh GNSS.
6. **Official drift benchmark:** final dead-reckoning positional drift is **<10% of reference distance travelled** for the accepted evaluation scenarios.
7. **Recovery:** GNSS return is gated and converges without a hidden UI-only teleport.
8. **Map matching:** road-constrained output is distinguishable from raw fused output and does not force ambiguous matches.
9. **Mobile update rate:** real navigation output sustains approximately **10 Hz** on the target smartphone.
10. **On-device:** core inference/navigation works without mandatory Internet/cloud inference.
11. **Real-time interface:** map displays real engine output with smooth marker/trail/heading and clear quality/state information.
12. **Recording/evidence:** test run can be recorded, finalized, exported and replayed without corrupting source data.
13. **Security/privacy:** MVP controls pass with no hidden background/network collection.
14. **Regression:** previously frozen acquisition/recording/replay/map tests remain green.
15. **Edge deliverable:** when included in the final acceptance stage, the external-IMU engine demonstrates sustained operation toward **~200 Hz** with measured timing and accuracy.

### If a criterion is not implemented

It must be explicitly marked **NOT READY / NOT VERIFIED**. A synthetic visualization, design document or projected benchmark does not count as a pass.

---

## 12. Evidence Package Per Final Field Run

Store:

```text
experiment manifest
phone metadata.json
phone measurements.jsonl
reference log
outage intervals
Git SHA
app/model versions + hashes
map/road-graph version
test configuration
metrics.json
trajectory plots
runtime performance report
notes / known anomalies
```

This makes every drift/update-rate claim reproducible.

---

**This document is a verification plan. It does not convert currently unimplemented calibration, INS, AI, fusion, map matching, recovery or edge components into completed features.**
