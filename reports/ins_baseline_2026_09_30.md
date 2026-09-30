# Classical strapdown INS baseline — 2026-09-30

What an unaided inertial navigation solution does on this project's own hardware, measured
on a real recorded session, with every input and every limitation named.

- Implementation: [`training/strapdown_ins.py`](../training/strapdown_ins.py)
- Host tests: [`tests/test_strapdown_ins.py`](../tests/test_strapdown_ins.py) (31 cases)
- Machine-readable metrics: [`reports/ins_baseline_metrics.json`](ins_baseline_metrics.json)
- Reproduction: `python tools/ins_baseline_report.py --local <session-directory>`

This is not a calibrated solution, not a product claim, and not a comparison against AI.
It is the classical baseline that a future result would have to beat, measured carefully
enough that the number means something.

## 1. What was run

| | |
|---|---|
| Session | `f2608548-d1c8-4007-9e72-87045a094f47` (real, completed, stopped cleanly) |
| Device | OnePlus CPH2585, Android 16 (API 36) |
| Duration | 1660.2 s (27 min 40 s) |
| Records | 493,067 IMU + 1,744 GNSS + 84 diagnostic |
| Paired inertial samples used | 163,863 |
| Declared calibration | `not_applied`, no calibration id, and no calibration record in the stream — so the tool selects assumed-zero biases, which is what the recording itself claims |
| Data location | `mobile/artifacts/phone-recordings-20260929/recordings/…` — gitignored, not committed |

The recording is the longest session in the local corpus. Its reference is **no motion**:
all 1,660 fixes that carry a speed report exactly `0.000 m/s`, the fixes sit within a 2.16 m
p95 (32.2 m worst case) offset of the anchor, and the median reported horizontal accuracy is
9.9 m. The *vehicle* was parked. The *handset* was not perfectly still, and was handled
during the session — see the limitations, because it matters.

## 2. What the run was not allowed to see

The propagation consumes paired accelerometer and gyroscope samples and nothing else. This
is structural, not a promise: `samples_from_records` is the only way samples enter, and its
payload filter has no branch that could accept a fix. It is checked at run time anyway by
re-reading a 120 s prefix of the same file with every GNSS record removed and requiring the
sample stream to be byte-identical:

| Check | Result |
|---|---|
| Timestamps identical without GNSS | true (11,847 samples compared) |
| Accelerometer values identical | true |
| Gyroscope values identical | true |
| Forbidden identifiers in the module (`gnss`, `gps`, `vbox`, `open`) | none |

GNSS is used for exactly two things, both outside the propagation: the **anchor position**,
taken from the fix nearest the first inertial sample (the session's first fix arrives 0.55 s
*after* it, and that offset is reported rather than hidden), and **scoring**, computed after
the run. Initial velocity is zero, as the caller supplies and as the session's own fixes
support; initial attitude comes from the measured gravity direction at rest with the heading
stated as a convention.

Excluded on purpose: `data/raw/iovnbd` (the repository's own audit leaves its axes
unresolved, so using it would mean guessing them) and VBOX (never a runtime input).

## 3. What was verified on real data

**Timing.** The mechanization derives every interval from timestamps. The session's
accelerometer intervals take **4,083 distinct values** between 9.805 ms and 10.325 ms, and
the paired stream's take **4,170** between 9.805 ms and 30.522 ms. A fixed-step integrator
would be wrong here by construction; this one cannot be, and a test asserts that the reported
step summary has `assumed_fixed_step: false`.

**Numerical health** over 163,862 steps: worst quaternion norm error 2.2e-16, worst
orthonormality error 1.3e-15. No drift, no renormalization failure, no failure status.

**Gap accounting.** 83 acquisition `TIME_GAP` notices, and 0 intervals long enough for the
mechanization to hold or fail. The two layers count different things (acquisition flags any
interval well above the nominal sensor period; the mechanization holds beyond 0.2 s and fails
beyond 5 s), and both counts are reported so neither is read as the other.

## 4. What it measured

Deviation of the unaided solution from the stationary reference, with the calibration the
session supports — none, so assumed-zero biases (`calibration_selection: assumed_zero`):

| Elapsed | Horizontal | Vertical | Horizontal speed |
|---|---|---|---|
| 60 s | 271.5 m | −26.1 m | 13.5 m/s |
| 300 s | 14.5 km | −567.8 m | 100.7 m/s |
| 600 s | 55.0 km | −1.46 km | 171.7 m/s |
| 1200 s | 247.1 km | −14.8 km | 640.6 m/s |
| 1660 s | 600.8 km | −47.3 km | 1046.7 m/s |

That is not a defect of the implementation; it is what an unaided INS does. The effective
acceleration implied by the horizontal curve grows from 0.15 m/s² at one minute to
0.44 m/s² at twenty-eight, which is the signature of a gravity vector whose cancellation is
being spoiled by a slowly growing misalignment. Two independent quantities in the session
say where the growth comes from:

- **Vertical at one minute is explained by the accelerometer's own magnitude residual.**
  Local normal gravity at the anchor (17.514°, 472.5 m) is 9.783536 m/s²; the resting window's
  measured magnitude is 9.771634 m/s², a residual of **−0.0119 m/s²**. Held constant, that
  alone integrates to **−16.4 km** of vertical error over the full 27.7 min and **−21.4 m** in
  the first minute; the run measured −26.1 m at that minute, so a quarter of the first
  minute's vertical error is not the sensor's magnitude error at all but misalignment. The
  vertical channel is not mysterious — it is mostly the magnitude error, twice integrated.
- **Horizontal is dominated by misalignment.** The initial attitude comes from a measured
  gravity direction, so its level error is small, but the gyroscope's residual rate rotates
  the estimated frame while the accelerometer keeps measuring the true specific force. The
  unremoved resting rate implies per-axis biases of **+128.7, −68.8, +90.0 °/h** against the
  modelled Earth rate.

**The attitude number is not an error.** `maximum_attitude_drift_deg` reports the largest
attitude change from the initial attitude anywhere in the run: **20.85°**. The session's whole
mean gyroscope output is zero to 1e-9 rad/s, so that excursion is a real rotation of the
handset that was later reversed — the mechanism tracked it, and it is not evidence of drift.

## 5. What the calibration is worth

The baseline's most useful negative result: taking the session's own resting mean gyroscope
rate (first 60 s) and removing it as if it were a bias makes **every** metric worse —

| Variant | 1660 s horizontal | Attitude excursion |
|---|---|---|
| the session's own calibration: none, so assumed-zero biases | 600.8 km | 20.85° |
| session resting mean rate removed (`session_resting_mean_gyro_removed`) | 4,168.5 km | 57.26° |

The reason is visible in the same window: it contains real motion (specific-force magnitude
9.730–9.806 m/s², peak rate 4.27e-3 rad/s = 0.24 °/s). An unscreened mean over a window that
is not actually at rest is a measurement of the window, not of the sensor. That is precisely
the job the on-device calibration engine already does with stability screening and a validity
gate (`mobile/app/src/main/java/com/intelligentdeadreckoning/app/calibration/`), and this
baseline deliberately does not reimplement it — a second estimator in Python would be a second
thing to keep honest.

**Heading is a first-order limitation, not a detail.** With the heading stated as 0°, 120° and
240°, the same data diverges to 600.8 km, 984.5 km and 418.9 km. The divergence depends on it,
so a real solution needs the vehicle-forward direction from calibration; the baseline states
its convention and quantifies the spread instead of choosing a favourable value.

## 6. Two mechanization bugs this stage found, and fixed

Both were found by running real data and were invisible to the synthetic suite, which is the
argument for running real data at all. Both are now pinned by tests that fail without the fix.

1. **Geodetic position update accumulated instead of incrementing.** The latitude/longitude
   update added the *running total* of the tangent-plane displacement each step instead of
   that step's increment. Over 300 s at 23 m/s the latitude reached 888° instead of 40.06°,
   which corrupted the gravity model and the Earth rate evaluated at it, turned the covariance
   of error terms into a positive feedback loop, and made long runs overflow to 1e308. Fixed
   in `step()`, and pinned by a test asserting the geodetic arc and the tangent-plane
   displacement agree to 1e-4 degrees — nothing else in the file asserted the geodetic state,
   which is exactly why the fault survived.

2. **The ENU transport rate had the wrong sign.** Differentiating the local triad gives
   `w_en = (−v_N/(R_N+h), +v_E/(R_E+h), +v_E·tanφ/(R_E+h))`; the module and the *test
   generator* both used its negation, so they agreed with each other and disagreed with the
   geometry. The consequences are not cosmetic: the term adds to the Earth rate in the
   attitude update, and with the sign reversed its quadratic part in the velocity equation
   destabilises instead of stabilises. The corrected term is pinned by a test that
   finite-differences the actual ENU triad built from latitude and longitude and compares
   component by component at five latitudes, plus a constant-velocity test with every frame
   term enabled that holds to 1e-7 m/s over five minutes.

The test file's claim of generator independence was also corrected: the generator and the
mechanism do share the frame-rate formulas and the gravity constant — what makes them
trustworthy is that each is separately pinned against published values or geometry, not that
they were written twice.

## 7. Limitations

1. **No dynamic session exists in this corpus.** Every real session here is stationary or a
   few seconds long. Nothing in this report tests sustained motion, cornering, vibration, or
   any interval where GNSS is actually lost while moving. The synthetic suite covers the
   closed-form trajectories; a phone on a moving vehicle is untested.
2. **The reference is "no motion" only.** There is no truth for altitude, attitude, or initial
   heading in this session. Position and speed are scored; the rest is stated as convention.
3. **Hand motion is in the input.** The handset was handled during a parked session, so some
   of the divergence is real inertial input that no navigation solution could attribute to the
   vehicle rather than the phone.
4. **Every threshold is an engineering choice**, not a measured constant: `max_step_s = 0.2 s`
   and `failure_gap_s = 5 s` bound when an interval is held and when the run fails.
5. **The position update is flat-earth** on the anchor's radii of curvature — exact to
   sub-metre over a few kilometres, disclosed rather than corrected.
6. **An unaided INS diverges.** Nothing here is aided, smoothed, or filtered, by design: the
   point of the baseline is to report divergence, not to hide it. Error growth beyond the
   tabulated window is unbounded.
7. **One session, one device, one mount.** Sensor statistics quoted here describe this
   recording, not the hardware model, and no accuracy figure is claimed anywhere.
8. **No AI, no network, no map, no file I/O in the propagation.** The module names none of
   them, and a test checks that structurally.
