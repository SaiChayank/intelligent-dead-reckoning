# PS26168 — Application Architecture

**Scope:** The Android application architecture that hosts the verified acquisition/recording/replay/map subsystems and will later host the real navigation engine. This document defines framework choice, module boundaries, local streaming, storage, internal interfaces, lifecycle ownership, map integration, optional edge integration, and prototype-to-production storage recommendations.

**Not in scope:** INS/EKF/AI algorithm design, feature engineering, model selection/training, map-matching mathematics, routing-algorithm design, security threat modeling, or UI visual-design specification. Those belong in separate documents.

**Guiding constraint carried forward:** prefer the simplest technically correct on-device architecture. Do not introduce a cloud backend, local HTTP server, database server, message broker, or microservices unless a concrete requirement appears that the current in-process architecture cannot satisfy.

---

# APPLICATION

## 1. Platform / Framework

### Recommended and current platform

**Android + Kotlin + Jetpack Compose**, implemented as one application.

This is both the planned product direction and the current repository implementation.

Current application characteristics:

- Android application ID: `com.intelligentdeadreckoning.app`
- Kotlin + Jetpack Compose / Material 3
- lifecycle-owned foreground acquisition
- Android sensors + Android location/GNSS
- Kotlin coroutine/Flow-based streaming
- app-private recording storage
- explicit SAF export
- read-only local replay
- MapLibre Android renderer
- bundled offline Hyderabad vector-map pack
- strict shared Kotlin/Python contracts
- no mandatory network dependency
- no background service
- no cloud inference
- no local web server/backend

### Why a conventional backend is not required

Unlike the reference cyber-threat project, this application's primary data path is entirely local:

```text
Android sensors / GNSS
        ↓
AndroidAcquisition
        ↓
typed Record stream
        ↓
future NavigationEngine
        ↓
NavigationState / Quality / Confidence
        ↓
Compose UI + offline MapLibre
```

The UI and navigation pipeline live inside the same trusted application process. Adding FastAPI/Redis/REST/SSE/WebSocket between them would create:

- avoidable serialization overhead,
- extra lifecycle/failure states,
- unnecessary local sockets,
- higher battery and memory cost,
- more code to test,
- no benefit to the current SIH requirement.

Therefore:

> **No application backend service is part of the MVP architecture.**

If a later edge-device deployment requires phone↔edge communication, that transport should be designed as a separate integration boundary rather than retrofitting a server into the mobile app.

---

## 2. Architectural Style

### Modular monolith, not microservices

The mobile product should remain **one deployable Android application with internally separated modules/packages**.

This mirrors the useful principle from the reference project: preserve strong module boundaries without paying distributed-system complexity.

Logical layers:

```text
┌─────────────────────────────────────────────────────┐
│                   PRESENTATION                      │
│ Compose screens · diagnostics · map · session UI   │
└───────────────────────┬─────────────────────────────┘
                        │ StateFlow / callbacks
┌───────────────────────▼─────────────────────────────┐
│                 APP ORCHESTRATION                   │
│ SessionViewModel · source/lifecycle coordination    │
└───────┬───────────┬──────────────┬──────────────────┘
        │           │              │
        ▼           ▼              ▼
 Acquisition    Recording       Replay
        │           │              │
        └──────┬────┴──────┬───────┘
               │ typed v1 Records
               ▼
        NavigationEngine
        (interface exists;
         implementation future)
               │
               ▼
 Navigation / quality / confidence
               │
               ▼
  NavigationPresentation adapter
               │
               ▼
      Map / Dashboard consumers

Shared cross-cutting layers:
- contracts/v1
- contracts/recording/v1
- diagnostics
- exact timestamp/source/session semantics
- offline storage
```

No layer should bypass the contract boundary merely for convenience.

---

## 3. Current Internal Modules

| Module / Package | Current responsibility | Status |
|---|---|---|
| `contracts/v1` | Cross-language measurement/navigation/quality/confidence/diagnostic contract and strict codecs | **Implemented / frozen baseline** |
| `contracts/recording/v1` | Recording-session metadata and canonical recording semantics | **Implemented / frozen baseline** |
| `app/acquisition` | Real Android IMU/GNSS input, permission handling, bounded queues, diagnostics, source coordination | **Implemented + physically verified** |
| `app/simulation` | Explicitly labelled scripted UI/demo source | **Implemented** |
| `app/recording` | Bounded asynchronous local JSONL recorder, metadata finalization/recovery | **Implemented + physically verified** |
| `app/sessions` | Saved-session inspection, paging, local ZIP export via user-selected document destination | **Implemented + physically verified** |
| `app/replay` | Read-only local replay with exact timestamps and replay source mapping | **Implemented + physically verified** |
| `app/map` | Navigation presentation adapter, offline pack install/style, renderer abstraction, GeoJSON overlays, synthetic demo controller | **Implemented; real engine not connected** |
| `app/ui` | Compose Dashboard/Diagnostics/Map/About, recording/session/replay controls | **Implemented** |
| `SessionViewModel` | Foreground owner/orchestrator for live source, recording, replay, export, session details | **Implemented** |
| future `core/navigation` or equivalent | Calibration, corrected INS, fusion, constraints, mode handling, real navigation output | **Not implemented** |
| future model-runtime adapter | On-device AI model loading/inference | **Not implemented** |
| future map matching | Road-network constraint/matching | **Not implemented** |
| future routing | Offline route planning/re-routing/turn guidance | **Not implemented / optional enhancement** |
| future `edge/` | High-rate FOG IMU + ONNX runtime deployment | **Not implemented** |

---

## 4. Application Orchestration

### Current owner: `SessionViewModel`

The current Android app uses `SessionViewModel` as the main application-session coordinator.

It owns or coordinates:

- scripted simulation,
- real `AndroidAcquisition`,
- `LocalRecorder`,
- saved-session inspection,
- local export,
- replay controller,
- source switching,
- foreground/background transitions,
- location-permission history,
- high-level mutual exclusion.

This is appropriate for the current prototype because the app has one foreground session and one activity.

### Important ownership rules

1. **Acquisition owns hardware listeners.**
2. **Recorder never owns sensors or location.**
3. **Replay never owns sensors or location.**
4. **Map renderer never owns sensors or localization.**
5. **Future NavigationEngine consumes typed records; it does not request Android permissions itself.**
6. **Compose UI observes state; it does not perform sensor integration.**
7. **Backgrounding stops live/replay/recording according to the frozen foreground-only policy.**
8. **Returning to foreground never silently restarts acquisition, recording, replay, or navigation.**

---

## 5. Input-Source Coordination

Current app-visible source choices:

```text
SIMULATION
REAL
```

Current recording/replay contract sources:

```text
real
simulation
replay_real
replay_simulation
```

### Live source behavior

`SourceCoordinator` is responsible for preventing concurrent active simulation and real acquisition.

On source switch:

- stop both source controllers,
- select the new source,
- do not auto-start it,
- stop an active recording if its source no longer matches,
- stop replay before returning to a live source.

### Future navigation-source behavior

The real NavigationEngine should not add a second independent "source selector."

Instead:

```text
REAL acquisition → NavigationEngine → REAL navigation output

replay_real events → evaluation/replay NavigationEngine session
                  → REPLAY_REAL navigation output

simulation → only if there is a canonical typed simulation stream
           → SIMULATION navigation output
```

Synthetic map fixtures remain UI demonstrations and must stay separate from real engine execution.

---

# STREAMING INTEGRATION

## 6. In-Process Transport: Kotlin Flow, not HTTP/SSE/WebSocket

The appropriate live transport inside this app is the existing coroutine/Flow model.

### Current streams

- acquisition state → `StateFlow<CaptureState>`
- real typed records → shared `Flow<Record>`
- recorder state → sampled `StateFlow<RecorderState>`
- replay state → sampled `StateFlow<ReplayState>`
- replay events → typed shared `Flow<Record>`
- saved-session list/details → `StateFlow`
- export state → `StateFlow`

### Future navigation streams

Recommended logical contract:

```text
measurement input:
Flow<Record>

navigation output:
Flow<Record>
    ├── navigation
    ├── gnss_quality
    ├── confidence
    └── diagnostic
```

The exact buffering policy must be defined with the real engine because navigation has stricter timing/ordering semantics than UI snapshots.

### Why Flow is correct here

- same-process producer/consumer,
- lifecycle-aware collection,
- coroutine backpressure primitives,
- no network protocol,
- exact typed Kotlin records,
- lower battery/latency overhead,
- straightforward deterministic JVM testing.

### Why SSE/WebSocket is not appropriate

Those are useful when a server pushes data across a process/network boundary.

Here, adding them would solve no present requirement.

Use them only if a future architecture explicitly introduces:

- a separate companion desktop process,
- a remote fleet dashboard,
- a networked edge compute unit,
- multi-client remote monitoring.

None is required for the current mobile MVP.

---

## 7. Required Streaming Boundaries

### Acquisition → Recorder

Already implemented.

```text
AndroidAcquisition.events
        ↓
LocalRecorder bounded admission queue
        ↓
I/O worker
```

Rules:

- no disk writes in sensor callbacks,
- bounded queue,
- explicit overflow/drop counters,
- accepted arrival order retained,
- recorder failure must not stop sensor acquisition,
- session/source identity must match.

### Acquisition → Future NavigationEngine

Required future boundary.

Rules:

- engine receives canonical typed records,
- no direct `SensorEvent` or `Location` objects cross the core boundary,
- exact `t_ns` / `received_ns`,
- no UI sampling before the engine,
- no implicit unit conversion,
- no conflation of IMU measurements,
- bounded reorder/late policy must remain explicit.

### Replay → Future NavigationEngine

Future replay/evaluation path should reuse the same engine input contract.

```text
saved measurements.jsonl
        ↓
ReplayReader
        ↓
canonical typed Records
        ↓
NavigationEngine
```

This is valuable because it avoids a separate "offline algorithm" code path.

Replay time and stored measurement time must remain separate.

### NavigationEngine → Presentation

```text
NavigationEngine output
        ↓
NavigationPresentation
        ↓
MapPresentation
        ↓
MapOverlay
        ↓
MapLibreRenderer
```

The presentation adapter can convert ENU to WGS84 and maintain a bounded trail.

It must not:

- run an EKF,
- propagate INS,
- decide GNSS trust,
- alter navigation timestamps,
- perform hidden road snapping,
- invent calibrated confidence.

---

# LOCAL STORAGE

## 8. Recording Storage

Current canonical private structure:

```text
Context.noBackupFilesDir/
└── recordings/
    └── <recording-id>/
        ├── metadata.json
        └── measurements.jsonl
```

Characteristics:

- app-private,
- no storage permission,
- no cloud upload,
- exact canonical JSONL,
- atomic metadata finalization where supported,
- explicit recovery semantics,
- existing session IDs never overwritten.

### Why no SQLite database is needed for raw measurements

A high-rate append-only sensor stream is better represented by the current streaming JSONL file than by row-per-sample SQLite writes for this prototype.

Advantages:

- exact contract representation,
- streaming write/read,
- easy Python interoperability,
- bounded memory,
- no ORM/database layer,
- portable export.

Do **not** migrate measurement storage to SQLite merely for architectural symmetry.

---

## 9. Session Catalogue

Current `SessionFiles` discovers recording directories and reads metadata on demand.

This is sufficient for the current prototype because:

- session count is modest,
- metadata is bounded,
- pages are bounded,
- recordings are already authoritative filesystem objects.

### Future production option

If hundreds/thousands of sessions eventually make directory scans expensive, add a small **derived index** (for example Room/SQLite) containing:

```text
recording_id
created_time
completion_state
source
duration
record_count
file_bytes
tags / user-visible name
```

The database would be a **cache/index**, not the source of truth for measurement payloads.

Do not add it until profiling shows the current catalogue is inadequate.

---

## 10. Export Storage

Current export behavior:

```text
private session
      ↓
user explicitly chooses destination
      ↓
SAF ACTION_CREATE_DOCUMENT
      ↓
ZIP
  ├── metadata.json
  └── measurements.jsonl
```

Requirements retained:

- private original remains authoritative,
- no automatic export,
- no shared-storage permission,
- no cloud provider if local-only policy is retained,
- exact bytes rather than semantic transformation,
- failed/cancelled export must not mutate the original.

---

## 11. Offline Map Storage

Current map pack is bundled in application assets and installed into an app-private offline-map directory.

Current contents include:

```text
hyderabad.mbtiles
manifest.json
glyph PBFs
license / attribution notices
```

Current characteristics:

- MapLibre rendering,
- local MBTiles/vector tiles,
- local glyphs,
- checksum/manifest verification,
- bounded size policy,
- no runtime tile download,
- no network fallback.

### Production extension

For wider geographic coverage, do **not** bundle all of India into the APK.

Preferred future strategy:

1. downloadable/preloaded regional packs,
2. versioned manifest,
3. checksum and license metadata,
4. explicit user selection,
5. atomic install/update,
6. storage-budget UI,
7. offline availability verification before trip start.

Any download feature would be optional provisioning; navigation itself must continue without a live network.

---

# INTERNAL API / INTERFACE SURFACE

## 12. Current Core Interfaces

### `SourceControl`

Purpose: start/stop one acquisition/simulation source.

Keep this simple. It is orchestration, not navigation.

### `RecordingStorage`

Purpose:

```text
create(metadata)
finalize(metadata)
recover()
```

Correctly isolates recorder policy from concrete filesystem implementation.

### `SessionFiles`

Purpose:

- inspect,
- page/list,
- open replay,
- export.

It is intentionally read-only except for export destination creation handled outside the private originals.

### `MapRenderer`

Purpose: replaceable renderer boundary.

Responsibilities:

- focus,
- present map overlay state,
- camera recenter/zoom/north-up.

It must not depend on navigation algorithm internals.

### `NavigationEngine`

Current v1 interface:

```text
initialize(session, calibration, mode)
acceptImu(record)
acceptGnss(record)
drain()
stop()
reset()
```

This is the correct central future boundary.

Do not replace it with a UI-specific ViewModel API.

---

## 13. Recommended Future Navigation Runtime Adapter

The existing interface is deliberately minimal. The application still needs an owner that translates Android/replay streams into engine calls and publishes outputs.

Recommended logical component:

```text
NavigationRuntime / NavigationSession
```

Responsibilities:

- create one engine session,
- validate session/source ownership,
- feed IMU/GNSS records,
- enforce start/stop lifecycle,
- drain ordered output,
- expose navigation/quality/confidence/diagnostic flow,
- report engine failure explicitly,
- never perform algorithm math itself.

It should not:

- own Android hardware,
- render maps,
- write recordings,
- train models,
- fetch network resources.

Conceptual API:

```kotlin
interface NavigationRuntime {
    val events: Flow<Record>
    val state: StateFlow<NavigationRuntimeState>

    fun start(
        session: EngineSession,
        calibration: Record,
        mode: InitializationMode
    )

    fun accept(record: Record)
    fun stop()
    fun reset()
}
```

This is a design recommendation for the later implementation phase, not a claim that the class exists now.

---

# APPLICATION DATA FLOW

## 14. Live Real-Phone Path — Current + Future

```text
SensorManager / LocationManager
            ↓
     AndroidAcquisition
            ↓
   canonical v1 Records
       ┌────┴─────────┐
       │              │
       ▼              ▼
 LocalRecorder   NavigationRuntime
 (implemented)    (future)
       │              │
       │              ▼
       │       NavigationEngine
       │              │
       │              ▼
       │   navigation / quality /
       │   confidence / diagnostics
       │              │
       ▼              ▼
 private files   Compose / Map
```

Recorder and navigation are sibling consumers.

The recorder must preserve the raw canonical acquisition stream even if the navigation engine fails.

---

## 15. Replay Path

Current:

```text
private recording
      ↓
SessionFiles.openReplay
      ↓
ReplayReader
      ↓
ReplayController
      ↓
replay_real / replay_simulation typed events
      ↓
Replay UI diagnostics
```

Future evaluation extension:

```text
ReplayController / bounded evaluation reader
      ↓
NavigationRuntime
      ↓
same NavigationEngine used live
      ↓
evaluation output / map presentation / metrics
```

This gives much stronger train/replay/live consistency than writing a second offline navigation implementation.

---

## 16. Synthetic Map Demo Path

Current:

```text
MapDemoController
      ↓
SyntheticMapDemo
      ↓
synthetic NavigationState
      ↓
NavigationPresentation
      ↓
MapLibre
```

This path is **presentation-only**.

It must remain clearly labelled:

- not user location,
- no real INS,
- no AI,
- no EKF,
- no measured drift,
- no real GNSS suppression,
- no real recovery.

Once the production engine is connected, synthetic demo mode can remain as a separate judge/demo fixture.

---

# MAP APPLICATION ARCHITECTURE

## 17. Rendering Stack

Current stack:

```text
OpenStreetMap-derived data
        ↓
OpenMapTiles vector schema
        ↓
bundled Hyderabad MBTiles
        ↓
MapLibre Android SDK
        ↓
MapLibreRenderer
        ↓
Compose-hosted MapView
```

The renderer is embedded with `AndroidView` because MapLibre is a native Android view while the surrounding application is Compose.

This is acceptable and avoids replacing a functioning offline renderer merely to achieve "pure Compose."

---

## 18. Map Responsibilities

Implemented/presentation responsibilities:

- draw roads/buildings/water/labels,
- show navigation marker,
- show heading geometry,
- show travelled trail,
- show uncertainty radius,
- toggle layers,
- camera focus/recenter/zoom/north-up,
- display attribution,
- display synthetic scenario overlays.

Future responsibilities after real engine exists:

- consume real `NavigationState`,
- consume validated confidence,
- display actual GNSS/DR/fused/recovery mode once a versioned field exists,
- interpolate marker motion for smooth display without altering state,
- show map-matched output when explicitly produced.

Not map responsibilities:

- sensor fusion,
- INS propagation,
- calibration,
- AI inference,
- GNSS quality gating,
- route calculation,
- map matching,
- estimator recovery.

---

# OFFLINE ROUTING / GUIDANCE

## 19. Routing Is a Separate Optional Module

Offline map tiles are not a routable graph.

If offline routing is added later, introduce a separate module:

```text
routing/
    RoadGraphStore
    RoutePlanner
    RouteProgressTracker
    ReRoutePolicy
    GuidanceGenerator
```

Inputs:

- origin/current position,
- destination,
- locally stored routable graph,
- optional vehicle/road constraints.

Outputs:

- planned route polyline,
- route progress,
- maneuver list,
- off-route status,
- optional re-route.

It should consume navigation position; it should not replace navigation state.

### Storage

A routable graph is distinct from `hyderabad.mbtiles`.

Potential future pack:

```text
region-pack/
├── map.mbtiles
├── road-graph.*
├── routing-metadata.json
├── licenses/
└── checksums.json
```

No routing engine is currently implemented, so this remains future work.

---

# EDGE INTEGRATION

## 20. Edge Engine Remains a Separate Deployment Target

The planned edge engine is not a backend for the Android app.

Conceptually:

```text
FOG IMU
  ↓
edge ingestion
  ↓
shared navigation semantics
  ↓
ONNX inference
  ↓
NavigationState / Quality / Confidence
```

The target is high-rate processing toward ~200 Hz.

### Shared semantics

Mobile and edge should share:

- coordinate conventions,
- calibration semantics,
- navigation-state definitions,
- model preprocessing definitions where applicable,
- confidence semantics,
- evaluation metrics.

They do not have to share the same UI/runtime framework.

### Optional phone↔edge connection

Not required for the MVP.

If later needed, design a versioned transport using the same canonical navigation concepts.

Do not expose ad-hoc JSON over a socket without:

- framing,
- versioning,
- exact timestamps,
- source identity,
- reconnection behavior,
- authentication/privacy review,
- backpressure.

---

# PERSISTENCE / DATABASE DECISION

## 21. Prototype Recommendation

### Raw measurement sessions

**Keep current filesystem JSONL + metadata.**

No database migration.

### Session index

**No database yet.**

Current paged filesystem catalogue is adequate.

### App preferences

Use Android-local state mechanisms only for small configuration/preferences.

Do not store sensor streams in `SavedStateHandle`.

### Offline map

Keep the verified private installed map pack.

### Models

Future exported model artifacts should live in a versioned app asset/private model directory with:

- model version,
- SHA-256,
- input schema/version,
- normalization parameters reference,
- target/runtime compatibility,
- evaluation provenance.

### Metrics

For prototype evaluation, write bounded structured reports/files rather than introducing a telemetry database.

---

## 22. Production Evolution

Only if scale or product requirements justify it:

| Need | Evolution |
|---|---|
| thousands of saved sessions | Room/SQLite derived catalogue |
| large regional map library | pack manager + manifest database/index |
| fleet/cloud synchronization | explicit opt-in synchronization service |
| remote monitoring | separate backend/API |
| shared edge device | versioned local-network/Bluetooth/USB transport |
| long-term telemetry | local rolling metrics + opt-in backend |
| production crash analytics | privacy-reviewed diagnostics pipeline |

None should be pre-built before the core navigation engine works.

---

# LIFECYCLE

## 23. Foreground-Only Ownership

Current frozen policy:

- explicit Start required,
- Home/background stops live acquisition,
- recording admission stops/finalizes,
- replay stops,
- returning does not resume automatically,
- rotation/activity recreation does not silently restart capture,
- no foreground service,
- no background location.

This is correct for the prototype and keeps the permission/lifecycle model auditable.

### Future navigation behavior

When live acquisition stops:

- navigation input admission must stop,
- engine must stop/finalize state,
- no hidden propagation in background,
- UI may retain the last snapshot only as visibly stale/stopped evidence.

If a future product adds background navigation, it is a new product/security/power requirement and requires a deliberate foreground-service/location-permission design. It must not be introduced silently.

---

# PERMISSIONS / PRIVACY

## 24. Current Android Permission Surface

Required:

- coarse location,
- fine location.

Optional hardware declarations:

- accelerometer,
- gyroscope,
- compass,
- GPS.

Explicitly removed/not requested for the current application:

- Internet,
- network state,
- Wi-Fi state,
- background location,
- foreground service,
- broad external storage permission.

This is a strong fit for the offline-navigation prototype.

### Privacy principles

- navigation inference stays local,
- raw recordings stay app-private until explicit export,
- map data is local,
- no automatic upload,
- no hidden third-party tile request,
- missing sensor/GNSS fields stay missing/null instead of being invented.

---

# FAILURE MODEL

## 25. Component Failure Isolation

| Failure | Expected behavior |
|---|---|
| one Android sensor missing | explicit unavailable/diagnostic; do not fabricate zero |
| location permission denied/revoked | IMU remains usable; GNSS cleared/unavailable |
| acquisition queue overflow | explicit drop count/diagnostic |
| recorder queue/write failure | recorder fails explicitly; acquisition can continue |
| export cancellation/failure | private original unchanged |
| replay corrupt record | replay fails explicitly; source file unchanged |
| offline map pack failure | visible map-unavailable state; no network fallback |
| future NavigationEngine failure | navigation output moves to failed/degraded; acquisition/recording remain independently operable |
| future AI model load failure | explicit engine/model failure; no fake AI-corrected output |
| routing unavailable | navigation still works; route/guidance unavailable |
| map unavailable | numeric navigation state should still remain available |

The navigation engine must not become a single point that destroys evidence capture.

---

# APPLICATION TEST SURFACE

## 26. Current Automated / Device-Test Areas

Current repository already contains dedicated tests for:

- acquisition,
- strict contract fixtures,
- recorder,
- recording storage failures,
- export,
- saved sessions,
- replay,
- navigation presentation,
- offline map,
- synthetic map controller,
- Compose UI/lifecycle.

### Future tests required when NavigationEngine is integrated

1. source/session isolation,
2. live acquisition → engine input mapping,
3. replay → same engine path,
4. exact timestamps,
5. output-order policy,
6. engine stop on lifecycle,
7. no auto restart,
8. engine failure does not stop recording,
9. navigation output → map adapter,
10. stale navigation hidden correctly,
11. confidence never fabricated from provider accuracy,
12. real/simulation/replay labels remain correct,
13. map renderer performs no filtering/inference,
14. long-duration bounded memory,
15. measured mobile navigation output rate.

---

# CURRENT FILE / MODULE MAP

## 27. Existing Files That Form the Application Architecture

```text
mobile/app/src/main/java/com/intelligentdeadreckoning/app/
├── MainActivity.kt
├── SessionViewModel.kt
├── acquisition/
│   ├── Acquisition.kt
│   └── AndroidAcquisition.kt
├── recording/
│   ├── AndroidRecordingMetadata.kt
│   ├── FileRecordingStorage.kt
│   └── LocalRecorder.kt
├── replay/
│   ├── ReplayController.kt
│   └── ReplayReader.kt
├── sessions/
│   ├── CreateSessionDocument.kt
│   ├── ExportController.kt
│   └── SessionFiles.kt
├── map/
│   ├── MapDemoController.kt
│   ├── MapLibreRenderer.kt
│   ├── MapOverlay.kt
│   ├── MapRenderer.kt
│   ├── NavigationPresentation.kt
│   ├── OfflineMapPack.kt
│   └── OfflineMapStyle.kt
├── simulation/
└── ui/
    ├── IdrApp.kt
    ├── MapDemoControls.kt
    ├── OfflineMapScreen.kt
    ├── RealDiagnostics.kt
    ├── ReplayPanel.kt
    ├── SessionDialog.kt
    └── Theme.kt

contracts/
├── v1/
└── recording/v1/
```

---

# FUTURE FILES / MODULES

## 28. Add Only When Their Phase Begins

Illustrative structure, not a command to implement them now:

```text
core/ or mobile navigation package
├── calibration/
├── mechanization/
├── fusion/
├── constraints/
├── navigation/
│   ├── NavigationEngineImpl
│   └── NavigationRuntime
├── model/
│   └── ModelRuntime
└── matching/
    └── MapMatcher

optional later
└── routing/
    ├── RoadGraphStore
    ├── RoutePlanner
    ├── RouteProgressTracker
    └── GuidanceGenerator
```

The final implementation language/module placement should be decided when the navigation-core phase begins and should preserve the already-frozen contract boundary.

---

# CURRENT APPLICATION STATUS

## 29. Implemented vs. Future

| Capability | Status |
|---|---|
| Compose app shell | **IMPLEMENTED** |
| real foreground IMU/GNSS | **IMPLEMENTED + VERIFIED** |
| permission state handling | **IMPLEMENTED + VERIFIED** |
| typed v1 records | **IMPLEMENTED** |
| local recording | **IMPLEMENTED + VERIFIED** |
| interrupted-recording recovery | **IMPLEMENTED + VERIFIED** |
| saved-session browser | **IMPLEMENTED** |
| explicit local ZIP export | **IMPLEMENTED + VERIFIED** |
| local replay | **IMPLEMENTED + VERIFIED** |
| offline Hyderabad map | **IMPLEMENTED + VERIFIED** |
| synthetic GNSS/DR/recovery map presentation | **IMPLEMENTED AS SYNTHETIC** |
| `NavigationPresentation` adapter | **IMPLEMENTED + TESTED** |
| actual `NavigationEngine` | **NOT IMPLEMENTED** |
| live real navigation state on map | **NOT IMPLEMENTED** |
| phone-to-vehicle calibration | **NOT IMPLEMENTED** |
| corrected deployable INS | **NOT IMPLEMENTED** |
| AI inference | **NOT IMPLEMENTED** |
| EKF/UKF | **NOT IMPLEMENTED** |
| real GNSS→DR→GNSS recovery | **NOT IMPLEMENTED** |
| map matching | **NOT IMPLEMENTED** |
| offline routing | **NOT IMPLEMENTED** |
| turn-by-turn guidance | **NOT IMPLEMENTED** |
| edge engine | **NOT IMPLEMENTED** |
| cloud/backend service | **NOT REQUIRED / NOT IMPLEMENTED** |

---

# ARCHITECTURAL DECISIONS

## 30. Final Decisions

1. **Android is the primary application deployment target.**
2. **Kotlin + Jetpack Compose remains the application framework.**
3. **Use one modular Android app, not mobile microservices.**
4. **No backend/API server is required for the MVP.**
5. **Use Kotlin Flow/coroutines for in-process live data transport.**
6. **Acquisition, recorder, replay, navigation, and map remain independent modules with explicit ownership.**
7. **Recorder and future navigation engine are sibling consumers of canonical acquisition records.**
8. **The navigation engine consumes typed contract records, never raw Android UI objects.**
9. **Replay should feed the same navigation path used live when the navigation engine is implemented.**
10. **Filesystem JSONL + metadata remains the canonical recording store.**
11. **Do not add SQLite/Room for high-rate measurement payloads.**
12. **MapLibre remains the renderer; the map does not perform localization.**
13. **Offline map operation remains local with no mandatory runtime Internet.**
14. **Routing is separate from map rendering, positioning, fusion, and map matching.**
15. **Current foreground-only lifecycle behavior remains frozen unless a later explicit background-navigation requirement is approved.**
16. **Future mobile AI inference must be local and versioned/provenanced.**
17. **The edge engine is a separate deployment target, not the Android app's backend.**
18. **Do not introduce server infrastructure merely to make the architecture look more enterprise-like.**
19. **App-private raw data stays private until explicit user export.**
20. **A failure in navigation/model/map/routing must not destroy acquisition/recording evidence.**

---

# MINIMUM FUTURE INTEGRATION POINT

## 31. Exact Integration Point for the Real Navigation Engine

The future live navigation implementation should connect here:

```text
SessionViewModel / dedicated NavigationRuntime
        │
        │ subscribes to
        ▼
AndroidAcquisition.events
        │
        ▼
NavigationEngine
        │
        │ emits canonical Record
        ▼
navigation + gnss_quality + confidence + diagnostic
        │
        ├──────────────► Dashboard / diagnostics
        │
        └──────────────► NavigationPresentation
                              │
                              ▼
                         MapLibreRenderer
```

The existing synthetic map path should remain available but explicitly separate.

The map must **not** subscribe directly to raw phone location as its future "live navigation" implementation.

---

# PROTOTYPE RECOMMENDATION

## 32. What to Build / What Not to Build

### Build

- real NavigationRuntime binding,
- actual NavigationEngine implementation after frame/calibration gates,
- live typed navigation output,
- confidence/quality presentation,
- real navigation → existing offline map adapter,
- model runtime only after validated model exists,
- later map matcher if the core estimate is working.

### Do not build merely for architecture

- FastAPI service,
- Redis,
- Kafka,
- cloud database,
- remote dashboard,
- WebSocket/SSE server,
- microservices,
- telemetry SaaS integration,
- account system,
- online tile server dependency.

They are unrelated to proving the SIH dead-reckoning objective.

---

**This document defines the application architecture only.** It intentionally keeps algorithm design, model training, feature engineering, detailed UI design, and routing/map-matching mathematics in their own documents.
