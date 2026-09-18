# Intelligent Dead Reckoning

Intelligent Dead Reckoning targets SIH problem statement **#26168**: keeping
vehicle navigation useful when satellite positioning becomes unavailable or
unreliable, such as in tunnels, parking structures, and urban canyons. Dead
reckoning estimates movement from a previous position using motion sensors.
The project plans to combine smartphone inertial sensing with learned error
correction and sensor fusion.

## Product and architecture

The **Android application is the main product**. The planned app will provide
vehicle positioning, GNSS availability states, calibration guidance, confidence
information, and map display, with inference on the device. The Kotlin + Jetpack
Compose foundation now lives in `mobile/`: Dashboard, Diagnostics and About
screens with Start/Stop controls and clearly labelled simulation or real phone
measurements. Foreground IMU/GNSS acquisition is implemented; device acceptance
is pending. Navigation is not yet connected.

- `training/`: the current Python offline research pipeline. It inspects data,
  verifies schemas and timing, and runs experimental navigation diagnostics.
  Future responsibilities include training, evaluation, and model export.
- `core/`: the future shared navigation core for calibration, coordinate frames,
  propagation, and fusion. Its implementation and mobile integration are planned.
  `training/common.py` is a data-analysis utility module, not this navigation core.
- `mobile/`: the Android application foundation in Kotlin and Jetpack Compose.
  It supports a scripted demo and foreground sensor/location acquisition.
  Navigation and local model inference remain future work. See [Android setup](mobile/README.md).
- `edge/`: a secondary deployment target for higher-frequency sensor hardware.
  The planned edge engine will reuse navigation logic; it is not implemented.
- `models/`: future trained/exported model artifacts.
- `reports/`: existing empirical evidence and experimental results.
- `tests/`: small synthetic-fixture tests; no raw dataset is required for tests.
- `docs/`: project proposals and architecture plans. These describe intended
  capabilities, not proof that they have been implemented.

The design sources are [project documentation](docs/26168%20Documentation.pdf),
[master blueprint](docs/26168%20Master%20Blueprint.pdf), and
[implementation plan](docs/26168%20Planning%20and%20Implementation%20Plan.pdf).

## Current state

**Phase 2 readiness: NO-GO.** The [foundation readiness review](reports/foundation_readiness.md)
records the earlier state before contract/acquisition implementation. It includes
measured evidence, training holds and [bounded continuation prompts](reports/foundation_next_steps.md).
The [navigation contract](contracts/v1/README.md) now has strict Python/Kotlin codecs.
See [acquisition behavior and device procedure](mobile/ACQUISITION.md) for the new
foreground source. Recording/replay and corrected INS remain missing gates.

Implemented: the Phase 0 inventory/schema/unit/sampling/synchronization audit,
machine-readable schema registry, empirical reports, raw-file integrity checks,
synthetic tests, and shared dataset configuration. The audit streams files or
processes one recording/pair at a time rather than loading the full dataset.
The Android app has lifecycle-owned simulation and real acquisition controls,
permission handling, bounded queues and diagnostics. Simulation remains scripted;
real mode displays phone measurements, never inferred INS outputs.

Experimental: M (Driver B) synchronization searches, attitude and body-frame
diagnostics, and a classical strapdown inertial navigation system (INS) baseline.
These scripts can be run for research; their outputs are not validated app
navigation or production calibration.

Planned: body-frame/mechanization corrections, AI training, extended Kalman filter
(EKF) fusion, map matching, the shared navigation core, Android recording/replay
and inference, and the edge engine. The app foundation does not
implement or validate any of these planned features.

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
