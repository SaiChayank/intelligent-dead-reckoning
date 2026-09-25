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
