# Phone-to-vehicle calibration

The app's session-local handoff is now implemented by `CalibratingFusionEngine`;
see [demonstration procedure](DEMONSTRATION.md). The estimator mathematics and
thresholds remain unchanged except for a confirmed resting-window guard defect:
recent moving-fix time is retained independently of the latest speed, so a later
stopped fix cannot relabel a just-closed driven window as stationary gravity.
The existing five-second motion-recency margin is unchanged.

## Scope and use

The calibration engine estimates the rotation between the phone's Android device frame and the
vehicle frame, plus the two sensor biases that can be defended from the evidence. It is the first
real `NavigationEngine` implementation and it is reached **only** through
`navigation/NavigationRuntime.kt`, which owns the session, routes canonical records in and
publishes canonical records out on a single worker thread.

What it publishes: `calibration` records (`CalibrationResult`) whenever the calibration is new,
changes status, or moves materially; `navigation` records that report `CALIBRATING` until a
calibration is in force and `DEGRADED` — never `TRACKING` — afterwards, because the frame is
known while no position, velocity or heading solution exists; `confidence`; and a `diagnostic`
record for every decision, including every rejection and its reason.

What it does not do: no position, velocity or heading estimate; no propagation, fusion, EKF, AI or
learning of any kind; no magnetometer and no vendor gravity sensor (see *Deliberate limits*); no
sensor access, no file access and no map — the engine sees typed records and nothing else.

## Frames and signs

| Frame | Definition |
|---|---|
| Vehicle | `X` forward, `Y` left, `Z` up, right-handed |
| Device | Android sensor frame: `X` right, `Y` up, `Z` out of the screen, right-handed |
| Estimated | `q_vehicle_from_device`, the rotation taking a device-frame vector into the vehicle frame |

Two sign conventions decide whether the answer is right at all, so they are stated rather than
left to the code:

- **The accelerometer measures specific force**, `a − g`. At rest that is the gravity reaction and
  it points **up**, so a resting accelerometer's mean direction is the world up axis expressed in
  device coordinates — not "down" and not "the direction of gravity".
- **Forward is decided by measured speed**, not by the force alone. Speeding up puts the
  horizontal specific force along vehicle forward; braking puts it backwards. The sign comes from
  the fitted slope of reported ground speed, and the two are cross-checked before either is used.

## Stage A — stationary

`StationaryAccumulator` judges a resting period in short **sub-windows** (default 0.5 s), and a
window is built only from sub-windows that each passed on their own: not vibrating, not rotating,
a plausible gravity magnitude, and pointing the same way as the window so far.

That last gate is the one that matters most. A driven phone is as *steady* as a parked one but its
specific force tilts by `atan(a / g)` — about 9° at a mild 1.5 m/s² — so judging a whole period at
once would blend a genuine parked measurement with driving samples into a window that looks
perfectly stable while its gravity direction is wrong by degrees. Instead, the first sub-window
that fails ends the window, so a drive starting after a parked period closes it within half a
second and delivers the parked measurement intact. `MountEstimator` additionally discards a quiet
window that ended while GNSS reported the vehicle moving, as a second line of defence.

From competing windows at the same attitude:

- **Tilt** — the mean of the agreeing resting means. Rotation about the vertical axis is invisible
  to an accelerometer, so the remaining yaw is left at the deterministic minimal-rotation value and
  is *reported as unmeasured*: a calibration with only Stage A evidence is `PENDING`, never `VALID`.
- **Gyroscope bias** — the mean resting rate, which is a device property and therefore survives a
  remount. Windows that disagree beyond `maxGyroBiasSpreadRad_S` are reported as inconsistent
  rather than averaged.
- **Accelerometer bias** — solved by multi-position calibration, and **only when the evidence
  justifies it**. One attitude cannot separate a horizontal bias from a tilt, nor a vertical one
  from a gravity-scale error; the solve additionally requires at least three attitudes that are not
  near-coplanar, a well-conditioned system, a consistent residual, and a magnitude above the
  sampling-noise floor. Anything less leaves the bias null and says so. A null bias is the honest
  answer, not a missing feature.

## Stage B — dynamic

A *segment* is a run of fixes above `movingSpeedM_S` with no long gap, bounded in length so
per-segment memory stays finite. Its evidence is judged by every gate below, in the order that
produces the most specific diagnosis; a rejected segment never contributes to the yaw:

| Rejection code | Meaning |
|---|---|
| `CALIBRATION_SEGMENT_GNSS_SPARSE` | fewer usable fixes than `minGnssFixes` |
| `CALIBRATION_SEGMENT_GNSS_INACCURATE` | reported horizontal accuracy worse than `maxGnssAccuracyM` |
| `CALIBRATION_SEGMENT_NO_IMU` | no usable paired IMU samples inside the segment |
| `CALIBRATION_SEGMENT_NOT_STRAIGHT` | yaw rotation above `maxYawRateRad_S` for too much of it |
| `CALIBRATION_SEGMENT_INSUFFICIENT_EXCITATION` | horizontal specific force below `minExcitationM_S2` |
| `CALIBRATION_SEGMENT_SPEED_SLOPE_UNRELIABLE` | ground-speed rate too small or too poorly sampled |
| `CALIBRATION_SEGMENT_FORCE_SPEED_MISMATCH` | force and speed rate disagree beyond `maxForceSpeedRatio` |
| `CALIBRATION_SEGMENT_COURSE_UNSTABLE` | reported course wandered during the segment |
| `CALIBRATION_SEGMENT_BASELINE_TOO_SHORT` | displacement below `minBaselineM` |
| `CALIBRATION_SEGMENT_GNSS_COURSE_INCONSISTENT` | reported course disagrees with measured displacement |

The yaw itself comes from the direction of the horizontal specific force projected off the
measured up axis, with the sign taken from the speed slope. GNSS supplies gravity-independent
evidence for whether the motion really was straight and which way it pointed: the reported course
must agree with the displacement actually measured between the segment's own fixes. A receiver
whose course contradicts its own displacement is not used at all, which is a different failure
from a receiver that is merely noisy.

Accepted segments are combined as a circular mean, and their spread is checked **before** they are
trusted: two straight segments that disagree about vehicle forward are contradictory evidence, not
noise, so the calibration is rejected as `INVALID` (`CALIBRATION_YAW_INCONSISTENT`) rather than
averaged. `EVALUATION` mode needs one segment; `DEPLOYABLE` mode needs two. An adopted prior
calibration counts as one determination, so a deployable session that adopts a prior still has to
earn its second segment.

## Remount detection

A material change of phone orientation invalidates the calibration and requires recalibration. Two
independent mechanisms are needed because the two cases are physically different:

- **Gravity change** — a resting window whose up direction has moved further than
  `remountTiltDeg` from the calibration's own reference cannot be the same mounting. This catches
  re-placement and any tilt change.
- **Handling rotation** — rotation about vehicle *up* is invisible to gravity, so an in-place
  yaw change is detected instead from gyroscope rotation integrated while the vehicle is parked
  (above `handlingRateRad_S`, past `remountRotationDeg`).

Either one publishes the calibration as `EXPIRED` under its own id, keeps the sensor biases,
drops every mounting-dependent quantity, and starts a new determination that must be recalibrated
from scratch — a new window for tilt, a new segment for yaw.

## Publication and ids

- An id names one calibration episode. While an id is in force its transform may still be refined
  as evidence arrives, and every refinement is announced under that id, so a consumer must treat
  the most recent record for an id as authoritative.
- A retired id is never published again: a remount expires it, and the replacement episode gets a
  new id.
- An adopted prior's id is **not** taken over. Continuing to publish another session's id with
  this session's refined transform would change what a consumer already recorded without saying
  so.
- `confidence` is a **bounded quality score, not a calibrated probability**: it is the weakest
  measured margin, so `0.8` means the least-supported part of the estimate had 80% of its
  tolerance in hand. No calibration probability model exists, so none is claimed, and a `PENDING`
  calibration is capped because an unmeasured yaw must not look usable. The `confidence` payload's
  numeric fields stay null: there is no accuracy, probability or speed uncertainty to justify them.

## Deliberate limits

- **Earth rotation is not compensated.** At phone gyro bias levels it is orders of magnitude below
  the noise floor; its worst-case contribution to yaw is a few thousandths of a degree per second.
- **The magnetometer is unused.** Magnetic yaw needs hard/soft-iron calibration plus declination,
  which this engine does not have — and a bad declination is a silent heading error.
- **The vendor gravity sensor is unused.** It is somebody else's fusion product rather than a
  measurement this engine can audit.
- **Yaw evidence exists only for forward travel.** Reversing makes course-over-ground point
  backwards, so segments are gated on sustained speed above `movingSpeedM_S`.
- **The estimator does not model physics it cannot measure.** Which GNSS state may follow which is
  not enforced: that is a judgement about a drive, and a validator that guessed it would reject
  real drives.

## Thresholds

Every value in `MountThresholds` and `StationaryThresholds` is a documented engineering choice for
phone-grade MEMS, not a measured constant, and they are injectable so tests drive the gates
directly instead of depending on production margins. The load-bearing defaults: 0.05 m/s² and
0.01 rad/s per-axis resting stability, a 1.5 s minimum resting window, 3 m/s to count as driving,
0.3 m/s² minimum excitation, a force-to-speed-rate ratio between 0.4 and 2.5, 15 m maximum fix
accuracy, 15° course-versus-displacement agreement, and 10° of tilt change or 20° of parked
rotation to declare a remount.

## Implementation boundaries

- `calibration/Rotations.kt` — quaternion algebra, proper-rotation invariants, `rotationFromTo`,
  yaw/pitch/roll extraction. Pure functions, no state.
- `calibration/StationaryWindow.kt` — IMU pairing type, resting-window judging, stability metrics.
- `calibration/MountEstimator.kt` — Stage A and Stage B evidence, remount detection, publication.
  Physics only: no sensor, no file, no map, no clock.
- `calibration/CalibrationNavigationEngine.kt` — the `NavigationEngine` adapter: record pairing,
  canonical output, diagnostics.
- `navigation/NavigationRuntime.kt` — the only way in or out; canonical output includes
  `calibration` records.
- `test/.../CalibrationEngineTest.kt` — the analytic suite below.

## Verification

`CalibrationEngineTest` builds a synthetic vehicle from a chosen ground-truth mount and generates
contract-level evidence from it, so every expectation is checked against the truth rather than
against another estimate. It covers: identity, portrait, landscape and tilted mounts (exact to
1e-4 degrees on noiseless geometry, including the same mount recovered on two different courses);
noisy resting windows with a capped `PENDING` confidence; a vibrating parked period publishing
nothing; a parked period followed by a hard pull-away that must not blend driven samples into the
resting window; gyroscope bias recovery; accelerometer bias solved only from a tetrahedron of
attitudes and refused when the attitudes are coplanar or the solve is absurd; constant-speed,
weak-excitation, turning and inaccurate-fix rejections; a course that contradicts its own
displacement; one-segment versus two-segment modes; contradictory segments rejecting rather than
averaging; remount from gravity and from handling; quaternion proper-rotation invariants and the
stage-B projection; and one end-to-end case through `NavigationRuntime` whose every output record
round-trips through the frozen codec.

Host tests are not device evidence. What remains unproven without a phone: that real MEMS noise and
real vibration pass the resting gates, that a real drive reaches the excitation gate often enough
in normal use, and that handling detection does not fire from road bumps while parked on a slope.
Those need the one-phone procedure described in `CI.md`; no accuracy claim is made until then.
