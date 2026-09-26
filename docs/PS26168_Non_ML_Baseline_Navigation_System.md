# PS26168 — Non-ML Baseline Navigation System

**Scope:** Define simple, explainable, non-ML navigation components that establish the scientific baseline any later AI-enhanced IDR system must beat. No ML model is selected here.

**Critical correction:** the repository's historical high-drift Phase-1 INS run is not accepted as this baseline because its physical input/frame interpretation was later shown to be unreliable. This document defines the *baseline that must be built correctly*.

---

## 1. Calibration Baseline

### Inputs

- Android accelerometer/gravity,
- gyroscope,
- optional GNSS speed/course,
- exact timestamps.

### Decision / estimation logic

#### Pitch / roll
During a stationary window:

```text
gravity_device
        ↓
estimate down/up direction
        ↓
device tilt relative to vehicle/world vertical
```

#### Yaw / forward alignment
During sufficiently straight motion:

```text
GNSS course / vehicle displacement direction
        +
phone-frame horizontal acceleration / rotation consistency
        ↓
estimate vehicle-forward axis
```

Use a proper rotation matrix/quaternion.

### Output

```text
CalibrationResult
- q_vehicle_from_device
- gyro bias
- optional accel bias
- quality
```

### Tuning strategy

- stationary variance thresholds from real phone data,
- straight-motion requirements from validation runs,
- reject poorly excited windows rather than force an answer.

### Weaknesses

- GNSS course poor at low speed,
- straight driving may not excite enough axes,
- phone can move after calibration,
- magnetometer may be distorted inside vehicles.

### AI burden of proof

An ML calibration model must outperform this explicit geometric baseline without losing physical interpretability.

---

## 2. Strapdown INS Baseline

### Inputs

- valid calibration,
- calibrated specific force,
- calibrated gyro,
- initial position/velocity/attitude,
- monotonic dt.

### Mechanization

Conceptually:

```text
gyro
  ↓
attitude propagation
  ↓
rotate specific force into navigation frame
  ↓
add gravity
  ↓
integrate acceleration → velocity
  ↓
integrate velocity → position
```

Use correct Earth/local-frame approximations appropriate to the test horizon.

### Output

- ENU position,
- ENU velocity,
- attitude,
- diagnostic state.

### Required unit tests

- stationary,
- constant velocity,
- constant acceleration,
- constant-rate turn,
- bias injection,
- irregular dt,
- quaternion normalization.

### Weaknesses

Expected drift from:

- gyro bias,
- accelerometer bias,
- vibration,
- mounting error,
- gravity-removal error,
- double integration.

That is exactly why AI enhancement may help.

---

## 3. GNSS Quality Baseline

### Inputs

- fix age,
- provider,
- horizontal/vertical accuracy,
- satellites used,
- speed/bearing availability,
- timing/permission state.

### Rule logic

Illustrative state machine:

```text
no permission / provider unavailable
        → UNAVAILABLE

provider active but no usable fix
        → ACQUIRING

fresh fix + acceptable metadata
        → GOOD

fresh but poor accuracy / weak metadata
        → DEGRADED

fix age exceeds stale threshold
        → STALE

known outage condition / sustained invalidity
        → DENIED
```

Exact thresholds require validation.

### Output

`GnssQualityState` + reason codes.

### Weaknesses

Provider accuracy can be optimistic.

Therefore fusion innovation gating must later corroborate input quality.

---

## 4. GNSS + INS EKF Baseline

### State

A minimal error-state or full-state EKF may include:

```text
position
velocity
attitude error
gyro bias
accelerometer bias
```

Exact state formulation is an implementation choice to freeze separately.

### Prediction

Driven by classical INS propagation.

### Measurement updates

Use available GNSS:

- position,
- velocity/speed where justified,
- course/heading where valid and properly modeled.

### Gating

Reject inconsistent measurements using statistically justified innovation checks rather than a UI-level "GPS bad" switch.

### GNSS outage

Prediction continues without GNSS update.

### Recovery

On return:

1. validate fix,
2. compute innovation,
3. gate,
4. update state,
5. reduce uncertainty based on actual filter math.

### Output

Canonical `NavigationState`, quality, diagnostics, uncalibrated covariance-based confidence.

### Weaknesses

- poorly tuned Q/R,
- unmodeled vibration,
- bias observability,
- low-cost MEMS drift,
- bad GNSS during urban canyon.

This is the main baseline AI must improve.

---

## 5. Stationary / Zero-Velocity Baseline

Cars frequently stop.

### Logic

If:

- accelerometer/gyro statistics indicate stationary,
- estimated speed is sufficiently low,
- GNSS does not contradict strongly,

then apply a stationary/zero-velocity constraint.

### Output

Pseudo-measurement:

```text
vehicle velocity ≈ 0
```

### Weaknesses

False stationary detection can freeze a slowly moving vehicle.

Tune conservatively.

---

## 6. Non-Holonomic Constraint Baseline

For a normal road vehicle in appropriate conditions:

```text
vehicle lateral velocity ≈ 0
vehicle vertical velocity ≈ 0
```

### Inputs

- vehicle-frame velocity,
- speed,
- turn rate,
- calibration validity.

### Gate

Disable/weaken when:

- calibration invalid,
- unusual maneuvers,
- vehicle type incompatible,
- very low-speed parking/reversing if model assumptions do not fit.

### Output

Filter pseudo-measurement/constraint.

### Weaknesses

Not universally valid for motorcycles, skids, slopes, or complex maneuvers.

---

## 7. Speed Baseline

Before AI, create at least two non-ML comparisons.

### A. GNSS speed where available

Useful only as a reference/aided signal, not during outage.

### B. Classical inertial speed

Integrate forward vehicle-frame acceleration with:

- bias correction,
- stationary reset,
- optional NHC constraints.

### C. Simple causal filter

Low-pass/robust smoothing of forward acceleration/speed estimate.

### Output

Forward speed estimate.

### Weaknesses

Double-integration/bias sensitivity makes this a meaningful AI target.

---

## 8. Recovery Baseline

No dedicated ML.

### Logic

- maintain DR during denied GNSS,
- upon recovery, require a valid/gated measurement,
- let EKF update the state,
- presentation layer interpolates marker motion only if needed,
- display smoothing never changes stored estimator truth.

### Metric

- pre-recovery error,
- peak innovation/correction,
- convergence time,
- post-recovery error.

---

## 9. Map-Matching Baseline

### Inputs

- current WGS84 position,
- heading,
- speed,
- uncertainty,
- local road graph.

### Simple candidate score

For nearby road edges:

```text
score =
    position distance term
  + heading compatibility term
  + continuity/topology term
```

Choose only if confidence is sufficient.

### Output

Keep both:

```text
raw fused position
matched position
matched edge ID
match confidence
```

### Weaknesses

- parallel roads,
- flyovers,
- parking/service lanes,
- large raw error.

Do not force a snap when candidates are ambiguous.

---

## 10. Confidence Baseline

Before calibrated confidence exists:

- expose filter covariance as **unvalidated**,
- never call it 95% real-world accuracy without empirical calibration.

### Output

`Confidence(state = UNVALIDATED, ...)`

Later empirical calibration may promote it.

---

## 11. Baseline Evaluation Protocol

Use the same held-out outage for all versions.

Compare:

```text
reference
classical INS
classical EKF
+ stationary/NHC
+ map matching
AI-enhanced system
```

Metrics:

- final outage error,
- drift percentage,
- RMSE,
- speed MAE/RMSE,
- heading error,
- recovery convergence.

---

## 12. Threshold Tuning

Thresholds include:

- stationary variance,
- GNSS quality,
- innovation gate,
- NHC enable/disable,
- map-match acceptance.

Tune on training/validation runs only.

The final test set is not used for threshold selection.

---

## 13. Expected Failure Modes / False Corrections

### Calibration
- wrong forward axis.

### Stationary detector
- slow creep interpreted as stopped.

### GNSS gate
- bad fix accepted or good fix rejected.

### NHC
- constraint applied during incompatible maneuver.

### Map matcher
- snapped to parallel wrong road.

### Recovery
- huge correction accepted without gating.

Each should create a diagnostic rather than silently producing a polished map.

---

## 14. Where ML Has the Strongest Burden of Proof

### Clearly justified ML target
**Speed / vibration correction.**

Reason:
- phone vibration/noise is complex and temporal,
- classical integration is weak,
- independent speed labels exist.

### Potentially justified after baseline
**Fusion residual / adaptive noise.**

Only after classical EKF residuals are available.

### Not clearly ML-requiring
- calibration,
- GNSS quality,
- NHC,
- map matching,
- routing.

Classical solutions are already strong and easier to audit.

---

## 15. Baseline Acceptance Gate

The non-ML baseline is acceptable only when:

- synthetic physics tests pass,
- coordinate/frame conventions are explicit,
- real replay runs deterministically,
- no reference leakage occurs,
- masked-GNSS evaluation can be reproduced,
- errors are plausible and attributable.

A catastrophically wrong baseline caused by an axis bug is not a useful target for AI to "beat."

---

**No ML model is selected in this document. This system exists to establish the honest classical baseline against which AI value is measured.**
