# PS26168 — Controlled Navigation Data-Collection & GNSS-Outage Evaluation Lab Architecture

**Scope:** Design of the controlled environment that produces ground-truthed smartphone/vehicle navigation data and repeatable GNSS-available / degraded / denied / recovery experiments. No navigation algorithm, feature engineering, or model training is designed here.

**Safety principle:** all vehicle experiments must be conducted legally and safely. A passenger/test operator handles the phone/laptop; the driver does not interact with the app while the vehicle is moving. Artificial GNSS interference/jamming is not part of this design.

---

## 1. Test Topology

```text
                        VEHICLE / TEST PLATFORM
┌──────────────────────────────────────────────────────────────┐
│                                                              │
│  Android phone                                               │
│  ┌──────────────────────┐                                    │
│  │ IMU + GNSS           │                                    │
│  │ IDR acquisition app  │                                    │
│  │ app-private recorder │                                    │
│  └──────────┬───────────┘                                    │
│             │ canonical recording                             │
│             │                                                │
│  Independent reference                                       │
│  ┌──────────────────────┐                                    │
│  │ RTK/VBOX-class GNSS  │  position/speed/heading/time       │
│  └──────────┬───────────┘                                    │
│             │                                                │
│  Optional environment logger                                 │
│  ┌──────────────────────┐                                    │
│  │ passenger/laptop     │  experiment events, mount state,   │
│  │ / action marker      │  tunnel entry/exit, notes          │
│  └──────────┬───────────┘                                    │
└─────────────┼────────────────────────────────────────────────┘
              │
              ▼
      OFFLINE POST-PROCESSING
┌─────────────────────────────────────┐
│ clock alignment / integrity checks  │
│ reference interpolation/alignment   │
│ GNSS masking / scenario labels      │
│ replay through same navigation code │
│ metrics + plots                     │
└─────────────────────────────────────┘
```

No OBD/CAN input is required for the mobile deployable path.

---

## 2. Component Responsibilities

| Component | Role |
|---|---|
| **Android phone** | Captures real device-frame accelerometer, gyroscope, gravity, magnetometer, GNSS/provider metadata, exact monotonic timestamps, permission/quality state |
| **IDR recorder** | Writes canonical metadata + JSONL without blocking sensor callbacks |
| **Independent reference receiver** | Supplies higher-quality position/speed/heading for evaluation only |
| **Passenger/test operator** | Marks experiment/scenario events, verifies mounting, starts/stops recording |
| **Optional laptop/logger** | Records experiment manifest and reference stream; never supplies hidden runtime aiding to the deployable phone path |
| **Offline evaluator** | Aligns reference and phone data, applies software GNSS masks where appropriate, computes metrics |
| **Replay harness** | Re-executes recorded canonical events through the same navigation engine once it exists |

---

## 3. Data Capture Independence

The purpose of the reference system is to **measure** IDR performance, not to assist it.

Rules:

- Reference/VBOX/RTK channels are never injected into deployable navigation inference.
- Reference data may initialize an **evaluation-mode** experiment only where the contract explicitly marks `initialization_mode = evaluation`.
- Deployable-mode benchmarks use only information the real phone would have.
- The phone recording remains valid even if the reference logger fails.
- The reference logger remains independent enough that one software bug cannot silently make estimate and ground truth identical.

---

## 4. Experiment IDs

Every run receives a unique experiment ID, for example:

```text
IDR_20260925_ROUTEA_MOUNT1_RUN03
```

Recommended metadata fields:

```text
experiment_id
date_time_utc
phone_model
android_version
app_version
git_sha
route_id
driver_id_or_anonymized_code
vehicle_id_or_anonymized_code
mount_id
mount_type
reference_device
reference_config
weather
road_surface
scenario_tags
planned_outage_intervals
notes
```

The experiment ID links:

- Android recording ID,
- reference log,
- manifest,
- generated masks,
- evaluation report,
- plots,
- model-evaluation output.

---

## 5. Timestamps / Clock Alignment

### Phone clock

Navigation integration uses the Android monotonic elapsed-realtime domain.

Preserve:

- `t_ns`,
- `received_ns`,
- boot/session identity.

### Reference clock

RTK/VBOX may expose GNSS/UTC time.

### Alignment requirement

Do not equate wall time and monotonic time by assumption.

Preferred alignment options:

1. shared physical trigger/event recorded by both systems,
2. GNSS UTC correspondence where justified,
3. cross-correlation only as a documented diagnostic/secondary alignment aid,
4. quantify residual offset uncertainty.

### Acceptance

Every evaluation report states:

- phone clock source,
- reference clock source,
- alignment method,
- estimated offset,
- estimated uncertainty,
- any drift correction.

---

## 6. Labels

Labels are evaluation metadata, not deployable features.

### Scenario labels

```text
GNSS_GOOD
GNSS_DEGRADED
GNSS_MASKED
PHYSICAL_GNSS_DENIED
RECOVERY
STATIONARY
STRAIGHT
TURN_LEFT
TURN_RIGHT
STOP_GO
LOW_SPEED
HIGH_VIBRATION
REMOUNT
```

### Ground-truth labels

Where available:

```text
reference_latitude
reference_longitude
reference_altitude
reference_speed
reference_heading
reference_distance
```

### Outage labels

Each outage interval records:

```text
start_time
end_time
type = physical | software_mask
reason / location
reference_distance_travelled
```

Software masking is valid for controlled evaluation only if the underlying phone recording had GNSS available and the runtime/replay harness removes it causally.

---

## 7. Scenario Metadata

Per run capture:

- phone placement/orientation,
- mount stiffness,
- whether phone moved during drive,
- vehicle type,
- road type,
- approximate speed range,
- route geometry,
- tunnel/parking/urban-canyon segments,
- stops,
- turns,
- bumps/potholes where safely encountered,
- GNSS conditions,
- reference health,
- sensor availability,
- thermal/battery state for long tests.

This prevents a successful run on one favorable setup from being generalized silently to all vehicles/mounts.

---

## 8. Ground-Truth Logger

The logger is the single source of experiment timing metadata.

It should record:

```text
experiment start/stop
reference receiver status
mount confirmed
calibration start/end
vehicle starts moving
outage/mask start/end
tunnel/parking entry/exit
remount event
reference degradation
operator notes
```

Where manual button presses are used, their timing uncertainty must be acknowledged.

Prefer automated markers generated from the data when they can be defined deterministically.

---

## 9. Storage Structure

Recommended:

```text
experiments/
└── <experiment_id>/
    ├── manifest.json
    ├── phone/
    │   ├── metadata.json
    │   └── measurements.jsonl
    ├── reference/
    │   ├── reference.csv
    │   └── metadata.json
    ├── labels/
    │   ├── events.jsonl
    │   └── outage_intervals.json
    ├── integrity/
    │   └── sha256.json
    ├── evaluation/
    │   ├── metrics.json
    │   └── plots/
    └── notes.md
```

Raw artifacts are immutable after capture.

Derived aligned/resampled files go into separate derived directories and never overwrite originals.

---

## 10. Core Experiment Families

### 10.1 Stationary

Purpose:

- gyro bias stability,
- accelerometer/gravity sanity,
- stationary detector,
- timestamp/rate validation.

Run:

- multiple phone orientations,
- engine off/on where practical,
- several durations.

### 10.2 Straight constant-speed

Purpose:

- forward-axis calibration,
- speed estimator,
- basic INS velocity propagation.

### 10.3 Acceleration / braking

Purpose:

- longitudinal dynamics,
- speed model response,
- jerk/vibration separation.

### 10.4 Left/right turns

Purpose:

- yaw-rate sign,
- attitude integration,
- heading recovery.

### 10.5 Stop–go / low speed

Purpose:

- stationary transitions,
- GNSS bearing null/instability,
- parking-like behavior.

### 10.6 Rough-road / vibration

Purpose:

- AI vibration filtering,
- sensor-noise robustness.

No deliberate unsafe driving is needed; use naturally occurring legal road conditions.

### 10.7 Mount variation

Examples:

- portrait mount,
- landscape mount,
- small tilt changes,
- different fixed mounts.

Purpose: validate phone-to-vehicle calibration.

### 10.8 Controlled remount

Performed while stopped.

Purpose:

- detect orientation discontinuity,
- invalidate calibration,
- require recalibration.

### 10.9 GNSS software-masked outage

Use a fully recorded ground-truthed drive.

Replay removes GNSS updates for predetermined intervals.

Advantages:

- repeatable,
- safe,
- identical ground truth across algorithm versions,
- ideal for regression testing.

### 10.10 Physical GNSS-loss environment

Examples:

- legal public tunnel,
- covered parking area,
- naturally obstructed urban canyon.

Do **not** use a jammer or illegal interference source.

Purpose:

- verify real Android provider behavior,
- measure stale/denied transition,
- validate recovery.

### 10.11 Recovery

Ensure each outage experiment contains enough open-sky driving afterward to evaluate:

- first accepted fix,
- innovation,
- position correction,
- convergence time,
- confidence contraction.

---

## 11. Calibration-Specific Protocol

Suggested run:

1. start stationary,
2. verify sensor rates,
3. collect static leveling window,
4. drive straight with sufficient speed/excitation,
5. compute dynamic yaw/forward alignment,
6. record calibration ID,
7. drive turns/stop-go,
8. stop and optionally remount,
9. confirm old calibration invalidates.

Ground truth may be used to validate calibration error but not to secretly generate deployable orientation.

---

## 12. Reference Quality

A "ground-truth" receiver can itself fail.

The evaluator must track:

- RTK fix mode,
- DOP/accuracy,
- satellite/reference status,
- solution age,
- gaps.

Intervals with invalid reference are excluded or marked, not silently interpolated into "truth."

---

## 13. Experiment Reproducibility

Every experiment must be reproducible from:

- manifest,
- phone recording,
- reference recording,
- software/Git SHA,
- preprocessing version,
- mask definition,
- evaluation script.

The same recorded experiment should be usable for:

- classical baseline,
- AI model evaluation,
- EKF tuning,
- map matching,
- regression tests.

---

## 14. Separation From IO-VNBD

IO-VNBD remains valuable research/reference data.

This lab exists because final Android claims require data whose:

- device frame is known,
- acquisition contract is controlled by this project,
- mount metadata is known,
- reference and clock alignment are documented.

Do not rewrite IO-VNBD history; treat this as a complementary final-validation source.

---

## 15. Minimum Final Validation Set

Before final claims, collect enough independent runs to cover:

- more than one route,
- multiple outage durations/distances,
- straight and turning motion,
- at least two mounting orientations,
- recovery segments,
- at least one naturally GNSS-degraded/denied environment if feasible,
- held-out runs not used for tuning.

Exact counts are chosen after pilot collection shows the variability; do not declare statistical robustness from one successful tunnel run.

---

**No navigation algorithm, ML feature, or model has been designed here. This document defines only how trustworthy experimental data and repeatable outage scenarios are produced.**
