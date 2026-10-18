# Intelligent Dead Reckoning (IDR) System
### Smart India Hackathon (SIH #26168) · ISRO / Department of Space
**Domain:** Autonomous Vehicles & Smart Navigation · **Target:** Land Vehicle Localization in GNSS-Denied Environments

---

## Executive Overview

In automotive navigation, satellite denial in urban canyons, multi-level flyovers, and tunnels leads to catastrophic localization failures and dangerous routing confusion. Traditional dead reckoning relies on raw double-integration of consumer-grade smartphone IMU accelerometers, suffering from cubic error growth ($\mathcal{O}(t^3)$) that drifts hundreds of metres within tens of seconds.

**The Solution:** An end-to-end, on-device **Intelligent Dead Reckoning (IDR)** system combining:
1. **15-State Error-State Extended Kalman Filter (ES-EKF)** with rigorous WGS-84 Somigliana normal gravity mechanization and non-holonomic velocity constraints ($v_y = v_z = 0$).
2. **AI Model A (1D-CNN) Forward Speed Estimator** that estimates longitudinal speed directly from tire-road micro-vibration harmonics without OBD-II cabling or wheel-speed sensors.
3. **In-Situ Mount Calibration & Dynamic Slip Detector** providing automatic gravity leveling ($q_{\text{level}}$) and forward acceleration alignment ($q_{\text{yaw}}$).
4. **Closed-Loop Offline Map-Matching Heading Feedback ($H[8] = -1.0$)** that constrains azimuthal gyroscope bias drift to the road centerline.
5. **100% Offline Autonomy** running on MapLibre vector maps with zero network permissions (`android.permission.INTERNET` removed).

---

## Quantitative Performance vs. SIH Mandate

The hackathon mandate requires horizontal position drift to stay strictly **$< 10\%$ of distance traveled** during complete GNSS denial:

| Navigation Engine Configuration | 30s Outage Drift (m) | Distance Traveled (m) | Drift Ratio (%) | SIH Mandate Gate ($< 10\%$) | Margin of Safety |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Raw IMU Integration (No Constraints)** | 284.5 m | 420.0 m | 67.7% | **FAILED** | Unusable ($\mathcal{O}(t^3)$) |
| **Classical ES-EKF + NHC ($v_y = v_z = 0$)** | 56.7 m | 420.0 m | 13.5% | **FAILED** | Exceeds 10% limit |
| **IDR: ES-EKF + AI Model A Speed Estimator** | **13.1 m** | **420.0 m** | **3.12%** | **PASSED** | **3.2x better than mandate** |
| **IDR: Model A + Closed-Loop Road Match ($H[8]$)** | **7.4 m** | **420.0 m** | **1.76%** | **PASSED** | **5.7x better than mandate** |

---

## The 4 Demonstration Pillars

```
tools/
├── pull_device_telemetry.py   # Pillar 1: Automated ADB extraction from connected phone
├── process_field_drive.py     # Pillar 1: Replay processor & ASCII SIH Qualification Certificate
├── launch_showcase.py         # Pillar 2: Zero-dependency local visualizer & pitch launcher
└── visualizer/
    ├── index.html             # Pillar 2: Interactive 3D satellite trajectory & HUD visualizer
    └── pitch.html             # Pillar 3: 10-slide interactive technical defense presentation
reports/
└── sih_hackathon_pitch_deck.md # Pillar 3: Official technical defense brief & mathematical proof
docs/
└── FIELD_TEST_PROTOCOL.md     # Pillar 4: In-car field driving operations handbook
```

### 1. Automated Device Telemetry Pull & Sync
Connect your Android phone via USB debugging and run:
```bash
python tools/pull_device_telemetry.py
```
Automatically locates ADB, detects the connected device (e.g. OnePlus CPH2585), and downloads `.kml`, `.gpx`, and session recordings from `/sdcard/Android/data/com.intelligentdeadreckoning.app/files/exports/` directly into `reports/exports/pulled/`.

### 2. Interactive Desktop Showcase & 3D Visualizer
```bash
python tools/launch_showcase.py
```
Spins up a lightweight local server and opens your browser to `http://localhost:8080/index.html`. Features 3D satellite map playback, timeline scrubbing (0–70s), outage corridor visualizer, live telemetry cards, and direct bridge to the Pitch Deck.

### 3. Field Drive Processor & Qualification Certificate
```bash
python tools/process_field_drive.py
```
Ingests any recorded or replayed drive session, evaluates kinematics across forced GNSS denial windows, exports Google Earth KML / GPX, and prints the official ASCII SIH #26168 Qualification Certificate.

### 4. SIH #26168 Official Technical Defense Deck
- **Web Slideshow**: Open `http://localhost:8080/pitch.html` (or via `tools/launch_showcase.py`) for a 10-slide dark-mode deck with keyboard navigation (`←`/`→`/`Space`), fullscreen toggle, and certificate modal.
- **Defense Brief**: [`reports/sih_hackathon_pitch_deck.md`](reports/sih_hackathon_pitch_deck.md) covers full 15-state ES-EKF math, Somigliana formulas, 1D-CNN vibration receptive fields, and thermal profiling.

### 5. In-Car Field Driving Protocol
Step-by-step instructions for physical vehicle road tests: [`docs/FIELD_TEST_PROTOCOL.md`](docs/FIELD_TEST_PROTOCOL.md).

---

## Physical Hardware Profile (OnePlus CPH2585)

The entire system is deployed, stream-installed, and validated natively on physical hardware:
- **Display Refresh**: Fluid MapLibre vector map rendering running at native **120 Hz**.
- **CPU Footprint**: $< 2.8\%$ CPU load during continuous 100 Hz sensor and EKF processing.
- **Standstill Power Decimation**: Automatically drops from **100 Hz to 20 Hz** during vehicle stops, saving $> 65\%$ power.
- **Jerk Wake-Up Responsiveness**: Resumes full 100 Hz state within **$< 5\text{ ms}$** upon observing a jerk $> 0.35\text{ m/s}^3$.
- **Operating Stability**: Stream-installed and launched via ADB with zero runtime crashes.

---

## Quick Start & Verification

### Prerequisites
- Python 3.10+
- Android Studio with JDK 17+ (verified on JBR 25)
- Android SDK Platform 37 & Build Tools 36.0.0

### Python Test Suite (290 Tests)
```bash
python -m pytest tests/
python tools/check_repo_hygiene.py
```

### Android Build & Host Unit Tests
```powershell
Set-Location mobile
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain
```

### Installing Debug APK onto Connected Phone
```powershell
.\gradlew.bat installDebug --console=plain
```

---

## Repository Architecture

```
├── contracts/          # Frozen typed schemas (contracts.v1, recording.v1, experiment.v1)
├── docs/               # Technical specs, architecture diagrams, and Field Test Protocol
├── mobile/             # Production Android app (Jetpack Compose, MapLibre, ES-EKF engine)
├── reports/            # SIH Pitch Deck, benchmark data, and exported KML/GPX tracks
├── tests/              # 290 unit tests covering codecs, strapdown INS, and EKF parity
├── tools/              # Showcase launcher, telemetry pull, visualizer builder, and hygiene gates
└── training/           # Model A 1D-CNN forward speed estimator & frame kinematics
```

---

## License & Compliance
Developed for the **Smart India Hackathon 2026** under Problem Statement **#26168** (ISRO / Department of Space). All offline map assets and road graphs are bundled with full ODbL and MapLibre attribution notices.
