# PS26168 — Final Pre-Blueprint Audit

**Scope:** Audit only. No new features or redesigns. This checks the current IDR project against six perspectives and eight finding categories, then issues a readiness verdict for freezing a real-navigation implementation blueprint.

**Important interpretation:** the project already has useful planning/blueprint documents. This audit asks a narrower question: **is the scientific/technical foundation ready to freeze the real NavigationEngine implementation as if the unresolved research questions are settled?**

---

# Six-Perspective Summary

1. **Problem-outcome compliance:** acquisition, offline operation, recording/replay, and map rendering have strong implementation paths. The core outcome—measured GNSS-outage navigation meeting the drift target—does not yet exist.
2. **Technical correctness:** Android acquisition/contract semantics are strong. IO-VNBD inertial/export semantics remain unresolved enough to block a defensible deployable strapdown/training interpretation.
3. **End-to-end integration:** acquisition→recording→export→replay and offline map paths are integrated. Acquisition→real NavigationEngine→map is not.
4. **ML scientific validity:** no deployable AI model is trained/validated yet; this is appropriate because the physical feature contract remains unresolved.
5. **Hackathon feasibility:** scope is manageable only if routing/cloud/product extras are deferred until the core navigation chain works.
6. **Demonstrability:** current demo can honestly show sensing, recording/replay, and offline synthetic map behavior, but not actual DR accuracy.

---

# A. Unresolved Contradictions

| # | Finding | Severity |
|---|---|---|
| A1 | Historical Phase-1 documentation describes the old INS run as a "classical pre-AI dead-reckoning baseline" and suggests proceeding to AI, while later foundation/frame investigations reject interpreting its huge error as ordinary drift because input frame/export semantics are not trustworthy. | HIGH |
| A2 | Older planning material can make AI speed and EKF look like the next straightforward step, while current evidence requires physical normalization first. | MEDIUM |
| A3 | The UI/map concept wants a visible `GNSS/DR/FUSED` mode, but v1 has no explicit localization-mode field. Synthetic labels are not production contract semantics. | MEDIUM |

---

# B. Missing Components

| # | Finding | Severity |
|---|---|---|
| B1 | No production `NavigationEngine` implementation. | **BLOCKER** |
| B2 | No validated phone-to-vehicle calibration engine. | **BLOCKER** |
| B3 | No corrected, physically credible classical INS baseline. | **BLOCKER** |
| B4 | No trained/deployed AI correction model. | HIGH |
| B5 | No GNSS/INS EKF/UKF implementation. | **BLOCKER** |
| B6 | No real GNSS→DR→recovery state machine. | **BLOCKER** |
| B7 | No map matcher. | HIGH |
| B8 | No ground-truthed Android driving dataset for final mobile drift validation. | **BLOCKER** |
| B9 | No edge/FOG runtime implementation. | HIGH / stage-dependent |
| B10 | No dedicated Python exported-session reader package equivalent to the Kotlin session layer. | MEDIUM |

---

# C. Unnecessary Complexity Risks

| # | Finding | Severity |
|---|---|---|
| C1 | Offline routing/re-routing/turn-by-turn before localization works would consume schedule without reducing the main risk. | MEDIUM |
| C2 | A deep end-to-end neural inertial model adds data/generalization risk before classical physics are validated. | HIGH |
| C3 | A cloud backend/microservices architecture would add unrelated complexity to an on-device problem. | MEDIUM |
| C4 | Full HMM/Viterbi matching before a credible fused trajectory risks using road snapping to hide estimator failure. | MEDIUM |

---

# D. High-Risk Dependencies

| # | Finding | Severity |
|---|---|---|
| D1 | IO-VNBD model training depends on correctly interpreting exported sensor frames/semantics. Current evidence does not support a universal raw-device interpretation. | **BLOCKER** |
| D2 | Final <10% drift evidence depends on independent ground truth during representative GNSS outages. Current live phone recordings do not provide that. | **BLOCKER** |
| D3 | Confidence-radius claims depend on empirical calibration against real error, not merely EKF covariance. | HIGH |
| D4 | Edge ~200 Hz acceptance depends on actual FOG input/runtime, not accelerated phone replay. | HIGH |

---

# E. Missing Tests

| # | Finding | Severity |
|---|---|---|
| E1 | Full synthetic INS suite—stationary, constant velocity, acceleration, turn, bias, irregular dt—has not yet become the acceptance gate for corrected mechanization. | **BLOCKER** |
| E2 | No end-to-end real `acquisition → navigation engine → map` test exists because the engine is absent. | HIGH |
| E3 | No masked-GNSS replay test compares final outage drift against ground truth with the deployable pipeline. | **BLOCKER** |
| E4 | No GNSS recovery innovation/convergence test. | HIGH |
| E5 | No calibrated uncertainty coverage test. | HIGH |
| E6 | No mobile navigation-output rate/latency benchmark. | HIGH |
| E7 | No edge 200 Hz benchmark. | HIGH / stage-dependent |

---

# F. Missing Demo Capabilities

| # | Finding | Severity |
|---|---|---|
| F1 | Current map GNSS/DR/recovery motion is synthetic. | HIGH |
| F2 | No measured real DR trajectory is displayed live. | **BLOCKER** for claiming final solution |
| F3 | No raw-vs-AI ablation on a scientifically approved held-out outage. | HIGH |
| F4 | No real recovery demonstration. | HIGH |
| F5 | No edge-engine demonstration. | HIGH / finale-dependent |

---

# G. Requirements Without Complete Traceability

| # | Finding | Severity |
|---|---|---|
| G1 | `<10% drift` has a target/formula but no passing current-system result. | **BLOCKER** |
| G2 | `~10 Hz mobile navigation output` has acquisition-rate evidence but no navigation-output benchmark. | HIGH |
| G3 | `~200 Hz edge` has architecture intent but no runtime evidence. | HIGH |
| G4 | "AI-enhanced" has architecture intent but no validated deployed AI contribution. | HIGH |

---

# H. Features That Should Be Removed From the Immediate MVP Critical Path

1. offline turn-by-turn,
2. rerouting,
3. multi-region map manager,
4. cloud/fleet backend,
5. background navigation,
6. iOS,
7. end-to-end neural inertial odometry,
8. production OTA,
9. advanced HMM matcher before raw fusion is credible.

---

# MASTER BLUEPRINT READINESS: NOT READY TO FREEZE THE REAL NAVIGATION IMPLEMENTATION

The project is **ready as a roadmap/foundation**, but **not ready to treat unresolved navigation decisions as a frozen implementation baseline**.

The blockers are scientific:
- physical input semantics,
- calibration,
- credible mechanization,
- fusion,
- independent ground-truth outage validation.

---

# Exact Corrections Required Before Navigation-Blueprint Freeze

## Correction 1 — Close D1: defensible inertial input contract
Do one of:
- validate a subset/transformation of IO-VNBD into a real physical frame,
- restrict training to signals whose semantics are demonstrably valid,
- or collect a new ground-truthed Android drive dataset for deployable training.

Do not guess a rotation.

## Correction 2 — Close B3/E1: classical INS synthetic tests
Required:
- stationary,
- constant velocity,
- known acceleration,
- constant-rate turn,
- gyro bias,
- accelerometer bias,
- irregular dt/gaps,
- proper rotation/quaternion checks.

Only then run real-data baselines.

## Correction 3 — Close B2: calibration
Implement and validate:
- static leveling,
- yaw/forward alignment,
- bias handling,
- remount invalidation.

## Correction 4 — Close B5/B6: classical fusion + recovery
Implement:
- GNSS measurement model,
- gating,
- outage propagation,
- recovery update,
- explicit quality/state behavior.

## Correction 5 — Close B8/G1: ground-truthed Android outage drive
Record real driving data with independent reference and defined outage segments.

## Correction 6 — AI only after baseline
Train the first AI correction on leakage-safe, physically valid inputs and compare against the identical classical baseline.

---

# Non-Blocking Existing Strengths

Preserve:
- verified Android sensing,
- exact timestamps/contracts,
- source separation,
- recording/recovery,
- export,
- replay,
- offline MapLibre,
- bounded queues,
- physical device evidence,
- synthetic map UX fixture,
- test discipline.

---

**Verdict: NOT READY for a frozen real-navigation implementation blueprint; READY to execute the blocker-closing research/implementation plan.**
