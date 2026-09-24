# Optional offline map — Stages 1 and 2

This is an enhancement, not a change to SIH requirements or the navigation
architecture. The Map tab is a renderer-only preview with an explicitly started
synthetic position demo. It does not subscribe to acquisition, replay or a running
NavigationEngine. The global source banner describes acquisition, not the map;
the map has a separate SYNTHETIC UI FIXTURE label. No navigation is running.

## Stage 2: typed presentation adapter and synthetic demo

`NavigationPresentation` consumes existing v1 typed navigation records without
changing the contract. It validates them through the strict codec and converts
the WGS84 origin plus ENU metres via ECEF back to latitude/longitude. This is a
display coordinate conversion, not INS propagation. Heading remains clockwise
from north; horizontal speed is the norm of east/north velocity in m/s. Long
nanosecond timestamps remain integers, including values above 2^53.

The contract has **no explicit GNSS/DR/fused mode**. Neither status nor the
historical `gnss_used_after_initialization` flag proves a current positioning
mode. They are not reinterpreted. The three 10-second scenario labels belong
only to `SyntheticMapDemo`. A real mode badge needs a separately approved contract
extension before integration; frozen v1 is unchanged. NavigationEngine remains
unimplemented.

On Map, tap **Start synthetic demo**. The camera centers on a 30-second synthetic
curve at zoom 16. Purple position, heading triangle, travelled trail and a shaded
synthetic uncertainty circle are local GeoJSON overlays. The 20 Hz display target
skips delayed ticks; it does not create a queue, predict positions or smooth real
engine outputs. Scenario boundaries use a continuous mathematical fixture, not
GNSS recovery fusion. The curve is not a road route or a map-matched trajectory.

Stop, leaving Map, recreation and backgrounding terminate the demo. Returning
requires Start again. Nothing is recorded, exported, fed to acquisition or used
as a training sample. Real/simulated acquisition can operate independently.

Presentation rules:

- Each adapter is bound to one exact session/source/version and initialization
  mode. A caller needs a new adapter when switching sessions or clock domains.
- Invalid, missing, future, duplicate/out-of-order and out-of-coverage positions
  hide overlays with an explicit diagnostic. Position age over 3 seconds is
  stale (a display policy, not a scientific GNSS threshold).
- A caller must call `snapshot(nowNs)` to expire a stopped live stream. The demo
  clears overlays on stop. No extrapolation is performed.
- The trail retains at most 512 distinct samples; rejected records or gaps over
  3 seconds break it. No full trip is held in memory.
- Missing heading/speed remain absent. A 95% radius is shown only for calibrated
  confidence with matching session/source and exact navigation timestamp; v1
  does not provide a stronger association. No 68%-to-95% conversion is invented.
- Radius geometry uses a mean-Earth-radius spherical display approximation.
  Radii over 10 km are omitted geometrically, not silently clamped; their numeric
  values remain available to the UI. This is not an accuracy estimator.

Stage 2 adds NavigationPresentation.kt, MapOverlay.kt and
NavigationPresentationTest.kt; updates MapRenderer.kt, MapLibreRenderer.kt,
OfflineMapScreen.kt, OfflineMapDeviceTest.kt and these documentation files.
Acquisition, recording, contracts, algorithms and bundled map assets are unchanged.

Stage 2 host verification (2026-09-24): **116 JVM tests passed, 0 failures,
0 errors, 0 skipped**, including 16 new presentation tests. `testDebugUnitTest
lintDebug assembleDebug assembleDebugAndroidTest` succeeded offline. Lint: 0 errors,
8 existing update advisories. `git diff --check` passed (line-ending notices only).
The read-only pack verifier still passes. No build failures were encountered;
review tightened stale-gap trail breaks and exact polygon closure before final
verification. The new Android demo start/stop/background test compiles but was
not run: no phone or configured emulator is available. **READY for emulator/device
acceptance; NOT READY to claim visually verified navigation integration.**

## Implemented

- MapLibre Native Android OpenGL 13.6.1, embedded through Compose AndroidView.
- Original local vector style, roads, water, buildings and English/Latin labels.
- Pan/zoom/rotation, recenter and north-up controls; lifecycle/memory forwarding.
- Replaceable MapRenderer camera interface, separate from typed navigation contracts.
- Bounded central Hyderabad map, bundled in APK; no downloads at runtime.
- Checksummed streaming installation on Dispatchers.IO into app-private
  `noBackupFilesDir/offline-maps/hyderabad-v1`. Each resource uses an atomic
  temporary-file replacement. The renderer is exposed only after all resources
  validate; this is not a whole-directory transaction. Storage errors are visible.
- Internet, network-state and Wi-Fi-state permissions inherited from the renderer
  are removed. No API key, cloud service, analytics or network fallback.

Frozen acquisition, recording, export and replay implementations are unchanged
by this stage. Raw datasets and historical reports are outside this change set.

## Coverage and provenance

Bounds: west 78.35, south 17.30, east 78.60, north 17.55 (WGS84 degrees).
This is **central Hyderabad, not the whole city**. 247 vector tiles at zooms 10–14;
display zooms above 14 only magnify existing detail. Camera centers are constrained
to the bounds; the viewport can still expose areas outside coverage.

Bundled MBTiles plus four glyph files: **22,906,375 bytes** (about 21.85 MiB).
Installation requires another private copy; allow extra space for APK, native SDK
and temporary replacement files. No routing graph or elevation/indoor map is included.
The glyph subset covers codepoints 0–1023. Full Telugu coverage is not claimed.

OpenFreeMap snapshot `20260913_164504_pt`, OpenMapTiles schema, OSM-derived data.
`assets/offline/hyderabad/manifest.json` records exact resource sizes and SHA-256.
MBTiles SHA-256:
`abc4b19e3bd667b81f12083b536dd7f391d042f2b0694cff1304c5ba35418ec3`.
Tiles are not scraped from the public OSM raster tile server.

Attribution is always displayed outside the map. Pack NOTICE.md links ODbL data
terms; bundled MapLibre/OpenMapTiles notices and Noto OFL preserve library/font
licensing. Before distributing modified data, review ODbL obligations. Provider
access is free today, not a perpetual availability guarantee. The checked-in pack
needs no provider access. See https://openfreemap.org/ and
https://www.openstreetmap.org/copyright .

## Reproduction and host verification

From repository root (Python standard library only):

```powershell
python mobile/tools/build_hyderabad_pack.py
python mobile/tools/verify_hyderabad_pack.py
```

The first command is a dry run. Explicit `--download` generates a pack only when
the database/manifest do not already exist, and needs Internet on the developer
computer. It never overwrites this pack. For intentional refresh, create a new
version in a separate checkout, preserve the checked-in license/NOTICE files,
review new checksums and update tests deliberately. Snapshot URLs may eventually
expire; existing assets are the reproducible build input. Font service responses
are not version-pinned: retain the manifest-verified bundled bytes.

From `mobile/`, using Android Studio JBR and the existing SDK:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\Saich\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME="$PWD\.gradle-user-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Replace machine-specific JDK/SDK paths as needed. The first dependency download
requires Internet on the development computer (omit `--offline` then).

Host verification on 2026-09-24: **100 JVM tests passed, zero failures/errors/skips**;
both APK builds pass; lint has **0 errors and 8 warnings** (SDK/dependency update
advisories). The universal debug APK is approximately 73.2 MB, including native
renderer libraries and assets. `zipalign -c -P 16 4` passes; this alone does not
prove native execution on a 16 KB page-size device. Pack verification passes all five resource hashes, SQLite integrity,
247 expected tile coordinates, vector protobuf structure/layers and glyph data.
It counted 146,029 feature occurrences across zoom levels, not unique objects.
These checks do not establish that the GPU actually renders the map.

## Required device/emulator gate — NOT YET VERIFIED

No phone was connected for this stage; no emulator AVD/system image was available.
OfflineMapDeviceTest compiles but has not been executed. Do not describe Stage 1
as device-verified. On a connected phone/emulator, run:

```powershell
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

1. Install without clearing app data or recordings. Open Map without starting sensors.
2. Disable connectivity. Verify roads, buildings, water and readable labels render
   around Hyderabad, not just the background or a successful style-loaded message.
3. Pan, pinch, rotate, zoom and use all camera buttons; check scrolling does not
   capture map gestures. Verify attribution and no live-position/navigation claims.
   Start the synthetic demo; verify marker, heading, trail, radius and scenario
   labels at 10 and 20 seconds. Stop removes overlays. No position is your location.
4. Leave/re-enter Map, background/foreground, rotate/recreate, and relaunch. Verify
   no crash, stale camera controls or acquisition restart; existing recordings remain.
5. Check missing/corrupt pack and insufficient private storage on a disposable test
   install. Failure must be visible, without network fallback or acquisition failure.
6. Measure APK/storage, frame rate, memory and battery on the target OnePlus 12R.

## Next bounded stages

First pass the rendering/lifecycle gate, including the synthetic overlay checks.
The tested presentation adapter is now present; real engine integration is not.
Do not invent DR or fused
positions while the navigation engine is unimplemented. Raw GNSS, if separately
enabled, must be labelled raw GNSS, not fused navigation.

Map rendering draws data; positioning estimates pose; map matching constrains
estimates to roads; routing finds a path; turn-by-turn navigation follows that path.
Only rendering is implemented here. Offline routing will need a separate local
road graph and routing engine; MBTiles are not a routing graph. GNSS-loss motion
and recovery must eventually come from the reviewed navigation pipeline, not this
renderer. Later display interpolation must not hide uncertainty or alter engine
state. No AI, fusion, calibration, map matching or propagation was added.
