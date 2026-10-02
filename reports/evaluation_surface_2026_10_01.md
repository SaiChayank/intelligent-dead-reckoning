# Evaluation surface — implementation and measured comparison report

Date: 2026-10-01. Scope: the engineering/evaluation surface for the classical INS, classical fusion,
+ constraints, + AI correction and + map matching arms; the `contracts/evaluation/v1` report that
backs it; the reproducible harness that produces that report; and what the shipped document actually
measured. Documented for operators in [mobile/EVALUATION.md](../mobile/EVALUATION.md); metric
definitions live in [contracts/evaluation/v1/README.md](../contracts/evaluation/v1/README.md).

## Result

**The surface is implemented, the report is reproducible, and every cell it shows is a measured
number or a stated absence.** The app renders the checked-in evidence itself: the Kotlin harness
regenerates `golden_report.json` from the production pipeline on every test run and requires the two
to match, and the same bytes are what the APK ships.

| Item | State |
|---|---|
| Evaluation report contract (`contracts/evaluation/v1`, Python + Kotlin) | **IMPLEMENTED** — strict codecs, 30-case negative corpus, 13 Python tests |
| Metrics against a declared, independent reference | **IMPLEMENTED + MEASURED** — scripted truth, 692 samples |
| Arm comparison (classical INS / fusion / + constraints / + AI / + map matching) | **IMPLEMENTED** — the four runnable arms measured; AI is `not_implemented` with a reason |
| Reproducible golden document | **VERIFIED** — regenerated and diffed by `EvaluationHarnessTest` on every run |
| Evaluation tab in the app | **IMPLEMENTED** — `tab_EVALUATION`, console-free, renders only what the report carries |
| Device memory, sensor-path latency, inference latency | **NOT MEASURED (absent, stated)** — no device run and no model in this repository |
| Field-reference accuracy | **NOT POSSIBLE YET** — no recorded session has an independent reference |

## What was built

1. **A report contract, not a table renderer.** `contracts/evaluation/v1` freezes the document, the
   metric definitions and nine failure codes. Its honesty rules are structural: an accuracy figure
   requires `reference.independent = true` and a non-`none` reference kind; `reference_consumed =
   true` is refused; an all-null accuracy or timing group is refused; `recovery_convergence_s`
   requires `recovery_threshold_m`; a non-`evaluated` arm must carry a reason and no metrics; a host
   platform may not carry device fields; p95 ≥ p50; arms are unique and bounded to the five names.
2. **Anchored INS, not a substitute baseline.** The `classical_ins` arm is the fusion engine aligned
   on the first fix and then fed no GNSS at all. `training/strapdown_ins.py` is deliberately not the
   arm: it has no GNSS-derived anchor and no heading reference, so comparing it would compare two
   different questions.
3. **A deterministic harness.** `EvaluationHarnessTest` drives `NavigationRuntime` →
   `FusionNavigationEngine` on the test dispatcher with an injected clock, one seed and a fixed
   scripted drive, scores every published sample against the closed-form trajectory, and writes
   `app/build/evaluation-report/scripted-outage-70s.json`.
4. **A read-only surface.** `EvaluationReports.kt` holds the data rules (refusal wording and the four
   absence strings) with no Android dependency beyond the codec; `EvaluationStore.kt` lists the
   bundled asset plus at most eight bounded documents from the app's own `evaluation` directory;
   `EvaluationScreen.kt` renders sections, provenance, segments, arm reasons and a limits card. The
   driver's Home and Signals pages were not touched, and the recording/replay consoles were made
   console-free on Map, Evaluation and About.
5. **A non-finite rule in both codecs.** A bare `NaN`/`Infinity` token is a float to Python's `json`
   but a string to Gson, so the two codecs disagreed (`NONFINITE` vs `INVALID_TYPE`). Both now
   refuse the token at their own parse layer, and a quoted `"NaN"` is refused the same way.

## The measured comparison

Host JVM replay, 2026-10-01, scripted truth: 70 s drive (straight, 92° turn, straight), IMU 100 Hz,
GNSS 1 Hz, denied 30–50 s; outage distance 240.0 m, recovery bound 5.0 m, one seed.
`fusion_map_match` carries no timing group: it is a post-processing pass over the constraints arm's
published positions.

| Metric | Classical INS | Classical fusion | + Constraints | + AI correction | + Map matching |
|---|---|---|---|---|---|
| Samples scored | 691 | 692 | 692 | `not_implemented` | 692 |
| Outage duration (s) | 20.0 | 20.0 | 20.0 | — | 20.0 |
| Outage distance (m) | 240.0 | 240.0 | 240.0 | — | 240.0 |
| Final position error (m) | 540.065 | 1.098 | 1.098 | — | 0.031 |
| Drift (% of outage distance) | 130.452 | 35.342 | 35.342 | — | 35.342 |
| Position RMSE (m) | 258.181 | 25.463 | 25.463 | — | 25.327 |
| Speed MAE (m/s) | 7.164 | 1.855 | 1.855 | — | 1.855 |
| Speed RMSE (m/s) | 8.693 | 2.426 | 2.426 | — | 2.426 |
| Heading error, mean abs (deg) | 2.507 | 32.876 | 32.876 | — | 32.876 |
| Recovery convergence (s) | never (null) | 1.1 | 1.1 | — | 1.1 |
| Navigation output (Hz) | 9.871 | 9.886 | 9.886 | — | (no timing group) |
| Inference latency p50/p95 (ms) | null | null | null | — | — |
| End-to-end p50/p95 (ms) | null | null | null | — | — |
| Queue high-water | 3 | 3 | 3 | — | — |
| Drops / errors | 0 / 0 | 0 / 0 | 0 / 0 | — | — |
| Memory peak (MB) | null | null | null | — | — |

All four evaluated arms report `reference_consumed = false`.

## Findings

**1. Anchored inertial coasting diverges on a 20 s outage.** The `classical_ins` arm ends 540 m from
truth and never falls back inside the 5 m bound; its end-of-outage drift is 130% of the 240 m
travelled during the outage. Its heading error is small because attitude is unobservable without
aiding on this drive and the turn is over before the outage. This is the number the fusion arms are
measured against, not a product baseline.

**2. Fusion bounds the outage and recovers quickly.** The same engine with GNSS aiding ends 1.1 m
from truth, converges to the 5 m bound 1.1 s after the fix returns without snapping to it
(`FusionNavigationEngine` prediction-only coasting), keeps 9.886 Hz output with a 3-record ingress
high-water and no drops or errors.

**3. The constraints A/B is degenerate on this drive, and the reason is pinned as evidence.**
`classical_fusion` and `fusion_constraints` are numerically identical: no constraint is ever offered.
The non-holonomic benign-dynamics gate reads the instantaneous forward specific force per sample and
requires 5 s contiguous below 0.35 m/s², but the scripted accelerometer noise is 0.5 m/s² per 100 Hz
sample, so the dwell never accumulates; the zero-velocity update has no such gate but the drive never
stops. `EvaluationHarnessTest.theConstraintsAbIsDegenerateOnThisDriveAndTheReasonIsMeasured` asserts
0 accepted and 0 refused in both arms, so fixing the gate breaks the test and forces the comparison
to be re-measured rather than quietly kept. The constraints stage's 71% outage-error reduction was
measured on held-out synthetic drives that include stops
([constraints report](constraints_ab_2026_09_30.md)); on this drive the constraint machinery is
simply not exercised. This is reported as it is: an A/B that measures nothing is not evidence of
anything.

**4. Map matching is optimistic by construction.** The `fusion_map_match` arm runs the shipped
`MapMatcher` over a synthetic road built along the truth path, so it measures the matcher's
projection onto a road that is right by construction (3 cm final error versus 1.1 m), not the road
network's agreement with reality. On this drive it cannot be worse than the raw arm, and the harness
asserts that bound.

**5. AI correction has nothing to run.** No trained error-correction model exists in the repository,
so `fusion_ai` is `not_implemented` with a reason instead of numbers, and the inference latencies
stay `null`.

## What is deliberately absent

`memory_peak_mb`, `end_to_end_p50_ms`/`p95_ms` and the inference latencies are `null`, and the
report's `platform.note` says why: a host replay has no device memory and no sensor-to-display path
to measure, and there is no model to time. The Evaluation screen renders those cells as
`not measured`, never as zero. Filling them requires a device run (or a trained model); until then a
report cannot claim them, and the UI does not.

## Limits

- **Reference authority.** `scripted_truth` is the closed-form trajectory the synthetic inputs were
  generated from. It is independent of every arm, reproducible, and **not field data**. No recorded
  session in this repository carries an independent reference, so no field-accuracy report exists
  yet and the screen's provenance line says exactly what the reference is.
- **Platform.** A host JVM replay cannot speak for device timing, memory or the sensor path. The
  platform block marks `host = true` and forbids device fields, so a device claim cannot be read
  into the document.
- **Coverage.** One drive, one seed, one bounded Hyderabad road construction. Reproducible, not
  representative.
- **The instrumented check is compile-only here.** No device is attached to this machine
  (`adb devices` is empty).

## Verification

- Kotlin JVM suite: **348 tests, 0 failures, 0 errors** (`EvaluationHarnessTest` regenerates and
  diffs the golden; `EvaluationSurfaceTest` 10 tests; `EvaluationStoreTest` 4 tests).
- Python: **272 tests OK, 1 environment-dependent skip**, including
  `test_evaluation_contract.py` (13) and `test_evaluation_asset.py` (3, SHA-256 byte equality
  between `contracts/evaluation/v1/golden_report.json` and the shipped asset).
- `lintDebug`: 0 errors, 1 warning (`OldTargetApi`, pre-existing). `compileDebugAndroidTestKotlin`
  and `assembleDebug` green; the debug APK contains `assets/evaluation/golden_report.json`
  (5,878 bytes); `assembleDebugAndroidTest` compiles the now-43 instrumented test methods, none of
  which ran.
- Hygiene: `check_repo_hygiene.py` — 262 tracked files checked, no findings. AST: 66 Python files
  parsed across `training/ tests/ contracts/ tools/`. Whitespace: only LF→CRLF notices.
- Regeneration command and the two-copy rule: [mobile/EVALUATION.md](../mobile/EVALUATION.md).

## Files

- `contracts/evaluation/v1/{models.py,codec.py,README.md,golden_report.json,invalid_reports.json}` and
  `kotlin/com/intelligentdeadreckoning/contracts/evaluation/v1/{Models.kt,EvaluationCodec.kt}`
- `mobile/app/src/main/java/com/intelligentdeadreckoning/app/evaluation/{EvaluationReports.kt,EvaluationStore.kt}`
- `mobile/app/src/main/java/com/intelligentdeadreckoning/app/ui/EvaluationScreen.kt`
- `mobile/app/src/main/assets/evaluation/golden_report.json`
- `mobile/app/src/test/java/com/intelligentdeadreckoning/app/{EvaluationHarnessTest,ScriptedDrive,EvaluationSurfaceTest,EvaluationStoreTest}.kt`
- `mobile/app/src/androidTest/java/com/intelligentdeadreckoning/app/EvaluationUiTest.kt`
- `tests/test_evaluation_contract.py`, `tests/test_evaluation_asset.py`
