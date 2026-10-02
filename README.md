# Intelligent Dead Reckoning

[![CI](https://github.com/SaiChayank/intelligent-dead-reckoning/actions/workflows/ci.yml/badge.svg)](https://github.com/SaiChayank/intelligent-dead-reckoning/actions/workflows/ci.yml)

Intelligent Dead Reckoning targets SIH problem statement **#26168**: keeping
vehicle navigation useful when satellite positioning becomes unavailable or
unreliable, such as in tunnels, parking structures, and urban canyons. Dead
reckoning estimates movement from a previous position using motion sensors.
The project plans to combine smartphone inertial sensing with learned error
correction and sensor fusion.

## Product and architecture

The **Android application is the main product**. The app will eventually provide
vehicle positioning, GNSS availability states, calibration guidance, confidence
information, and map display, with inference on the device. The Kotlin + Jetpack
Compose application lives in `mobile/`: Home (Dashboard), Map, Signals
(Diagnostics) and About screens with Start/Stop controls and clearly labelled
simulation, recorded or real phone measurements. Foreground IMU/GNSS acquisition
is implemented and device-verified; local recording with recovery, local export
and read-only replay are implemented; the map draws the labelled synthetic
fixture, saved recordings and live phone GNSS. Navigation estimation is not yet
connected.

- `training/`: the current Python offline research pipeline. It inspects data,
  verifies schemas and timing, and runs experimental navigation diagnostics.
  Future responsibilities include training, evaluation, and model export.
- `core/`: the future shared navigation core for calibration, coordinate frames,
  propagation, and fusion. Reserved and empty — see [core/README.md](core/README.md).
  `training/common.py` is a data-analysis utility module, not this navigation core.
- `mobile/`: the Android application in Kotlin and Jetpack Compose:
  acquisition, recording with recovery, export, replay, session library and the
  offline map with its three presentation sources. Navigation estimation and
  local model inference remain future work. See [Android setup](mobile/README.md).
- `edge/`: a secondary deployment target for higher-frequency sensor hardware.
  The planned edge engine will reuse navigation logic; it is a reserved,
  currently empty directory — see [edge/README.md](edge/README.md).
- `models/`: future trained/exported model artifacts; reserved and empty —
  see [models/README.md](models/README.md).
- `reports/`: existing empirical evidence and experimental results.
- `tests/`: small synthetic-fixture tests; no raw dataset is required for tests.
- `docs/`: project proposals and architecture plans. These describe intended
  capabilities, not proof that they have been implemented.

The design sources are the current-state exports in `docs/`:
[project documentation](docs/SIH_PS26168_Project_Documentation_Current_State.pdf),
[master blueprint](docs/SIH_PS26168_Master_Blueprint_Current_State.pdf), and
[implementation plan](docs/SIH_PS26168_Planning_and_Implementation_Plan_Current_State.pdf).

Repository layout, ownership boundaries and the generated-artifact policy are in
[ARCHITECTURE.md](ARCHITECTURE.md); the pinned toolchain and verification
commands in [DEVELOPER_SETUP.md](DEVELOPER_SETUP.md); the continuous-integration
gates, what CI cannot verify, and the manual physical-device acceptance gates in
[CI.md](CI.md).

## Current state

**Phase 2 readiness: NO-GO.** The [foundation readiness review](reports/foundation_readiness.md)
records the measured evidence, training holds and [bounded continuation prompts](reports/foundation_next_steps.md).
The [current-state audit](CURRENT_STATE_AUDIT.md) classifies every subsystem and
lists the recommended build order. The [navigation contract](contracts/v1/README.md)
has strict Python/Kotlin codecs. Recording/replay are implemented and verified;
corrected INS remains a missing gate.

### IMPLEMENTED — code exists in the tree

- **Contracts** — versioned measurement (`contracts/v1`), recording
  (`contracts/recording/v1`), experiment (`contracts/experiment/v1`) and
  evaluation-report (`contracts/evaluation/v1`) schemas
  with strict Python and Kotlin codecs, golden/invalid fixtures, and
  cross-language parity tests. These are the only data contracts; nothing else may
  define a parallel schema, and the experiment layer composes the other two by
  reference rather than redefining them.
- **Android acquisition** — foreground IMU (accelerometer, gyroscope,
  magnetometer, gravity) plus GNSS/network fixes and satellite status, with
  permission handling and bounded queues. See [mobile/ACQUISITION.md](mobile/ACQUISITION.md).
- **GNSS quality and outage** — a deterministic state machine over the frozen
  `GnssState` set (`unavailable`, `acquiring`, `good`, `degraded`, `stale`,
  `denied`), with stable reason codes and at most one diagnostic per real change
  of state. Its thresholds are per provider and measured against the real
  recordings instead of assumed, and a later EKF innovation test may only lower
  trust through a one-way input. See [mobile/GNSS_QUALITY.md](mobile/GNSS_QUALITY.md).
- **Recording** — local session writing with atomic metadata finalization,
  interrupted-session recovery and the frozen `measurements.jsonl` format.
  See [mobile/RECORDING.md](mobile/RECORDING.md).
- **Export** — explicit local ZIP export through the system document picker,
  local destinations only, private original never modified.
  See [mobile/EXPORT.md](mobile/EXPORT.md).
- **Replay** — read-only session replay with explicit `replay_real` /
  `replay_simulation` labels. See [mobile/REPLAY.md](mobile/REPLAY.md).
- **Phone-to-vehicle calibration** — the first real `NavigationEngine`
  implementation, reached only through the navigation runtime: gravity/tilt
  alignment from resting windows, yaw from straight-motion evidence with GNSS
  validation, gyroscope bias from rest, an accelerometer bias only when the
  resting attitudes justify one, and remount detection that expires a
  calibration when the phone is moved. It estimates the mounting frame and
  nothing else — no position, velocity or heading solution, and no learning.
  See [mobile/CALIBRATION.md](mobile/CALIBRATION.md).
- **GNSS+INS fusion** — the first production navigation solution: a classical
  15-state error-state EKF (position, velocity, attitude error, gyro bias,
  accelerometer bias, covariance) whose prediction is the validated strapdown
  baseline, with joint chi-square-gated GNSS position/velocity updates,
  rejected-update diagnostics, prediction-only outage coasting, and recovery
  that converges toward fixes instead of snapping to the first one. It emits
  only canonical `NavigationState` / `Confidence` / `DiagnosticEvent`, and a
  persistent gate stalemate stops the run rather than publishing an unbounded
  position. See [mobile/FUSION.md](mobile/FUSION.md).
- **Vehicle-motion constraints** — gated zero-velocity updates and non-holonomic
  lateral/vertical constraints inside the fusion pipeline: a rolling stationary
  detector implementing the project's declared resting rule, speed/yaw-rate/
  calibration/longitudinal-force gates that stand the constraints down in parking,
  turns, acceleration, braking and grades, and constraint refusals booked separately
  from GNSS rejections. On held-out synthetic outage drives they cut outage-end error
  by 71%, outage RMSE by 68% and stop drift by 50% with no regressions
  ([constraints report](reports/constraints_ab_2026_09_30.md)).
- **Python session validation** — `tools/validate_phone_recording.py` validates
  phone or local sessions against the same rules the on-device reader enforces;
  `tools/parity_probe.py` keeps the two readers honest against each other.
- **Experiment collection and evaluation foundation** — an experiment
  (`contracts/experiment/v1`, [reference](contracts/experiment/v1/README.md))
  binds recording sessions to the context an evaluation needs: known phone
  mounting orientation and how it was established, one shared boot-identity clock,
  calibration/motion/scenario intervals, observed GNSS good/degraded/lost/recovered
  intervals, an optional independent RTK/VBOX-class reference, and software GNSS
  outage masks for repeatable degraded-GNSS evaluation. `tools/seal_experiment.py`
  computes the session digests and the `integrity.sha256` covering the whole
  directory, `tools/validate_experiment.py` accepts or rejects one experiment or a
  corpus, and `tools/experiment_report.py` prints the timeline and coverage. The
  loader is strictly read-only: masking withholds GNSS records at read time and no
  recording is ever altered. See the
  [collection protocol](docs/PS26168_Experiment_Data_Collection_Protocol.md).
- **Map** — bundled offline Hyderabad vector tiles with four never-blended
  sources (synthetic fixture, recorded session, live phone GNSS and the real
  navigation engine's published output), coverage refusal, gap-split trails and a
  GNSS loss timeline. The engine source runs
  NavigationEngine → NavigationState → NavigationPresentation → MapOverlay →
  MapLibreRenderer, shows position, speed, trail, acquisition GNSS quality,
  runtime status and confidence radius, names the contract 1.1.0 localization
  mode, and offers an opt-in raw-versus-map-matched evaluation overlay.
  See [mobile/OFFLINE_MAP.md](mobile/OFFLINE_MAP.md) and
  [mobile/MAP_ENGINE_VIEW.md](mobile/MAP_ENGINE_VIEW.md).
- **Offline map matching** — a separate offline road-graph package derived
  from OSM for the same bounded Hyderabad region (`assets/roadgraph/`,
  ODbL-licensed, version/checksum/licensing manifest — deliberately not the
  rendering-only MBTiles) and a causal MVP matcher: nearby candidate search,
  distance to road, heading compatibility, previous-edge continuity and
  uncertainty gating, covering parallel roads, intersections, service roads,
  no-candidate, low-confidence and outside-coverage refusals. It reports the
  map-matched position beside the raw fused position and never overwrites
  navigation truth. Host-validated on synthetic fixtures and the real
  packaged graph, and reachable from the engine map view as an opt-in evaluation
  overlay; nothing downstream consumes it and it is not navigation.
  See [mobile/MAP_MATCHING.md](mobile/MAP_MATCHING.md) and the
  [validation report](reports/map_matching_2026_10_01.md).
- **Evaluation surface** — a versioned evaluation report
  (`contracts/evaluation/v1`) that an actual run produces: the evaluated session,
  the platform, the declared reference and whether it is independent, the GNSS
  segments, and per arm an implementation identity, a status and the measured
  accuracy / timing groups. The strict codec refuses a consumed reference, an
  all-null group, a convergence time without its bound and any metric for an arm
  that never ran. The app's Evaluation tab renders the checked-in report —
  classical INS, classical fusion, + constraints, + AI correction and + map
  matching — and states every absence (`not measured`, `not implemented`) rather
  than filling it. The harness regenerates that document from the production
  pipeline and the suite requires the two to match, so the screen shows the
  evidence the tests enforce. See [mobile/EVALUATION.md](mobile/EVALUATION.md) and
  the [report](reports/evaluation_surface_2026_10_01.md).
- **Offline research pipeline** — Phase 0 inventory/schema/unit/sampling/
  synchronization audit, schema registry and streaming diagnostics in `training/`.

### VERIFIED — backed by measured evidence

- **Device-verified** (OnePlus CPH2585 / Android 16): acquisition acceptance
  (8/8 connected tests, 30-minute endurance, bounded memory —
  [report](reports/android_acquisition_2026_09_19.md)); recording/export/replay
  flows and the map screen, including pixel-measured trail, framing and
  timeline behaviour ([MAP_DEVICE_VERIFICATION.md](mobile/MAP_DEVICE_VERIFICATION.md)).
- **Real-data verified**: 44 of 44 real phone sessions (688,345 records)
  accepted by the contract reader, including two recovered interruptions
  ([corpus report](reports/recording_corpus_2026_09_29.md)).
- **Real-data baseline**: the classical strapdown INS baseline measured on the
  longest real session (27 min 40 s, stationary reference): 271 m of horizontal
  divergence after one minute, 600.8 km after 27.7 min, with the vertical
  explained by the sensor's own 0.0119 m/s² magnitude residual. It consumes no
  GNSS at runtime, and the report names every limitation
  ([baseline report](reports/ins_baseline_2026_09_30.md)).
- **Host-verified**: 348 Kotlin unit tests, 272 Python tests (1 skip),
  `lintDebug` clean (0 errors, 1 pre-existing target-SDK warning). Host tests are
  not device evidence and are labelled where they are all that exists (see audit
  section 2).
- **Navigation confidence semantics** — the engine's covariance is published as
  `UNVALIDATED`, split into its own display field, drawn as a dashed ring and
  labelled as a model claim; the Android provider accuracy can never become fused
  confidence. The first measured comparison of predicted uncertainty against actual
  error (scripted truth, 692 samples) gives 97.9% coverage fused, 100% dead
  reckoning and 64.5% recovery, which supports the unvalidated exposure and does
  **not** support a calibrated claim. See
  [mobile/CONFIDENCE.md](mobile/CONFIDENCE.md) and the
  [evaluation report](reports/confidence_evaluation_2026_10_01.md).

### EXPERIMENTAL — research scripts; outputs are not product claims

- M (Driver B) synchronization searches, attitude and body-frame diagnostics,
  and the historical INS script `training/ins_mechanization.py` (382.67% drift
  over 429.8 m; gated behind `--offline-baseline` because it uses future GPS and
  VBOX velocity). These run for research only; they are not validated app
  navigation or production calibration, and that script is **not** the
  "physically correct classical baseline" any future AI result must be compared
  against — its inputs and its initial-state assumptions are exactly what
  [`training/strapdown_ins.py`](training/strapdown_ins.py) exists to replace,
  with no GNSS or reference-instrument input at all.

### PLANNED — not implemented

- `NavigationEngine` implementations beyond calibration and fusion:
  standalone propagation / INS-mechanization engines. The fusion engine is the
  session the map's engine view draws —
  [mobile/MAP_ENGINE_VIEW.md](mobile/MAP_ENGINE_VIEW.md). Calibration still reports
  no position, velocity or heading, and sessions recorded before it existed still
  carry `calibration: not_applied`.
- A calibration collection/composition flow in the UI, without which a device shows
  `—` for heading while position, speed and localization mode publish.
- AI error-correction training, on-device inference, offline routing, the shared
  navigation core (`core/`), and the edge runtime (`edge/`). The map matcher is
  reachable only as the engine view's opt-in evaluation overlay: it is not part of
  the runtime, and its output stays separate from navigation truth.
- A real GNSS-loss/recovery corpus (41 of 44 sessions have no GNSS at all), an
  in-coverage multi-point recording for device trail verification, and a sealed
  reference-bearing evaluation experiment, without which the fusion covariance stays
  `UNVALIDATED` and no calibrated 95% accuracy can be published
  ([what would license it](mobile/CONFIDENCE.md)).

### OPTIONAL — enabled at user discretion; no claim depends on them

- The synthetic map demo package: scripted paths, blackout simulation and
  comparison traces, labelled as a UI fixture throughout
  ([mobile/MAP_DEMO_FEATURES.md](mobile/MAP_DEMO_FEATURES.md)).
- Matplotlib plots and report generation in the research pipeline.
- Local recording itself: acquisition and display work without it.

## Run the Android demo

In Android Studio, choose **Open** and select this repository's `mobile` folder,
not the Python repository root. Let Gradle sync, select a connected Android phone
or emulator, and run the `app` configuration. The launcher name is **IDR Demo**.
No Python environment or IO-VNBD dataset is needed to run the Android demo.

See [mobile/README.md](mobile/README.md) for SDK/JDK requirements, PowerShell
build commands, tests, simulation behavior and scope limitations.

## Windows PowerShell setup

Supported runtime: **64-bit CPython 3.12**, verified with Python 3.12.14. Earlier
planning documents proposed Python 3.11; the current repository environment uses
3.12. Other Python versions have not been verified.

From the cloned repository root:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m pip check
$env:PYTHONUTF8 = "1"
```

If PowerShell blocks activation, activation is optional. Use the environment's
Python directly, for example:

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -X utf8 -m training.inspect_dataset --no-write
```

`requirements.txt` pins the four direct runtime dependencies: NumPy and pandas
for data processing, SciPy for experimental INS rotations, and Matplotlib for
optional plots. No ML, Android, or edge deployment dependencies are required.
The older `training/phase0/requirements.txt` redirects to this root definition.
Transitive dependencies are resolved by pip; this is not a full platform lockfile.
Do not commit `.venv/`.

## Dataset placement and configuration

Obtain and extract IO-VNBD separately; raw data is excluded from Git. The default
root is `data/raw/iovnbd`, resolved relative to this repository regardless of the
process's current directory. Keep the original dataset directory structure:

```text
data/raw/iovnbd/
  Synchronised V abd S datasets/
    Categorised IOVNB Dataset/
      M (Driver B)/
        S-M.csv
        V-M.csv
    ... other synchronized recordings ...
  Unsynchronised V and S Dataset/
    ... original unsynchronized recordings ...
```

The word `abd` is the dataset's actual spelling. The ten legacy diagnostics use
the M pair; the Phase 0 audit inspects the available dataset tree. A full audit
requires the complete extraction, including unsynchronized files and route images.

Every diagnostic accepts `--data-root PATH`. Explicit relative paths are resolved
from your current working directory. Absolute paths and paths with spaces work:

```powershell
python -m training.inspect_dataset --data-root "D:\Datasets\IO-VNBD" --no-write
```

An existing directory named `iovnbd_git` also works when supplied explicitly.
There is no automatic fallback or directory renaming. All root resolution is in
`training/common.py`; missing paths produce an error with the expected structure.
The shared encoding/normalization helpers preserve original labels and make no
automatic physical-axis or unit assumptions.

## Run the existing diagnostics without saving files

Run these commands from the repository root after environment setup. Each command
also accepts `--data-root PATH`; use `--help` for details.

```powershell
python -m training.inspect_dataset --no-write
python -m training.analyze_synchronization
python -m training.verify_alignment
python -m training.estimate_time_alignment
python -m training.affine_time_alignment
python -m training.final_alignment_check
python -m training.diagnose_attitude --duration 60
python -m training.diagnose_body_frame --duration 60 --top 3 --no-write
python -m training.diagnose_body_frame_v2 --duration 60 --top 3
python -m training.ins_mechanization --duration 60 --no-write --offline-baseline
```

These diagnostics read the M pair; even a short `--duration` run currently loads
that pair before selecting its window. Alignment searches may take longer.
The full `estimate_time_alignment` search took about 6.5 minutes during setup
verification. `analyze_synchronization` emits an existing pandas date-format
inference warning but completes successfully; that legacy parsing behavior is
retained.
Direct-file invocation also works for these ten scripts, for example
`python training/inspect_dataset.py --no-write`. Invoke Phase 0 as a module.

Frame-validation update: the three `diagnose_attitude` / `diagnose_body_frame`
entry points now delegate to the common physical-frame audit, defaulting to M.
They print separate maneuver windows and never write their old reports. Use
`python -m training.frame_audit --write-report` for the full six-sequence evidence.
See [body-frame conventions](reports/body_frame_conventions.md) before further INS work.
The [export-frame follow-up](reports/frame_resolution_followup.md) adds an important
restriction: the paired recordings' acceleration/gravity export appears world-aligned,
while gyro header copies expose a different device-axis naming scheme. Neither a
fixed export-to-vehicle rotation nor a full reconstructed raw-IMU contract is approved.
Do not pass the exported gravity vector directly into device-frame calibration.
Run the follow-up with `python -B -m training.frame_export_audit --no-write`.
Its explicit `--write-report` option writes only `reports/frame_export_evidence.json`.
The historical INS initialization now requires explicit `--offline-baseline`
consent because it uses later GPS samples and VBOX initial velocity. Its
propagation algorithm remains unchanged.

Output behavior:

- `inspect_dataset`: writes `reports/data_schema_raw.txt`.
- `frame_audit --write-report`: writes `reports/body_frame_metrics.md` and
  `reports/body_frame_metrics.json`; all three older frame diagnostics use this method.
- `ins_mechanization`: writes `reports/phase1_metrics.md` and
  `reports/position_plots/M_raw_ins_vs_vbox.png`.
- `training.phase0.audit`: writes its Markdown and JSON outputs to `reports/`
  or the explicit `--report-dir` directory.

Legacy report paths are relative to the working directory, so run from the
repository root. `--no-write` skips report/plot creation. The audit also supports
this flag. The other listed diagnostics only print to the terminal. Importing
shared modules does not load a dataset or run an analysis. Use Python's `-B`
option to suppress the interpreter's normal `__pycache__` files when needed.

## Scientific limitations and evidence

Use the empirical [data schema](reports/data_schema.md),
[data dictionary](reports/data_dictionary.md),
[synchronization analysis](reports/synchronization_analysis.md), and
[JSON registry](reports/schema_registry.json) when choosing subsequent work.

- Smartphone GPS speed has an incorrect `Kmh` label in examined recordings;
  empirical comparisons support m/s. Multiply by 3.6 only when explicitly comparing
  to VBOX km/h. Some legacy scripts still print raw speed comparisons and inherited
  labels; their calculations have been preserved, not adopted as unit evidence.
- Nominal M IMU rows are about 10 Hz. Stored GPS value changes occur much less
  frequently; repeated/forward-filled values do not establish sensor hardware rate.
- M has smartphone DATE intervals of 1.158 s and 1.365 s. Equal row counts do not
  establish exact synchronization or justify treating row index as a universal clock.
- Legacy offset/affine searches and body-frame fits use VBOX reference data.
  Fitted alignment/calibration is diagnostic evidence, not a deployable phone
  feature. Never fit it on a held-out test sequence for future evaluation.
- Corresponding CSV copies establish the source-header correspondence
  `Yaw/Pitch/Roll` to `X/Y/Z`, respectively. This is not a vehicle-axis mapping or
  a verified full physical gyro triad. Current frame diagnostics reject reflected
  rotations; historical candidate scores are not calibration approval.
- The experimental INS uses nominal sample time and VBOX-assisted initial
  velocity. It exhibits large drift and is not ready for standalone navigation.
  Its model and integration calculations were not corrected in this foundation task.

The Phase 0 reports are preserved historical artifacts. Refactoring audit code
changes source hashes, so their recorded provenance may not match today's source.
A future intentional audit rerun is required to refresh that provenance. New audit
runs include the shared utility source in their provenance hashes. Do not interpret
successful setup tests as new dataset verification or training approval.

## Verification and the next task

```powershell
python -B -X utf8 -m unittest discover -s tests -v
python -m pip check
python -B -X utf8 -c "import ast; from pathlib import Path; files=[p for d in ('training','tests') for p in Path(d).rglob('*.py')]; [ast.parse(p.read_text(encoding='utf-8-sig'),filename=str(p)) for p in files]; print('Parsed',len(files),'Python files')"
```

Experiment tooling, once drives have been collected. Neither command needs a
device, and neither writes to a recording:

```powershell
python tools/seal_experiment.py experiments/<experiment_id>
python tools/validate_experiment.py --corpus experiments
python tools/experiment_report.py --experiment experiments/<experiment_id> --mask <mask_id>
```

Tests use synthetic fixtures outside `data/raw/`. Preserve raw files unchanged;
the historical `reports/phase0_raw_manifest.json` records SHA-256, sizes, and
modification timestamps for integrity comparison.

Historical foundation verification passed: 32 tests, parsing of the then-current 19 project Python files,
21 CLI help checks (module and direct-file entry points), and all ten diagnostic
commands above. No scripts were blocked. All 1,186 raw files matched the saved
SHA-256/size/timestamp manifest, and all 12 pre-existing report files were
unchanged. The full Phase 0 audit was not rerun during this verification.

The exact command to start Prompt 2 (intentional Phase 0 provenance refresh) is:

```powershell
python -m training.phase0.audit --data-root data/raw/iovnbd --verify-repeat
```

That command performs two full analysis passes, checks deterministic output and
raw integrity, and **replaces the Phase 0 reports**. It is documented as the next
intentional step and is not part of the foundation smoke tests.
