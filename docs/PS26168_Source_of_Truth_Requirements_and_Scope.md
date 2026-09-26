# PS26168 — Source of Truth (Requirements & Scope Only)
**AI-ML based Intelligent Dead Reckoning system for seamless navigation — ISRO / Department of Space**

**Purpose of this file:** establish the authoritative requirement and scope baseline for SIH PS26168 without redesigning the project. The PS26145 reference is used only for document structure and classification discipline.

## Sources Consulted

- **[SIH]** Official SIH problem statement for PS26168 previously supplied in the project context — authoritative for official objective, expected solution, performance benchmark, dataset guidance and final deliverables.
- **[REPO]** Current repository `SaiChayank/intelligent-dead-reckoning`, `main @ da84645` — implementation evidence only; repository behavior does not redefine SIH requirements.
- **[PROJECT-DOCS]** Existing project documentation, Master Blueprint, Planning & Implementation Plan and frozen decisions — team design decisions derived from the official problem.
- **[REFERENCE]** PS26145 documents supplied in this task — formatting/analysis reference only; they have no authority over PS26168.

### Classification Vocabulary

| Classification | Meaning |
|---|---|
| **OFFICIAL SIH** | Explicitly stated in the PS26168 problem statement / expected solution / benchmark |
| **DERIVED REQUIREMENT** | Needed to realize or evaluate an official requirement, but not explicitly mandated in that exact form |
| **TEAM DECISION** | Chosen implementation/design decision for this project |
| **OPTIONAL** | Enhancement that is useful but not required to satisfy the PS |
| **FUTURE / PRODUCTION** | Valid productization work beyond the first complete SIH prototype |
| **ASSUMPTION / OPEN** | Not established by the official text and not yet frozen by evidence |

No new navigation, ML, map-matching, routing or UI architecture is invented in this document.

---

## 1. Official Objective

**OFFICIAL SIH:** Build an **AI-ML based Intelligent Dead Reckoning and GNSS+INS fusion system** that keeps vehicle navigation continuous when GNSS/GPS/NavIC becomes unavailable or unreliable, using smartphone inertial sensors without relying on a physical connection to the vehicle's internal computer.

The intended system must:

- operate from a standalone smartphone's built-in inertial sensors,
- use AI/ML to handle noisy consumer-grade IMU measurements and predict useful vehicle kinematics/corrections,
- continue navigation during GNSS blackout,
- seamlessly transition back to GNSS-aided INS after recovery,
- use road-layout/map constraints to limit drift,
- run inference locally on the smartphone,
- and provide a second **edge-deployable software engine** able to work with external IMUs rather than being restricted to smartphone sensors alone.

---

## 2. Target Users / Operating Context

| Item | Classification |
|---|---|
| Smartphone-based road-navigation users who encounter tunnels, underpasses, multi-level parking, dense urban canyons, forested roads or other GNSS-poor environments | **OFFICIAL SIH context** |
| Logistics, ride-hailing, quick-commerce and emergency-response vehicle use cases | **OFFICIAL SIH context** |
| Older cars, commercial vehicles and two-wheelers without factory-connected high-end inertial navigation | **OFFICIAL SIH context** |
| Drivers / fleet operators consuming the final navigation result | **DERIVED REQUIREMENT** |
| Engineering/evaluation teams using the edge engine with higher-grade IMUs | **DERIVED REQUIREMENT** |
| A judge/researcher inspecting drift, fusion and AI evidence | **DERIVED REQUIREMENT** |

The problem statement describes use cases rather than a formal persona specification, so the last three rows are implementation/user-experience derivations.

---

## 3. Functional Requirements

| Requirement | Classification |
|---|---|
| Working mobile application for intelligent dead reckoning | **OFFICIAL SIH** |
| Edge-deployable software engine not limited to smartphone IMUs | **OFFICIAL SIH** |
| Automatic in-vehicle phone alignment/calibration for pitch, roll and yaw relative to driving direction | **OFFICIAL SIH** |
| AI speed & vibration filtering from smartphone IMU signals | **OFFICIAL SIH** |
| Estimate forward vehicle velocity without OBD-II / external speedometer dependency | **OFFICIAL SIH** |
| Filter/handle engine vibration, potholes, bumps and accidental phone misalignment | **OFFICIAL SIH** |
| Dead-reckoning navigation during GNSS outage | **OFFICIAL SIH** |
| GNSS+INS fusion when GNSS is available | **OFFICIAL SIH** |
| AI/ML contribution to the GNSS+INS fusion approach | **OFFICIAL SIH** |
| Seamless GNSS-aided → DR → GNSS-aided transition | **OFFICIAL SIH** |
| Advanced map matching against an offline road database such as OSM | **OFFICIAL SIH** |
| Kinematic constraints / non-holonomic constraints to keep vehicle motion physically/road plausible | **OFFICIAL SIH** |
| Functional real-time navigation UI with smooth, uninterrupted vehicle position display | **OFFICIAL SIH** |
| Train models offline before deployment; run inference on-device | **OFFICIAL SIH** |
| Use IO-VNBD to train/test preliminary models and produce position plots for screening | **OFFICIAL SIH** |
| Record real phone data locally for repeatable debugging/evaluation | **DERIVED REQUIREMENT / TEAM DECISION** |
| Replay recorded sessions through the same canonical pipeline | **DERIVED REQUIREMENT / TEAM DECISION** |
| Expose navigation confidence/uncertainty and GNSS quality | **DERIVED REQUIREMENT / TEAM DECISION** |
| Offline local map rendering with no mandatory runtime network | **DERIVED REQUIREMENT / TEAM DECISION**, consistent with official offline-map guidance |
| Planned-route display / route progress | **OPTIONAL** |
| Offline routing / rerouting | **OPTIONAL** |
| Turn-by-turn guidance | **OPTIONAL** |

---

## 4. Non-Functional / Performance Requirements

| Requirement | Classification |
|---|---|
| Dead-reckoning drift **< 10% of distance travelled** during GNSS blackout | **OFFICIAL SIH** |
| Smartphone position update rate around **10 Hz** | **OFFICIAL SIH** |
| Edge engine higher-rate operation using FOG IMU data, around **200 Hz** | **OFFICIAL SIH** |
| Lightweight / edge-deployable inference | **OFFICIAL SIH** |
| Seamless transition within a very short interval ("within milliseconds" in the expected-solution framing) | **OFFICIAL SIH** |
| No mandatory cloud inference at runtime | **OFFICIAL SIH / directly implied by on-device execution** |
| Bounded memory / no unbounded queues | **DERIVED REQUIREMENT** |
| Deterministic replay/evaluation of recorded sessions | **DERIVED REQUIREMENT** |
| App remains usable without Internet once required maps/models are installed | **DERIVED REQUIREMENT / TEAM DECISION** |
| Battery/thermal behavior must be measured before production claims | **DERIVED REQUIREMENT** |
| Broad multi-device compatibility beyond tested hardware | **FUTURE / PRODUCTION** |

### Important performance interpretation

The official `<10%` benchmark is the primary quantitative dead-reckoning acceptance condition. Example distances in the PS illustrate that target; they do not replace the ratio itself.

The smartphone `~10 Hz` and edge `~200 Hz` numbers are **navigation/update-rate targets**, not merely raw sensor sampling-rate claims.

---

## 5. Architectural Constraints

### Official / problem-imposed

1. **No required OBD-II / vehicle-computer connection.**
2. **On-device smartphone inference** after offline/cloud/desktop training.
3. **Smartphone built-in IMU** is a primary deployment input.
4. **External IMU / edge deployment** must also be supported by the final algorithms/models.
5. **GNSS may be unavailable** for the exact operating period the system must bridge.
6. **Map matching / road constraints** are part of the expected solution.
7. **GNSS+INS fusion** is part of the expected solution.
8. **AI/ML** must contribute meaningfully rather than being a decorative label.

### Team-frozen constraints

| Item | Classification |
|---|---|
| Android/Kotlin/Jetpack Compose primary mobile platform | **TEAM DECISION** |
| Python offline research/training pipeline | **TEAM DECISION** |
| Strict cross-language `contracts/v1` measurement/navigation schema | **TEAM DECISION** |
| Recording contract in `contracts/recording/v1` | **TEAM DECISION** |
| Foreground-only acquisition for current prototype; no background service | **TEAM DECISION** |
| MapLibre + local OpenStreetMap/OpenMapTiles-derived vector pack | **TEAM DECISION** |
| No mandatory runtime Internet permission | **TEAM DECISION** |
| Physics-first navigation with specialized AI correction | **TEAM DECISION** |
| Classical baseline must exist before AI-enhanced claims | **TEAM DECISION** |
| Replay preserves source timestamps and source lineage | **TEAM DECISION** |

These team choices may evolve only through an explicit revision process; they are not retroactively labelled as official SIH mandates.

---

## 6. Required Capability Categories

The official expected solution identifies six core capability groups.

### 6.1 In-Vehicle Alignment & Calibration Engine
**OFFICIAL SIH**

Must determine phone orientation relative to the vehicle's driving direction even when the phone is not perfectly aligned.

### 6.2 AI Speed & Vibration Filter
**OFFICIAL SIH**

Must use a deep-learning or statistical approach locally to suppress non-navigation noise and estimate useful vehicle forward motion from noisy smartphone IMU signals.

### 6.3 Advanced Map Matching & Kinematic Constraints
**OFFICIAL SIH**

Must constrain the estimated path to plausible road/vehicle motion using map geometry and vehicle kinematics.

### 6.4 GNSS+INS Fusion Engine
**OFFICIAL SIH**

Must combine GNSS and IMU/INS state and meaningfully reduce drift/error.

### 6.5 Seamless GNSS Deficit Handler
**OFFICIAL SIH**

Must move between aided and unaided/dead-reckoning operation without visible navigation discontinuity.

### 6.6 Real-Time Navigation Interface
**OFFICIAL SIH**

Must display smooth continuous vehicle motion and make the final navigation result usable.

### Additional final deliverable
**OFFICIAL SIH:** edge-deployable software engine compatible with external IMU data.

---

## 7. Required Inputs

| Input | Classification |
|---|---|
| Smartphone accelerometer | **OFFICIAL SIH** |
| Smartphone gyroscope | **OFFICIAL SIH** |
| Smartphone magnetometer/compass | **OFFICIAL SIH** |
| GNSS when available | **OFFICIAL SIH** |
| External IMU / FOG data for edge engine | **OFFICIAL SIH** |
| Offline road/map database | **OFFICIAL SIH guidance / expected solution** |
| IO-VNBD data for preliminary model development and screening | **OFFICIAL SIH** |
| Vehicle OBD/CAN speed | **EXPLICITLY NOT REQUIRED / should not be a dependency** |
| Independent RTK/VBOX reference during team validation | **DERIVED REQUIREMENT** |
| Android gravity sensor | **TEAM DECISION / optional aiding for calibration/diagnostics** |

---

## 8. Required Outputs

| Output | Classification |
|---|---|
| Continuous vehicle position during GNSS availability and blackout | **OFFICIAL SIH** |
| Position and velocity from GNSS+INS fusion | **OFFICIAL SIH** |
| Smooth live vehicle marker/interface | **OFFICIAL SIH** |
| Road-constrained / map-matched navigation output | **OFFICIAL SIH** |
| Seamless recovery to GNSS-aided operation | **OFFICIAL SIH** |
| Preliminary position plots on IO-VNBD subset for screening | **OFFICIAL SIH** |
| Mobile app | **OFFICIAL SIH** |
| Edge-deployable navigation engine | **OFFICIAL SIH** |
| Heading/orientation output | **DERIVED REQUIREMENT** |
| GNSS quality state | **DERIVED REQUIREMENT / TEAM DECISION** |
| Navigation confidence/uncertainty | **DERIVED REQUIREMENT / TEAM DECISION** |
| Recorded JSONL session + metadata | **TEAM DECISION** |
| Replay diagnostics/evaluation metrics | **TEAM DECISION** |
| Offline route / ETA / turn-by-turn | **OPTIONAL** |

---

## 9. Official Dataset / Model-Development Guidance

**OFFICIAL SIH:**

- Use **IO-VNBD** for training/testing during screening.
- Preliminary AI models and position plots inferred from a subset of IO-VNBD are expected with the proposal.
- Additional datasets may be provided during later/finale evaluation.
- Teams may train using smartphone-collected data or other open-source datasets.
- Training can occur offline/cloud/desktop **a priori**.
- The trained lightweight model is then exported to the smartphone for on-device execution.
- Teams may bring downloaded offline map data such as OpenStreetMap.

### Current project evidence affecting use of IO-VNBD

**TEAM EVIDENCE, not official SIH text:**

- synchronized files exist and have been audited,
- smartphone GPS speed header appears mislabeled in examined data,
- timestamp gaps/repeated GPS values exist,
- inertial/export-frame semantics are not yet sufficiently resolved for blindly treating every CSV channel as raw Android device-frame IMU.

Therefore the dataset remains required/useful, but the project must not silently invent physical semantics just to satisfy a training schedule.

---

## 10. Resolved Team Decisions

These are **not official requirements**, but they are frozen current-project choices unless explicitly revised:

- Android app is the main product.
- Kotlin + Jetpack Compose UI.
- MapLibre renderer with bundled Hyderabad offline pack.
- App-private local recordings and explicit SAF export.
- Kotlin/Python strict v1 contract.
- Replay source mapping `real → replay_real`, `simulation → replay_simulation`.
- No background recording/navigation service in the current prototype.
- Map rendering is downstream of navigation; it does not implement localization.
- Current synthetic map demo is presentation-only.
- Classical mechanization/fusion is the baseline; AI must show incremental value.
- Routing and turn-by-turn remain optional.

---

## 11. Missing / Open Technical Decisions

Not yet fully frozen by implementation/evidence:

- exact production calibration algorithm and acceptance thresholds,
- exact corrected INS mechanization implementation,
- exact EKF/UKF/error-state formulation,
- exact AI speed/vibration model after data semantics close,
- whether a learned fusion-residual model is worth deploying,
- exact confidence calibration method,
- exact map-matching algorithm and road-graph representation,
- versioned production localization-mode field (`GNSS` / `DR` / `FUSED` / `RECOVERY`) — absent from current v1,
- edge hardware ingestion format,
- final FOG runtime hardware and ONNX deployment profile,
- whether optional offline routing/rerouting enters the final prototype.

---

## 12. Assumptions / Interpretive Risks

| Item | Classification / Resolution |
|---|---|
| "Lane-level accuracy" as a universal numeric pass bar | Official wording/goal, but the explicit measurable benchmark is `<10%` drift; do not invent a separate lane-width threshold without a defined evaluation |
| All IO-VNBD inertial columns are raw phone-frame channels | **REJECTED ASSUMPTION** by current evidence |
| Equal synchronized row counts prove exact sensor synchronization | **REJECTED ASSUMPTION** |
| Android provider accuracy equals fused 95% confidence | **REJECTED ASSUMPTION** |
| Offline map tiles are sufficient for routing/map matching | **REJECTED ASSUMPTION** — a routable/matchable road graph is separately required |
| Synthetic map DR/recovery proves estimator performance | **REJECTED ASSUMPTION** |
| 100 Hz phone IMU sampling proves 10 Hz navigation output | **REJECTED ASSUMPTION** |
| Accelerated replay proving 200 Hz means the edge engine meets 200 Hz | **REJECTED ASSUMPTION** |

---

## 13. Conflicts / Priority Rules

### Source priority

1. **Official SIH problem statement**
2. **Frozen project Source of Truth / approved baselines**
3. **Current repository evidence**
4. **Supporting/project documents**
5. **Reference videos / external inspiration**

### Current important rulings

- Current repository implementation evidence may supersede older README statements about what is implemented, but it does **not** supersede official requirements.
- Current verified MapLibre implementation supersedes older proposals for other map SDKs.
- Later frame-resolution evidence supersedes the interpretation that the historical raw INS run is a valid physical baseline.
- Synthetic map controls remain synthetic even if visually polished.
- Optional routing cannot be allowed to change the established localization architecture.

---

## 14. Optional Enhancements

- planned route overlay,
- offline route planning,
- offline re-routing,
- turn-by-turn guidance,
- route-progress visualization,
- multiple regional map packs,
- richer uncertainty visualizations,
- comparison/ablation trails,
- extended trip analytics,
- advanced online HMM map matching,
- multi-device benchmark dashboard.

None is required to prove the core IDR objective unless separately promoted through an explicit team decision.

---

## 15. Future / Production Features

- background navigation with proper foreground-service/location design,
- broad Android device certification,
- secure signed model/map update system,
- encrypted long-term trip storage,
- fleet/cloud synchronization,
- remote monitoring,
- multi-region map/road graph management,
- production edge hardware packaging,
- automatic model rollback/anti-rollback,
- safety/security accreditation,
- iOS or other mobile platforms.

---

# Consolidated Requirement Matrix

| # | Item | Classification |
|---:|---|---|
| 1 | AI-ML Intelligent Dead Reckoning during GNSS outage | OFFICIAL SIH |
| 2 | Mobile application | OFFICIAL SIH |
| 3 | Edge-deployable engine | OFFICIAL SIH |
| 4 | Smartphone accelerometer/gyro/magnetometer + GNSS | OFFICIAL SIH |
| 5 | No OBD-II dependency | OFFICIAL SIH |
| 6 | Automatic phone-to-vehicle alignment/calibration | OFFICIAL SIH |
| 7 | AI speed/vibration filter | OFFICIAL SIH |
| 8 | Map matching + kinematic constraints | OFFICIAL SIH |
| 9 | AI-enhanced GNSS+INS fusion | OFFICIAL SIH |
| 10 | Seamless GNSS deficit/recovery handling | OFFICIAL SIH |
| 11 | Smooth real-time navigation interface | OFFICIAL SIH |
| 12 | IO-VNBD preliminary model/testing requirement | OFFICIAL SIH |
| 13 | On-device inference after offline training | OFFICIAL SIH |
| 14 | Offline map data may be pre-downloaded | OFFICIAL SIH guidance |
| 15 | Dead-reckoning drift <10% of distance travelled | OFFICIAL SIH |
| 16 | Smartphone position update ~10 Hz | OFFICIAL SIH |
| 17 | Edge/FOG update around 200 Hz | OFFICIAL SIH |
| 18 | Confidence/uncertainty output | DERIVED / TEAM DECISION |
| 19 | Recording/export/replay | DERIVED / TEAM DECISION |
| 20 | Android/Kotlin/Compose | TEAM DECISION |
| 21 | MapLibre + Hyderabad pack | TEAM DECISION |
| 22 | Strict v1 cross-language contract | TEAM DECISION |
| 23 | Foreground-only current lifecycle | TEAM DECISION |
| 24 | Physics-first + specialized AI correction | TEAM DECISION |
| 25 | Offline routing/rerouting/turn-by-turn | OPTIONAL |
| 26 | Background navigation/cloud/fleet services | FUTURE / PRODUCTION |

---

**This document is the requirements-and-scope baseline. It does not claim that calibration, real INS, AI inference, fusion, map matching, GNSS recovery or the edge engine are already implemented.**
