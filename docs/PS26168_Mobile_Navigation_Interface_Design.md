# PS26168 — Mobile Navigation Interface Design
**Scope:** The user-facing Android interface only, built on the already-defined acquisition, recording/replay, navigation contract and offline map architecture. No INS/EKF/AI/map-matching algorithm is designed here.

**Official minimum requirement, restated:** the final mobile application must provide a functional real-time navigation interface with a **smooth, uninterrupted vehicle icon** through GNSS availability, GNSS loss/dead reckoning and GNSS recovery.

**Current-state boundary:** the repository already implements Dashboard, Diagnostics, Map, About, recording/sessions/export/replay, offline MapLibre rendering and an explicitly synthetic map demonstration. The real `NavigationEngine` remains unimplemented, so the current map demo must not be presented as measured navigation.

---

## Page Evaluation

| Candidate Page / Surface | Serves official minimum? | User / judge usefulness | Current status | Verdict |
|---|---|---|---|---|
| **Navigation / Live Map** | **Directly** — this is the official real-time interface requirement | Highest | Offline renderer + synthetic fixture implemented; real engine feed not implemented | **MVP** |
| **Dashboard** | Indirectly — summarizes mode, speed, confidence, recording and GNSS status | High | Implemented foundation | **MVP** |
| **Calibration** | **Directly supports official calibration engine** | High before driving | Final calibration engine not implemented | **MVP** |
| **Diagnostics** | Not user-navigation output itself, but essential for validating IMU/GNSS/quality and demo credibility | High for team/judges | Implemented + physically verified acquisition | **MVP** |
| **Saved Sessions / Recording** | Not official by name, but required for reproducible field evaluation and evidence | High | Implemented | **MVP-supporting** |
| **Replay / Evaluation** | Not official by name, but essential fallback and deterministic verification | High | Implemented replay foundation; real engine replay future | **High-Value** |
| **Trip / Accuracy Analytics** | Not official | High for demonstrating drift and AI-vs-baseline | Not implemented | **High-Value** |
| **About / Scope / Privacy** | Not official | Useful for transparent demo boundaries/licensing | Implemented | **High-Value** |
| **Offline Route Planner / Turn-by-Turn** | Not required for core IDR | Product value, but secondary | Not implemented | **Optional Advanced** |
| **Edge Performance** | Supports edge deliverable, but not normal driver UI | High for finale engineering evidence | Not implemented | **High-Value / engineering** |
| **Fleet / Cloud / Account UI** | No | Low for core PS | Not implemented | **Future / Production** |

---

# MVP UI

## 1. Navigation / Live Map

### Purpose

The primary driving view. It must make the navigation result understandable without exposing raw estimator complexity.

### Main components

- offline MapLibre map,
- current vehicle marker,
- marker heading/orientation,
- travelled trail,
- road-matched position once map matching exists,
- optional raw-vs-matched toggle for evaluation mode,
- planned route overlay only if routing is later implemented,
- GNSS / DR / Fused / Recovery mode indicator **only after the contract carries an approved explicit mode**,
- speed,
- heading,
- GNSS quality indicator,
- confidence/uncertainty radius when scientifically valid,
- recenter / north-up / zoom controls,
- offline coverage state,
- recording indicator.

### Current implementation

Already implemented and device-verified:

- MapLibre Native Android renderer,
- local Hyderabad vector pack,
- streets/buildings/water/labels,
- local attribution,
- pan/zoom/recenter/north-up behavior,
- synthetic position marker,
- heading geometry,
- bounded trail,
- synthetic uncertainty circle,
- scenario overlays and comparison trail,
- explicit `SYNTHETIC UI FIXTURE` labeling,
- no Internet permission.

Not yet real:

- live DR position,
- real GNSS→DR→recovery,
- fused confidence,
- map matching,
- routing,
- real localization-mode field.

### Useful visualizations

- **Vehicle marker + heading arrow** — primary.
- **Travelled trail** — bounded recent route history.
- **Accuracy/confidence radius** — only when calibrated or clearly labelled unvalidated.
- **Raw vs map-matched path** — evaluation toggle, not default driver clutter.
- **GNSS blackout segment** — optional demo/evaluation overlay.
- **Route polyline** — only if offline routing is actually implemented.

### Real-time update requirements

- consume `NavigationState` / quality / confidence from the navigation runtime,
- never subscribe directly to raw phone GNSS for the final "live navigation" marker,
- UI can interpolate animation frames for smoothness,
- interpolation must not alter estimator state or recorded truth,
- stale states must visibly stop/hide rather than keep drifting cosmetically.

### Loading state

- map pack initialization indicator,
- calibration/navigation initialization state,
- avoid showing `(0,0)` or a fake vehicle marker before valid state exists.

### Empty state

Examples:

- "No navigation session running"
- "Calibration required"
- "Offline map unavailable for this area"
- "Waiting for initial position"

### Error state

Non-blocking where possible:

- map unavailable → numeric navigation can remain visible,
- navigation failed → stop moving marker and show explicit error,
- model unavailable → show classical-fallback state if supported,
- GNSS denied → this is an operating mode, not an app error.

---

## 2. Dashboard

### Purpose

Immediate summary of the active session and one-tap access to the most important actions.

### Main components

- source: Real / Simulation / Replay,
- navigation status,
- GNSS quality,
- current speed,
- heading,
- confidence summary,
- active calibration ID/status,
- recording state,
- sensor status,
- shortcut to Live Map,
- Start / Stop controls.

### Current-state requirement

Do not show real position/speed as if inferred by the navigation engine until that engine exists. Acquisition values and synthetic demo values remain clearly separated.

### Useful compact metrics

- sensor input Hz,
- GNSS fix age,
- navigation output Hz once available,
- queue/drop/error counters only in a compact health card.

### Empty state

"Ready — choose Phone sensors / Replay and start."

### Error state

Show actionable state such as:

- permission denied,
- required sensor unavailable,
- calibration invalid,
- navigation engine failed.

Avoid generic "Something went wrong."

---

## 3. Calibration

### Purpose

Guide the user through the official in-vehicle alignment/calibration requirement.

### Proposed components

1. **Mount check**
   - phone stable,
   - sensor availability,
   - current orientation preview.

2. **Static leveling**
   - progress ring for stationary window,
   - gravity stability,
   - gyro stability.

3. **Forward/yaw alignment**
   - instruction to drive straight only when safe,
   - progress based on sufficient motion excitation,
   - GNSS course availability if used.

4. **Result**
   - Valid / Invalid / Needs More Motion,
   - calibration ID,
   - pitch/roll/yaw summary,
   - quality/confidence,
   - recalibrate action.

5. **Remount warning**
   - "Phone position changed — recalibration required."

### Safety

The driver should not manipulate calibration controls while the vehicle is moving; a passenger/operator can supervise during field tests.

### Current status

Target UI only. The real calibration algorithm is not implemented yet.

---

## 4. Diagnostics

### Purpose

Engineering/judge evidence that the phone is actually sensing, timestamping and preserving quality state correctly.

### Main components

Current implemented foundation should continue to show:

- accelerometer,
- gyroscope,
- gravity,
- magnetometer,
- sensor names/vendors,
- requested/observed rates,
- GNSS provider,
- satellite counts where valid,
- permission state,
- fix age / accuracy,
- accepted/delayed/duplicate/drop/invalid counters,
- queue depth/high-water,
- current source/session.

Future additions:

- navigation output rate,
- per-stage latency,
- active model version/hash,
- calibration status,
- filter health,
- map matcher state.

### Visualization

Prefer small sparklines or concise values; do not turn Diagnostics into the driver-facing main screen.

---

## 5. Saved Sessions / Recording

### Purpose

Capture reproducible evidence without loading full trips into memory.

### Main components

- explicit Start Recording / Stop,
- recording ID,
- acquisition session ID,
- source,
- duration,
- record count,
- written/drop/error counters,
- finalization state,
- saved-session list,
- session metadata/detail,
- explicit local export.

### Current status

Implemented.

### Empty state

"No saved sessions yet."

### Error state

- recording write failure,
- incomplete/recovered recording,
- export cancelled,
- export failed.

Recording failure must not silently stop sensor acquisition.

---

# High-Value UI

## 6. Replay / Evaluation

### Purpose

Re-run a real saved session deterministically for debugging, demonstration and future navigation evaluation.

### Components

- session selector,
- source lineage (`replay_real` / `replay_simulation`),
- playback status,
- timestamp/event counters,
- real-time vs accelerated evaluation mode **only when the navigation-runtime replay path supports it**,
- current replay sensor/GNSS snapshot,
- optional map output from the real navigation engine,
- stop/reset.

### Current status

Local replay exists. Real replay → NavigationEngine → map remains future.

### Visualizations once the engine exists

- estimated path,
- ground-truth path when an evaluation reference is loaded on the desktop/evaluation side,
- outage region,
- raw/classical/AI comparison,
- drift metric.

Do not show ground truth as a deployable mobile input.

---

## 7. Trip / Accuracy Analytics

### Purpose

Make the scientific value of the project understandable to judges without replacing formal reports.

### Main components

- selected session/outage,
- distance travelled,
- final outage error,
- drift percentage,
- position RMSE,
- speed MAE/RMSE,
- heading error,
- recovery convergence time,
- output rate / p95 latency,
- baseline-vs-AI comparison.

### Useful charts

- position error vs time,
- trajectory comparison,
- speed estimate vs reference,
- uncertainty radius vs actual error,
- GNSS availability / DR / recovery timeline.

### Data boundary

This page is evaluation-oriented. It may consume ground truth only for offline/replay reports, never for live deployable navigation.

---

## 8. About / Scope / Privacy

### Purpose

Keep the prototype transparent.

Content:

- problem statement,
- current build version / Git SHA,
- offline/no-cloud behavior,
- map attribution/licensing,
- recording/export privacy behavior,
- current implemented vs future limitations,
- synthetic-demo disclosure.

Current repository already has an About surface; preserve this transparency.

---

## 9. Edge Performance

### Purpose

Engineering/finale page or laptop-side panel for the edge engine.

### Components

- edge input source/device,
- input rate,
- output rate,
- model version,
- p50/p95 latency,
- queue depth/drops,
- CPU/memory,
- current navigation state,
- benchmark status toward ~200 Hz.

Not a normal driver page.

---

# Optional Advanced UI

## 10. Offline Route Planning / Guidance

Only after a local road graph/routing engine exists.

Possible components:

- destination selection,
- planned route,
- distance remaining,
- next maneuver,
- route progress,
- off-route state,
- local reroute.

Do not implement a fake route simply by drawing the synthetic demo curve.

---

# Future / Production UI

- background navigation notification / service controls,
- multiple offline region manager,
- encrypted trip-history manager,
- fleet sync/account controls,
- remote diagnostics,
- accessibility/localization expansion,
- iOS interface,
- OTA model/map update status.

---

## Navigation State Presentation Rules

### Navigation status vs GNSS quality vs localization mode vs confidence

Do not collapse these into one badge.

- **Navigation status:** uninitialized / calibrating / tracking / degraded / failed.
- **GNSS quality:** unavailable / acquiring / good / degraded / stale / denied.
- **Localization mode:** future explicit GNSS / DR / FUSED / RECOVERY field.
- **Confidence:** unavailable / unvalidated / calibrated + numeric uncertainty.

The current v1 contract has no explicit localization-mode field. The UI must not infer a production mode from `gnss_used_after_initialization` or synthetic labels.

---

## Recommended Frontend Stack

**Kotlin + Jetpack Compose + Material 3 + MapLibre Native Android**, using the current in-process coroutine/Flow architecture.

### Why

- **Jetpack Compose** — already the app framework; lifecycle-aware declarative UI.
- **Material 3** — accessible, consistent controls and status surfaces.
- **MapLibre Native Android** — already integrated and device-verified with the offline pack.
- **AndroidView bridge** — acceptable for embedding MapLibre's native MapView inside Compose.
- **StateFlow / Flow** — existing application state transport; no REST/SSE/WebSocket needed inside one process.
- **Compose Canvas / lightweight chart library only where needed** — avoid a large charting dependency until analytics screens are built.

No React/web dashboard is required for the mobile product.

---

## UI Update / State Ownership

```text
AndroidAcquisition / Replay
          ↓
NavigationRuntime (future)
          ↓
canonical navigation / quality / confidence records
          ↓
SessionViewModel or dedicated presentation state holder
          ↓
Compose screens
          ↓
MapLibre renderer
```

The map renderer does **not** own sensors or localization.

---

## Loading / Empty / Error Design Principles

Every main page should distinguish:

1. **Loading / initializing**
2. **Valid but empty / not started**
3. **Degraded operating condition**
4. **Actual error**

Examples:

- GNSS denied while DR is healthy = operating condition, not error.
- No saved sessions = empty state.
- Map pack installing = loading.
- Navigation state numerically invalid = error.

---

## Current UI Acceptance Boundary

### Verified now

- Dashboard/Diagnostics/About foundation,
- real sensor/GNSS acquisition UI,
- recording/session/export/replay controls,
- offline MapLibre map,
- synthetic map interaction/overlays,
- lifecycle stop/no silent restart.

### Not yet claimable

- live real IDR map motion,
- real GNSS→DR→recovery mode,
- calibrated uncertainty,
- real map-matched position,
- real routing,
- calibration UI backed by working calibration,
- edge performance UI backed by edge engine.

---

**No navigation algorithm, backend or security architecture is altered by this interface design.**
