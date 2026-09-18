# Bounded continuation prompts

The readiness audit is complete; the **foundation is not ready for AI**. The
original request assumed a corrected INS and Android acquisition already existed.
They do not. No percentage of remaining model allowance can make those scientific
and implementation gates pass automatically. Use one bounded task per session,
keeping Astra/high if that is your chosen setting. Do not spend tokens rerunning
completed full-dataset audits just to recreate an overview.

## Completed in the current bounded review

`foundation_readiness.md`, per-pair `training_admission.json`, a proposed versioned
navigation contract, shared golden fixture, four Python and four Kotlin parity
tests, current host test/build verification, and a repeatable historical INS
replay. There is no approval to start AI or production EKF.

## Common instruction for every prompt below

Work in the existing Intelligent Dead Reckoning repository. Read
reports/foundation_readiness.md and relevant source before editing. Inspect Git
status and preserve all existing tracked/untracked work. Use apply_patch. Never
modify, rename, delete, move or regenerate data/raw files. Preserve historical
reports; put new evidence in new explicitly named files. No AI training or
production EKF. Finish with files changed, exact test results, remaining blockers
and the next bounded action. Do not call an absent component complete.

## Prompt 1 — implement the contract boundary

Apply the common instruction. Complete only the proposed contract under
contracts/v1. Implement equivalent Python/Kotlin typed models and strict streaming
JSON/JSONL readers/writers for every defined event. Keep Kotlin contracts and the
NavigationEngine interface independent of Compose and map SDKs. Do not implement
INS propagation, a sensor collector, map rendering or an EKF. Preserve exact
Int64 nanosecond strings, nulls, source labels, quaternion conventions and units.
Validate enum/version/key/type/range/finite-value constraints and cross-field
invariants. Reject duplicates and malformed records with explicit diagnostics.
Extend golden tests with invalid versions, reflected/non-unit rotations, NaN,
truncated JSONL, absent optional data and timestamps beyond 2^53. Verify round trips
both directions and clearly label the engine interface as not yet implemented.
Do not reinterpret existing IO-VNBD exports as raw Android device-frame IMU.

## Prompt 2 — real sensor and GNSS acquisition, without recording

Apply the common instruction. Continue from the working Compose shell and typed
contract. Add a real source alongside simulation using lifecycle-owned Android
accelerometer, gyro, magnetometer, optional gravity and optional comparison
rotation vector acquisition. If rotation vectors are added, version their event
definition explicitly; do not force them into a three-axis IMU record. Add GNSS
acquisition with safe nullable speed/bearing/altitude/accuracy and satellite status.
Implement precise/approximate/denied/revoked runtime location handling. Preserve
per-sensor monotonic event and receipt timestamps; use bounded queues and explicit
late/duplicate/drop events. Keep I/O/heavy processing off the UI thread. Stop all
listeners when the owner stops. Do not add a foreground service unless separately
requested. Diagnostics must show measured rates and real/simulated source labels.
Test permission states, missing hardware, time conversion, ordering, lifecycle,
null GNSS fields and source switching. Follow the device procedure in the readiness
report; record exactly what was actually verified on the connected phone. No
recording, navigation, maps, AI or fusion in this prompt.

## Prompt 3 — local recording, export and real replay

Apply the common instruction. With verified acquisition, implement optional local
streaming JSONL recording and metadata: contract/source versions, boot/session
clock identity, device/OS and sensor names/vendors, requested/measured sampling,
calibration state and source configuration. Add bounded asynchronous writes,
explicit start/stop, file errors and partial-record recovery. Export only on user
request through Android's sharing/storage workflow. Do not upload data. Implement
Kotlin replay and a Python reader using the same contract; preserve source times
and mark replay_real/replay_simulation distinctly. Compare a short actual recording
on both sides by counts, timestamps, nulls and numeric tolerances. Test corrupt
lines, process/lifecycle interruption, disk errors and slow consumers. Run the
stationary five-minute recording/export/replay check and a bounded endurance test.
Do not claim fresh phone data validates the old IO-VNBD exporter.

## Prompt 4 — resolve the physical frame evidence (external gate)

Apply the common instruction. Review the latest frame-export follow-up, not just
the earlier body-frame report. Use newly supplied original logger/export settings,
source/mount metadata or independently referenced calibration evidence to resolve
the actual complete gyro triad, acceleration/gravity export frame and proper
mounting rotation. Test signed axes, six-face gravity, timing and stability on
independent windows. Do not rank by absolute correlation alone or retrofit a
test-sequence lag. If required evidence is still unavailable, stop with an exact
missing-material list and retain no-go; do not guess a transform or rebuild the
INS on a false assumption. New-device calibration and historical-dataset approval
must be separate decisions. The project owner may explicitly approve a different
validated baseline dataset if the old export semantics cannot be recovered.

## Prompt 5 — corrected classical INS only after Prompt 4 passes

Apply the common instruction. Implement the reviewed physical frame/unit/timing
conventions in a classical open-loop strapdown baseline. Add explicitly separate
evaluation and causal deployable initialization. Use reliable real deltas and a
documented reject/fallback policy, proper rotations/quaternions, consistent
specific-force/gravity handling, justified biases and stable velocity/position
integration. Add physical tests for stationary, gravity-only, constant velocity,
constant acceleration, positive/negative constant yaw motion, known rotations,
bias subtraction, irregular/invalid deltas, ENU and quaternion normalization.
Evaluate stationary, straight, turning, braking, 60-second mixed and a reasonable
longer segment. Report vector velocity as well as scalar speed errors, attitude,
biases, timing anomalies, position drift and valid heading errors. Label every
artifact with sequence/time/mode/units/GNSS-after-initialization policy. Repeat
runs deterministically. No AI, GNSS updates, constraints or fusion. Large drift
must be attributed with evidence rather than excused as expected.

## Prompt 6 — deployable preprocessing, splits and map boundary

Apply the common instruction. After corrected baseline approval, implement matching
causal Python/Kotlin preprocessing and strict feature allowlists excluding VBOX/CAN
reference fields. Use real and adversarial shared fixtures to compare resampling,
filters, gaps, initialization boundaries, units and frame outputs at documented
tolerances. Create whole-recording/session splits merging exact/numeric duplicates
and overlaps before windows; fit alignment, normalization and calibration only on
training/independent calibration data. Freeze configuration and provenance before
test evaluation. Approve/reject sequences individually with explicit evidence.
Implement/test a UI-independent map adapter boundary with offline no-map fallback;
keep algorithm state independent of a map provider. Do not silently add online
location uploads or proprietary tile caching. Refresh Phase 0 into a NEW report
directory intentionally after source fixes, compare historical results and run
repeat/integrity checks. This prompt still does not train a model.

## Prompt 7 — rerun the full readiness gate

Apply the common instruction. Rerun the original Python, Android, integration and
AI-readiness checks against actual implementations and fresh evidence. Update
foundation_readiness.md with every gate's result, tests, real segment metrics,
device/recording/replay status, contract/preprocessing parity, provenance and the
training admission manifest. If any required gate fails, retain no-go and give
its owner an exact repair task. Only if every gate passes, provide a subsequent
leakage-safe AI velocity-model development prompt. Do not begin training as part
of the readiness review itself.
