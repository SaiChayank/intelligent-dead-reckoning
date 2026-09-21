Android real acquisition evidence — 2026-09-19

Purpose

This report records the physical-device acceptance evidence for the foreground Android acquisition stage of Intelligent Dead Reckoning (IDR). It covers real sensor acquisition, runtime location-permission behavior, GNSS metadata, lifecycle/source ownership, Android listener cleanup and a stationary endurance run.

It does not validate navigation accuracy, INS mechanization, phone-to-vehicle mounting, calibration, AI inference, map matching, recording/export/replay or background collection.

Test device and environment

Device: OnePlus 12R / model identifier CPH2585

OS: Android 16 / API 36

App package: com.intelligentdeadreckoning.app

Activity: .MainActivity

Android SDK: C:\Users\Saich\AppData\Local\Android\Sdk

Java runtime: Android Studio bundled JBR 25.0.3 at C:\Program Files\Android\Android Studio\jbr

Gradle: 9.3.1

The app remained foreground-only. No foreground service, background location, trip recorder or upload path was introduced for this stage.

Automated verification

The final verification cycle completed successfully:

.\gradlew.bat test
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat assembleDebug

Observed result:

host/JVM test task: successful;

lint: successful;

connected device tests: 8 tests, 0 skipped, 0 failed on CPH2585 / Android 16;

debug APK assembly: successful.

installDebug was run separately when the app needed to remain installed after connected testing because the instrumentation/test workflow can remove the app/test package.

Real sensor hardware and measured rates

Channel

Reported device

Vendor

Requested rate

Measured rate observed

Accelerometer

bmi26x Accelerometer Non-wakeup

BOSCH

100 Hz

~98.79–98.80 Hz

Gyroscope

bmi26x Gyroscope Non-wakeup

BOSCH

100 Hz

~98.79–98.81 Hz

Gravity

gravity Non-Wakeup

QTI

50 Hz

~49.39–49.40 Hz

Magnetometer

mmc56x3x Magnetometer Non-wakeup

memsic

50 Hz

50.00 Hz

Rates remained stable across the stationary endurance run. They are observed rates on this device and are not promised rates on other hardware.

Runtime location-permission acceptance

NOT_REQUESTED

Real IMU acquisition ran without location permission.

The state remained NOT_REQUESTED.

Diagnostic reason was corrected to LOCATION_NOT_REQUESTED instead of being collapsed into PERMISSION_DENIED.

DENIED

A fresh first denial produced the denied state.

IMU acquisition continued.

No location fix was fabricated.

APPROXIMATE

Approximate-only location was enabled through Android settings.

Network-provider location was used when available.

Satellite counts remained unavailable rather than being invented.

A cached/pre-session location was explicitly rejected as INVALID_MEASUREMENT.

Backgrounding while visiting settings stopped the active session, as designed.

PRECISE

Precise access enabled GPS-provider acquisition and GNSS satellite status.

Observed GPS rate was approximately 0.997 Hz.

One observed satellite snapshot showed 91 visible satellites and approximately 40–45 used.

One observed GPS fix reported approximately 9.935 m horizontal accuracy and 2.710 m vertical accuracy.

Speed could be a real zero or null; bearing could remain null. Optional Android fields were not replaced with fabricated values.

Network-provider fixes could coexist and were kept provider-labelled rather than silently merged with GPS.

The displayed navigation quality remained conservative (degraded) because this stage does not validate a navigation solution.

REVOKED

Location permission was revoked after previously being granted.

IMU acquisition remained available.

GNSS state/data was cleared or became unavailable.

PERMISSION_REVOKED was emitted.

Returning to the app did not silently restart the stopped session.

Physical orientation and gyroscope sanity

The phone was held in six opposing orientations. Representative gravity readings were approximately:

Orientation

Representative gravity behavior

upright / screen vertical

dominant +Y near 9.8 m/s²

upside-down vertical

dominant -Y near 9.8 m/s²

right-side horizontal

dominant -X near 9.8 m/s²

left-side horizontal

dominant +X near 9.8 m/s²

screen facing upward

dominant +Z near 9.8 m/s²

screen facing downward

dominant -Z near 9.8 m/s²

For the final Z-axis pair, gravity was approximately +9.806 m/s² with the screen facing upward and -9.807 m/s² with the screen facing downward.

Deliberate rotations produced activity on gyroscope X, Y and Z. One observed moving sample was approximately X +0.247, Y +0.265, Z -0.442 rad/s. When stationary, gyro readings returned close to zero.

Result: physical axis sanity passed for this stage. This does not establish a vehicle mounting transform or sensor calibration.

Lifecycle and source ownership

Physical checks confirmed:

starting Phone sensors begins real acquisition;

pressing Home/backgrounding stops the active session;

returning to the app remains stopped and requires explicit Start;

switching from Phone sensors to Simulation stops the real acquisition source;

switching back to Phone sensors does not silently resume the prior run;

last values may remain visible for inspection while stopped, but a new Start creates a new session.

Android listener cleanup

Sensor listeners

adb shell dumpsys sensorservice showed registrations for accelerometer, gyroscope, gravity and magnetometer while the real source was active and corresponding removals after Stop.

Observed requested periods matched the implementation intent:

accelerometer: 10,000 µs;

gyroscope: 10,000 µs;

gravity: 20,000 µs;

magnetometer: 20,000 µs.

Result: sensor listener cleanup passed.

Location listeners

With precise location enabled, adb shell dumpsys location showed active application requests for both GPS and network providers while acquisition was running. The current test registration was added at approximately 15:10:39 and removed at approximately 15:11:22.

Evidence included:

GPS +registration with HIGH_ACCURACY;

network +registration with BALANCED;

GPS -registration after Stop;

network -registration after Stop;

OEM registration state changing from addLocation to removeLocation.

Result: location listener cleanup passed.

30-minute stationary endurance

The foreground acquisition session began at approximately 16:56. The phone remained stationary for about 30 minutes.

Pipeline counters at the final observation

Metric

Observation

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

0 / 256

Queue peak

18 / 256

A queue peak of 18 remained well below the fixed 256-item capacity. No dropped, invalid or duplicate measurements accumulated during this run. Two delayed measurements were reported explicitly rather than hidden.

Memory samples

Collected with:

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell dumpsys meminfo com.intelligentdeadreckoning.app | Select-String "TOTAL"

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

Memory rose slightly during the early part of the session and then fell below the first reading. This does not show monotonic growth during the tested interval. It is a one-device, one-session endurance observation and must not be presented as a formal proof that no leak can exist.

Diagnostics seen during endurance

Some TIME_GAP / stale-location diagnostics were observed while stationary. These relate to the slower location stream and the configured staleness thresholds. The IMU stream remained active, the queue stayed bounded and the final counters showed zero dropped measurements.

Acceptance summary

Check

Result

Real accelerometer

PASS

Real gyroscope

PASS

Real gravity sensor

PASS

Real magnetometer

PASS

Measured rates

PASS

NOT_REQUESTED location state

PASS

DENIED location state

PASS

APPROXIMATE location state

PASS

PRECISE location state

PASS

REVOKED location state

PASS

GPS/provider metadata

PASS

Satellite-status handling

PASS

Nullable GNSS fields

PASS

Six-face gravity sanity

PASS

Gyroscope X/Y/Z response

PASS

Background/lifecycle stop

PASS

No automatic restart

PASS

Simulation/real source switching

PASS

Sensor listener cleanup

PASS

Location listener cleanup

PASS

>=30-minute stationary endurance

PASS

Queue remained bounded

PASS

No monotonic memory growth observed

PASS

Connected Android tests (8/8)

PASS

Lint

PASS

Debug assembly

PASS

Boundaries for the next stage

Prompt 2 is closed at this checkpoint. The acquisition code should not be changed merely to extend this evidence set unless a defect is discovered.

The next implementation stage is Prompt 3: local streaming recording, metadata, bounded asynchronous writes, user-triggered export and replay interoperability. This report makes no claim that those features already exist.