# Local/offline security and privacy verification

This checklist implements the focused controls in
[`docs/PS26168_Focused_Security_Privacy_Review.md`](../docs/PS26168_Focused_Security_Privacy_Review.md).
It verifies the existing single-user, local Android prototype; it does not introduce
accounts, authentication, RBAC, a backend, telemetry, or a new network capability.

## Automated checks

From the repository root:

```sh
python -B -m unittest discover -s tests
python tools/check_repo_hygiene.py
python tools/audit_dependencies.py
```

The dependency command runs an already-installed `pip-audit` against pinned
`requirements.txt` and returns its status; it never installs or updates application
dependencies. The CI job installs a pinned auditor into its disposable runner before
running it. Advisory resolution requires network access on that development/CI host;
the installed application remains offline. Review Android's pinned Gradle coordinates
and available advisories before a release; the build has no Android vulnerability
scanner plugin or Gradle dependency lockfile, so a clean Python audit is not an Android audit.

From `mobile/` with the project JDK/SDK configured:

```sh
bash gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

The JVM tests exercise strict Python/Kotlin contract fixtures, session traversal and
symlink refusal, deterministic export preserving originals, recording recovery,
MBTiles and road-graph SHA-256/size checks, and model-policy non-applicability. The
instrumented security test inspects the installed package's requested permissions,
backup flag, and packaged backup-exclusion rules; `assembleDebugAndroidTest` only
compiles it. An authorized Android device is required to execute instrumented tests.

## Current invariants

- The source manifest grants only coarse/fine foreground location and removes inherited
  Internet, network-state and Wi-Fi-state permissions. It requests no background
  location, service, notification, or broad storage permission; cleartext traffic is
  disabled. Confirm the *merged/installed* manifest rather than source alone.
- Acquisition, recording and replay are foreground-owned. Returning from background
  does not restart them. Recordings are created beneath `Context.noBackupFilesDir`;
  no external-storage permission is used.
- Backup is disabled and Android cloud-backup/device-transfer rules exclude every
  private domain without re-including any. Export is an explicit user action through
  `ACTION_CREATE_DOCUMENT`; only the two approved local document authorities are
  accepted, even if a provider ignores `EXTRA_LOCAL_ONLY`. A partial exported copy can
  remain after cancellation/failure; the private source is never intentionally modified.
- Session IDs and artifact names are allowlisted, paths are normalized under the
  recording root, and symlinked roots/ancestors/session directories/artifacts are
  refused. Session readers bound metadata and use the canonical strict recording codec.
  Experiment manifest paths reject absolute, traversal, drive/colon and backslash forms;
  integrity walkers refuse symlinks and unlisted artifacts. Dataset snapshots likewise
  reject symlinked roots or nested entries.
- Python and Kotlin codecs reject unknown/missing keys, duplicate JSON keys, malformed
  UTF-8/JSON, non-finite numbers, invalid enums/units/ranges, inconsistent invariants,
  oversized records and invalid stream ordering before records reach the engine.
- Bundled offline map resources and the road graph are size-bounded and SHA-256 checked
  against checked-in manifests before install/use. Private copies are atomically replaced
  only after verified temporary copies. No network fallback is attempted.
- Experiment sealing binds the manifest and every artifact with a sorted SHA-256
  manifest; verification rejects tampering, missing and unlisted artifacts. Phase-0
  dataset audit snapshots hash/size/mtime before and after analysis.
- There is **no deployed model, model loader, inference runtime, or model update path**.
  Consequently a model SHA-256 check cannot currently run; do not describe this as a
  model-integrity pass. Before adding a model, require a reviewed immutable hash/version
  manifest and refuse mismatches before inference. No automatic online learning and no
  silent model replacement are implemented or permitted by the current architecture.
- No app-runtime `Log.*`, `println`, `printStackTrace`, or Timber calls are used. Errors
  shown at storage, replay, map, graph and export boundaries are stable diagnostic codes
  or generic messages, not exception paths/provider text. The Diagnostics screen
  intentionally displays raw measurements (including exact GNSS coordinates) while
  foreground and may be captured in a user-triggered recording; this is UI, not a
  general/system log. Treat screenshots and exported recordings as sensitive.
- Repository hygiene scans tracked paths and common credential formats, and blocks
  generated secrets/model weights. This is a high-precision pattern gate, not proof that
  no secret exists: review new/untracked files and release-signing material separately.

## Manual device acceptance (disposable test install where noted)

1. **Package/permissions:** install a fresh debug APK; inspect the merged manifest and
   `dumpsys package <applicationId>`. Verify no granted/requested Internet,
   `ACCESS_BACKGROUND_LOCATION`, foreground-service, notification, or broad-storage
   permission. Verify only coarse/fine location are requested. Deny location, grant
   approximate, grant precise, revoke while running, and confirm IMU operation and
   explicit stopped/degraded states without fabricated GNSS.
2. **Offline behavior:** enable airplane mode (and leave it on), then load the bundled
   map, record a short session, stop, inspect the session list and replay. Verify there
   are no tile/API/network requests and no hidden restart when returning from Home.
3. **Private storage/backup:** record once and inspect app-private `noBackupFilesDir`;
   confirm no recording appears in shared storage. Review packaged backup/data-extraction
   rules. Confirm data-clear/uninstall consequences with a disposable install only.
4. **Explicit export:** initiate export, cancel the picker, export to local Downloads or
   device storage, and verify the archive contains only `metadata.json` and
   `measurements.jsonl`. Try an unsupported/cloud provider and confirm rejection. Compare
   private-source SHA-256 before/after successful, cancelled and failed exports.
5. **Path/tamper refusal:** host tests create traversal and symlink fixtures without
   touching real recordings. On a disposable test install, corrupt a copied map/road
   asset and confirm a generic refusal, no network fallback, and no change to raw
   navigation output. Never alter the user's installed asset or existing recordings.
6. **Privacy/log review:** with test-only synthetic data, inspect Android logcat while
   acquiring/recording/replaying. Confirm no exact coordinate, trip path, session payload,
   export URI, or device identifier is emitted by the app. Keep screenshots and exports
   local; do not use real-route data for this check.
7. **No model mutation:** verify About/release notes continue to say AI/model inference is
   not deployed. If any model is introduced later, separately test missing, wrong-hash,
   and rollback/version policy before enabling it; do not test against a live user model.

## This pass: verification status

- Host Python suite: **282 passed, 5 skipped**; Android JVM suite: **366 passed**;
  lint, debug APK assembly, and instrumented APK compilation succeeded.
- Repository hygiene: **262 tracked files checked**, no common secret-pattern or
  forbidden-file findings; `git diff --check` passed.
- The instrumented security test is compiled but not executed. No authorized phone or
  emulator/ADB is available here, so effective package inspection and the manual device
  acceptance steps above remain outstanding.
- `pip-audit` wrapper and isolated CI gate are implemented, but the auditor is not
  installed in this environment. No Python vulnerability result is claimed from this
  pass. Dependency advisories are retrieved online by the CI gate; review its result.
- No model artifact/runtime exists, so model hashing, online learning, and replacement
  behavior are not applicable at runtime; preserve the explicit gate before any future
  model deployment.

Record device model, OS/build, APK SHA-256, test outcome, and redacted evidence on the
next device pass. Do not claim any device-only check complete from host tests or APK
assembly.
