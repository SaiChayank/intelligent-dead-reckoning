# IDR Android foundation

See [the demonstration procedure](DEMONSTRATION.md) for the new session-local
calibration-to-fusion handoff and its device/field acceptance boundaries. Older
no-handoff statements below describe the previous checkpoint.

The native Android application is the main product of Intelligent Dead Reckoning
(SIH #26168). Simulation, foreground acquisition, private recording/recovery,
export, replay, map rendering and calibration/fusion engines are implemented.
However, the normal app journey does not supply a valid calibration to fusion, so
an aligned navigation solution is not yet available from ordinary app use. Routing
and AI are not implemented. This is a standalone Gradle project inside the
existing repository.

## What works

- Optional central Hyderabad offline map preview: bundled tiles, camera controls, attribution and an explicitly started synthetic marker/heading/trail demo. Four separate map sources are implemented: synthetic fixture, recorded session, live raw phone GNSS and navigation-engine output. However, the ordinary app journey supplies no valid calibration, so the fusion engine remains uninitialized and the engine source correctly displays no position. Engine covariance/presentation is host-tested only and is not validated navigation. The optional raw-vs-map-matched overlay is evaluation-only. No routing exists. See [map scope and device gate](OFFLINE_MAP.md), [the engine view](MAP_ENGINE_VIEW.md) and [confidence semantics](CONFIDENCE.md).
- Recording details, paged local sessions and explicit local ZIP export through Android's document picker are implemented. See [export workflow and device-validation gate](EXPORT.md).
- Read-only local replay supports start/pause/resume/stop with explicit `replay_real` / `replay_simulation` labels. See [replay policy and verification](REPLAY.md).

- **Evaluation tab** — renders the checked-in `contracts/evaluation/v1` arm-comparison report (classical INS, classical fusion, + constraints, + AI correction, + map matching) with every absent value stated as `not measured` / `not implemented`, never filled; the JVM harness regenerates the report from the host evaluation pipeline and requires it to match the shipped bytes. It is a console-free engineering page, not device or field accuracy evidence. See [EVALUATION.md](EVALUATION.md).
- Kotlin + Jetpack Compose / Material 3 UI: Dashboard, Map, Diagnostics, Evaluation and About.
- A visible `SIMULATION` or `REAL PHONE` banner on every screen and an explicit
  source selector. Simulation values remain scripted demo data.
- Phone sensors mode collects accelerometer, gyroscope, magnetometer and optional
  gravity plus permitted GNSS/network fixes and satellite status. See
  [acquisition details](ACQUISITION.md) for time, queue and permission behavior.
- Start begins a fresh deterministic demo; Stop retains the final snapshot.
- Switching tabs retains the session. Android Back returns to Dashboard first.
- Leaving the foreground stops the demo. Returning never automatically resumes.
- Recreating the activity (including rotation) stops the current session safely.
  The latest snapshot survives activity recreation through a ViewModel, but not
  process death. A new process starts Ready, with no measurements.
- Scrollable screens, system-inset handling, labelled controls and disabled
  buttons for invalid transitions. This first visual design uses a fixed dark theme.

Optional foreground local recording subscribes to the Phone sensors stream, with
explicit Start/Stop, bounded writing and interrupted-session recovery. See
[recording architecture and device acceptance](RECORDING.md). Its new physical
device gate is separate from the frozen acquisition verification.

Not implemented: ZIP import, replay seek/speed controls, rotation-vector events,
routing, AI correction, and a user-facing calibration collection/composition flow.
The 15-state EKF, phone-to-vehicle calibration, constraints and navigation runtime
exist in source and have host coverage, but they are not integrated into a usable
calibrated app journey and have no independent moving-drive acceptance. See
[CALIBRATION.md](CALIBRATION.md), [FUSION.md](FUSION.md), and
[FINAL_RELEASE_READINESS.md](../FINAL_RELEASE_READINESS.md). Device acceptance
remains separately required.

## Interface and design system

The Compose surface runs on a single dark design system — the written contract is
[UI_DESIGN.md](UI_DESIGN.md) (tokens, state vocabulary, honesty rules, map-overlay
colours, dialog and accessibility rules). The implementation lives in
`app/src/main/java/com/intelligentdeadreckoning/app/ui/design/`:

- `Tokens.kt` — colour, spacing, radius, size, motion and typography scales. One accent
  ("signal lime", `#C6F24E`) carries selection, primary actions and live metrics; semantic
  success/warning/danger/info tones stay separate so "simulated" can never read as "selected".
  Surfaces run `#070707`–`#181818`, primary text `#F5F5F5`.
- `Components.kt` — reusable primitives: `IdrCard` (primary/secondary/utility/glass), `IdrButton`,
  `IdrChip` (carries `Selected` semantics, 44 dp touch target), `StatusPill`, `StatusDot`,
  `IdrOverlayChip` (map chrome), `StatTile`, `IdrMeter`, `KeyValueRow`, `StatePanel`
  (loading/empty/degraded/error/success), `PlaceholderBlock`, the `IdrIcon` line-glyph family and
  the floating `IdrDock`. No icon or animation dependency was added.
- `ui/Theme.kt` — `IdrTheme`, mapping that system onto Material 3 and exposing
  `LocalIdrReducedMotion`, which is true when the platform animation scale is zero. Every animated
  primitive collapses its duration to 0 in that case.

Motion is deliberately bounded: presses scale 1 → 0.97, the dock's active indicator slides between
item positions, chips animate their selection colour, and screens fade in. Nothing animates from a
continuously changing sensor or clock value, and there is no unbounded repeating animation, so the
Compose test clock always reaches idle. No font binaries are bundled: the platform family is used
with explicit weights and tightened letter spacing, and numeric readouts use the monospaced family.

The Map tab is map-first. The offline renderer fills a full-width hero with floating glass chrome
(status chips, camera cluster and demo console), and the synthetic-demo options plus the coverage
and limits disclosure sit in the scroll flow beneath it. The dock reserves its own inset space
instead of covering page content, so scrolled controls stay reachable and clickable.

This is a historical presentation checkpoint. Its **195 JVM test** count and source-change
summary describe only that dated change, not the current checkout. Test counts and current
verification are recorded in [FINAL_RELEASE_READINESS.md](../FINAL_RELEASE_READINESS.md).

## Open and run in Android Studio

1. Choose **Open**, then select `Intelligent Dead Reckoning/mobile`.
2. Let Gradle sync. Choose the Android Studio bundled JDK for Gradle.
3. In SDK Manager, install Android SDK Platform **37.0**, Build Tools **36.0.0**
   and Android SDK Platform-Tools. The compile platform need not match the phone OS.
4. Connect an Android 8.0+ (API 26+) phone with USB debugging enabled and authorize
   this computer, or choose an emulator. Select it in the device selector.
5. Run the **app** configuration. Open **IDR Demo** and tap **Start simulation**,
   or select **Phone sensors**, tap **Start sensors**, and **Allow location** if
   desired. Grant precise location for GNSS/satellite status. Returning from
   Settings/background requires Start again.

Internet is needed on the development computer for the first dependency download.
The installed app does not require or request internet access.

Pinned build stack: AGP 9.1.1, Gradle 9.3.1 (checked SHA-256 in the wrapper),
built-in Kotlin 2.2.10 with matching Compose compiler, Compose BOM 2026.02.01,
Activity 1.12.4, Lifecycle 2.10.0 and Coroutines 1.10.2.
Java/Kotlin bytecode targets 17. Gradle requires a compatible JDK 17+;
Android Studio's bundled JBR is recommended. Compile SDK is 37, target SDK is 36,
minimum SDK is 26. Target 36 is intentional for this foundation, not a claim about
future store-submission requirements.

The toolchain follows the [AGP 9.1.1 compatibility matrix](https://developer.android.com/build/releases/agp-9-1-0-release-notes).
AGP supplies Kotlin; do not add a second `org.jetbrains.kotlin.android` plugin.
See [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).

## PowerShell build

From the repository root (adjust the two machine-specific paths as necessary):

```powershell
Set-Location mobile
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
# Optional repository-local cache, ignored by Git:
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain
```

If Android Studio is installed in `Android Studio1`, use that installation's
`jbr` directory. `local.properties` can instead contain the SDK location generated
by Android Studio; never commit that machine-specific file.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

With one authorized device connected:

```powershell
.\gradlew.bat connectedDebugAndroidTest --console=plain
.\gradlew.bat installDebug --console=plain
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n com.intelligentdeadreckoning.app/.MainActivity
```

With multiple connected devices, set `ANDROID_SERIAL` to the intended device
before testing or installing. Only install a debug build on a device you control.
The release build is not signed/configured for distribution.

On macOS/Linux use `bash ./gradlew` and set `JAVA_HOME` / `ANDROID_HOME` to the
local installations. Gradle wrapper scripts and JAR belong in source control;
build outputs, caches and `local.properties` do not.

## Architecture and time model

`MainActivity` owns a `SessionViewModel`. The ViewModel owns a
`SimulationController` and `AndroidAcquisition`, selected by a Kotlin-only
`SourceCoordinator`. Compose observes immutable StateFlow snapshots using
`collectAsStateWithLifecycle`. Activity start/stop owns acquisition lifetime;
source switches stop the old source and require explicit Start for the new one.

`SimulationController` is Kotlin-only and accepts a coroutine scope and injectable
monotonic clock. The Android adapter supplies `SystemClock.elapsedRealtime` in
milliseconds. A single cancellable coroutine requests an update every 100 ms
(10 Hz **UI demo target**, not a measured hardware sampling rate). Actual elapsed
time comes from the clock, not sample count. Delayed ticks skip missed updates;
they do not invent a backlog. Only the latest snapshot is retained. StateFlow
conflates slow collectors; there is no unbounded queue or sample history.

Calls are serialized on the owning UI dispatcher. The tiny signal function does
no I/O or heavy work. Activity `onStop` cancels updates with a background-stop
reason; ViewModel cancellation also cancels its scope. There is no foreground
service, background capture, database or file writer.

This timing model is for simulation only. Real acquisition preserves independent
elapsed-realtime nanosecond timestamps and has its own worker, bounded queues,
validation and measured rates, documented in ACQUISITION.md. Synthetic signals
are independent interface fixtures, **not mutually consistent vehicle physics**.
They must not be used to validate or train the navigation algorithm.

## Tests and verification

The implemented shared contract is documented in `../contracts/v1/README.md`.
`ContractFixtureTest` serializes Kotlin-native values and compares them with the
same golden JSON used by Python, including exact nanoseconds, nulls and rotations.
Gson 2.11.0 supports the strict runtime codec. Acquisition uses these typed records
with source `real`; the old IO-VNBD export frames are not remapped by this app.

The two replay readers are kept honest against each other: `PythonParityTest` encodes
fixture sessions once with the canonical Kotlin codec — the exact byte form an export
carries — and runs the same bytes through `ReplayReader`/`SessionFiles` and through
`tools/parity_probe.py` (the Python reader that consumes exported recordings). Both must
agree on acceptance, refusal code, record order, exact Int64 timestamps and replay
source. The Python interpreter is located under the repository root (`.venv` first), so
the check runs in ordinary `testDebugUnitTest` with no device attached. After a real
recording is copied off the phone, `python tools/validate_phone_recording.py --local
<session-dir>` accepts or refuses it with the same rules.

`testDebugUnitTest` covers initial empty state, monotonic elapsed time, duplicate
Start prevention, Stop cancellation, background stop, fresh restart, idempotent
Stop, scheduler gaps, backward-clock defense, deterministic finite signals,
invalid time rejection and long-duration formatting.

`connectedDebugAndroidTest` covers source labels, empty initial display, controls,
screen navigation, background/foreground transitions and activity recreation.
New acquisition UI tests also start/stop phone listeners; existing granted location
permission can allow local fixes during those tests. No data is written/uploaded.
An Android Location fixture separately verifies hasSpeed/hasBearing semantics.
See the new device procedure before interpreting a host test as hardware evidence.

Generated reports are under `app/build/reports/tests`, `app/build/reports/androidTests`
and `app/build/reports/lint-results-debug.html`. These are Android build outputs,
not the repository's historical Phase 0 reports.

Manual acceptance: Start on Dashboard; verify changing demo values; switch to
Diagnostics; Stop and verify values freeze; start a new demo; press Home and
return, verifying it stays stopped. Read About for scope/privacy boundaries.
Test counts change as the suite evolves; use the dated results in
[FINAL_RELEASE_READINESS.md](../FINAL_RELEASE_READINESS.md) and the current local
Gradle/Python output rather than old count snapshots. Android instrumentation APK
assembly is compile evidence only; it does not mean tests ran on a device.
Never test the UI while driving.

## Security and privacy verification

The local/offline boundary, automated gates, and hands-on device checklist are documented in [SECURITY_VERIFICATION.md](SECURITY_VERIFICATION.md). It covers the effective permission set, no-backup private recordings, user-initiated local export, safe paths, strict codecs, offline asset and experiment integrity, the dependency audit, and the fact that no model is currently deployed. Host tests/builds do not substitute for the documented physical-device checks.

## Privacy and research boundaries

The manifest declares fine/coarse location. The app asks only after Allow location
is tapped; IMU operation and simulation do not require a location grant. No
background-location, foreground-service, notification or internet permission is
requested. When a user explicitly starts local recording, sensor and permitted
location data is persisted in app-private, no-backup storage until the user deletes
the session or clears app data; export is a separate explicit copy. The app has no
upload path. Android may retain ordinary installation/runtime diagnostics. Backup
is disabled. No analytics or third-party network SDK is included.

The app does not read or modify `data/raw/`, Python code or historical reports.
The Python pipeline remains offline research. The future navigation `core/` and
secondary `edge/` deployment are not implemented here. Existing IO-VNBD frame
uncertainties cannot be resolved simply by displaying this phone's future readings.

Current release gates and scoped evidence are consolidated in
[FINAL_RELEASE_READINESS.md](../FINAL_RELEASE_READINESS.md). Do not claim field
navigation until calibration composition, independent reference drives and the
physical performance/accuracy gates pass.
