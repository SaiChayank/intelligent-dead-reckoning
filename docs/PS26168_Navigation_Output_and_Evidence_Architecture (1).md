# PS26168 — Navigation Output, Confidence & Evidence Architecture

**Scope:** The navigation engine output contract that downstream Android UI, offline map rendering, recording/replay, evaluation, and the future edge engine consume. This document defines output semantics, confidence/uncertainty, quality/status separation, evidence/provenance, ordering, stale-data behavior, and the exact boundary between the current v1 contract and future navigation-mode extensions. It does **not** redesign the INS/EKF/AI pipeline, implement map matching/routing, or define UI styling.

**Project state used:** current repository baseline after verified Android acquisition, recording/export/replay, offline MapLibre rendering, and the synthetic GNSS/DR/recovery map presentation. The real `NavigationEngine` remains an interface; production INS/AI/EKF/map-matching outputs are not yet implemented.

---

## 1. Field Classification

### OFFICIAL / PROBLEM-OUTCOME FIELDS

The problem statement does not prescribe a complete wire schema like PS26145 did for alerts. It does, however, require a navigation result that can continue through GNSS denial and be benchmarked against the stated drift/update-rate targets. Therefore the fields below are the **minimum externally meaningful outputs implied by the problem outcome**, not a claim that the SIH listing names these JSON keys verbatim.

| Output | Type | Meaning | Requirement basis |
|---|---|---|---|
| `position` | geographic position / local position convertible to WGS84 | Current estimated vehicle position, including during GNSS outage | Core dead-reckoning outcome |
| `timestamp` | monotonic time | Time represented by the navigation estimate | Required for real-time continuous navigation |
| `velocity` | vector / scalar speed | Estimated vehicle motion used to advance the solution | Required for dead reckoning and evaluation |
| `heading` | degrees [0, 360), nullable when unavailable | Direction of vehicle travel/orientation | Required for trajectory continuation and map presentation |
| `navigation_quality` | structured status | Whether a position is healthy, degraded, unavailable, or failed | Needed so a consumer does not treat all numeric positions as equally trustworthy |
| `uncertainty` | structured confidence/accuracy | Quantifies how trustworthy the estimate is | Needed to expose drift growth during denial and recovery |
| `update_rate` | measured runtime property | Effective output rate | Must be benchmarked toward ~10 Hz mobile and ~200 Hz edge |
| `outage_drift` | evaluation metric, not a live state field | Position error accumulated during a GNSS-denied segment | Must be benchmarked against the <10% distance-travelled target |

**Important:** `update_rate` and `outage_drift` are acceptance metrics. They are not repeated inside every navigation record unless a later telemetry schema explicitly chooses to expose them.

### CURRENT CONTRACT — REQUIRED NAVIGATION FIELDS

The repository's existing **navigation exchange contract v1.0.0** is the current authoritative wire format. A navigation record is carried inside the common `Record` envelope.

| Field | Type | Meaning | Why required |
|---|---|---|---|
| `contract_version` | string (`1.0.0`) | Contract version | Prevents silent interpretation drift |
| `session_id` | non-empty string | Acquisition/navigation session identity | Keeps records from different sessions separate |
| `source` | enum (`real`, `simulation`, `replay_real`, `replay_simulation`) | Provenance of the stream | Prevents replay/simulation from masquerading as live data |
| `event_id` | canonical decimal string | Session-unique event identity | Allows exact ordering/audit checks without floating-point conversion |
| `t_ns` | nonnegative Int64 encoded as decimal string on wire | Measurement/navigation time | Scientific integration time; must remain exact |
| `received_ns` | nonnegative Int64 encoded as decimal string on wire | Receipt/publication time | Supports latency and ordering diagnostics |
| `status` | enum | `uninitialized`, `calibrating`, `tracking`, `degraded`, `failed` | Separates numeric output from system health |
| `initialization_mode` | enum (`evaluation`, `deployable`) | Declares whether non-deployable reference assistance is permitted | Prevents evaluation shortcuts from being presented as deployable behavior |
| `origin_wgs84_deg_m` | WGS84 lat/lon/ellipsoidal-altitude object, nullable | Geographic origin for ENU position | Makes local navigation coordinates geographically interpretable |
| `position_enu_m` | 3D vector, nullable | East/North/Up position relative to origin | Canonical navigation position in the current contract |
| `velocity_enu_m_s` | 3D vector, nullable | ENU velocity | Required for state propagation and speed display |
| `q_enu_from_vehicle_wxyz` | unit quaternion, nullable | Vehicle orientation in ENU | Required for physically consistent orientation |
| `heading_deg` | float [0, 360), nullable | Vehicle heading/course for presentation | Required by map/navigation consumers when available |
| `calibration_id` | string, nullable | Calibration instance used by the output | Traceability to phone-to-vehicle calibration |
| `gnss_used_after_initialization` | boolean | Whether GNSS updates influenced the navigation state after initialization | Prevents pure-DR claims when GNSS corrections were actually used |

### CURRENT CONTRACT — COMPANION QUALITY / CONFIDENCE RECORDS

Quality and confidence remain separate event types rather than being hidden inside the navigation record.

| Record | Field | Type | Meaning |
|---|---|---|---|
| `gnss_quality` | `state` | enum (`unavailable`, `acquiring`, `good`, `degraded`, `stale`, `denied`) | State of the GNSS input, not the fused navigation estimate |
| `gnss_quality` | `fix_age_s` | float/null | Age of the most relevant fix |
| `gnss_quality` | `satellites_used` | integer/null | Satellite count when valid/available |
| `gnss_quality` | `reasons` | list of stable reason codes | Machine-readable reason for the GNSS state |
| `confidence` | `state` | enum (`unavailable`, `unvalidated`, `calibrated`) | Whether uncertainty values have a justified calibration |
| `confidence` | `probability` | float/null | Reserved confidence quantity; must be null unless calibrated |
| `confidence` | `horizontal_accuracy_95_m` | float/null | Estimated 95% horizontal accuracy radius |
| `confidence` | `speed_std_m_s` | float/null | Estimated one-standard-deviation speed uncertainty |

### DERIVED REQUIRED FIELDS / BEHAVIOR FOR THE FINAL SYSTEM

These are not all present as explicit v1 fields, but the final working system needs their semantics somewhere in the navigation-output layer.

| Item | Type | Meaning | Current status |
|---|---|---|---|
| localization mode | enum concept (`GNSS`, `DR`, `FUSED`) | Which localization regime currently drives the presented position | **Missing as an explicit v1 field; requires versioned contract review before addition** |
| outage start/end identity | event/diagnostic provenance | Delimits GNSS-denied intervals for evaluation and UI | Not yet formalized |
| stale-state timeout policy | deterministic policy | Defines when old navigation output must stop being presented as current | Map adapter currently hides stale synthetic/navigation presentation after a bounded timeout; production engine policy not frozen |
| output latency metric | runtime telemetry | `received_ns - t_ns` or engine publication latency with clearly defined clock semantics | Not yet formalized as a dedicated telemetry stream |
| model provenance | version/hash reference | Identifies AI model(s) that contributed to an estimate | Future; no production model is deployed yet |
| map-match provenance | raw vs. matched position linkage | Distinguishes filter state from road-snapped presentation/correction | Future; map matching not implemented |
| recovery convergence state | structured diagnostic/state | Shows that GNSS has returned but fusion has not yet fully reconverged | Future; recovery logic not implemented |

### OPTIONAL / ADVANCED OUTPUTS

| Field / output | Type | Meaning | Recommendation |
|---|---|---|---|
| `raw_ins_position` | position | Uncorrected INS position for comparison | High-value evaluation/debug output; not required in production UI |
| `ai_corrected_position` | position | AI-enhanced intermediate estimate | Useful for ablation plots if the architecture produces it explicitly |
| `innovation` / residuals | vector/scalar | EKF innovation and normalized innovation statistics | Engineering diagnostics; hide from normal UI |
| covariance diagonal/full matrix | vector/matrix | Filter state covariance | Valuable for validation; do not expose as user-facing "accuracy" until calibrated |
| `map_matched_position` | position | Road-constrained position | Future map-matching output |
| route-progress fields | structured object | Distance along route, maneuver progress, off-route state | Optional navigation enhancement; routing not core DR |
| raw feature snapshot | structured object | AI input feature window associated with an output | Offline debugging only; bounded and privacy-aware |
| edge-runtime timing | structured telemetry | Per-stage latency at ~200 Hz target | Edge-engine benchmarking |
| energy/thermal metrics | structured telemetry | Runtime power/thermal evidence | Production hardening / extended testing |

---

## 2. Navigation Status vs. GNSS State vs. Localization Mode vs. Confidence

These are four different questions and must never be collapsed into one label.

- **`NavigationStatus`** answers: *"Is the navigation solution initialized and usable?"*
- **`GnssState`** answers: *"What is the health/availability of the satellite-positioning input?"*
- **Localization mode** answers: *"Which positioning regime is currently responsible for the presented solution — GNSS-aided, dead reckoning, or fused/recovery?"*
- **`Confidence` / uncertainty** answers: *"How uncertain is the numerical estimate?"*

A degraded GNSS input does **not** automatically imply a failed navigation solution. During a tunnel, `GnssState = denied` can coexist with a valid but increasingly uncertain DR navigation state.

Likewise, a tracking navigation state does not prove high confidence. A filter can still produce a numerical position while its uncertainty grows.

**Current contract limitation:** v1.0.0 contains `NavigationStatus`, `GnssState`, confidence, and `gnss_used_after_initialization`, but it does **not** carry an explicit current `GNSS/DR/FUSED` mode field. Do not infer that mode in the final system from a single boolean or UI label. If the mode becomes a contract-level output, introduce it through an explicit versioned contract change with matching Python/Kotlin codecs and tests.

---

## 3. Confidence / Uncertainty Semantics

### 3.1 Do not equate GNSS provider accuracy with fused-navigation confidence

Android GNSS/location accuracy fields and the navigation contract's `horizontal_accuracy_95_m` have different meanings.

- Provider-reported GNSS horizontal/vertical accuracy is source metadata and may represent a provider-specific confidence level.
- `horizontal_accuracy_95_m` is intended to represent the navigation system's calibrated 95% horizontal uncertainty.
- A provider's 68%-style uncertainty must **not** be renamed as 95% uncertainty without an explicit error model.
- During GNSS denial, the navigation uncertainty should evolve from the filter/model state, not remain frozen at the last GNSS accuracy.

### 3.2 Confidence states

- **`unavailable`** — the system cannot provide a justified uncertainty estimate.
- **`unvalidated`** — a numeric uncertainty may exist internally, but its relationship to real error has not been calibrated/validated.
- **`calibrated`** — the uncertainty estimate has been empirically checked against held-out/independent ground truth under the defined evaluation protocol.

The current project is **not yet entitled to mark production DR confidence as calibrated**, because the real INS/EKF/AI navigation engine has not been completed and validated.

### 3.3 Recommended validation before `calibrated`

For each relevant operating regime:

1. compute predicted horizontal uncertainty,
2. compare it against actual position error from ground truth,
3. check empirical coverage (for example, whether a claimed 95% radius contains the true position at approximately the intended rate),
4. evaluate separately for GNSS-good, degraded, denied, and recovery segments,
5. freeze the calibration method/version with the model/filter version.

A confidence display is useful only if it is an uncertainty claim the evidence can support.

---

## 4. Navigation Quality and Mode Transition Policy

The final implementation should use explicit, deterministic state transitions. The UI must reflect engine state; it must not invent its own localization mode.

### GNSS available

Expected behavior:

- accept validated GNSS updates,
- maintain INS propagation between updates,
- run the fusion update,
- publish fused position/velocity/heading,
- keep uncertainty bounded by actual filter evidence,
- identify that GNSS has contributed after initialization.

### GNSS degraded

Expected behavior:

- do not hard-switch merely because one fix is noisy,
- reduce/withhold GNSS influence according to validated gating logic,
- continue inertial propagation,
- increase uncertainty if observability deteriorates,
- expose reasons through GNSS-quality diagnostics.

### GNSS denied / stale beyond policy

Expected behavior:

- no repeated stale GNSS fix may masquerade as a fresh correction,
- continue causal DR propagation from the last trusted state,
- AI correction may assist only if the deployed model has passed its own validation gate,
- vehicle constraints/map matching may constrain the solution only if those modules are actually enabled and evidence-backed,
- uncertainty should generally grow with the outage unless valid constraints justify otherwise.

### GNSS recovery

Expected behavior:

- the first returning fix must be quality-gated,
- avoid a visual/physical hard snap solely for presentation,
- let the fusion layer reconverge using actual innovation/covariance logic,
- map smoothing may interpolate display frames but must not rewrite engine state or hide a large estimator correction,
- record the recovery interval so drift and reconvergence can be evaluated.

---

## 5. Evidence and Explanation Generation

For this project, "evidence" means machine-readable navigation provenance and diagnostics, not a natural-language security explanation.

### Required evidence categories

| Category | Example values | Purpose |
|---|---|---|
| Time | `t_ns`, `received_ns`, session clock identity | Reproduce timing and latency |
| Source | real/simulation/replay source | Prevent provenance ambiguity |
| Sensor/GNSS quality | permission/provider/satellite/fix-age/reason codes | Explain why GNSS was trusted or rejected |
| Calibration | calibration ID/status | Trace output to mounting calibration |
| Initialization | evaluation vs deployable | Prevent non-causal reference assistance being hidden |
| Navigation status | tracking/degraded/failed | Explain availability of the solution |
| Confidence | state, 95% horizontal radius, speed std | Quantify uncertainty without conflating it with GNSS source accuracy |
| Diagnostics | gap, late/invalid/drop/engine-failure codes | Explain degraded behavior and data loss |
| Model provenance | model version/hash when AI is deployed | Reproduce the AI contribution |
| Map-match provenance | raw estimate vs matched estimate when implemented | Avoid presenting snapped coordinates as raw filter output |

### Human-readable explanation

For the MVP/final demo, prefer **deterministic templates** generated from structured state rather than free-form LLM text.

Examples:

- `"GNSS denied for 18.4 s; navigation continuing in DR with 95% horizontal uncertainty 12.7 m."`
- `"GNSS recovered; fusion reconvergence in progress. Display remains based on fused engine output."`
- `"Navigation degraded: gyroscope time gap exceeded validated propagation threshold."`
- `"Position unavailable: calibration invalid after detected phone remount."`

Templates are fast, deterministic, auditable, and cannot invent sensor/model evidence.

---

## 6. Output Ordering, Rate, Staleness, and Duplicate Handling

Navigation output is a time series, not an alert stream. It therefore should **not** use alert-style cooldown/deduplication.

### Ordering

- Preserve exact event identity and Int64 timestamps.
- Equal timestamps across different event types are valid.
- The navigation engine may use its bounded reorder policy internally, but once a state has been emitted, consumers must not retroactively reorder historical output.
- Replays preserve original measurement timestamps and use a separate playback clock.

### Duplicate handling

- Duplicate event IDs are invalid.
- A repeated numeric position with a **new valid event ID/time** is not automatically a duplicate; a stationary vehicle can legitimately produce the same coordinates/state repeatedly.
- Map/UI presentation may avoid redrawing identical geometry, but it must not change the recorded navigation stream.

### Staleness

- A consumer must stop treating an old state as live after the defined stale timeout.
- The existing map `NavigationPresentation` currently rejects future/out-of-order navigation records and hides stale position after a bounded interval. That is presentation protection, not yet the production engine's final stale policy.
- The final stale threshold must be tied to actual output rate and engine behavior.

### Rate

Measure effective navigation publication rate separately from IMU input rate.

- Smartphone target: approximately 10 Hz navigation output.
- Edge target: approximately 200 Hz processing/output where required by the final FOG-IMU evaluation.
- A high-rate IMU input does not prove a high-rate validated navigation state.
- UI rendering may be sampled/interpolated separately from engine output, provided interpolation is presentation-only.

---

## 7. Map / Routing Boundary

The map is a consumer of navigation output.

```
Sensors / GNSS
      ↓
Calibration
      ↓
AI correction + INS
      ↓
Fusion / constraints
      ↓
Map matching (when implemented)
      ↓
Navigation output
      ↓
NavigationPresentation
      ↓
MapLibre renderer
```

The renderer must never:

- estimate inertial position,
- decide whether GNSS should be trusted,
- create a second EKF/filter,
- fabricate `GNSS/DR/FUSED` mode,
- snap coordinates to roads unless the map-matching module explicitly provides a matched result,
- reinterpret a synthetic demo as measured navigation.

**Routing is separate.** A planned route, route progress, rerouting, and turn-by-turn guidance are optional navigation-product functions. They may consume the current position but must not become part of the estimator's truth source unless a separately designed constraint interface explicitly allows it.

---

## 8. Final Current v1 Navigation Contract

Conceptual wire shape using the existing v1 envelope:

```json
{
  "contract_version": "1.0.0",
  "session_id": "string",
  "source": "real | simulation | replay_real | replay_simulation",
  "event": {
    "event_id": "canonical decimal string",
    "type": "navigation",
    "t_ns": "exact Int64 decimal string",
    "received_ns": "exact Int64 decimal string",
    "data": {
      "status": "uninitialized | calibrating | tracking | degraded | failed",
      "initialization_mode": "evaluation | deployable",
      "origin_wgs84_deg_m": {
        "latitude_deg": "float",
        "longitude_deg": "float",
        "altitude_m": "float"
      },
      "position_enu_m": {
        "x": "float",
        "y": "float",
        "z": "float"
      },
      "velocity_enu_m_s": {
        "x": "float",
        "y": "float",
        "z": "float"
      },
      "q_enu_from_vehicle_wxyz": {
        "w": "float",
        "x": "float",
        "y": "float",
        "z": "float"
      },
      "heading_deg": "float | null",
      "calibration_id": "string | null",
      "gnss_used_after_initialization": "boolean"
    }
  }
}
```

Nullable fields follow the exact invariants already defined by the contract; unavailable state must not be represented by fabricated zeros or sentinel coordinates.

Companion quality records use the same envelope with:

```json
{
  "type": "gnss_quality",
  "data": {
    "state": "unavailable | acquiring | good | degraded | stale | denied",
    "fix_age_s": "float | null",
    "satellites_used": "integer | null",
    "reasons": ["STABLE_REASON_CODE"]
  }
}
```

and:

```json
{
  "type": "confidence",
  "data": {
    "state": "unavailable | unvalidated | calibrated",
    "probability": "float | null",
    "horizontal_accuracy_95_m": "float | null",
    "speed_std_m_s": "float | null"
  }
}
```

---

## 9. Proposed Future Contract Extension — NOT YET FROZEN

The live map requirement benefits from an explicit localization-mode field, but adding one changes contract meaning and therefore must not be slipped into v1.0.0.

A future contract revision may add:

```text
localization_mode = GNSS | DR | FUSED | RECOVERY
```

or a similarly precise enum **only after**:

1. the real navigation state machine is designed,
2. transition semantics are defined,
3. the field's relationship to `NavigationStatus`, `GnssState`, and `gnss_used_after_initialization` is unambiguous,
4. Python and Kotlin codecs are versioned together,
5. golden/invalid fixtures and interoperability tests are added,
6. replay/source semantics remain exact.

Until then, the synthetic map's `GNSS/DR/recovery` labels remain explicitly scripted presentation labels and must not be interpreted as production engine output.

---

## 10. Current Implementation Boundary

| Capability | State |
|---|---|
| strict v1 navigation/quality/confidence codecs | **IMPLEMENTED** |
| exact session/source/timestamp semantics | **IMPLEMENTED** |
| real Android IMU/GNSS acquisition | **IMPLEMENTED + DEVICE VERIFIED** |
| local recording/recovery/export | **IMPLEMENTED + DEVICE VERIFIED** |
| Kotlin replay with replay-source remapping | **IMPLEMENTED + DEVICE VERIFIED** |
| ENU→WGS84 `NavigationPresentation` adapter | **IMPLEMENTED + TESTED** |
| offline MapLibre/Hyderabad renderer | **IMPLEMENTED + DEVICE VERIFIED** |
| synthetic GNSS/DR/recovery visualization | **IMPLEMENTED + DEVICE VERIFIED AS SYNTHETIC** |
| real `NavigationEngine` implementation | **NOT IMPLEMENTED** |
| deployable calibration output | **NOT IMPLEMENTED** |
| corrected classical INS | **NOT IMPLEMENTED / historical baseline invalid for deployment** |
| AI correction inference | **NOT IMPLEMENTED** |
| EKF/UKF fusion | **NOT IMPLEMENTED** |
| real GNSS→DR→recovery state machine | **NOT IMPLEMENTED** |
| map matching | **NOT IMPLEMENTED** |
| explicit live `GNSS/DR/FUSED` contract field | **NOT PRESENT IN v1** |
| offline routing / rerouting / turn-by-turn | **NOT IMPLEMENTED** |
| edge ~200 Hz engine | **NOT IMPLEMENTED** |

---

## 11. Decision Summary

1. Keep the existing v1 contract frozen until a real navigation state machine requires a justified version change.
2. Keep navigation status, GNSS quality, localization mode, and confidence semantically separate.
3. Never turn Android provider accuracy into calibrated fused-navigation confidence by renaming it.
4. Preserve exact timestamps/source/session provenance through recording and replay.
5. The UI/map consumes navigation output; it does not estimate navigation.
6. Map matching and routing remain separate functions.
7. Synthetic map demonstrations remain clearly labelled and cannot be used as evidence of actual drift performance.
8. The <10% outage-drift target and ~10 Hz/~200 Hz targets are acceptance metrics that require measured evidence, not schema fields.
9. Model/filter/map-match provenance becomes required once those modules actually produce navigation output.
10. Any explicit `GNSS/DR/FUSED/RECOVERY` field requires a versioned contract revision, not an ad-hoc UI-only invention.

---

**This document defines the navigation-output and evidence architecture only.** It does not claim that the real navigation engine, AI correction, EKF/UKF, map matching, routing, or recovery logic already exists.
