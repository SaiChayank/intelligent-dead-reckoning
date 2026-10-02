# Real navigation drives the map — report 2026-10-01

Stage 13 of the Android product line. Scope: put the real `NavigationRuntime` behind
the existing Map screen as a fourth, never-blended source, without touching the three
existing sources and without letting the map estimate anything. Stop after real
navigation drives the map — no AI, no routing, no new estimator.

## Verdict

**Implemented and host-verified. Physical-device execution pending (no device was
attached).** The map's engine mode draws what the engine published, names it as such,
and draws nothing when the engine has published nothing. The only contract change is
the versioned `1.1.0` addition the architecture documents demanded, not a repurposed
field.

## What changed

| Area | Change |
|---|---|
| Contract 1.1.0 | `navigation.localization_mode` (`gnss`/`dr`/`fused`/`recovery`), required in 1.1.0, absent in 1.0.0; null exactly when no position is presented; `fused`/`recovery` require `gnss_used_after_initialization`. Python and Kotlin models/codecs, a new shared corpus `edge_records_1_1.jsonl`, 6 new invalid cases (47 → 53), `interop.py --corpus 1.0.0\|1.1.0`, both languages consuming the same fixtures |
| Engine seam | `NavigationRuntime.ENGINE_OUTPUT_CONTRACT_VERSION = "1.1.0"`; `start()` refuses any other version before a session exists, so an output stream that cannot represent its own results fails at the seam |
| Engine output | `FusionNavigationEngine` publishes `localization_mode`: `gnss` while the solution stands on an anchor, `dr` when no fix has been accepted inside the provider's stale bound, `recovery` for the first `localizationRecoveryFixes = 3` fixes after GNSS returns, `fused` otherwise; null exactly when it publishes no position |
| Map fold | New `map/EngineSessionMap.kt`: pairs `NavigationState` with the `Confidence` of the same `t_ns`, carries the mode, expires stale state, restarts on a new session, and (opt-in) adds the map-matched claim as a parallel output |
| Presentation | `MapPresentation` gained `localizationMode`, `matchedPoint`, `matchedTrail`, `matchConfidence`, `matchedEdgeId`, `matcherVersion` (appended; existing fields untouched). `NO_ENGINE_VIEW` added |
| Overlay / renderer | `MapOverlay` emits `matched` / `matched-trail` beside — never over — the raw position and trail; `MapLibreRenderer` gained two display layers; the `MapRenderer` interface is unchanged |
| Marker smoothing | New `map/MarkerAnimation.kt`: presentation-only easing between published positions, holding at the newest, drawing nothing when the published point is null |
| Screen | `OfflineMapScreen` gained `MapMode.ENGINE` (SYNTHETIC/RECORDED/LIVE preserved), an `EnginePanel` (position, heading, speed, trail, acquisition GNSS quality, runtime status, confidence radius, localization mode, evaluation toggle, camera controls, honest disclaimer), and the engine camera focus-once rule |
| Wiring | `SessionViewModel` now runs `FusionNavigationEngine` (10 Hz publications) with a 500 ms expiry ticker and `mapEvaluation`; `MainActivity` → `IdrApp` → `OfflineMapScreen` parameter chain |
| Road graph on device | New `matching/RoadGraphPack.kt` installs and verifies `assets/roadgraph/hyderabad-v1` in private storage on the I/O dispatcher |

## Verification evidence

Host, 2026-10-01, Windows/Git Bash, Android Studio JBR, `--offline`:

```
cd mobile && bash gradlew :app:testDebugUnitTest :app:lintDebug \
  --offline --console=plain -Pkotlin.compiler.execution.strategy=in-process
```

- **324 JVM tests, 0 failures, 0 errors** (302 before this stage), including the new
  `EngineSessionMapTest` (11) and `MarkerAnimationTest` (6), the 15 fusion-engine tests
  and the updated contract suite (`StrictContractTest`, 53 invalid cases).
- `lintDebug`: **0 errors, 5 warnings**, all pre-existing (target SDK, Gradle,
  dependency update notices) and none in the new code.
- `compileDebugAndroidTestKotlin` passes: the three new device tests compile, and the
  instrumented source now holds 41 test methods (26 were executed by the 2026-09-29 run;
  `DesignSystemUiTest`'s 12 and these 3 landed afterwards).
- Python: **256 tests passed, 1 environment-dependent skip** (`unittest discover`),
  including the two new codec tests.
- **Bidirectional contract interoperability actually executed** for both versions: Python
  exported 17 typed records for 1.0.0 and 7 for 1.1.0; Kotlin consumed the Python 1.1.0
  stream and wrote its own; Python verified the Kotlin output — "Python verified 7 Kotlin
  records; all typed values identical" (1.1.0) and 17 records (1.0.0).

Not run here: `connectedDebugAndroidTest`. `adb devices` reported no attached device,
so the engine view has **no device evidence** in this report.

## Findings

1. **The production engine's mode could not be represented in v1, so v1 was extended
   properly.** `status`, `gnss_used_after_initialization` and the synthetic fixture's
   scenario labels were all candidates for misuse; none is a localization regime. The
   architecture documents had already required a versioned revision with six
   prerequisites; this stage met them: explicit enum, patched Python and Kotlin codecs,
   a shared 1.1.0 corpus, invalid cases in both languages, an interop corpus switch, and
   a seam that refuses headers that cannot carry the field.
2. **The fold holds a state until its own confidence arrives.** A `NavigationState`
   is published when its same-`t_ns` `Confidence` arrives, or — if a later state
   arrives first — on its own without an accuracy. A device therefore shows the first
   position one publication interval (100 ms) late rather than showing it with an
   invented radius. Both paths are tested.
3. **A second engine session would have left the map stuck.** Found while testing the
   fold: the session-pinned presentation adapter rejected the new session's records
   forever, so stopping and restarting sensors would have frozen the view on
   `Session/source mismatch`. The fold now restarts on a header change, and the
   previous session's trail, held state and matched overlay are dropped rather than
   mixed in. Test: `aNewEngineSessionRestartsTheFoldWithoutMixingSessions`.
4. **Heading is honestly absent on a device today.** The fusion engine publishes
   `heading_deg` only from calibrated vehicle attitude, and no screen collects or
   composes a calibration record yet — so position, speed, localization mode and trail
   publish while heading reads `—`. This is documented in the panel, in
   [MAP_ENGINE_VIEW.md](../mobile/MAP_ENGINE_VIEW.md) and here rather than worked
   around by approximating course as heading.
5. **The engine's own GNSS quality is not displayed, because it does not exist.** The
   runtime's canonical output set includes `gnss_quality`, but the fusion engine never
   publishes one; the view shows the acquisition stream's quality state and labels it
   "acquisition GNSS quality". Fabricating an engine-quality record was rejected.
6. **Enabling the evaluation overlay costs a real install on the device.** Turning the
   toggle on extracts and SHA-256-verifies the 7.7 MB gzip road-graph asset on the I/O
   dispatcher; the device test exercises exactly that path. Matching itself never
   invents an accuracy: a published position without a paired calibrated confidence is
   left unmatched. *(Superseded the same day by the confidence stage: the gate now
   consumes whichever uncertainty the engine published — calibrated if it exists,
   otherwise the covariance published as `UNVALIDATED` — and still never the platform
   fix radius, so the overlay can fire on real engine output.
   See [mobile/CONFIDENCE.md](../mobile/CONFIDENCE.md).)*

## Limits (not claimed)

- No device run of engine mode with live output; the device tests are written and
  compiled but unexecuted here.
- No accuracy, turning or route claim about the fused output — it is a model output on
  synthetic evidence, unchanged from the fusion stage.
- The evaluation overlay is not navigation: the raw published position remains the
  navigation truth and the matched claim is drawn beside it only while the toggle is on.
- No calibration flow exists in any screen, so the vehicle-attitude packaging that
  would produce a heading is still open work.

## Next bounded actions

1. **Calibration composition in the app**: collect and compose a calibration record so
   the engine can publish heading — the named prerequisite for a heading on the map.
2. **Engine GNSS quality**: publish `GnssQualityState` from the engine (or state plainly
   that acquisition's quality is the product's quality) instead of labelling a gap.
3. **Device run**: execute `EngineMapDeviceTest` plus a real drive/replay through engine
   mode on the target phone and attach the pixel evidence to
   [mobile/MAP_DEVICE_VERIFICATION.md](../mobile/MAP_DEVICE_VERIFICATION.md).
4. **Route readiness**: offline routing is still absent; the road graph exists and is
   verified, so routing is the next component that needs its own stage and its own
   validation, not a quiet addition here.
