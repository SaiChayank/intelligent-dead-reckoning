# Foreground local session replay

## Scope and use

Open **Saved sessions**, choose **Replay session**, then use Pause, Resume or Stop replay. Replay replaces the live dashboard with its own diagnostics panel. Select Phone sensors or Simulation explicitly to leave playback; neither starts automatically. Completed playback must be started again explicitly from Saved sessions.

Playback reads existing app-private sessions only. There is no ZIP import, seek, speed adjustment, navigation, map, AI, fusion or background service. The frozen recording and acquisition implementations and contracts are unchanged.

## Contract and safety

- Only completed sessions or INCOMPLETE / RECOVERED sessions are eligible. Open, failed, recovery-required and unrecoverable sessions are rejected. Recovered sessions retain a visible incomplete warning.
- `SessionFiles.openReplay` opens measurements read-only. Replay never truncates, repairs, rewrites or deletes source data. Existing recorder recovery remains the only recovery owner.
- Every record is decoded by the canonical RecordingCodec (which delegates measurement validation to Codec). Versions, UTF-8, duplicate keys, fields, types, enums, finite values, source/session membership and nulls retain canonical rules.
- File arrival order is preserved, including valid diagnostic-before-measurement event-ID ordering. Original event IDs, session identity, `t_ns`, `received_ns`, payload units and nulls stay exact. Only the emitted header's source changes through `replaySource`: real → replay_real; simulation → replay_simulation. Replaying again intentionally emits the same original IDs in a new playback run; consumers must reset between runs, never merge runs into one stream.
- Duplicate IDs are checked with an exact fixed-capacity primitive hash set (16 MiB), independent of trip duration. This version explicitly caps playback at 1,000,000 records and each record at the canonical 64 KiB limit. Oversized sessions fail with REPLAY_RECORD_LIMIT, rather than silently dropping data. No session payload array or unbounded queue is retained. Raising the cap or introducing a disk-backed index is a future extension.
- Final record and per-channel counts are verified at EOF. A complete last record without LF is accepted. Blank rows, corrupt interior records and partial trailing records fail explicitly with a line number. Validation is incremental: an earlier valid prefix may have been emitted before a later failure; FAILED means the entire run is not validated. Downstream consumers must not promote a partial run to a successful evaluation.

## Timing and lifecycle

Replay uses a separate monotonic playback clock at fixed 1× speed. Relative pacing uses receipt timestamps, matching recorded arrival rather than sorting physical sensor times. The first row is due immediately; each next row is due at the maximum receipt offset seen so far. Regressing receipt times therefore cause no negative wait and retain file order. All scheduling arithmetic retains integer nanoseconds; only bounded delays are rounded to milliseconds. Paused wall time is excluded. UI elapsed time is the last emitted playback position, not a replacement measurement timestamp.

File reads and decoding run on Dispatchers.IO. The typed `replayEvents` SharedFlow has no extra queue and suspends for slow active collectors rather than dropping records. A slow consumer can make playback late; it does not change event timestamps. Without subscribers, playback still advances for the diagnostics snapshot. The UI samples presentation state at 100 ms and retains only the latest record, not the entire stream. Pause prevents scheduling new emissions; an emission already handed to a subscriber cannot be retracted.

The ViewModel stops the selected live source before starting replay and rejects replay while recording/finalization/export is active. Live acquisition/recording start is blocked while replay is busy. Source switching, activity stop/rotation and ViewModel destruction cancel playback and close its input. Returning never resumes automatically. The STOPPING state blocks a second run until the old worker exits. A process restart returns to idle; no saved playback auto-start exists.

## Implementation boundaries

- `replay/ReplayReader.kt`: bounded line reader, canonical validation, exact duplicate detection, counts.
- `replay/ReplayController.kt`: lifecycle-owned playback clock, controls and typed stream.
- `sessions/SessionFiles.kt`: eligibility and read-only access only; export behavior preserved.
- `SessionViewModel.kt`, `MainActivity.kt`: mutual exclusion and lifecycle wiring.
- `ui/SessionDialog.kt`, `ui/ReplayPanel.kt`, `ui/IdrApp.kt`: selection, controls, source labels and presentation.
- `ReplayTest.kt`: deterministic JVM fixtures and virtual-clock tests.
- `ReplayUiTest.kt`: disposable synthetic phone fixtures, controls, source exclusion and lifecycle tests.

## Verification procedure

Run `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` from mobile with the configured JBR/SDK. Update the app and test APK using `adb install -r`, never uninstall/clear data. Run `adb shell am instrument -w com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner`.

JVM cases include exact timestamps beyond 2^53, both replay sources, null GNSS fields, duplicate/interior/trailing corruption, versions/membership/counts, incomplete eligibility, empty sessions, pause/resume, repeated start, cancellation, restart, bounded limits and slow consumers. On-device fixtures are explicitly SIMULATION and do not claim measured motion. Separately exercise an existing real and recovered recording without altering it, and compare private-file checksums before/after. Physical results are recorded below only after execution.

## Executed results — 2026-09-23

**READY for the next stage within the documented replay limits.**

- Gradle `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain -Pkotlin.compiler.execution.strategy=in-process`: BUILD SUCCESSFUL. All **96 JVM tests** passed, 0 failures/errors (10 new replay tests). Lint: **0 errors, 1 warning**. Both APKs built.
- APKs installed with `adb -s 5c7d81bb install -r`; app data was not cleared. Physical OnePlus CPH2585 instrumentation: **19 tests passed**, 0 failures, in 26.83 seconds. Includes the 17 prior tests and two replay UI/lifecycle tests.
- Actual saved-session UI: `02edb616-0a6d-4cd1-82a9-aae0f55340b3` replayed as `replay_real` with INCOMPLETE / RECOVERED warning. Paused at 3551 / 6925 records, playback position 11 seconds; later inspection confirmed the identical paused count. Explicit resume completed at **6925 / 6925**, position 23 seconds, with EOF counts verified.
- Actual completed session `0309c1f1-2475-49a5-9f88-71fe6dbfb156` replayed to **57 / 57** with source `replay_real` and counts verified. Sub-second duration displays as 0 whole seconds, not an invented zero-timestamp recording.
- Before/after SHA-256 inventories: **80/80 pre-existing private recording files unchanged**. Existing recorder regression tests created four new small sessions (8 files). Replay's two explicitly synthetic disposable fixtures were cleaned up; no user session was deleted or rewritten.
- `git diff --check` passed. No changes under contracts, data/raw, acquisition implementation or recorder implementation. Previous uncommitted work remains present. No commit, merge or push.

No implementation test failures required a semantic change to frozen behavior. Review caught that valid zero-valued channel counts must compare equal to absent observed channels in an empty session; the EOF comparison was corrected and covered by a test. A pre-existing Kotlin test-source nullable-receiver compiler warning and one lint warning remain; neither blocked execution.

Remaining limitations: fixed 1×, no seeking/import, one-million-record cap, a 16 MiB duplicate index per active reader, incremental validation (late corruption can invalidate an emitted prefix), and cooperative cancellation of bounded local-file work. Pause does not retract a record already delivered to a subscriber. No long-duration whole-trace replay or other-OEM verification is claimed by this stage. Maps and navigation engine integration remain separate future tasks.
