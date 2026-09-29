# Offline map device acceptance — 2026-09-25

Scope: optional offline Hyderabad renderer and **synthetic** navigation-state
presentation. This is not acceptance of a real navigation engine, GNSS recovery
algorithm, route planner or map matching.

Device: OnePlus CPH2585 (12R), Android 16, USB-authorized. Updated the app and test
APK with `adb install -r`; no uninstall, app-data clearing, permission grants,
recording starts, network upload or raw-dataset operations.

## Findings and changes

- Actual framebuffer screenshots showed streets, place labels and water, not
  just a successful style load. Synthetic heading, trail and uncertainty rendered.
- Attribution below a scrollable map could be off-screen. Fixed by adding an
  attribution Surface inside the map bounds; the device test asserts visibility.
- The original fictional origin was on Hussain Sagar lake. Moved only the
  synthetic fixture to 17.435 N, 78.445 E, a built-up area. It remains an arbitrary
  curve, not a vehicle recording or road-matched route. Tests use the new origin.
- Added camera assertions for horizontal pan, zoom and recenter, explicit
  lifecycle/recreation checks and screenshots at three synthetic scenario stages.

Files changed: NavigationPresentation.kt, OfflineMapScreen.kt,
NavigationPresentationTest.kt, OfflineMapDeviceTest.kt, OFFLINE_MAP.md and this
report. Frozen acquisition/recorder/contracts and navigation algorithms unchanged.

## Verified results

- Final direct device run: **OK (4 tests), 36.554 seconds**. Earlier three-test
  smoke run and first four-test visual run also passed.
- Pack install/checksum validation and idempotent reuse passed; SQLite contains
  247 tiles; INTERNET permission is denied to the installed application.
- Style loads across tab switches and background/resume. Synthetic Start/Stop,
  no automatic restart and recreation passed. Camera longitude changed after
  horizontal swipe, zoom increased and recenter returned to Hyderabad center.
- Screenshot inspection confirmed roads, labels and buildings, heading/marker,
  travelled trail, scenario labels and in-map attribution. The DR-stage screenshot
  shows the larger synthetic uncertainty area. No genuine GNSS-loss claim.
- Host suite: **116 tests, 0 failures/errors/skips**; both APK builds succeeded;
  lint: **0 errors, 8 warnings**. The new instrumentation test compiled successfully.
- **All 88 pre-existing recording files remain present with identical SHA-256
  hashes** after installation and device tests. No app-data reset or recording
  recovery operation was performed by this task.

**READY: offline rendering + synthetic map presentation accepted on this phone.**
This completes the bounded map demo stage, not the full live navigation feature.
Human pinch/rotation review and production stress/fault testing remain below.

## Commands

Host, from `mobile/`, with the documented JDK/SDK/Gradle-user-home setup:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Gradle `connectedDebugAndroidTest` failed before running tests because its host UTP
temporary directory under `C:\Users\Saich\.android\utp` was inaccessible. A retry
with elevated tool permissions had the same result. This host-runner issue was
not repaired by changing global settings; direct Android instrumentation bypassed it.

Equivalent direct execution from repository root (`adb` from SDK platform-tools):

```powershell
adb -s 5c7d81bb install -r mobile/app/build/outputs/apk/debug/app-debug.apk
adb -s 5c7d81bb install -r mobile/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 5c7d81bb shell am instrument -w -r -e class com.intelligentdeadreckoning.app.OfflineMapDeviceTest com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumentation test writes actual screen captures only to the target app's
private cache `cache/map-verification/`. Selected screenshots are copied locally
to ignored `mobile/artifacts/map-validation-20260925/`; they are not committed or
uploaded. They depict synthetic positions, not captured location measurements.

## Limits and remaining work

- The app has no INTERNET permission, verified on device; all rendering resources
  are local. Wi-Fi/mobile radios were not toggled and airplane mode was not tested.
- Panning and camera buttons are tested; two-finger pinch/rotation still need human
  ergonomic review. No battery, thermal, sustained frame-rate or long-trip claim.
- Do not inject disk-full/corrupt-pack faults into this user's existing install.
  Those destructive fault scenarios remain for a disposable test install.
- No real positioning, live DR, offline routing/re-routing, planned route, road
  matching or turn-by-turn guidance was added or verified. The frozen v1 contract
  still lacks a current GNSS/DR/fused mode field. It needs explicit review before
  any such field is introduced. NavigationEngine remains an interface only.
- Do not treat this acceptance as permission to start AI or EKF implementation.

## Attribution clipping fix — 2026-09-29

Same device (CPH2585, Android 16, API 36). This run uninstalled the previous
install, backed its 44 app-private recordings off the phone first, and installed
the current debug build with `adb install -r -g`.

Finding: `OfflineMapDeviceTest.syntheticScenarioVisualEvidenceAndRecreation`
failed on `map_attribution` being *not displayed*. The node existed, but its
clipped bounds were empty (`Rect(0,0,0,0)`) and the attribution string was absent
from the accessibility tree entirely.

Root cause: the hero was sized as 70% of the **window** height, while a page only
gets the window minus the header, the dock and its own vertical padding. On this
display the hero measured 1,946 px against a 1,884 px scroll viewport, so the
bottom of the floating chrome — the demo console and the OpenStreetMap
attribution — fell past the viewport edge and was clipped away. Attribution was
positioned at y=2,521 px with the viewport ending at y=2,512 px.

Fix: the page measures its own area (`BoxWithConstraints` around the page scroll
container in `IdrApp.kt`) and the hero takes the smaller of the window proportion
and that measured area. The floating chrome now sizes the hero when the console
needs more room than the minimum, instead of being clipped by its own background
box. The attribution is therefore always inside the visible page.

Evidence: the full instrumented suite passed on the device, **24 tests, 0
failures** (previously 23 of 24), together with 136 host unit tests and
`lintDebug` with 0 source warnings. The attribution assertion is the regression
test for this fix: it checks licence-visible attribution, not cosmetics.

Device framebuffer captures from this run are in ignored
`mobile/artifacts/device-shots/`; they show the synthetic dashboard only.

## Recorded-session mode — 2026-09-29

The map previously had one data source: a scripted 30 s synthetic fixture with
a fixed origin. It now has two, selected by `map_source_synthetic` /
`map_source_recorded`, with the synthetic demo still the default so every
pre-existing tag, string and test is untouched.

Declared semantics of the added mode: `RecordedSessionMap` folds real recorded
GNSS fixes into display state and draws them as recorded. It applies no
propagation, dead reckoning, sensor fusion, road matching, routing or inference
of a GNSS/DR/fused mode. A missing fix is left missing — the trail is split at
every gap rather than bridged, because a straight line between two fixes twenty
seconds apart is movement nobody observed. A fix outside the bundled tiles is
counted and never drawn. The platform's reported fix radius is carried as
`fixRadiusMetres` and is never relabelled as a calibrated 95% confidence, which
remains `accuracy95Metres` and stays empty for recorded data.

`MapOverlay` still emits the synthetic comparison, scenario and outage layers
only for `Source.SIMULATION`, so replayed recordings (which arrive as
`REPLAY_REAL`) physically cannot paint a comparison or DR line. A host test
asserts that.

Evidence:

- Host: 8 new tests in `RecordedSessionMapTest` (gap splitting, course over
ground fallback, coverage rejection, malformed coordinates, bounded history,
and the no-synthetic-layers assertion); 144 host tests total, 0 failures.
- Device: the full instrumented suite still passes, 24 tests, 0 failures.
- Device, real data: one real recording (`02edb616`, 2.4 MB) was pushed into the
  app's private recordings directory byte-identically and selected in recorded
  mode. The screen reported `1 fixes · 0 drawn · 0 gaps · 1 outside coverage`,
  switched `map_source` to `MAP SOURCE: RECORDED SESSION — real GNSS fixes, no
  fusion or DR`, hid the synthetic console and kept the attribution. That result
  is correct: the corpus's fixes are ~5.6 km west of the bundled pack, as
  recorded in `reports/recording_corpus_2026_09_29.md`.
- Not verified: drawing a real multi-fix trail on the device, because no session
  in the current corpus has two fixes inside the covered area. The segment-drawing
  path is covered by host tests only.
