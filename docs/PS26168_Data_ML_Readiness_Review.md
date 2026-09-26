# PS26168 — Data & ML Readiness Review

**Scope:** Audit only — check the already-defined canonical schema, data requirements, dataset portfolio, causal features, non-ML baseline plan, ML architecture, model selection, and model-development methodology against each other for consistency, completeness, and deployability. No runtime/app redesign happens here.

---

## Checklist Walk-Through

### 1. Every learning task has the data it requires

#### Speed / vibration correction
**PARTIAL.**

- IO-VNBD provides synchronized smartphone and VBOX/reference channels.
- Reference speed exists.
- However, the deployable physical interpretation of the exported inertial channels is not yet fully resolved.
- Future ground-truthed Android drives are required for final product validation.

**Status:** data volume exists, but source semantics remain a blocker.

#### Fusion residual / adaptive noise model
**FAIL — not ready.**

The classical EKF does not yet exist, therefore:

- no trustworthy innovation/residual stream,
- no accepted state/covariance features,
- no target residual correction dataset.

#### Uncertainty calibration
**FAIL — not ready.**

Requires:

- real filter uncertainty,
- independent ground-truth error,
- multiple GNSS/DR/recovery conditions.

Those do not yet exist in sufficient form.

#### Remount detection
**PARTIAL.**

A controlled remount protocol is defined, but labelled remount recordings have not yet been collected at scale.

#### Map matching
**PARTIAL.**

Offline rendering data exists, but a routable/matchable road graph has not yet been integrated.

---

### 2. Every proposed feature can actually be derived

#### Live Android
**PASS for raw canonical measurements.**

The app has verified:

- accelerometer,
- gyroscope,
- gravity,
- magnetometer,
- GNSS/location,
- exact timestamps,
- provider/quality state.

#### IO-VNBD
**PARTIAL.**

Simple source-domain fields can be read, but features requiring a guaranteed device→vehicle physical interpretation remain blocked until frame/export semantics are closed.

#### EKF residual features
**FAIL for now.**

No filter, no residuals.

#### Map-matching features
**FAIL for now.**

No road graph/matcher.

---

### 3. No feature violates the no-OBD / local-inference constraint

**PASS in design.**

The selected feature set uses:

- phone IMU,
- phone GNSS,
- calibration state,
- filter state,
- offline map/road data.

VBOX/CAN is reference-only.

No model requires cloud inference.

---

### 4. All deployable features are causal

**PASS in design.**

The feature-engineering document explicitly disallows:

- future GNSS,
- centered future-crossing smoothing,
- post-recovery leakage into an earlier outage state,
- whole-trip normalization using test/future data.

**Process gap:** train/live equivalence is not yet executable because the final deployable feature module does not exist.

---

### 5. No train/test or temporal leakage remains in the methodology

**PASS in design.**

The methodology requires:

- drive-level splitting,
- duplicate grouping,
- training-only normalization fitting,
- held-out route/condition evaluation,
- test isolation.

Execution remains pending.

---

### 6. Non-ML baselines exist where appropriate

**PARTIAL.**

Planned classical baselines exist conceptually for:

- calibration,
- INS,
- GNSS quality,
- EKF,
- NHC,
- map matching.

However, the current historical Phase-1 INS run is **not an accepted physical baseline** because of frame/input-semantic problems.

Therefore the project still needs a corrected baseline implementation and synthetic tests.

---

### 7. ML is only used where justified

**PASS.**

The current model-selection decision is deliberately conservative:

- ML primary: speed/vibration correction after data gate.
- ML deferred: fusion residual correction.
- no ML primary for calibration, GNSS quality, map matching, or routing.
- no end-to-end neural navigator.

This is consistent with the physics-first architecture.

---

### 8. Model outputs can support confidence and evidence

**PARTIAL.**

The contract can carry:

- navigation state,
- GNSS quality,
- confidence,
- diagnostics.

But:

- no real model version/provenance stream is frozen yet,
- no calibrated uncertainty exists,
- no actual AI artifact exists.

The schema is ready; the evidence is not.

---

### 9. Selected models are feasible for near-real-time inference

**PASS in architecture, unverified in measurement.**

A small causal 1D CNN/TCN is a plausible mobile model.

A small MLP for future residual correction is also plausible.

But actual:

- TFLite/ONNX latency,
- full navigation-loop latency,
- 10 Hz stability,

must still be measured.

---

### 10. Condition imbalance and generalization risks are addressed

**PASS in methodology.**

The design explicitly handles:

- route imbalance,
- motion-type imbalance,
- GNSS-good vs outage imbalance,
- mount/device imbalance,
- trip-level correlation.

Per-condition reporting is required.

---

### 11. Training and inference schemas match

**PARTIAL — important open gap.**

The intended architecture says training/replay/live should reuse the same preprocessing/feature semantics.

Current reality:

- canonical raw contracts exist,
- live Android acquisition exists,
- replay exists,
- final deployable feature/preprocessing module does not yet exist.

Therefore equivalence cannot yet be proven.

---

# Traceability Table

| Task | Data | Features / state | Non-ML baseline | ML choice | Output | Status |
|---|---|---|---|---|---|---|
| phone→vehicle calibration | live Android + controlled runs | gravity, gyro stability, GNSS course | analytic geometry | none MVP | CalibrationResult | NOT BUILT |
| speed/vibration | IO-VNBD + future Android ground truth | calibrated causal IMU windows | linear / classical speed propagation | causal 1D CNN/TCN | speed/correction | DATA SEMANTICS BLOCKED |
| classical INS | validated calibrated IMU | specific force, gyro, dt | strapdown INS | none | position/velocity/attitude | NOT BUILT CORRECTLY |
| GNSS quality | live GNSS | fix age/accuracy/sats/reasons | deterministic gate | none MVP | GnssQualityState | PARTIAL FOUNDATION |
| GNSS/INS fusion | INS + GNSS | state/covariance/innovation | EKF | none first | NavigationState | NOT BUILT |
| residual correction | EKF + reference | residual/covariance summary | rule-adaptive Q/R | small MLP later | correction/noise scale | BLOCKED BY EKF |
| NHC | vehicle-frame state | lateral/vertical velocity | kinematic pseudo-measurement | none | constrained state | NOT BUILT |
| uncertainty | filter + reference | covariance/outage/context | raw covariance | empirical calibration | Confidence | BLOCKED |
| map matching | nav output + road graph | distance/heading/topology | candidate/HMM | none | matched position | ROAD GRAPH MISSING |
| routing | road graph + destination | graph state | graph search | none | route/progress | OPTIONAL / NOT BUILT |

---

# Inconsistencies and Missing Links — Consolidated

## Gap 1 — IO-VNBD physical-source semantics
**Severity: BLOCKER**

The biggest issue is not model family selection. It is whether the training inputs correspond to the physical quantities the deployed Android model will receive.

Required closure:
- verified mapping/subset,
- or new ground-truthed Android training data.

---

## Gap 2 — Classical baseline not yet scientifically accepted
**Severity: BLOCKER**

The old high-drift INS result is reproducible evidence of a failed interpretation, not the baseline AI must beat.

Required:
- synthetic mechanization tests,
- correct frames,
- real-data baseline.

---

## Gap 3 — No classical fusion
**Severity: BLOCKER**

Residual/uncertainty models cannot be built responsibly without it.

---

## Gap 4 — No final ground-truth Android drive data
**Severity: BLOCKER**

A mobile product cannot be accepted from IO-VNBD-only evidence.

---

## Gap 5 — Train/serve feature equivalence not yet executable
**Severity: HIGH**

The design is correct but needs a shared implementation and regression test.

---

## Gap 6 — No model artifact / export benchmark
**Severity: HIGH**

No TFLite/ONNX model is currently deployed.

---

## Gap 7 — Map matching needs road graph
**Severity: MEDIUM/HIGH**

Rendering tiles are not enough.

---

## Gap 8 — Edge evidence absent
**Severity: HIGH / stage-dependent**

No FOG-grade data or 200 Hz runtime exists.

---

# DATA/ML STATUS: **NOT READY**

The design is coherent, but the project is not ready to freeze or train the final navigation models.

### Blocking sequence

```text
resolve physical source semantics
        ↓
correct classical INS
        ↓
phone calibration
        ↓
classical GNSS/INS fusion
        ↓
ground-truthed Android evaluation
        ↓
train first AI speed/vibration model
        ↓
ablation
```

---

# What Is Ready

- canonical contracts,
- Android acquisition,
- recording/export/replay,
- offline map foundation,
- dataset inventory/audit,
- causality/leakage policy,
- ML architecture decision,
- model-development methodology,
- model-family shortlist.

---

# What Is Not Ready

- accepted deployable inertial feature contract from IO-VNBD,
- production calibration,
- classical navigation baseline,
- fusion,
- AI training,
- uncertainty calibration,
- map matcher,
- edge deployment.

---

**Readiness verdict: NOT READY for final model development. The blockers are explicitly bounded and should be resolved before AI training is treated as production progress.**
