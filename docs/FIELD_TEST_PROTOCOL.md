# In-Vehicle Field Driving & Verification Protocol
## Intelligent Dead Reckoning System (SIH #26168)

This operational guide provides step-by-step instructions for conducting real-world road tests, validating the in-situ calibration, injecting simulated GNSS denial periods, and pulling telemetry into Google Earth.

---

### Phase 1: Pre-Drive Vehicle Mounting

1. **Mounting Location**:
   - Secure the smartphone in a rigid dashboard or windshield phone mount.
   - Avoid magnetic mounts that distort the magnetometer.
   - Align the phone roughly facing forward (screen facing passenger or driver). Exact leveling is **not required** — the in-situ calibration algorithm calculates the relative vehicle-to-device quaternion automatically.

2. **App Preparation**:
   - Launch the **Intelligent Dead Reckoning** app on your phone.
   - Ensure Location and Sensor permissions are granted.
   - Switch to the **Map** tab. The offline vector map of Hyderabad (or your region) will display immediately.

---

### Phase 2: In-Situ Calibration & Alignment

1. **Parked Stillness (5–10 seconds)**:
   - Begin with the car parked and engine idling or off.
   - Tap **"Start sensors"** on the Map screen.
   - The status banner will indicate: `Mount calibration: calibrating`.
   - The filter measures the local gravity vector to calculate pitch and roll leveling ($q_{\text{level}}$).

2. **Forward Acceleration Segment**:
   - Drive forward smoothly for 50–100 metres with good GNSS visibility.
   - The filter observes forward linear acceleration to lock the azimuth heading offset ($q_{\text{yaw}}$).
   - Once locked, the status banner updates to: `Mount calibration: valid · Localization: fused`.
   - The phone is now calibrated to the vehicle's chassis frame.

---

### Phase 3: Live Field Outage Simulation

You can test the system either during a natural outage (underpass, flyover tunnel) or by triggering a simulated outage:

1. **Triggering the Outage**:
   - While driving at normal road speeds (30–60 km/h), tap the **"Simulate Outage (30s)"** chip in the bottom sheet.
   - The map HUD banner immediately switches to:
     ```
     SIMULATED GNSS OUTAGE ACTIVE · 29s REMAINING [ABORT]
     Withholding GNSS fixes · Ground truth buffering in background
     ```
   - GNSS fixes are withheld from the navigation engine, but buffered in the background for ground truth.

2. **In-Tunnel Dead Reckoning**:
   - The map displays **`INTELLIGENT DEAD RECKONING ACTIVE`**.
   - Watch the speed readout. The shipped Model A v1 is trained on synthetic data and is known to be unreliable on real drives; record what you see rather than assuming it is right.
   - Watch road snap: Map-matching heading constraints keep the vehicle on the road centerline.

3. **Convergence & Benchmark Evaluation**:
   - At the end of the 30 seconds (or upon exiting the tunnel), authoritative GNSS fixes resume.
   - The HUD banner displays **`GNSS RECOVERED · POST-OUTAGE CONVERGENCE`**.
   - The **`FIELD DRIFT BENCHMARK REPORT`** card appears and shows the duration, distance, final drift error and
     drift ratio the engine measured against the GNSS fixes it withheld. **Write the numbers down as they appear.**
     The card shows whether the run is under the 10 % gate; it is not a pass or fail until the drive has been
     scored offline (Phase 5 below).

---

### Phase 4: Exporting Telemetry to Google Earth

1. **On the Phone**:
   - On the benchmark card, tap **"Export KML (Google Earth)"**.
   - Choose Google Earth or save the file to local storage.

2. **On Your Laptop (via ADB)**:
   - Connect the phone to your laptop with USB debugging enabled.
   - In the repository root, run:
     ```bash
     python tools/pull_device_telemetry.py
     ```
   - All `.kml` and `.gpx` files from the phone will be downloaded to `reports/exports/pulled/`.
   - Double-click the `.kml` file to open it in Google Earth desktop to view the 3D ground truth vs. dead-reckoning trajectory overlay.

---

### Phase 5: Recording for offline scoring, then scoring the drive

**Before leaving (learned on a OnePlus CPH2585, Android 16):**
- Install the debug build (`bash mobile/gradlew -p mobile installDebug`). ADB cannot grant location on this ROM unless "USB debugging (Security settings)" is on in Developer options, so on the phone: Home, tap **Allow location**, choose **Precise** and **While using the app**.
- Keep the screen on and the app in the foreground; sensors and recording stop when the app leaves the foreground.
- On the Home screen choose **Phone sensors**, then **Start sensors**, then **Start recording**. Check the Signals tab shows the accelerometer and gyroscope near 98.8 Hz (this phone, measured) and "Dropped 0".
- Indoors the phone sees satellites but uses none; the first GPS fix can take a minute under open sky. Wait for a GPS fix before moving off. A recording with fewer than 30 GPS fixes cannot be scored.

**After the drive:**
1. Stop the recording, then the sensors. Leave the phone connected.
2. Pull and validate (writes under `data/raw/phone/`, which is not committed):
   ```bash
   python tools/pull_phone_recording.py --list
   python tools/pull_phone_recording.py --label hyderabad-morning-drive
   ```
   The newest real recording is pulled byte for byte and checked with `tools/validate_phone_recording.py --local`; it must say "accepted".
3. Seal it before looking at any result: `python tools/seal_experiment.py` (see the two-wheeler steps for the rule about deciding the split first).
4. Score it:
   ```bash
   python tools/field_drive_eval.py data/raw/phone/hyderabad-morning-drive/<recording-id>
   ```
   This replays the drive through the real engine with the app's own calibrator settings and cuts GNSS outages (50 m, 1 km, 30 s, 60 s) after a 120 s warm-up. Results go to `reports/field/<recording-id>/` (`summary.md`, `summary.json`, `outages.csv`).
   - **The reference is the phone's own GPS**, so drift includes that GPS's error (the summary prints its reported accuracy). Record a second, better GNSS logger on the same vehicle if the number has to stand up to scrutiny, and say which in the notes.
   - If the engine never produces a position, `summary.md` says why. The app's calibrator needs a still start and two separate straight acceleration or braking segments with good GPS; a short or twisty drive will not lock it. `--calibration <file.json>` takes an offline mount calibration for such a drive, and `--warmup-s` changes the warm-up.
   - The tool was checked against the existing IO-VNBD harness on drive `m` (same outage counts; 30 s and 60 s medians within 0.1 points) and on the bundled synthetic demo drives (labelled "NOT A FIELD DRIVE"). It has not been run on a real phone drive: none exists yet.

### Two-wheeler recording (needed before the two-wheeler profile can be validated)

`VehicleProfile.Kind.TWO_WHEELER` has values that were chosen, not measured. To measure them, record at least three drives of 20 minutes or more
on a motorcycle or scooter, with a phone and the same tooling as Phases 1 to 3:

1. Mount the phone rigidly (tank bag or handlebar mount with a locked orientation) and say which in the notes. A phone that can swing is not usable.
2. Include starts and stops at junctions, at least ten turns each way at ordinary speed, one stretch of rough road and one stretch above 60 km/h.
3. Record a reference: a second GNSS logger with a better antenna on the same vehicle, or a mapped route with timestamped landmarks. State which.
4. Force outages with the mask tool exactly as in Phase 3 (30 s, 60 s, 1 km) and keep the outage list.
5. Seal the recording with `tools/seal_experiment.py` before looking at any result, and decide the development/held-out split of the drives first.
6. Score both profiles (car and two-wheeler) on the same outages with the same harness and report paired differences with intervals. The profile is
   marked validated only if the two-wheeler profile is better on the held-out drives; otherwise it stays unvalidated or is retuned on the development drives.
