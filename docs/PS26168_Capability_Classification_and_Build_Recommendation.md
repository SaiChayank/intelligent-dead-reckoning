# PS26168 — Capability Classification & Build Recommendation

**Scope:** Classify every major capability already decided or present in the Intelligent Dead Reckoning project into five delivery tiers, evaluate the highest-impact advanced ideas, and end with an explicit build/defer recommendation for the first complete SIH prototype.

**Tiers used throughout:**
1. **OFFICIAL / PROBLEM-OUTCOME REQUIREMENT** — stated or directly implied by the SIH problem outcome.
2. **REQUIRED FOR WORKING MVP** — not necessarily named verbatim, but the system cannot credibly satisfy or demonstrate the problem without it.
3. **HIGH-VALUE HACKATHON DIFFERENTIATOR** — not mandatory, but disproportionately improves the submission relative to effort.
4. **OPTIONAL ADVANCED** — valuable, but can reasonably be deferred.
5. **FUTURE / PRODUCTION** — real engineering value, but wrong scope for the first SIH prototype.

---

## Part 1 — Classification of Everything Already Decided

| Capability | Tier | Current status / rationale |
|---|---:|---|
| GNSS-outage-resilient vehicle positioning | 1 | Core problem outcome |
| Drift benchmark <10% of distance travelled during outage | 1 | Hard project target; must be measured, not inferred |
| ~10 Hz smartphone navigation output | 1 | Hard mobile target |
| ~200 Hz edge/FOG engine target | 1 | Edge deployment target |
| Smartphone IMU + GNSS input without OBD/CAN | 1 | Core deployment constraint |
| Seamless GNSS available → DR → recovery behavior | 1 | Core continuity requirement |
| Offline/local inference | 2 | Required to preserve operation during GNSS/network-denied scenarios and current project architecture |
| Phone-to-vehicle alignment/calibration | 2 | Required before device-frame inertial data can become vehicle-frame motion |
| Classical INS mechanization | 2 | Required baseline and physical navigation core |
| GNSS/INS fusion | 2 | Required for seamless aiding/recovery |
| AI speed/vibration or correction model | 2 | Required to justify the AI-enhanced IDR approach |
| Confidence/uncertainty output | 2 | Required for honest degraded-mode presentation |
| GNSS quality state machine | 2 | Required to decide when GNSS may influence fusion |
| Vehicle non-holonomic constraints | 2 | Strongly justified drift-control mechanism |
| Map matching | 2 | Required in the approved final architecture as drift control |
| Android real sensor/GNSS acquisition | 2 | Implemented and physically verified |
| Versioned cross-language data contract | 2 | Implemented; required for consistency |
| Bounded recording + recovery | 2 | Implemented; required for evidence/evaluation |
| Local export | 2 | Implemented |
| Local replay | 2 | Implemented |
| Offline map rendering | 2 | Implemented |
| Real engine-driven live map | 2 | Not yet implemented |
| Synthetic GNSS/DR/recovery map demo | 3 | Implemented; presentation aid only |
| Smooth marker/camera animation | 3 | High-value presentation polish |
| Visible confidence/uncertainty radius | 3 | Strong demonstrability if evidence-backed |
| Route overlay / route progress | 3 | Valuable navigation feel, separate from positioning |
| Offline routing | 4 | Useful enhancement, not needed to prove DR |
| Offline rerouting | 4 | Depends on routing graph/progress |
| Turn-by-turn guidance | 4 | Product feature |
| Full HMM/Viterbi map matcher | 4 | Final-quality approach; higher effort |
| Multi-region downloadable map packs | 4 | Useful after Hyderabad prototype |
| Advanced AI adaptive covariance | 4 | Valuable only after stable EKF |
| End-to-end neural inertial odometry replacing INS | 5 | Too risky/complex for first prototype |
| Background navigation service | 5 | New lifecycle/permission/power scope |
| Cloud inference | 5 | Conflicts with local/offline objective |
| Fleet backend / user accounts | 5 | Not needed for problem proof |
| iOS application | 5 | Out of current scope |
| Production OTA model/map delivery | 5 | Production provisioning |

---

## Part 2 — Detailed Evaluation of High-Impact Advanced Ideas

### AI Speed / Vibration Filter
- **VALUE:** Very high; addresses MEMS vibration/noise and lack of OBD speed.
- **EFFORT:** Medium-high.
- **RISK:** High until IO-VNBD frame/export semantics are resolved.
- **DEPENDENCIES:** verified inputs, valid labels, sequence-group split, reproducible preprocessing.
- **DEMO IMPACT:** High if raw-vs-AI results visibly improve.
- **Tier:** 2.

### AI-Aided Fusion Correction
- **VALUE:** High after a classical EKF works.
- **EFFORT:** High.
- **RISK:** High if attempted before a credible baseline.
- **DEPENDENCIES:** stable EKF, clean residual targets, leakage-free training.
- **DEMO IMPACT:** High only if ablation proves value.
- **Tier:** 4 initially.

### Calibrated Uncertainty
- **VALUE:** High.
- **EFFORT:** Medium.
- **RISK:** Medium; covariance is not automatically calibrated accuracy.
- **DEPENDENCIES:** functioning filter and independent ground truth.
- **DEMO IMPACT:** High.
- **Tier:** 2 for uncertainty output; empirical calibration can mature later.

### Non-Holonomic Constraints
- **VALUE:** High for road vehicles.
- **EFFORT:** Low-medium.
- **RISK:** Medium if blindly applied to motorcycles/skids/parking/remount errors.
- **DEPENDENCIES:** valid vehicle frame and motion state.
- **DEMO IMPACT:** Medium.
- **Tier:** 2.

### HMM/Viterbi Map Matching
- **VALUE:** High.
- **EFFORT:** Medium-high.
- **RISK:** Medium-high; can visually hide bad estimation.
- **DEPENDENCIES:** road graph, reliable position/heading/uncertainty.
- **DEMO IMPACT:** High.
- **Tier:** 4 for full implementation.

### Offline Routing
- **VALUE:** Medium-high.
- **EFFORT:** Medium-high.
- **RISK:** Medium schedule risk.
- **DEPENDENCIES:** routable graph, route planner/progress.
- **DEMO IMPACT:** High visually.
- **Tier:** 4.

### GNSS-Recovery Smoothing
- **VALUE:** High.
- **EFFORT:** Low-medium if fusion is correct.
- **RISK:** High if it hides estimator jumps rather than fixing them.
- **DEPENDENCIES:** real fusion innovation/recovery logic.
- **DEMO IMPACT:** High.
- **Tier:** 2.

### Edge FOG Engine
- **VALUE:** High.
- **EFFORT:** High.
- **RISK:** High before shared-core semantics stabilize.
- **DEPENDENCIES:** frozen contract, rate-agnostic core, ONNX, FOG spec.
- **DEMO IMPACT:** High at finale.
- **Tier:** 1/2 depending on competition stage.

### Background Navigation
- **VALUE:** Product-relevant.
- **EFFORT:** Medium.
- **RISK:** High permission/battery complexity.
- **DEMO IMPACT:** Low.
- **Tier:** 5.

### Cloud / Fleet Dashboard
- **VALUE:** Production/fleet value.
- **EFFORT:** High.
- **RISK:** Scope/privacy/network expansion.
- **DEMO IMPACT:** Low relative to core DR proof.
- **Tier:** 5.

### End-to-End Neural Inertial Odometry
- **VALUE:** Research-interesting.
- **EFFORT:** Very high.
- **RISK:** Very high generalization/explainability risk.
- **DEPENDENCIES:** much larger diverse ground-truthed data.
- **Tier:** 5.

---

## Part 3 — What to Build

1. Preserve verified acquisition, recording, export, replay, and offline-map foundations.
2. Close IO-VNBD frame/export semantics before deployable inertial training.
3. Implement physically correct classical INS with synthetic tests.
4. Implement phone-to-vehicle calibration.
5. Implement GNSS-quality policy and classical GNSS/INS fusion.
6. Add first AI correction only where measured ablation proves value.
7. Add non-holonomic constraints.
8. Connect canonical navigation output to existing `NavigationPresentation`/MapLibre.
9. Implement GNSS-denial/recovery without presentation-only deception.
10. Add map matching after raw fused trajectory is credible.
11. Measure drift, velocity error, output rate, latency, memory/queues.
12. Build edge engine after mobile/shared-core semantics stabilize.

### Build if time remains
- offline routing,
- route progress,
- rerouting,
- richer uncertainty UI,
- multiple map packs,
- fuller HMM matcher,
- broader Android device coverage.

### Do not build for first complete prototype
- cloud inference,
- fleet backend,
- user accounts,
- microservices,
- background navigation,
- iOS,
- OTA infrastructure,
- end-to-end neural odometry,
- a second localization pipeline inside the map,
- routing before localization works.

---

## Part 4 — Strategic Calls

### 1. Do not train around an unresolved frame problem
Current IO-VNBD evidence does not justify treating exported accelerometer/gravity/orientation as a universal raw Android device-frame stream.

**Decision:** physical source semantics are a blocker for deployable inertial-model claims.

### 2. The current map is real as a renderer, synthetic as navigation
MapLibre/offline rendering is physically verified. GNSS/DR/recovery motion currently shown is scripted.

**Decision:** keep it as a demo fixture; connect real navigation only through canonical engine output.

### 3. Classical fusion before AI fusion
AI should improve a physically interpretable baseline.

**Decision:** INS/EKF first, AI ablation second.

### 4. Routing is not dead reckoning
Routing improves product feel but does not create position.

**Decision:** defer until localization is credible.

---

## Final Recommended MVP

```text
Verified Android sensing
        ↓
phone-to-vehicle calibration
        ↓
validated preprocessing
        ↓
classical INS
        ↓
AI speed/noise correction
        ↓
GNSS/INS fusion
        ↓
vehicle constraints
        ↓
optional first-pass map matching
        ↓
canonical NavigationState + quality + confidence
        ↓
existing offline MapLibre live map
        ↓
record / export / replay / evaluate
```

**MVP success is measured by evidence, not feature count.**
