# PS26168 — Real-Time Runtime Pipeline Design

**Scope:** Runtime mechanics only — event flow, concurrency model, state management, buffering, ordering, backpressure, failure handling, live/replay behavior, and technology choice for the on-device navigation pipeline. No UI visual design, model-training methodology, or offline-map styling is designed here.

**Target flow:**

```text
Sensors / Replay
    ↓
Acquisition / Contract Validation
    ↓
Calibration / Physical Normalization
    ↓
Feature Windowing / AI Correction
    ↓
INS Mechanization
    ↓
GNSS Quality + Fusion
    ↓
Vehicle Constraints
    ↓
Map Matching
    ↓
Confidence / Diagnostics
    ↓
Navigation Output
    ↓
Map/UI + Recorder/Evaluation consumers
```

---

## 1. Event Flow Overview

| Stage | Consumes | Produces | Runtime behavior |
|---|---|---|---|
| Android acquisition | SensorEvent / Location callbacks | canonical `Record` | callbacks return quickly; samples enter bounded processing path |
| Contract validation | canonical candidate records | validated records / diagnostics | synchronous, cheap |
| Calibration state | valid IMU/GNSS | active CalibrationResult/state | single navigation-session ownership |
| Physical normalization | IMU + calibration | vehicle-frame physical inputs | synchronous per event |
| Feature/window state | normalized input | causal features / model tensors | bounded state |
| AI correction | feature window | speed/noise correction | inference worker, non-UI |
| INS mechanization | IMU + corrections | predicted state | ordered stateful processing |
| GNSS quality | GNSS/provider state | quality state | cheap, deterministic |
| Fusion | INS + accepted GNSS | fused state/covariance | ordered, stateful |
| Vehicle constraints | fused state | constrained state | synchronous filter update |
| Map matching | nav state + local graph | raw + matched state | bounded candidate search |
| Confidence | covariance + context | Confidence record | cheap post-processing/calibration |
| Navigation output | complete state | canonical records | non-blocking publication |
| Recorder | raw canonical acquisition stream | metadata + JSONL | sibling downstream consumer; already implemented |
| Map/UI | canonical navigation output | presentation | sampled/interpolated for display only |

---

## 2. Synchronous vs. Asynchronous Operations

### Must remain non-blocking at Android callback boundary

Never perform in `SensorEventListener` / location callbacks:

- file I/O,
- model inference,
- INS integration,
- map rendering,
- heavy JSON processing.

Callbacks only capture/validate minimal data and enqueue/offer it to the existing acquisition worker path.

### Sequential within one navigation state

For one accepted IMU event, these operations are logically ordered:

```text
normalize
→ update feature state
→ AI correction if due
→ mechanize
→ apply pending/accepted aiding
→ constraints
→ publish
```

They do not benefit from being arbitrarily parallelized because later stages depend on earlier state.

### Parallel sibling work

Recording, UI presentation, and diagnostics can consume published streams independently.

A slow map renderer must not stall INS.

---

## 3. State Management

Navigation is inherently stateful.

One live engine session owns:

- active calibration,
- attitude,
- position,
- velocity,
- biases,
- covariance,
- GNSS quality,
- feature-window buffers,
- model recurrent state if any,
- outage/recovery state,
- map-matching history.

### Ownership

A state object belongs to exactly one engine session.

Do not share mutable navigation state between:

- real live session,
- replay,
- simulation,
- two recordings.

### Reset

A new session explicitly resets:

- mechanization state,
- feature windows,
- recurrent hidden state,
- filter covariance,
- matcher history.

No hidden state survives source switching.

---

## 4. Buffering

Every unbounded producer/consumer boundary is bounded.

### Existing acquisition inbox

Current Android acquisition uses bounded queues and drop/high-water diagnostics.

Preserve this.

### Navigation ingress

Add a bounded navigation-input channel/queue between canonical acquisition/replay records and the navigation runtime.

Purpose:

- keep heavy inference out of acquisition callbacks,
- measure engine backlog,
- make overload explicit.

### Feature windows

Use fixed-size ring buffers/time windows.

Never store a full trip in memory just to run a sequence model.

### Presentation

Map/UI receives sampled state snapshots if necessary.

Dropping **presentation frames** is acceptable; dropping navigation input is a scientific/runtime failure and must be diagnosed.

---

## 5. Ordering

### Per-session total event order

The engine processes one canonical input sequence at a time.

Rules:

- preserve accepted arrival order where the acquisition contract requires it,
- respect `t_ns`,
- detect duplicate sensor/provider timestamps,
- allow a narrowly defined bounded-lateness policy only if the engine explicitly supports it,
- never revise already-emitted navigation history from a very late event.

### IMU ordering

Gyro/accelerometer integration must be deterministic.

A large time gap triggers:

- diagnostic,
- degraded state,
- possible reinitialization policy,

not blind integration across missing time.

### GNSS ordering

A stale/cached GNSS fix cannot be accepted merely because its callback arrives now.

Use measurement time/fix age.

---

## 6. Backpressure

Backpressure must never silently become unbounded memory growth.

### If navigation falls behind

1. engine queue depth rises,
2. high-water metric rises,
3. warning diagnostic is emitted,
4. if capacity is exhausted, the explicit overflow policy executes.

### Overflow policy

For navigation-critical IMU input, blindly dropping arbitrary old/new samples is dangerous.

Preferred strategy:

- size queue to absorb expected short jitter,
- optimize the slow stage,
- reduce optional work,
- if overflow still occurs, mark navigation **degraded/failed** and record the loss,
- do not continue presenting normal-confidence output as though no samples were lost.

For UI presentation:
- latest-state/conflated behavior is acceptable.

For recorder:
- preserve its independent existing overflow/error semantics.

---

## 7. Queue Limits

Exact capacities are benchmark parameters, not arbitrary architecture constants.

Each queue has:

```text
capacity
current depth
high-water mark
drop count
overflow timestamp/reason
```

Choose capacity from:

- measured peak input rate,
- worst expected short processing pause,
- allowed latency budget,
- memory budget.

A queue that holds several seconds of delayed IMU data may avoid drops but creates unusable stale navigation, so "bigger" is not always better.

---

## 8. Malformed / Invalid Events

Validation occurs before navigation state mutation.

Reject:

- nonfinite vectors,
- unsupported unit/frame,
- negative timestamps,
- `received_ns < t_ns`,
- wrong session/source,
- unsupported event type,
- impossible range,
- invalid quaternion.

Response:

```text
reject input
emit diagnostic
increment invalid/drop counter
do not mutate navigation state
```

Invalid data never silently becomes zero.

---

## 9. Dropped Events / Time Gaps

Distinguish:

### Acquisition drop
Sample never reaches the navigation runtime.

### Engine-ingress overflow
Canonical record exists but could not be admitted.

### Model-stage failure
Record entered engine but AI correction was unavailable.

### Presentation drop
Navigation state exists; UI did not render every frame.

These have different severity.

#### Model failure fallback

If AI inference fails and the classical path is valid:

```text
log MODEL_INFERENCE_FAILED
continue classical navigation
mark provenance/diagnostics
```

Do not crash the whole navigator solely because an enhancement model failed.

#### Core mechanization/fusion failure

If numerical state becomes invalid:

- stop publishing valid tracking state,
- emit failure diagnostic,
- require reset/reinitialization.

---

## 10. Retries and Component Failure

### Sensor acquisition
No retry loop inside callback; Android lifecycle/provider handles availability.

### Model load
May retry only at explicit initialization/reset. Do not reload model every frame.

### Map pack
If missing/corrupt, map presentation fails independently; numeric navigation may continue.

### Road graph
If map matching unavailable, raw fused position may continue with matcher-unavailable diagnostic.

### Recorder
Recorder failure does not stop navigation/acquisition unless an explicit policy says otherwise.

### GNSS
No "retry" request needed; continue listening and update quality state as fixes arrive.

---

## 11. Technology Comparison

| Option | Android fit | State/ordering | Backpressure | Latency | Complexity | Portability |
|---|---|---|---|---|---|---|
| **Kotlin coroutines + Channel/Flow** | **excellent** | explicit single-owner workers | native bounded channels | low | low | Android/JVM |
| Java executors + BlockingQueue | good | explicit | good | low | medium | JVM |
| RxJava | good | rich stream ops | good | low | medium-high | Android/JVM |
| C++ native core via JNI | moderate | manual | manual | potentially lowest | **high** | good for future edge/shared native core |
| Local HTTP/service process | poor for current need | cross-process | extra protocol | higher | high | unnecessary |
| Cloud streaming/backend | conflicts with offline objective | network-dependent | network semantics | variable | highest | wrong MVP scope |

---

## 12. Recommendation

**Mobile:** Kotlin coroutines + bounded Channels/Flow, with one explicit navigation-state owner.

Reasoning:

- already aligned with current Android architecture,
- no extra service,
- lifecycle-aware,
- bounded queues available,
- good testability,
- easy UI StateFlow publication,
- sufficient for ~10 Hz navigation output and ~100 Hz phone IMU input if implementation is efficient.

### CPU-heavy inference

If TFLite/ONNX inference or map matching is too heavy:

- use a dedicated dispatcher/thread,
- preserve state serialization around the engine,
- do not allow concurrent mutation of filter state.

### C++ decision

Only introduce JNI/native shared core after profiling shows Kotlin/Python reference implementations cannot satisfy final performance/portability goals.

---

## 13. LIVE REAL MODE

Source:

```text
AndroidAcquisition.events
```

Behavior:

- explicit foreground start,
- real source,
- recorder may subscribe in parallel,
- NavigationRuntime consumes canonical events,
- GNSS quality follows actual provider behavior,
- output feeds UI/map.

Pacing is determined by sensors/GNSS.

Use for:

- physical field test,
- latency/output-rate measurement,
- final demo.

---

## 14. REPLAY MODE

Source:

```text
metadata.json + measurements.jsonl
```

Output source lineage:

```text
real → replay_real
simulation → replay_simulation
```

### Pacing modes

The existing replay UI may currently support its implemented behavior; future navigation evaluation should support two logical modes where needed:

#### Real-time paced
Preserve recorded timing for UI/runtime behavior.

#### Accelerated deterministic evaluation
Process as fast as safe while **using original `t_ns` for all physics**, never wall-clock acceleration as a new dt.

This is essential:

> accelerated replay changes how fast the test runs, not the physical time inside the navigation equations.

### State

Every replay navigation run starts from a fresh engine state unless the test deliberately chains recordings.

---

## 15. SIMULATION / SYNTHETIC MODE

Current synthetic map demo is a UI fixture.

It must stay separate from real engine performance.

Future physics fixtures may drive the NavigationEngine for tests, but their source and expected truth are explicit.

---

## 16. Navigation Output Publication

Canonical engine output:

- NavigationState,
- GnssQualityState,
- Confidence,
- DiagnosticEvent.

Consumers:

```text
Map/UI
evaluation logger
debug metrics
future edge interoperability
```

Recorder policy decision:

- raw acquisition recording remains canonical evidence,
- if derived navigation records are also recorded later, their provenance/version must be explicit and they must not replace raw measurements.

---

## 17. Runtime Metrics

Track:

- input events/sec by channel,
- navigation states/sec,
- model inference ms,
- mechanization/fusion ms,
- matcher ms,
- p50/p95 end-to-end processing latency,
- queue depth/high water,
- drops,
- invalid events,
- model failures,
- memory/PSS,
- thermal/battery for extended tests.

---

## 18. Determinism

Given:

- identical recording,
- identical engine/model version,
- identical configuration,
- deterministic model/runtime where feasible,

replay should produce identical or tolerance-bounded navigation results.

Any nondeterminism is documented.

---

**No UI/backend or model-training design is performed here. This document defines the real-time mechanics of the on-device navigation runtime.**
