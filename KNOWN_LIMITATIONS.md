# Known limitations — SIH local prototype

This file describes limitations of the current prototype and intentionally avoids turning
code presence, synthetic inputs, or dated subsystem checks into product claims.

## Navigation/scientific validity

- Normal app use does not provide a valid calibration into fusion; no aligned navigation
  position is available through the user journey. Do not present the engine map view as
  working dead reckoning.
- There is no current independent moving-drive dataset with reference truth and GNSS outage
  and recovery intervals. `experiments/` is empty. The app's checked-in evaluation is a
  deterministic host replay against generated/scripted truth, not field performance.
- IO-VNBD admission is no-go (0 approved sequences). Frame and synchronization issues remain
  unresolved. Do not train or claim supervised ML from it.
- No model, inference runtime, model latency, or model artifact hash exists.
- Fusion covariance/confidence is an engine model output and is not field-calibrated
  accuracy. The opt-in map matcher is a parallel overlay, not localization/routing.
- Offline map coverage is bounded to central Hyderabad. Its road graph is undirected,
  OSM-derived and matching-only; no directions, turn restrictions, routing costs, or
  whole-city coverage.

## Device and offline evidence

- Historical acquisition/map runs are dated, device-specific evidence; they do not qualify
  the complete current demo or current fusion accuracy.
- ADB/device access was not available for the final local package check. Connected tests,
  preserve-data update observation, current on-device permission verification, and a physical
  airplane-mode demo are pending. Instrumentation assembly is compile-only.
- ADB missing also prevents screen recording or checking airplane-mode behavior on a real
  phone. Any fallback capture must be identified as emulator/synthetic/rehearsal.
- Accessibility, multiple OEM/API versions, battery/thermal, sustained navigation rate,
  memory, live GNSS outage/recovery and safe vehicle acceptance remain incomplete.

## Build/security/distribution

- Direct dependencies and wrapper are pinned, but there is no complete transitive Android
  lock/SBOM or Android advisory scan. `pip-audit` was unavailable in this local environment.
- Debug/release build variants are not configured for distribution signing; no signing key
  was created. Release minification is disabled. Do not distribute as a production app.
- Root application licensing decision remains unresolved; map and OSM/ODbL notices apply to
  bundled assets and must be reviewed before redistribution.
- Dependency downloads may require Internet on the developer machine. The app itself removes
  network permissions and contains bundled offline assets; physical airplane-mode verification
  of this build remains a pending gate.
- Updating with a same-signature `adb install -r` should preserve private recordings;
  uninstall/clear-data removes them. No fresh device inventory was available in this run.

## Data and demo

- The tracked replay fixture has two synthetic diagnostic rows and no movement/sensors/GNSS.
  It demonstrates playback controls only. Synthetic map and scripted evaluation fixtures are
  not user trips or field truth.
- The checked-in model manifest intentionally has no artifacts/hash entries; empty is not a
  clean model-verification pass.
- The ground-truth package manifest is blocked/empty; it must not be represented as a completed
  field evaluation package.
- Python `requirements.txt` pins direct research dependencies only; transitive Python versions
  are resolved by pip, and the Python stack is not necessary for the Android UI demo.
- App language is English, theme is fixed dark, and broad device/accessibility matrix checks
  are not complete.

## Intentionally excluded

No cloud services, public hosting, domains, user accounts, analytics, telemetry, online map
fallback, model download/update, routing, or background navigation are included. Broader
architecture extraction and unrelated UI/product work are deferred. Production readiness
requires calibration integration, approved independent field data, evaluation and device
evidence, dependency/security/legal review, and authorized signing.
