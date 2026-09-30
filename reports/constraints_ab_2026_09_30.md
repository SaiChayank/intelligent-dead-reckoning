# Vehicle-motion constraints (ZUPT / NHC) — validation report

Date: 2026-09-30. Scope: classical stationary/zero-velocity updates and non-holonomic
lateral/vertical constraints added to the stage-7 fusion pipeline, per
`docs/PS26168_Non_ML_Baseline_Navigation_System.md` sections 5–6.

## Result

**Constraints validated.** On the held-out synthetic outage drives the constrained
arm improves every error metric, with no regression and no guard trips:

| Metric (mean over 5 drives) | EKF only | EKF + ZUPT/NHC | Change |
|---|---:|---:|---:|
| Outage-end error | 408.74 m | **118.90 m** | **−70.9%** |
| Outage RMSE | 179.57 m | **57.59 m** | **−67.9%** |
| Stop drift (parked inside outage) | 52.55 m | **26.06 m** | **−50.4%** |
| Final error (end of drive) | 3.49 m | **3.40 m** | −2.5% |

The A/B is deterministic and reproducible: same seeded fix noise per drive, only
`FusionConfig.motionConstraintsEnabled` differs
(`VehicleConstraintsEngineTest.theHeldOutOutageEvaluationRunsBothArmsOnTheSameDrives`).
Drive shape: 30 s parked → 12 m/s cruise → 120 s GNSS outage containing a full stop
(brake, 30 s parked, re-accelerate) → cruise → parked. The regression gate fails the
suite if the constrained arm is >10% worse on any metric; it passes with a −2.5% final
error and large outage gains.

## What the constraints are

- **Zero-velocity update (`updateZupt`)**: three ENU velocity rows asserting v≈0, gated by
  the rolling stationary detector (the declared 2 s / 20-sample / 0.05 rad/s / 9.3–10.3
  m/s² resting rule from `reports/body_frame_conventions.md`), the filter's own speed
  (hysteretic: engage below 0.5 m/s, hold through the band, disengage above the 2 m/s NHC
  floor), and non-contradicting fresh GNSS speed. Variance σ 0.2 m/s, deliberately loose.
- **Non-holonomic constraints (`updateNhc`)**: the vehicle lateral (left) and vertical
  velocity ≈ 0, as rows along the ENU columns of the calibrated vehicle attitude — forward
  velocity is never touched. Gated by calibration validity, speed ≥ 2 m/s (parking/reverse
  stand-down), yaw rate ≤ 0.5 rad/s (unusual maneuvers), measured longitudinal specific
  force ≤ 0.35 m/s² (acceleration/braking/grade stand-down), a 5 s contiguous benign-dwell
  before (re)start, fresh GNSS (≤ 3 s) corroborating motion, and a 2 s backoff after any
  refusal. Variances σ 0.3 / 0.5 m/s, deliberately loose.
- Both go through the same joint NIS gate and Joseph update as GNSS rows. Constraint
  refusals are counted separately (`constraintAccepted` / `constraintRejected`) and never
  touch the GNSS counters, `lastRejectionNis`, or the persistent-rejection span. One
  diagnostic per direction (`FUSION_CONSTRAINT_APPLIED` / `FUSION_CONSTRAINT_REJECTED`),
  not per sample. `FusionConfig.motionConstraintsEnabled = false` restores the plain
  filter exactly.

## Gating lessons measured, not assumed

The A/B harness caught four genuine failure mechanisms during development; each is fixed
by a named gate and the fix is load-bearing for the result above:

1. **NHC on a parked vehicle is a feedback loop.** A filter whose speed error grew past
   the NHC floor while stopped re-opened its own speed gate and "constrained" a parked
   car along a corrupted attitude — velocity 0.87 → 14.46 m/s while parked, every update
   "accepted". Fresh-GNSS-required and the ZUPT's ownership of the parked regime removed
   the loop at its only unspoofable input.
2. **A ZUPT speed gate referenced to filter sigma is self-defeating.** Letting the ZUPT
   re-arm "once NIS is consistent" dragged every outage cruise to zero (≈7.2 km error on
   all five drives) — the stage-7 lesson that a gate must not trust the covariance it
   gates. The speed gate is absolute and hysteretic instead.
3. **A speed gate without hysteresis locks the ZUPT out of real stops.** Braking leaves
   ~1 m/s of estimate lag; a strict 0.5 m/s engage threshold meant the ZUPT was locked out
   of exactly the stop it exists for (420 m of parked drift). The engage/hold/disengage
   band fixes it.
4. **NHC during acceleration/braking/grade is a forward-velocity drain.** With any tilt
   error, the tight lateral row converts it into a forward-velocity correction that fights
   the measured acceleration (velocity 5.87 vs 6.50 truth mid-acceleration), and the first
   NHC after a dynamic phase meets cross-covariances built while it stood down — a violent
   one-step attitude correction followed by gravity leakage and a refusal storm. The
   measured-longitudinal-force stand-down plus the benign dwell remove both.

## Limitations

- **A creeping vehicle below the engage threshold on a smooth road is still frozen by
  ZUPT when no fresh fix contradicts it.** This is the declared IMU-only limitation
  ("slow creep interpreted as stopped"); with fixes present, GNSS speed arbitrates.
- **Level cruise and parked are IMU-identical.** The NHC resolves this with fresh-GNSS
  corroboration; the ZUPT resolves it with the filter-speed gate and, in outages, accepts
  the residual risk for a genuinely-creeping vehicle that also fools the detector.
- **Synthetic evaluation only.** The drives are deterministic and physically consistent
  (real specific-force profiles, integrated truth), but there is no ground-truthed real
  drive in the corpus yet; numbers are evidence of mechanism, not of field accuracy.
- Thresholds (`zuptSpeedThresholdM_S`, `nhcMinSpeedM_S`, `nhcMaxYawRateRad_S`,
  `nhcMaxForwardAccelM_S2`, `nhcDwellS`, variances) are chosen engineering values,
  stated as such, subject to the same validation discipline as every other gate in
  this project.

## Tests

21 new tests: `StationaryDetectorTest` (8, detector gates and hysteresis),
`VehicleConstraintsTest` (7, filter-level NIS gating, axis semantics via both mount
orientations, counter isolation), `VehicleConstraintsEngineTest` (6, gate matrix through
the production engine plus the A/B evaluation). Full suite: 282 tests, 0 failures;
lint 0 errors.
