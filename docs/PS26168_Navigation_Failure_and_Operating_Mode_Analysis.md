# PS26168 — Navigation Failure & Operating-Mode Analysis
**Scope:** Behavior-level analysis of the physical/navigation problems the IDR system must handle. No dataset schema, feature engineering, exact filter/model architecture or UI implementation is defined here.

All analysis respects the established problem constraints: standalone smartphone navigation without OBD dependence, on-device inference, GNSS outage/degradation, phone IMU noise, map constraints, seamless recovery, and later external-IMU/edge deployment.

---

## 1. Complete GNSS Blackout

### Observable behavior

The phone stops receiving usable fresh satellite-position fixes while the vehicle continues moving. This is expected in tunnels, underground parking and other structurally blocked environments.

### Information available

- accelerometer,
- gyroscope,
- magnetometer/compass where usable,
- last accepted GNSS state,
- current calibration,
- previous navigation state,
- offline map/road geometry,
- vehicle-motion constraints.

### Information unavailable

- fresh absolute GNSS position,
- fresh GNSS velocity/course,
- any OBD/vehicle-wheel speed unless separately added (not allowed as a dependency),
- future recovery position.

### Required signals / context

- exact IMU timing,
- calibrated phone→vehicle frame,
- last trusted navigation state,
- bias/noise state,
- outage duration,
- confidence/uncertainty,
- map/road constraints where available.

### Timescale

From milliseconds after loss to tens of seconds/minutes; benchmark examples include short sub-minute outages and much longer tunnel-like distances.

### Likely failure modes

- velocity bias accumulation,
- yaw drift,
- gravity leakage into horizontal acceleration,
- wrong phone-to-vehicle alignment,
- overconfident state despite growing error,
- stale GNSS being accidentally reused as if fresh.

### Useful evidence

- last accepted GNSS timestamp,
- outage duration/distance,
- estimated vs reference trajectory,
- final outage error,
- drift percentage,
- uncertainty growth.

### Rules vs classical estimation vs ML

**Hybrid.**

- rules/state machine detect staleness/denial,
- classical INS propagates state,
- AI may improve speed/noise correction,
- EKF/covariance carries uncertainty,
- NHC/map matching constrain drift.

---

## 2. GNSS Degradation / Urban Canyon Before Full Loss

### Observable behavior

GNSS fixes continue arriving but can jump, bias, become stale/low-quality or disagree strongly with inertial prediction due to multipath, weak geometry or interference.

### Information available

- provider accuracy,
- fix age,
- satellites,
- speed/bearing when available,
- filter prediction,
- innovation/residual once fusion exists.

### Information unavailable

- guaranteed truth of an individual fix,
- reliable line-of-sight/multipath classification from Android metadata alone.

### Required context

- history of accepted/rejected fixes,
- innovation consistency,
- current covariance,
- map context.

### Timescale

Seconds to minutes.

### Likely confusion cases

- a fresh-looking but biased fix,
- brief satellite-count drop,
- low-speed bearing noise,
- network-provider location when precise GPS is unavailable,
- cached pre-session fix.

### Useful evidence

- fix age,
- provider,
- accuracy,
- satellite use,
- innovation magnitude,
- accepted/rejected reason code.

### Detection/handling approach

**Rules + statistical fusion gating.**

ML is optional later if ground-truthed degraded-GNSS data proves deterministic rules inadequate.

---

## 3. Phone Mount Misalignment / Arbitrary Orientation

### Observable behavior

The phone's X/Y/Z axes do not align with vehicle forward/left/up. The mount may be portrait, landscape or tilted.

### Information available

- gravity direction during stationary periods,
- gyro motion,
- GNSS course/displacement during sufficiently straight movement,
- accelerometer patterns,
- current mount state.

### Information unavailable

- vehicle frame by assumption,
- yaw from gravity alone,
- reliable magnetic heading in all vehicles.

### Required context

- stationary calibration window,
- straight-motion excitation,
- explicit calibration ID and validity.

### Timescale

Initial seconds before/at start of trip; recalibration after remount.

### Failure modes

- reflection/improper rotation,
- 90°/180° forward-axis mistake,
- yaw sign error,
- calibration on insufficient motion,
- dataset-export axis interpretation confused with real Android axes.

### Useful evidence

- estimated pitch/roll/yaw,
- gravity residual,
- straight-motion alignment score,
- validation against held-out maneuver.

### Approach

**Classical geometric/optimization calibration first.**

ML is not the primary solution because the physical rotation must remain explicit and unit-testable.

---

## 4. Accidental Remount / Phone Movement During Trip

### Observable behavior

Phone orientation abruptly or gradually changes relative to the vehicle after calibration.

### Information available

- gravity/orientation discontinuity,
- gyro impulse/rotation,
- calibration residual changes,
- inconsistency between predicted vehicle motion and measured axes.

### Information unavailable

- whether the movement was intentional without user input,
- new vehicle frame until recalibration.

### Timescale

Sub-second to several seconds.

### Likely failure modes

- continuing with an old transform,
- incorrectly attributing remount motion to vehicle turn/acceleration,
- hidden catastrophic drift.

### Useful evidence

- orientation change magnitude,
- time of calibration invalidation,
- recalibration request.

### Approach

**Rule/change-point detection + calibration invalidation.**

A lightweight classifier is optional if labelled remount data shows rules are insufficient.

---

## 5. Engine Vibration / Road Roughness / Potholes

### Observable behavior

High-frequency accelerometer/gyro energy unrelated to net vehicle translation; transient spikes during bumps/potholes; engine-idle harmonics.

### Information available

- short-window IMU sequences,
- frequency/variance/RMS patterns,
- current motion estimate,
- stationarity context.

### Information unavailable

- true forward speed from OBD,
- perfect separation of road/engine/vehicle dynamics from one instantaneous sample.

### Timescale

Tens of milliseconds to seconds.

### Likely failure modes

- integrating vibration as acceleration,
- predicting false speed during idle,
- treating pothole vertical shock as forward motion,
- excessive filtering that removes real braking/acceleration.

### Useful evidence

- raw vs filtered acceleration,
- spectral/short-window energy,
- baseline vs AI speed error,
- downstream outage-drift difference.

### Approach

**AI/ML + signal processing.**

This is one of the strongest justified learning problems in the PS.

---

## 6. Low-Cost IMU Bias / Drift Accumulation

### Observable behavior

Even after vibration filtering, small gyro/accelerometer bias causes position and heading error to grow over time when no absolute measurement is available.

### Information available

- current filter bias estimates,
- stationary segments,
- prior GNSS updates,
- covariance/uncertainty,
- NHC/map constraints.

### Information unavailable

- exact bias truth at every moment,
- external reference during deployment outage.

### Timescale

Continuous; error can become severe over tens of seconds/minutes.

### Failure modes

- wrong gravity compensation,
- gyro yaw bias,
- accelerometer offset,
- temperature changes,
- numerical integration issues.

### Useful evidence

- estimated bias,
- covariance growth,
- drift vs outage duration/distance,
- stationary reset behavior.

### Approach

**Classical estimation first; AI optional as a correction layer.**

AI must not be used to hide a frame/mechanization bug.

---

## 7. Forward-Speed Estimation Without OBD

### Observable problem

Distance travelled during GNSS denial depends heavily on forward velocity, but the phone has no direct wheel/OBD speed feed.

### Information available

- calibrated longitudinal/lateral/vertical IMU sequences,
- vibration pattern,
- previous speed state,
- stationary events,
- prior GNSS speed before outage.

### Information unavailable

- direct wheel speed,
- future GNSS speed during outage.

### Timescale

Short causal windows, continuously.

### Likely failure modes

- speed drift from acceleration integration,
- idle vibration interpreted as movement,
- underestimating constant-speed motion because acceleration approaches zero,
- route memorization in the learned model.

### Useful evidence

- predicted vs reference speed,
- MAE/RMSE,
- error by motion regime,
- classical vs AI comparison.

### Approach

**AI/ML is primary enhancement**, backed by classical speed/integration baseline.

---

## 8. Stationary / Stop-Go / Low-Speed Operation

### Observable behavior

Vehicle stops at lights/parking, crawls slowly, reverses or resumes. GNSS bearing can become missing/noisy at low speed.

### Information available

- IMU stability,
- estimated speed,
- GNSS speed when valid,
- filter state.

### Information unavailable

- reliable course angle from GNSS when nearly stationary.

### Timescale

Seconds.

### Likely failure modes

- velocity drifting while stopped,
- zero-velocity constraint firing during slow creep,
- false heading changes from noisy bearing.

### Useful evidence

- stationary detector state,
- speed before/after zero-velocity update,
- false-stationary rate.

### Approach

**Rule/statistical stationary detector + zero-velocity update.**

ML is optional, not required for MVP.

---

## 9. Turns / Curved Roads

### Observable behavior

Yaw rate becomes significant; vehicle heading changes while forward speed may remain steady.

### Information available

- gyroscope yaw-rate after calibration,
- lateral acceleration,
- map road geometry,
- GNSS heading/course when valid.

### Information unavailable

- perfect heading truth during outage.

### Timescale

Sub-second to several seconds per maneuver.

### Likely failure modes

- wrong gyro-axis sign,
- yaw bias,
- overaggressive straight-road/NHC constraint,
- incorrect road match at intersections.

### Useful evidence

- heading trace,
- yaw-rate sign,
- turn trajectory vs reference,
- map-match candidate history.

### Approach

**Classical mechanization + fusion + gated constraints.**

Sequence ML is not required just to turn correctly.

---

## 10. Map-Matching Ambiguity

### Observable behavior

The raw fused position lies near multiple plausible roads: parallel roads, flyovers, service roads, intersections or parking aisles.

### Information available

- raw position,
- heading,
- speed,
- uncertainty,
- road graph topology/history.

### Information unavailable

- guaranteed current lane/road when geometric evidence is ambiguous.

### Timescale

Seconds across multiple observations.

### Failure modes

- snapping to wrong parallel road,
- impossible jumps between disconnected edges,
- map error/coverage gap,
- map matching visually hiding a bad estimator.

### Useful evidence

- raw and matched points,
- candidate edges,
- distance/heading/topology scores,
- match confidence.

### Approach

**Classical geometry/HMM-style matching first.**

Keep raw fused position separate from matched output.

---

## 11. GNSS Recovery After Outage

### Observable behavior

Fresh GNSS becomes available while the DR state may be offset from the absolute fix.

### Information available

- DR prediction/covariance,
- returning GNSS fix/quality,
- innovation,
- map constraint,
- outage duration.

### Information unavailable

- assumption that first returning fix is correct.

### Timescale

Milliseconds to several seconds depending on filter convergence.

### Failure modes

- visible marker teleport,
- accepting one bad multipath fix,
- over-smoothing presentation and hiding a real estimator correction,
- prolonged recovery.

### Useful evidence

- first accepted fix,
- innovation,
- correction magnitude,
- confidence contraction,
- convergence time,
- before/after error.

### Approach

**Quality gate + fusion update + presentation smoothing only for visual interpolation.**

UI animation must not rewrite stored estimator state.

---

## 12. Offline Map / Coverage Failure

### Observable behavior

Vehicle leaves bundled map coverage or map pack is missing/corrupt.

### Information available

- numeric navigation state,
- current WGS84 position,
- map coverage bounds/install status.

### Information unavailable

- road topology outside installed coverage.

### Timescale

Immediate when crossing coverage.

### Failure modes

- blank map,
- forced network fallback,
- map matcher using nonexistent road graph.

### Useful evidence

- explicit map coverage warning,
- numeric position remains available,
- map/navigation failure separated.

### Approach

**Operational state handling, not ML.**

Navigation should continue numerically if the map renderer fails; map matching may degrade/disable separately.

---

## 13. Edge / High-Rate External IMU Operation

### Observable problem

The same algorithmic concepts must work on external IMU data at much higher update rates, with a target around 200 Hz for FOG-based edge evaluation.

### Information available

Depends on device:
- calibrated external accel/gyro,
- precise timestamps,
- GNSS aiding if connected,
- more stable inertial measurements.

### Information unavailable

Until hardware is defined:
- exact bus/protocol,
- clock behavior,
- noise characteristics,
- CPU budget.

### Timescale

~5 ms per 200 Hz cycle.

### Failure modes

- smartphone-specific preprocessing baked into shared logic,
- model window tied to one sample rate,
- slow Python/mobile assumptions,
- timestamp/interface bottleneck.

### Useful evidence

- sustained input/output rate,
- p95 latency,
- drops/backlog,
- accuracy under held-out outage.

### Approach

**Shared semantics + rate-aware implementation.**

This is a deployment/generalization problem more than a new ML problem.

---

# NAVIGATION PROBLEM → OBSERVABLE SIGNAL → HANDLING APPROACH

| Problem / Mode | Observable Signal | Primary Handling |
|---|---|---|
| Complete GNSS blackout | fix age grows; no usable fresh GNSS | INS/DR + AI speed correction + EKF prediction + constraints |
| Degraded GNSS | poor/inconsistent fixes, large innovation | GNSS quality rules + fusion gating |
| Arbitrary phone mount | gravity/kinematics inconsistent with vehicle frame | automatic calibration |
| Remount | orientation discontinuity | invalidate/recalibrate |
| Vibration/potholes | high-frequency/transient IMU content | signal processing + AI speed/vibration model |
| Bias/drift | growing inertial error/uncertainty | filter bias state + GNSS aiding + constraints |
| No OBD speed | acceleration alone insufficient at steady speed | AI forward-speed estimation |
| Stationary/stop-go | low motion, noisy GNSS bearing | stationary detector / zero-velocity update |
| Curves/turns | yaw rate + lateral motion | mechanization + gated NHC/fusion |
| Road ambiguity | multiple nearby candidate edges | map-matching scoring/HMM |
| GNSS recovery | returning fix vs DR innovation | gate + fusion reconvergence |
| Map coverage loss | WGS84 outside installed pack/road graph | renderer/matcher degrade separately |
| Edge high-rate | ~200 Hz external IMU stream | optimized shared engine / ONNX runtime |

---

**No exact dataset schema, feature set, model architecture or filter equations are selected here. This is the problem-behavior decomposition that later design documents must satisfy.**
