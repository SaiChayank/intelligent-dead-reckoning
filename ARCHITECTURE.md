# Repository architecture

This document defines where each logical area lives, what it owns, and what it
must not do. It is the organizing companion to the
[current-state audit](CURRENT_STATE_AUDIT.md) (what exists and how solid it is)
and [DEVELOPER_SETUP.md](DEVELOPER_SETUP.md) (how to build and verify it).

The one hard rule behind every boundary below: **the app is an offline,
on-device product.** There is no server, no cloud component, and no network
permission in the shipped manifest. Anything that implies otherwise is a defect.

## Logical areas and ownership

| Area | Location | Owns | Must not |
|---|---|---|---|
| Contracts | `contracts/v1`, `contracts/recording/v1`, `contracts/experiment/v1` | The only data schemas: typed payloads, strict Python + Kotlin codecs, golden/invalid fixtures | Depend on any app, map, or ML code; grow a parallel schema anywhere else. `experiment/v1` composes the other two by reference and never redefines a measurement or session field |
| Android application | `mobile/app/src/main` | UI, ViewModel, lifecycle, design system | Define data schemas (use contracts) or estimate positions |
| Acquisition | `mobile/.../acquisition/` | Foreground sensor/GNSS capture, timestamps, bounded queues, validation, and the deterministic GNSS quality/outage state machine with its validated per-provider thresholds ([mobile/GNSS_QUALITY.md](mobile/GNSS_QUALITY.md)) | Write files, do navigation math, or invent values |
| Recording | `mobile/.../recording/` | Session writing, atomic metadata, interrupted-session recovery | Reshape, sort, resample or interpolate what acquisition produced |
| Replay | `mobile/.../replay/` | Strict read-only session reading and playback control | Repair, rewrite or reinterpret a session |
| Maps | `mobile/.../map/`, `mobile/.../ui/OfflineMapScreen.kt`, `mobile/app/src/main/assets/offline/` | Presentation of positions the platform or a recording reported; bundled tile pack | Act as a second localization engine: no propagation, map matching, or inferred positions |
| Navigation runtime | `mobile/.../navigation/` | The seam to the engine: one engine-session lifecycle (start/stop/reset), record ownership and routing, bounded ingress/output, worker thread, failure isolation | Touch sensors or the map, write files, implement navigation math, or invent estimates |
| Calibration | `mobile/.../calibration/` | Phone-to-vehicle frame estimation from gravity and straight-motion evidence, sensor-bias estimation, remount detection: the first real `NavigationEngine` implementation | Touch sensors, GNSS providers, files or the map; produce a position, velocity or heading solution; publish a transform or a bias it did not measure. Documented in [mobile/CALIBRATION.md](mobile/CALIBRATION.md) |
| Fusion | `mobile/.../fusion/` | The classical GNSS+INS error-state EKF: 15-state covariance (position, velocity, attitude error, gyro/accel biases), prediction driven by the validated strapdown baseline, joint chi-square-gated GNSS position/velocity updates, rejection diagnostics, prediction-only outage coasting, non-snapping recovery, and gated vehicle-motion constraints (ZUPT / NHC with speed, yaw-rate, calibration, longitudinal-force and GNSS-freshness gates). Emits only canonical `NavigationState` / `Confidence` / `DiagnosticEvent` ([mobile/FUSION.md](mobile/FUSION.md)) | Touch sensors, GNSS providers, files or the map; emit anything outside the frozen contract set; snap to a returning fix; claim accuracy against ground truth |
| Navigation core | `core/` (**reserved, empty**) | Future frames, propagation, fusion shared with the edge runtime | — (nothing implements it yet; the calibration and fusion engines live in the app module until there is a second consumer, and `NavigationEngine` in `contracts/v1` is the frozen interface both must satisfy) |
| ML / training | `training/` | Offline research: audits, diagnostics, the classical strapdown INS baseline (`strapdown_ins.py`), and the historical experimental INS script | Ship into the app or claim product-grade results. The baseline is host-tested and GNSS-free at runtime ([report](reports/ins_baseline_2026_09_30.md)); the older experimental script is neither, and the two are not interchangeable |
| Experiment data | `experiments/` (**content gitignored**), `docs/PS26168_Experiment_Data_Collection_Protocol.md` | Collected drives: manifests, annotations, outage masks, optional references, integrity manifests | Define a measurement or session schema (use contracts), modify a raw recording, or claim navigation accuracy. Loaded read-only by `contracts/experiment/v1`; sealed by `tools/seal_experiment.py` |
| Evaluation | `reports/` + `tests/` | Measured evidence and the deterministic suites that keep it honest | Store raw dataset copies or unlabelled numbers |
| Models / artifacts | `models/` (**reserved, empty**) | Future trained/exported weights (gitignored patterns exist) | — (no model has been trained or approved) |
| Edge runtime | `edge/` (**reserved, empty**) | Future higher-frequency deployment target | — (reuses the navigation core when that exists) |
| Tests | `tests/` (Python), `mobile/app/src/test`, `mobile/app/src/androidTest` | Host suites in both languages, instrumented device suite | Require the raw dataset (synthetic fixtures only) |
| Reports / docs | `reports/`, `docs/` | Historical evidence (immutable once written) and planning documents | Rewrite historical reports; new evidence goes in new files |
| Tools | `tools/`, `mobile/tools/` | Session validation, parity probe, map-pack build/verify, read-only INS baseline measurement | Become a second implementation of contract rules (they call the contract code), or reimplement navigation math |

Empty directories are reserved on purpose: `core/`, `edge/`, and `models/` name
planned components so the boundary is visible before code exists. Each carries a
`README.md` stating its status. Do not place prototypes in them before the
prerequisites in the audit's build order are met.

## Dependency direction

```
contracts  (no dependencies; Python + Kotlin twins, one schema)
   ↑
acquisition → recording → replay / export → sessions (SessionFiles)
   ↑                                             ↑
Android application (ViewModel, UI)  ←——————— map (presentation only)
   ↑
core (future)  ←——  edge (future)      training (research, reads data/ offline)
```

Rules that follow from it:

1. Contracts depend on nothing and are compiled into the app via
   `sourceSets` in `mobile/app/build.gradle.kts` — one schema, two languages,
   kept honest by golden fixtures and `tools/parity_probe.py`.
2. The map layer may only *present* positions from the platform, a recording,
   or a labelled synthetic fixture. The three sources are never blended and
   each states its identity in the UI (`map_source`).
3. `training/` reads the offline dataset and writes reports; it never feeds
   the app at runtime. `training/common.py` is a data utility, **not** the
   navigation core.
4. Tools call contract code; they do not re-implement validation.

## Versioning and frozen semantics

- `contracts/v1` and `contracts/recording/v1` are versioned. Existing fields,
  units, nullability, timestamps (exact Int64 nanoseconds), source identity and
  replay semantics are frozen; additions require a documented contract change,
  never a silent edit.
- Frozen test tags and status strings (`tab_*`, `map_source`, `offline_map`,
  `map_attribution`, `map_status`, `map_mode`, `demo_*`, `overlay_*`,
  `scenario_*`, `rate_*`, `signal_*`, "Offline map loaded · 247 tiles",
  "0s / 30s", …) are asserted by device tests; renaming them is a breaking
  change.

## Ignored and generated artifacts

Policy (enforced by `.gitignore`):

- **Never committed:** raw/processed dataset (`data/raw/`, `data/processed/`),
  Python environments (`.venv/`), Gradle caches and build outputs
  (`.gradle*/`, `mobile/**/build/`, `mobile/.gradle-user-home/`), local
  machine files (`local.properties`, `.env*`, keys), model weights
  (`models/**/*.tflite` and friends).
- **Local evidence only:** `mobile/artifacts/` (device screenshots, pulled
  recordings, map evidence), `reports/position_plots/`, `reports/figures/`,
  `reports/results/`. Evidence is summarized into tracked Markdown; bulky raw
  captures stay local.
- **Tracked by exception:** historical evidence JSONs already in `reports/`
  (~23 MB) are preserved as written; do not add more bulk JSON to git — write
  new evidence as Markdown with numbers inline, or keep JSON local.
- **Generated but tracked:** the Gradle wrapper (verified by SHA-256 in
  `gradle-wrapper.properties`) and the bundled map pack manifest + tiles under
  `mobile/app/src/main/assets/offline/hyderabad/` (247 tiles, integrity pinned
  in `manifest.json` and asserted by `OfflineMapTest`).
- **Session data on device** lives in app-private `no_backup/recordings/`;
  it is never synced (allowBackup=false) and exported only by explicit user
  action to a local document destination.

When in doubt: if a file is produced by a command, it is ignored or summarized;
if it is an input or a decision, it is tracked.
