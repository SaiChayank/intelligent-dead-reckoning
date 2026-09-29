# Continuous integration

Three GitHub Actions jobs run on every push to `main`, every pull request and
manual dispatch ([.github/workflows/ci.yml](.github/workflows/ci.yml)). CI needs
network access only to fetch dependencies; the app under test never uses the
network. No Docker, no hosted services, no emulator.

| Job | Gates (all verified locally before landing) |
|---|---|
| `python` | Python 3.12, `pip install -r requirements.txt` + `pip check`, contract tests (28), Python session-reader tests (34 + 8), full suite (140 tests, 1 environment-dependent skip), AST compile of 42 files across `training/ tests/ contracts/ tools/`, import checks |
| `android` | JDK 25 (matches the verified Android Studio JBR), Android SDK platform 37 + build-tools 36.0.0, `bash gradlew testDebugUnitTest` (154 JVM tests incl. Kotlin/Python session-reader parity), `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` |
| `quality` | `git diff --check` over the pushed range (with `cr-at-eol` for the repo's CRLF files), `tools/check_repo_hygiene.py` (forbidden tracked files, secret-pattern scan, map-manifest and contract-fixture validation) |

Notes on deliberate choices:

- `assembleDebugAndroidTest` **compiles** the device suite; nothing runs on a
  device or emulator in CI.
- The whitespace gate is scoped to the pushed range on purpose: historical
  evidence files and shipped map licences contain deliberate trailing
  whitespace and must never be reformatted to satisfy a linter.
- `tools/check_repo_hygiene.py` validates the map manifest's existence, sizes
  and parseability; byte-exact SHA-256 verification stays in `OfflineMapTest`
  (JVM suite) so the check exists in exactly one place.
- `gradlew` is tracked without its executable bit, so CI invokes it as
  `bash gradlew`; the wrapper is pinned by SHA-256 in
  `gradle-wrapper.properties`.
- The Android SDK step installs `platforms;android-37` (and warms build-tools
  36.0.0) through `sdkmanager`'s full path — it is not on `PATH` on hosted
  runners — and degrades to a notice when `sdkmanager` is absent, because AGP
  auto-downloads whatever the build needs. The step can never be the reason CI
  goes red; the Gradle build itself is the gate.

## CI gates required before merging

1. `python` green — contracts and both session readers agree on the fixtures.
2. `android` green — JVM suite, lint (0 errors), debug APK and instrumented APK
   all build.
3. `quality` green — no whitespace errors in the change, no forbidden tracked
   files, no secret patterns, artifacts parse.
4. No `FINDING:` line from the hygiene gate (it exits 1 on any finding).

A change that touches contracts, the recording format or replay semantics must
also keep `PythonParityTest` green — it is inside the `android` JVM gate, so a
green `android` job covers it.

## What CI cannot verify

- **Anything physical.** Sensor rates, real GNSS fixes, real outages, camera
  framing on a screen, gesture ergonomics, sunlight contrast, memory behaviour
  over a 30-minute run.
- **MapLibre rendering.** CI builds and tests the JVM layer; no tiles are ever
  drawn. Pixel-level evidence lives in
  [mobile/MAP_DEVICE_VERIFICATION.md](mobile/MAP_DEVICE_VERIFICATION.md).
- **Real recordings.** The 44-session corpus findings
  ([reports/recording_corpus_2026_09_29.md](reports/recording_corpus_2026_09_29.md))
  come from a phone, not CI. CI sees only synthetic fixtures.
- **Export/import round-trips on a device**, permission flows, and behaviour
  under real background/foreground transitions.
- **Performance and endurance** (bounded queues under load, no monotonic memory
  growth) — measured by the manual acceptance procedure, not CI.
- **The instrumented suite.** `assembleDebugAndroidTest` proves it compiles;
  running the 26 device tests requires the manual gate below.

## Manual physical-device gates (OnePlus CPH2585 / Android 16)

These are acceptance gates, run by a human on the target phone; they are
**not** replaced by CI. Keep the phone, not an emulator: the evidence in
`mobile/MAP_DEVICE_VERIFICATION.md` and `reports/` is device-specific.

1. **Instrumented suite** — `bash gradlew connectedDebugAndroidTest` with one
   authorized device (`ANDROID_SERIAL` if several). Expected: 26 tests,
   0 failures. Note: the runner may uninstall the app afterwards; reinstall
   with `installDebug` and re-seed any session you need.
2. **Acquisition acceptance** — the 30-minute stationary endurance procedure in
   [mobile/ACQUISITION.md](mobile/ACQUISITION.md): queue stays bounded, memory
   stabilizes, measured rates match the manifest.
3. **Map verification** — the pixel-measured procedure in
   [mobile/MAP_DEVICE_VERIFICATION.md](mobile/MAP_DEVICE_VERIFICATION.md):
   synthetic fixture, recorded session and live GNSS sources, camera controls,
   loss timeline geometry, coverage refusal.
4. **Recording/export/replay** — record, recover an interrupted session, export
   a local ZIP, replay it; validate the pulled bytes with
   `python tools/validate_phone_recording.py --local <session-dir>` (expected
   exit 0). The 2026-09-29 corpus run accepted 44/44 real sessions.
5. **Never test the UI while driving.**

## Status badges

Deliberately **not** added yet: badges must only claim what is proven, and the
first hosted run of these workflows has not been observed. After one green run
on GitHub, add:

```markdown
[![CI](https://github.com/SaiChayank/intelligent-dead-reckoning/actions/workflows/ci.yml/badge.svg)](https://github.com/SaiChayank/intelligent-dead-reckoning/actions/workflows/ci.yml)
```
