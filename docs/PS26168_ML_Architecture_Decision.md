# PS26168 — ML Formulation & Architecture Decision

**Scope:** Compare six candidate learning-system architectures against the actual IDR problem and select one architecture for the prototype. This is the decision companion to the broader ML Architecture Formulation.

**Grounding constraint:** the navigation problem already contains well-understood physics. The question is not "which AI can replace navigation?" but "where can learning improve the parts low-cost MEMS sensors make unreliable without sacrificing causal, interpretable navigation?"

---

## 1. Candidate Architectures — Definitions

1. **End-to-end learned navigation** — raw/canonical sensor sequence directly predicts position/velocity/heading.
2. **Specialized learned modules** — independent models for speed/noise, residual correction, uncertainty, etc.
3. **Hierarchical learned navigation** — a learned regime/classification stage selects downstream models or behaviors.
4. **Physics-first + learned correction hybrid** — classical calibration/INS/fusion stays primary; AI corrects defined weaknesses.
5. **Learned ensemble** — multiple learned estimators are combined.
6. **Classical/learned navigation + anomaly/health path** — navigation runs normally while a separate model detects abnormal sensor/mounting conditions.

---

## 2. Evaluation

### 2.1 Navigation Quality

| Architecture | Assessment |
|---|---|
| End-to-end | High theoretical ceiling, but greatest risk of dataset memorization and poor cross-device/route generalization |
| Specialized modules | Strong — targets can be individually supervised and validated |
| Hierarchical | Potentially strong, but quality is limited by early-stage routing/regime errors |
| **Physics + learned correction** | **Strongest practical fit** — physical propagation plus learned compensation for sensor weaknesses |
| Ensemble | Potential gains if constituent errors are genuinely diverse; otherwise marginal |
| Navigation + anomaly | Helpful for robustness, but does not itself solve drift |

### 2.2 Training Complexity

| Architecture | Assessment |
|---|---|
| End-to-end | Very high |
| Specialized | Moderate |
| Hierarchical | High |
| **Physics + learned correction** | **Moderate and staged** |
| Ensemble | High |
| Navigation + anomaly | High if anomaly data is scarce |

### 2.3 Inference Latency

| Architecture | Assessment |
|---|---|
| End-to-end | Moderate/high depending on sequence model |
| Specialized | Low if only applicable modules execute |
| Hierarchical | Moderate |
| **Physics + learned correction** | **Low–moderate, controllable** |
| Ensemble | Highest |
| Navigation + anomaly | Additional parallel cost |

For the mobile target, a model that marginally improves accuracy but breaks the ~10 Hz navigation loop is not acceptable.

### 2.4 Explainability / Scientific Auditability

| Architecture | Assessment |
|---|---|
| End-to-end | Weak |
| Specialized | Strong |
| Hierarchical | Moderate |
| **Physics + learned correction** | **Strongest** — raw INS, fusion, and AI contribution can be plotted separately |
| Ensemble | Moderate |
| Navigation + anomaly | Moderate |

### 2.5 Data / Domain Shift

| Architecture | Assessment |
|---|---|
| End-to-end | Worst sensitivity |
| Specialized | Better — each target can use narrowly valid data |
| Hierarchical | Moderate-high sensitivity |
| **Physics + learned correction** | **Best practical robustness** because physical structure handles unseen conditions |
| Ensemble | Does not remove shared domain shift |
| Navigation + anomaly | Anomaly model itself may be very domain-sensitive |

### 2.6 Extensibility

| Architecture | Assessment |
|---|---|
| End-to-end | Weak — one artifact encodes everything |
| Specialized | Strong |
| Hierarchical | Moderate |
| **Physics + learned correction** | **Strong** |
| Ensemble | Moderate |
| Navigation + anomaly | Strong as an adjunct |

### 2.7 Hackathon Feasibility

| Architecture | Assessment |
|---|---|
| End-to-end | Weak |
| Specialized | Good |
| Hierarchical | Moderate |
| **Physics + learned correction** | **Best** |
| Ensemble | Weak |
| Navigation + anomaly | Optional/stretch |

---

## 3. Summary Comparison Table

| Architecture | Quality fit | Complexity | Latency | Explainability | Data robustness | Feasibility |
|---|---:|---:|---:|---:|---:|---:|
| End-to-end learned | medium | very high | medium | low | low | low |
| Specialized modules | high | medium | high | high | medium-high | high |
| Hierarchical | medium-high | high | medium | medium | medium | medium |
| **Physics + learned correction** | **high** | **medium** | **high** | **high** | **high** | **highest** |
| Ensemble | medium-high | high | low | medium | medium | low |
| Navigation + anomaly | support-only | high | medium | medium | medium | optional |

---

## 4. Recommendation

### Selected Architecture

**Option 4 — Physics-first + learned correction hybrid, implemented using specialized models from Option 2 where learning is actually justified.**

This means the prototype is not "AI replacing INS." It is:

```text
calibration
   ↓
classical INS
   ↓
GNSS/INS fusion
   ↓
constraints / map matching
```

with learning at explicit seams such as:

```text
IMU window ──► speed/vibration correction
EKF state/residual ──► residual/noise correction
navigation state ──► uncertainty calibration
```

### Why this wins

1. **Matches the data reality.** The project does not currently have the breadth of ground-truthed data needed for a robust end-to-end navigator.
2. **Preserves physics.** Coordinate transforms, attitude integration, and GNSS measurement geometry remain explicit and testable.
3. **Allows honest ablation.** Classical and AI-enhanced outputs can be compared on the same outage.
4. **Runs locally.** Small models are compatible with Android and ONNX/TFLite.
5. **Degrades gracefully.** If an AI artifact fails integrity/load checks, the classical path can still run.
6. **Extends to edge.** The same model contracts can later run at higher rate/compute budgets.
7. **Avoids scope traps.** No ensemble, giant transformer, or learned map matching is required to prove the main idea.

---

## 5. Placement Decision

### Model A — Speed / vibration correction

Runs before or alongside mechanization.

Input:
- causal calibrated IMU window,
- motion-state context,
- optional previous predicted speed if recurrence is explicit.

Output:
- forward-speed estimate and/or correction,
- optional confidence.

### Model B — Fusion residual / adaptive-noise correction

**Deferred until classical EKF passes.**

Input:
- causal EKF residual/state/quality summary.

Output:
- bounded residual correction or measurement/process noise adjustment.

### Model C — Uncertainty calibration

**Deferred until enough independent error data exists.**

Input:
- covariance/outage/motion/calibration context.

Output:
- calibrated horizontal uncertainty.

---

## 6. Rejected Architecture Decisions

### No end-to-end position network for MVP
Reason: weak physical auditability and high domain-shift risk.

### No ensemble for MVP
Reason: extra training/runtime cost without a demonstrated single-model limitation yet.

### No learned map matcher initially
Use classical geometry/HMM logic first.

### No online learning
A navigation model changing itself during a judged drive would make reproducibility and safety worse.

---

## 7. Required Evidence Before Calling the Architecture Successful

- validated physical input mapping,
- classical baseline,
- held-out outage evaluation,
- AI-vs-classical ablation,
- model export round-trip check,
- on-device latency benchmark,
- no future/reference leakage,
- generalization to at least one held-out route/condition,
- model artifact hash/version traceability.

---

**Final architecture decision: physics-first, specialized learning where measured value exists; no end-to-end learned navigation for the prototype.**
