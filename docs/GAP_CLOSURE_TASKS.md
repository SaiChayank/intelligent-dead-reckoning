# PS #26168 gap closure: detailed task list (phone-free work)

Working rules for every task: one branch per block (`gap/<n>-<name>`), local commits only, never push.
Every number quoted anywhere comes from a generated artifact. Nothing is called "AI-improved" unless
it beats the named non-AI baseline on held-out drives. If a result contradicts the plan, stop and
record it rather than tuning until it looks good. Toolchain and commands: see the memory notes
(`.venv`, JDK 25, `bash gradlew`, IO-VNBD in `data/raw/iovnbd`).

Standard verification ("V"), run at the end of every block:
- `.venv/Scripts/python.exe -B -X utf8 -W error::ResourceWarning -m unittest discover -s tests`
- `.venv/Scripts/python.exe -B -X utf8 -m pytest tests -q`
- `.venv/Scripts/python.exe -B tools/check_repo_hygiene.py`
- `cd mobile; bash gradlew testDebugUnitTest lintDebug assembleDebug --console=plain`
- `cd mobile; bash gradlew -p ../edge test --console=plain`
Gradle runs one at a time (shared build lock). Record counts in `reports/gap_closure_log.md`.

---

## Block A: finish Phase 1 (branch `gap/1-iovnbd-harness`)

**A1. Evaluation runs.** Running: `tools/iovnbd_outage_eval.py run --input vehicle` then `--input phone`.
Outputs: `reports/iovnbd_eval/{vehicle,phone}/` (summary.json, summary.md, outages.csv, plots/).
Done when: both finish, summary.json exists for each, `engine_restarts_after_failure` is recorded.

**A2. Read results; decide.** For each input and scenario (d50, d1000, t30, t60) read median and p90 drift,
pass rate (excluding and including failures), and the GNSS-aided accuracy. Compare against the
hold-last-GNSS-velocity baseline (reuse the throwaway computation: project last fix speed/course).
Decision rule: vehicle input within a factor of about 2 of the 10 % gate, or clearly better than
the baseline, confirms the earlier failures were phone-data limits. If it still fails badly, stop Block A,
investigate the engine (heading before the outage, speed runaway, NHC and mount handling) and do not
proceed to Block B until understood. Do not tune thresholds to improve the scores.

**A3. Fix known harness weaknesses before committing.**
- The engine restarts after failure (hundreds in the first run): confirm each restart is logged and scored
  (outages overlapping a failure count as failed). Report the restart count per arm in summary.md.
- Add the hold-last-GNSS-velocity baseline as a scored arm in `iovnbd_outage_eval.py` (Python only, from
  the recording's own GNSS stream) so it appears in every report.
- Make `summary.md` state in words which results are phone-export, vehicle-IMU, and synthetic.

**A4. Verification V.** All suites green; new Kotlin test `IovnbdEvalTest` passes; new Python tests pass.

**A5. Record and commit.** Append the Phase 1 gate and results to `reports/gap_closure_log.md`, including
the diagnosis section already written. Mark the 9 on-device failures as unverified (phone needed).
Commit on `gap/1-iovnbd-harness` with the repo identity; fast-forward local `main`.
Tracked files: tools, tests, edge sources, reports/iovnbd_eval (summary files and plots; keep the large
per-run CSVs out of git, they live in `data/processed`).

---

## Block B: Phase 2, speed model v3 (branch `gap/2-speed-v3`)

**B1. Data admission.** New `training/iovnbd_admission_v2.py`: a drive is admitted when its conversion
succeeded and either input calibration is valid; record per-drive reason codes. Keep drive-level splits
(`training/iovnbd_split.py`, LOGO folds). Output `reports/iovnbd_admission_v2.json`. Test: admitted set
is a subset of converted drives, no drive in two folds.

**B2. Features at the native 10 Hz.** Causal window of 5 to 10 s ending at the current sample, vehicle-frame
longitudinal and lateral acceleration, yaw rate, acceleration norm, time since last GNSS speed, last GNSS
speed. No future samples; no VBOX truth as an input except as the training label. Unit tests for
causality (changing a future sample does not change the feature).

**B3. Target and model.** Predict speed change since the last GNSS speed (anchored), plus sigma and
stop probability. Causal 1D-CNN or GRU, loss Huber + 0.5 NLL + 0.5 BCE as in `training/train_speed_estimator.py`.
Seeds fixed. Train with LOGO folds; keep the 12-candidate research exception documented.

**B4. Baselines to beat.** Hold-last-GNSS-speed, integrated longitudinal acceleration, predict-the-mean.
Score by outage drift through the Phase 1 harness (add a `speed_v3` arm), not only speed RMSE.
Gate: v3 lowers median outage drift versus the best non-AI arm on held-out drives. If not, do not ship it
and say so in the log.

**B5. Bump and idle filter.** In `fusion/StationaryDetector.kt` add a vertical jerk and energy gate that
suppresses speed-measurement updates during potholes and flags idling vibration. Host tests with injected
bumps on `ScriptedDrive`; no regression in existing stationary tests.

**B6. Kotlin port.** Make `ModelAWeights` shape-driven (channel count, window, layer sizes from JSON); add the
matching feature extraction in `AiSpeedEstimator.kt`; golden-vector parity test (Python vs Kotlin) in the
style of `PythonParityTest`. Export `models/speed_estimator_v3_weights.json` with SHA-256 in
`models/ai_model_manifest.json`; copy to `mobile/app/src/main/assets/models/`. Replace v1 in the app only if
B4's gate passed; otherwise ship the non-AI arm and keep v3 out of the app.

**B7. Verify (V) and commit.** Log baselines, v3 results, and the decision.

---

## Block C: Phases 3 to 6

**C1. Phase 3, learned GNSS trust (branch `gap/3-ai-fusion`).**
- Label: phone-GNSS horizontal error versus VBOX truth on IO-VNBD.
- Features: reported accuracy, satellites, speed/course consistency, EKF innovation (causal).
- Model: small MLP, numpy-portable; Kotlin port and parity test.
- Use: sets measurement R and gate in `GnssInsEkf.updateGnssPosition` behind a config flag, replacing the
  fixed rules in `acquisition/GnssQuality.kt`.
- Also: v3 sigma head drives the forward-speed measurement variance.
- Gate: GNSS-aided RMSE and post-outage recovery improve on held-out drives versus the rule-based arm.

**C2. Phase 4, HMM map matching (branch `gap/4-map-matching`).**
- Replace greedy `MapMatcher` with an online fixed-lag (about 2 s) Viterbi: emission from distance and heading,
  transition from route distance versus travelled distance. Keep the `MapMatchResult` API.
- Feed a cross-track position pseudo-measurement into the EKF next to the heading row
  (`GnssInsEkf.updateHeading`), only when the match is confident.
- Tests: parallel roads, junction ambiguity, tunnel (no candidate), U-turn; extend `RoadGraphFixtures`.
- Gate: matched arm beats unmatched on the curved and urban-canyon scenarios from
  `tools/generate_multi_scenarios.py` (Hyderabad; no UK road graph).

**C3. Phase 5, 10 Hz and transitions (branch `gap/5-rate-and-transitions`).**
- Confirm `publicationIntervalNs` (the engine default is 200 ms; `CalibratingFusionEngine` already uses 100 ms):
  make 10 Hz the single default and update tests and docs that say 5 Hz.
- Add switch-latency fields (last fix time, outage declared, first DR state) to `OutageBenchmarkReport`
  in `NavigationRuntime.kt`; test that no output gap exceeds one publication interval.
- Persist the last valid mount calibration per device (file in app-private storage) so a drive starting in a
  tunnel can dead-reckon; host test with a replayed session without GNSS.
- Magnetometer: gated heading aid when slow, with interference checks, or keep it off with measured evidence.

**C4. Phase 6, edge engine (branch `gap/6-edge`).**
- External-IMU adapter in `edge/InputAdapter.kt`: declared units, mount quaternion, rate; CSV and JSONL.
- Synthetic 200 Hz FOG-grade drive from `tools/generate_sample_drive.py` (labelled synthetic) and a run with
  `BenchmarkHarness`; report throughput, latency, drops. Keep the "measured, not inferred" rate rules.
- Two-wheeler profile: relaxed lateral and vertical NHC; synthetic leaning-turn test.
- Gate: sustained at least 200 Hz without drops on the synthetic input; adapter covered by tests.

---

## Block D: Phase 8, claims cleanup (branch `gap/8-claims`)

**D1. Single source of numbers.** A small generator (`tools/build_claims.py`) reads the summary JSONs and writes
the tables used by `README.md`, `reports/ai_dead_reckoning_final_report.md`,
`reports/sih_hackathon_pitch_deck.md`, `tools/visualizer/*.html`. Each table is labelled synthetic, IO-VNBD
phone export, IO-VNBD vehicle IMU, or field. Remove numbers that have no generated source.

**D2. Housekeeping.**
- Restore or remove links to the deleted `ARCHITECTURE.md`, `FINAL_RELEASE_READINESS.md`, `mobile/*.md`.
- `models/README.md` and `model_manifest.json` status must match `ai_model_manifest.json`.
- `git rm -r --cached edge/bin` and add it to `.gitignore`.
- README test counts regenerated from real runs.

---

## Waiting on the phone or a vehicle (not started)

- Diagnose the 9 failing on-device tests: AcquisitionUiTest (1), AndroidSecurityBoundaryTest (1, permissions:
  may be real), DesignSystemUiTest (2), ExportLifecycleDeviceTest (1), RecordingStorageDeviceTest (1),
  RecordingUiTest (3). Pull the logcat and test report, classify each as environment or real bug.
- Phase 7: follow `docs/FIELD_TEST_PROTOCOL.md`; seal experiments; run the Phase 1 report on real 100 Hz data;
  retrain the speed model with real vibration features; two-wheeler drives if possible.

## Stop conditions
Stop and record (do not push on) if: vehicle-input drift stays far over the gate (A2); v3 does not beat the
baselines (B4); a suite that passed earlier fails after a change; any result that looks too good to be true.
