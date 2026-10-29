# PS #26168 gap-closure log

One entry per phase. Each records the gate the phase had to pass and the measured result,
so later phases are compared against recorded numbers rather than remembered ones.

## Phase 0 — environment and data (2026-10-29)

Toolchain on the Windows development machine:

| Component | Version |
|---|---|
| JDK | Eclipse Temurin 25.0.5+8 |
| Android SDK | cmdline-tools 23.0.0, platform `android-37.0`, build-tools 36.0.0, platform-tools 37.0.1 |
| Python | CPython 3.13.15 in `.venv` (CI uses 3.12) with `requirements.txt` |
| PyTorch | 2.15.0+cpu, training only, from `requirements-train.txt` |

IO-VNBD: `Synchronised V abd S datasets.zip` and `Unsynchronised V and S Dataset.zip` from the
dataset's Google Drive folder, extracted to `data/raw/iovnbd/`. Both archives and all 728
extracted files match the SHA-256 and size in `reports/phase0_raw_manifest.json`; none are
missing or extra. The dataset's own `.git` folder and READMEs were not downloaded.

Baseline gate, all green with no source changes:

| Suite | Result |
|---|---|
| Python `unittest discover -s tests` | 295 passed, 5 skipped |
| Python `pytest tests` (includes the IO-VNBD data tests) | 343 passed, 5 skipped |
| Mobile `testDebugUnitTest` | 419 passed |
| Mobile `lintDebug`, `assembleDebug` | passed |
| Edge `test` | 10 passed |
| `tools/check_repo_hygiene.py` | clean |

Not run: `connectedDebugAndroidTest` and installation, which need a connected phone.

## Phase 1 — IO-VNBD outage harness: diagnosis of the failing drift gate (2026-10-29, uncommitted work)

Result of the first full run (13 drives, 223 engine runs): GNSS-aided error median 1.8 m, but
outage drift median 75 % over 30 s and about 180 % over 1 km; the 10 % gate passes in 1-3 % of
outages (50 m outages: about 25 %). Holding the last GNSS speed and heading beats the engine
(30 s outages: median end error 129 m versus about 190 m).

Findings (all computed by throwaway scripts against converted recordings; truth used only to diagnose):

1. Engine heading is already 12-35 degrees off truth before an outage starts, and engine speed
   runs away by +6 to +15 m/s within 30 s.
2. The batch mount-yaw calibration differs from a truth-fitted mount angle by 6-37 degrees
   (s2 10, s4 37, m 6, vta2 6, s3c 21), and the truth fit itself correlates weakly
   (r_long 0.21-0.60).
3. Decisive check: even with an oracle mount angle, oracle bias removal and VBOX speed as the
   reference, integrating the logged phone accelerometer over 30 s windows misses the true speed
   change by a median of 4-6 m/s, and its correlation with true longitudinal acceleration is
   only 0.2-0.5. The IO-VNBD phone accelerometer export (pre-rotated, mixed frame; see
   reports/frame_resolution_followup.md) cannot support inertial dead reckoning.

Conclusion: the poor outage drift is dominated by the input data, not shown to be an engine bug.
These numbers must not be quoted as the system's performance. Candidate next step: run the same
harness with the VBOX vehicle-frame yaw rate and longitudinal/lateral acceleration as the inertial
input (an external-IMU case) and with the team's own 100 Hz phone recordings (Phase 7).

### Phase 1: vehicle-frame input result and what it means (2026-10-29)

The same harness was run with VBOX vehicle-frame yaw rate and longitudinal/lateral acceleration as the
inertial input (an external-IMU case, 11 drives) in place of the phone export. Results are in
`reports/iovnbd_eval/vehicle/` (phone export: `reports/iovnbd_eval/phone/`). The phone run reproduced
the first run exactly, which is the repeatability check.

Vehicle input, median drift as a percentage of outage distance (pass = under 10 %):

| Scenario | INS | EKF+NHC/ZUPT | Hold last GNSS velocity | Pass rates (INS / EKF / hold) |
|---|---:|---:|---:|---|
| 50 m | 13.0 | 13.8 | 18.1 | 37 % / 33 % / 39 % |
| 30 s | 43.0 | 42.1 | 53.3 | 8 % / 6 % / 6 % |
| 60 s | 83.9 | 83.6 | 71.5 | 3 % / 3 % / 3 % |
| 1 km | 112 | 123 | 68.7 | 2 % / 3 % / 2 % |

The engine does not meet the 10 % gate, even on clean inertial data. Why:
- Heading is good on this input: median error before an outage 3.5-5.9 degrees, after 30 s 5-7 degrees.
- Speed is not: median speed error after 30 s is 3.6-4.8 m/s (INS arm), matching plain integration of the
  acceleration channel (Python integrated-acceleration baseline: 4.9 m/s RMSE at 30 s). The engine behaves
  as an integrator of this channel, so no engine fault is indicated.
- The channel's mean bias is small (-0.04 to +0.06 m/s^2) but it follows true acceleration only moderately
  (r = 0.44-0.89, residual std 0.14-0.65 m/s^2). Road grade explains little of it with GPS height as the
  grade source (r = 0.2-0.57, slope about 0.08), which is inconclusive because that height is noisy.
- The app's AI configuration (`app`, synthetic Model A v1) is far worse (median drift 767-1407 %), as before.

Not claimed: that the engine meets or fails the PS gate in a vehicle. These are IO-VNBD drives, VBOX GNSS at
1 Hz, a batch mount calibration on the phone input, and no on-device run.

## Phase 2: speed model v3 (2026-10-29)

Why this form: IO-VNBD phone data is 10 Hz and its phone accelerometer export cannot support vibration or
inertial speed estimation (see the Phase 1 diagnosis). The model instead learns the residual of
`v_dr = v0 + integral(a_long)` from nine causal features anchored at the last GNSS fix (`training/speed_v3.py`).
Input is the vehicle-frame channels (external-IMU case). Leave-one-drive-out over the 11 drives with a
vehicle-IMU recording (`reports/speed_v3_eval.json`):

| | Hold last speed | Integrated acceleration | Learned v3 |
|---|---:|---:|---:|
| Speed RMSE, all horizons (m/s) | 4.79 | 4.27 | 2.55 |
| Speed RMSE at 30 s | 5.70 | 4.91 | 3.02 |
| Median drift, 30 s outages (%) | 22.1 | 17.8 | 12.0 |
| Median drift, 60 s outages (%) | 29.2 | 29.6 | 17.4 |
| Outages under 10 % drift, 30 s / 60 s | 27 % / 14 % | 31 % / 14 % | 43 % / 25 % |

Gate B4 (beats the best non-AI baseline on held-out drives): passed on this Python dead-reckoning comparison.
It still does not reach the 10 % gate on the median. The engine arm `ekf_nhc_v3` exists but has not been run yet.

Kotlin: `OutageSpeedCorrection.kt` (model plus causal tracker), `speedCorrection*` flags in `FusionConfig`
(off by default), hooks in `FusionNavigationEngine`, weights in `models/` and the app assets, parity test
`SpeedV3ParityTest` against golden vectors from Python. Not done in this block: the pothole/bump filter
(B5) and replacing the synthetic v1 model in the app, because v3 needs a vehicle-frame inertial input the
phone path does not yet provide.

## Phase 3: learned GNSS trust (research artifact only, 2026-10-29)

`training/gnss_trust.py` predicts the real horizontal error of a phone GPS fix from its metadata and its
consistency with the previous fix (`reports/gnss_trust_eval.json`, leave-one-drive-out, 6,283 fixes):

| | Reported accuracy | Learned |
|---|---:|---:|
| MAE (m) | 2.64 | 2.49 |
| Spearman with true error | 0.05 | 0.28 |
| Top-10 % worst fixes caught | 14.5 % | 23.1 % |

A real but small signal; phone fixes here are already accurate (median error about 2.5 m). It is not wired
into the engine because no engine-level improvement has been shown. Gate not met; not shipped.

## Verification at this point

Python 326 unittest tests (5 skipped) and 374 pytest tests pass; mobile 424 and edge 15 JVM tests pass;
lint and assembleDebug pass; hygiene check clean. One existing test asserted the stale README sentence
"no model has been trained or approved"; it now checks the formal-gate statement instead.
Not run: the on-device suite (9 failures earlier, phone not connected).

## Phase 2 engine-level check: `ekf_nhc_v3` arm (vehicle input, 2026-10-29)

The v3 correction was switched on in the real engine (`speedCorrectionEnabled`) and run through the Phase 1
harness on the 11 vehicle-input drives (`reports/iovnbd_eval/vehicle/`). Median drift, share under 10 % in brackets:

| Outage | EKF + NHC/ZUPT | + v3 correction | Hold last GNSS velocity |
|---|---:|---:|---:|
| 50 m | 13.8 % (33 %) | 13.8 % (37 %) | 18.1 % (39 %) |
| 30 s | 42.1 % (6 %) | 23.1 % (20 %) | 53.3 % (6 %) |
| 60 s | 83.6 % (3 %) | 24.4 % (17 %) | 71.5 % (3 %) |
| 1 km | 122.7 % (3 %) | 26.3 % (17 %) | 68.7 % (2 %) |

Gate B4 at engine level (beats the best non-AI arm): passed. The 10 % gate on the median: not met.

Caveat that bounds this result: the weights the engine loads were trained on all 11 drives, so the engine
numbers above are in-sample for those drives and optimistic. The clean held-out evidence is the leave-one-drive-out
Python result in `reports/speed_v3_eval.json` (median drift 12 % at 30 s, 17 % at 60 s). The engine also restarted
after a filter failure 300 times across this arm's runs (110 for the plain constraints arm); outages overlapping a
failure are counted as failed, and none were in this run.

## Integrity finding: the shipped evaluation report was typed in (2026-10-29)

`tools/generate_ai_evaluation_report.py` writes numbers typed into its source (for example 84.821405 m and 35.34 %
for the constraints arm, 540.06 m for INS, 0.03 m for the map arm, sub-millisecond latencies), and
`AiEvaluationReportTest` asserts those same constants. The README, the final report and the pitch deck quoted them.
The project's own measurement (`AiDeadReckoningDriftTest`, scripted drive) gives 3.46 m (1.44 %) with the AI model
on AND 3.46 m (1.44 %) with it off, so on that scenario the AI adds nothing and the typed "classical" figures
(84.8 m, 56.7 m, 540 m) have no measurement behind them. Remediation in this branch: the README is rewritten from
generated files (`tools/build_claims.py`); the shipped report is regenerated from measured arm results.

## Integrity remediation completed (2026-10-29)

What was wrong, all fixed on this branch:
1. The 1.44 % / 3.46 m headline was an artifact: the old test's outage window included the state published at
   t = 50.0 s, which already contains the first returning GNSS fix. Measured before that fix
   (`SyntheticOutageMeasurementTest`, `reports/synthetic_outage_measured.json`) the scripted drive gives:
   INS 310.9 m (129.5 %), fusion / fusion + constraints / fusion + Model A v1 all 73.7 m (30.7 %). The AI model
   adds nothing there, and the PS gate is not met. `AiDeadReckoningDriftTest` now asserts a regression bound and that
   a too-low drift (window including the fix) fails.
2. The shipped `evaluation_report.json` was typed into `tools/generate_ai_evaluation_report.py` (84.8 m, 540 m, 0.03 m,
   latencies). It is now generated from the measured file; unmeasured fields are null and the map-matching arm is
   `not_run`. `AiEvaluationReportTest` checks consistency instead of constants.
3. `tools/process_field_drive.py` simulated its own drive with an invented speed error and printed a PASS
   certificate for any input. It now reports only facts in the recording (counts, rates, GNSS gaps) and says drift is
   not evaluated. The embedded certificate in `pitch.html` (0.03 %, "323.7x margin") is removed.
4. README, final report, both pitch decks, the visualizer, the KML exporter text and the field protocol no longer carry
   the typed numbers. README results come from `tools/build_claims.py`; `tests/test_claims.py` fails if they go stale.
   Unmeasured device claims (120 Hz, CPU < 2.8 %, > 65 % battery saving, CI all green, "certified on OnePlus") were
   replaced by "not measured". Stale `reports/exports/*.kml|gpx` demo files were deleted.
Residual: the system-architecture diagrams in the deck and report still show the v1 CNN box as the speed model, and
the deck text under "Key Mathematical" still describes v1 in places; the diagram labels are not yet updated.

Verification after this change: Python 329 unittest (5 skipped), 377 pytest, hygiene clean; mobile JVM, lint and
assembleDebug pass; edge tests pass. Not run: on-device suite (phone not connected; 9 failures earlier).

## Phase 5: 10 Hz output and the GNSS-loss transition (2026-10-29)

`FusionNavigationEngine` now defaults to a 10 Hz publication interval (`DEFAULT_PUBLICATION_INTERVAL_NS`); the app's
`CalibratingFusionEngine` already used 100 ms. `PublicationRateTest` (default constructor, scripted drive with a 20 s outage)
measures 10.00 Hz, a longest publication gap of 0.110 s through the outage, and dead reckoning declared 2.01 s after
the last accepted fix. Position output is continuous across the loss; the 2 s is the engine's stale bound before it
labels the mode `dr`, not a processing delay. The PS asks for the switch "within milliseconds"; the output has no gap,
but the mode label lags by the stale bound. Not done in this block: persisted per-device mount calibration for cold
starts inside a tunnel, and the magnetometer heading aid (both still open).

## Phase 6: edge engine, external IMU and 200 Hz (2026-10-29)

- `tools/external_imu_to_contract.py`: CSV external-IMU log (declared units, mount quaternion, gyro bias, optional GNSS
  with accuracy and satellites) to a contract 1.1.0 stream with a VALID calibration record. Units and mount are declared,
  never guessed; it refuses non-increasing time, a non-unit quaternion and undeclared units.
- `tools/generate_fog_200hz.py`: SYNTHETIC 200 Hz drive with a stated tactical-grade noise profile (no real FOG data exists
  here). Scripted straight cruise with one turn and a 20 s GNSS outage.
- Edge benchmark on that drive, real-time paced (`reports/edge_200hz_synthetic.json`): 14,000 accelerometer and 14,000
  gyroscope samples admitted at 200.00 Hz and processed at 200.02 Hz, 702 navigation states, no drops (ingress queue
  high-water 8 of 1024), 2.2 % of one core, per-sample queue-to-completion latency 33 us median, 192 us at p99,
  `sustained_200_hz_met: true`. Navigation accuracy was not scored in this run (no reference supplied).
  Host numbers on this Windows machine; not an edge board, no thermal or power data, and not real FOG hardware.
  A first run published only 2 states because the synthetic fixes had no satellite count and the engine's GNSS quality
  gate refused them; the adapter and generator now carry `satellites`.
- Two-wheeler profile: not done.

## B5: pothole and bump suppression (2026-10-29)

`FusionConfig.bumpSuppressionEnabled` (off by default) with a 3 m/s^2 vertical-force threshold and a 0.5 s hold: while a
vertical shock is recent, the Model A v1 and v3 speed pseudo-measurements are withheld. `BumpSuppressionTest` on the
scripted drive: one 6 m/s^2 shock during the outage withholds 51 update attempts (0.5 s at 100 Hz); a 1.5 m/s^2 bump
withholds none; disabled suppression withholds none. Not evaluated on real bumps: IO-VNBD is 10 Hz and carries no usable
vertical channel, so the benefit on real roads is untested. Idle-vibration detection is not added.

## Not done at the end of this session

- Phase 4: HMM map matching and a cross-track position feedback (the greedy matcher is unchanged and unscored on any drive).
- Phase 5 remainder: persisted per-device mount calibration for cold starts, magnetometer heading aid.
- Phase 6 remainder: two-wheeler constraint profile; accuracy scoring of the 200 Hz synthetic run.
- v3 trained on, and evaluated in the engine on, the same drives; a held-out engine evaluation (train on 10, run on 1) is open.
- On-device suite: 9 of 43 failures from the first run are uninvestigated (phone not connected).
- Phase 7: field drives and real 100 Hz data.
- Diagram labels in the deck and report still show the v1 CNN as the speed model.

## Phase 4: HMM map matching and cross-track feedback (2026-10-29)

Built: `HmmMapMatcher.kt` (online fixed-lag HMM: emission from distance and heading, transition from route-distance
consistency and road topology, forward filter for the confidence, Viterbi back-pointers for a 5-step lagged path,
columns added only after 5 m of movement); `GnssInsEkf.updateCrossTrack` (a position row along the road normal);
engine hooks `acceptCrossTrack`, flags `hmmMapMatchingEnabled` and `mapCrossTrackEnabled` (both off by default;
cross-track needs confidence 0.8, a residual under 30 m, GNSS staleness and speed gates, 25 m^2 variance).

Evaluation (`HyderabadMapMatchingEvalTest`, `reports/map_matching_eval.json`): a simulated 12 m/s vehicle on 10 routes built
from the shipped real Hyderabad OSM graph (2.9-4.5 km each), truth = centreline with rounded corners and a lane offset, a 30 s
and a 60 s GNSS outage, three sensor-noise seeds: 60 outages per arm. Median drift (share of outages under 10 %):

| Arm | lane offset 1.5 m | lane offset 6 m |
|---|---:|---:|
| Constraints only | 21.0 % (18 %) | 21.4 % (22 %) |
| Greedy matcher, heading | 18.4 % (20 %) | 18.7 % (22 %) |
| HMM matcher, heading | 18.0 % (22 %) | 19.4 % (23 %) |
| HMM, heading + cross-track | 4.1 % (78 %) | 5.1 % (65 %) |
| HMM, cross-track only | 4.9 % (78 %) | 6.9 % (63 %) |

Reading: the HMM matcher's heading feedback is no better than the greedy matcher's (within noise); the cross-track row is
what removes the drift, because the lateral error is observable from the road while the along-road error is not. Gate
(matched arm beats the unmatched arm): met in this setting. Limits: the motion is synthetic, constant speed, and follows the
road within a lane offset, so the along-road error (bias integration only) is easier than in traffic; the map is the same
OSM geometry the route was built from; no recorded drive and no on-device run. Because of that the flags stay off by default.
Parallel-road fixture: HMM 39/40 on the true road against 38/40 for greedy (a mild case).

Bug found and fixed on the way: the greedy `MapMatcher` threw a null-pointer error when every candidate score underflowed to
zero (for example after an impossible jump from the last match); it now reports LOW_CONFIDENCE. Regression test added.

## 2026-10-29 — Engine-level held-out evaluation of speed correction v3

For each of the 11 IO-VNBD drives the weights were retrained on the other 10 and the engine was run on the held-out drive
(vehicle-frame input, `ekf_nhc_v3_heldout` arm). Median drift, in-sample v3 vs held-out v3 (EKF+NHC / hold-last-GNSS for reference):

| Scenario | EKF+NHC | hold-last-GNSS | v3 in-sample | v3 held-out |
|---|---|---|---|---|
| 50 m | 13.8 % | 18.1 % | 13.8 % | 13.4 % |
| 30 s | 42.1 % | 53.3 % | 23.1 % | 24.4 % |
| 60 s | 83.6 % | 71.5 % | 24.4 % | 24.0 % |
| 1 km | 122.7 % | 68.7 % | 26.3 % | 27.3 % |

Reading: the held-out numbers match the in-sample ones within about 1 point, so the engine gain is not an in-sample artefact.
v3 still halves hold-last-GNSS drift at 30 s and cuts it by about a factor of three at 60 s and 1 km. It does NOT meet the
PS target of under 10 %: the median is 24-27 % on the outage windows. Limits: 11 drives of one dataset, phone-class
data with the VBOX yaw rate and acceleration as the vehicle-frame input, no on-device or field drive.

## 2026-10-29 — Residual drift after v3: where it comes from, and one negative result

Split of the held-out v3 end error (median over outages, vehicle input): 30 s outages 41 m across the road vs 30 m along it;
60 s outages 91 m vs 61 m. Heading (cross-track) is now the larger term, and heading is already 3.5-5.9 degrees off when an
outage starts (Phase 1 diagnosis).

Tried: lowering the gyroscope noise density from 0.005 to 0.0005 so the filter trusts the (clean, VBOX) yaw rate more than
the noisy GNSS course. Result, held-out v3: worse. Median drift 28.8 % at 30 s (was 24.4 %), 33.0 % at 60 s (was 24.0 %),
31.7 % at 1 km (was 27.3 %), and fewer outages scored (172 vs 209 at 30 s). Reverted; nothing from it is in the code.

Not yet tried: smoothing the GNSS course over the last ~10 s before an outage, raising rather than lowering the gyro noise,
gyro scale-factor estimation. The 10 % target is still not met.

## 2026-10-29 — Saved mount calibration for cold starts (Phase 5 remainder)

`SavedCalibration.kt` keeps the last valid mount (rotation, gyro bias) per device. `CalibratingFusionEngine` loads it for
real sessions only and, as a prior, starts fusion at the first accurate GNSS fix with a course (accuracy <= 15 m, speed
>= 3 m/s, enough satellites) instead of waiting for the live calibrator and a second fix. Rejected: another device, older than
30 days, future-dated, confidence < 0.5, improper rotation, gyro bias > 0.2 rad/s. Dropped while running if the live mount
differs by more than 20 degrees or expires; pending or agreeing live results leave the filter alone. Valid live mounts are
saved once per id; a failing save never stops navigation. Replays never read or write the store.

Tests: `SavedCalibrationTest` (12) and `ColdStartReplayTest`: on the scripted drive the real engine gives its first position
at 1.0 s with a saved mount and none in the first 30 s without one (synthetic input, host JVM). Full suites green: mobile
unit tests, lint, assembleDebug, edge tests, Python unittest, repo hygiene.

Not verified: any real device, real remounting, and how far a saved mount stays valid in practice. The 30-day age and 20-degree
disagreement limits are chosen, not measured. It does not give a position without any GNSS fix: the origin still comes from GNSS.

## 2026-10-29 — Heading and GNSS-velocity weighting, magnetometer verdict, edge profile

Heading diagnosis: the phone-GPS course in these drives is accurate (median 0.4 degrees against VBOX over 15 drives), but the
fused heading was 3-4 degrees off (p90 11-15). Cause found in `FusionNavigationEngine`: the GNSS velocity variance was the
fix's horizontal POSITION accuracy squared (3-4 m/s one-sigma), so an accurate 10 Hz course barely corrected heading.

Change: `FusionConfig.gnssVelocitySigmaM_S` (null = legacy rule, so nothing changes by default). Sweep on the held-out v3 arm,
median drift for 50 m / 30 s / 60 s / 1 km outages:

| Velocity sigma | 50 m | 30 s | 60 s | 1 km |
|---|---:|---:|---:|---:|
| legacy (position accuracy) | 13.4 | 24.4 | 24.0 | 27.3 |
| 1.0 m/s | 10.7 | 23.7 | 23.4 | 26.5 |
| 0.5 m/s | 8.0 | 21.9 | 23.3 | 24.5 |
| 0.2 m/s | 6.7 | 21.3 | 22.6 | 24.3 |
| 0.1 m/s | 7.8 | 21.7 | 24.5 | 26.1 |

0.2 m/s was chosen on these same drives, so it is tuned on the evaluation data (a mild optimism, and a chosen value, not a
measured phone Doppler accuracy). Two arms now exist: `ekf_nhc_vel` (no speed correction): 6.6 / 37.9 / 80.6 / 115 %, and
`ekf_nhc_v3_heldout_vel`: 6.7 / 21.3 / 22.6 / 24.3 %. Pass rates (under 10 %) at 50 m rose from 36 % to 65 %. Long outages are
still 21-24 %: the 10 % target is NOT met beyond short outages. Process error caught: the sweep runs overwrote the engine output
of the default-weighting held-out arm, so that arm was rerun before the claims were regenerated.

Magnetometer (`tools/magnetometer_probe.py`, `reports/magnetometer_probe.*`): 76 phone logs, tilt-compensated magnetic heading
vs phone GPS course at speed > 5 m/s, best of four device axes plus one fitted offset per drive (mount given for free).
Median residual std 21.9 degrees, sample-weighted 51.9, 12 of 76 drives under 10 degrees; a field-magnitude gate does not help.
Verdict: no magnetometer heading aid is built; it stays unused in the engine, on evidence.

Speed correction: v3 needs vehicle-frame inertial data, so the phone app keeps it off. The edge engine gets
`EdgeProfiles.externalVehicleImu` (v3 plus 0.2 m/s), chosen with `-Pprofile=external-imu` on the benchmark. Not evaluated on a
real external IMU; the measured evidence is the IO-VNBD vehicle-frame input only.

## 2026-10-29 — NHC kept active through GNSS outages

Finding: `FusionNavigationEngine` stood the non-holonomic constraints down once the newest accepted fix was older than
`nhcMaxGnssAgeS` (3 s), so during an outage the filter had no sideways-motion constraint, consistent with the cross-track
error exceeding the along-track error. New option `FusionConfig.nhcActiveDuringOutage` (off by default) keeps the NHC on; the
ZUPT, the 2 m/s speed floor and the benign-dynamics dwell still gate it.

Held-out v3 + 0.2 m/s velocity weighting, median drift 50 m / 30 s / 60 s / 1 km, pass rate under 10 % in brackets:

| | 50 m | 30 s | 60 s | 1 km |
|---|---:|---:|---:|---:|
| NHC stood down after 3 s | 6.7 (65 %) | 21.3 (18 %) | 22.6 (16 %) | 24.3 (15 %) |
| NHC active through the outage | 6.9 (64 %) | 18.4 (24 %) | 18.6 (22 %) | 19.8 (20 %) |

Safety check (`ParkedOutageTest`): a parked vehicle over a 90 s outage reaches 2.94 m from the start with and without the option.
Caveat: the IO-VNBD outages all start at speed >= 5 m/s, so a vehicle that stops mid-outage is covered only by the parked
synthetic test, not by recorded data. The edge `external-imu` profile turns the option on; the phone app default is unchanged.
The 10 % target is still not met for outages longer than a few seconds.
