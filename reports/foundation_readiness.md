# Foundation readiness review — 2026-09-16

## Decision: NO-GO for Phase 2 AI and production EKF

The repository has a useful Python research foundation and a working Android
simulation shell. It does **not** yet have a reviewed corrected Phase 1 INS or
an Android sensor/recording pipeline. Passing the existing tests cannot certify
components those tests do not exercise. No AI training or production EKF was
started. No dataset sequence is currently approved for AI velocity training.

This review completes the bounded audit and an initial cross-language contract
fixture check. It does not complete the missing implementations. Readiness must
be reevaluated after the repairs below; do not reinterpret this report as a go.

## A. Python gates

- **PASS — configurable paths.** `training/common.py` centralizes the default
  `data/raw/iovnbd` and explicit `--data-root`. It does not silently select another
  directory. Existing path, override, missing-file and side-effect tests pass.
- **PARTIAL — normalized schema and units.** `schema_registry.json`,
  `data_dictionary.md`, `training/phase0/schema.py` and shared conversion helpers
  preserve source labels, inferred units and feature restrictions. Unit inference
  is not universal certification of every column or physical axis. The historical
  INS still reads raw export channels and must not be called a normalized,
  validated deployable IMU pipeline.
- **PASS for documentation, FAIL for training approval — synchronization.**
  `synchronization_analysis.md` and `phase0_evidence.json` quantify uncertainty,
  gaps, lag hypotheses and row mismatches. No pair is classified exact. Across
  144 file pairs: 20 approximate, 94 uncertain, 30 unusable. Copies represent
  only 72 named synchronized recordings, not 144 independent sequences.
- **PARTIAL — coordinate definitions; FAIL — physical transform.** FLU vehicle,
  ENU navigation, proper rotations and causal-calibration mathematics are explicit
  in `frame_math.py` and the frame reports. The newest
  `frame_resolution_followup.md` supersedes optimistic assumptions about the
  old exporter: acceleration/gravity appear world-like, the complete physical
  gyro triad and stable mounting transform remain unresolved. Export labels
  Yaw/Pitch/Roll matching X/Y/Z do not establish vehicle yaw/pitch/roll axes.
- **FAIL — requested INS synthetic suite.** The current 70 pre-existing tests
  cover foundation, Phase 0, frame mathematics and export diagnostics. They do
  not exercise `run_ins` with the full stationary/constant-velocity/acceleration/
  circular-motion/bias/irregular-delta suite required by Phase 1. Four additional
  contract tests added here do not close that gap.
- **FAIL — physically credible short real-data baseline.** The historical
  60-second replay is numerically reproducible but its large error is not accepted
  as merely normal inertial drift. See measured results below.
- **PARTIAL safeguard, FAIL two-mode implementation — initialization.** The
  historical CLI refuses ordinary execution unless `--offline-baseline` explicitly
  permits future-GPS/VBOX-assisted replay. That is a valuable safety guard, but
  there are not two implemented, tested `evaluation` and `deployable` modes.
  The latter's causal gravity/GNSS calibration must be connected only to verified
  device-frame inputs. Current replay additionally combines initial smartphone
  heading with VBOX-derived initial velocity.
- **PARTIAL — reproducibility/provenance.** Historical Phase 0 evidence records
  two byte-identical full runs and unchanged raw files. Current hashes differ
  from that record for `audit.py`, `schema.py`, `statistics.py`; `__init__.py` and
  `reporting.py` still match. The historical reports are preserved, not falsely
  attributed to the current source. No full Phase 0 refresh was run during this
  bounded review. The historical INS replay was rerun twice with identical stdout.

## Measured and historical numerical evidence

Fresh, read-only M (Driver B), first 601 rows / 60 seconds:

- Reference distance: **429.787636 m**.
- Final / maximum position error: **1,644.649825 m**.
- Mean position error: **383.444221 m**; position RMSE: **583.438455 m**.
- Drift/reference distance: **382.665690%**.
- Scalar speed MAE: **82.157219 km/h**; RMSE: **126.273792 km/h**.
- Initial smartphone displacement heading: **62.884°**; VBOX initial heading:
  **44.112°**; initial reference speed: **5.458889 m/s**.
- No valid stationary gyro-bias samples in this segment; bias defaults to zero.
- These are scalar speed errors, not vector velocity errors. No reliable
  heading-error report or requested stationary/straight/turn/braking segment
  suite is delivered by this replay.

Evidence interpretation: fixed nominal 0.1 s row deltas, unverified export-frame
assumptions and initialization differences confound bias/noise/drift attribution.
Identical output is reproducibility, not scientific validity. The old
`phase1_metrics.md` remains a historical experimental report; its suggestion to
proceed to AI correction is **not** the current readiness verdict.

Historical Phase 0 M speed evidence, same rows and km/h comparison domain:

- Raw smartphone numbers treated as km/h: MAE **25.979700**, RMSE **29.970314**.
- Smartphone numbers multiplied by 3.6: MAE **4.223081**, RMSE **7.437801**.
- Correlation is approximately **0.932429 in both cases**; scale-sensitive error,
  not correlation alone, supports smartphone m/s despite the Kmh source label.
- Smartphone scaled mean/median/max: **35.877776 / 36.684 / 97.272 km/h**;
  VBOX: **35.705738 / 36.443 / 100.688 km/h**.
- M same-row position separation median/mean/p95/max:
  **21.782383 / 29.922638 / 87.236462 / 168.906318 m**.
- M full durations differ by **2.323 s**, with two smartphone DATE gaps and one
  elapsed-counter backward step. The first 60 s has no DATE gap. The full-trace
  diagnostic best lag of -3.5 s is **not** a deployable calibration parameter.
- IMU logging is nominally about 10 Hz for representative synchronized files;
  most copies show GPS value changes near **0.111 Hz**, not a fresh fix on every
  tenth row. Value-change frequency is not certified hardware sample frequency.

The latest frame-export study considered 241 smartphone files / 97 recording
groups. Its three unfiltered reconstruction hypotheses each passed **0/127**
eligible windows; the filtered reconstruction passed **1/127**. A local success
does not approve a whole recording, an exporter or a universal rotation.

## B. Android gates

- **PASS — Compose build.** AGP 9.1.1 / Gradle 9.3.1, Kotlin/Compose compiler
  2.2.10, compile SDK 37 / target 36 / minimum 26. Fresh assemble and lint pass.
- **PASS — simulation and labels.** Dashboard, Diagnostics, About, Start/Stop,
  latest-snapshot StateFlow and background cancellation exist. Every screen
  explicitly labels simulation; readings are independent scripted UI fixtures.
- **NOT IMPLEMENTED — sensors and GNSS.** Source inspection finds no acquisition
  implementation or location/sensor permissions. A connected phone does not mean
  SensorManager/GNSS acquisition has been validated. See exact future procedure
  below; that procedure is not a substitute for missing code.
- **NOT IMPLEMENTED — recording/replay.** No recorder, serialized trip reader,
  export action or real replay source exists. JSON contract fixtures are not a
  trip-recording implementation. This is a hard gate failure.
- **NOT IMPLEMENTED — NavigationEngine.** Simulation logic is independent of
  Compose, but `SimulationController` is not a navigation engine. A proposed
  UI-independent interface boundary is documented in `contracts/v1/README.md`;
  production bindings and implementation remain absent.
- **NOT IMPLEMENTED — replaceable/offline map layer.** The shell intentionally
  has no map. A map boundary and no-map offline fallback strategy are proposed
  in the contract; no replaceability/cache/offline-tile test can pass yet.

Device status: OnePlus 12R / CPH2585, Android 16 / API 36, authorized ADB device
observed during this review. The unchanged app implementation previously passed
5 instrumented UI/lifecycle tests and manual Start/Stop/screen checks on this
phone (see `mobile/VERIFICATION.md`). This review reran host build/unit/lint checks,
not the connected test suite. Test-only contract dependencies do not add sensors
or network access to the installed application. Other device/API combinations
remain unverified.

### Exact future acquisition/recording acceptance procedure

Owner: Android acquisition developer, with the project owner operating the device.
Do these tests while stationary or with a passenger operating the logger.

1. Implement acquisition and local recording first. A build showing only
   SIMULATION is an immediate **not implemented**, not a device-test pass.
2. Build and run unit/instrumented tests using commands in `mobile/README.md`;
   run `adb devices -l`, select the intended serial, install after connected tests.
3. Verify deny, approximate-location, precise-location, permission-revoke and
   lifecycle transitions. Missing hardware must show unavailable, never zero
   readings masquerading as valid measurements. Do not request background
   location/service permissions until the feature actually requires them.
4. Record six stationary faces for >=10 s each and known +90°/-90° turns about
   each device axis, three repeats per direction. Inspect sensor identifiers,
   original monotonic event/receipt times, units and accuracy. Apply the independent
   calibration acceptance protocol in `frame_resolution_followup.md`; a phone's
   new capture does not retroactively certify IO-VNBD export semantics.
5. Outdoors, obtain fresh GNSS fixes; demonstrate nullable speed/bearing and
   provider/accuracy/satellite fields. Test a fixture with absent speed/bearing
   independently of whether this device normally supplies them. Loss of GNSS
   must transition to unavailable/stale, not a silently repeated healthy fix.
6. Record >=5 minutes, stop, export locally and replay on Kotlin and Python.
   Compare event counts, IDs, source tags, nullability and exact timestamp integers;
   compare numeric payloads with contract tolerances. Truncated/corrupt records
   must be rejected or explicitly diagnosed. No uploads are allowed.
7. Test app backgrounding, restart, bounded-queue overflow and late/duplicate
   events; verify no listener survives its owner and no silent auto-resume occurs.
   Inspect heap/queue bounds during a >=30-minute stationary endurance capture.
8. Save measured rates, gaps, drop counts, permission outcomes, device/build IDs
   and fixture hashes in a new dated device-validation report. Only then revisit
   the Android acquisition/recording gates.

## C. Integration contract status

**DEFINED AND FIXTURE-TESTED, NOT PRODUCTION-INTEGRATED.**
`contracts/v1/README.md` specifies IMU, GNSS, calibration, navigation, GNSS quality,
confidence and diagnostic events: units, frames, exact integer-string nanosecond
timestamps, receipt time, sampling/gap proposals, nullability, versions, UTF-8 JSON
and future JSONL serialization, quaternion conventions and numerical tolerances.

`contracts/v1/golden.json` contains eight synthetic events across seven event
types. Python-native and Kotlin-native fixtures are separately serialized and
compared with the same golden values. Both sides test exact timestamps above
2^53, explicit null speed/bearing/uncertainty, signed gyro SI units and +90° ENU
yaw mapping vehicle-forward to north. New checks: **4 Python + 4 Kotlin passed**.

These are positive interoperability/math smoke tests. Full typed production
serializers, malformed-input rejection, device-clock conversion, resampling,
filter-state parity and replay integration are not implemented or tested here.
No schema fixture is proof that Android reproduces Python preprocessing.
Engineering gap/buffer thresholds in the proposed contract require review and
device evidence; they are not measured guarantees.

## D. Training admission and leakage

**Approved training sequences: none. Approved evaluation sequences: none.**

`training_admission.json` records an explicit false approval for every one of the
144 audited pair IDs / 72 sequence names, preserving historical classification
and split-group IDs. No gate is passed by omitting an inconvenient sequence.

Ten unique names remain **conditional diagnostic candidates only**: m, s2, vta16,
vta2, vta26, vta30, vta8, vtb2, vw11, vw14c. They still fail complete frame,
inertial-label correspondence, corrected-baseline and preprocessing-parity gates.
The other 124 file pairs remain quarantined as uncertain/unusable in Phase 0.
All 144 are rejected from a Phase 2 run **at present**; this is a hold for evidence,
not irreversible deletion or a claim that no future use is possible. Unpaired
recordings are not eligible for VBOX-supervised training without verified labels.

Readiness conditions still unmet:

- Units are supported for many labels, but mapping and alignment remain conditional.
- Diagnostic GPS agreement does not certify IMU-to-VBOX velocity alignment.
- Whole-sequence splits are possible in principle, but a frozen executable split
  manifest merging duplicate/overlap-linked sessions has not been built/tested.
- Schema feature restrictions and tests exist; no deployable feature-extraction
  pipeline has yet demonstrated end-to-end exclusion of VBOX/CAN fields.
- Do not fit lag, scaling, mounting, normalization or filter parameters on a held-out
  test sequence. The existing diagnostic searches cannot be imported as test-set
  calibration. Split recording groups BEFORE making windows or fitting anything.
- A corrected reproducible baseline and trustworthy segment metrics are missing.
- Android/Python real preprocessing parity is missing. Contract parity is narrower.

## Tests, commands and preservation

Fresh results:

- `python -B -X utf8 -m unittest discover -s tests -q`: **74 passed**.
- AST parse of every Python source under `training/` and `tests/`: **25 parsed**.
- `python -B -X utf8 -m training.ins_mechanization --data-root data/raw/iovnbd
  --duration 60 --offline-baseline --no-write`: repeated twice; identical stdout
  and successful exits. Historical Phase 1 report/plots were not regenerated.
- From `mobile`, `gradlew.bat testDebugUnitTest lintDebug assembleDebug`: **16 JVM
  tests passed**, APK built, lint **0 errors / 8 warnings**. Remaining lint findings
  are pinned-version/target notices, including the new test-only Gson dependency.
- The initial offline Kotlin check lacked `error_prone_annotations:2.27.0`; the
  authorized online dependency fetch resolved it. This is not an open blocker.
- Gradle currently warns that the test resource `srcDir` API is deprecated; it
  works with the pinned toolchain. Migrate when updating that toolchain.
- Existing report hashes/sizes/timestamps and raw-data checks are verified without
  regenerating historical analyses. All 1,186 raw files match the historical
  checksum/size/mtime manifest. No raw file was modified or removed.

Current-source full Phase 0 determinism remains intentionally unverified. After
source/scientific fixes, refresh into a NEW directory, preserving old provenance:

```powershell
python -m training.phase0.audit --data-root data/raw/iovnbd --report-dir reports/phase0_refresh --verify-repeat
```

Do not run that command blindly over historical reports. Compare outputs and
review differences before promoting a refreshed registry.

## Owners and concrete fixes

These are proposed responsibility roles, not claims that another person has
accepted an assignment. The project owner must assign them before execution.

1. **Project owner / dataset maintainer — P0 frame evidence.** Obtain original
   logger/export settings/source and mount metadata. If unavailable, approve an
   alternative validated dataset or acquire independently referenced calibration
   data. Do not guess axes to make the INS pass. Exit: signed complete gyro triad,
   acceleration semantics and a stable proper mounting rotation independently
   validated outside fitting windows.
2. **Android acquisition developer — P0 capture and replay.** Implement real
   source types, permission/lifecycle ownership, bounded timestamped buffers,
   streaming local recording, export and replay. Exit: procedure above with
   actual device evidence; simulation stays clearly distinct.
3. **Python/navigation developer — P0 corrected INS.** Once frame evidence passes,
   implement validated units/timing, explicit two-mode initialization, proper
   specific-force mechanization, supported bias estimation and physical synthetic
   tests. Exit: required real segments, all intermediate diagnostics, repeated
   outputs and evidence-based drift interpretation. No AI/EKF shortcuts.
4. **Integration developer — P1 contract implementation.** Implement UI-independent
   NavigationEngine DTOs/strict serializers and a replaceable map adapter with
   offline/no-map behavior. Add invalid/old-version/corrupt-record tests and shared
   real-recording preprocessing fixtures. Exit: Python/Kotlin parity across the
   actual causal preprocessing pipeline, not only this golden JSON.
5. **Data/evaluation owner — P0 admission and provenance.** Freeze deduplicated
   sequence/session splits, train-only alignment/calibration/normalization,
   deployable feature allowlist and evaluated lag sensitivity. Refresh Phase 0
   provenance intentionally and approve sequences individually. Exit: training
   admission manifest contains evidence-backed approvals plus untouched evaluation.

## Next actions / bounded prompts

Use `foundation_next_steps.md` in order. The finite review/contract work above is
complete; missing foundation implementations are not. The immediate buildable
next track is Android acquisition, beginning with strict typed contract bindings,
while the project owner resolves old-dataset
frame metadata. Phase 1 cannot be scientifically repaired by coding around that
external evidence gap. **Do not start AI or production EKF until this entire
readiness report is rerun and every required gate passes.**
