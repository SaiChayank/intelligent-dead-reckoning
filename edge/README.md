# Edge runtime — secondary JVM target

This standalone Gradle project compiles the existing shared Kotlin contracts and
mobile fusion implementation. It is **not the Android app**. For the phone
prototype, open `../mobile` in Android Studio, select the `app` run configuration,
select the connected phone and run. Do not run `edge/build.gradle.kts` as a script.

See [runtime scope, input format and benchmark limits](src/README.md).
There is no qualified edge-board benchmark or admitted AI model.

## Windows PowerShell verification

From the repository root, this checkout uses Android Studio's installed JBR 25
as the edge toolchain and emits Java 17-compatible bytecode:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = Join-Path $PWD 'mobile\.gradle-user-home'
.\mobile\gradlew.bat -p edge test --offline --console=plain
```

Offline mode requires dependencies already in that cache. On a fresh clone,
omit `--offline` for initial dependency installation. Do not remove or upgrade
the pinned Kotlin plugin merely to hide a dependency-resolution error.
For an IDE-only plugin error, open/link `edge/settings.gradle.kts` as its own
Gradle project, use the installed JBR 25 as Gradle JDK, then sync. The edge target
is independent of `mobile/settings.gradle.kts`.

## Debugging checkpoint

The plugin uses explicit `id("org.jetbrains.kotlin.jvm") version "2.2.10"`.
Memory settings are local to this Gradle project; Kotlin compiles in-process.
Successful shutdown now clears the stopping state. Regression tests distinguish
invalid pre-session input from late input, pair the accepted test measurement,
and hold the worker with a latch to deterministically exercise queue overflow.
No INS/EKF mathematics, contracts or acquisition behavior were changed.

Verification on this checkout: the command above with `--rerun-tasks` passed
all ten edge JVM tests (BUILD SUCCESSFUL, 34 s), without the earlier metaspace
warning. The Android build also passed; the connected phone's
`OfflineMapDeviceTest#demoOptionsPauseOverridesAndReset` passed (one test, 6.028 s).
An Android Studio sync error was not independently reproduced; the supplied
plugin block alone is not an error message.
