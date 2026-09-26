# PS26168 — ML Architecture Formulation

**Scope:** Compare ways to organize machine learning relative to the classical inertial-navigation/fusion stack. This document chooses the *system-level learning architecture*, not exact model algorithms. Where a model family is named, it is illustrative unless explicitly carried into the separate model-selection document.

**Grounding facts:**

- The project is not one homogeneous prediction problem. Speed estimation, vibration filtering, calibration quality, fusion residual correction, uncertainty calibration, and remount detection have different targets and time scales.
- Classical navigation physics already provides a strong structural prior: coordinate transforms, strapdown mechanization, state propagation, GNSS measurement updates, and vehicle constraints.
- The final mobile system must run locally at approximately 10 Hz output, and the later edge target must scale toward approximately 200 Hz.
- The AI contribution must remain explainable enough to support ablation against a classical baseline.
- Current data semantics are not yet sufficient to justify an end-to-end learned navigation system.

---

## 1. End-to-End Neural Navigation

One sequence model consumes raw/canonical IMU and optional GNSS context and directly predicts navigation position/velocity/heading.

### Assessment

| Criterion | Assessment |
|---|---|
| Navigation quality | Potentially strong with very large diverse datasets, but highly sensitive to train/test domain shift, phone placement, vehicle, route, and sensor hardware |
| Training complexity | Very high — long sequences, trajectory losses, frame consistency, drift losses, initialization, large data volume |
| Inference latency | Moderate-to-high depending on recurrent/transformer architecture |
| Explainability | Weak — difficult to separate whether an error came from frame handling, integration, bias, or learned shortcut |
| Physical consistency | Weak unless explicitly constrained; can violate kinematics or coordinate-frame invariants |
| Data demand | Highest |
| Extensibility | Poor if output/state definition changes; usually retraining needed |
| Hackathon feasibility | Weak |

### Main risk

A model can appear to "learn navigation" while actually learning:

- route identity,
- speed profile,
- mount orientation,
- dataset preprocessing quirks,
- GNSS/reference leakage.

**Conclusion:** unsuitable as the first IDR architecture.

---

## 2. Specialized Learning Modules

Separate models solve narrow tasks, for example:

```text
IMU window → speed/noise correction
fusion residuals → residual correction
filter state → uncertainty calibration
mounting signals → remount probability
```

### Assessment

| Criterion | Assessment |
|---|---|
| Navigation quality | Strong potential because each model targets one measurable weakness |
| Training complexity | Moderate — several smaller datasets/targets |
| Inference latency | Low-to-moderate per model; can skip modules when not needed |
| Explainability | Stronger — each model has one job and one target |
| Physical consistency | Good if model output is bounded and consumed by a classical estimator |
| Data demand | Moderate |
| Extensibility | Good — one model can be upgraded without replacing the whole navigator |
| Hackathon feasibility | Good |

### Main strength

A speed model can be evaluated by speed error; a residual model can be evaluated by navigation error; an uncertainty model can be evaluated by coverage. Failures remain attributable.

---

## 3. Hierarchical Learned Navigation

A staged learned system, e.g.:

```text
motion-state classifier
        ↓
specialized speed model
        ↓
learned outage/recovery model
        ↓
navigation output
```

### Assessment

| Criterion | Assessment |
|---|---|
| Quality | Potentially strong in clearly separated regimes |
| Training complexity | High — stage errors propagate |
| Latency | Moderate |
| Explainability | Moderate |
| Physical consistency | Depends on whether classical estimator remains in loop |
| Data demand | High |
| Extensibility | Moderate |
| Hackathon feasibility | Moderate-to-weak |

### Main risk

A wrong early regime decision can route the entire trajectory through the wrong learned path.

---

## 4. Physics-First + Learned Correction Hybrid

Classical navigation remains authoritative:

```text
IMU/GNSS
   ↓
physical normalization / calibration
   ↓
classical INS
   ↓
GNSS/INS fusion
   ↓
vehicle constraints / map matching
   ↓
navigation output
```

AI is inserted only where evidence shows it helps:

```text
IMU → learned speed/noise correction ─┐
                                     │
EKF residual/state → learned correction ─► classical navigation/fusion
                                     │
state/covariance → uncertainty calibrator
```

### Assessment

| Criterion | Assessment |
|---|---|
| Navigation quality | Strong fit because learned components attack known MEMS weaknesses while physics provides continuity |
| Training complexity | Moderate |
| Inference latency | Favorable if models are lightweight |
| Explainability | Strong — compare classical and AI-enhanced outputs directly |
| Physical consistency | Strongest of ML-using options |
| Data demand | Lower than full end-to-end learning |
| Extensibility | Strong |
| Hackathon feasibility | **Best** |

### Main strength

The system remains functional if one optional AI module is disabled, and the AI benefit can be measured honestly.

---

## 5. Ensemble of Multiple Learned Estimators

Multiple models independently predict speed, displacement, or correction and combine by voting/weighting/stacking.

### Assessment

| Criterion | Assessment |
|---|---|
| Quality | Can improve robustness if models have genuinely different errors |
| Training complexity | High |
| Inference latency | Highest among modular options |
| Explainability | Moderate-to-weak |
| Physical consistency | Depends on the combination layer |
| Data demand | High |
| Extensibility | Moderate |
| Hackathon feasibility | Weak |

### Main concern

Ensembling several weak, similarly biased models does not create a strong navigator; it mostly adds tuning and runtime cost.

---

## 6. Classical Navigation + Supervised Learning + Anomaly/Health Model

A classical/learned navigation path runs alongside a model intended to detect:

- sensor malfunction,
- remounting,
- out-of-distribution vibration,
- unusual uncertainty growth.

### Assessment

| Criterion | Assessment |
|---|---|
| Quality | Useful as a safety/health adjunct |
| Training complexity | High because "abnormal" coverage is hard to define |
| Latency | Moderate |
| Explainability | Moderate |
| Data demand | High for robust anomaly behavior |
| Hackathon feasibility | Optional only |

This path is useful later, but it should not be used to compensate for an unfinished core navigator.

---

# Comparison Summary

| Architecture | Physical correctness | Data efficiency | Explainability | Runtime fit | Extensibility | MVP fit |
|---|---|---|---|---|---|---|
| End-to-end neural navigation | low–medium | low | low | medium | low | weak |
| Specialized learning modules | high | medium-high | high | high | high | strong |
| Hierarchical learned navigation | medium | medium | medium | medium | medium | moderate |
| **Physics-first + learned correction** | **highest** | **high** | **high** | **high** | **high** | **best** |
| Learned ensemble | medium | low | medium | low-medium | medium | weak |
| Navigation + anomaly/health path | high for safety adjunct | medium | medium | medium | high | optional |

---

# Recommendation

## Recommended Architecture

**Physics-first + learned correction hybrid, using specialized learned modules only where the classical baseline demonstrates a measurable weakness.**

Target organization:

```text
REAL / REPLAY MEASUREMENTS
        ↓
validated physical normalization
        ↓
phone-to-vehicle calibration
        ↓
┌──────────────────────────────┐
│ lightweight AI speed/noise   │
│ correction                   │
└──────────────┬───────────────┘
               ↓
        classical INS
               ↓
        GNSS/INS fusion
               ↓
      optional learned residual
      correction/adaptive noise
               ↓
     non-holonomic constraints
               ↓
          map matching
               ↓
        NavigationState
               ↓
   uncertainty calibration model
```

### Rules carried forward

1. AI never receives unresolved/guessed physical axes.
2. VBOX/reference channels are labels, not inference inputs.
3. The system must have a classical baseline that runs without AI.
4. Every learned module requires an ablation against the identical classical path.
5. A model is skipped if its target cannot be justified causally.
6. No AI module may hide a frame/sign/timestamp bug.
7. Final inference stays on-device.
8. The map remains downstream of navigation.

---

# MVP AI Scope

### Primary learned module

**Speed / vibration correction** once physical inputs are validated.

Reason:
- directly relevant to low-cost phone IMU,
- easy to evaluate against reference speed,
- bounded sequence target,
- useful during GNSS denial,
- deployable as a small causal sequence model.

### Secondary learned module

**Fusion residual/noise correction**, only after the classical EKF is stable.

### Later

- uncertainty calibration,
- health/anomaly model,
- advanced adaptive fusion.

---

**No exact neural network or training hyperparameter is selected here.**
