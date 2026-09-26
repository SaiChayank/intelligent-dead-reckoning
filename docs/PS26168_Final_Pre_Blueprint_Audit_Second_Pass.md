# PS26168 — Final Pre-Blueprint Audit (Second Pass)

**Scope:** Audit only. This re-runs readiness after dedicated output architecture, application architecture, capability classification, canonical data model, data requirements, dataset portfolio, feature engineering, and SIH demo documents have now been made explicit.

This pass may close **documentation/design gaps**, but it does not convert unimplemented algorithms or unresolved data semantics into solved engineering.

---

# Six-Perspective Summary

1. **Problem-outcome compliance:** traceability is clearer, but real navigation output still does not exist.
2. **Technical correctness:** contract/lifecycle/storage/map boundaries are now well defined. The physical-frame/INS issue remains unresolved.
3. **End-to-end integration:** current app subsystems are integrated; the planned `acquisition → NavigationRuntime → NavigationEngine → NavigationPresentation → MapLibre` seam is explicit but not implemented.
4. **ML scientific validity:** leakage rules, causality, splits, and reference/input boundaries are explicit, but remain conditional on resolving source semantics.
5. **Hackathon feasibility:** improved by deferring backend/cloud/background/iOS/end-to-end-neural/routing work from the critical path.
6. **Demonstrability:** a target demo and fallback ladder now exist. Current honest demo scope remains sensing/recording/replay/offline map/synthetic presentation.

---

# A. Contradictions / Rulings

| # | Finding | Ruling | Severity |
|---|---|---|---|
| A1 | Historical raw INS report can be read as permission to proceed directly to AI despite later frame evidence. | **Ruled:** later frame/foundation evidence is authoritative. Historical run is a reproducibility artifact, not an approved physical baseline. | RESOLVED |
| A2 | Map UI wants localization mode but v1 lacks it. | **Ruled:** do not overload v1. Add a versioned field only when the real state machine exists and Python/Kotlin codecs/tests change together. | MEDIUM, design resolved |
| A3 | Older architecture ideas may mention Google Maps/osmdroid while current implementation uses offline MapLibre. | **Ruled:** current verified MapLibre implementation is authoritative. | RESOLVED |
| A4 | Older planning may imply a backend-style architecture. | **Ruled:** the current on-device modular Android architecture is authoritative; no backend is needed for MVP. | RESOLVED |

---

# B. Missing Components

| # | Finding | Severity |
|---|---|---|
| B1 | Real `NavigationEngine` implementation | **BLOCKER** |
| B2 | Validated phone→vehicle calibration | **BLOCKER** |
| B3 | Physically credible classical INS | **BLOCKER** |
| B4 | GNSS/INS fusion and recovery | **BLOCKER** |
| B5 | Validated AI correction model | HIGH |
| B6 | Ground-truthed Android drive/evaluation set | **BLOCKER** |
| B7 | Map matching | HIGH |
| B8 | Edge FOG engine | HIGH / stage-dependent |
| B9 | Python exported-session convenience/validation layer | MEDIUM |

---

# C. Unnecessary Complexity

**Closed by design decisions:**
- no mobile microservices,
- no FastAPI/Redis for app-local communication,
- no cloud inference,
- no routing on the immediate critical path,
- no end-to-end neural inertial model for MVP,
- no background service,
- no multi-region map manager before core navigation.

Remaining caution:
- do not let full HMM matching substitute for fixing the estimator.

---

# D. High-Risk Dependencies

| # | Finding | Severity |
|---|---|---|
| D1 | Valid training depends on a physically justified IO-VNBD source mapping or replacement ground-truthed data. | **BLOCKER** |
| D2 | `<10% drift` depends on independent reference over representative outages. | **BLOCKER** |
| D3 | AI benefit depends on an identical classical baseline/ablation. | HIGH |
| D4 | Confidence calibration depends on enough independent error samples across GNSS/DR/recovery. | HIGH |
| D5 | Edge acceptance depends on actual FOG data/runtime. | HIGH |

---

# E. Missing Tests

The test plan is now explicit, but still unexecuted because modules are missing:
- synthetic mechanization suite,
- calibration physical acceptance,
- masked-GNSS held-out drive,
- innovation gating/recovery,
- raw-vs-AI ablation,
- confidence empirical coverage,
- engine-to-map integration,
- 10 Hz mobile navigation benchmark,
- 200 Hz edge benchmark.

**Status:** design gap largely closed; execution gap remains.

---

# F. Demo Readiness

### Ready today
- offline map startup,
- real sensor/GNSS acquisition,
- recording,
- export,
- replay,
- source/timestamp evidence,
- synthetic map demo with explicit labels.

### Not ready today
- real calibration,
- real DR map motion,
- AI ablation,
- measured outage drift,
- map matching,
- real recovery,
- edge demonstration.

---

# G. Traceability

| Target | Design path | Current evidence |
|---|---|---|
| GNSS-denied continuity | calibration → INS → AI → fusion → constraints → map match | design only |
| <10% drift | ground-truthed outage evaluation | no passing result |
| ~10 Hz mobile | NavigationEngine output benchmark | acquisition rates known; nav output absent |
| ~200 Hz edge | edge/FOG runtime | not implemented |
| offline operation | local inference + MapLibre/MBTiles | foundation verified |
| no OBD/CAN | phone sensor contract | architecture satisfies |
| seamless recovery | fusion gating + presentation | not implemented |
| AI enhancement | leakage-safe model + ablation | not implemented |

---

# H. Features Removed From Immediate Critical Path

Confirmed:
- routing,
- rerouting,
- turn-by-turn,
- cloud/fleet backend,
- background navigation,
- iOS,
- multi-region provisioning,
- end-to-end neural odometry,
- OTA.

---

# MASTER BLUEPRINT READINESS: STILL NOT READY TO FREEZE THE REAL NAVIGATION IMPLEMENTATION

The second pass closes documentation gaps but not empirical blockers.

Remaining blockers:
1. unresolved deployable inertial data semantics,
2. no validated calibration,
3. no credible classical INS,
4. no fusion/recovery implementation,
5. no ground-truthed Android outage result.

Therefore the correct decision remains **NOT READY** for treating the real navigation stack as frozen/approved.

---

# FINAL APPROVED DECISION REGISTER

## Scope
- Android smartphone is primary MVP.
- Edge/FOG is second deployment target.
- No OBD/CAN.
- Offline/local inference.
- MapLibre + local OSM/OpenMapTiles is authoritative current map stack.
- Routing remains optional until localization works.

## Contracts
- `contracts/v1` is canonical measurement/navigation boundary.
- `contracts/recording/v1` is canonical recording boundary.
- exact Int64 timestamps/source/session lineage preserved.
- missing values remain null.
- replay real→replay_real, simulation→replay_simulation.
- localization mode is not silently added to v1.

## Application
- one modular Android app,
- Kotlin/Compose,
- Flow/coroutines,
- no local backend/server,
- foreground-only current lifecycle,
- app-private recordings,
- explicit SAF export,
- offline MapLibre.

## Data
- IO-VNBD remains primary research/reference data.
- synchronized data is not automatically a physically valid raw-device stream.
- VBOX/reference is label/evaluation only.
- no random-row split.
- duplicates stay in one partition.
- future ground-truthed Android drive required.

## Feature Engineering
- causal only,
- bounded windows,
- no future GNSS/VBOX leakage,
- same deployable feature code for replay/training/live wherever possible,
- unresolved axes/units are not guessed.

## Navigation
- classical mechanization before AI,
- classical fusion before AI-fusion enhancement,
- AI requires ablation,
- NHC/map matching are downstream constraints,
- renderer never becomes localization.

## Testing
- synthetic mechanization suite mandatory,
- physical calibration acceptance mandatory,
- masked-GNSS held-out evaluation mandatory,
- drift/output-rate evidence mandatory.

## Demo
- synthetic map remains labelled,
- real replay preferred fallback for real-navigation demo,
- every numerical claim must point to recorded evidence.

---

# Exact Next Gate

```text
resolve physical input contract
        ↓
classical INS synthetic tests
        ↓
calibration
        ↓
real-data baseline
        ↓
GNSS/INS fusion + recovery
        ↓
ground-truthed outage evaluation
        ↓
first AI model + ablation
```

Once these close successfully, re-run this audit and only then freeze the navigation implementation baseline.

---

**Final second-pass verdict: documentation/design readiness improved substantially; real-navigation scientific readiness remains blocked.**
