# Reference-inspired map presentation package

Scope approved by the user: presentation-only demonstrations, not new localization
algorithms. Original project visuals/code; no video artwork or third-party source
code copied. Existing acquisition, recording and v1 contracts remain unchanged.

## Reference evidence

All four auto-generated transcripts were read; relevant demonstration frames were
sampled. This is not independent verification of the creators' benchmark claims.

- [DRISHTI, 1:21–2:32](https://www.youtube.com/watch?v=w6l_-iElGDs&t=81s):
  baseline/comparison trace, denied-GNSS segment, uncertainty display and recovery.
  Adopted as explicitly scripted comparison and automatic blackout segment, not
  measured drift, filter covariance or an actual INS baseline. Its <10% target
  is not an achieved result for this project.
- [IntelliNav, 1:28–2:04](https://www.youtube.com/watch?v=ik0CZppdgvU&t=88s):
  manual blackout control, road-overlay toggle and speed/distance/outage HUD.
  Adopted only inside the demo; no actual GNSS fixes are suppressed. No invented
  noise index, pitch/roll telemetry, 3.8ms inference or <1% drift claim.
- [Asmodeus, 0:47–1:58](https://www.youtube.com/watch?v=z8vTUNuLFzQ&t=47s):
  comparison vehicles, selectable paths, playback speed and multiple views.
  Adopted three local synthetic paths and speed controls, comparison marker/trail
  and overlay toggles. No additional regional map downloads, proprietary basemap,
  satellite imagery or simulated road-following disguised as routing.
- [NAVZERO, 0:51–1:15](https://www.youtube.com/watch?v=t_2sAWrL0Mg&t=51s):
  narrated outage/recovery and on-device operation. No unique interactive map
  control was established in the sampled frames. The existing recovery scenario
  is retained; its new red comparison convergence is scripted, not an EKF.

## Use

Open Map. Start synthetic demo retains the existing 30-second sequence. Pause
freezes virtual time; Resume excludes time spent paused. Stop hides all position
overlays, while Reset also clears elapsed time and counters. Backgrounding stops
the demo; returning or activity recreation never resumes automatically.

Open **Demo options**:

- Select Left curve, Straight or Right curve while stopped/reset. These are
  mathematical curves around Ameerpet, not planned road routes.
- Choose 0.5×, 1× or 2× playback before or during playback. This changes display
  time, not the fixture's 10 m/s physical speed label.
- Auto uses a denied interval of 10–20 virtual seconds. Blackout forces a denied
  scenario; Recover forces an available scenario. These controls never reach
  acquisition, recorded data, replay or a navigation engine.
- Toggle comparison, travelled trail, uncertainty, scenario path and road overlay.
  Turning roads off hides road geometry/road names, not the map's other layers.
  Attribution remains visible.

Purple is the scripted reference marker/path. Red is a scripted drift illustration
that separates by 3 m per denied virtual second and converges by 6 m per available
virtual second. It is **not the repository's classical INS, AI or ground truth**.
The red heading is not estimated; it is a comparison point only. Dashed gray shows
the chosen scenario path. Amber marks only the automatic 10–20s segment, even
when a manual signal override is selected; it does not represent an actual tunnel.

The HUD shows virtual elapsed time, fixture speed/heading, total synthetic distance,
cumulative outage time/distance and current outage duration. Distances use the
fixture's known 10 m/s speed, not real sensor integration. The shaded radius remains
synthetic. No benchmark accuracy/drift percentage or ETA is presented.

## Implementation and constraints

- MapDemoController is UI-owned, pure Kotlin, with no Android, disk or sensor API.
  It feeds the existing typed navigation adapter using a separate virtual timeline;
  real Android elapsed-realtime timestamps are never multiplied or rewritten.
- Delayed ticks split at automatic outage boundaries before computing counters;
  playback rate changes first account for elapsed time at the previous rate.
- Both trails are bounded at 512 points. Scenario geometry is fixed at 61 points,
  outage geometry at 21. No full-session data accumulation or background service.
- Completion occurs at 30 virtual seconds and freezes final demo state. Reset/Stop
  removes it. A new Start clears traces and resets signal overrides to Auto.
- Replay, live acquisition and their clocks remain independent. New comparison
  GeoJSON features are emitted only for Source.SIMULATION, never real/replay sources.
- NavigationEngine and contracts remain untouched. Real fusion, map matching,
  guidance, routing, magnetic attitude and AI inference remain out of scope.

Files added: MapDemoController.kt, MapDemoControls.kt, MapDemoControllerTest.kt and
this guide. MapPresentation/SyntheticMapDemo, MapRenderer, MapLibreRenderer,
MapOverlay, OfflineMapScreen and OfflineMapDeviceTest are extended. Prior uncommitted
device-verification changes are preserved. No dependency changes or downloads.

## Verification commands

From `mobile/`, with documented JAVA_HOME, ANDROID_HOME and GRADLE_USER_HOME:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Physical test class is `com.intelligentdeadreckoning.app.OfflineMapDeviceTest`.
Use the direct `adb install -r` and `am instrument` commands documented in
MAP_DEVICE_VERIFICATION.md if the host Gradle UTP directory remains inaccessible.
Never clear application data to work around test-runner problems.

## Verification results and continuation handoff

Confirmed on 2026-09-25:

- Host build succeeded with **132 JVM tests, zero failures/errors/skips**,
  including 16 MapDemoController tests. Both APKs built; lint reported 0 errors
  and 8 existing warnings. The final regression test covers a signal-control
  click arriving after the virtual run has completed.
- The expanded physical-device suite completed **OK (5 tests), 40.102 seconds**
  on the connected OnePlus CPH2585 before that final completion-label fix.
  Scenario/rate selection, pause/resume/reset, signal overrides, overlay controls,
  camera movement, lifecycle behavior and screenshot collection passed.
- Real device screenshots under ignored
  `mobile/artifacts/map-demo-controls-20260925/` show comparison traces, scenario
  geometry, synthetic statistics and attribution. No benchmark claims follow
  from these screenshots.
- All 88 existing recording-file SHA-256 hashes matched the pre-test inventory
  after that completed run. Map assets passed the read-only pack verifier.
- The final completion-label fix passed host tests. Its subsequent device rerun
  was interrupted before the final suite summary was retrieved; do not count it
  as a second completed device pass.

On continuation, commit `da84645` already contains the implementation and final
fix. Git was clean and synchronized with the locally recorded origin/main.
The old test process was no longer available and `adb devices -l` listed no
connected device. Therefore no new recording-integrity or physical-device claim
was made during continuation.

### Final-build acceptance completed — 2026-09-25

- Installed the existing final app and test APKs with `adb install -r`, preserving
  app data. Direct `OfflineMapDeviceTest` instrumentation passed **OK (5 tests),
  41.45 seconds** on OnePlus CPH2585. This closes the pending final-build recheck.
- The first attempt had 2 failures out of 5: a Compose startup timeout and a
  missing Compose hierarchy. A complete rerun passed without source changes.
  The precise transient startup cause remains unproven; leave the phone unlocked
  and untouched during instrumentation. Do not describe the first run as passing.
- Fresh pre-install and post-test inventories contained **88 recording files**
  with identical paths and SHA-256 hashes. No recording was started or deleted.
- Repeated the documented offline Gradle build/check command: **BUILD SUCCESSFUL
  in 17s**, 80 tasks (79 up-to-date). The retained JVM results contain **132 tests,
  0 failures/errors/skips**; this invocation reused those results, not 132 fresh
  executions. Lint remains **0 errors, 8 warnings**. SDK XML-version and restricted
  analytics-settings warnings were non-fatal.
- `python mobile/tools/verify_hyderabad_pack.py` passed read-only verification:
  5 files, 247 tiles, 146,029 vector features. No pack regeneration was performed.
- Only this verification guide was edited during the final continuation. No raw
  data, acquisition, recording, contracts or algorithm source was modified.

**READY: the bounded offline map + synthetic demo feature is complete and verified
on this phone.** Human pinch/rotation ergonomics, prolonged performance/battery
testing and disposable-install storage fault tests remain outside this acceptance.
Real GNSS/DR fusion, routing, map matching and turn-by-turn navigation are not
implemented by this feature. Do not present the synthetic demo as real navigation.

### Regression hardening — 2026-09-26

Added three deterministic JVM tests, without changing runtime behavior:

- Thirty complete start/stop runs across all scenarios verify that geometry and
  counters do not carry over between runs.
- A `Long.MAX_VALUE` clock gap at each supported playback rate verifies bounded
  arithmetic, completion and comparison convergence.
- Nine cadence/rate combinations verify identical final distance/outage counters
  and the 512-point trail bounds.

Fresh execution from `mobile/`:

```powershell
.\gradlew.bat testDebugUnitTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Result: **BUILD SUCCESSFUL in 30s; 135 tests, 0 failures/errors/skips**, including
19 MapDemoController tests. These results are fresh, not reused test results.
Existing SDK XML/analytics warnings and a nullable-receiver compiler warning in
StrictContractTest.kt remain; no test failed. No phone operations were performed
in this continuation, and no production source, raw data or recordings changed.
These deterministic tests are not evidence of real-device memory or battery usage.

## Remaining manual acceptance checklist

Owner: device tester. Use the synthetic Map screen, not real acquisition.

1. Pinch and rotate the map; check readable attribution, smooth gestures and that
   North/Hyderabad controls restore the expected camera. Record phone/OS and result.
2. If convenient, enable airplane mode manually, reopen Map and verify local tiles
   still render. Restore connectivity afterward. This checks offline rendering,
   **not** real GNSS-denied navigation or GNSS recovery.
3. Exercise repeated demos for 15 minutes; note rendering stalls, crashes and
   thermal/battery observations. Quantitative performance claims require profiling
   and a controlled baseline, not a short subjective check.

Storage-full/corrupt-pack tests belong on a disposable install owned by a developer;
never alter this phone's existing recordings to simulate a failure. Real navigation
integration requires a separate reviewed contract/engine task and is not the next
automatic action. No AI/EKF readiness approval is implied by this map acceptance.
