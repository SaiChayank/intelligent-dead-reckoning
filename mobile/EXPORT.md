# Local recording UI and explicit export

This stage adds presentation and read-only export around the frozen recorder. Acquisition, recorder storage/recovery, and recording contract 1.0.0 are unchanged. No replay or navigation implementation is included.

## Usage

1. Select Phone sensors and start acquisition. Start recording explicitly. Simulation currently has no typed acquisition recording stream; its recording button remains disabled.
2. Recording state and written/dropped/write-error counters are separate from live acquisition. Recording details shows session ID, original source, elapsed seconds, and a once-per-second size snapshot of both private files. Unknown values remain unknown. Buffered writes can make the size snapshot lag.
3. Stop recording and wait for finalization. Saved sessions shows timestamp (UTC), original source, duration, record count, completion/recovery status and combined file size. Refresh after finalization/recovery. Pages contain at most 20 sessions in lexicographic ID order, not date order.
4. Select Export copy (.zip). Choose local device storage in the Android document picker and confirm. Cancelling creates no app-initiated output. Only Android external-storage/downloads document providers are accepted; cloud/unknown providers are rejected even if they ignore the local-only picker flag.

No storage permission, background service, network request, automatic export or hidden external file is added. Opening the picker retains the established lifecycle behavior: acquisition stops when the activity stops. Returning never restarts it automatically. Export is disabled during recorder start/record/finalize. Recording cannot start while export is choosing/writing.

## Packaging and reconstruction

The archive contains exactly `metadata.json`, then `measurements.jsonl`, at its root. Each is an exact byte copy of the app-private original. ZIP uses STORED entries (no compression), fixed timestamps and no variable comments. Two bounded streaming passes calculate CRC/size and copy data; memory is independent of recording length. Repeated exports of unchanged input have identical archive bytes.

Extract into a new directory and use the existing recording contract readers. Exact Int64 strings, nulls, original source identities and measurement units are never reserialized. No historical IO-VNBD interpretation is applied. Private originals are never removed or rewritten. This is packaging, not new validation: incomplete/failed/recovered evidence retains its state and even corrupt bytes, and downstream strict readers must still validate it. Open or recovery-required sessions cannot be exported.

The export reports success only after closing the archive/output. Missing/invalid metadata or files produce explicit errors. A failed, cancelled or process-interrupted write may leave a partial user-selected destination; remove that copy or export again explicitly. Do not treat a partial archive as a valid session. The app does not silently delete the chosen document. Backgrounding during active copying cancels the worker; the picker phase itself is allowed to background the activity. No automatic restart occurs after process death.

## Verification and remaining device gate

2026-09-23 continuation: `testDebugUnitTest` passed all 86 tests (0 failures/errors, including 10 new session/export tests); `lintDebug` passed with 0 errors and 2 warnings; `assembleDebug` and `assembleDebugAndroidTest` passed. `git diff --check` passed. A minimum-SDK incompatibility in `InputStream.readNBytes` was found by lint and replaced with a bounded API-compatible read. `adb devices` returned no devices, so instrumented tests and physical picker/export validation were not executed in this continuation. Status: NOT READY for device-verified stage sign-off; implementation/build/JVM checks are complete.

JVM tests cover exact/deterministic packaging, source preservation, missing/open/incomplete sessions, bounded pages, oversized metadata, write failure, cancellation, repeated export rejection and lifecycle cancellation. Android tests cover SAF intent/cancel/local-provider filtering and missing-session UI; existing recording control/lifecycle tests remain intact.

Build and test commands from `mobile` (use the configured Android Studio JBR and repository-local Gradle cache):

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
adb devices
```

Do not uninstall or clear app data to run tests: private recordings would be lost. With the phone authorized, install both APKs using `adb install -r`, then run `adb shell am instrument -w com.intelligentdeadreckoning.app.test/androidx.test.runner.AndroidJUnitRunner`.

### Connected-phone verification (2026-09-23)

Status: **READY for the next stage**, superseding the earlier no-device gate above. No replay was added.

- Authorized physical OnePlus CPH2585 (`5c7d81bb`); APKs updated with `adb install -r`, without uninstalling or clearing data.
- Initial instrumentation run: **16 tests passed**. After adding `ExportLifecycleDeviceTest`, the full instrumentation suite passed **17 tests**, 0 failures, in 21.575 seconds. This includes recording controls, acquisition stop, source switching, foreground stop/no restart, session UI, SAF intent and provider restrictions.
- Actual Saved sessions screen showed session `02edb616-0a6d-4cd1-82a9-aae0f55340b3` as REAL, INCOMPLETE / RECOVERED, 6925 records, 23 seconds; the incomplete warning was visible.
- Actual Android Downloads document picker cancellation returned “Export cancelled; original unchanged.” A second explicit picker operation saved `idr-0309c1f1-2475-49a5-9f88-71fe6dbfb156.zip` (21689 bytes). The app displayed successful export separately from recording/navigation.
- Large completed session exported to `idr-f2608548-d1c8-4007-9e72-87045a094f47.zip` (174754375 bytes). Both ZIPs contain exactly metadata.json and measurements.jsonl. Each entry's SHA-256 matched its private original.
- The large export completed before the attempted Home-button interruption. This is **not** evidence of a successful mid-copy UI cancellation. A deterministic on-device slow-destination test instead blocked actual writer output, invoked the same `onBackground` cancellation hook, and verified CANCELLED, destination closure and unchanged fixture originals. OEM/provider-specific blocking I/O remains a limitation: cancellation is cooperative between chunks, not a way to forcibly interrupt a hung provider call.
- SHA-256 inventory before and after both full test runs: **64/64 pre-existing recording files unchanged**. Recording lifecycle tests added eight small app-private sessions (16 files); no user sessions were removed. Both intentional export copies remain in Downloads. Ignored local `mobile/artifacts/export-device-check.zip` and `export-large-device-check.zip` are verification copies and must not be committed or uploaded.
- `assembleDebugAndroidTest` passed after adding the new test; `git diff --check` passed. Prior 86 JVM-test and lint results above remain applicable: no production code changed during this device-validation continuation.

Remaining non-blocking risks: device/provider coverage is currently this phone and its local Downloads provider; cancellation or process death can leave a partial chosen destination, as documented. No other OEMs or cloud providers were validated or enabled. No acquisition/recorder/contract redesign was required, and no commit, merge or push was performed.
