Verification

Foundation verification — 2026-09-16

Historical checkpoint. This section records the state of the Android project before real phone acquisition was implemented. Statements such as “simulation only”, “no location permission”, and “all values remain scripted” apply only to this 2026-09-16 checkpoint and are superseded by the 2026-09-19 verification below.

Scope: Kotlin + Jetpack Compose UI and simulation only.

Verified result

Gradle wrapper generated from the official Gradle 9.3.1 distribution after checking SHA-256 b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06.

Debug APK assembled successfully (12,260,168 bytes).

12 JVM unit tests passed; no skips or failures.

5 instrumented Compose UI tests passed on OnePlus 12R / CPH2585, Android 16, API 36. These covered controls, screen navigation, background stop and recreation.

Lint completed with 0 errors, 7 warnings. The remaining warnings concerned intentionally pinned Gradle/dependency versions and target SDK 36 rather than the newer SDK. They were not hidden by a lint baseline or suppression.

The debug app was installed again after the instrumentation suite and launched successfully. The test runner can uninstall its test app/build on completion; installation should be run separately after connected tests when the app needs to remain installed.

Manual phone UI check passed: Start, changing demo values, Diagnostics, About, return to Dashboard and Stop. The UI hierarchy confirmed Stopped and Start new demo after stopping. Dashboard and Diagnostics screenshots were visually inspected and saved locally in the ignored mobile/artifacts/ folder.

The first lint run found an API-27-only theme attribute with minimum SDK 26; the redundant attribute was removed and lint rerun successfully. The initial background UI assertion incorrectly expected a partial string as an exact match; it was corrected to check the full expected message. Production behavior already stopped correctly, and all five UI tests passed after the assertion correction.

Commands used for the foundation checkpoint

From mobile, with JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME and ANDROID_SERIAL set as described in the README:

.\gradlew.bat testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest --console=plain

# Install separately AFTER the test runner has finished:
.\gradlew.bat installDebug --console=plain

& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -W -n com.intelligentdeadreckoning.app/.MainActivity

The final successful build invocation also included installDebug. Since Gradle ran that before test cleanup, the APK was then explicitly reinstalled with adb install -r app/build/outputs/apk/debug/app-debug.apk; launch returned Status: ok. Avoid relying on task ordering for retaining the app after tests.

Verified development runtime for that checkpoint: Android Studio bundled JBR 25.0.3, AGP 9.1.1, Gradle 9.3.1, SDK Platform 37.0 and Build Tools 36.0.0.

The first build emitted a non-fatal SDK metadata-version warning and packaged libandroidx.graphics.path.so without stripping debug symbols. Neither prevented the build, installation or tests.

Historical debug APK SHA-256:

6e8ee6d04eb191229dc087966ef346cc062a97fe220bf0d98fc88fb34e565bdc

This is the verified local debug artifact from the foundation checkpoint, not a cross-machine byte-reproducibility claim: debug signing keys and tool environments can differ.

Preservation and boundaries at the foundation checkpoint

Compared all 1,186 raw dataset files with the existing Phase 0 manifest: file count, SHA-256, sizes and modification timestamps matched; no files were missing.

Compared 16 historical report files against the saved pre-follow-up snapshot: hashes, sizes and timestamps matched. The two already-existing frame-export follow-up reports remained present. No historical report was regenerated.

No Python, INS, AI, EKF, map matching, edge or raw-data file was edited.

Changes for that task were the new mobile project, root README Android status/setup sections and Android-specific .gitignore entries. Pre-existing Git work was left intact; no commit, reset or checkout was performed.

At that historical point, the merged manifest contained AndroidX's app-private signature permission for receiver protection, but no location, internet, foreground-service, notification or sensor-access permission. No real acquisition API was called.

Not yet verified / not delivered at the foundation checkpoint

Only one physical device/OS had been tested. Older supported APIs, tablets, landscape, TalkBack and large-font accessibility still needed broader device coverage before a production release. The release build was not distribution-signed. UI text was English-only and the visual theme was fixed dark.

At that checkpoint all values were scripted; it was not a measured IMU sampling-rate test, an INS validation, a real GNSS fix or a trip recorder. Real acquisition was the next separate implementation stage.

Real phone acquisition verification — 2026-09-19

Scope: foreground real-device sensor/GNSS acquisition, permission handling, source/lifecycle behavior, bounded streaming diagnostics and physical-device acceptance. Recording/export/replay, navigation fusion, calibration, map matching and INS remain outside this checkpoint.

Device and automated verification

Physical device: OnePlus 12R / CPH2585, Android 16 / API 36.

gradlew.bat test completed successfully.

gradlew.bat lintDebug completed successfully.

gradlew.bat connectedDebugAndroidTest ran 8 tests, 0 skipped, 0 failed on CPH2585.

gradlew.bat assembleDebug completed successfully.

installDebug was run separately when the app needed to remain installed after connected tests.

Real Dashboard and real Diagnostics are separate views; simulation and real acquisition do not share the same screen implementation.

Real hardware observed

Channel

Device / vendor

Requested

Observed during verification

Accelerometer

bmi26x Accelerometer Non-wakeup / BOSCH

100 Hz

~98.79–98.80 Hz

Gyroscope

bmi26x Gyroscope Non-wakeup / BOSCH

100 Hz

~98.79–98.81 Hz

Gravity

gravity Non-Wakeup / QTI

50 Hz

~49.39–49.40 Hz

Magnetometer

mmc56x3x Magnetometer Non-wakeup / memsic

50 Hz

~50.00 Hz

The measured rates are device observations, not guarantees for other phones.

Permission and GNSS behavior

The following runtime states were physically exercised:

NOT_REQUESTED: real IMU acquisition continued; diagnostics reported LOCATION_NOT_REQUESTED rather than incorrectly calling the state denied.

DENIED: IMU acquisition continued without location access.

APPROXIMATE: network-provider location was used when available; GNSS satellite counts were not claimed. A cached/pre-session location was rejected explicitly as invalid rather than promoted to current data.

PRECISE: GPS acquisition was observed at approximately 0.997 Hz. Satellite status was available; one observed snapshot showed 91 visible satellites and roughly 40–45 used. An example GPS fix reported approximately 9.935 m horizontal accuracy and 2.710 m vertical accuracy. Speed and bearing correctly remained nullable when Android did not provide them.

REVOKED: after revoking a previously granted permission, IMU acquisition remained available, GNSS data was cleared/unavailable, and a PERMISSION_REVOKED diagnostic was emitted.

The quality state intentionally remains conservative (degraded/stale/unavailable as appropriate); this checkpoint does not certify GNSS fixes as navigation-ready.

Physical sensor-axis sanity checks

Six-face gravity/accelerometer checks were completed on the physical device:

screen-up / screen-down produced approximately +Z / -Z gravity near 9.8 m/s²;

right-side / left-side orientations produced approximately -X / +X gravity near 9.8 m/s²;

upright / upside-down orientations produced approximately +Y / -Y gravity near 9.8 m/s².

The sign changes and dominant-axis magnitudes were physically plausible. Deliberate phone rotations produced non-zero responses on gyroscope X, Y and Z; stationary readings returned close to zero. This is an axis sanity check, not a calibration or phone-to-vehicle mounting solution.

Lifecycle, source switching and listener cleanup

Pressing Home/backgrounding the activity stopped real acquisition.

Returning to the app did not silently restart acquisition; explicit Start was required.

Switching from Phone sensors to Simulation stopped the real source. Switching back did not silently resume it.

dumpsys sensorservice showed the real sensor registrations being removed when acquisition stopped.

With precise location enabled, dumpsys location showed active GPS and network registrations while running, then explicit gps provider -registration, network provider -registration, and removeLocation records after Stop.

30-minute stationary foreground endurance

A stationary foreground run started at approximately 16:56 and continued for about 30 minutes. Final observed pipeline counters were:

Counter

Final observation

Accepted

526,748

Delayed

2

Duplicates

0

Dropped

0

Invalid

0

Queue depth

0 / 256 at final observation

Queue peak

18 / 256

Memory samples collected with adb shell dumpsys meminfo com.intelligentdeadreckoning.app were:

Approx. time

TOTAL PSS

TOTAL RSS

16:57

167,518 kB

304,708 kB

17:07

173,084 kB

310,200 kB

17:16

157,229 kB

294,464 kB

17:26

155,967 kB

293,224 kB

The samples do not show monotonic memory growth during this run; memory rose early and then fell below the initial reading. This is evidence from one device/session, not a formal long-duration leak proof.

TIME_GAP and stale-location diagnostics seen during stationary operation were associated with the slower location channel and are expected under the configured diagnostic thresholds. They did not correspond to unbounded IMU queue growth or dropped IMU samples in this run.

Prompt 2 acceptance status

The following Prompt 2 items are verified on the tested device:

real accelerometer, gyroscope, gravity and magnetometer acquisition;

measured per-channel rates and device metadata;

monotonic event and receipt timestamps;

bounded 256-item acquisition queue and explicit diagnostics;

NOT_REQUESTED, DENIED, APPROXIMATE, PRECISE and REVOKED location states;

nullable GNSS fields and provider separation;

GNSS satellite-status handling under precise access;

lifecycle stop and no automatic restart;

simulation/real source-switch stop behavior;

six-face gravity/accelerometer sanity and gyroscope response;

sensor and location listener cleanup;



=30-minute stationary endurance with bounded queue and non-monotonic memory usage;

host tests, lint, connected device tests and debug assembly.

Detailed evidence is recorded in ../reports/android_acquisition_2026_09_19.md.

Still not delivered / future verification

This checkpoint does not deliver or validate:

trip recording, export or replay;

background acquisition or a foreground service;

phone-to-vehicle mounting calibration;

bias/scale calibration;

INS mechanization on this live phone stream;

EKF/UKF fusion;

AI inference;

map matching;

navigation-quality position estimates;

production distribution signing or broad multi-device compatibility testing.

Recording/export/replay is the next separate implementation stage (Prompt 3).