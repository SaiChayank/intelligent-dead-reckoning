# Offline map device acceptance — 2026-09-25

Scope: optional offline Hyderabad renderer and **synthetic** navigation-state
presentation. This is not acceptance of a real navigation engine, GNSS recovery
algorithm, route planner or map matching.

Device: OnePlus CPH2585 (12R), Android 16, USB-authorized. Updated the app and test
APK with `adb install -r`; no uninstall, app-data clearing, permission grants,
recording starts, network upload or raw-dataset operations.

## Findings and changes

- Actual framebuffer screenshots showed streets, place labels and water, not
  just a successful style load. Synthetic heading, trail and uncertainty rendered.
- Attribution below a scrollable map could be off-screen. Fixed by adding an
  attribution Surface inside the map bounds; the device test asserts visibility.
- The original fictional origin was on Hussain Sagar lake. Moved only the
  synthetic fixture to 17.435 N, 78.445 E, a built-up area. It remains an arbitrary
  curve, not a vehicle recording or road-matched route. Tests use the new origin.
- Added camera assertions for horizontal pan, zoom and recenter, explicit
  lifecycle/recreation checks and screenshots at three synthetic scenario stages.

Files changed: NavigationPresentation.kt, OfflineMapScreen.kt,
NavigationPresentationTest.kt, OfflineMapDeviceTest.kt, OFFLINE_MAP.md and this
report. Frozen acquisition/recorder/contracts and navigation algorithms unchanged.

## Verified results

- Final direct device run: **OK (4 tests), 36.554 seconds**. Earlier three-test
  smoke run and first four-test visual run also passed.
- Pack install/checksum validation and idempotent reuse passed; SQLite contains
  247 tiles; INTERNET permission is denied to the installed application.
- Style loads across tab switches and background/resume. Synthetic Start/Stop,
  no automatic restart and recreation passed. Camera longitude changed after
  horizontal swipe, zoom increased and recenter returned to Hyderabad center.
- Screenshot inspection confirmed roads, labels and buildings, heading/marker,
  travelled trail, scenario labels and in-map attribution. The DR-stage screenshot
  shows the larger synthetic uncertainty area. No genuine GNSS-loss claim.
- Host suite: **116 tests, 0 failures/errors/skips**; both APK builds succeeded;
  lint: **0 errors, 8 warnings**. The new instrumentation test compiled successfully.
- **All 88 pre-existing recording files remain present with identical SHA-256
  hashes** after installation and device tests. No app-data reset or recording
  recovery operation was performed by this task.

**READY: offline rendering + synthetic map presentation accepted on this phone.**
This completes the bounded map demo stage, not the full live navigation feature.
Human pinch/rotation review and production stress/fault testing remain below.

## Commands

Host, from `mobile/`, with the documented JDK/SDK/Gradle-user-home setup:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Gradle `connectedDebugAndroidTest` failed before running tests because its host UTP
temporary directory under `C:\Users\Saich\.android\utp` was inaccessible. A retry
with elevated tool permissions had the same result. This host-runner issue was
not repaired by changing global settings; direct Android instrumentation bypassed it.

Equivalent direct execution from repository root (`adb` from SDK platform-tools):

```powershell
adb -s 5c7d81bb install -r mobile/app/build/outputs/apk/debug/app-debug.apk
adb -s 5c7d81bb install -r mobile/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 5c7d81bb shell am instrument -w -r -e class com.intelligentdeadreckoning.app.OfflineMapDeviceTest com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumentation test writes actual screen captures only to the target app's
private cache `cache/map-verification/`. Selected screenshots are copied locally
to ignored `mobile/artifacts/map-validation-20260925/`; they are not committed or
uploaded. They depict synthetic positions, not captured location measurements.

## Limits and remaining work

- The app has no INTERNET permission, verified on device; all rendering resources
  are local. Wi-Fi/mobile radios were not toggled and airplane mode was not tested.
- Panning and camera buttons are tested; two-finger pinch/rotation still need human
  ergonomic review. No battery, thermal, sustained frame-rate or long-trip claim.
- Do not inject disk-full/corrupt-pack faults into this user's existing install.
  Those destructive fault scenarios remain for a disposable test install.
- No real positioning, live DR, offline routing/re-routing, planned route, road
  matching or turn-by-turn guidance was added or verified. The frozen v1 contract
  still lacks a current GNSS/DR/fused mode field. It needs explicit review before
  any such field is introduced. NavigationEngine remains an interface only.
  *Superseded 2026-10-01: contract 1.1.0 adds `navigation.localization_mode`, the
  fusion engine publishes it, and the map has a fourth source that draws it. The
  device run for that view is still pending — see the last section of this file.*
- Do not treat this acceptance as permission to start AI or EKF implementation.

## Attribution clipping fix — 2026-09-29

Same device (CPH2585, Android 16, API 36). This run uninstalled the previous
install, backed its 44 app-private recordings off the phone first, and installed
the current debug build with `adb install -r -g`.

Finding: `OfflineMapDeviceTest.syntheticScenarioVisualEvidenceAndRecreation`
failed on `map_attribution` being *not displayed*. The node existed, but its
clipped bounds were empty (`Rect(0,0,0,0)`) and the attribution string was absent
from the accessibility tree entirely.

Root cause: the hero was sized as 70% of the **window** height, while a page only
gets the window minus the header, the dock and its own vertical padding. On this
display the hero measured 1,946 px against a 1,884 px scroll viewport, so the
bottom of the floating chrome — the demo console and the OpenStreetMap
attribution — fell past the viewport edge and was clipped away. Attribution was
positioned at y=2,521 px with the viewport ending at y=2,512 px.

Fix: the page measures its own area (`BoxWithConstraints` around the page scroll
container in `IdrApp.kt`) and the hero takes the smaller of the window proportion
and that measured area. The floating chrome now sizes the hero when the console
needs more room than the minimum, instead of being clipped by its own background
box. The attribution is therefore always inside the visible page.

Evidence: the full instrumented suite passed on the device, **24 tests, 0
failures** (previously 23 of 24), together with 136 host unit tests and
`lintDebug` with 0 source warnings. The attribution assertion is the regression
test for this fix: it checks licence-visible attribution, not cosmetics.

Device framebuffer captures from this run are in ignored
`mobile/artifacts/device-shots/`; they show the synthetic dashboard only.

## Recorded-session mode — 2026-09-29

The map previously had one data source: a scripted 30 s synthetic fixture with
a fixed origin. It now has two, selected by `map_source_synthetic` /
`map_source_recorded`, with the synthetic demo still the default so every
pre-existing tag, string and test is untouched.

Declared semantics of the added mode: `RecordedSessionMap` folds real recorded
GNSS fixes into display state and draws them as recorded. It applies no
propagation, dead reckoning, sensor fusion, road matching, routing or inference
of a GNSS/DR/fused mode. A missing fix is left missing — the trail is split at
every gap rather than bridged, because a straight line between two fixes twenty
seconds apart is movement nobody observed. A fix outside the bundled tiles is
counted and never drawn. The platform's reported fix radius is carried as
`fixRadiusMetres` and is never relabelled as a calibrated 95% confidence, which
remains `accuracy95Metres` and stays empty for recorded data.

`MapOverlay` still emits the synthetic comparison, scenario and outage layers
only for `Source.SIMULATION`, so replayed recordings (which arrive as
`REPLAY_REAL`) physically cannot paint a comparison or DR line. A host test
asserts that.

Evidence:

- Host: 8 new tests in `RecordedSessionMapTest` (gap splitting, course over
ground fallback, coverage rejection, malformed coordinates, bounded history,
and the no-synthetic-layers assertion); 144 host tests total, 0 failures.
- Device: the full instrumented suite still passes, 24 tests, 0 failures.
- Device, real data: one real recording (`02edb616`, 2.4 MB) was pushed into the
  app's private recordings directory byte-identically and selected in recorded
  mode. The screen reported `1 fixes · 0 drawn · 0 gaps · 1 outside coverage`,
  switched `map_source` to `MAP SOURCE: RECORDED SESSION — real GNSS fixes, no
  fusion or DR`, hid the synthetic console and kept the attribution. That result
  is correct: the corpus's fixes are ~5.6 km west of the bundled pack, as
  recorded in `reports/recording_corpus_2026_09_29.md`.
- Not verified: drawing a real multi-fix trail on the device, because no session
  in the current corpus has two fixes inside the covered area. The segment-drawing
  path is covered by host tests only. **Partly superseded the same day** — the
  section below drew seven real in-coverage fixes on the device, but not a line:
  every fix in that recording is at the same position, so it is seven isolated
  points. A *multi-point* recorded trail is still host-tested only.

## Recorded-session mode on real in-coverage data — 2026-09-29, later run

The section above could not put a real fix on the map, because none of the
recorded fixes were inside the bundled tiles. This run could, because the phone
was somewhere else: a fresh 139 s recording made in the app
(`f50068f4-f977-4090-9add-e109efa75a69`, 41,304 records, 14.6 MB, `source: real`,
`calibration: not_applied`, 0 dropped, 0 write errors, 0 ID gaps) reported its
GNSS fixes at 17.5206881° N, 78.365531° E — inside the pack
(17.30–17.55° N, 78.35–78.60° E). The mismatch in
`reports/recording_corpus_2026_09_29.md` is therefore a property of where the
2026-09-22 recording was made, not an inability of this screen to draw real data.
Building a westward pack was not needed and was not done.

Three defects were found and fixed while verifying it.

### 1. Isolated fixes were counted and then discarded

`RecordedSessionMap` documents "a single drawn fix is a position, not a path; it
is shown as a point rather than a line", but it dropped one-point segments and
`MapOverlay` emitted a `LineString` only for segments of two or more points. A
lone fix therefore reached the statistics and never reached the map. The
recording above is exactly that case — 0.05 Hz network fixes about 20 s apart, so
all seven are isolated — and the panel claimed "7 drawn" while emitting no trail
geometry at all.

Fix: one-point segments are kept and emitted as `Point` features (`kind:
trail-fix`), painted by a new `display-trail-fix` circle layer with the trail's
colour. Joining them into a line would invent movement nobody observed, so they
stay points. `Stats` now reports `lines` (multi-point segments plotted) and
`points` (isolated fixes plotted), computed from the geometry actually retained
rather than from the fixes accepted, in place of the single misleading `drawn`
count. The panel line now reads `7 fixes · 0 segments · 7 isolated · 6 gaps`.

### 2. Recorded mode never framed the camera on the recording

Presenting was correct, but nothing moved the camera, and recorded mode also hid
the synthetic console that carried the only camera controls. A recording whose
fixes are not already in the default view therefore drew nothing visible: the map
looked empty while the panel reported seven loaded fixes. This fix sits near the
pack's western edge, outside the viewport that the pack's camera bounds force at
the default zoom, so it hit the case exactly.

Verified with a temporary probe that logged the camera position and
`queryRenderedFeatures`: after loading, the camera was still at Hyderabad centre
while the fix projected to a screen point outside the visible area; the nine
features (7 `trail-fix`, `accuracy`, `position`) were in the style and renderable,
just off-screen.

Fixes: `MapRenderer.frame(points)` fits the camera to the recorded fixes — a
single position is centred at the focus zoom rather than asking for an unbounded
zoom on a degenerate box — the screen calls it whenever a recording is loaded,
and the recorded panel now carries `Fit recording`, `Zoom +` and `Zoom −` so the
camera is reachable in recorded mode as it is in the synthetic demo.

### 3. The bottom dock sat inside the system gesture band

This device declares `InsetsSource type=mandatorySystemGestures
frame=[0,2685][1264,2780]`, while the dock's tab labels sat at y 2665–2693 and its
tap targets reached y 2732. Tapping a tab label was therefore unreliable: a plain
tap was swallowed and a slightly longer touch was read as the home gesture, which
backgrounded the app mid-verification. Real touches failed where the Compose test
API — which injects clicks directly into the hierarchy — always succeeded, which
is why the instrumented suite never caught it.

Fix: `IdrDock` insets itself by the union of `navigationBars` and
`mandatorySystemGestures` on the bottom side. The dock's tabs moved from
y 2560–2732 to 2465–2637, clear of the band, and a tap on the bottom edge of the
MAP tab now switches screens and leaves the app focused.

### Session read latency, measured

Recorded mode states `Reading saved session…` while it streams and validates a
session. Measured on this device for the 14.6 MB / 41,304-record session: 5–12 s
across runs, after which the fixes paint with no further interaction — polled at
1 s intervals, the purple fix marker appears in the same sample that the panel
flips to the loaded session. Nothing about the drawing is deferred: the wait is
`ReplayReader` validating every record before anything is displayed. An honest
progress state is the right treatment of that wait; making the read cheaper is
future work.

Evidence: **145 host tests, 0 failures** (9 in `RecordedSessionMapTest`,
including a regression test for the discarded isolated fixes), **24 device
tests, 0 failures**, `lintDebug` clean, and pixel checks of the device
framebuffer showing that the synthetic fixture and the recorded session each draw
their own overlay geometry and that the recorded marker sits at the framed fix.
The fresh session, its validation report and the screen captures are kept locally
under ignored `mobile/artifacts/`.

## Recorded-mode overlay dead end — 2026-09-29, after the commit

The phone detached mid-run (`adb devices` empty, `adb usb` reporting no devices)
and was reattached before the work finished, so this change is both host-tested
and checked on the device itself; the device evidence is the last subsection here.

### A switch the recorded screen cannot reach could blank a recording

The overlay switches (`overlay_trail`, `overlay_roads`, `overlay_uncertainty` and
the two synthetic-only ones) are rendered by the synthetic console, which recorded
mode hides (`if (showControls && !recorded)`). They were nevertheless the only
writes to the `overlays` state that `present()` receives, and that state survives a
source switch — it is one `remember { mutableStateOf(DemoOverlays()) }` shared by
both modes. Switching off `trail` in the synthetic demo and then opening
`Recorded session` therefore drew no trail at all while the panel still reported
the fixes it had loaded; switching off `roads` hid the basemap the same way.
Nothing on the recorded screen could switch either back on, so the screen was a
dead end. This is the same "panel reports fixes, map draws nothing" symptom as
defects 1 and 2 above, caused by stale state instead of by geometry.

Fix: `DemoOverlays.forRecordedSession()` resolves the switches for recorded mode,
keeping the three layers a recording depends on (`trail`, `uncertainty` for the
radius its last fix reported, and `roads`) on, and leaving the synthetic-only
layers alone because `MapOverlay` already refuses to emit them for replayed data.
Both call sites that present a recording — the `LaunchedEffect` and the `ON_STOP`
lifecycle observer — now go through that resolution.

Host evidence: two tests added to `RecordedSessionMapTest`, one on the
resolution itself and one end-to-end showing that the abandoned switches render no
`trail` or `accuracy` geometry while the resolved switches render both.
**145 → 147 host tests, 0 failures**; `lintDebug` 0 errors, 5 warnings, all five
pre-existing dependency/target-version notices.

Device verification. The old APK was still installed, so the bug was reproduced on
the screen first and then re-run against the fixed build — same device, same
session, same sequence: expand `Options` in synthetic mode, switch overlays off,
switch to `Recorded session`, wait for the load, capture the framebuffer.

| build | switches left off in the console | road-colour px | purple px |
| --- | --- | --- | --- |
| old | Trail | 11372 | 935 |
| old | Trail + Road overlay | **4** | 935 |
| fixed | Trail + Uncertainty + Road overlay | **11372** | 935 |

Road pixels are exact matches of the style's `roads` colour `#fffdf4` within the map
area. The old build lost the basemap roads entirely while the console switch was
off (11372 → 4 px); the fixed build draws them regardless (11372 px), and its
recorded map matches the old build's switch-on map exactly on every mask measured —
road colour, purple overlay, the blue-ish tint, and the accuracy-fill blend. The
accuracy polygon for the recording's reported 100 m radius is likewise present with
`Uncertainty` switched off: 119 accuracy-fill px, identical to the switch-on
capture. Instrumented suite on the fixed build: `OfflineMapDeviceTest`
**OK (5 tests), 41.213 s** — no regression.

## Live phone GNSS on the map — 2026-09-29, later still

The map had two sources and neither was the phone's own position: a scripted
fixture and a saved recording. A third mode, `map_source_live`, now draws the live
acquisition stream. It is the same fold a recording gets — `RecordedSessionMap`,
which validates finiteness, refuses fixes outside the bundled pack, splits the trail
at every gap and drops repeats — with the presented source made explicit rather than
hardcoded, so live fixes are labelled `REAL` and replayed ones stay `REPLAY_REAL`.
`SessionViewModel.liveGnss` folds `real.events`, the same record stream the recorder
subscribes to, so the trail is built from the real stream and not sampled from a UI
snapshot.

The synthetic console belongs to the fixture, so live mode hides it exactly as
recorded mode does, and both resolve the overlay switches through
`forConsoleHidden()`: a switch the user can no longer reach must not blank real data.
The camera takes the first live fix it sees and is then left alone — following a
track is an engine's job, not this screen's.

One honesty change: `map_mode` now reads "Live GNSS only · no dead reckoning, fusion
or routing yet" in live mode, because live mode makes the old "no live position"
string false. The other two modes keep that string, so the existing device assertion
on it stays true and unchanged.

Evidence:

- Host: **149 tests, 0 failures** (147 + 2), including that a live stream is
  presented as `REAL` and can never be presented as a replay, and that live fixes
  cannot draw the synthetic comparison or scenario layers. `lintDebug`: 0 errors,
  5 warnings, all pre-existing dependency notices.
- Device: new `liveModeNamesItsRealSourceAndCannotClaimNavigation`, asserting the
  `LIVE PHONE GNSS` and "no dead reckoning" labels, that the scripted console is
  absent in live mode, and that returning to the fixture restores both.
  `OfflineMapDeviceTest` **OK (6 tests)**.
- Device, real data: live mode with sensors running produced real fixes with real
  satellites and fix age — `GNSS quality degraded · fix age 0.1 s · 20 satellites`,
  then `stale · fix age 12.9 s` as the provider slowed, over 7 fixes in 120 s. Every
  fix came from the network provider near 78.30° E, outside the pack's 78.35° E
  edge, so the panel reported `7 outside coverage` and the map drew nothing (0 purple
  pixels). The coverage refusal works on live data as it does on a recording.
- **Not verified on device: an in-coverage live fix drawing its marker and moving
  the camera.** Indoors the phone delivered only network fixes ~4 km west of the
  covered area, and no GPS fix arrived in 90 s of polling. The GPS provider's own
  last-known location (78.37° E, 3.8 m accuracy, 40 satellites) is inside the pack,
  so this is fix availability, not coverage. The drawing path is the same one
  recorded mode already put 935 purple pixels through on this device; only the
  stream feeding it is new. Re-check outdoors or near a window.

Honest limit: the **`trail` half of this fix is still host-verified only**. Every
GNSS fix in the one recording on the phone is at the same coordinate (17.5206881 N,
78.365531 E), so its seven `trail-fix` dots cannot be told apart from the
`display-position` marker, which is emitted unconditionally and painted on top of
them — the purple pixel count is 935 in all three captures whatever the switch
says. Seeing that half on screen needs a recording with two distinct in-coverage
fixes, and no session in the corpus has one.

### Camera framing on a degenerate box: investigated and cleared

`frame()` centres a recording whose fixes all sit at one position rather than
building a box from it. That raised the question of whether a recording that is a
straight north-south line — zero east-west extent — hands
`CameraUpdateFactory.newLatLngBounds` an unbounded zoom request. It does not, and
no change was made. The fit takes the *smaller* of the two axis zooms, so a
zero-span axis only asks for more zoom than the other axis needs and therefore
loses that comparison; only a box degenerate on **both** axes is unbounded, which
is the case the guard already covers.

The zoom arithmetic itself is native — `MapLibreMap.getCameraForLatLngBounds`
delegates into MapLibre's C++ fit — so it is not reachable from a host test. The
Java side was read out of the 13.6.1 AAR: `LatLngBounds.Builder.include` appends
and `build` sweeps min/max, so the box handed to the camera is the intended one.

## GNSS loss timeline — 2026-09-29, last run

The fold already splits the trail at every gap and counts the gaps, but it never
said *when*. `RecordedSessionMap` now retains those same breaks as `Outage`
intervals as well as counting them, and `outageMarks()` places them over the
observed window. The bar is lime where a fix exists and amber where none does,
with the current loss reported as a number (`currentOutageSeconds`) rather than
drawn as an interval that has not closed.

One derivation feeds both the drawn trail and the timeline, so the two cannot
disagree about when the fixes stopped. The interval begins when the previous fix
went **stale** — the same 5 s threshold the acquisition contract uses — and not
when the next one happened to arrive, which would have shortened every loss by
that threshold.

### The window is the observer's, and that is what makes it testable

A bar is drawn from the window rather than from the losses. An unbroken lime bar
therefore means a fix was present for the whole window, and **no bar at all** means
there is no window to draw over yet — never a window whose history is unknown.
Three sources share the one placement rule, and each supplies its own window:

| source | window | end of window |
| --- | --- | --- |
| recorded session | first to last real fix | a fix |
| live phone GNSS | first to last real fix | a fix |
| scripted demo | its own elapsed clock | the present tick |

That last row is why the demo now draws the timeline too. A device test cannot
produce a real GNSS gap on demand, and recorded mode chooses the session with the
most records, so no fixture could be seeded deterministically past the phone's own
14.6 MB session. The fixture's blackout, by contrast, is scripted and repeatable,
so the feature is now covered by a device **test** rather than only by measured
pixels. The demo's bar is labelled as scripted in the panel, and it draws its
own scripted loss, never phone data.

### Measured on the device (1264×2780, the bar is x 144–1119 = 976 px)

- **Live, real**: `2 fixes · 1 gaps · span 20s`. One loss, `[first+5 s, second]`
  over 20 s, so the boundary belongs at 144 + 0.25×976 = **388**. Measured lime to
  x 385 with amber from 387 (the pill rounding and antialiasing move a boundary by
  a pixel or two; verified at the same row on every capture).
- **Recorded, real**: `7 fixes · 6 gaps · longest 20s · span 120s`. Six amber blocks
  of **121 px** each (0.125 × 976 = 15 s of 120 s), the first starting **41 px** in
  (5 s / 120 s, the stale threshold), repeating every **162 px** (20 s / 120 s).
- **Scripted, frozen**: at `COMPLETED` the demo clock stops at 30 s, so the
  automatic 10–20 s blackout must land on 1/3 and 2/3 — **469.3 and 794.7**. Measured
  boundaries at **469.5 and 794**, with the HUD independently reporting
  `outage total 10.0s`. This is the same rule and the same composable as the two rows
  above; only the window differs.
- **Absence**: no bar at demo `IDLE` (window zero), and none in live mode before the
  first fix. Both checked by pixel and not only by the semantics tree.

### Evidence

- Host: **154 tests, 0 failures** (152 + 2): the placement rule over a window with no
  fixes behind it, clamping a loss that reaches past the window, dropping one that
  would occupy no width, and the scripted losses being retained, closed at the
  instant the signal returned and cleared by reset. `lintDebug`: 0 errors, 5
  warnings, all pre-existing dependency notices.
- Device: `OfflineMapDeviceTest` **OK (7 tests)**; the suite is 26 tests, 0 failures.
  The new `scriptedLossTimelineNeedsAWindowAndShowsTheLossItCovers` drives the
  fixture and asserts the bar appears once it has a window and is gone after reset.
- The refactor that removed the "draw only if there are losses" guard was checked
  against the pre-refactor capture for recorded mode: the six amber blocks are at
  the identical pixel positions.

Honest limit: `recorded_timeline` and `live_timeline` are pixel-verified on real data
but still have no device **test**, for the two reasons above. The placement they rely
on is the same function the scripted test exercises on the device and the host suite
exercises directly.

## Navigation engine mode — gate written 2026-10-01, run pending

No device was attached when the engine view landed, so this section is the procedure and
the expected results, not evidence. The host side (fold, presentation, marker easing,
contract 1.1.0, lint) is green; see
[MAP_ENGINE_VIEW.md](MAP_ENGINE_VIEW.md) and
[reports/map_engine_integration_2026_10_01.md](../reports/map_engine_integration_2026_10_01.md).

The suite gained `EngineMapDeviceTest` (3 tests). The current instrumented source has
**41 test methods**: the 26 the 2026-09-29 run executed, the 12 in `DesignSystemUiTest`
(added with the design-system commit) and these 3. `assembleDebugAndroidTest` proves they
compile; `connectedDebugAndroidTest` must run them:

1. `engineModeNamesItsOwnSourceAndShowsNothingItWasNotGiven` — the fourth chip selects the
   engine source, the source and mode chips say NAVIGATION ENGINE / "Navigation engine
   output", the fixture and live consoles are absent, every value reads `—` or "not
   published" until the engine publishes, and the camera chips are disabled because no
   position exists. Captures `engine-no-output.png`. The confidence line reads "not
   published" until a paired `Confidence` record exists; once one does, it is labelled with
   its state — `95%, CALIBRATED`, or `filter covariance, UNVALIDATED — not a calibrated
   accuracy` with the speed sigma, and the unvalidated radius is the dashed ring rather
   than the filled one ([CONFIDENCE.md](CONFIDENCE.md)). That labelling has no device run
   yet either; it is pinned on the host by `NavigationPresentationTest` and
   `EngineSessionMapTest`.
2. `evaluationOverlayIsAnExplicitOptInThatChangesNoSourceLabel` — the toggle starts as
   "Map matching off", turning it on installs and SHA-256-verifies the packaged road graph
   on the device, the panel says the overlay is on, and the source label does not change;
   turning it off restores the statement that the drawn position is what the engine
   published.
3. `engineModeSurvivesBackgroundingWithoutStartingAnything` — backgrounding and returning
   keeps the engine source, still draws nothing, and starts neither the fixture nor the
   live stream.

Manual run on the target phone (OnePlus CPH2585 / Android 16):

```bash
cd mobile && bash gradlew connectedDebugAndroidTest --offline --console=plain
# Install separately AFTER the test runner has finished:
bash gradlew installDebug --console=plain
```

Expected: **41 tests, 0 failures**; `engine-no-output.png` in the app's private test cache.
Then, on the same install:

4. Start Sensors, open Map, select **Navigation engine**. Expected: the position appears
   only after the engine publishes; no fixture or live marker appears; heading reads `—`
   (no calibration record exists yet — this is the honest state, not a defect).
5. Turn **Map matching on**. Expected: a dashed comparison-coloured claim appears beside
   the raw position and trail; the raw purple claim does not move or disappear; the note
   names the raw position as navigation truth.
6. Stop Sensors while the engine view is open. Expected: the position, trail and matched
   overlay leave the screen within three seconds and nothing is extrapolated or held.
7. Leave and re-enter Map, background/foreground, and start a second sensor session.
   Expected: the engine view follows the new session (no "Session/source mismatch" stuck
   state) and never starts a session by itself.
