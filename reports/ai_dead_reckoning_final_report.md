# Intelligent Dead Reckoning: Prototype & Evaluation Report (corrected)

**Smart India Hackathon #26168**
*AI-ML based Intelligent Dead Reckoning system for seamless navigation — ISRO / Department of Space*

---

## 1. Executive Summary

An offline Android app and edge engine that tracks a vehicle through GNSS outages using the phone IMU: a
15-state error-state EKF with zero-velocity and non-holonomic constraints, automatic mount calibration, a
learned outage speed correction (Model A v3), offline map display and road matching. It needs no OBD-II, wheel
sensor or network permission.

**Status against the PS #26168 benchmark (drift under 10 % of distance in an outage): not met.** This report
replaces an earlier version whose headline numbers (1.44 % drift, 24x margin, 0.28 ms latency, 417 tests)
were typed in rather than measured; the correction is recorded in `reports/gap_closure_log.md`.

| Requirement | Evidence | Status |
| :--- | :--- | :--- |
| No OBD-II / CAN-bus / wheel sensor | Engine inputs are IMU and GNSS only | Met by design |
| Dead-reckoning drift under 10 % | IO-VNBD, vehicle-frame IMU, best arm: median 23 % (30 s), 24 % (60 s), 26 % (1 km); held-out speed model 12 % (30 s) | **Not met** |
| Phone IMU in a vehicle | No phone drive has been scored | **Not evaluated** |
| AI speed estimation | v1 (synthetic) fails on real drives; v3 lowers held-out speed RMSE from 4.3-4.8 to 2.6 m/s | Partly met, external-IMU input only |
| Auto mount calibration | Implemented; the in-motion calibrator does not reach VALID on 10 Hz IO-VNBD data | Partly met, unverified in a vehicle |
| 10 Hz output | Host replay measures about 10 Hz | Met on host, not on device |
| Offline map and matching | HMM matcher with cross-track feedback scored on synthetic motion over real Hyderabad roads: median drift 4-5 % against about 21 % with no map; not a recorded drive | Implemented; not verified in a vehicle |
| Verification | Host suites pass (Python, mobile JVM, edge); on-device suite 34 of 43 passed | See the log |

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

    subgraph AI_Engine ["Model A: AI Speed & Motion Filter"]
        RING["100-Sample Sliding Ring Buffer\n(1.0s @ 100 Hz in Vehicle Frame)"]
        CNN["Causal 1D Convolutional Neural Network\n(Kernel 3, Dilations 1, 2, 4)"]
        STAT["Statistical Feature Extractor\n(Mean, Var, Energy, RMS)"]
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

Full generated tables: `reports/claims.md`. Raw results: `reports/iovnbd_eval/`, `reports/speed_v3_eval.json`,
`reports/synthetic_outage_measured.json`.

### 3.1 Scripted synthetic drive (a straight cruise with one turn; host JVM)

Measured by `SyntheticOutageMeasurementTest` over a 20 s outage (240 m), error taken at the last state before the
first returning GNSS fix:

| Arm | End error (m) | Drift (%) |
| :--- | ---: | ---: |
| Classical INS (one alignment fix, then no GNSS) | 310.9 | 129.5 |
| Fusion, no constraints | 73.7 | 30.7 |
| Fusion + constraints | 73.7 | 30.7 |
| Fusion + constraints + Model A v1 | 73.7 | 30.7 |

The synthetic-trained Model A v1 adds nothing on this scenario. An earlier version of the test reported 1.44 %
because its window included the state that already contained the first recovery fix.

### 3.2 IO-VNBD (real drives, VBOX reference)

Median outage drift, vehicle-frame inertial input (share of outages under 10 % in brackets):

| Arm | 30 s | 60 s | 1 km |
| :--- | ---: | ---: | ---: |
| INS only | 43 % (8%) | 84 % (3%) | 112 % (2%) |
| EKF + NHC/ZUPT | 42 % (6%) | 84 % (3%) | 123 % (3%) |
| EKF + NHC/ZUPT + v3 (in-sample weights) | 23 % (20%) | 24 % (17%) | 26 % (17%) |
| Hold last GNSS velocity | 53 % (6%) | 71 % (3%) | 69 % (2%) |

With the phone IMU export instead, every arm is worse (the export is not a usable inertial signal; see the log).
Why the v3 gain matters: the engine's speed error is the integral of the acceleration channel's error, which the
learned correction reduces; the engine's heading is already good (4 to 7 degrees) on this input.

---

## 4. Components

- **Synthetic sample drives** (`tools/generate_sample_drive.py`, `generate_multi_scenarios.py`): scripted, labelled
  as such; used for UI replay and host tests.
- **Model A v1** (`training/speed_estimator.py`, `AiSpeedEstimator.kt`): causal 1D-CNN trained on synthetic data.
  Bundled in the app; fails on real drives (leave-one-drive-out RMSE 62 to 97 m/s).
- **Model A v3** (`training/speed_v3.py`, `OutageSpeedCorrection.kt`): learned correction of integrated
  acceleration, anchored at the last GNSS fix. Off by default; needs a vehicle-frame inertial input.
- **15-state ES-EKF** (`GnssInsEkf.kt`) with ZUPT, NHC, forward-speed and heading rows, and Joseph-form updates.
- **Mount calibration and slip detection** (`MountEstimator.kt`, `MountSlipDetector.kt`).
- **Map matching** (`HmmMapMatcher.kt`): online fixed-lag HMM matcher with a cross-track position row in the EKF (off by default); the greedy `MapMatcher.kt` feeds a heading row. Scored on synthetic motion over the real Hyderabad road graph (`reports/map_matching_eval.json`); no recorded drive yet.
- **Adaptive rate decimation:** implemented; its power saving is not measured.
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
