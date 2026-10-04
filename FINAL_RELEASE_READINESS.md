# Final Release Readiness — 2026-10-04 (local prototype package)

## Decision

**NOT READY for production deployment or a navigation-performance demonstration.** The Android application is a substantial offline sensing, recording, replay and map prototype, and the navigation runtime and classical fusion code exist. However, ordinary app use provides no valid calibration to fusion, so the engine remains uninitialized and publishes no usable navigation position. No independent moving-drive/reference outage result, approved ML sequence/model, or physical navigation qualification exists. A successful debug build is not a release or navigation acceptance.

This report supersedes prior status/count snapshots where they conflict. Dated reports remain historical evidence for the specific code/device/session described; they are not silently upgraded into current-checkout acceptance. The local host package is reproducible; the full requested end-to-end offline device-demo package cannot be signed off until a device with radios disabled is exercised. The detailed input audit is [reports/production_quality_audit_2026_10_03.md](reports/production_quality_audit_2026_10_03.md) (pre-existing audit input, preserved unchanged). The reproducible local prototype procedures are in [RELEASE_PACKAGE.md](RELEASE_PACKAGE.md); this status document explains why the package is not a production or field-navigation release.

## Architecture

The current product is one offline Android app (Kotlin, Jetpack Compose, Material 3) with a modular in-process design. The fuller as-built diagram is [docs/LOCAL_RELEASE_ARCHITECTURE.md](docs/LOCAL_RELEASE_ARCHITECTURE.md); the implemented/future matrix is [docs/PROTOTYPE_CAPABILITIES.md](docs/PROTOTYPE_CAPABILITIES.md) and the standalone limitations document is [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

```text
Android sensors + optional GNSS
              │ canonical typed records
       ┌──────┴───────────┐
       ▼                  ▼
 bounded local recorder   NavigationRuntime
       │                  │
 private JSONL + metadata ├─ calibration engine (host-tested)
 recovery/export/replay   └─ fusion EKF (host-tested; app has no valid calibration)
       │                              │
       └──── replay records ─────────┘
                                      ▼ canonical navigation output
                         NavigationPresentation → offline MapLibre

Other separate map sources: explicitly synthetic demo, recorded GNSS, live raw GNSS.
```

- Android acquisition owns foreground sensors/location and bounded queues. Local recordings are optional, app-private under `noBackupFilesDir`, recoverable and explicitly user-deletable; export is a separately confirmed local ZIP copy. There is no upload, Internet permission, cloud/backend, or background capture service.
- `NavigationRuntime` enforces session/source identity, bounded asynchronous input and lifecycle ownership. Calibration/fusion components are integrated at the engine boundary, but the app does not provide a valid calibration record or hand-off. Fusion refuses alignment rather than inventing a vehicle frame; the engine map accordingly has no position in ordinary use.
- The offline MapLibre renderer consumes separate synthetic, recorded-GNSS, live-GNSS or navigation-engine presentations. It does not localize. Road matching is an optional evaluation overlay, not an estimator constraint.
- Python under `training/` and `tools/` is an offline data/research/validation pipeline. Contract schemas have strict Python/Kotlin implementations and fixture parity. `experiments/` currently contains only its README.
- AI inference, model assets, routing, edge runtime, shared `core/` extraction and backend services are absent/deferred. No additional service/database is needed for this prototype.

## Implemented capability and evidence matrix

| Capability | Implementation status | Evidence and boundary |
|---|---|---|
| Android foreground IMU and optional GNSS acquisition | Implemented | Dated OnePlus CPH2585 / Android 16 acquisition runs verify sensor presence/rates and listener cleanup. The latest [2026-10-02 spot check](reports/android_acquisition_2026_10_02_foreground.md) was ~9m18s, stationary, with inconsistent UI-counter snapshots; it is not fresh 30-minute or navigation evidence. |
| Optional private recording and recovery | Implemented | Historical dated 10-minute recording and process-interruption reports demonstrate recording-stage behavior on one device; older output remains scoped to the tested build/session. No current device test was possible in this audit. |
| Session list, exact ZIP export, replay | Implemented | Host contract/reader/parity tests and historical dated device evidence. Export copies are independent of private-session deletion. |
| User-initiated permanent session delete | Added in this audit | Per-session confirmation, path/session validation, recursive deletion without following symlinks, and active recording/replay/export/library-operation guards. Host regression tests cover target isolation, malformed files, missing/traversal IDs and symlink behavior. Android UI test is compiled but cannot run without ADB/device. |
| Offline map and explicit data-source presentation | Implemented | Historical device evidence verifies renderer, attribution, synthetic presentation and some recorded/live raw-GNSS cases. These do not establish navigation accuracy. |
| Phone-to-vehicle calibration engine | Implemented in source | Analytic host tests exist; **no user-facing calibration collection/composition flow or valid app hand-off**. No production calibration is obtained. |
| 15-state GNSS/INS fusion, constraints, recovery and confidence | Implemented in source; host-tested | Synthetic/analytic tests verify code paths. With ordinary app input, calibration is `PENDING`, so engine refuses alignment and publishes no position. Covariance is `UNVALIDATED` when it can be emitted after alignment; this is not empirical accuracy. |
| Navigation-engine map view / localization mode | Wired in source; host-tested | Engine-map device tests compile; they are source/no-output tests, not a live calibrated navigation test. No current physical run. Contract 1.1.0 adds `localization_mode`; this does not itself complete calibration or acceptance. |
| Offline map matching | Implemented as opt-in overlay | Host-tested and separated from raw navigation; not an estimator constraint and not field-qualified. |
| Python measurement/session/experiment contracts and validators | Implemented | Python/Kotlin session-reader parity runs as part of Android JVM suite; validation commands run below. Empty experiment corpus means no experiment evidence, not a successful drive. |
| ML | Not implemented | Training admission is `no-go`, with 0 approved sequences; no trained model, deployment, inference latency or held-out AI result. |
| Offline routing, turn-by-turn, background navigation, shared/edge deployment | Deferred | Not necessary to make unsupported claims; do not add without accepted product scope. |

## Verification performed for this audit

The following are current local-checkout results unless marked unavailable:

- **Python full suite:** `python -B -X utf8 -m unittest discover -s tests -q` — 286 tests, 5 skipped (one new local-release manifest/fixture suite of 4 tests; symlink-dependent skips are platform-dependent), freshly passed.
- **Android JVM suite:** `cd mobile && bash gradlew testDebugUnitTest --no-daemon --offline --console=plain -Pkotlin.compiler.execution.strategy=in-process` — BUILD SUCCESSFUL; 31 XML result files, 373 tests, 0 failures/errors/skips (verified by summing Gradle XML reports after a forced rerun). Includes Kotlin/Python recording-reader parity, calibration/fusion, and evaluation harness tests.
- **Android lint/build/instrumentation compile:** `lintDebug`, `assembleDebug`, `assembleRelease`, and `assembleDebugAndroidTest` succeeded offline in the latest multi-task Gradle invocation; release output is `app-release-unsigned.apk`. `assembleDebugAndroidTest` compiles tests only. Lint has non-fatal existing SDK/API deprecation warnings; no release signing or minification is configured.
- **Python/Kotlin parity:** `PythonParityTest.bothReadersReachTheSameVerdictOnEveryFixture` is included in the full JVM suite. Standalone bidirectional contract interop passed for both versions: Python verified 17 Kotlin 1.0.0 records and 7 Kotlin 1.1.0 records as typed-value-identical.
- **Local replay fixture:** `python -B tools/validate_phone_recording.py --local mobile/app/src/androidTest/assets/demo-replay-v1` accepted 2 synthetic diagnostic records (`source=simulation`), with no sensor/GNSS channels. This test fixture demonstrates replay mechanics only, not navigation evidence.
- **Experiment validator:** `python -B tools/validate_experiment.py --corpus experiments` returned `accepted=true`, 0 experiments/findings. This is an empty corpus, not a passed field evaluation.
- **Offline map pack:** `python -B mobile/tools/verify_hyderabad_pack.py` verified 5 resource files, 247 tiles, 146,029 vector-feature occurrences, read-only.
- **Release package regression tests:** `python -B -X utf8 -m unittest tests.test_local_release_package -v` — 4 passed, including truthful empty model/field-evidence manifests and fixture validation.
- **Phase 0:** safe `python -B -X utf8 -m training.phase0.audit --data-root data/raw/iovnbd --verify-repeat --no-write` passed: 564 CSVs, 144 pairs audited twice, deterministic repeat output, raw integrity snapshot unchanged, no reports written.
- **Repository hygiene/whitespace:** `python -B tools/check_repo_hygiene.py` checked 327 tracked files and was clean; `git diff --check` passed. Hygiene's tracked-file scan does not imply that existing ignored/untracked private artifacts are releasable.

No verification command should install dependencies or tools, access a production service/device, rewrite reports, or modify raw dataset content. Any output/results must describe actual command outcomes; compile-only and empty-corpus outcomes are not represented as functional passes.

## Physical-device evidence

Historical evidence exists for acquisition/recording and map-only behavior on a OnePlus CPH2585 / Android 16. It is scoped to dated runs and older build checkpoints. The latest acquisition spot check is short and stationary. There is **no physical-device evidence of calibrated fusion, moving-drive navigation, genuine moving GNSS denial/recovery, engine-map accuracy, or current full instrumentation suite**.

In this audit environment `adb` is unavailable (`adb: command not found`, exit 127). Therefore connected instrumentation, preserve-recordings update observation, fallback screen capture, and physical airplane-mode UI checks cannot be run. Instrumentation sources compile but are not counted as executed. The local package gives the exact outstanding procedure in [RELEASE_PACKAGE.md](RELEASE_PACKAGE.md); historical dated results and limits remain in [mobile/MAP_DEVICE_VERIFICATION.md](mobile/MAP_DEVICE_VERIFICATION.md). The tracked replay fixture and offline/install/update procedures are present, but live airplane-mode demonstration execution is pending: the task asked to verify it with Internet unavailable, and host asset/hash checks plus offline Gradle builds are not equivalent to a physical no-Internet app run.

## ML evidence

No model has been trained or approved, no model file/runtime exists, and on-device inference/latency is not applicable. `reports/training_admission.json` approves **0** sequences; unresolved physical sensor-frame semantics and unverified IMU/reference synchronization remain scientific blockers. Do not train on unapproved data or claim AI improvement. See [ML dataset readiness](reports/ml_dataset_readiness_2026_09_30.md), the [admission manifest](reports/training_admission.json), and the explicit empty [model artifact manifest](models/model_manifest.json). The ground-truth manifest is also explicitly blocked/empty at [evaluation/ground_truth_manifest.json](evaluation/ground_truth_manifest.json); only the scripted host fixture is available.

## Performance evidence

A 10 Hz navigation publication interval in source is a nominal display cadence, not a measured output rate. Acquisition-only measurements of IMU cadence and bounded queues are not navigation throughput/latency. No current navigation p50/p95 latency, output rate, sustained queue/drop, memory, battery or thermal measurement exists. The 2026-10-02 short acquisition spot check showed light thermal status while USB powered but is not a battery/performance qualification. No <10% moving-outage drift result exists; the long real-session strapdown report instead documents severe divergence on a stationary handset and is explicitly a negative, non-calibrated baseline, not vehicle navigation. See [INS baseline](reports/ins_baseline_2026_09_30.md).

## Security and privacy evidence

- The source manifest removes network permissions and requests foreground fine/coarse location only; recordings are private/no-backup; export is explicit; no model update or upload path exists. Local bundled tile resources and the road graph have checked-in hashes; the release/environment inventory is [release/environment-manifest.json](release/environment-manifest.json).
- Strict codecs, bounded file reads, path validation, symlink refusal, and safe error messaging have host coverage. This audit adds explicit user deletion and regression coverage. Delete is permanent for only the selected private session; separately exported copies are not erased.
- Device-level permission/package/privacy interaction for this checkout was not reverified because ADB/device access is unavailable.
- `pip-audit` is absent locally, and no Android dependency advisory scanner/lock result is available here. Dependency vulnerability review remains an unresolved release gate; do not claim a clean advisory scan.
- Root application licensing choice is unresolved; do not choose a license as part of this audit. No release signing key/configuration exists, and none was generated. Debug builds do not qualify distribution.

## Known limitations and external blockers

A concise, standalone summary is maintained in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md); troubleshooting for local build/demo/update is in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

1. **Calibration integration:** add and validate an app-accessible calibration flow and safe hand-off before ordinary sessions can initialize fusion. This requires a scientifically reviewed implementation and device/field evidence; it is not inferred from code presence.
2. **Scientific/navigation validity:** collect independent, reference-bearing moving Android drives with genuine GNSS-denied and recovery intervals; report untouched-segment accuracy, drift, failure behavior and recovery. Current experiment corpus is empty.
3. **ML readiness:** unblock dataset frame/synchronization admission and an executable leakage-safe preprocessing/split gate before any training/model claims. Currently 0 approved sequences.
4. **Physical acceptance/performance:** run full instrumentation and calibrated live engine-map acceptance on an authorized target device; measure navigation output rate/latency, queue/drop, memory, battery and thermal response. Do not test the UI while driving.
5. **Release/security:** run Python advisory audit and reviewed Android dependency/SBOM/advisory scan; determine project licensing and produce a reviewed signed/minified release configuration only with appropriate authorization.
6. **Accessibility/device matrix:** TalkBack, large font, landscape/tablet and representative OEM/API acceptance are not evidenced in this pass.

## Intentionally deferred

- AI training/inference until data is scientifically admissible and has a leakage-safe independent evaluation.
- Routing, turn guidance, cloud/backend, telemetry and online map provisioning; these do not close the present calibration/field-evidence blockers.
- Edge runtime and shared navigation-core extraction until an accepted mobile solution and explicit deployment requirement exist.
- Broad architecture rewrites, databases, services or unrelated product features.

**Local prototype package status: reproducible on a configured development host for host tests/builds and synthetic demo assets. Physical offline-demo execution is pending device access. Production release decision remains NOT READY**, irrespective of green host tests/builds. The external calibration, field-reference, ML-data, physical-device, dependency-security, licensing and signing gates above remain unsatisfied.
