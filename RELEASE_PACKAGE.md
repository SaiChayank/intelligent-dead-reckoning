# Local prototype release package

**Scope:** reproducible local Android prototype, not a production navigation release. No
server, cloud, public hosting, account, telemetry or analytics are needed. This document is
the operational entry point; current outcomes and blockers are in
[FINAL_RELEASE_READINESS.md](FINAL_RELEASE_READINESS.md).

## 1. Final Android build

Verified stack: Android Studio JBR / JDK 25 (bytecode 17), Gradle Wrapper 9.3.1 with
SHA-256 `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`, AGP 9.1.1,
Android SDK Platform 37, Build Tools 36.0.0, target SDK 36, minimum SDK 26. Use the
repository's wrapper and Gradle cache; don't install a system Gradle.

From the repository root in PowerShell (adjust local paths):

```powershell
Set-Location mobile
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest --console=plain
```

The first build may need Internet on the development computer to fetch wrapper,
plugins/dependencies and SDK components. After those are present, add `--offline` to
reproduce entirely from local caches. On macOS/Linux, set `JAVA_HOME`, `ANDROID_HOME`,
and `GRADLE_USER_HOME` similarly and run `bash ./gradlew ...` from `mobile/`.

Artifacts:

- Debug app: `mobile/app/build/outputs/apk/debug/app-debug.apk`.
- Release variant: `mobile/app/build/outputs/apk/release/app-release-unsigned.apk` (unsigned;
  release build is not signed/configured for distribution).
- Device test APK: `mobile/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

Build output is local/ignored. Compute SHA-256 locally when handing a build to another
engineer; hashes can change with toolchain, build inputs, or debug signing and are not a
claim of cross-machine byte-reproducibility. No signing key is generated or included.

## 2. Preserve-recordings install/update

Recordings reside in the app's private `noBackupFilesDir/recordings/`; Android backup is
disabled. Updating with the same application ID and signing certificate using `adb install
-r` preserves this app data. **Do not use `adb uninstall`, `pm clear`, Android Studio's
uninstall option, or clear storage.** Uninstall/reinstall is destructive to recordings.

With exactly one authorized device (or set `ANDROID_SERIAL`), from repository root:

```bash
adb devices
adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.intelligentdeadreckoning.app/.MainActivity
```

Before and after update, optionally inventory private recording file paths/byte hashes
using a debuggable same-signature install. `run-as` is available only for debuggable
builds; keep the inventory outside the repository, do not print or share session contents,
and compare each file hash plus relative path. No such fresh device inventory was possible
in this workspace. The test runner may uninstall a test package when done; install the app
APK separately after instrumentation and confirm the recordings still exist.

If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop: the installed package has a
different signing key. Do not uninstall it when recordings matter. Export sessions first
through the app's explicit local ZIP flow, preserve the originals, then arrange a compatible
signed update through the device owner. This prototype's unsigned release variant cannot
serve as that signed update.

## 3. Environment/dependency manifest

See [release/environment-manifest.json](release/environment-manifest.json) for pinned
direct Python and Android build/runtime coordinates, SDK levels, app identity, and declared
offline/privacy boundary. Python direct dependencies are pinned in
[requirements.txt](requirements.txt). Gradle wrapper distribution has a pinned hash;
Android coordinates are pinned in Gradle build files. **Transitive dependencies are not
fully locked/SBOM-captured**, and no Android advisory scan was available locally. Run
`pip-audit` via the CI workflow and an independently reviewed Android dependency scan
before any distribution claim. Do not install scanners or update dependencies as part of
the offline demo.

## 4. Offline map and road graph verification

From repository root, verify bundled tiles/resources with the standard library:

```powershell
python -B mobile/tools/verify_hyderabad_pack.py
```

JVM checks validate MBTiles/resource SHA-256 and size against
`mobile/app/src/main/assets/offline/hyderabad/manifest.json`, MBTiles structure and tile
inventory. The map is bounded to central Hyderabad; it is not a whole-city map. The
independent OSM road graph is a map-matching input, not a routing graph. Its asset SHA-256
is `b05842482abf96805342925dfd91a48660d26ca365d1f92d898b4a5201ed066f` (7,675,094 bytes),
recorded in `mobile/app/src/main/assets/roadgraph/hyderabad-v1/manifest.json`; the
`RoadGraphTest` and Python road-graph asset test verify it. ODbL attribution/license details
are in [its notice](mobile/app/src/main/assets/roadgraph/hyderabad-v1/NOTICE.md). Neither
package is downloaded by the app. To verify strict measurement-contract bytes across Python
and Kotlin locally without checking generated data into Git:

```bash
python -B -X utf8 -m contracts.v1.interop export mobile/app/build/contract_interop/python_1_0.jsonl --corpus 1.0.0
python -B -X utf8 -m contracts.v1.interop export mobile/app/build/contract_interop/python_1_1.jsonl --corpus 1.1.0
cd mobile
IDR_CONTRACT_PYTHON_JSONL=build/contract_interop/python_1_0.jsonl IDR_CONTRACT_PYTHON_JSONL_1_1=build/contract_interop/python_1_1.jsonl bash gradlew testDebugUnitTest --offline --console=plain -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks --tests '*StrictContractTest.kotlinConsumesPythonOutputAndEmitsTypedOutputForPython' --tests '*StrictContractTest.kotlinConsumesPythonOutputAndEmitsTypedOutputForPython11'
cd ..
python -B -X utf8 -m contracts.v1.interop verify mobile/app/build/contract_interop/kotlin.jsonl --corpus 1.0.0
python -B -X utf8 -m contracts.v1.interop verify mobile/app/build/contract_interop/kotlin_1_1.jsonl --corpus 1.1.0
```

The exported and Kotlin-emitted interop files live under ignored Gradle `build/`, not Git.

On an authorized physical device, enable airplane mode, disable Wi-Fi if still active, then
launch the installed APK and load Map. Confirm streets/labels, attribution, and the synthetic
demo. Check system package permission output shows no INTERNET permission; the host and
instrumentation security tests also check effective permission policy. Record build hash,
device/OS, whether radios were confirmed off, and screenshots. **That physical no-Internet
verification remains pending here: ADB/device access was unavailable.** Source/host asset
verification is not a substitute for the physical run.

## 5. Model artifact manifest and hashes

[models/model_manifest.json](models/model_manifest.json) is the explicit artifact manifest:
`no_model_artifacts`, count 0, artifact list empty, and consequently no model SHA-256 values
to report. `reports/training_admission.json` says `no-go`, 0 approved sequences. Do not
invent weights/hashes or treat the experimental engine as AI. Training and on-device
inference are not part of this release package.

## 6. Ground-truth evaluation package

[evaluation/ground_truth_manifest.json](evaluation/ground_truth_manifest.json) explicitly
records `blocked_no_field_ground_truth`: no approved independent moving-drive reference or
sealed field experiment exists, and the experiments corpus is empty. The package is not
silently replaced by test simulations. The only included evaluation evidence is the
reproducible host `scripted_truth` report at
`contracts/evaluation/v1/golden_report.json` and the byte-identical Android asset. It is
useful as a code/test fixture only, not field, physical-device, or independent real-world
performance evidence. The Evaluation tab labels its provenance and missing ML results.

When approved field truth exists, follow
[the experiment collection protocol](docs/PS26168_Experiment_Data_Collection_Protocol.md),
seal with `python tools/seal_experiment.py experiments/<id>`, validate with
`python tools/validate_experiment.py --experiment experiments/<id>` and
`python tools/validate_experiment.py --corpus experiments`, then generate/report the
prespecified held-out evaluation. Never fit calibration, timing, preprocessing, or map
matching thresholds on the held-out reference.

## 7. Demo recording/replay fixture

Tracked test fixture: `mobile/app/src/androidTest/assets/demo-replay-v1/` contains a strict
recording `metadata.json` and two `measurements.jsonl` **synthetic diagnostic-only rows**;
no sensor data, location, device identity, or ground truth. Host validation:

```powershell
python -B tools/validate_phone_recording.py --local mobile/app/src/androidTest/assets/demo-replay-v1
```

Expected acceptance is two diagnostic records and `source: simulation`. On device, run
`ReplayUiTest` as part of the complete connected suite. The test copies fixture bytes into
a uniquely named app-private disposable session, checks explicit `replay_simulation` source
and controls, then removes the fixture. It demonstrates replay mechanics, not dead reckoning
or navigation. A normal clone does not seed private app storage or silently import it into
the demo UI.

The separate Map synthetic demo is UI-generated and deterministic; it demonstrates map
rendering/controls, not a captured trip. Existing local phone recordings are private,
ignored artifacts and must not be used as a portable fixture or committed.

## 8. Fallback screen recording

Preferred proof is a live, freshly rebuilt local demo with visible source labels and airplane
mode confirmed. If device access is unavailable, make a short screen capture of the host
build/demo on an emulator only if one is already installed; do not install new dependencies
or claim a physical run. Label every clip visibly or in its opening slate as **“Prototype UI
demo — synthetic fixture; not field navigation evidence”**. Show build/revision, device/emulator,
OS, and connectivity status. Keep recordings local; redact location, device IDs, notifications,
recording IDs, or private session lists. Store captures under ignored `mobile/artifacts/`;
do not attach them to public hosting, upload, or check them into Git. For a prerecorded
fallback, identify it plainly as a rehearsal recording and state the capture date/build; never
present it as live operation.

## 9. Final SIH prototype runbook

No driving or unsafe phone operation during presentation. Use a stationary phone or emulator.
Prepare the app before radios are disabled only if dependencies/build/installation require it;
the app itself should then operate offline.

1. State the boundary up front: this is an offline Android prototype; normal app flow has no
   aligned fusion solution; no field-reference or ML claim is available.
2. Show installed app identity/version and local build hash recorded in the demo notes.
3. Enable airplane mode; explicitly disable Wi-Fi if the device leaves it enabled. Note that
   GNSS reception can be unavailable in airplane mode and is not required for the UI fixture.
4. Open **Map**; show local tile labels, visible attribution and coverage disclosure.
5. Select **Synthetic demo**, tap **Start demo**, wait through the scripted timeline, pause,
   resume and stop. Point out source label and the explicit synthetic disclaimer.
6. Open **Evaluation**; show host scripted reference, limitations, absent/not-implemented ML
   arm, and report provenance. Explain that results are not field data.
7. If demonstration time allows, show **Signals** in simulation and the read-only fixture replay
   using the authorized instrumented procedure. Do not start recording genuine location unless
   explicitly approved and needed; do not use anyone else's recording.
8. Close with the status: locally reproducible prototype package; production navigation is
   **not ready** until calibration integration, independent moving-drive evidence, field
   evaluation, physical device acceptance, security review, signing, and licensing gates pass.

Expected outcomes are map assets from bundle/cache, synthetic labels on the fixture, no app
network request or permission, no required login, and no production claim. If the airplane-mode
map does not draw, stop and capture the exact diagnostic; do not switch to an online map or
quietly continue as if offline validation passed.

## 10. Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common SDK, Gradle, install/update, map,
replay, permissions, offline, and recording-preservation failures. Do not erase app data as
a troubleshooting shortcut.
