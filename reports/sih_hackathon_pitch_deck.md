# Intelligent Dead Reckoning (IDR) System
## Smart India Hackathon (SIH #26168) · Final Pitch & Technical Defense Deck
**Organization:** ISRO / Department of Space · **Domain:** Smart Vehicles & Autonomous Navigation

---

### Executive Summary

In automotive navigation, satellite denial in urban canyons, multi-level flyovers, tunnels, and dense tree cover leads to catastrophic localization failures, GPS multipath ghosting, and dangerous routing confusion. Traditional smartphone-based dead reckoning relies on raw double-integration of consumer-grade IMU accelerometers, which suffers from cubic error growth ($\mathcal{O}(t^3)$) that drifts hundreds of metres within tens of seconds.

**Our solution:** an on-device **Intelligent Dead Reckoning (IDR)** engine combining a **15-state error-state EKF (ES-EKF)** with zero-velocity and non-holonomic constraints, automatic mount calibration, a **learned outage speed correction (Model A v3)** that corrects integrated acceleration from the last GNSS speed, a mount-slip detector and offline map matching, with no wheel-speed or OBD-II input.

**Status:** the PS #26168 mandate (drift under 10% of distance in an outage) is **not yet met**. The best measured median drift is 23% to 26% on IO-VNBD with a vehicle-frame IMU; nothing has been scored on a phone in a vehicle. The earlier synthetic-trained Model A v1 (a 1D-CNN) fails on real drives, and figures this deck used to show came from a scripted synthetic drive and were not measured.

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
       │   Road Vector Projection  │  Heading Feedback (H[8]=-1)  │   < 5 ms Jerk Wake-Up     │
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

IO-VNBD drives with a VBOX reference, VBOX vehicle-frame yaw rate and acceleration as the inertial input, VBOX GNSS at 1 Hz, outages withheld from the engine. Median drift as a percentage of distance travelled in the outage, with the share of outages under the 10% gate in brackets. Generated from `reports/iovnbd_eval/vehicle/summary.json` (full tables in `reports/claims.md`).

| Configuration | 50 m | 30 s | 60 s | 1 km |
| :--- | :---: | :---: | :---: | :---: |
| INS only (no constraints) | 13% (37%) | 43% (8%) | 84% (3%) | 112% (2%) |
| ES-EKF + NHC/ZUPT | 14% (33%) | 42% (6%) | 84% (3%) | 123% (3%) |
| ES-EKF + NHC/ZUPT + speed correction v3 (weights trained on these drives) | 14% (37%) | 23% (20%) | 24% (17%) | 26% (17%) |
| Baseline: hold last GNSS velocity | 18% (39%) | 53% (6%) | 71% (3%) | 69% (2%) |

Held-out evidence for the speed correction (leave-one-drive-out): median drift 12% at 30 s and 17% at 60 s, against 18% to 29% for integrated acceleration and 22% to 29% for holding the last speed (`reports/speed_v3_eval.json`).

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

The debug app has been installed on a OnePlus CPH2585 (Android 16). The on-device suite ran once: 34 of 43 tests passed and 9 failed (not yet investigated). No drive has been recorded with this build. Rendering rate, CPU load, power saving of the standstill decimator and wake-up latency have **not been measured**.

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
   - The Evaluation tab shows the shipped scripted-drive report, which is generated from a measured run (`reports/synthetic_outage_measured.json`): about 31% drift for the fused arms and 130% for unaided INS. It says so; it is not a pass.
   - Tap **"Export KML"** to immediately open the run in Google Earth.
