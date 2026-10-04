# Intelligent Dead Reckoning — SIH #26168

An offline Android prototype for exploring phone sensing, local recordings, replay,
Hyderabad map display, and navigation research. **This is not a qualified dead-reckoning
navigation product.** The app now estimates session-local calibration and hands it to fusion
after motion/course gates pass; field acceptance is pending. There is no approved field
ground-truth package or trained model. See [the demo procedure](mobile/DEMONSTRATION.md) and
[known limitations](KNOWN_LIMITATIONS.md) and [final release readiness](FINAL_RELEASE_READINESS.md).

No public hosting, cloud service, account system, or analytics are part of this package.
The Android app requests no Internet permission. Internet may be needed on a development
computer for the initial dependency download. The local demo uses bundled assets and is
designed for disabled connectivity; physical airplane-mode verification remains pending.

## Quick start

Requirements: Android Studio with JDK 17+ (verified JDK 25), Android SDK Platform 37,
Build Tools 36.0.0, and an Android 8.0+ device/emulator. From PowerShell at the repository
root (adjust SDK/JDK paths):

```powershell
Set-Location mobile
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain
```

Once dependencies and the Gradle distribution are cached, add `--offline` to build without
network access. Debug APK: `mobile/app/build/outputs/apk/debug/app-debug.apk`.

**Never uninstall or clear app data to update the prototype:** recordings are app-private
and uninstallation removes them. Use the same-signature, in-place `adb install -r` update
procedure in [the local release package](RELEASE_PACKAGE.md). The current release variant
is not distribution-signed.

## Local synthetic demo procedure

> This is the reproducible rehearsal path, not a claim of current physical-device verification;
> ADB was unavailable for this pass. See the local status in
> [FINAL_RELEASE_READINESS.md](FINAL_RELEASE_READINESS.md).


1. Install/launch **IDR Demo**, then enable airplane mode (disable Wi-Fi separately if the
   device permits Wi-Fi while in airplane mode).
2. Open **Map → Synthetic demo → Start demo**. Show the local basemap, attribution,
   synthetic marker/trail, and scripted availability timeline. Labels identify this as a
   synthetic UI fixture—not a real position, navigation result, or accuracy claim.
3. Open **Evaluation** to inspect the checked-in *host scripted-truth* report. It is not
   field ground truth and not device performance. The ML arm is explicitly not implemented.
4. Optional fixture replay: run the `ReplayUiTest` device test, which seeds and replays a
   small synthetic, non-location recording from tracked fixture bytes, then cleans up.

The complete build, preserve-data update, offline verification, demo, evaluation and
fallback-recording procedures are in [RELEASE_PACKAGE.md](RELEASE_PACKAGE.md). A physical
airplane-mode/device run could not be completed in this workspace because `adb` is absent;
do not describe the procedure as device-verified here.

## Capability boundary

- **Implemented:** foreground phone sensor/optional GNSS acquisition; private local session
  recording/recovery, export, replay and user-confirmed deletion; offline Hyderabad vector
  tiles; synthetic map demo; strict Python/Kotlin contracts; experimental host-tested
  calibration/fusion/constraints and a parallel opt-in map-matching overlay.
- **Not accepted as navigation:** calibration hand-off awaits moving-device acceptance; no independent
  moving-drive/outage reference qualification, and no current physical navigation
  acceptance. The map does not calculate positions.
- **Not implemented:** trained model or inference, field ground-truth evaluation package,
  routing/turn-by-turn, cloud/backend, public hosting, or production signing.

See the [implemented-vs-future matrix](docs/PROTOTYPE_CAPABILITIES.md),
[architecture diagram](docs/LOCAL_RELEASE_ARCHITECTURE.md), and
[troubleshooting guide](TROUBLESHOOTING.md). Root Python research dependencies are pinned
directly in [requirements.txt](requirements.txt); the Android and toolchain inventory is
[release/environment-manifest.json](release/environment-manifest.json). These do not claim
complete transitive dependency locking.

## Local checks

```powershell
python -B -X utf8 -m unittest discover -s tests -q
python -B tools/check_repo_hygiene.py
Set-Location mobile
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest --offline --console=plain
```

Instrumentation is compiled by `assembleDebugAndroidTest`; it only runs with an attached
authorized device (`connectedDebugAndroidTest`). Test/build evidence and external blockers
are summarized in [FINAL_RELEASE_READINESS.md](FINAL_RELEASE_READINESS.md). Raw IO-VNBD data,
local recordings and device artifacts are not required for the synthetic app demo and are
not included in Git.
