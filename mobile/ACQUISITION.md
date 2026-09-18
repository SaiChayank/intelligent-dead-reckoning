# Foreground phone acquisition

Select **Phone sensors** and **Start sensors** in IDR Demo. Dashboard and
Diagnostics show real sensor values; About and the source banner always identify
the selected source. Select **Simulation** to return to the existing scripted
demo. Switching source stops both sources; Start is required to begin again.

## Hardware and permissions

Android 8 / API 26 or later is supported by the build. A physical phone with an
accelerometer and gyroscope is needed for useful motion capture; magnetometer,
gravity and GNSS availability vary by device. The manifest marks hardware as
optional so missing hardware can be reported as unavailable, not fabricated zeros.
Rotation-vector acquisition is deferred: it needs a separately versioned event
definition and is not squeezed into the v1 three-axis IMU event.

Tap **Allow location** to request fine and coarse permissions together. Precise
access enables GPS-provider fixes and satellite status. Approximate access uses
the network provider if the phone offers it; its accuracy/rate can be poor and
satellite counts remain unavailable. Denied/not-requested location still allows
IMU acquisition. **App settings** is available after denial or to change precision.
Enable the phone's Location setting as well. Returning from Settings requires
Start again because acquisition stops when the activity stops.

The app checks permissions on resume and every 500 ms during acquisition, and
catches registration SecurityException races. It removes old location callbacks,
clears previous location snapshots and discards queued fixes from the preceding
permission epoch. A newly registered listener captures that epoch, preventing
old callbacks from becoming new measurements after a precision downgrade.
Revocation is identified using permission history in memory/SavedStateHandle.
If Android completely discards saved state on process restart, it starts fresh
without asserting that it knows the previous grant history; it never auto-resumes.

Fine/coarse location are the only user-facing runtime permissions. The selected
IMU rates do not require a high-rate sensor permission. There is no background
location, foreground service, notification, internet or storage permission. No
trip data, coordinates or raw samples are written to files or logged/uploaded.

## Timing, units and ordering

Raw accelerometer/gravity use m/s², gyro uses rad/s, magnetometer uses µT. Axes
are Android natural-orientation device X-right/Y-top/Z-out, unaffected by screen
rotation. Readings are not mounted, calibrated, gravity-subtracted or integrated.
Existing IO-VNBD exports are never treated as this raw device stream.

Both SensorEvent.timestamp and Location.elapsedRealtimeNanos use Android's
monotonic elapsed-realtime clock, including sleep. The callback captures a separate
SystemClock.elapsedRealtimeNanos receipt time. Raw Long values are retained;
contract serialization keeps exact decimal Int64 strings. Session start is an
explicit integer origin; subtract integers before converting a duration to Double
seconds. GNSS UTC milliseconds are nullable metadata and never replace monotonic
time. Each Start generates a new UUID session ID. Reboots/process restart create
new sessions; no attempt is made to stitch clocks across them.

- Accelerometer/gyro request 100 Hz; magnetometer/gravity request 50 Hz with no
  requested batching. GNSS/network request at most nominal 1 Hz through provider
  minTime=1000 ms. These are requests, not device guarantees.
- Rates use intervals between up to 256 recent increasing measurement timestamps
  per channel. They are unavailable until two distinct times exist, and display
  zero when an IMU channel is stale for >1 s or a location channel for >5 s.
- GNSS `hasSpeed`, `hasBearing`, `hasAltitude`, `hasAccuracy` and
  `hasVerticalAccuracy` determine presence. Missing/nonfinite/invalid optional
  values stay null. A real zero remains zero. Altitude is the Location ellipsoidal
  field, not an MSL conversion. Latitude/longitude still pass strict validation.
- GPS and network remain separate provider-labelled channels. GNSS satellite
  status has its own receipt time; counts expire after 5 s and are attached only
  to GPS fixes. Counts are the latest available status, not an exact atomic fix
  association. Null means unavailable, whereas zero means a measured zero count.
- Arrival order is retained in the output stream. Independent sensors can have
  identical times. Duplicate sensor/provider timestamps within a bounded history
  are rejected with DUPLICATE_EVENT. Old timestamps outside the retained history
  cannot re-enter: they are rejected as late.
- A receipt delay >100 ms emits LATE_MEASUREMENT but preserves an otherwise valid
  measurement. Per-channel reordering within 100 ms is retained in arrival order
  without regressing the latest displayed value; older out-of-order samples are
  dropped explicitly. There is no sorting, resampling or invented common clock.
- Negative/future timestamps and pre-session cached fixes are rejected with
  INVALID_MEASUREMENT. No wall-clock or row-index fallback is used. Sensor gaps
  >100 ms and GNSS gaps >5 s emit TIME_GAP; these thresholds are diagnostic policy,
  not a validated navigation error model.

## Ownership and bounds

`SessionViewModel` owns the source coordinator and Android adapter. The activity's
onStop stops both sources, including during recreation. Android callbacks run on
a dedicated HandlerThread and copy reusable SensorEvent values immediately.
They offer to a 256-item nonblocking inbox; overflow drops the newest item.
Validation/codec checks and statistics run on Dispatchers.Default. UI receives
at most approximately five immutable StateFlow snapshots per second. There is
no growing trip history or work performed by sensor callbacks on the UI thread.

The hot SharedFlow of typed real-source records has replay=0 and 256 extra slots.
It is exposed as SessionViewModel.measurements for future consumers. Without
subscribers it has no retention guarantee. A slow subscriber causes tryEmit to
fail; output loss is counted along with inbox drops and reported as QUEUE_OVERFLOW
in the diagnostics snapshot. This flow is not a recorder. StateFlow conflation
only skips UI snapshots and is not counted as lost sensor input.

Each channel retains at most 256 timestamps for duplicate/rate checks; diagnostic
history retains 16 events (the screen displays the last eight). There are four
sensor and at most two location channels. Hardware/queue sizes, accepted/delayed/
duplicate/invalid/drop counters, sensor accuracy and provider uncertainty are
visible. GNSS remains acquiring/unavailable/denied/stale/degraded; no unvalidated
fix is promoted to a navigation-ready "good" state.

Stop immediately gates offers/publication, discards queued input with an explicit
SESSION_STOPPED count, then unregisters listeners on their owner HandlerThread
and quits it. The processor exits and publishes a final stopped snapshot. A new
session cannot receive old-run callbacks. Last displayed samples may be retained
for inspection while stopped; they are cleared on Start. No automatic restart,
background service, navigation, map, recording or calibration is implemented.

## Validation and device acceptance

Host tests exercise permission states, missing/refused sensors, timestamp precision,
nullable GNSS, ordering, duplicates, queue bounds, stale states, source switching
and lifecycle ownership. Android tests additionally cover real-mode Start/Stop,
background/recreation and Android Location presence flags. Compilation is not
proof of hardware operation. Current evidence is in
`../reports/android_acquisition_2026_09_16.md`.

Use the build commands in README.md. For this workstation JAVA_HOME is
`C:\Program Files\Android\Android Studio1\jbr` and the SDK is
`C:\Users\Saich\AppData\Local\Android\Sdk`. If the Kotlin daemon cannot write
its user-profile directory, append the quoted PowerShell argument
`'-Pkotlin.compiler.execution.strategy=in-process'` to compile in Gradle's process.

Apply the acquisition portions of foundation_readiness.md's device procedure:

1. Connect/unlock the phone, enable USB debugging and authorize this computer.
   Run `adb devices -l`; it must show the intended device as `device`. Set
   ANDROID_SERIAL if more than one device is listed.
2. Run `gradlew.bat connectedDebugAndroidTest --offline --console=plain`, then
   `gradlew.bat installDebug --offline --console=plain`. Start IDR Demo with
   `adb shell am start -n com.intelligentdeadreckoning.app/.MainActivity`.
3. Start simulation, switch to Phone sensors, verify simulation has stopped, then
   Start sensors. Deny location; verify real IMU values still change and GNSS is
   denied. Grant approximate access; verify network-labelled fixes if available
   and no GNSS satellite claim. Upgrade to precise; verify GPS provider/status.
   Revoke access in App settings, return, verify stopped/revoked (or fresh denied
   after OS state loss) and explicit Start. Never auto-resume a real session.
4. While stationary, compare six phone faces for at least 10 seconds each and
   rotate ±90° about each physical axis three times. Check each sensor's name,
   vendor, units, accuracy and independent timestamps. This is live inspection,
   not a saved calibration capture or certification of the IO-VNBD exporter.
5. Outdoors, inspect GPS fixes, measured rates, uncertainty and satellite counts.
   Disable Location or block reception: availability/staleness must change and
   old fixes must not keep appearing healthy. Missing speed/bearing is verified
   separately by the Android Location fixture; do not depend on the phone naturally
   omitting those fields. Check receipt>=event and distinct per-sensor times.
6. Start/Stop, rotate/recreate the activity, press Home, return and switch source
   repeatedly. Returning must stay stopped. Inspect `adb shell dumpsys sensorservice`
   and `adb shell dumpsys location` for this package before/after stop, verifying
   its registrations disappear. Do not copy unrelated apps' location diagnostics
   into project evidence.
7. Run a >=30-minute stationary foreground session. Inspect counters, bounded
   queue peak and `adb shell dumpsys meminfo com.intelligentdeadreckoning.app` at
   intervals. Observe memory stabilizing rather than growth with duration. Record
   measured rates and outcomes in a new evidence report; don't infer these from
   nominal rates or host tests. Queue-overflow/late/duplicate injections are
   covered deterministically by unit fixtures.

The original procedure's recording/export/replay step is deferred to Prompt 3,
because this task explicitly excludes recording. No session export/load command
is advertised here. Do not operate the phone while driving.

## Android API references

- [SensorEvent timestamps and axes](https://developer.android.com/reference/android/hardware/SensorEvent)
- [Location elapsed time and optional fields](https://developer.android.com/reference/android/location/Location)
- [Precise/approximate runtime permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
- [LocationManager subscriptions and GNSS status](https://developer.android.com/reference/android/location/LocationManager)
