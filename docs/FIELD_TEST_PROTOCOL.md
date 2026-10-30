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
     scored against the withheld fixes with the evaluation harness (field-drive scoring is not implemented yet, see
     `reports/gap_closure_log.md`).

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
