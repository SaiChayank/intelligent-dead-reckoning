# Foundation verification — 2026-09-16

Scope: Kotlin + Jetpack Compose UI and simulation only.

## Verified result

- Gradle wrapper generated from the official Gradle 9.3.1 distribution after
  checking SHA-256 `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`.
- Debug APK assembled successfully (12,260,168 bytes).
- 12 JVM unit tests passed; no skips or failures.
- 5 instrumented Compose UI tests passed on OnePlus 12R / CPH2585, Android 16,
  API 36. These cover controls, screen navigation, background stop and recreation.
- Lint completed with **0 errors, 7 warnings**. The remaining warnings concern
  the intentionally pinned Gradle/dependency versions and target SDK 36 rather
  than the newer SDK. They are not hidden by a lint baseline or suppression.
- The debug app was installed again after the instrumentation suite and launched
  successfully. The test runner can uninstall its test app/build on completion;
  run installation separately after connected tests when you want to keep it.
- Manual phone UI check passed: Start, changing demo values, Diagnostics,
  About, return to Dashboard and Stop. The UI hierarchy confirmed `Stopped` and
  `Start new demo` after stopping. Dashboard and Diagnostics screenshots were
  visually inspected and saved locally in the ignored `mobile/artifacts/` folder.

The first lint run found an API-27-only theme attribute with minimum SDK 26;
the redundant attribute was removed and lint rerun successfully. The initial
background UI assertion incorrectly expected a partial string as an exact match;
it now checks the full expected message. The production behavior already stopped
correctly, and all five UI tests passed after the assertion correction.

## Commands

From `mobile`, with `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME` and
`ANDROID_SERIAL` set as described in the README:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest --console=plain
# Install separately AFTER the test runner has finished:
.\gradlew.bat installDebug --console=plain
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -W -n com.intelligentdeadreckoning.app/.MainActivity
```

The final successful build invocation also included `installDebug`. Since Gradle
ran that before test cleanup, the APK was then explicitly reinstalled with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`; launch returned
`Status: ok`. Avoid relying on task ordering for retaining the app after tests.

Verified development runtime: Android Studio bundled JBR 25.0.3, AGP 9.1.1,
Gradle 9.3.1, SDK Platform 37.0 and Build Tools 36.0.0.
The first build emitted a non-fatal SDK metadata-version warning and packaged
`libandroidx.graphics.path.so` without stripping debug symbols. Neither prevented
the build, installation or tests.

Debug APK SHA-256:
`6e8ee6d04eb191229dc087966ef346cc062a97fe220bf0d98fc88fb34e565bdc`.
This is the verified local debug artifact, not a cross-machine byte-reproducibility
claim: debug signing keys and tool environments can differ.

## Preservation and boundaries

- Compared all **1,186 raw dataset files** with the existing Phase 0 manifest:
  file count, SHA-256, sizes and modification timestamps match; no missing files.
- Compared 16 historical report files against the saved pre-follow-up snapshot:
  hashes, sizes and timestamps match. The two already-existing frame-export
  follow-up reports remain present. No historical report was regenerated.
- No Python, INS, AI, EKF, map matching, edge or raw-data file was edited.
- Changes for this task: the new `mobile` project, root README Android status/setup
  sections and Android-specific `.gitignore` entries. Pre-existing Git work was
  left intact; no commit, reset or checkout was performed.
- The merged manifest contains AndroidX's app-private signature permission for
  receiver protection, but no location, internet, foreground-service, notification
  or sensor-access permission. No real acquisition API is called.

## Not yet verified / not delivered

Only one physical device/OS was tested. Older supported APIs, tablets, landscape,
TalkBack and large-font accessibility need broader device coverage before a
production release. The release build is not distribution-signed. UI text is
English-only and the visual theme is currently fixed dark.

All values remain scripted; this is not a measured IMU sampling-rate test, an
INS validation, a real GNSS fix or a trip recorder. Real acquisition is the next
separate implementation stage.
