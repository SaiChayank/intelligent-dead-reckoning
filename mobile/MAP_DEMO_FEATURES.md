# Reference-inspired map presentation package

Scope approved by the user: presentation-only demonstrations, not new localization
algorithms. Original project visuals/code; no video artwork or third-party source
code copied. Existing acquisition, recording and v1 contracts remain unchanged.

## Reference evidence

All four auto-generated transcripts were read; relevant demonstration frames were
sampled. This is not independent verification of the creators' benchmark claims.

- [DRISHTI, 1:21–2:32](https://www.youtube.com/watch?v=w6l_-iElGDs&t=81s):
  baseline/comparison trace, denied-GNSS segment, uncertainty display and recovery.
  Adopted as explicitly scripted comparison and automatic blackout segment, not
  measured drift, filter covariance or an actual INS baseline. Its <10% target
  is not an achieved result for this project.
- [IntelliNav, 1:28–2:04](https://www.youtube.com/watch?v=ik0CZppdgvU&t=88s):
  manual blackout control, road-overlay toggle and speed/distance/outage HUD.
  Adopted only inside the demo; no actual GNSS fixes are suppressed. No invented
  noise index, pitch/roll telemetry, 3.8ms inference or <1% drift claim.
- [Asmodeus, 0:47–1:58](https://www.youtube.com/watch?v=z8vTUNuLFzQ&t=47s):
  comparison vehicles, selectable paths, playback speed and multiple views.
  Adopted three local synthetic paths and speed controls, comparison marker/trail
  and overlay toggles. No additional regional map downloads, proprietary basemap,
  satellite imagery or simulated road-following disguised as routing.
- [NAVZERO, 0:51–1:15](https://www.youtube.com/watch?v=t_2sAWrL0Mg&t=51s):
  narrated outage/recovery and on-device operation. No unique interactive map
  control was established in the sampled frames. The existing recovery scenario
  is retained; its new red comparison convergence is scripted, not an EKF.

## Use

Open Map. Start synthetic demo retains the existing 30-second sequence. Pause
freezes virtual time; Resume excludes time spent paused. Stop hides all position
overlays, while Reset also clears elapsed time and counters. Backgrounding stops
the demo; returning or activity recreation never resumes automatically.

Open **Demo options**:

- Select Left curve, Straight or Right curve while stopped/reset. These are
  mathematical curves around Ameerpet, not planned road routes.
- Choose 0.5×, 1× or 2× playback before or during playback. This changes display
  time, not the fixture's 10 m/s physical speed label.
- Auto uses a denied interval of 10–20 virtual seconds. Blackout forces a denied
  scenario; Recover forces an available scenario. These controls never reach
  acquisition, recorded data, replay or a navigation engine.
- Toggle comparison, travelled trail, uncertainty, scenario path and road overlay.
  Turning roads off hides road geometry/road names, not the map's other layers.
  Attribution remains visible.

Purple is the scripted reference marker/path. Red is a scripted drift illustration
that separates by 3 m per denied virtual second and converges by 6 m per available
virtual second. It is **not the repository's classical INS, AI or ground truth**.
The red heading is not estimated; it is a comparison point only. Dashed gray shows
the chosen scenario path. Amber marks only the automatic 10–20s segment, even
when a manual signal override is selected; it does not represent an actual tunnel.

The HUD shows virtual elapsed time, fixture speed/heading, total synthetic distance,
cumulative outage time/distance and current outage duration. Distances use the
fixture's known 10 m/s speed, not real sensor integration. The shaded radius remains
synthetic. No benchmark accuracy/drift percentage or ETA is presented.

## Implementation and constraints

- MapDemoController is UI-owned, pure Kotlin, with no Android, disk or sensor API.
  It feeds the existing typed navigation adapter using a separate virtual timeline;
  real Android elapsed-realtime timestamps are never multiplied or rewritten.
- Delayed ticks split at automatic outage boundaries before computing counters;
  playback rate changes first account for elapsed time at the previous rate.
- Both trails are bounded at 512 points. Scenario geometry is fixed at 61 points,
  outage geometry at 21. No full-session data accumulation or background service.
- Completion occurs at 30 virtual seconds and freezes final demo state. Reset/Stop
  removes it. A new Start clears traces and resets signal overrides to Auto.
- Replay, live acquisition and their clocks remain independent. New comparison
  GeoJSON features are emitted only for Source.SIMULATION, never real/replay sources.
- NavigationEngine and contracts remain untouched. Real fusion, map matching,
  guidance, routing, magnetic attitude and AI inference remain out of scope.

Files added: MapDemoController.kt, MapDemoControls.kt, MapDemoControllerTest.kt and
this guide. MapPresentation/SyntheticMapDemo, MapRenderer, MapLibreRenderer,
MapOverlay, OfflineMapScreen and OfflineMapDeviceTest are extended. Prior uncommitted
device-verification changes are preserved. No dependency changes or downloads.

## Verification commands

From `mobile/`, with documented JAVA_HOME, ANDROID_HOME and GRADLE_USER_HOME:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Physical test class is `com.intelligentdeadreckoning.app.OfflineMapDeviceTest`.
Use the direct `adb install -r` and `am instrument` commands documented in
MAP_DEVICE_VERIFICATION.md if the host Gradle UTP directory remains inaccessible.
Never clear application data to work around test-runner problems.
