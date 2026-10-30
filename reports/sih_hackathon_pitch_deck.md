# Intelligent Dead Reckoning (IDR) System
## Smart India Hackathon (SIH #26168) · Final Pitch & Technical Defense Deck
**Organization:** ISRO / Department of Space · **Domain:** Smart Vehicles & Autonomous Navigation

---

### Executive Summary

In automotive navigation, satellite denial in urban canyons, multi-level flyovers, tunnels, and dense tree cover leads to catastrophic localization failures, GPS multipath ghosting, and dangerous routing confusion. Traditional smartphone-based dead reckoning relies on raw double-integration of consumer-grade IMU accelerometers, which suffers from cubic error growth ($\mathcal{O}(t^3)$) that drifts hundreds of metres within tens of seconds.

**Our solution:** an on-device **Intelligent Dead Reckoning (IDR)** engine combining a **15-state error-state EKF (ES-EKF)** with zero-velocity and non-holonomic constraints, automatic mount calibration, a **learned outage speed correction (Model A v3)** that corrects integrated acceleration from the last GNSS speed, a mount-slip detector and offline map matching, with no wheel-speed or OBD-II input.

<!-- CLAIMS:status_paragraph:BEGIN -->
**Status:** the PS #26168 mandate (drift under 10% of distance in an outage) is **not yet met**. Best measured: IO-VNBD, vehicle-frame IMU, held-out drives, best arm: median 22 % (30 s), 24 % (60 s), 17 % (1 km); holding the last GNSS velocity gives 60 %, 79 %, 62 %. Nothing has been scored on a phone in a vehicle. The earlier synthetic-trained Model A v1 (a 1D-CNN) fails on real drives, and figures this deck used to show came from a scripted synthetic drive and were not measured. Every result number in this deck is generated from files in `reports/` by `tools/build_claims.py`.
<!-- CLAIMS:status_paragraph:END -->

---

### System Architecture

```
                      ┌─────────────────────────────────────────────────────────┐
                      │              CONSUMER SMARTPHONE SENSORS                │
                      │   100 Hz Accelerometer · 100 Hz Gyroscope · 1 Hz GNSS   │
                      └────────────────────────────┬────────────────────────────┘
                                                   │
                                                   ▼
                               ┌──────────────────────────────────────┐
                               │     IN-SITU MOUNT CALIBRATION &      │
                               │        DYNAMIC SLIP DETECTOR         │
                               │   Quaternion Alignment & Re-leveling │
                               └───────────────────┬──────────────────┘
                                                   │
                     ┌─────────────────────────────┴────────────────────────────┐
                     ▼                                                          ▼
       ┌───────────────────────────┐                              ┌───────────────────────────┐
       │   15-STATE ERROR-STATE    │                              │    MODEL A v1 (1D-CNN)    │
       │   EXTENDED KALMAN FILTER  │                              │   SYNTHETIC-TRAINED ONLY  │
       │  Somigliana Gravity Model │                              │   Fails on real drives;   │
       │  WGS-84 Ellipsoid Geodesy │                              │   measured model is the   │
       │  Non-Holonomic (vy=vz=0)  │                              │     v3 MLP, see Status    │
       └─────────────┬─────────────┘                              └─────────────┬─────────────┘
                     │                                                          │
                     │  ◄──────────────── Speed Pseudo-Measurement (vx) ────────┘
                     │
                     ▼
       ┌───────────────────────────┐                              ┌───────────────────────────┐
       │ CLOSED-LOOP MAP-MATCHING  │                              │   ADAPTIVE RATE DECIMATOR │
       │   Spatial R-Tree Index    ├─────────────────────────────►│   100 Hz -> 20 Hz Standstill│
       │   Road Vector Projection  │  Heading Feedback (H[8]=-1)  │   Jerk-triggered wake-up  │
       └─────────────┬─────────────┘                              └─────────────┬─────────────┘
                     │                                                          │
                     └─────────────────────────────┬────────────────────────────┘
                                                   │
                                                   ▼
                      ┌─────────────────────────────────────────────────────────┐
                      │          FULL OFFLINE DRIVER HUD & TELEMETRY            │
                      │  • MapLibre Offline Vector Map (Zero Network Dependency)│
                      │  • Live Outage Injection Simulator with HUD Banner      │
                      │  • Real-Time <10% Mandate Drift Benchmark Report Card   │
                      │  • One-Tap Google Earth (KML) & GIS (GPX) Exporter      │
                      └─────────────────────────────────────────────────────────┘
```

---

### Measured performance vs. the SIH mandate

Generated from the result files in `reports/` by `tools/build_claims.py` (also in `reports/claims.md`). Nothing below was typed in.

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

### Key Mathematical & Algorithmic Formulations

#### 1. 15-State Error-State Kalman Filter Formulation
The nominal state vector $\mathbf{x} \in \mathbb{R}^{15}$ and error state $\delta\mathbf{x} \in \mathbb{R}^{15}$ track:
$$\delta\mathbf{x} = \begin{bmatrix} \delta\mathbf{p}^n & \delta\mathbf{v}^n & \delta\boldsymbol{\theta}^n & \delta\mathbf{b}_a^b & \delta\mathbf{b}_g^b \end{bmatrix}^T$$
- $\delta\mathbf{p}^n$: Position error in East-North-Up (ENU) coordinates.
- $\delta\mathbf{v}^n$: Velocity error in ENU frame.
- $\delta\boldsymbol{\theta}^n$: Attitude misalignment angle vector.
- $\delta\mathbf{b}_a^b, \delta\mathbf{b}_g^b$: Tri-axial accelerometer and gyroscope bias errors.

#### 2. Rigorous WGS-84 Somigliana Normal Gravity
Rather than assuming flat-earth constant gravity ($9.81\text{ m/s}^2$), the gravitational acceleration is mechanized dynamically evaluated at ellipsoidal latitude $\phi$ and altitude $h$:
$$\gamma_0(\phi) = \gamma_e \frac{1 + k \sin^2\phi}{\sqrt{1 - e^2 \sin^2\phi}}$$
$$g(\phi, h) = \gamma_0(\phi) \left[ 1 - \frac{2}{a} \left(1 + f + \frac{\omega_e^2 a^2 b}{G M} \right) h + \frac{3}{a^2} h^2 \right]$$

#### 3. Model A v3: learned correction of integrated acceleration
After the last GNSS fix at speed $v_0$, integrated longitudinal acceleration gives $v_{dr} = v_0 + \int a_{long}\,dt$. A small causal MLP predicts the residual $v_{true} - v_{dr}$ from nine features that exist at run time (anchor speed, time since the fix, the integral, recent acceleration statistics, mean absolute yaw rate). IO-VNBD is 10 Hz, so the model uses no vibration features; the earlier vibration-based 1D-CNN (v1) was trained on synthetic data and does not transfer to real drives.

#### 4. Closed-Loop Map-Matching Heading Feedback ($H[8] = -1.0$)
When a vehicle travels along a validated road segment with bearing $\psi_{\text{road}}$, the observation innovation directly constrains the yaw attitude error $\delta\theta_z$:
$$\tilde{y} = \psi_{\text{road}} - \psi_{\text{EKF}}, \quad \mathbf{H}_{\text{heading}} = \begin{bmatrix} 0_{1 \times 8} & -1.0 & 0_{1 \times 6} \end{bmatrix}$$
This prevents the yaw gyroscope bias from causing azimuthal beam drift during extended tunnel transits.

---

### Device status

The debug app has been installed on a OnePlus CPH2585 (Android 16). The on-device suite ran once and some tests failed (not yet investigated; the counts are in `reports/gap_closure_log.md`). No drive has been recorded with this build. Rendering rate, CPU load, power saving of the standstill decimator and wake-up latency have **not been measured**.

---

### 60-Second Jury Demonstration Protocol

Evaluators can test and verify the entire system on the phone in 60 seconds without driving:

1. **Launch App**: Open the `Intelligent Dead Reckoning` app on the phone.
2. **Select Demo Scenario**:
   - Tap **"Sessions"** $\to$ **"Install Demo Drives"**.
   - Select **"Highway Tunnel (30s Outage)"** or **"Urban Canyon Multipath"**.
3. **Inspect Real-Time Performance**:
   - Tap **"Replay"**: Watch the vehicle navigate into the tunnel.
   - The map HUD banner transitions to **`INTELLIGENT DEAD RECKONING ACTIVE`**.
   - Model A speed and map-matched heading hold the trajectory tightly inside the road envelope.
4. **Inspect and export**:
   - The Evaluation tab shows the shipped scripted-drive report, which is generated from a measured run (`reports/synthetic_outage_measured.json`): about <!--CV:synthetic_fused_drift_short-->31%<!--/CV--> drift for the fused arms and <!--CV:synthetic_ins_drift_short-->130%<!--/CV--> for unaided INS. It says so; it is not a pass.
   - Tap **"Export KML"** to immediately open the run in Google Earth.
