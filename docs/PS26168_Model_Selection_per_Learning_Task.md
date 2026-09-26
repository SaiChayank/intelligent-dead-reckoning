# PS26168 — Model Selection per Learning Task

**Scope:** Compare candidate model families for each justified learning problem inside the selected physics-first architecture. No training is performed here. Each task ends with a baseline, MVP model recommendation, alternative, and optional-advanced direction.

**Cross-cutting rule:** classical/analytic methods remain primary where the problem is already well defined by physics. ML is selected only when it has a measurable target and can plausibly add value.

---

## 0. Cross-Cutting Notes

- All models use causal inputs.
- VBOX/reference channels are labels only.
- Training windows are grouped by drive before splitting.
- Model output thresholds/calibration use validation only.
- Runtime cost must be measured on the target device.
- Temporal models are considered only where a time sequence contains information a static feature vector would lose.
- The first exported artifact must support the mobile runtime; ONNX support is also preferred for the edge target.

---

## 1. Speed / Vibration Correction — Primary ML Task

### Candidates

| Candidate | Input | Output | Compute | Explainability | Strengths / Weaknesses |
|---|---|---|---|---|---|
| Ridge / linear regression | engineered causal IMU window statistics | speed / speed correction | very low | excellent | strong baseline; misses nonlinear vibration/motion relationships |
| Random Forest / gradient boosting | engineered window features | speed / correction | low | good | strong tabular baseline; deployment across Android/edge needs careful export/runtime choice |
| Small MLP | engineered features | speed / correction | low | moderate | easy TFLite/ONNX export; ignores within-window order unless features encode it |
| **Small causal 1D CNN / TCN** | ordered calibrated IMU window | forward speed / correction | low–moderate | moderate | captures vibration/local temporal patterns; parallelizable and mobile-friendly |
| GRU | ordered IMU window | speed / correction | moderate | lower | good temporal memory; sequential inference cost |
| Transformer | longer sequence | speed / correction | high | low | unnecessary complexity/data demand for MVP |

### Recommendation

**MVP MODEL:** small causal **1D CNN / Temporal Convolutional Network** after physical input semantics are validated.

Why:
- sequence-shaped vibration/noise problem,
- lower latency than recurrent models,
- straightforward fixed-window on-device inference,
- TFLite/ONNX friendly,
- bounded receptive field supports causality.

**BASELINE:** Ridge + gradient-boosting/tabular baseline on engineered features.

**ALTERNATIVE:** GRU if TCN fails to capture longer dependence.

**OPTIONAL ADVANCED:** small CNN+GRU only if measured improvement justifies extra latency.

---

## 2. Phone-to-Vehicle Calibration

This is primarily an analytic estimation problem.

### Candidates

| Candidate | Role | Decision |
|---|---|---|
| gravity-based leveling + GNSS-course/kinematic yaw alignment | primary calibration | **MVP** |
| robust least-squares / RANSAC fit | improve dynamic alignment | alternative |
| classifier/regressor predicting mount orientation | learned shortcut | not recommended for MVP |
| neural orientation estimator | full learned calibration | future research |

### Recommendation

**MVP:** no ML. Use explicit geometry/optimization with a calibration-quality score.

Reason:
- physical rotation must remain interpretable,
- errors are catastrophic downstream,
- analytic solution can be unit-tested.

ML may later detect remount patterns, not replace the rotation definition.

---

## 3. Sensor Bias Estimation

### Candidates

- stationary mean estimator,
- slowly adapting EWMA/Kalman bias state,
- MLP/sequence bias predictor from temperature/motion context.

### Recommendation

**MVP:** classical bias state/estimator.

**Alternative:** learned bias correction only after temperature/device diversity data exists.

Current project does not have enough evidence to justify a learned sensor-bias model.

---

## 4. Fusion Residual / Adaptive Noise Correction

**Precondition:** classical EKF must already pass.

### Candidates

| Candidate | Input | Output | Compute | Notes |
|---|---|---|---|---|
| fixed hand-tuned Q/R | filter state/context | noise matrices | lowest | classical baseline |
| rule-based adaptive noise | GNSS quality / innovation | Q/R scale | very low | interpretable |
| XGBoost/LightGBM | residual summary | correction/noise scale | low | good research baseline |
| **small MLP** | residual/covariance/quality vector | bounded correction or noise scale | low | easy mobile/ONNX deployment |
| GRU/TCN | residual history | adaptive correction | moderate | only if temporal history adds measurable value |

### Recommendation

**MVP for first working system:** fixed/rule-adaptive classical EKF, **no learned fusion model yet**.

**First learned candidate:** small MLP once residual targets exist.

**Alternative:** gradient boosting for offline comparison.

**Optional advanced:** causal TCN residual model.

This task is explicitly deferred until the classical filter is stable.

---

## 5. GNSS Quality / Measurement Acceptance

### Candidates

- deterministic rules on fix age/accuracy/satellites/provider,
- innovation gating from EKF,
- binary classifier for GNSS-good vs bad,
- anomaly detector.

### Recommendation

**MVP:** deterministic quality rules + statistical innovation gate.

No ML required initially.

Why:
- conditions are interpretable,
- false acceptance is dangerous,
- labels for "bad GNSS" are difficult without strong reference.

A classifier becomes justified only after a ground-truthed urban/tunnel dataset exists.

---

## 6. Remount / Phone-Movement Detection

### Candidates

| Candidate | Input | Strength |
|---|---|---|
| threshold on orientation/gravity discontinuity | cheap, interpretable |
| change-point detection | better for subtle shifts |
| Logistic Regression / small tree | supervised combination of several signals |
| sequence model | handles gradual complex changes but data-hungry |

### Recommendation

**MVP:** threshold/change-point detector.

**Alternative ML:** Logistic Regression or small tree if labelled remount data shows rule overlap.

Do not use a deep model.

---

## 7. Motion-State Detection

Possible states:
- stationary,
- moving,
- turning,
- braking/accelerating.

### Candidates

- deterministic thresholds,
- Logistic Regression,
- Random Forest,
- small temporal CNN.

### Recommendation

**MVP:** deterministic stationary/motion rules plus vehicle-state values already produced by navigation.

**ML:** only if a downstream model demonstrably benefits from a more accurate state label.

Do not create a classifier merely because classification is easy to demo.

---

## 8. Uncertainty Calibration

This is not a conventional navigation predictor.

### Candidates

| Candidate | Output | Recommendation |
|---|---|---|
| raw EKF covariance | uncalibrated uncertainty | baseline only |
| scalar covariance inflation fit | adjusted radius | simple MVP candidate |
| isotonic calibration | monotonic mapping to empirical error quantile | strong candidate |
| conformal calibration | empirical coverage radius | strong where assumptions/data allow |
| neural uncertainty head | direct learned uncertainty | optional advanced |

### Recommendation

**MVP:** start with filter covariance labelled `unvalidated`; promote to `calibrated` only after a lightweight empirical calibration method such as isotonic/scale/conformal validation succeeds.

No neural uncertainty model for MVP.

---

## 9. Map Matching

### Candidates

- nearest-road snap,
- heading-aware candidate scoring,
- HMM/Viterbi,
- neural map matcher.

### Recommendation

**MVP baseline:** heading/uncertainty-aware classical candidate scoring.

**Preferred final classical method:** online HMM/Viterbi if time/road-graph quality permits.

**No ML map matcher** for the prototype.

Reason:
- road topology already provides structured information,
- learned matcher adds data requirements with little SIH value.

---

## 10. Route Progress / Off-Route Detection

### Candidates

- geometric projection to route,
- map-matched edge progression,
- thresholded off-route distance,
- learned route-intent model.

### Recommendation

If routing is built, use **classical geometry and graph logic**.

No ML is justified.

---

## 11. Confidence / Failure Health Model

Optional future problem:

```text
is current navigation estimate likely outside its stated uncertainty?
```

Candidates:
- calibrated residual thresholds,
- One-Class/Isolation Forest,
- small supervised classifier.

### Recommendation

**Future only.**

A health model should not be added before uncertainty itself is validated.

---

# Summary Table

| Learning/algorithm task | MVP | Alternative | Optional Advanced |
|---|---|---|---|
| Speed/vibration | **causal 1D CNN/TCN** | GRU / MLP | CNN+GRU |
| Calibration | **analytic geometry** | robust optimization | learned orientation research |
| Bias | **classical estimator** | adaptive state | learned bias model |
| Fusion | **classical EKF + rules** | small MLP later | causal TCN residual model |
| GNSS quality | **rules + innovation gate** | lightweight classifier later | anomaly model |
| Remount | **threshold/change-point** | Logistic/tree | temporal model |
| Motion state | **rules** | Logistic/RF | small TCN |
| Uncertainty | **empirical calibration of filter uncertainty** | conformal/isotonic variant | neural head |
| Map matching | **classical candidate/HMM** | fuller Viterbi | learned matcher |
| Routing | **classical graph** | — | learned routing not justified |

---

# Build Order

1. analytic calibration,
2. classical INS,
3. classical GNSS/INS EKF,
4. speed/vibration TCN with ablation,
5. NHC,
6. classical map matching,
7. fusion residual model only if there is time and evidence,
8. uncertainty calibration.

Do not train task 4+ before the corresponding target and physical inputs are valid.

---

# Mobile Deployment Recommendation

For neural models:

- prefer fixed, small causal windows,
- float32 first,
- benchmark before quantization,
- TFLite for Android,
- ONNX export for edge where feasible,
- verify exported outputs against training framework.

Quantization is an optimization step, not an accuracy fix.

---

**No training is performed in this document. Model recommendations become active only when their data/readiness gates are satisfied.**
