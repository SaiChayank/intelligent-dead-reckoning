# Phase 1 — Raw INS Baseline

## Sequence

| Item | Value |
|---|---|
| Sequence | M (Driver B) |
| Samples | 601 |
| Duration | 60.000 s |
| Nominal sampling rate | 10 Hz |

## Timestamp diagnostics

The smartphone DATE field was retained for diagnostic purposes. Mechanization uses the synchronized nominal 10-Hz row clock because the two DATE gaps account exactly for the 2.323 s duration discrepancy with the VBOX stream.

Detected DATE gaps:

- None

## Initialization

- Initial GPS heading: 62.884 deg
- Initial velocity: VBOX row-0 speed/heading (evaluation baseline only)
- Gyro bias samples: 0
- Estimated gyro bias: [+0.000000, +0.000000, +0.000000] rad/s

## Metrics

| Metric | Value |
|---|---:|
| Total reference distance | 429.788 m |
| Final position error | 1644.650 m |
| Mean position error | 383.444 m |
| Median position error | 145.716 m |
| Position RMSE | 583.438 m |
| Maximum position error | 1644.650 m |
| Drift / distance | 382.6657% |
| Velocity RMSE | 126.274 km/h |
| Velocity MAE | 82.157 km/h |

## Propagation policy

- Accelerometer and gravity are processed in phone body coordinates.
- Linear acceleration = accelerometer - supplied gravity.
- Raw gyroscope measurements are integrated using 3-D rotations.
- Linear acceleration is rotated into local ENU coordinates.
- Velocity is integrated from acceleration.
- Position is integrated from velocity.
- No EKF.
- No AI.
- No map matching.
- No GNSS measurement updates.
- GPS is used only for initial heading/reference comparison.

## Interpretation

This is the classical pre-AI dead-reckoning baseline. Significant drift is expected. Phase 2 should be evaluated by measuring whether AI-derived corrections reduce this drift.
