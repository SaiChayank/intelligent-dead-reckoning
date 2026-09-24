# IDR Android foundation

The native Android application is the main product of Intelligent Dead Reckoning
(SIH #26168). This is a simulation and foreground acquisition app; navigation
is still planned. It is a standalone Gradle project inside the existing repository.

## What works

- Optional central Hyderabad offline map preview: bundled tiles, camera controls, attribution and an explicitly started synthetic marker/heading/trail demo. A v1 navigation presentation adapter is tested but not connected to a real engine. No live position or routing yet. See [map scope, build results and device gate](OFFLINE_MAP.md).
- Recording details, paged local sessions and explicit local ZIP export through Android's document picker are implemented. See [export workflow and device-validation gate](EXPORT.md).
- Read-only local replay supports start/pause/resume/stop with explicit `replay_real` / `replay_simulation` labels. See [replay policy and verification](REPLAY.md).

- Kotlin + Jetpack Compose / Material 3 UI: Dashboard, Diagnostics and About.
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

Not implemented: ZIP import, replay seek/speed controls, rotation-vector events, live map positioning/routing, INS, AI,
fusion, calibration or shared-core implementation. Host tests/build pass;
connected-device acquisition acceptance still requires the procedure linked above.

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
Never test the UI while driving.

## Privacy and research boundaries

The manifest declares fine/coarse location. The app asks only after Allow location
is tapped; IMU operation and simulation do not require a location grant. No
background-location, foreground-service, notification or internet permission is
requested. No location, sensor or trip data is recorded, persisted or uploaded.
Android may retain ordinary installation/runtime diagnostics; that is not trip
recording. Backup is disabled. No analytics or third-party network SDK is included.

The app does not read or modify `data/raw/`, Python code or historical reports.
The Python pipeline remains offline research. The future navigation `core/` and
secondary `edge/` deployment are not implemented here. Existing IO-VNBD frame
uncertainties cannot be resolved simply by displaying this phone's future readings.

Next action: complete connected-phone acquisition validation. A subsequent bounded
task can add local recording/export/replay after acquisition is verified.
Integrate navigation only after coordinate conventions and mechanization are
reviewed and tested separately.
