# Navigation engine view on the map — Stage 3

**Status: implemented, host-verified; physical-device run pending (no device was
attached on 2026-10-01).** The map now has a fourth source, and it is the only one
that is navigation: the positions, heading, speed and trail the on-device navigation
engine published. The other three sources — synthetic fixture, recorded session, live
raw GNSS — are unchanged and still never blended with it.

What this stage does **not** claim: that the engine's output is accurate (it is a
model output, see [FUSION.md](FUSION.md)), that a device has ever drawn it, or that
the map estimates anything itself. The screen reads no sensor and runs no filter.

## The pipeline

```
NavigationEngine → NavigationState → NavigationPresentation → MapOverlay → MapLibreRenderer
```

| Layer | Owns | Must never |
|---|---|---|
| `fusion/FusionNavigationEngine` | Publishing canonical `NavigationState` / `Confidence` / `DiagnosticEvent`; the filter runs at sensor rate and publications are capped at 10 Hz for display (`ENGINE_PUBLICATION_INTERVAL_NS = 100 ms`) | Publication rate is not a filter rate; it is the rate the map is asked to redraw |
| `navigation/NavigationRuntime` | The session seam: exactly one engine session, explicit start/stop/reset, bounded output flow, counters. `start()` refuses any header whose `contract_version` is not `ENGINE_OUTPUT_CONTRACT_VERSION` (`1.1.0`) before the session is created | Restarting an engine session on its own; forwarding a payload outside the canonical set; routing a record whose session/source does not match |
| `map/EngineSessionMap` | Folding engine output: pairs each `NavigationState` with the `Confidence` of the same `t_ns`, carries `localization_mode`, and — only when the evaluation toggle supplies a road graph — adds the map-matched claim as a **parallel** output | Inventing an accuracy for an unpaired state; letting matching move the raw position or trail; mixing two engine sessions into one trail |
| `map/NavigationPresentation` | The single validation point: exact session/source/version/mode identity, no future/duplicate/out-of-order timestamps, 3 s staleness (`STALE_NS`), bounded Hyderabad coverage, confidence only from a paired record with the same session and exact `t_ns` and then split by state — `CALIBRATED` into the calibrated field, `UNVALIDATED` into its own | Accepting a record from another session, a future timestamp or an out-of-coverage position; holding a stale position on screen; merging the two confidence states into one number |
| `map/MapOverlay` | GeoJSON features. Raw `position` / `trail` are written only from published state; `matched` / `matched-trail` are separate features | Overwriting the raw claim with the matched one |
| `map/MapLibreRenderer` | Layers and `present(state, overlays)` | Being a second localization engine; the renderer contract is unchanged by this stage |

## What the view displays, and where each value comes from

| Display | Source | When absent |
|---|---|---|
| Current position | `NavigationState.origin_wgs84_deg_m` + `position_enu_m` through `MapCoordinates.fromEnu` | `Position —`; nothing is drawn |
| Heading | `NavigationState.heading_deg` | `—`. The engine publishes no heading before a calibration record supplies the vehicle attitude, and **no calibration flow exists in any screen yet**; position, speed and localization mode publish without it |
| Speed | `hypot(velocity_enu_m_s.x, .y)` | `—` |
| Travelled trail | Accepted positions, bounded at 512, cleared on a >3 s gap between accepted fixes | Empty trail, no line |
| GNSS quality | The acquisition stream's own `GnssQualityState` (`capture.quality`), labelled **acquisition** GNSS quality | `unavailable` / no fix age. The engine publishes no quality record yet, and the screen does not fabricate one |
| Navigation status | `NavigationRuntimeState`: engine status (the engine's own `status` wire), session phase, message, accepted/rejected counters | It always has a value; before any session it reads `uninitialized` + "No navigation session. Explicit start required." |
| Confidence radius | `Confidence.horizontal_accuracy_95_m` for the same `t_ns`, split by *state*: a `CALIBRATED` record fills `accuracy95Metres`, an `UNVALIDATED` one fills `unvalidatedAccuracy95Metres` — never the same field, and never the platform's `fixRadiusMetres` | The engine's line reads `filter covariance, UNVALIDATED — not a calibrated accuracy` when only the covariance exists, states `paired confidence state UNAVAILABLE` when that is what the engine published, and `not published` when no record was paired. See [CONFIDENCE.md](CONFIDENCE.md) |
| Confidence radius on the map | `unvalidatedAccuracy95Metres` draws the dashed `kind = "uncertainty"` outline; a calibrated or platform radius draws the filled `kind = "accuracy"` ring; the confidence state travels in `confidenceState` | No circle is drawn for a position with no radius |
| Speed sigma | `Confidence.speed_std_m_s`, from the same paired record and only alongside a radius | `—` |
| Localization mode | Contract 1.1.0 `NavigationState.localization_mode` | `—`. The mode is null exactly when no position is presented |
| Raw vs map-matched position | Evaluation toggle only; see below | The toggle is off by default and the note says the drawn position is exactly what the engine published |

## The four map sources

`MapMode` is `SYNTHETIC`, `RECORDED`, `LIVE`, `ENGINE` (chips `map_source_synthetic`,
`map_source_recorded`, `map_source_live`, `map_source_engine`). Selecting a source
stops the scripted demo, and each source draws only its own data:

- `SYNTHETIC` — the UI fixture (`SyntheticMapDemo`), independent of acquisition.
- `RECORDED` — one saved session, raw fixes exactly as recorded.
- `LIVE` — the phone's raw fixes exactly as the platform reported them.
- `ENGINE` — published navigation output. The engine session follows the phone's
  measured stream (or a replay session); when nothing is running the view says so
  instead of drawing the fixture or the live stream.

The map never accesses raw sensors to estimate navigation. The only acquisition
reads in this view are the quality *state* it is allowed to display and whether
acquisition is running (to offer the start controls).

## Staleness, stop and lifecycle

- **Stale state expires.** `NavigationPresentation.STALE_NS` is 3 s, and the
  ViewModel's engine-map flow ticks every 500 ms, so a stopped stream leaves the
  screen within three seconds. An expired state takes its position, its trail and its
  map-matched overlay with it.
- **No extrapolation after engine stop.** The drawn marker interpolates only inside
  the span two published positions already bracket; when the published point becomes
  null, the marker stops with it. `MarkerAnimation` never moves past the newest
  published point and can never produce a position the engine did not publish.
- **Smooth marker animation is presentation-only.** Only the drawn point is eased
  (`EngineSessionMap` state is untouched). The panel values, the trail and the status
  always show published values.
- **No background auto-restart.** Leaving the app stops the engine session through
  the ViewModel's existing lifecycle path; returning requires an explicit start. The
  engine is never rebound to a failed session, and the screen starts nothing by itself.
- **A new engine session restarts the fold.** The runtime forwards only records it has
  bound to its own session; when the header changes (a new measurement session, or a
  replay session) the fold restarts rather than rejecting the new session, and nothing
  from the previous session — trail, held state, matched overlay — is carried across.

## Evaluation overlay: raw versus map-matched

The map-matched claim is an **evaluation** output, not navigation:

- it is opt-in (`map_evaluation`, "Map matching off" → "Map matching on"); enabling it
  installs and SHA-256-verifies the bundled road-graph package
  (`assets/roadgraph/hyderabad-v1`, 7.7 MB gzip) on the I/O dispatcher;
- it matches only positions the engine published, and only with the 95% radius the
  engine published beside them: an unpaired state, or a radius above the matcher's
  50 m gate, is left unmatched rather than matched with an invented accuracy;
- the raw position and trail are read, never written: matching adds `matched` /
  `matched-trail` features beside them, and the note states that the raw position is
  still the navigation truth;
- the renderer's data contract is unchanged — two new GeoJSON kinds (`matched`,
  `matched-trail`) that only ever exist while the toggle is on.

## Why contract 1.1.0, and not a renamed field

The engine needs to say which regime is holding the position up (GNSS anchor, inertial
DR, fused solution, post-outage recovery). The architecture documents had already
decided this needs a versioned revision rather than repurposing `status` or
`gnss_used_after_initialization`
([architecture §9](../docs/PS26168_Navigation_Output_and_Evidence_Architecture.md)):

- `NavigationState.localization_mode`: `gnss` | `dr` | `fused` | `recovery`, required in
  1.1.0, absent by definition in 1.0.0;
- null exactly when no position is presented; `fused` and `recovery` require
  `gnss_used_after_initialization = true`;
- a 1.0.0 envelope cannot carry it: encoding a typed record with a non-null mode under a
  1.0.0 header is `INVALID_MODEL`, and the Kotlin/Python codecs drop the key for 1.0.0
  streams (decoding them to null) so frozen 1.0.0 readers keep working;
- `NavigationRuntime` only creates an engine session for a `1.1.0` header, so an engine
  output stream that cannot represent its own results is refused at the seam.

## Verification

Host (2026-10-01, Windows/Git Bash, Android Studio JBR):

```bash
cd mobile && bash gradlew testDebugUnitTest lintDebug --offline --console=plain
```

- **324 JVM tests, 0 failures** (baseline 302), including `EngineSessionMapTest`
  (11 tests: state/confidence pairing, an unpaired state published without an accuracy,
  localization mode carriage, the version boundary, staleness expiry of the matched
  overlay, evaluation matching beside the raw position, refusal on a 60 m radius,
  no-graph, invalid record, session restart, reset) and `MarkerAnimationTest`
  (6 tests: immediate first publish, interpolation, never past the target, continuity
  across publications, nothing drawn without a published point, reset).
- `lintDebug`: **0 errors, 5 pre-existing warnings**.

Physical device (OnePlus CPH2585 / Android 16 gate): `EngineMapDeviceTest` adds three
instrumented tests — the engine view names its own source and draws nothing it was not
given; the evaluation overlay is an explicit opt-in that changes no source label; engine
mode survives backgrounding without starting anything. They compile
(`assembleDebugAndroidTest`); running them needs the phone. The procedure is in
[MAP_DEVICE_VERIFICATION.md](MAP_DEVICE_VERIFICATION.md).

Not yet verified, and not claimed: a device run with live engine output; a heading value
(a calibration composition flow does not exist yet, so a device shows `—`); engine-
published GNSS quality (the engine emits no `gnss_quality` record yet, so the view shows
the acquisition stream's state and labels it as acquisition); any accuracy or
turning-level claim about the fused output.

## Reproduce

```bash
# Kotlin: fold, marker easing, presentation and the full JVM gate
cd mobile && bash gradlew testDebugUnitTest lintDebug --offline --console=plain
# contract 1.1.0 corpus (Python and Kotlin concede the same invalid set)
.venv/Scripts/python.exe -B -X utf8 contracts/v1/interop.py --corpus 1.1.0
```

See also [MAP_MATCHING.md](MAP_MATCHING.md) for the road graph and matcher itself and
[FUSION.md](FUSION.md) for the engine that publishes the states this view draws.
