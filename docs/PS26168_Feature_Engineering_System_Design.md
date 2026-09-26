# PS26168 — Feature Engineering System Design

**Scope:** Define causal, online-capable features derived from canonical IDR measurements for AI correction, motion-state estimation, confidence support, and evaluation. No model architecture, estimator equation, or final algorithm selection is made here.

**Causality rule:** a feature at time `t` may use only measurements/state available at times `≤ t`. No future GNSS, future VBOX/reference state, centered smoothing, post-outage recovery data, or whole-trip normalization may influence a deployable feature.

**Scientific gate:** IO-VNBD inertial channels must first map into a validated physical contract. The features below define intended semantics; they do not approve unresolved dataset channels.

---

## 1. Streaming Building Blocks

| Primitive | Purpose |
|---|---|
| fixed trailing windows | vibration/motion statistics |
| causal EWMA/EWMV | slowly adapting bias/noise baselines |
| Welford mean/variance | stable online statistics |
| causal low/high-pass filters | separate motion from vibration where validated |
| bounded ring buffers | sequence windows without trip-sized memory |
| finite differences with guarded dt | jerk/angular acceleration |
| quaternion rotation | device→vehicle→ENU |
| robust clipping flags | detect, not hide, outliers |
| missingness masks | preserve optional-signal absence |
| quality/state embeddings | GNSS/calibration/sensor context |

All windows are bounded.

---

## 2. Physical Normalization Precondition

```text
raw sensor
  ↓
contract validation
  ↓
bias correction (when justified)
  ↓
device→vehicle rotation
  ↓
gravity/specific-force treatment
  ↓
feature computation
```

Do not compute "forward acceleration" from an unresolved axis.

---

## 3. Core IMU Features

### Accelerometer / specific force
Candidate features:
- forward/left/up specific force,
- force norm,
- trailing mean/variance/RMS,
- absolute mean,
- peak-to-peak,
- clipped/outlier count,
- sample-gap flag.

### Gyroscope
- roll/pitch/yaw rate in vehicle frame,
- gyro norm,
- rolling mean/variance/RMS,
- bias residual,
- turn-rate magnitude,
- gap flag.

### Jerk

```text
jerk_k = (a_k - a_{k-1}) / dt
```

with guarded dt.

Features:
- forward jerk,
- lateral jerk,
- jerk magnitude,
- rolling RMS/variance.

Repeated/invalid timestamps produce missing/diagnostic output, not divide-by-zero repair.

---

## 4. Vibration / Road-Noise Features

Candidate short-window features:
- high-frequency acceleration RMS,
- high-frequency gyro RMS,
- causal spectral-band energy,
- zero-crossing rate,
- peak density,
- robust MAD,
- high-frequency / total-energy ratio.

Requirements:
- window tied to actual sample rate,
- no centered filter,
- rate variation explicit,
- feature latency counted.

---

## 5. Motion-State Features

Possible states:
- stationary,
- low-speed,
- accelerating,
- braking,
- turning,
- steady-speed,
- high-vibration.

Inputs:
- accel/gyro stats,
- current estimated speed,
- GNSS speed only when runtime mode explicitly allows it.

A pure outage model must not require GNSS speed after denial.

---

## 6. Calibration Features / Diagnostics

- gravity stability,
- gyro stationary variance,
- tilt stability,
- straight-motion duration,
- GNSS course stability,
- forward-acceleration excitation,
- mounting-change discontinuity,
- calibration age,
- calibration confidence.

These may support calibration but need not become main navigation-model features.

---

## 7. Speed-Estimation Features

Candidate deployable window:
- vehicle-frame forward specific force,
- lateral/up force,
- yaw rate,
- gyro norm,
- vibration metrics,
- jerk,
- previous predicted speed/state if recurrence is explicit,
- motion-state flags,
- calibration quality.

Training label:
- VBOX/reference speed.

Prohibited:
- future VBOX,
- centered future-crossing smoothing,
- random-row leakage,
- VBOX acceleration as inference input,
- future GNSS.

---

## 8. Heading / Yaw Features

- integrated yaw increment,
- current yaw rate,
- yaw-rate mean/variance,
- turn-state,
- validated magnetometer residual,
- GNSS course residual while valid,
- calibration yaw uncertainty.

GNSS course remains nullable, especially at low speed.

---

## 9. GNSS Quality Features

- fix age,
- horizontal/vertical accuracy,
- satellites used,
- provider,
- speed-present flag,
- bearing-present flag,
- state/reason codes,
- time since last accepted update.

No "good" threshold is frozen before validation.

---

## 10. EKF / Fusion Residual Features

Only after classical fusion exists:
- position innovation norm,
- velocity innovation norm,
- normalized innovation statistic,
- rejected-update flag,
- covariance summary,
- time since last accepted GNSS,
- outage duration,
- speed,
- yaw rate,
- calibration confidence.

Potential uses:
- adaptive measurement noise,
- AI residual correction,
- recovery gating.

Training and live must use the same fusion implementation.

---

## 11. Non-Holonomic Constraint Features

Gating:
- speed,
- turn rate,
- lateral acceleration,
- calibration confidence,
- low-speed/reverse state,
- vehicle class if applicable.

Constraint residuals:
- lateral velocity,
- vertical velocity.

---

## 12. Map-Matching Features

After road graph exists:
- distance to candidate edge,
- heading difference,
- transition distance,
- topology continuity,
- speed compatibility,
- position uncertainty,
- previous matched edge.

A full-sequence offline Viterbi result must not be presented as equivalent to an online causal matcher unless that is explicitly the intended runtime algorithm.

---

## 13. Confidence Features

Potential inputs:
- EKF covariance,
- outage duration,
- distance since GNSS correction,
- speed,
- turn rate,
- calibration quality,
- innovation stats,
- map-match confidence,
- gap indicators.

Label:
- actual horizontal error against independent ground truth.

The uncertainty model predicts uncertainty; it does not silently alter position.

---

## 14. Data-Quality Features

Masks/flags:
- missing optional sensor,
- GNSS unavailable,
- sensor accuracy,
- late-event count,
- time-gap flag,
- queue-drop flag,
- invalid calibration,
- model unavailable,
- map coverage unavailable.

Missingness remains explicit rather than zero-imputed by default.

---

## 15. Window Design

Prefer time-based windows.

Illustrative horizons:
- 100–300 ms: vibration/transients,
- 0.5–2 s: motion/speed correction,
- 2–10 s: calibration/quality trends.

Exact values are tunable, not frozen here.

---

## 16. Resampling

If a fixed-rate tensor is required:
- define one causal interpolation/hold policy,
- preserve original timestamps,
- mark generated/interpolated points,
- never interpolate across large gaps,
- use identical training/inference preprocessing.

---

## 17. Leakage Controls

### Split first
Assign recording groups before fitting normalizers/filters/feature selection.

### No reference input
VBOX/CAN is label/evaluation only.

### No future windows
No centered filters for deployable features.

### No recovery leakage
Post-outage GNSS cannot improve an earlier outage state except in clearly labelled offline smoothing research.

### Duplicate groups
Duplicate copies stay in the same split.

---

## 18. Train / Serve Equivalence

The deployable feature code used during training/replay should be the same implementation used live, or verified numerically equivalent.

Recommended:

```text
canonical Record stream
        ↓
common preprocessing/features
        ├── live inference
        └── replay/training materialization
```

Avoid a convenient pandas-only implementation with different window/filter semantics.

---

## 19. Missing / Out-of-Order Handling

- invalid record: reject + diagnostic,
- duplicate sensor/provider timestamp: follow acquisition rule,
- bounded late event: process according to explicit reorder policy,
- too late: drop/diagnose, never revise emitted history,
- large IMU gap: degrade/reinitialize rather than integrate blindly,
- missing optional feature: null/mask, not zero.

---

## 20. Candidate Feature Groups by Use

| Use | Primary groups |
|---|---|
| speed/vibration model | calibrated IMU, jerk, vibration, motion state |
| calibration quality | gravity stability, gyro variance, straight-motion/GNSS context |
| GNSS quality | fix age/provider/satellites/reasons |
| adaptive fusion | innovations, covariance, GNSS quality, motion |
| uncertainty | covariance + outage/motion/calibration/map-match context |
| map matcher | road distance/heading/topology/uncertainty |

---

## 21. Current Readiness

### Ready
- live Android frame semantics,
- exact timestamps,
- typed measurements,
- replay/recording infrastructure,
- deterministic fixtures.

### Not ready
- universal IO-VNBD device-frame mapping,
- deployable forward/lateral features from all synchronized IO-VNBD,
- frozen model windows,
- EKF residual features,
- map-matching features,
- calibrated uncertainty features.

---

**No model architecture or algorithm selection is performed here.**
