# Optional foreground local recording — Prompt 3B

This stage subscribes to the verified `AndroidAcquisition.events` typed stream.
Acquisition and recording contract 1.0.0 are unchanged. No recording operation
is inserted into sensor/location callbacks or the acquisition processor.

## Using it

1. Select **Phone sensors**, then **Start sensors**.
2. Select **Start recording** on Dashboard or Diagnostics. Wait for `recording`.
3. Select **Stop recording**. Wait for `completed` or inspect the explicit failure.
   Stopping recording leaves sensor acquisition running.
4. **Stop sensors**, switching sources, backgrounding, or activity recreation
   stops recording admission and finalizes the accepted prefix. Returning never
   restarts acquisition or recording automatically.

Recording needs no additional permission. Location permission still controls
whether acquisition can emit location fixes. Empty sessions are valid.
Start recording remains disabled until acquisition publishes its sensor configuration.
The display samples recorder status at 5 Hz; internal counters and disk writes remain
at full stream rate. This avoids continuous Compose recomposition during capture.

The simulation shell currently publishes conflated `SimulationState` UI snapshots,
not a canonical typed measurement stream. Recording controls therefore require
Phone sensors. The recorder accepts original `real` and `simulation` contract
records, tested with a synthetic typed stream; this stage does not invent a new
simulation sensor source or reinterpret UI snapshots as Android IMU measurements.

## Storage and privacy

Sessions live under `Context.noBackupFilesDir/recordings/<recording-id>/` with
`metadata.json` and `measurements.jsonl`. The usual device location is
`/data/user/0/com.intelligentdeadreckoning.app/no_backup/recordings/`.
The Android-provided directory is resolved on the I/O worker, since accessing it
can itself create a directory. No shared/external-storage permission or write is
used. Existing backup exclusions remain intact. There is no upload, service,
export, replay UI, or automatic deletion. Clearing app data/uninstalling removes
private recordings; reinstalling during instrumentation may also remove them.

IDs are generated UUIDs. Existing directories are never overwritten by Start.
Metadata uses the frozen codec, exact decimal-string Int64 times/counts, explicit
nulls, and original source. JSONL rows use `RecordingCodec.encodeRecord`, which
delegates to the existing canonical measurement codec. Each row ends in LF.
Records retain arrival order, units, frames and both original timestamps.
Per-type channel counts sum to the finalized record count.

Metadata snapshots the acquisition session identity/origin, phone OS/device,
sensor descriptions, requested/observed rates, and permission state at recording
start. Provider enablement, boot identity and build revision/version information
not available from this snapshot remain null. Project calibration is `not_applied`.
Permission changes during recording are still governed/reported by acquisition;
start metadata is not retrospectively rewritten to claim a different start state.

## Threading, states and loss accounting

`LocalRecorder` owns an independent SupervisorJob and an I/O worker. Its downstream
collector does a nonblocking offer to a capacity-512 channel. It performs no disk
I/O. Only one recording may start at a time, including startup/finalization.
No entire session or growing event-ID set is held in RAM.

States: idle → starting → recording → finalizing → completed/failed. Startup
recovery can also expose recovered (explicitly incomplete) or failed. Repeated
start returns false; the UI disables it while busy. Explicit Stop cancels the
subscriber and closes admission immediately, then drains already accepted records.
The finite drain can finish after foreground exit/owner disposal. It collects no
new background samples and never starts a service or automatically restarts.

Overflow policy: reject the overflowing record, increment dropped, stop admission,
drain the accepted prefix and mark the session **failed** with recovery required.
Subsequent acquisition events are outside the stopped recording boundary. The
acquisition publisher is neither blocked on disk nor stopped by recorder failure.
Source/session mismatch similarly fails admission and never appends the new source.

Counters shown on screen:

- Written: complete append calls that returned successfully; this is not an
  assertion of per-record fsync durability.
- Dropped: recording-boundary records rejected on overflow/mismatch or discarded
  after a writer failure. It excludes samples acquisition itself rejected.
- Write errors: create/write/sync/close/finalization errors.
- ID gaps: skipped IDs between observed acquisition events. These can also arise
  from normal acquisition validation/rejection, so they are **not** an exact
  additional dropped-sample count. Acquisition's existing dropped/invalid/late
  diagnostics remain available separately. The first observed ID need not be zero.

Acquisition allocates decimal IDs sequentially, but can emit up to two diagnostics
(late measurement and time gap) before emitting the measurement that caused them.
Thus arrival order can be `0, 2, 3, 1`. The constant-memory guard tracks a three-ID
window, rejecting duplicates/nondecimal IDs and IDs outside the producer's bounded
reordering behavior. Gaps can decrease as the triggering measurement arrives.
This producer-specific check does not change the wider v1 contract. Timestamps can
be late or equal across sensors; neither timestamps nor IDs are used to sort stored
rows. No claim of lossless capture upstream of the subscriber or before Start/after
Stop is made.

## Finalization and failures

Initial metadata is written to a temporary file, synced and atomically renamed.
JSONL writes stream directly to the private file; clean Stop fsyncs and closes it
before final metadata replacement. Atomic rename failure is surfaced, without a
non-atomic overwrite fallback. The old metadata is retained for the next recovery
attempt; a temporary metadata file may remain after a failure.

Clean metadata is completed/stopped, with exact counts and end time. Writer failure
is failed/failed with recovery required when metadata can be written. Partial write
counts are reconciled by later recovery. Errors remain explicit in runtime state;
the frozen metadata schema has no raw exception/path field. Acquisition continues.
An initial create failure never overwrites a pre-existing session's metadata.

Startup checks run on I/O and block new recording Start while scanning. A process
lease excludes sessions still being finalized by another owner in the same app
process; it is released after the metadata replacement attempt. There is no second
Android process configured. A kernel-blocked storage call cannot be forcibly made
durable by a coroutine timeout; admission still stops immediately and process death
leaves recovery evidence. Abrupt power loss can lose unsynced filesystem data.

## Recovery

Open sessions and sessions requiring recovery are scanned one bounded line at a
time, including membership, timestamp-origin and event-ID checks. Complete rows
are validated by the frozen codec. A valid last record without LF is retained.

Only a final, non-LF-terminated fragment that is a valid but unfinished JSON object
prefix can be trimmed. A strict bounded prefix grammar distinguishes missing bytes
from syntax errors; invalid UTF-8 and ambiguous fragments are conservatively
rejected. All preceding rows must validate before any truncation occurs.
Recovered metadata is **incomplete/interrupted/recovered**, with the valid-prefix
count. It is never reported completed. Recovery on a subsequent load is idempotent.

Blank rows, complete corrupt rows, duplicate IDs/keys, session mismatch, unsupported
versions and corrupt interior bytes are never skipped or repaired. Measurement
bytes remain intact and valid metadata is marked incomplete/unrecoverable.
Missing/invalid metadata or missing measurements is surfaced as a recovery failure.
Already completed sessions are not rescanned during startup; replay validation is
a later stage. Recovery reports remain visible until the next explicit Start.

## Verification and remaining device gate

Host tests cover explicit/repeated Start, early Stop, empty sessions, exact values,
bounded overflow with a latch-controlled slow writer, continued publisher operation,
write/create/sync/close/finalization failure, ownership/source stops, source mismatch,
duplicate IDs, atomic file finalization, recovery/corruption, existing-session
preservation, process leases and nullable GNSS/original simulation source.

`RecordingUiTest` adds real app wiring checks for explicit recording Stop,
acquisition Stop, background/return and source switching. It does not claim outdoor
GNSS accuracy or long-duration performance. These tests can produce private test
recordings; use a development installation without valuable existing recordings.

Commands from `mobile` using the README environment:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat connectedDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Manual acceptance before freezing Prompt 3B:

1. Run the connected suite on the OnePlus and inspect real Start/Stop recording.
2. Record for at least 10 minutes with GNSS where available; inspect written/drop/
   error counters and memory behavior. Confirm sensor rates remain comparable to
   the frozen acquisition baseline and that Stop recording leaves sensors running.
3. Background/return, rotate, Stop sensors and switch sources while recording.
   Confirm finalized status and no automatic restart.
4. On a disposable test session, terminate the process while recording and reopen.
   Confirm explicit recovered/incomplete status. Inspect metadata/JSONL locally
   through Android Studio Device Explorer; export UI is intentionally absent.
5. Verify on-device storage failure behavior in a controlled test environment.
   Host ENOSPC injection is not a claim of physically filling the phone storage.

No physical recording acceptance is claimed by this implementation.

## Execution checkpoint — 2026-09-22

- `testDebugUnitTest`: **74 tests passed**, 0 failures/errors/skips. This includes
  16 new recorder tests and all 58 existing tests.
- `lintDebug`: **0 errors, 8 warnings** (existing dependency/SDK update notices).
- `assembleDebug` and `assembleDebugAndroidTest`: **passed**. Four new recording
  UI tests compile in the instrumentation APK.
- `connectedDebugAndroidTest`: **blocked**, exact error:
  `com.android.builder.testing.api.DeviceException: No connected devices!`.
  `adb devices` also returned an empty device list. No new device test passed or
  physical recording is claimed.
- `git diff --check`: passed. Frozen acquisition, contracts, raw data and historical
  reports have no changes in this continuation. No commit/merge/push was performed.

The initial Kotlin daemon encountered a sandbox cache-access warning and Gradle
successfully used its fallback compiler. Subsequent verification used the quoted
in-process compiler property above. An unquoted PowerShell invocation split that
property and failed with `Task '.compiler.execution.strategy=in-process' not found`;
quoting it fixed the invocation. No JVM test failures remained. The existing
nullable-File warning in `StrictContractTest.kt` is outside this stage and unchanged.

Review also addressed storage access on the UI thread, a capture-state snapshot
race, existing-session overwrite on create failure, and recovery racing another
owner's finalization. These protections are in the final implementation.

Changed files:

- Added `app/src/main/java/com/intelligentdeadreckoning/app/recording/LocalRecorder.kt`.
- Added `app/src/main/java/com/intelligentdeadreckoning/app/recording/FileRecordingStorage.kt`.
- Added `app/src/main/java/com/intelligentdeadreckoning/app/recording/AndroidRecordingMetadata.kt`.
- Added `app/src/test/java/com/intelligentdeadreckoning/app/LocalRecorderTest.kt`.
- Added `app/src/androidTest/java/com/intelligentdeadreckoning/app/RecordingUiTest.kt`.
- Modified `app/src/main/java/com/intelligentdeadreckoning/app/SessionViewModel.kt`.
- Modified `app/src/main/java/com/intelligentdeadreckoning/app/MainActivity.kt`.
- Modified `app/src/main/java/com/intelligentdeadreckoning/app/ui/IdrApp.kt`.
- Modified `README.md`; added `RECORDING.md` (this document).

Verdict: implementation and host validation are complete; **NOT READY to freeze
Prompt 3B or advance through its device gate** until the connected suite and manual
recording acceptance above pass. The next action belongs to the developer with
the test phone: connect/authorize it and run the documented device checks.

## Connected-device follow-up — 2026-09-22

The OnePlus 12R (CPH2585, Android 16) was connected and authorized. The installed
app had no `no_backup` directory before these tests. Only test-generated recordings
were created during instrumentation; no pre-existing trip recordings were found.

The first connected run executed 12 tests: 9 passed and 3 recording tests timed
out. A focused rerun exposed `ComposeNotIdleException`: publishing recording
counters for every appended sensor record caused continuous UI recomposition.
An actual on-device UI inspection showed recording continuing with 5,835 written
records, zero recorder drops/write errors and zero ID gaps during that failing
test. This was a short diagnostic observation, not a sustained acceptance run.

Fixes confined to Prompt 3B integration:

- Sample the ViewModel's presentation flow every 200 ms. Recorder state/counters,
  acquisition stream, timestamps, units and disk writes remain full-rate.
- Gate recording Start on the sensor configuration becoming available. The first
  diagnostic session could otherwise contain an empty sensor-descriptor list
  because acquisition had only just started.
- Wait for the enabled recording control in the Android test before clicking it.

Final verification command, with the documented Java/SDK/cache environment and
the connected device selected:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug connectedDebugAndroidTest --no-daemon --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Result: **BUILD SUCCESSFUL**; **12/12 connected Android tests passed**, zero
failures/skips; **74 JVM tests passed**; lint remains **0 errors, 8 warnings**.
The four recording tests verified explicit Stop leaves acquisition running,
acquisition Stop finalizes recording, background/return does not restart it, and
source switching ends the recording. The eight existing Android tests also passed.

The test runner initially could not create `.android/utp/utpRunTemp...` through a
previously sandboxed Gradle daemon. A fresh approved `--no-daemon` invocation
resolved the runner access issue. After test-runner cleanup, the debug APK was
reinstalled separately with `adb install -r` (Success), and MainActivity launched
with `Status: ok`.

Files changed in this follow-up: `SessionViewModel.kt`, `ui/IdrApp.kt`,
`RecordingUiTest.kt`, and this document. Frozen Prompt 2 acquisition and Prompt 3A
contracts remain untouched. Nothing was committed, merged or pushed.

Remaining gate: the ten-minute real recording/memory/rate check, explicit process
interruption/recovery on the phone, and controlled on-device storage-failure checks
are **not yet verified**. GNSS recording with granted location permission is also
not established by these lifecycle tests. **NOT READY to freeze Prompt 3B** until
the remaining acceptance checks pass; the connected automated-test gate now passes.

## Sustained-test attempt and continuation — 2026-09-22

Precise location was granted through the phone permission dialog. Four sensors were
available. The first recording (`06f7e84a-cb4c-40a9-97f5-ba7b7d36c31a`) began at
approximately 07:28:51 IST and failed after 2,666 written records / 8.98 seconds of
valid data. The recorder reported three discarded records and one write error:
`DUPLICATE_OR_REORDERED_EVENT_ID`. Acquisition continued without drops or invalid
samples. A TIME_GAP diagnostic (ID 5970) preceded its triggering measurement (5969).
This contradicted the recorder's assumption, not the frozen acquisition contract.

The recorder was corrected to allow the producer's bounded diagnostic ordering
while preserving arrival order and bounded memory. Two new regression tests cover
the actual AcquisitionProcessor producing `0, 2, 3, 1`, and recovery preserving
that same order. **76 JVM tests now pass**, including 18 recorder tests, with no
failures/errors. `lintDebug` passed and the updated debug APK was built and installed.
The 12 connected tests passed before this ordering fix; they have not yet been
rerun against this final fix.

The failed session was retained on the phone. On reopening, recovery explicitly
reported one recovered/incomplete session, zero trimmed bytes and zero unrecoverable
sessions. A read-only Python streaming check using the existing strict contracts
validated all 2,666 records and metadata counts: 2,665 IMU records and one diagnostic,
four sensor descriptors. No location coordinates were printed. This verifies recovery
of that failed session, not a separate abrupt-process-death or partial-tail test.

A fresh recording (`f2608548-d1c8-4007-9e72-87045a094f47`) started at approximately
07:32:52 IST. Its last verified checkpoint, about 100 seconds after Start, showed:

- Recording active; 29,375 written; zero recorder drops/write errors/ID gaps.
- Acquisition: 32,809 accepted; zero drops/invalid; queue 0/256, high-water 13.
- GNSS quality labelled degraded (the acquisition quality is not calibrated).
- PSS 167,856 KiB / RSS 328,408 KiB, versus initial 159,731 / 319,004 KiB.
  Two samples are insufficient to establish a long-term memory trend.

On continuation at approximately 18:28 IST, `adb devices -l` listed no device.
There is no observed Stop/finalization or ten-minute result for this second session.
Its files were not deleted and should be inspected on reconnection before starting
another test or running installation/test cleanup. The final state, duration and
GNSS row counts remain unknown. **NOT READY**: reconnect the phone, inspect/finalize
the retained session, validate its files, and complete the outstanding device gates.

The local read-only inspection helper is
`mobile/artifacts/validate_phone_recording.py` (ignored acceptance artifact). It
streams JSONL through the Python strict codec and verifies metadata membership,
counts and end timestamps without retaining the full session in RAM or printing
coordinates. No export/replay product feature was added.

## Reconnected-session validation — 2026-09-22, evening

The retained session `f2608548-d1c8-4007-9e72-87045a094f47` was found already
**completed/stopped**, recovery `none`, with duration **1,660.211414991 seconds**
(27 minutes 40 seconds). No new session or installation was started before inspecting
it. Its JSONL file is 174,752,240 bytes. All **494,895 records** passed streaming
validation through the existing Python strict codec, including duplicate-ID checks,
session/source/version membership, timestamp origin, finalized end time, total count
and metadata channel-count agreement. Counts: 493,067 IMU, 1,744 GNSS, 84 diagnostics.

Measured rates over the saved record spans:

- Accelerometer: 164,029 samples, 98.8005 Hz.
- Gyroscope: 164,020 samples, 98.7957 Hz.
- Gravity: 82,008 samples, 49.3966 Hz.
- Magnetometer: 83,010 samples, 50.0000 Hz.
- GPS provider: 1,660 fixes, 1.0000 Hz.
- Network provider: 84 fixes, 0.0507 Hz.

These IMU rates are consistent with the earlier acquisition baseline. Maximum
observed IMU receipt delay was 56.74 ms; GPS 69.29 ms; network 1,459.20 ms.
The 84 diagnostics comprise 83 TIME_GAP events and one LATE_MEASUREMENT. This is
explicit diagnostic evidence, not a reason to alter timestamps or drop rows.
Bearing was null in all 1,744 GNSS rows; speed was null in 84 rows. Nulls validated
without conversion to zero. Four actual sensor descriptors were present.

The completed metadata establishes clean recorder finalization; the final runtime
drop/error counters were not observed before the app process ended. Earlier zero
counter snapshots should not be misrepresented as a final UI measurement. No GPS
position-accuracy or navigation claim is made from this stationary recording.

### Abrupt process interruption on the phone

A separate session `02edb616-0a6d-4cd1-82a9-aae0f55340b3` was started and the app
was terminated using `adb shell am force-stop`. Metadata was observed `open` both
before and immediately after termination. On reopening, the UI explicitly reported
one recovered/incomplete session, zero trimmed bytes, and zero unrecoverable sessions;
the app was Ready in simulation and did not automatically restart acquisition.

Python validated its **6,925 records**: 6,922 IMU, one network GNSS fix and two
diagnostics. Metadata is incomplete/interrupted/recovered with a 23.285843169-second
retained duration. This demonstrates genuine process-interruption recovery on the
OnePlus. No partial row happened in this run, so tail trimming remains separately
covered by deterministic host tests rather than claimed as physically observed.

### Device storage-failure and regression checks

Added `app/src/androidTest/java/com/intelligentdeadreckoning/app/RecordingStorageDeviceTest.kt`
with two disposable app-private cache fixtures. One makes the intended directory a
regular file, exercising an actual Android filesystem create failure and confirming
explicit failure without blocking the publisher. The other injects an ENOSPC-like
append IOException and verifies failed/recovery-required metadata on Android storage.
Only the unique test fixture directories are cleaned up. The phone was not filled
and real recordings were not modified by these tests.

The final installed implementation passed **14 Android instrumentation tests**:
the original eight, four recording UI tests and two storage-failure tests. **76 JVM
tests** passed on the host; the latest Android test APK build and lint passed.
Tests were run through direct instrumentation to avoid Gradle's uninstall cleanup
deleting the retained private recordings:

```powershell
& $adb -s $serial install -r mobile/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $serial shell am start -W -n com.intelligentdeadreckoning.app/.MainActivity
& $adb -s $serial shell am instrument -w com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner
```

Runner caveat: two initial expanded-suite runs had 13/14 passes, with the first
existing acquisition UI test reporting `No compose hierarchies found`. That test
passed individually, and the full suite passed **14/14** after explicitly launching
the app first. No frozen acquisition test or behavior was changed to hide the issue.
Cold-start test-runner reliability still needs investigation; the successful run's
launch precondition is recorded here rather than claimed as an unconditional pass.

Remaining limits: memory was only sampled near Start and at approximately 100
seconds, so the completed 27-minute file does not prove a flat long-duration memory
trend. Continuous memory observation and cold-start runner investigation remain
open. Physical disk exhaustion was not performed (failure was safely injected).
**Recording duration, schema/rate, recovery and controlled storage-failure gates
pass; NOT READY for an unconditional Prompt 3B freeze until the remaining observation
and test-runner caveat are resolved.** The main completed recording remains on the
phone; no export/replay feature, acquisition change or contract revision was added.

## Final monitored run and cold-start verification — 2026-09-22

This checkpoint supersedes the preceding conditional NOT READY verdict for Prompt
3B. It is scoped to the recording stage on the tested OnePlus, not navigation/AI
readiness or a production-release certification.

### Monitored recording

Session `c3db6fd7-bbcd-4b93-93cd-694f34d97108` started at approximately 18:41:11 IST.
A bounded host monitor sampled the visible counters and `dumpsys meminfo` every
30 seconds and issued Stop automatically after the final ten-minute sample.
There were **21 observations** (first at approximately 15 seconds, last at 603
seconds). App acquisition stayed in the foreground. No existing recording was
deleted or overwritten.

Final observed UI: **completed; 179,358 written; 0 dropped; 0 write errors;
0 ID gaps**. Acquisition continued after Stop recording, with zero reported drops
and invalid samples; it was then stopped before instrumentation tests.

Strict Python streaming validation of the saved file passed:

- Metadata duration: **603.757373467 seconds**, completed/stopped, recovery none.
- **179,358 records**: 179,298 IMU, 30 network-location fixes, 30 TIME_GAP diagnostics.
- Accelerometer and gyroscope: 59,645 each, **98.7919 Hz**.
- Magnetometer: 30,187, **50.0000 Hz**; gravity: 29,821, **49.3935 Hz**.
- Network fixes: **0.04974 Hz**; speed and bearing remained null in all 30 fixes.
- Four actual sensor descriptors; total/per-type counts, membership, unique IDs
  and timestamp bounds all agree with metadata.

GPS did not produce fixes during this monitored run. The UI alternated stale and
degraded based on network fixes. This is explicitly not a new GPS accuracy claim;
GPS recording at approximately 1 Hz was independently observed in the earlier
27-minute session.

Memory and queue observations:

- Recording-time PSS: **152,014–162,406 KiB** (approximately 148.5–158.6 MiB).
- First/last PSS: 153,201 / 162,406 KiB; a net increase of about 9 MiB, with several
  intermediate decreases rather than uninterrupted growth.
- Recording-time RSS: **323,872–334,368 KiB**.
- Queue depth was 0 at 20 observations and 2 at the first; high-water reached
  **53/256**. Every sampled acquisition drop/invalid counter and recorder
  drop/write-error/ID-gap counter was zero.
- A separate post-finalization observation measured PSS **177,078 KiB**, RSS
  **349,048 KiB**, while acquisition was still running. This rise is retained in
  the evidence; memory is not claimed to be perfectly flat or leak-free.

Interpretation: the ten-minute bounded-memory observation passes for this stage:
no session-sized RAM accumulation or queue growth was observed while records grew
to 179,358. Modest heap growth/fluctuation and the finalization rise warrant normal
longer-duration/device-coverage testing, not an unsupported assertion of zero
allocation or indefinite memory stability. Raw observations are in the local,
ignored `mobile/artifacts/memory-profile-c3db6fd7.json` acceptance artifact.

### Cold-start test race

`AcquisitionUiTest.kt` now waits up to ten seconds for exactly one
`source_simulation` control before exercising the screen. During that wait, an
empty Compose root registry is allowed; absence of the control at timeout still
fails. This resolves the observed launch/readiness race without changing the
frozen acquisition implementation or weakening any existing assertion.

After installing the updated test APK, the full suite was executed **three times**,
each immediately preceded by `am force-stop` and with **no warm app launch**:

```powershell
& $adb -s $serial shell am force-stop com.intelligentdeadreckoning.app
& $adb -s $serial shell am instrument -w com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner
```

Results: **14/14 passed** on each run (20.471 s, 20.768 s, 20.655 s), no failures.
The latest test APK build and lint passed; lint remains 0 errors / 8 pre-existing
warnings. The production code is unchanged from the **76-passing-JVM-test**
checkpoint. Only test setup, this documentation and the ignored memory evidence
artifact changed during this final validation turn. No commit/merge/push occurred.

**Verdict: READY for Prompt 3B review/freeze and the next separately requested
recording stage.** Foreground recording, long-session serialization/rates,
bounded-memory observation, lifecycle/source boundaries, process recovery and
controlled device storage failures now have evidence. Remaining limits include
other phones/Android releases, longer trips, true physical storage exhaustion
(safely injected instead), and physical partial-write timing (deterministic host
tests cover trimming/corruption). Export/replay implementation has not begun.
