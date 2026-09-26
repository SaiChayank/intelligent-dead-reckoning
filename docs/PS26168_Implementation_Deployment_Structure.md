# PS26168 — Implementation & Deployment Structure

**Scope:** How the already-approved Intelligent Dead Reckoning architecture gets built, tested, packaged, and run, plus the repository layout it lives in. No navigation/ML implementation code is written here.

**Current repository baseline:** `SaiChayank/intelligent-dead-reckoning`, `main @ da84645` (`Added Live Map`).

**Guiding simplification:** the project is an **on-device Android navigation system plus offline desktop training/evaluation and a later edge target**. Do not introduce a backend, message broker, cloud service, or microservice deployment just to make the system look more complex.

---

## 1. Local Development Environment

### Android application

**Purpose:** fast iteration on acquisition, recording, replay, map rendering, and later navigation-engine integration.

Current environment:

- Android Studio
- Kotlin / Jetpack Compose
- Android Gradle Plugin / Gradle
- physical OnePlus 12R test device
- app package `com.intelligentdeadreckoning.app`
- app module under `mobile/app`
- shared Kotlin contract sources included from `contracts/v1` and `contracts/recording/v1`

Current device-oriented development loop:

```text
edit Kotlin / contracts
        ↓
JVM unit tests
        ↓
lint
        ↓
assembleDebug
        ↓
install / connected Android tests
        ↓
physical verification
        ↓
record evidence in mobile/*.md or reports/
```

No backend process is required to launch the app.

### Python / data-science environment

**Purpose:** IO-VNBD audit, navigation research, model training/evaluation, exported-session analysis, and later ONNX export.

Recommended current pattern:

```text
repository root
├── .venv/
├── training/
├── tests/
├── contracts/
├── reports/
└── data/raw/iovnbd/   # gitignored
```

Use one project virtual environment and pinned dependency files once the ML stack stabilizes.

### Development data

- Raw IO-VNBD remains local and gitignored.
- Small synthetic/golden fixtures may be committed.
- Large generated outputs, training checkpoints, APKs, and model binaries stay out of Git unless intentionally versioned as release artifacts.
- Real phone recordings live in Android app-private storage until explicit export.

---

## 2. Minimal Hackathon Deployment

**Purpose:** the actual judged mobile prototype with the least possible operational risk.

### Required hardware

- One Android phone with the verified app installed.
- One laptop for optional offline plots/evaluation and ADB/device diagnostics.
- Vehicle/test environment where driving tests can be conducted safely.
- Optional independent ground-truth receiver for final drift validation.

### Runtime topology

```text
ANDROID PHONE
┌─────────────────────────────────────────────┐
│ Sensors + GNSS                              │
│     ↓                                       │
│ AndroidAcquisition                          │
│     ↓                                       │
│ future NavigationRuntime / NavigationEngine │
│     ↓                                       │
│ NavigationState / Quality / Confidence      │
│     ↓                         ┌────────────┐ │
│ Compose UI + MapLibre  ◀─────│ recorder   │ │
│                              └────────────┘ │
│ local replay / export / offline map         │
└─────────────────────────────────────────────┘

OPTIONAL LAPTOP
- exported-session validation
- reference comparison
- plots / benchmark evidence
```

### Offline behavior

The judged navigation run must not depend on:

- venue Wi-Fi,
- cloud inference,
- online tile servers,
- Google Maps APIs,
- remote routing APIs,
- remote model serving.

The current manifest removes inherited Internet/network permissions from the offline map stack; preserve that property unless a later explicit provisioning feature is approved.

---

## 3. Android Build / Packaging Deployment

### Build artifacts

Primary:

```text
mobile/app/build/outputs/apk/debug/app-debug.apk
```

Final judged build may use a release signing configuration, but signing secrets must not be committed.

### Build gates

Before installing a candidate demo build:

1. JVM tests pass.
2. `lintDebug` passes.
3. `assembleDebug` or release equivalent passes.
4. connected tests pass on the target device.
5. app installs cleanly.
6. acquisition physical smoke test passes.
7. offline map loads with network unavailable.
8. saved sessions remain intact unless an intentional migration is being tested.

### Installation policy

For demo preparation:

- install the exact rehearsed build,
- avoid clearing app data after golden recordings are prepared,
- record version name/code and Git SHA in verification notes,
- record the offline-map manifest/checksum,
- record model artifact hashes once models exist.

---

## 4. Offline Map Deployment

Current map pack contains:

```text
mobile/app/src/main/assets/offline/hyderabad/
├── hyderabad.mbtiles
├── manifest.json
├── glyphs/
└── licensing / attribution files
```

At runtime the application verifies/installs the pack into app-private storage for MapLibre.

### Prototype decision

Keep **one bounded Hyderabad pack** for the SIH prototype.

Do not:

- bundle all-India tiles,
- add an online fallback,
- download map tiles during a navigation demo.

### Production path

Later regional packs may be:

- separately versioned,
- checksum-verified,
- preloaded/downloaded before a trip,
- installed atomically,
- removed through a storage-management UI.

Navigation must remain functional within installed coverage after connectivity disappears.

---

## 5. Training / Model Artifact Deployment

No production navigation model is currently deployed.

When a model is accepted:

```text
training run
    ↓
frozen preprocessing schema
    ↓
saved native training model
    ↓
TFLite / ONNX export
    ↓
round-trip equivalence test
    ↓
SHA-256 + metadata manifest
    ↓
Android asset/private model package
    ↓
on-device load + latency benchmark
```

Each deployed model package should include or reference:

- model ID/version,
- artifact SHA-256,
- feature-schema version,
- normalization/preprocessing version,
- training manifest ID,
- validation/test metrics,
- supported input rate/window,
- output semantics.

No model artifact is promoted merely because training loss looks good.

---

## 6. Edge Deployment

**Status:** future.

Target:

```text
FOG IMU
  ↓
edge ingest
  ↓
shared navigation semantics
  ↓
ONNX Runtime
  ↓
NavigationState / Quality / Confidence
```

Prototype edge implementation should remain a **single process** first.

Preferred evolution:

1. Python reference engine.
2. ONNX Runtime inference.
3. NumPy/Numba profiling/optimization.
4. C++ only if measured 200 Hz performance cannot be met cleanly.

Do not begin with a multi-process/distributed edge architecture.

---

## 7. Optional Production Architecture

Future system may contain:

- Android foreground/background product service where legally/technically required,
- regional map/road-graph manager,
- signed model packages,
- encrypted recording archive,
- broader device support,
- dedicated embedded/edge target,
- OTA provisioning,
- opt-in fleet sync/backend.

These are **not** hackathon build requirements.

---

# Repository Structure

Current/final-intended structure:

```text
intelligent-dead-reckoning/
├── contracts/
│   ├── v1/
│   └── recording/v1/
├── data/
│   └── raw/iovnbd/              # gitignored
├── training/
│   ├── phase0/
│   ├── diagnostics/
│   └── future model/navigation research
├── mobile/
│   ├── app/
│   ├── tools/
│   ├── README.md
│   ├── ACQUISITION.md
│   ├── RECORDING.md
│   ├── EXPORT.md
│   ├── REPLAY.md
│   ├── OFFLINE_MAP.md
│   ├── MAP_DEMO_FEATURES.md
│   └── MAP_DEVICE_VERIFICATION.md
├── reports/
├── tests/
├── models/                      # future, generated/versioned intentionally
├── edge/                        # future
└── docs/
```

---

## `contracts/`

**Responsibility:** shared data semantics.

Contains:

- typed Python/Kotlin measurement/navigation models,
- strict JSON/JSONL codecs,
- golden and invalid fixtures,
- recording-session metadata.

Rule: mobile, replay, Python tools, and edge code reuse these semantics; no parallel schemas.

---

## `training/`

**Responsibility:** data audit, diagnostics, classical-navigation research, feature extraction, ML training/evaluation, model export.

Expected eventual internal separation:

```text
training/
├── data/
├── navigation/
├── features/
├── models/
├── evaluation/
└── export/
```

Training code must not become a second incompatible implementation of deployable features.

---

## `mobile/app/`

**Responsibility:** the Android product.

Current major packages:

```text
app/
├── acquisition/
├── recording/
├── sessions/
├── replay/
├── map/
├── simulation/
└── ui/
```

Future:

```text
app/
├── navigation/
├── model/
└── matching/
```

Only add these when the corresponding scientific gate opens.

---

## `reports/`

**Responsibility:** empirical evidence and decision records.

Examples:

- dataset/schema audits,
- synchronization findings,
- frame-resolution studies,
- acquisition evidence,
- benchmark results,
- future INS/EKF/AI evaluations.

A report must distinguish:

```text
measured
derived
assumed
synthetic
future
```

---

## `tests/`

**Responsibility:** Python/cross-language/golden validation.

Future tests should include:

- synthetic navigation physics,
- model export equivalence,
- replay → navigation determinism,
- train/serve feature equivalence.

---

## `models/`

**Future responsibility:** accepted deployable artifacts only.

Do not use it as a dumping ground for every training checkpoint.

Recommended:

```text
models/
└── <model-id>/
    ├── model.onnx / model.tflite
    ├── manifest.json
    ├── preprocessing.json
    └── metrics.json
```

Large artifacts may remain outside Git and be release-attached or otherwise versioned.

---

## `edge/`

**Future responsibility:** FOG/high-rate deployment.

Keep its algorithm semantics aligned with the shared navigation contracts.

---

## `docs/`

**Responsibility:** frozen design documents, blueprint, implementation plan, architecture, data/ML decisions, security/privacy review, and demo runbook.

---

# Build / Run Profiles

## Profile A — Android foundation

Runs:

```text
real acquisition
recording
sessions/export
replay
offline map
synthetic map demo
```

This is the current verified foundation.

## Profile B — Navigation development

Future:

```text
real/replay measurements
       ↓
NavigationRuntime
       ↓
classical/AI navigation engine
       ↓
diagnostics + map
```

Use replay first for deterministic debugging.

## Profile C — Field evaluation

```text
Android phone + independent reference
       ↓
real drive
       ↓
record/export
       ↓
offline benchmark report
```

## Profile D — Edge benchmark

Future:

```text
FOG input/replay
       ↓
edge engine
       ↓
200 Hz timing/accuracy benchmark
```

---

# Deployment Gates

| Gate | Must be true before promotion |
|---|---|
| Foundation | acquisition/recording/replay/map regressions pass |
| Classical navigation | synthetic INS + calibration tests pass |
| Fusion | masked-GNSS replay + recovery tests pass |
| AI | held-out ablation beats/meaningfully improves baseline without runtime regression |
| Live map | map consumes canonical real navigation output |
| Final mobile | ground-truthed field test + ~10 Hz benchmark |
| Edge | actual edge input + sustained ~200 Hz evidence |

---

# Git / Release Discipline

Recommended feature workflow:

```text
git switch -c feature/<scope>
# implement + test
git status
git diff --check
git diff
git commit
git switch main
git merge --ff-only feature/<scope>
git push origin main
```

Do not merge a phase that fails its Definition of Done merely to keep schedule moving.

For each meaningful freeze point record:

- Git SHA,
- test counts,
- physical device,
- OS/API,
- model/map manifest versions,
- known limitations.

---

**No navigation/ML implementation code is created by this document. It defines how the current and future approved architecture is built, packaged, verified, and deployed.**
