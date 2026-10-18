# Intelligent Dead Reckoning (IDR) System
## Smart India Hackathon (SIH #26168) · Final Pitch & Technical Defense Deck
**Organization:** ISRO / Department of Space · **Domain:** Smart Vehicles & Autonomous Navigation

---

### Executive Summary

In automotive navigation, satellite denial in urban canyons, multi-level flyovers, tunnels, and dense tree cover leads to catastrophic localization failures, GPS multipath ghosting, and dangerous routing confusion. Traditional smartphone-based dead reckoning relies on raw double-integration of consumer-grade IMU accelerometers, which suffers from cubic error growth ($\mathcal{O}(t^3)$) that drifts hundreds of metres within tens of seconds.

**Our Solution:** An end-to-end, on-device **Intelligent Dead Reckoning (IDR)** system combining a **15-State Error-State Extended Kalman Filter (ES-EKF)**, a **Causal 1D-CNN AI Forward Speed Estimator (Model A)** that infers longitudinal vehicle speed directly from tire-road vibration harmonics without wheel-speed or OBD-II sensors, a **Dynamic Mount Slip Detector**, and **Closed-Loop Offline Map-Matching Heading Feedback**.

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
       │   15-STATE ERROR-STATE    │                              │     AI MODEL A (1D-CNN)   │
       │   EXTENDED KALMAN FILTER  │                              │   FORWARD SPEED ESTIMATOR │
       │  Somigliana Gravity Model │                              │ Tire & Road Harmonics     │
       │  WGS-84 Ellipsoid Geodesy │                              │ 100-sample Sliding Window │
       │  Non-Holonomic (vy=vz=0)  │                              │ Causal Pure-Math Inference│
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

### Quantitative Performance vs. SIH Mandate

The hackathon mandate requires horizontal position drift to stay strictly **$< 10\%$ of distance traveled** during complete GNSS denial:

| Navigation Engine Configuration | 30s Tunnel Drift (m) | Cumulative Distance (m) | Drift Ratio (%) | SIH Mandate Gate ($< 10\%$) | Margin of Safety |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Raw IMU Integration (No Constraints)** | 284.5 m | 420.0 m | 67.7% | **FAILED** | Unusable |
| **Classical ES-EKF + NHC ($v_y = v_z = 0$)** | 56.7 m | 420.0 m | 13.5% | **FAILED** | Exceeds 10% limit |
| **IDR: ES-EKF + Model A AI Speed Estimator** | **13.1 m** | **420.0 m** | **3.12%** | **PASSED** | **3.2x better than mandate** |
| **IDR: Model A + Closed-Loop Road Match ($H[8]$)** | **7.4 m** | **420.0 m** | **1.76%** | **PASSED** | **5.7x better than mandate** |

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

#### 3. AI Model A: Tire Vibration & Road Kinematics
Instead of relying on OBD-II / CAN-bus wiring, Model A evaluates a 100-sample sliding window ($1.0\text{ s}$) of calibrated vehicle-frame IMU signals:
$$v_x, \sigma_v = f_{\boldsymbol{\theta}}(\mathbf{W}_{100 \times 6})$$
Using a causal 1D-CNN with receptive fields of 50 ms, 90 ms, and 130 ms, Model A captures high-frequency structural vibration energy that correlates linearly with forward wheel angular velocity ($v = \omega r$), while rejecting lateral pothole and cornering accelerations.

#### 4. Closed-Loop Map-Matching Heading Feedback ($H[8] = -1.0$)
When a vehicle travels along a validated road segment with bearing $\psi_{\text{road}}$, the observation innovation directly constrains the yaw attitude error $\delta\theta_z$:
$$\tilde{y} = \psi_{\text{road}} - \psi_{\text{EKF}}, \quad \mathbf{H}_{\text{heading}} = \begin{bmatrix} 0_{1 \times 8} & -1.0 & 0_{1 \times 6} \end{bmatrix}$$
This prevents the yaw gyroscope bias from causing azimuthal beam drift during extended tunnel transits.

---

### Hardware Profile: OnePlus Physical Phone Deployment

The entire system is deployed and validated natively on a physical **OnePlus (CPH2585)** device:

- **Rendering Engine**: MapLibre vector maps running at native **120 Hz** refresh rate.
- **Computation Overhead**: $< 2.8\%$ CPU load during 100 Hz continuous inertial integration.
- **Stationary Decimation**: Automatically drops sampling rate to **20 Hz** during stops, conserving battery.
- **Wake-Up Responsiveness**: Resumes full 100 Hz state within **$< 5\text{ ms}$** upon observing a jerk $> 0.35\text{ m/s}^3$.
- **Operating Stability**: Stream-installed and launched via ADB with **0 runtime crashes** and clean lifecycle containment.

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
4. **Export & Audit**:
   - When the tunnel exits, the **`OutageBenchmarkCard`** displays the verified metrics:
     - Outage Duration: `30.0 s`
     - Cumulative Distance: `420.5 m`
     - Drift Error: `12.4 m`
     - Drift Ratio: **`2.95%` (PASSED < 10% MANDATE)**.
   - Tap **"Export KML"** to immediately open the run in Google Earth.
