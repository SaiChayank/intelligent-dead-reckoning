# Demonstration prototype — calibration-to-fusion integration

This continuation connects the existing causal calibration estimator to classical
fusion through `CalibratingFusionEngine`, on NavigationRuntime's existing worker.
It changes no acquisition timestamps/units, recording format, INS/EKF mathematics,
model admission policy or map-matching behavior. No AI model is present.

## Session behavior

Start Phone sensors explicitly. The engine starts in DEPLOYABLE mode. Calibration
uses stationary accelerometer/gyro evidence and two accepted straight forward
acceleration/braking segments under the existing estimator gates. No manual
identity quaternion, reference-only data or persisted calibration is supplied.
The engine map card displays the latest calibration status and collection guidance.

After a VALID proper mounting rotation and gyro bias exist, fusion waits for GPS
speed >=3 m/s, non-null bearing, at least four used satellites, and horizontal accuracy
in (0, 15] m. Course must agree
within 15 degrees with an already measured displacement >=15 m, using fixes no
more than 10 seconds apart. These are engineering thresholds, not field-calibrated
accuracy guarantees. Reverse travel is unsupported during calibration/alignment.
The first eligible fix strictly after the calibration timestamp initializes
fusion heading and supplies the GNSS anchor. Earlier samples are never replayed.
The existing engine then owns propagation, GNSS gates and recovery.

Output event IDs are resequenced into one session stream. Same-episode mounting
refinements do not change an active filter's frame; the published calibration stays
the adopted one. EXPIRED/INVALID/PENDING calibration resets fusion and clears its
mount. A fresh valid calibration and fresh motion evidence are required again.
Stop never activates a calibration produced only during finalization. Starting a
new session or source calibrates again. Foreground lifecycle remains unchanged.

## Demonstration on the phone

1. For an immediate indoor presentation, open Map -> Synthetic demo -> Start demo.
   Use the scenario, pause/rate and blackout/recovery controls. This is the labelled
   scripted visual demo, not the live engine or measured navigation performance.
2. For live calibration, choose Phone sensors, grant precise location, secure the
   handset in a fixed vehicle mount and start sensors while parked. Keep it still
   for several seconds. Open Map -> Navigation engine to inspect calibration status.
3. A passenger operates the UI. In a suitable controlled setting, collect two
   separate straight forward acceleration/braking segments with good GPS, separated
   by a stop. Each must satisfy CALIBRATION.md's evidence gates; duration alone is
   insufficient. Diagnostic rejection codes explain why evidence was not accepted.
4. After VALID appears, continue straight forward with usable course and sufficient
   displacement. The engine map should acquire a position. A stationary start waits
   for heading evidence; it does not invent north-facing attitude.
5. Stop sensors before handling/remounting. Backgrounding stops the session; returning
   does not resume it. Real GNSS outage/recovery and accuracy need independent
   reference-bearing experiments under the collection protocol.

The local recorder still records the canonical acquisition stream, not derived
calibration/navigation output. Replay recalculates calibration from the recorded
measurements. Export behavior and stored source labels are unchanged.

## Acceptance limits

The integration tests exercise measured synthetic evidence through both actual
engines, plus missing heading/bias, invalidation, source rejection and lifecycle
handoff gates. This does not prove real phone mounting convergence, moving-drive
accuracy, <10% outage drift, battery performance, or AI improvement. A field run
requires a mounted phone, forward-motion evidence and independent reference; an
indoor USB-connected phone cannot supply those conditions.

The scientific training admission remains NO-GO. Routing and turn-by-turn remain
future work. Runtime integration removes the missing handoff, not those evidence
gates. Older readiness statements about absent calibration handoff are historical
for their checkout and are superseded by this implementation once tests pass.

## Device blockers corrected

Android's OS-owned app-data path can be an alias (`/data/user/0` versus
`/data/data`). Map/graph installers canonicalize that OS root, then still reject
symlinks/traversal in every app-owned child through PrivateAssetPaths. This fixes
the observed UNSAFE_PRIVATE_ROOT failure without weakening child-path validation.

AAPT silently expands `.gz` assets and removes that suffix. The build now stages
the unchanged bundled road-graph bytes as `road-graph.bin`; the installer restores
the canonical `road-graph.json.gz` private filename. The source asset and manifest
are unchanged. APK verification found 7,675,094 bytes and SHA-256
`b05842482abf96805342925dfd91a48660d26ca365d1f92d898b4a5201ed066f`.
The device regression test actually installs, verifies and loads the graph.

The calibration regression exposed a separate recent-motion defect: a newer
zero-speed fix could erase evidence that the just-closed quiet IMU window was
driven. Retaining the last moving-fix time preserves the existing five-second
exclusion gate. Estimator mathematics and acceptance thresholds are unchanged.

## Verification for this continuation

- JVM suite: 382 tests, zero failures, including eight new handoff tests and one
  actual calibration-to-fusion analytic vehicle test. The latter establishes
  causal wiring, not field accuracy.
- Connected OnePlus 12R (CPH2585, Android 16): EngineMapDeviceTest,
  OfflineMapDeviceTest and EvaluationUiTest passed: **OK (13 tests)**, 59.716 s.
  Covered graph installation/loading, offline map, source separation, synthetic
  blackout/recovery controls, UI recreation and foreground lifecycle behavior.
- Python local release-package checks: four tests passed.
- Final combined Gradle command: BUILD SUCCESSFUL (29 s); lint zero errors,
  five warnings. Deprecation/dependency warnings remain and are not acceptance
  claims about field accuracy.
- Installed the debug app using `adb install -r`; no uninstall or data clearing.
  Both existing private recording files retained identical before/after SHA-256.
- No raw dataset, historical report, canonical contract or acquisition/recording
  implementation was modified. Pre-existing version changes and untracked
  `edge/bin/` work were preserved. No commit, merge or push was performed.
- Initial device attempts failed on the OS-root alias and gzip asset packaging;
  both were corrected before the passing run. Generated-asset build dependencies
  are declared for asset merge and lint consumers to avoid ordering failures.

Reproduce from `mobile/`, with the documented JDK/SDK environment configured:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.intelligentdeadreckoning.app.EngineMapDeviceTest,com.intelligentdeadreckoning.app.OfflineMapDeviceTest,com.intelligentdeadreckoning.app.EvaluationUiTest com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner
```

Python check from the repository root:
`python -B -X utf8 -m unittest tests.test_local_release_package -q`.
The inspected phone screenshot is copied locally to the ignored build directory
`app/build/demo-evidence/synthetic-dr.png`; it is explicitly a synthetic UI fixture.

**READY for the labelled indoor demonstration and a controlled field-validation
trial. NOT READY for an AI-enhanced or accuracy-qualified navigation claim.**
The next owner is the field-test operator: collect fixed-mount calibration and
reference-bearing forward drives, measure actual outage drift/recovery, and
complete training admission before anyone enables AI development.
