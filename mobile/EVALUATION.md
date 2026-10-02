# Evaluation: the measured arm comparison, rendered

The Evaluation tab (dock item `Evaluation`, test tag `tab_EVALUATION`) shows one thing: the numbers
an evaluation run actually measured, for the classical INS, classical fusion, + constraints, + AI
correction and + map matching arms, side by side. It is the engineering surface for the question
"does this stage help?", and it is deliberately not a driver screen.

The screen computes nothing. It decodes a `contracts/evaluation/v1` document through the strict
codec and renders what is in it; every number in the table was measured by the run that wrote the
document. A document that fails the codec is refused with the code that refused it, never read
leniently and never partially rendered.

Two rules make fabrication structurally hard rather than merely frowned upon:

- **An accuracy figure may only exist when the reference is independent and the arm did not consume
  it.** The contract refuses `reference_consumed = true` and refuses any accuracy group with a
  `none` reference.
- **`not_run` and `not_implemented` are answers.** They must carry a reason and must carry no
  metrics.

In the UI, a cell for a metric an evaluated arm did not measure reads `not measured`; an arm the
report does not carry reads `not in this report`; an arm that did not run reads `not evaluated` or
`not implemented` and its reason appears below the table. No cell is ever filled with a zero,
averaged, or borrowed from another arm.

## Where it sits

- `Screen.EVALUATION` is a console-free page: the recording and replay panels do not render on it
  (`consoleFreeScreens = MAP, EVALUATION, ABOUT`), so the driver's Home/Signals flow stays as it
  was. No driver screen gained a metric.
- The table is horizontally scrollable: rows are metrics, columns are the five arm IDs in the order
  the request names them. Accuracy rows and runtime rows are separate sections because they carry
  different limits — one is scored against a declared reference, the other is not scored at all.
- Above the table, the provenance line states the reference kind, its independence, host vs device,
  session id and duration; below it, the absent-arm reasons and the session's GNSS segments. A
  limits card repeats what the run could not measure.

Code: `app/src/main/java/com/intelligentdeadreckoning/app/evaluation/EvaluationReports.kt` (the
data rules, device-free and unit-tested), `EvaluationStore.kt` (the report library: the bundled
document plus any `.json` in the app's own `evaluation` directory, bounded), and
`ui/EvaluationScreen.kt` (rendering only).

## The report document

`contracts/evaluation/v1` owns the schema, the metric definitions and the failure codes; see
[its README](../contracts/evaluation/v1/README.md). A report records the evaluated session, the
platform, the reference, the GNSS segments, and per arm an implementation identity, a status, and
`accuracy` / `timing` groups whose every field is either a measured number or an explicit `null`.

The app renders the checked-in `contracts/evaluation/v1/golden_report.json`. That file is not
decorative: the Kotlin suite regenerates it from the production pipeline on every run and requires
the two to match (non-numeric fields exactly, metrics to 1e-6 relative), so the document the user
reads is the document the tests enforce.

## Regenerating the golden report

The harness is `EvaluationHarnessTest`. It replays one scripted ground-truth drive
(`ScriptedDrive`) through `NavigationRuntime` → `FusionNavigationEngine` once per arm, scores every
published position against the trajectory the inputs were generated from, and writes
`mobile/app/build/evaluation-report/scripted-outage-70s.json`. It is deterministic: one seed, a
fixed drive, an injected clock and the test dispatcher.

```bash
# from mobile/, with JAVA_HOME / ANDROID_HOME set as in README.md
bash gradlew testDebugUnitTest --offline --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process --tests '*EvaluationHarnessTest'
```

If a change to an engine, the drive or a metric definition moves a number, the harness fails and the
document must be regenerated **into both places**, which are kept byte-identical:

1. `contracts/evaluation/v1/golden_report.json` — the contract's fixture.
2. `mobile/app/src/main/assets/evaluation/golden_report.json` — the copy the APK ships.

`tests/test_evaluation_asset.py` asserts the two files are byte-for-byte equal by SHA-256, that the
asset decodes as a valid five-arm host report with an independent reference, and that it claims no
device fields and contains no `NaN`. There is deliberately no Gradle staging task: the build copies
nothing, and a missing or edited asset fails a test instead of silently changing what the app shows.
If the golden is absent from the test classpath, the harness's failure message names the generated
file to copy.

## What the shipped golden measured

Host replay, 2026-10-01, scripted truth: a 70 s drive (straight, 92° turn, straight), IMU at
100 Hz, GNSS at 1 Hz, GNSS denied from 30 s to 50 s. Outage distance 240.0 m, recovery bound 5.0 m.
`fusion_map_match` carries no timing group — it is a post-processing pass over the constraints arm's
published positions, not a runtime.

| Metric | Classical INS | Classical fusion | + Constraints | + AI correction | + Map matching |
|---|---|---|---|---|---|
| Samples scored | 691 | 692 | 692 | not implemented | 692 |
| Outage duration (s) | 20.0 | 20.0 | 20.0 | — | 20.0 |
| Outage distance (m) | 240.0 | 240.0 | 240.0 | — | 240.0 |
| Final position error (m) | 540.065 | 1.098 | 1.098 | — | **0.031** |
| Drift (% of outage distance) | 130.452 | 35.342 | 35.342 | — | 35.342 |
| Position RMSE (m) | 258.181 | 25.463 | 25.463 | — | **25.327** |
| Speed MAE (m/s) | 7.164 | 1.855 | 1.855 | — | 1.855 |
| Speed RMSE (m/s) | 8.693 | 2.426 | 2.426 | — | 2.426 |
| Heading error, mean abs (deg) | 2.507 | 32.876 | 32.876 | — | 32.876 |
| Recovery convergence (s) | never (null) | 1.1 | 1.1 | — | 1.1 |
| Navigation output (Hz) | 9.871 | 9.886 | 9.886 | — | (no timing group) |
| Queue high-water | 3 | 3 | 3 | — | — |
| Drops / errors | 0 / 0 | 0 / 0 | 0 / 0 | — | — |

Readings that matter, and their limits:

- **Anchored inertial coasting diverges.** Without GNSS the classical INS arm ends 540 m from
  truth and never recovers to the 5 m bound; its drift is 130% of the distance travelled during the
  outage. Headings stay close only because attitude is unobservable without aiding and the drive's
  turn is over before the outage.
- **Fusion bounds the outage.** The same engine with GNSS aiding ends 1.1 m from truth, converges
  within 1.1 s of the fix returning, and holds 9.886 Hz output.
- **The constraints A/B is degenerate on this drive, and the reason is pinned as evidence.** The
  two arms are numerically identical because no constraint is ever offered: the non-holonomic
  benign-dynamics gate reads the instantaneous forward specific force per sample and requires 5 s
  contiguous below 0.35 m/s², while the scripted accelerometer noise is 0.5 m/s² per 100 Hz sample,
  so the dwell never accumulates; the zero-velocity update has no such gate but the drive never
  stops. `EvaluationHarnessTest.theConstraintsAbIsDegenerateOnThisDriveAndTheReasonIsMeasured`
  asserts 0 accepted and 0 refused in both arms, so if the gate is ever fixed the test fails and the
  comparison must be re-measured rather than quietly kept. The earlier constraints stage's 71%
  outage-error reduction was measured on held-out synthetic drives that include stops
  ([constraints report](../reports/constraints_ab_2026_09_30.md)); this drive simply does not
  exercise the constraints.
- **Map matching is optimistic by construction.** The synthetic road runs along the truth path, so
  the matched arm measures the matcher's projection onto a road that is right by construction — 3 cm
  final error versus 1.1 m — not the road network's agreement with reality. On this drive it cannot
  be worse than the raw arm, and the harness asserts that.
- **AI correction is `not_implemented`.** No trained model exists in the repository, so the arm
  carries a reason and no numbers. There is no inference to time.

## What is absent, and why

| Requested | In the shipped golden | Why |
|---|---|---|
| Model inference latency p50/p95 | `null` | No model exists to run. |
| End-to-end p50/p95 | `null` | A host replay has no sensor-to-display path to measure. |
| Memory peak | `null` | A host JVM run cannot speak for the phone's process. |
| Recovery convergence | 1.1 s for the fused arms | Measured against the declared 5 m bound; the contract requires the bound whenever the time exists. |
| Outage duration / distance | 20.0 s / 240.0 m | From the declared segments and the reference trajectory. |

The absence is stated in the report's `platform.note` and rendered on the screen. It is not a
promise: filling any of those cells requires a device run or a trained model, and only then does a
report stop being a host measurement.

## Limits

- **Reference authority.** This is `scripted_truth`: the closed-form trajectory the synthetic inputs
  were generated from. It is independent of every arm and it is **not field data**. No recorded
  session in this repository currently has an independent reference, so no field-accuracy report can
  exist yet, and the screen says so rather than implying otherwise.
- **Platform.** The run is a host JVM replay. Device timing, memory and sensor-path latency are
  unmeasured, and the report's platform block marks `host = true` so a device claim can never be
  read into it.
- **Coverage.** One drive, one seed, one bounded Hyderabad-road construction. Reproducible, not
  representative.

## Verification

- `EvaluationHarnessTest` — four tests: real numbers for every evaluated arm and absence for the
  rest, the degenerate constraints A/B, the map-match optimism bound, and golden reproducibility.
- `EvaluationSurfaceTest` (10 tests) — every requested metric is a row in fixed order, per-arm values
  are exact, every cell is a number or a named absence, all 30 negative-corpus documents are refused
  with the exact contract code, non-finite token parity with the Python codec.
- `EvaluationStoreTest` (4 tests) — bundled first, installed bounded and named by evaluation id,
  refusals kept rather than dropped.
- `EvaluationUiTest` (2 tests, instrumented, compile-only here) — the bundled report shows its
  provenance, limits and absent reason on the Evaluation page, and the recording console is absent
  there but present on Home.
- Python: `tests/test_evaluation_contract.py` (13 tests, including the non-finite rule) and
  `tests/test_evaluation_asset.py` (3 tests, byte equality of the two golden copies).
