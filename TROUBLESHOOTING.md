# Local prototype troubleshooting

## Gradle/JDK/SDK

- **Gradle plugin not found while using `--offline`:** the machine has not fetched that plugin,
  or Gradle is using the wrong cache. Use the Android Studio JBR and set `GRADLE_USER_HOME`
  to `mobile/.gradle-user-home` (see [build procedure](RELEASE_PACKAGE.md#1-final-android-build));
  one initial online dependency sync may be required. Don't install system Gradle or delete
  caches indiscriminately.
- **SDK platform 37 / build-tools missing:** install Android SDK Platform 37 and Build Tools
  36.0.0 in Android Studio SDK Manager on the development host, then retry. This does not
  enable app network access.
- **Gradle daemon or Kotlin compiler memory failure:** close competing Android builds and use
  the project defaults; retry once. Do not change project dependencies or SDK versions to
  conceal an unexplained failure.
- **Lint warnings:** read the report under `mobile/app/build/reports/`. Report warnings honestly;
  don't baseline/suppress dependency or target-SDK advisories to claim a clean security scan.

## Install/update and recordings

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** signature differs. Do not uninstall or clear data
  if private recordings matter. Export required sessions to local storage first; obtain a
  compatible signed build or use an isolated disposable device.
- **App missing after device tests:** instrumentation runner may uninstall a package. Reinstall
  the debug APK with `adb install -r`; avoid uninstall/test-cleanup if retaining user recordings.
- **Saved sessions missing:** check the active package and whether app data was cleared or the
  app uninstalled. Android no-backup storage intentionally is not restored from cloud backup.
  Do not copy unrelated recordings into the app or modify user files to troubleshoot.
- **ADB says unauthorized/no devices:** unlock phone and accept the debugging prompt; check
  `adb devices`. If ADB is not installed or no device is attached, treat physical verification
  as blocked. Do not claim that APK assembly is device execution.

## Offline map/demo

- **Map says preparing:** wait for local asset verification/copy. The first install needs free
  storage for the APK, native renderer, bundled tiles and private verified copy.
- **Map reports unavailable/corrupt pack:** capture the safe error code and build identity.
  Run `python -B mobile/tools/verify_hyderabad_pack.py` on the host and the offline-map JVM
  tests. Rebuild/reinstall only on a disposable install if investigating data corruption; never
  delete the existing user's private app data as a shortcut.
- **Map is blank in airplane mode:** ensure the demo includes its bundled renderer/style and
  attribution; inspect exact app diagnostics and device logs locally. Do not turn networking
  on or switch to remote tiles to make the demo appear to pass.
- **Position/roads not visible:** synthetic paths and map coverage are distinct. The synthetic
  path is illustrative and the tile pack covers only central Hyderabad. Live GNSS can be absent
  in airplane mode; this is not a failed synthetic demo.
- **Road graph error when overlay is enabled:** disable the optional evaluation overlay, then
  run host road-graph SHA-256 tests. Raw engine output should remain independent. The graph is
  not used for map rendering or routing.

## Demo, evaluation, replay

- **Unexpected navigation position/claim:** stop. Check visible map source; select Synthetic
  demo only to show UI. Ordinary app calibration hand-off is absent; don't substitute a
  synthetic/recorded/live GNSS marker for fusion navigation.
- **Evaluation report unavailable:** use the bundled document and run
  `EvaluationHarnessTest`; compare the two golden report copies with the Python asset test.
  Any current host report remains scripted truth, not independent field evidence.
- **Replay fixture refuses:** ensure `metadata.json` and `measurements.jsonl` remain together;
  run `python -B tools/validate_phone_recording.py --local
  mobile/app/src/androidTest/assets/demo-replay-v1`. It should accept two simulation diagnostic
  rows. Fixture replay on device requires an attached device and the instrumented suite.
- **Replay looks like live data:** stop playback and check the explicit `replay_simulation`
  source label. The fixture intentionally contains no position/sensor values.
- **Fixture remains in app sessions after a failed test:** inspect only the unique
  `000-demo-replay-test-*` session on a disposable/debug device and remove that exact fixture
  via app's confirmed session deletion UI. Never bulk-delete the recording directory.

## Privacy and evidence

- Do not paste raw sessions, precise coordinates, private recording IDs, APK signing material,
  or full device logs into public tickets. Export is an explicit local copy; protect it as
  sensitive trip data.
- Keep screen captures in ignored `mobile/artifacts/`, inspect/redact before sharing, and label
  emulator/synthetic/rehearsal status. Do not store private recordings in the repository.
- If a check cannot run because the dependency tool, ADB, device, or approved ground truth is
  absent, mark it **blocked/pending**, don't install tools, fabricate a result, or downgrade the
  gate.
