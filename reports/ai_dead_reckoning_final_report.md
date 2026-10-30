# Intelligent Dead Reckoning: Prototype & Evaluation Report (corrected)

**Smart India Hackathon #26168**
*AI-ML based Intelligent Dead Reckoning system for seamless navigation — ISRO / Department of Space*

---

## 1. Executive Summary

An offline Android app and edge engine that tracks a vehicle through GNSS outages using the phone IMU: a
15-state error-state EKF with zero-velocity and non-holonomic constraints, automatic mount calibration, a
learned outage speed correction (Model A v3), offline map display and road matching. It needs no OBD-II, wheel
sensor or network permission.

**Status against the PS #26168 benchmark (drift under 10 % of distance in an outage): <!--CV:verdict_word-->not met<!--/CV-->.** This report
replaces an earlier version whose headline numbers (1.44 % drift, 24x margin, 0.28 ms latency, 417 tests)
were typed in rather than measured; the correction is recorded in `reports/gap_closure_log.md`.

<!-- CLAIMS:status_table:BEGIN -->
| Requirement | Evidence | Status |
| :--- | :--- | :--- |
| No OBD-II / CAN-bus / wheel sensor | Engine inputs are IMU and GNSS only | Met by design |
| Dead-reckoning drift under 10 % | IO-VNBD, vehicle-frame IMU, held-out drives, best arm: median 22 % (30 s), 24 % (60 s), 17 % (1 km); holding the last GNSS velocity gives 60 %, 79 %, 62 % | **Not met** |
| Phone IMU in a vehicle | No phone drive has been scored | **Not evaluated** |
| AI speed estimation | v1 (synthetic) fails on real drives; v3 lowers leave-one-drive-out speed RMSE from 4.273 (integrated acceleration) and 4.785 (hold last speed) to 2.554 m/s | Partly met, external-IMU input only |
| Auto mount calibration | Implemented; the in-motion calibrator does not reach VALID on 10 Hz IO-VNBD data; a saved mount lets a drive start at the first accurate fix (synthetic replay, host only) | Partly met, unverified in a vehicle |
| 10 Hz output | Publication interval is 100 ms; host replay only | Met on host, not on device |
| Offline map and matching | HMM matcher with cross-track feedback on synthetic motion over real Hyderabad roads (30 s outages): median 4-7 % against 14-15 % with no map; not a recorded drive; the in-app switch is experimental and off by default | Implemented; not verified in a vehicle |
| Edge engine, external IMU at 200 Hz | SYNTHETIC drive: 12 m RMSE during a 20 s outage, 26 m final, 200.01 Hz paired processing, 0 drops; no real FOG hardware | Partly met (synthetic only) |
| Verification | Host suites pass (Python, mobile JVM, edge); on-device suite had failures on its first run and needs the phone | See the log |
<!-- CLAIMS:status_table:END -->

---

## 2. End-to-End System Architecture

```mermaid
flowchart TD
    subgraph Sensors ["Raw Smartphone Sensors (200 Hz)"]
        ACC[Tri-axial Accelerometer]
        GYR[Tri-axial Gyroscope]
        GPS[Consumer GNSS Fixes (1 Hz)]
    end

    subgraph Calibration ["In-Cabin Auto-Calibration Stage"]
        GRAV["Gravity Vector Estimation\n(Tilt / Leveling Quaternion)"]
        PCA["Longitudinal Acceleration Discovery\n(Forward Axis Alignment)"]
        QCAL["q_vehicle_from_device"]
    end

    subgraph AI_Engine ["Model A v1 (synthetic-trained vibration CNN, not validated on real drives) and outage speed correction v3"]
        RING["100-Sample Sliding Ring Buffer\n(1.0s @ 100 Hz in Vehicle Frame)"]
        CNN["Causal 1D Convolutional Neural Network\n(Kernel 3, Dilations 1, 2, 4)"]
        STAT["Statistical Feature Extractor\n(Mean, Var, Energy, RMS)"]
        V3["Outage speed correction v3
(MLP on 9 causal features at 10 Hz,
vehicle-frame inertial input)"]
        HEADS["Multi-Head Output:\n1. Forward Speed v_x\n2. Uncertainty sigma_v\n3. Stillness Probability p_stop"]
    end

    subgraph EKF ["15-State Error-State Kalman Filter (ES-EKF)"]
        STATE["State Vector (15):\nPosition [x, y, z] ENU\nVelocity [vx, vy, vz] ENU\nOrientation [roll, pitch, yaw]\nGyro Biases [bx, by, bz]\nAccel Biases [bax, bay, baz]"]
        PROP["High-rate Mechanization (200 Hz)"]
        ZUPT["Zero Velocity Updates (ZUPT)\nwhen p_stop > 0.85"]
        NHC["Non-Holonomic Constraints (NHC)\nvy_vehicle ≈ 0, vz_vehicle ≈ 0"]
        AI_UPD["Forward Speed Pseudo-Measurement\nvx_vehicle = v_hat_x (10 Hz in outage)"]
        GNSS_UPD["GNSS PVT Innovation Updates\n(when fixes available)"]
    end

    subgraph Navigation ["Offline Navigation & Display"]
        SNAP["Road Graph Vector Snapper"]
        UI["MapLibre 100% Offline Vector Map\nSmooth 10 Hz Driver Display"]
    end

    ACC --> GRAV
    GYR --> GRAV
    GPS --> PCA
    GRAV --> QCAL
    PCA --> QCAL

    ACC --> PROP
    GYR --> PROP
    QCAL --> RING
    ACC --> RING
    GYR --> RING

    RING --> CNN
    RING --> STAT
    CNN --> HEADS
    STAT --> HEADS

    HEADS --> ZUPT
    HEADS --> AI_UPD
    V3 --> AI_UPD
    GPS --> GNSS_UPD

    PROP --> STATE
    ZUPT --> STATE
    NHC --> STATE
    AI_UPD --> STATE
    GNSS_UPD --> STATE

    STATE --> SNAP
    SNAP --> UI
```

---

## 3. Measured results

Everything between the markers below is generated from files in `reports/` by `tools/build_claims.py` (the same text is in
`reports/claims.md` and the README). Synthetic, IO-VNBD (real drives with a VBOX reference) and field results are labelled
as such; no field drive and no phone-in-vehicle drive has been scored.

<!-- CLAIMS:results:BEGIN -->
Every number here is read from a file in `reports/` by `tools/build_claims.py`. Nothing is on a device or in a vehicle yet. IO-VNBD GNSS is the VBOX receiver at 1 Hz (the phone logged GPS only about every 9 s).

**IO-VNBD, vehicle-frame inertial input (external-IMU case)** (11 IO-VNBD drives, 13.9 h, 462 km). Median drift as a percentage of distance travelled in the outage, and the share of outages under the 10 % gate. GNSS-aided error while fixes are available: EKF + NHC/ZUPT 1.39 m median, App configuration 0.92 m median.

| Arm | 50 m outage | 30 s outage | 60 s outage | 1 km outage |
|---|---:|---:|---:|---:|
| INS only (no constraints) | 13 % (37%) | 43 % (8%) | 84 % (3%) | 112 % (2%) |
| EKF + NHC/ZUPT | 14 % (33%) | 42 % (6%) | 84 % (3%) | 123 % (3%) |
| EKF + NHC/ZUPT + Model A v1 | 14 % (33%) | 44 % (7%) | 223 % (1%) | 420 % (3%) |
| EKF + NHC/ZUPT + speed correction v3 (in-sample weights) | 14 % (37%) | 23 % (20%) | 24 % (17%) | 26 % (17%) |
| EKF + NHC/ZUPT + speed correction v3 (held-out weights) | 13 % (36%) | 24 % (17%) | 24 % (16%) | 27 % (12%) |
| EKF + NHC/ZUPT + tight GNSS velocity (0.2 m/s) | 7 % (64%) | 38 % (6%) | 81 % (2%) | 115 % (3%) |
| EKF + NHC/ZUPT + v3 (held-out) + tight GNSS velocity | 7 % (65%) | 21 % (18%) | 23 % (16%) | 24 % (14%) |
| EKF + NHC through outages + v3 (held-out) + tight GNSS velocity | 7 % (64%) | 18 % (24%) | 19 % (22%) | 20 % (20%) |
| EKF + NHC through outages + v3 (held-out) + tight GNSS velocity + ZUPT engaging below 4 m/s | 7 % (63%) | 17 % (26%) | 17 % (27%) | 20 % (20%) |
| EKF + NHC through outages + v3 (held-out) + tight GNSS velocity + learned GNSS trust (held-out weights) | 7 % (64%) | 19 % (26%) | 17 % (26%) | 19 % (19%) |
| App configuration | 32 % (21%) | 767 % (0%) | 1169 % (0%) | 1407 % (1%) |
| Baseline: hold last GNSS velocity | 18 % (39%) | 53 % (6%) | 71 % (3%) | 69 % (2%) |

_The in-sample v3 row uses weights trained on these same drives (optimistic). The held-out row retrains without the drive being run, so it is the one to quote._

**IO-VNBD, phone IMU export** (13 IO-VNBD drives, 13.2 h, 444 km). Median drift as a percentage of distance travelled in the outage, and the share of outages under the 10 % gate. GNSS-aided error while fixes are available: EKF + NHC/ZUPT 1.81 m median, App configuration 0.88 m median.

| Arm | 50 m outage | 30 s outage | 60 s outage | 1 km outage |
|---|---:|---:|---:|---:|
| INS only (no constraints) | 19 % (26%) | 70 % (2%) | 133 % (1%) | 183 % (2%) |
| EKF + NHC/ZUPT | 19 % (26%) | 75 % (3%) | 125 % (1%) | 177 % (2%) |
| EKF + NHC/ZUPT + Model A v1 | 19 % (24%) | 75 % (3%) | 129 % (1%) | 409 % (3%) |
| App configuration | 28 % (25%) | 771 % (1%) | 1944 % (0%) | 2292 % (2%) |
| Baseline: hold last GNSS velocity | 16 % (41%) | 49 % (7%) | 68 % (4%) | 70 % (2%) |

**Held-out check of the 10 % target** (IO-VNBD, vehicle-frame input). The drives were split before any held-out result was read: development = m, s2, s4, vta16, vta8, y1; held-out = s1, s3c, vfa01, vta2, vw3. Earlier choices (0.2 m/s GNSS velocity weighting, NHC through outages) were made on all drives, so the held-out set is fresh only for the ZUPT change. Median drift (share of outages under 10 %):

| Drives | Outage | Hold last GNSS | Best arm | Best arm + ZUPT engaging below 4 m/s |
|---|---|---:|---:|---:|
| development | 50 m outage | 18.7 % (38%) | 7.5 % (62%) | 7.5 % (63%) |
| development | 30 s outage | 50.0 % (8%) | 18.1 % (26%) | 16.9 % (26%) |
| development | 60 s outage | 68.6 % (2%) | 17.9 % (23%) | 15.9 % (28%) |
| development | 1 km outage | 68.9 % (2%) | 20.5 % (20%) | 20.8 % (20%) |
| held-out | 50 m outage | 13.0 % (41%) | 5.3 % (68%) | 5.8 % (64%) |
| held-out | 30 s outage | 59.6 % (2%) | 21.6 % (18%) | 17.3 % (26%) |
| held-out | 60 s outage | 78.7 % (4%) | 24.0 % (17%) | 23.0 % (25%) |
| held-out | 1 km outage | 62.1 % (3%) | 16.7 % (19%) | 16.7 % (19%) |

Why: on the development drives the speed error is the largest part of the drift (median share of the squared error 30 s outage 0.83, 60 s outage 0.79, 1 km outage 0.80), and the heading part is about 5.2-6.5 % of the distance. Held-out median drift by whether the vehicle stops during the outage:

| Outage | Outages with a stop | Median drift, with a stop | Median drift, no stop |
|---|---:|---:|---:|
| 30 s outage | 15 of 51 | 25.4 % | 19.5 % |
| 60 s outage | 21 of 44 | 34.5 % | 22.8 % |
| 1 km outage | 16 of 34 | 21.8 % | 18.2 % |

Even outages with no stop drift well above 10 %, so better stop detection alone would not meet the target; the remaining error is the outage-to-outage spread of the speed estimate.

**Speed model v3** (learned correction of integrated acceleration, vehicle-frame inertial input, leave-one-drive-out over 11 drives):

| | Hold last speed | Integrated acceleration | Learned v3 |
|---|---:|---:|---:|
| Speed RMSE, all horizons (m/s) | 4.785 | 4.273 | 2.554 |
| Median drift over 30 s outages (%) | 22.07 | 17.8 | 12.0 |
| Median drift over 60 s outages (%) | 29.24 | 29.61 | 17.36 |
| Outages under 10 % drift, 30 s | 27% | 30% | 42% |
| Outages under 10 % drift, 60 s | 14% | 14% | 25% |

**Sustained circles at a swept yaw rate**: SYNTHETIC vehicle-frame circle sweep: 14 m/s, 30 s straight then a sustained circle at the stated yaw rate (negative = right), GNSS withheld 35-65 s, heading supplied, declared zero accelerometer bias, noisy fixes (2 m). Worst horizontal error during the outage, metres; negative rates are right turns:

| Configuration | -0.7 rad/s | -0.3 rad/s | +0.2 rad/s | +0.3 rad/s | +0.4 rad/s | +0.5 rad/s | +0.6 rad/s | +0.7 rad/s | +0.8 rad/s | +1.0 rad/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Default | 44 | 191 | 260 | 262 | 200 | 50 | 49 | 48 | 47 | 45 |
| GNSS velocity weighting 0.2 m/s | 3 | 6 | 4 | 3 | 4 | 6 | 7 | 7 | 8 | 8 |
| NHC yaw-rate gate raised to 2.0 rad/s | 41 | 191 | 260 | 262 | 200 | 37 | 22 | 9 | 6 | 8 |
| Both | 7 | 6 | 4 | 3 | 4 | 4 | 3 | 3 | 3 | 3 |

_No flag was added: both settings already existed and stay at the old behaviour by default._

**Map matching on real Hyderabad road geometry** (10 routes from the shipped OSM graph). Simulated vehicle at 12 m/s (constant speed) on routes built from the shipped real Hyderabad OSM road graph; truth is the centreline with rounded corners shifted right by the lane offset of each condition; synthetic sensors with declared noise and biases; 1 Hz GNSS with 3 m noise withheld for a 30 s and a 60 s outage; three noise seeds. Median drift as a percentage of outage distance (share of outages under 10 %); `lane6m` puts the true lane 6 m from the mapped centreline.

| Arm | lane1.5m: 30 s | lane1.5m: 60 s | lane6m: 30 s | lane6m: 60 s |
|---|---:|---:|---:|---:|
| Constraints only (no map) | 13.6 % (33%) | 33.1 % (3%) | 15.1 % (37%) | 31.6 % (7%) |
| Greedy matcher, heading feedback | 12.4 % (37%) | 32.1 % (3%) | 11.9 % (37%) | 31.2 % (7%) |
| HMM matcher, heading feedback | 12.0 % (40%) | 31.8 % (3%) | 11.8 % (40%) | 31.4 % (7%) |
| HMM matcher, heading + cross-track | 4.0 % (90%) | 4.6 % (67%) | 6.7 % (67%) | 4.1 % (63%) |
| HMM matcher, cross-track only | 4.9 % (90%) | 5.1 % (67%) | 7.6 % (67%) | 5.3 % (60%) |

_Synthetic motion at constant speed along real road geometry, not a recorded drive._

**Learned GNSS trust** (behind a flag, off by default; leave-one-drive-out, 6283 phone fixes): rank correlation with true fix error 0.28 (reported accuracy: 0.048); mean absolute error 2.487 m (reported accuracy: 2.638 m).

Engine-level paired comparison on IO-VNBD (the best arm with and without the trust weighting; weights trained without the drive being run; positive difference = trust helps; interval is a 95 % bootstrap of the mean paired difference in percentage points):

| Drives | Outage | Paired outages | Median drift without | with | Mean difference (95 % interval) |
|---|---|---:|---:|---:|---|
| development | 50 m outage | 176 | 7.3 % | 7.3 % | +2.04 (-0.30 to +6.02) |
| development | 30 s outage | 157 | 18.1 % | 18.1 % | +0.24 (-0.29 to +0.93) |
| development | 60 s outage | 140 | 17.3 % | 16.6 % | +0.47 (-0.06 to +1.09) |
| development | 1 km outage | 105 | 19.9 % | 19.9 % | -0.89 (-2.52 to +0.09) |
| held-out | 50 m outage | 54 | 5.4 % | 5.0 % | -0.86 (-2.66 to +0.45) |
| held-out | 30 s outage | 48 | 21.6 % | 21.2 % | +1.78 (-0.29 to +5.06) |
| held-out | 60 s outage | 39 | 23.9 % | 21.0 % | +2.09 (+0.16 to +5.23) * |
| held-out | 1 km outage | 30 | 18.1 % | 14.8 % | +0.95 (-0.28 to +2.49) |

1 of 8 intervals exclude zero (marked *). Verdict: no demonstrated gain; the flag stays off.

**Edge engine at 200 Hz (SYNTHETIC drive, host JVM, real-time paced)**: a scripted 70 s drive with one turn and a 20 s GNSS outage, tactical-grade noise stated in `tools/generate_fog_200hz.py`; not a recording and not real FOG hardware. Positions are scored against the generator's truth. A paired processing rate just under 200 Hz is pacing jitter on this laptop, not a drop (no samples were dropped).

| Run | Paired processing (Hz) | Ingress drops | RMSE all (m) | RMSE during outage (m) | Final error after outage (m) |
|---|---:|---:|---:|---:|---:|
| default profile, accelerometer bias undeclared | 200.01 | 0 | 430.1 | 201.6 | 381.1 |
| default profile, accelerometer bias declared zero | 199.99 | 0 | 9.5 | 16.4 | 29.3 |
| external-imu profile, bias undeclared | 200.02 | 0 | 6.5 | 12.0 | 26.0 |
| external-imu profile, bias declared zero | 200.01 | 0 | 6.4 | 11.8 | 25.8 |

_Declaring a zero accelerometer bias is an assumption (the generator adds a small real bias). With the bias undeclared and the legacy GNSS-velocity weighting, the default profile diverges; the cause and the fixes are in `reports/gap_closure_log.md` (T2)._

**Scripted synthetic drive** (70 s scripted drive: straight cruise at 12 m/s with one turn, 20 s GNSS outage, seeded noise and fixed sensor biases). Measured by the JVM test `SyntheticOutageMeasurementTest`; this is a straight cruise, so it says little about real roads.

| Arm | End error (m) | Drift (%) | Max error (m) |
|---|---:|---:|---:|
| Classical INS (one alignment fix, then no GNSS) | 310.87 | 129.53 | 310.87 |
| Classical fusion (GNSS+INS filter, no motion constraints) | 73.68 | 30.70 | 73.68 |
| Fusion + vehicle-motion constraints | 73.68 | 30.70 | 73.68 |
| Fusion + constraints + Model A v1 forward speed | 73.68 | 30.70 | 73.68 |
<!-- CLAIMS:results:END -->

---

## 4. Components

- **Synthetic sample drives** (`tools/generate_sample_drive.py`, `generate_multi_scenarios.py`): scripted, labelled
  as such; used for UI replay and host tests.
- **Model A v1** (`training/speed_estimator.py`, `AiSpeedEstimator.kt`): causal 1D-CNN trained on synthetic data.
  Bundled in the app; fails on real drives (`reports/model_a_real_data_eval.md`).
- **Model A v3** (`training/speed_v3.py`, `OutageSpeedCorrection.kt`): learned correction of integrated
  acceleration, anchored at the last GNSS fix. Off by default; needs a vehicle-frame inertial input.
- **15-state ES-EKF** (`GnssInsEkf.kt`) with ZUPT, NHC, forward-speed and heading rows, and Joseph-form updates.
- **Mount calibration and slip detection** (`MountEstimator.kt`, `MountSlipDetector.kt`).
- **Map matching** (`HmmMapMatcher.kt`): online fixed-lag HMM matcher with a cross-track position row in the EKF (off by default); the greedy `MapMatcher.kt` feeds a heading row. Scored on synthetic motion over the real Hyderabad road graph (`reports/map_matching_eval.json`); no recorded drive yet.
- **Adaptive rate decimation:** implemented; its power saving is not measured.
- **Saved mount calibration** (`SavedCalibration.kt`): the last valid mount per device lets a drive start at the first accurate
  fix; dropped if the live calibrator disagrees by more than 20 degrees. Tested on the host only.
- **Edge engine** (`edge/`): external-IMU adapter, `external-imu` profile (v3 correction, tight GNSS velocity weighting, NHC
  through outages), 200 Hz benchmark scored against a truth file (synthetic data only).
- **Optional engine settings, all off by default:** initial heading from the first fix's course, learned GNSS trust,
  GNSS velocity weighting, NHC through outages, a two-wheeler profile (values chosen, not measured) and an experimental
  in-app map-matching switch.
- **IO-VNBD evaluation harness** (`tools/iovnbd_to_contract.py`, `tools/iovnbd_outage_eval.py`, edge task
  `runIovnbdEval`): converts drives, withholds GNSS, replays through the engine, scores against VBOX.

---

## 5. How to run

```powershell
# Android JVM tests and edge tests (from the repository root)
cd mobile; bash gradlew testDebugUnitTest lintDebug assembleDebug --console=plain
bash gradlew -p ../edge test --console=plain
# Python tests and hygiene
cd ..; python -B -X utf8 -m unittest discover -s tests; python tools/check_repo_hygiene.py
# Regenerate the IO-VNBD results (needs data/raw/iovnbd; see data/README.md)
python tools/iovnbd_to_contract.py; python tools/iovnbd_outage_eval.py run --input vehicle
python tools/build_claims.py
```

Test counts are recorded with dates in `reports/gap_closure_log.md`. On-device tests need a connected phone.
