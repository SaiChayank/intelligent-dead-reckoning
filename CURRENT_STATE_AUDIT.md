# Current state audit — 2026-09-29

Production-readiness audit of the Intelligent Dead Reckoning repository
(SIH #26168), performed before any further implementation. Documentation-only
stage: no implementation code was modified. Claims below were checked against the
tree on `main` (`3759df5`) and re-verified where cheap: Python suite 140 tests
(1 skip, symlink-dependent), Gradle host suite 154 tests 0 failures, `lintDebug`
clean, `git diff --check` clean. The 26-test instrumented suite was last run on
the physical device at the previous stage and was not re-run here (no code
changed). Classification of every finding: **BLOCKER / HIGH / MEDIUM / LOW**.

> Status note: the documentation/structure findings of sections 6, 8 and 9
> (stale README claims, broken PDF links, duplicate `docs/… (1).md` files,
> missing architecture/setup docs, unignored `.freebuff/`) were closed by the
> repository-organization stage that followed this audit. The "no CI" findings
> in sections 5 and 17 were closed by the CI stage (`.github/workflows/ci.yml`,
> `tools/check_repo_hygiene.py`, `CI.md`). All other findings stand as written.

## 0. Attention-area verdicts (read this first)

| Area | Verdict |
|---|---|
| Android acquisition | Implemented, frozen, device-verified (8/8 connected tests, 30-min endurance) |
| Recording / recovery / export | Implemented, real-corpus validated (44/44 sessions accepted, 2 recovered) |
| Replay | Implemented, read-only, Kotlin/Python parity tested |
| Python recording validation | Implemented, tested, run against real bytes |
| Live GNSS map | Implemented; out-of-coverage refusal device-verified; **in-coverage drawing not yet seen on device** |
| Recorded-session map | Implemented; single-point session device-verified; **multi-point trail host-verified only** |
| Synthetic map | Implemented and device-verified as a labelled UI fixture |
| GNSS loss timeline | Implemented; pixel-verified on all three sources; device-tested only on the scripted fixture |
| NavigationEngine | **Interface only.** No implementation exists. |
| IO-VNBD research | Phase 0 complete; **frame/mounting semantics unresolved — training blocked** |
| Frame / calibration state | `calibration: not_applied` in every session; mounting rotation unvalidated |
| Old INS experiments | 382.67% drift, gated behind `--offline-baseline`; research artifact, not a baseline |
| Root README accuracy | **Stale in at least four places** (see section 6) |

## 1. Implemented and verified

- **Contract boundary** (`contracts/v1`): seven typed payloads, strict
  Python + Kotlin codecs, golden and invalid fixtures, bidirectional
  round-trip verification. `NavigationEngine` exists as an interface with an
  explicit "no implementation" note. [BLOCKER-free; freeze as-is]
- **Recording contract** (`contracts/recording/v1`): session metadata +
  measurements layout, strict reader (`session.py`), Kotlin `RecordingCodec`;
  the measurement schema is deliberately the v1 record — no parallel contract.
- **Android acquisition** (`AndroidAcquisition.kt`, `Acquisition.kt`):
  foreground-only, HandlerThread-based, bounded queues, permission handling.
  Device acceptance **PASS** (`reports/android_acquisition_2026_09_19.md`):
  8/8 connected tests, 30-min stationary endurance, bounded memory. Frozen.
- **Recording with recovery** (`LocalRecorder.kt`, `FileRecordingStorage.kt`):
  atomic metadata replacement (temp + fsync + rename), interrupted-JSONL
  recovery with a strict prefix grammar, bounded async writes. Validated by
  real hardware: 44/44 sessions accepted by the contract reader, including
  2 interrupted sessions that replayed after recovery
  (`reports/recording_corpus_2026_09_29.md`).
- **Export** (`ExportController.kt`, `CreateSessionDocument.kt`): STORED ZIP
  with per-entry CRC and size checks, two streaming passes, local-only URI
  guard, interrupted-export warning that never touches the private original.
- **Replay** (`ReplayReader.kt`, `ReplayController.kt`): strict arrival-order
  reader (duplicate event IDs, count/channel reconciliation, replay source
  rewriting); cross-language parity via `tools/parity_probe.py` +
  `PythonParityTest.kt`.
- **Python recording validation** (`tools/validate_phone_recording.py`):
  device and local modes funnelling into one validator; positions never
  printed; exit-code contract documented and tested.
- **Offline map pack**: `hyderabad-v1`, 247 tiles, manifest SHA-256/size
  pinned and tested; no network access at any point (manifest strips
  INTERNET and network-state permissions; a device test asserts
  `INTERNET == PERMISSION_DENIED`).
- **Map presentation**: three explicit, never-blended sources (synthetic
  fixture / recorded session / live phone GNSS), coverage refusal, gap-split
  trails, isolated-fix dots, camera framing, GNSS loss timeline with one
  shared placement rule. Pixel-verified on device across all three sources
  (`mobile/MAP_DEVICE_VERIFICATION.md`).
- **Test totals**: Python 140 (1 skip), Kotlin host 154, instrumented 26.
  Lint clean (5 pre-existing dependency/target-version warnings).

## 2. Implemented but only host-tested

- **Multi-point recorded trail drawing.** Every device session available has
  fixes at one coordinate, so the `display-trail` LineString has never been
  observed on a device screen — only the host fold/overlay tests cover it.
  [HIGH]
- **Recorded and live loss timelines** are pixel-measured on device but carry
  no device *test*; only the scripted fixture has one. [MEDIUM]
- **In-coverage live fix drawing** (marker + camera on a real phone fix):
  indoors the phone only produced out-of-coverage network fixes; the refusal
  path is device-verified, the drawing path is not. [HIGH]
- **Recovery/export fault paths** (corrupt metadata, failed rename,
  interrupted export) exercised on host only. [LOW]
- **Kotlin/Python reader parity** (`PythonParityTest`, 1 test) — thin but
  present; both readers independently validated against the 44-session corpus.

## 3. Implemented but experimental

- `training/ins_mechanization.py` — classical strapdown INS, 382.67% drift
  over 429.8 m; requires `--offline-baseline` consent because it uses future
  GPS and VBOX initial velocity. Research artifact only. [HIGH as a baseline:
  it is not the "physically correct classical baseline" any AI claim must beat]
- `training/diagnose_body_frame*.py`, `frame_audit.py`, `frame_export_audit.py`,
  alignment searches — diagnostics whose *conclusions* are partly confirmed
  (header correspondence), partly inferred (world-aligned acceleration), and
  partly unresolved (mounting transform). [BLOCKER for calibration/training]
- `tools/parity_probe.py` — one-shot verdict printer, no maintenance issues.

## 4. Partially implemented

- **NavigationEngine**: interface only (`contracts/v1` Models.kt);
  `navigation_modes` empty in every recorded session. [BLOCKER for any
  navigation claim]
- **Calibration**: `CalibrationResult` payload exists; every session records
  `calibration: not_applied`; `CausalCalibration` prototype in the reports
  requires device-frame inputs the IO-VNBD export cannot supply. [BLOCKER]
- **Confidence**: `Confidence` payload defined; nothing produces it; the map
  shows a platform accuracy radius and correctly refuses to relabel it as 95%.
  [MEDIUM]
- **`core/`, `edge/`, `models/`**: empty directories, planned in README only.
  [LOW]
- **Recovery UX**: interrupted sessions are recovered on first library load,
  but there is no user-facing recovery report beyond the message string. [LOW]

## 5. Missing

- NavigationEngine implementation (evaluation + deployable modes). [BLOCKER]
- A real GNSS-loss/recovery corpus: 41 of 44 sessions have no GNSS at all,
  and none captured loss while moving. [BLOCKER for the problem statement]
- An in-coverage multi-point recording for device trail verification. [HIGH]
- Session deletion: no delete path anywhere — precise location history
  accumulates until uninstall. [HIGH, privacy]
- Progress/streaming for the 5–12 s recorded-session read. [MEDIUM]
- ZIP import, replay seek/speed controls (explicitly deferred). [LOW]
- Project LICENSE (only map licences are bundled). [MEDIUM]
- Any CI (`.github/` absent). [HIGH] — closed post-audit; see `CI.md`.
- Release build config: minification off, no signing, `versionName 0.1.0-demo`.
  [MEDIUM]

## 6. Stale documentation

- **Root README** (`README.md`) [HIGH]:
  - Links to `docs/26168 Documentation.pdf`, `docs/26168 Master Blueprint.pdf`,
    `docs/26168 Planning and Implementation Plan.pdf` — none exist; the real
    files are `docs/SIH_PS26168_*_Current_State.pdf`. All three links 404.
  - "device acceptance is pending" — acceptance passed 2026-09-19.
  - "Recording/replay and corrected INS remain missing gates" and "Planned: …
    Android recording/replay" — recording, recovery, export and replay are
    implemented and device-verified.
  - Describes "Dashboard, Diagnostics and About screens" — the app now has
    four tabs including Map.
- **`mobile/README.md`** [MEDIUM]: "No live position or routing yet" and
  "Not implemented: … live map positioning" — live GNSS mode shipped in
  `3759df5`. Routing remains correctly listed as missing.
- **In-app copy** `MapLimitsCard` (`OfflineMapScreen.kt:739`) [MEDIUM]:
  "no live position" is shown in every mode, including the one that draws the
  phone's live position. Also describes the purple/amber legend in terms of the
  scripted fixture only, though real trails and real losses now use those
  colours.
- **`mobile/VERIFICATION.md`** [LOW]: mostly disciplined (historical
  checkpoints explicitly marked), but its top summary predates the map work.
- **`reports/android_acquisition_2026_09_19.md`** [LOW]: ends "This report
  makes no claim that those features already exist" about recording — true
  when written, stale now.

## 7. Architectural inconsistencies

- **UI-layer session reading**: `loadRecordedView` (in `OfflineMapScreen.kt`)
  performs file I/O, session selection and folding inside the screen file,
  while every other data path goes through `SessionViewModel`. [MEDIUM]
- **Session selection is implicit**: recorded mode silently picks "the
  recording with the most records"; the user cannot choose a session. On this
  phone that always selects a 41k-record file, and it makes device tests
  order-dependent. [HIGH for tests, MEDIUM for UX]
- **Two gap semantics**: `RecordedSessionMap.DEFAULT_GAP_NS` (5 s, display
  splits) mirrors the acquisition contract's stale threshold but is a separate
  constant in a separate layer. Documented, but they can drift. [LOW]
- **`MapLimitsCard` legend text** duplicates colour semantics that also live in
  `MapLibreRenderer` layer definitions; three places to update per change. [LOW]
- **compileSdk 37 / targetSdk 36** mismatch is deliberate and lint-warned. [LOW]

## 8. Duplicate/parallel implementations

- `docs/PS26168_Application_Architecture (1).md` and
  `docs/PS26168_Navigation_Output_and_Evidence_Architecture (1).md` are
  byte-identical duplicates (MD5-verified) of their originals. [LOW]
- `training/diagnose_body_frame.py` and `diagnose_body_frame_v2.py` — two
  generations of the same diagnostic coexist; README says the entry points
  delegate to `frame_audit`, but both large files remain. [LOW]
- **Python and Kotlin contracts are intentional dual implementations**, kept
  honest by golden fixtures and the parity probe — correct pattern, listed
  here only to confirm it is not accidental duplication. [none]
- **No parallel data contracts** were found; the recording schema reuses the
  v1 record envelope as required. [none]

## 9. Technical debt

- **23 MB of historical JSON reports tracked in Git**
  (`phase0_inventory.json` 11 MB, `phase0_evidence.json` 7 MB,
  `frame_export_evidence.json` 3.2 MB, …). Clone weight, not runtime cost.
  [MEDIUM]
- **22 MB `hyderabad.mbtiles` inside the APK assets** — required for the
  offline pack, but it dominates app size and is rebuilt only via
  `mobile/tools/build_hyderabad_pack.py`. [LOW]
- **`.freebuff/` untracked and not gitignored** — will pollute `git status`
  indefinitely. [LOW]
- **No dependency lockfile** for either ecosystem (`requirements.txt` pins
  four direct deps only; Gradle versions are pinned per-dependency). [MEDIUM]
- **Release build unconfigured** (minify off, no signing, no shrinkResources).
  [MEDIUM]
- **`ReplayReader` is not replay-seekable** and `loadRecordedView` rescans the
  whole file on every mode switch. [LOW]
- **Gradle verification depends on a precise environment recipe**
  (`GRADLE_USER_HOME` must point at `mobile/.gradle-user-home` before any `cd`);
  not documented in any repo file. [MEDIUM]

## 10. Security/privacy weaknesses

- **No session deletion** [HIGH]: recordings hold precise continuous location
  plus IMU; the only removal path is uninstall. Export is user-consented, but
  retention is not user-controllable.
- **`data_extraction_rules.xml` + `allowBackup=false`** correctly exclude
  backup; verified present. [none]
- **No INTERNET permission at all** (manifest-removed, device-tested) — the
  strongest privacy property in the app. [none]
- **Exported ZIP contains raw location history** and lands in shared storage
  via the document picker; the app warns about privacy but cannot enforce
  handling. [MEDIUM, inherent to export design]
- **Positions suppressed in validation reports** (`validate_phone_recording`
  never prints positions). [none]
- **No rate limiting / biometric gate** on export of sensitive data. [LOW]
- **Map renderer network calls are disabled** by `MapLibre.setConnected(false)`
  *and* by manifest permission removal — belt and braces. [none]

## 11. Performance weaknesses

- **5–12 s blocking read of a 41k-record session** on mode switch, with only a
  text spinner. Byte-at-a-time line splitting in `ReplayReader.next()` is
  likely the dominant cost. [HIGH for UX]
- **`loadRecordedView` caps at 200k scanned records** but the corpus contains
  a 494k-record session; that session would be silently truncated with only a
  stats-line suffix noting it. [MEDIUM]
- **`liveGnss` flow folds every GNSS record on the main graph** with
  `WhileSubscribed` rebuilds; fine at 1 Hz fixes, would need review at higher
  rates. [LOW]
- **Map overlay GeoJSON is re-serialized in full** (`Gson().toJson`) on every
  `present()` call, i.e. every demo tick (~20 Hz). [LOW]
- **`EventIds` allocates a fixed 16 MiB long array per reader instance.** [LOW]

## 12. Test coverage gaps

- No device test for the recorded-mode trail/timeline (see section 2). [HIGH]
- No test asserts that `MapLibreRenderer` camera maths (bounds, degenerate
  handling) survives MapLibre upgrades — the zoom fit is native and untestable
  on host. [MEDIUM]
- **`training/ins_mechanization.run_ins` has no synthetic suite** (stationary,
  constant-velocity, circular, bias scenarios) — called out as FAIL in
  `foundation_readiness.md` and still open. [HIGH, gates any baseline claim]
- No Python tests for `tools/parity_probe.py` itself. [LOW]
- No tests for `OfflineMapScreen` session-selection logic (it lives in the UI
  layer; see section 7). [MEDIUM]
- No load/soak test for recording beyond the manual 30-min endurance run. [LOW]
- 1 Python skip (symlink support) is environment-dependent, acceptable. [none]

## 13. Error-handling gaps

- **`loadRecordedView` wraps no error boundary**: a corrupt session that
  passes `replayable` but throws mid-stream surfaces as `error == null`,
  `view == null` — the panel then says "No replayable recording on this
  device", which is a *false* reason. [HIGH]
- **`MapLibreRenderer.present` silently no-ops** when `map.style` is null;
  a timing race would draw nothing with no diagnostic anywhere. [MEDIUM]
- **`ExportController` reports "partial destination may remain"** after an
  interrupted export but never names or cleans the partial file. [LOW]
- **`FileRecordingStorage.recover`** counts failures per directory but the
  summary message is the only surface; nothing persists a recovery report.
  [LOW]
- `ReplayReader` throws `IOException("Replay line N: …")` — good detail; the
  map path above is where that detail gets lost. [MEDIUM]

## 14. Lifecycle/concurrency risks

- `LocalRecorder` uses a lock + supervisor scope + bounded queue; 18 host
  tests cover admission, drops, and finalize paths. Reviewed — sound. [none]
- `AndroidAcquisition` keeps sensor work on a HandlerThread with lock-guarded
  state and `quitSafely`; 30-min endurance passed. [none]
- **`loadRecordedView` runs in `LaunchedEffect(mode)`** and sets
  `recordedBusy`; switching modes rapidly cancels the read mid-stream, which
  is correct, but the cancelled read still burns I/O on the 41k-record file.
  [LOW]
- **`ON_STOP` lifecycle observer re-presents state**; documented as
  intentional, and the comment addresses the capture subtlety. [none]
- **`liveGnss` rebuilds per subscriber** (WhileSubscribed 5 s) — documented,
  and it prevents a stale trail after backgrounding. [none]
- MapLibre `MapView` lifecycle is manually forwarded; correct but fragile if
  a future contributor adds a second MapView. [LOW]

## 15. Data/ML scientific risks

- **IO-VNBD frame/mounting semantics unresolved** [BLOCKER]: acceleration/
  gravity exports appear world-aligned while gyro headers use a different
  device-axis naming; no verified export-to-vehicle rotation exists. Any
  calibration, corrected INS, or training built on these files would encode
  an unproven frame assumption. `training_admission.json` correctly refuses
  all 144 pairs.
- **Historical INS drift 382.67%** [BLOCKER as a baseline]: it is not a
  "physically correct classical baseline" to compare AI against; it also
  consumes future GPS/VBOX for initialization. The requested synthetic
  mechanization suite (section 12) must land before any baseline is credible.
- **Audit copies are not independent sequences** [HIGH]: 144 pairs are copies
  of 72 recordings; naive train/test splitting would leak. Admission policy
  says so; tooling does not yet enforce split groups.
- **No GNSS-loss corpus exists** [BLOCKER for the core problem statement]:
  the problem is navigation *through outages*; 41/44 sessions have no GNSS,
  and no session captured loss while moving.
- **GNSS course never observed** [HIGH]: `bearing_deg` null in all 1,775
  fixes; initial heading must come from magnetic/gyro evidence — a design
  consequence that no current code implements.
- **Speed unit label `Kmh` is wrong in source data** [MEDIUM, documented]:
  retained deliberately as evidence; any future consumer must not inherit it.

## 16. User-facing polish gaps

- 5–12 s "Reading saved session…" with no progress or cancellation feedback.
  [MEDIUM]
- No session picker: recorded mode always shows the largest recording. [MEDIUM]
- No session deletion or storage usage display. [HIGH, see section 10]
- `MapLimitsCard` legend is fixture-centric and says "no live position" in
  live mode. [MEDIUM]
- "IDR Demo" launcher name and `0.1.0-demo` version name are honest but
  pre-release. [LOW]
- Screen/tab naming drift: code says DIAGNOSTICS/"Signals", README says
  "Diagnostics". [LOW]
- The recorded panel's stats line is accurate but dense; outage counts deserve
  the summary treatment the live panel gives them. [LOW]

## 17. Release/deployment blockers

- No LICENSE [MEDIUM]; no CI [HIGH]; no release signing/minify config [MEDIUM].
- README broken links and stale claims (section 6) — first-contact document is
  wrong about what exists. [HIGH]
- No privacy policy/consent text for continuous location recording beyond
  inline copy. [MEDIUM for any real deployment]
- `data/raw/iovnbd` contains a nested `.git` directory (dataset cloned into
  the tree); ignored by rules, but a footgun for accidental commits. [LOW]
- No versioning/CHANGELOG discipline beyond commit messages. [LOW]
- No crash reporting (deliberate, offline-only) — means release debugging is
  logcat-only. [LOW]

---

# Recommended build order

1. **Phase A — Truth pass (docs only).** Fix root README links + stale claims,
   `mobile/README.md` live-map claims, and the `MapLimitsCard` legend copy;
   delete the two byte-identical `(1)` doc duplicates; document the Gradle
   environment recipe. Unblocks nothing technically; restores trust in docs.
2. **Phase B — Close the map's own gaps.** Deterministic seeded multi-point
   fixture + device test for the recorded trail/timeline; replace "most
   records" selection with explicit session selection (also fixes the
   order-dependent tests); error boundary + honest error states for
   `loadRecordedView`; streaming/progress read. Depends on nothing.
3. **Phase C — Data capture (parallel track).** Record a genuine
   GNSS-loss/recovery drive *inside* the pack area and a second multi-point
   in-coverage session; add session deletion + storage surface before more
   capture. Depends on nothing; B's tests benefit from it.
4. **Phase D — Baseline before intelligence.** Write the synthetic
   mechanization test suite (stationary/constant-velocity/circular/bias),
   then re-derive a *causal* classical baseline with no future information;
   enforce split groups in `training_admission`. All AI work depends on this.
5. **Phase E — Frame resolution.** Resolve IO-VNBD frame/mounting semantics
   (or formally retire the dataset for calibration) before any calibration or
   training. D can proceed in parallel; anything fitted cannot.
6. **Phase F — NavigationEngine v1.** Implement `evaluation` mode only,
   consuming replayed records, emitting contract `NavigationState`; wire the
   map to it behind the existing presentation adapter. Depends on D.
7. **Phase G — Deployable mode + calibration**, then AI error-correction
   models benchmarked against D's baseline, then confidence. Depends on E+F.

## Dependencies between phases

- A independent. B independent (but C's data improves B's evidence).
- C independent, parallel from day one.
- D depends on nothing; E blocks G's calibration but not D.
- F depends on D (credible baseline semantics) and the contract (done).
- G depends on D+E+F, and on admission policy enforcement from D.

## Explicitly NOT to build yet

- **Any AI/ML model, EKF, or learned correction** — no approved training
  sequence exists (0 of 144 pairs), no credible baseline to beat. [BLOCKER
  discipline: building it now would produce unverifiable claims]
- **Map matching, routing, or turn-by-turn** — needs road graph data and the
  engine; the problem statement does not require it.
- **Cloud/backend/infrastructure** — contradicts the offline-only manifest
  and the problem statement.
- **A second localization engine inside map/UI code** — the map is a
  renderer; it must keep refusing to estimate positions.
- **Confidence display beyond reported platform accuracy** — a calibrated
  95% claim without calibration would be fabrication.
- **Edge deployment (`edge/`)** — premature until the engine exists.
- **Recording format changes** — frozen; the 44-session corpus and the
  validators depend on byte semantics.

End of audit. No implementation was modified in producing this document.
