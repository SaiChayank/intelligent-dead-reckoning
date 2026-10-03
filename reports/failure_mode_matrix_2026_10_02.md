# Production reliability pass — failure-mode matrix

**Date:** 2026-10-02
**Scope:** Android acquisition, calibration, fusion/navigation, recording/replay, offline assets, map matching, and Activity/process lifecycle. This is a test-and-hardening record, not a claim of physical-device qualification.

## Outcome key

- **Contained**: the failing subsystem reports a named state/diagnostic and unrelated work can continue.
- **Refused**: untrusted input is not used; no position is fabricated.
- **Terminal**: the current navigation/recording run is marked failed and must be explicitly reset/restarted.
- **Device-only**: the code has an instrumentation/procedure seam, but this checkout has no attached device, so that behavior was not executed in this pass.

## Failure-mode matrix

| Injected failure | Expected behavior / state transition | Diagnostic and position rule | Verification |
|---|---|---|---|
| Accelerometer missing | Sensor marked unavailable; registration is not attempted; acquisition can still report other present channels | `SENSOR_MISSING`; no IMU sample or engine propagation is invented | Host `AcquisitionTest.missingHardwareNeverRegistersOrInventsSamples`; actual inventory is device-only |
| Gyroscope missing | Same; engine cannot form accel/gyro pairs and coasts only as bounded by its normal propagation rules | `SENSOR_MISSING` / `FUSION_IMU_PAIRING`; no sample reuse | Host sensor-registration and engine pairing tests; device inventory is device-only |
| Magnetometer missing | Optional channel remains unavailable; no fabricated magnetic sample or heading | `SENSOR_MISSING`; navigation heading stays null until calibration/source provides it | Host registration test; physical sensor availability is device-only |
| Location denied / revoked | GNSS becomes `DENIED`; permission transition clears queued/current location while IMU remains usable | `PERMISSION_DENIED`, `PERMISSION_REVOKED`, `PERMISSION_CHANGED`; no location-derived position | Host acquisition and quality tests; OS dialog/revocation requires device-only instrumentation |
| GNSS stale / denied | Quality moves to `STALE`/`DENIED`; live marker expires on clock ticks; fusion transitions from tracking to degraded/DR and grows uncertainty | `STALE_FIX`, GNSS outage/recovery diagnostics; position is prediction-only only while INS is operating, never presented as fresh GNSS | Host `GnssQualityTest`, `AcquisitionTest`, `RecordedSessionMapTest`, fusion outage tests |
| Calibration fails / insufficient evidence | No valid calibration is published; engine stays calibrating/degraded and will not claim calibrated vehicle heading/tracking | Named `CALIBRATION_*` reason; missing attitude remains null | Host `CalibrationEngineTest` analytic noisy, low-excitation, contradictory-course and inadequate-segment cases |
| Phone remount | Prior calibration expires; a new pending calibration id begins; engine must not continue using expired mount | `CALIBRATION_REMOUNT_DETECTED`; old transform no longer valid | Host `CalibrationEngineTest.remountAtRestIsDetectedFromGravityAndRequiresRecalibration` and handling/remount cases |
| Model missing/corrupt | **Not applicable in current Android build:** no on-device AI model or inference dependency exists; AI arm is explicitly `not_implemented` | No AI output or model-derived position is produced | Static source inventory; not a runtime-injected path because no model component exists |
| Model inference exception | **Not applicable in current Android build:** no inference path exists | No inference, no corrected position | Same as above; introducing an AI runtime requires a separate adapter and failure tests before enabling it |
| INS numerical invalidity | Numerical failure is terminal for the filter; state/covariance roll back to the last validated checkpoint; engine publishes failed status without spatial values | `ENGINE_FAILURE` at runtime; `NUMERICAL_INVALIDITY` in filter state; failed position hidden | Host `FusionFilterTest.finiteButExtremeSamplesFailNumericallyWithoutLeakingNonFiniteState` and navigation presentation tests |
| EKF invalid covariance/state | Nonfinite, asymmetric, non-positive-definite covariance or invalid nominal state is refused; correction/propagation cannot leak partial state | `NUMERICAL_INVALIDITY`; no invalid confidence or fresh position | Host covariance positive-definiteness test + covariance invariants across prediction/update; numerical failure test |
| Navigation ingress overflow | Bounded queue refuses new row, counts drop/high-water, consumer remains nonblocking | `ingressDropped` and `ingressHighWater`; no silent record loss | Host `NavigationRuntimeTest.ingressIsBoundedAndDropsAreCounted` |
| Recorder write failure / low storage | Recording admission stops; accepted prefix drains if possible; metadata is failed/recovery-required; acquisition continues independently | `WRITE_FAILURE`, nonzero `writeErrors`/dropped; no false completed recording | Host `LocalRecorderTest.writeCreateSyncCloseAndFinalizationFailuresAreVisible`; Android filesystem injection in `RecordingStorageDeviceTest` is compile-only here |
| Corrupt recording | Recovery preserves complete corrupt bytes and marks unrecoverable; does not truncate interior corruption or fabricate a valid session | Recovery failed summary; no replayable state | Host `LocalRecorderTest.corruptInteriorCompleteTailDuplicateAndInvalidUtf8AreNeverTrimmed` |
| Corrupt replay | Reader aborts at malformed row/count/checksum/invariant boundary; controller enters `FAILED`; already-emitted prefix is explicitly not a validated whole session | `Replay line N: ... Playback aborted`; no silent repair | Host `ReplayTest.rejectsDuplicateInteriorCorruptionTruncatedTailAndCountMismatch` and controller failure test |
| Corrupt offline map pack | Hash/length/manifest validation refuses installation; UI displays unavailable, never switches to network fallback | Specific install exception in `map_error`; no map/position claim from missing map | Pack logic host/device tests; failed-asset install is device-only in this pass |
| Road graph unavailable/corrupt | Evaluation matcher has no usable graph; raw navigation remains separate; matching is not allowed to invent/snap | Dedicated `matchingIssue` explains graph installation failure while raw position remains visible; no matched point | Host `RoadGraphTest` missing/corrupt/checksum, plus `EngineSessionMapTest.graphInstallationFailureKeepsRawPositionAndExplainsMissingOverlay`; Android install/low-space injection is device-only |
| Map matcher has no candidate / ambiguous evidence | Match refused; matched fields remain null; raw published navigation point remains unchanged | `NO_CANDIDATE` or `LOW_CONFIDENCE`; no snapped position | Host `MapMatcherTest.aFixWithNoRoadWithinReachIsReportedNotSnapped` and ambiguity test |
| Out of map coverage | Map matcher refuses candidate; raw coordinate remains; map presentation may hide outside supported render coverage | `OUTSIDE_COVERAGE` / `Outside offline coverage`; no extrapolation to graph bounds | Host `MapMatcherTest` and `NavigationPresentationTest` |
| Low storage during recorder operation | Same terminal recording failure as write failure; queue closes and drops are accounted; acquisition continues | `WRITE_FAILURE` and failed metadata/recovery state | Injected host ENOSPC plus Android filesystem instrumentation source; physical storage exhaustion not induced |
| Activity background/recreation | Foreground source, replay, and navigation stop; no automatic resume after foreground/recreated Activity; recorder finalizes | Explicit stopped state; no stale live/engine marker | Host lifecycle state tests and Android instrumentation tests (compile-only this pass) |
| Process restart during recording | OPEN/REQUIRED sessions are scanned at startup; valid prefix is recovered as INCOMPLETE; ambiguous/interior corruption becomes UNRECOVERABLE, preserving bytes | Recovery summary names recovered/unrecoverable; replay only accepts completed or recovered-incomplete sessions | Host `LocalRecorderTest` recovery cases; actual kill/relaunch on Android device remains device-only |

## Reliability hardening in this pass

1. **Atomic numerical rollback:** finite-but-extreme inertial inputs can overflow intermediate quaternion/geodesy/covariance arithmetic. Propagation now checkpoints the last valid filter state, validates nominal state and a Cholesky-positive-definite covariance, and restores that checkpoint before latching `NUMERICAL_INVALIDITY`. EKF updates also validate the incoming covariance and avoid publishing an invalid/partially corrected state.
2. **Corrupt recorded-map view containment:** loading a selected recording for map display now catches parse/I/O exceptions at the panel boundary, reports `Recording unavailable` with a `recorded_error` diagnostic, and leaves the map and other data sources alive. Cancellation is rethrown rather than swallowed.
3. **Failure-state map hiding:** the map presenter hides its last position when a valid FAILED navigation event arrives; failed state has no spatial fields under the contract.

## Coverage limits and residual gaps

- No Android device/emulator is attached during this pass. OS permission revocation, actual physical sensor absence, true storage exhaustion, Activity recreation UI, native MapLibre pack failure, and OS process death/relaunch cannot be claimed runtime-verified from host tests.
- There is no model to fail: the Android system has no model loader or inference runtime, and the evaluation contract honestly marks the AI arm not implemented. Model failure tests become applicable only after a model adapter exists.
- Graph-load failures now remain local to the optional evaluation overlay: the raw engine map continues, `matchingIssue` is exposed to the UI, and no map-matched output is shown. Device filesystem/asset corruption is still not injected on-device in this environment.
- The existing failed navigation output intentionally has no position. Consumers must clear/hide the preceding point (tested at the presenter level); any new UI consumer needs the same rule.

## Verification status

- Kotlin JVM: **363 tests, 0 failures, 0 errors** (clean test task run).
- Python: **272 tests passed, 1 skipped**.
- `lintDebug`: **0 errors, 1 existing warning** (`OldTargetApi` in the application configuration); `compileDebugAndroidTestKotlin` and `assembleDebug` succeeded.
- Python AST gate: 66 files parsed. `git diff --check` exited 0 (only the checkout's LF/CRLF normalization warnings).
- The APK was produced (82,291,649 bytes); androidTest sources compiled but were not run. The host shell has no `adb` command/attached emulator, so device-only rows above remain verification limits.

The first host run found the missing `abs` import and a failed-state presentation fixture that violated the strict contract; both were corrected before the final green test run.
