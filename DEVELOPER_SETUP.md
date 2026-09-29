# Developer setup and environment

Everything runs offline. The development machine needs internet once, for
dependency download; the built app never touches the network (the manifest
removes `INTERNET` and network-state permissions, and a device test asserts
`INTERNET == PERMISSION_DENIED`).

## Toolchain versions (as verified)

| Tool | Version | Notes |
|---|---|---|
| Python | CPython 3.12 (verified 3.12.14), 64-bit | Other versions unverified |
| Python deps | `requirements.txt` (numpy 2.3.5, pandas 3.0.1, scipy 1.16.3, matplotlib 3.10.7) | Direct deps pinned; transitive resolved by pip — not a full lockfile |
| JDK | 17+ (Android Studio bundled JBR recommended) | Bytecode target 17 |
| Gradle | 9.3.1 | Wrapper JAR tracked; SHA-256 pinned in `gradle-wrapper.properties` |
| AGP | 9.1.1 | Supplies built-in Kotlin 2.2.10 — do **not** add `org.jetbrains.kotlin.android` |
| Compose BOM | 2026.02.01 | Material 3, activity-compose 1.12.4, lifecycle 2.10.0, coroutines 1.10.2 |
| Android SDK | compile 37, target 36, min 26 | Target 36 is intentional for now |
| MapLibre | `org.maplibre.gl:android-sdk-opengl:13.6.1` | Runs fully offline (`MapLibre.setConnected(false)`) |
| Gson | 2.11.0 | Strict contract codec |

## Python setup

From the repository root:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip check
```

Activation is optional; use the interpreter directly. Set `PYTHONUTF8=1` on
PowerShell. Never commit `.venv/`. Dataset placement (`data/raw/iovnbd`) and
the research diagnostics are documented in the [root README](README.md).

Run the Python suite:

```powershell
.\.venv\Scripts\python.exe -B -X utf8 -W error::ResourceWarning -m unittest discover -s tests
```

Expected: 140 tests, 1 skip (symlink-dependent), 0 failures.

## Android build and test

Open `mobile/` in Android Studio (not the repository root), or build from the
command line. PowerShell from the repository root:

```powershell
Set-Location mobile
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain
```

Expected: 154 host tests, 0 failures; `lintDebug` 0 errors (5 known
dependency/target-version warnings); debug APK at
`app/build/outputs/apk/debug/app-debug.apk`.

**Gradle cache location — read this before debugging a plugin error.** The
populated cache is `mobile/.gradle-user-home` (contains the Android Gradle
plugin). The repository-root `.gradle-user-home` is a partial cache **without**
`com.android.application`; pointing `GRADLE_USER_HOME` there fails with
`Plugin [id: 'com.android.application', version: '9.1.1'] was not found`.
Compute `GRADLE_USER_HOME` **before** any `cd`, from the repository root:

```bash
# Git Bash / bash (macOS, Linux)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export GRADLE_USER_HOME="$(cygpath -m "$PWD/mobile/.gradle-user-home")"
cd mobile && ./gradlew.bat testDebugUnitTest lintDebug --offline --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process
```

`--offline` works after the first dependency sync. Never commit
`local.properties`, any `.gradle*` directory, or build outputs.

## Instrumented tests (physical device)

With one authorized device connected (`adb devices` shows it as `device`):

```powershell
.\gradlew.bat connectedDebugAndroidTest --console=plain
.\gradlew.bat installDebug --console=plain
```

Expected: 26 device tests, 0 failures. Notes learned from real runs:

- The test runner may **uninstall the app** when the suite finishes; reinstall
  with `installDebug` and re-seed any session you need afterwards.
- With multiple devices, set `ANDROID_SERIAL` first.
- On Git Bash, scope `MSYS_NO_PATHCONV=1` to adb commands only (it breaks
  `JAVA_HOME` translation when exported globally):
  `(export MSYS_NO_PATHCONV=1; adb shell run-as com.intelligentdeadreckoning.app ls no_backup/recordings)`.
- `adb exec-out screencap -p > out.png` needs a stable working directory — run
  it from the repository root or use an absolute path.
- Read-only session inspection on a debug build:
  `adb shell run-as com.intelligentdeadreckoning.app cat no_backup/recordings/<id>/metadata.json`.

Only install debug builds on devices you control. The release build is not
signed or configured for distribution.

## Validating a recording

After copying a session off the phone (or via `run-as`):

```powershell
.\.venv\Scripts\python.exe tools\validate_phone_recording.py --local path\to\session
```

Exit codes: 0 accepted, 2 usage, 3 contract violation, 4 device unreadable.
The same rules the on-device reader enforces; positions are never printed.

## Continuous integration

GitHub Actions runs the Python suite, the Gradle JVM/lint/debug builds and a
repository-hygiene gate on every push (see [CI.md](CI.md) for the exact gates,
what CI cannot verify and the manual device gates). CI reproduces the commands
documented above; run them locally before pushing.

## Where things are

Architecture, ownership boundaries and the artifact policy:
[ARCHITECTURE.md](ARCHITECTURE.md). What is implemented/verified/experimental:
[root README](README.md). Subsystem evidence:
[mobile/VERIFICATION.md](mobile/VERIFICATION.md),
[mobile/MAP_DEVICE_VERIFICATION.md](mobile/MAP_DEVICE_VERIFICATION.md),
[reports/](reports/).
