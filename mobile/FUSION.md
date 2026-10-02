# Classical GNSS+INS fusion: a 15-state error-state EKF

`app/src/main/java/.../fusion/` — `GnssInsEkf.kt`, `FusionNavigationEngine.kt`,
`FusionTypes.kt`, `Geodesy.kt`, `Matrices.kt`

One question: **while GNSS is unavailable, degraded, lying, or slow, how does the vehicle keep a
position it can honestly publish — and when GNSS returns, how does it reconcile without
teleporting?** The filter answers it with a classical error-state Kalman filter whose prediction is
the strapdown mechanization already validated by the offline baseline ([training/strapdown_ins.py](../training/strapdown_ins.py),
[reports/ins_baseline_2026_09_30.md](../reports/ins_baseline_2026_09_30.md)). It emits only the
frozen contract's `NavigationState`, `Confidence` and `DiagnosticEvent` — nothing else leaves the
engine, and the frozen codec round-trips every one of them.

## State

The **nominal** state is position, velocity, attitude (ENU-from-device quaternion) and the two bias
vectors. The **error** state, the thing the covariance describes, is fifteen numbers:

| Block | Indices | Frame | Units | Prior source |
|---|---|---|---|---|
| position | 0–2 | ENU, from the anchor | m | fix accuracy × 1 horizontally, × 3 vertically |
| velocity | 3–5 | ENU | m/s | the fix's speed+course when present, else ≥ 2 m/s |
| attitude error | 6–8 | navigation frame, `q_true = exp(δ) ⊗ q̂` | rad | tilt (5°)², heading (10°)² supplied or half a turn |
| gyroscope bias | 9–11 | device | rad/s | calibration prior, else 1e-4 variance |
| accelerometer bias | 12–14 | device | m/s² | calibration prior, else 1e-2 variance |

An **error-state** formulation is used rather than a full-state one for three reasons: the nominal
attitude is propagated by the same validated mechanization the baseline runs, so the on-device
trajectory never forks from the offline one; the attitude error is a three-parameter rotation
vector, so the quaternion never enters the covariance and stays a proper rotation by construction;
and the covariance stays meaningful at speed, which a full-attitude parameterization would not.

Every initial variance must be finite and strictly positive — a zero variance would claim a
perfectly known state and make the first update divide by zero, so `align()` refuses it.

## Alignment: the one moment state comes from outside

The first usable fix supplies the anchor and the initial state. This is deliberately **not** a
measurement update: it is the single point where outside evidence sets the state, kept separate
from `updateGnssPosition`, which is what every later fix — including the first one after an outage
— goes through. Without a valid calibration the engine does not align at all
(`CALIBRATION_REQUIRED`): the vehicle frame and therefore the vehicle heading are unknown, and
reporting an attitude that cannot be justified is worse than reporting none. With no supplied
heading the prior is half a turn of variance and `FUSION_HEADING_UNOBSERVED` says so — GNSS course
was null on every recorded fix, the magnetometer is deliberately unused, and motion alignment is
not implemented.

## Prediction

Driven entirely by inertial samples, exactly as the validated baseline:

- attitude `q ← navIncrement ⊗ q ⊗ bodyIncrement` with the navigation frame's own rotation
  (Earth rate + transport rate); specific force rotated into ENU, Coriolis and WGS-84 gravity
  subtracted; trapezoid velocity/position integration. The **interval comes from timestamps**,
  never from a nominal rate.
- error covariance `P ← ΦPΦᵀ + Q` with `Φ = I + F·dt` (`O(dt²)` terms are 1e-4 relative at 100 Hz)
  and an **exact** double-integrator `Q` including the position–velocity cross term, so the two
  blocks cannot drift out of consistency with each other.

Timing semantics — each broken case has its own named outcome rather than being smoothed over:

| Interval | Outcome | Effect |
|---|---|---|
| identical timestamp | `DUPLICATE_TIMESTAMP` | counted, nothing propagated (no time passed) |
| backwards timestamp | `BACKWARDS_TIMESTAMP` | counted, never integrated |
| > `maxStepS` (0.2 s) | `HELD_GAP` | state held, `Q` still added — unobserved motion becomes uncertainty |
| > `failureGapS` (5.0 s) | `FAILED_GAP` | run stops (`TIME_GAP_EXCEEDS_LIMIT`); no trajectory is invented |
| non-finite sample | `FAILED_NON_FINITE` | run stops (`NON_FINITE_SAMPLE`) |

The two timing limits are the baseline's own `max_step_s` / `failure_gap_s`, not new numbers.

## Measurement updates

| Measurement | Rows | Built when |
|---|---|---|
| GNSS position | 2 horizontal + 1 vertical | always; vertical only with a measured **ellipsoidal** altitude, an anchor that has one, and a stated positive vertical variance |
| GNSS course velocity | 2 (east, north) | speed **and** bearing present |
| GNSS speed alone | 1, along the filter's own heading | speed present, bearing absent, horizontal speed ≥ 1 m/s |

Semantic rules that are not negotiable:

- A fix with **no horizontal accuracy** never reaches the filter — there is no honest way to form
  its covariance, and inventing one would fabricate the number that decides whether it is believed.
  The same pre-filter refusal covers invalid coordinates, unknown providers, poor accuracy and
  satellite counts below the policy minimum, each with a named reason (`GNSS_MEASUREMENT_REFUSED`).
  Every bound is read from `GnssQualityPolicy`, so the fusion boundary and the published quality
  state cannot drift apart.
- The provider's accuracy is a one-sigma radius, so the variance is its square, unconverted.
- MSL altitude is not mixed with the ellipsoidal anchor; without all three vertical preconditions
  the vertical row is simply **not built**, and the horizontal rows are still applied. A missing
  field loses its own row, never the whole measurement.
- Speed without bearing is a magnitude, not a direction: it constrains only the component along
  the direction the filter already believes, and while stationary that direction does not exist, so
  the row is refused.
- Magnetometer: deliberately unused — no invented measurements of any kind.

## Vehicle-motion constraints (ZUPT / NHC)

Approved by the constraints stage and documented in
[reports/constraints_ab_2026_09_30.md](../reports/constraints_ab_2026_09_30.md):

- **Zero-velocity update** (`updateZupt`): three ENU velocity rows asserting v≈0, gated by the
  rolling stationary detector (the declared 2 s resting rule), the filter's own speed
  (hysteretic: engage < 0.5 m/s, disengage > 2 m/s), and non-contradicting fresh GNSS speed.
  During an outage the ZUPT owns the parked regime.
- **Non-holonomic constraints** (`updateNhc`): vehicle lateral and vertical velocity ≈ 0, as rows
  along the ENU columns of the calibrated vehicle attitude — forward velocity is untouched.
  Gated by calibration validity, speed ≥ 2 m/s, yaw rate ≤ 0.5 rad/s, measured longitudinal
  specific force ≤ 0.35 m/s² (accel/brake/grade), a 5 s benign dwell, fresh GNSS (≤ 3 s), and a
  2 s backoff after refusals. Stands down entirely during outages — level cruise and parked are
  IMU-identical, and the fresh course-velocity row is the evidence that separates them.
- Both pass the same joint NIS gate and Joseph update as GNSS rows, with deliberately loose
  variances ("tune conservatively"). Constraint refusals are booked separately and never touch
  the GNSS counters or the persistent-rejection guard. `motionConstraintsEnabled = false`
  restores the plain filter (the A/B arm).
- **Measured on held-out outage drives**: outage-end error −71%, outage RMSE −68%, stop drift
  −50%, final error −2.5% versus the plain filter, with no regressions. The report also records
  the four failure mechanisms the gates exist to prevent — each caught by the A/B harness, not
  assumed.

## Gating

Gating is on the **joint** normalised innovation squared `νᵀS⁻¹ν` for the whole measurement against
the chi-square quantile for its own dimension at 0.999 confidence — `10.8276 / 13.8155 / 16.2662`
for 1/2/3 degrees of freedom, quoted from the standard table rather than recomputed. A two-row
position update is judged once against the 2-dof threshold, not twice against the 1-dof one. Only
after passing is the measurement applied, row by row, each row recomputing its gain against the
covariance the previous row left behind, with the **Joseph form** `P = (I−KH)P(I−KH)ᵀ + KRKᵀ` so
positive-definiteness survives the update. The error state is folded into the nominal state
(`inject`) and the error is zero again — the correction never partially applies: a rejected
measurement is discarded whole and `correctionM` is exactly 0.

Outcomes: `ACCEPTED`, `REJECTED_GATE` (failed the chi-square test), `REJECTED_INVALID`
(non-finite, or variance not positive), `REJECTED_ILL_CONDITIONED` (S would not decompose, so
gating itself is meaningless).

## Rejected-update diagnostics

`GNSS_UPDATE_REJECTED` (warning) reports the count since the last report, the NIS, the threshold,
the degrees of freedom and the raw innovation magnitude, and states explicitly that nothing was
applied partially. `GNSS_MEASUREMENT_REFUSED` (warning) does the same for pre-filter refusals.
Both are rate-limited to one per second per kind and count the events they skip, so a stream of
bad fixes cannot flood the log while still being counted honestly. The gate book is open:
`FusionSolution` exposes `acceptedUpdates`, `rejectedUpdates`, `duplicateTimestamps`,
`backwardsTimestamps`, `heldGaps` and `lastRejectionNis`.

### The persistent-rejection guard

An innovation gate is a statistical test against a covariance the filter reports **about itself**,
so it is not robustness against a persistent lie: once the filter honestly admits enough
uncertainty, a measurement it was refusing becomes admissible. The opposite failure is just as
real and much worse — the solution drifts away from a stream of arriving fixes, the gate refuses
all of them, and the position grows without bound while every rejection is individually
defensible. A stretch of refusals that long means one of the two is wrong and this filter cannot
tell which, so it stops claiming a solution (`PERSISTENT_INNOVATION_REJECTION`, published once as
`ENGINE_FAILURE`, status `FAILED`, all spatial fields nulled per the contract) instead of
publishing an unbounded one.

- The stretch is **contiguous** and counts only intervals between measurements that followed one
  another: a pause longer than `maxRejectionGapS` (5 s) resets it, because a stream that stopped
  is an **outage**, and outages are coasted through however long they last.
- It trips after `maxRejectionSpanS` (30 s) of unbroken refusal.
- **Only an accepted position update resets it.** Velocity rows belong to the same fix but
  constrain how the state moves, not where it is: a solution that has lost its position can accept
  every velocity row it is offered and still run away, and letting a speed acceptance reset the
  span would let a fix whose position is refused forever keep the guard from ever firing — the one
  thing it exists to catch.

Both limits are chosen, not measured: long enough for the covariance to legitimately grow its way
back to reopening the gate (a few tens of seconds in the synthetic cases), short enough that a
runaway is caught while it is still metres rather than kilometres. Recovery from the guard is a
deliberate re-alignment, never a silent teleport.

## Outage and recovery

- **Outage is prediction-only.** With no accepted fix within the provider's own stale bound (the
  same per-provider bound the quality state machine uses, so the 0.05 Hz network channel is not
  called an outage for being slow), status falls to `DEGRADED`, `FUSION_GNSS_OUTAGE` is published
  once, and the covariance grows with the unobserved motion. The coast is unbounded in time — a
  600 s synthetic outage never trips the rejection guard, because nothing is arriving to refuse.
- **Recovery is a convergence, not a snap.** The returning fix goes through the same gated update
  path as any other; the correction is the Kalman gain times the innovation, so a fix tens of
  metres away moves the state by a fraction of that and the remainder is taken out over subsequent
  updates. `FUSION_GNSS_RECOVERED` reports the actual correction magnitude that was applied. The
  tests pin that the state does not jump to the first returning fix (recovery error is bounded,
  and the anchor position of a persistently lying stream stays >2.9 km from the lie while the
  state stays within 1 m of truth).
- **The status never changes silently.** Freshness uses `lastAcceptedGnssNs` — the engine's own
  acceptance clock — so a fix accepted as the anchor does not report an outage in the moments
  after aligning on a fix the solution is literally standing on.

## Published outputs

`NavigationState` / `Confidence` / `DiagnosticEvent` only, at 5 Hz or immediately on status
change. A `FAILED` run publishes no spatial fields even though it still holds an anchor and a
finite state — the contract forbids them and it is right to: the whole point of the failure is
that the state stopped describing the vehicle. `horizontal_accuracy_95_m` is the stated
one-sigma → 95% conversion (`sqrt(5.991)` × circular-equivalent sigma), not a provider figure and
not a validated error bound; `probability` stays `null` because the covariance is a model output
that has not been validated against an independent truth, and the contract forbids a probability
unless the state is `CALIBRATED`.

The whole confidence story — what each field means, where it is allowed to travel, the measured
coverage per regime and what would license a calibrated claim — is
[mobile/CONFIDENCE.md](CONFIDENCE.md). In the map and the engine panel the unvalidated radius is
carried in its own field, drawn as a dashed ring and labelled as a model claim; a `CALIBRATED`
radius is the only thing that may fill the calibrated field or draw the solid ring. As of
2026-10-01 the measured coverage (scripted truth, 692 samples) is 97.9% fused, 100% DR and 64.5%
recovery, which supports publishing the covariance as `UNVALIDATED` and does not support a
calibrated claim in any regime.

The engine session speaks contract **1.1.0**, and each published position names its regime in
`localization_mode` — the field that version added:

- `gnss` — the solution is standing on an anchor fix: no accepted update or propagation step has
  moved it off that fix yet;
- `fused` — the integrated solution with current GNSS aiding;
- `dr` — no fix has been accepted inside the provider's own stale bound, so the position is
  inertial dead reckoning alone (the same per-provider bound the status machine uses, so a slow
  network channel is not called an outage);
- `recovery` — GNSS aiding resumed after a DR stretch and the solution is still converging: the
  first `localizationRecoveryFixes` (3) accepted fixes after a stale interval stay `recovery`,
  then the mode returns to `fused`.

The mode is `null` exactly when the state carries no position, and `fused`/`recovery` cannot be
published without `gnss_used_after_initialization = true`; both are contract invariants checked by
the codec in both languages. `NavigationRuntime` creates an engine session only for a 1.1.0 header
(`ENGINE_OUTPUT_CONTRACT_VERSION`), so an output stream that cannot carry the mode is refused at
the seam instead of being published as something it is not.

## Diagnostics

| Code | Severity | When |
|---|---|---|
| `FUSION_SESSION_STARTED` / `FUSION_SESSION_STOPPED` | info | engine lifecycle |
| `FUSION_CALIBRATION_ADOPTED` | info | a valid calibration was taken in |
| `CALIBRATION_REQUIRED` | warning | a usable fix arrived with no valid calibration, or `TRACKING` is blocked by it |
| `FUSION_ALIGNED` | info | the anchor was set, with the fix's accuracy and altitude note |
| `FUSION_HEADING_UNOBSERVED` | warning | no initial heading was supplied; the prior is half a turn |
| `FUSION_ALIGNMENT_FAILED` | error | a non-positive/non-finite variance was refused |
| `GNSS_MEASUREMENT_REFUSED` | warning | pre-filter refusals, with the named reason, rate-limited |
| `GNSS_UPDATE_REJECTED` | warning | gate rejections, with NIS/threshold/dof/innovation, rate-limited |
| `FUSION_GNSS_OUTAGE` / `FUSION_GNSS_RECOVERED` | warning / info | status left / re-entered freshness |
| `FUSION_TIME_GAP` / `FUSION_TIMESTAMP` | warning | held gap / duplicate or backwards timestamps |
| `FUSION_IMU_PAIRING` / `FUSION_IMU_UNIT` / `FUSION_IMU_FRAME` / `FUSION_RECORD_KIND` | warning | unusable IMU input |
| `ENGINE_FAILURE` | error | the filter stopped; published once per run |

## Where the numbers came from

| Number | Value | Provenance |
|---|---|---|
| accel / gyro noise density | 0.05 m/s²/√Hz, 0.005 rad/s/√Hz | **Chosen, not measured.** Typical consumer-MEMS order of magnitude; a device figure needs a static Allan-variance run on the phone, which has not been done |
| bias random walks | 1e-4 m/s²/√s, 1e-5 rad/s/√s | Chosen, not measured (same reason) |
| `maxStepS` / `failureGapS` | 0.2 s / 5.0 s | The validated baseline's `max_step_s` / `failure_gap_s` |
| gate probability | 0.999 | Chosen: the covariance is model-derived and optimistic, so a tighter gate would reject good fixes; the tests report what it actually rejects |
| rejection span / gap | 30 s / 5 s | Chosen, not measured — see the guard above |
| speed floor for speed-only rows | 1 m/s | Below it a speed has no direction to apply |
| publication rate | 5 Hz | Faster than any fix rate in the corpus, slower than the IMU |

## Findings from the synthetic suite

1. **Bias convergence needs realistic priors *and* realistic noise.** Starting from zero bias with
   priors a few times the injected truth, 120 s of accelerate–turn–accelerate with 5 m GNSS noise
   pulls the gyro estimate past half the injected value in x and z while the position error stays
   under 20 m. Two failure modes are pinned by the same test's comments and setup: priors much
   looser than a few times the bias let the state absorb the mechanization's own integration error
   (which is not a bias and does not point at the truth), and with noiseless GNSS the bias
   signature and the filter's integration error are the same size, so the estimator fits the
   latter. A zero-bias instrument is **not** given a fabricated bias (estimates stay within 1e-3
   rad/s and 1e-2 m/s² of zero).
2. **Unobservable yaw saturates instead of diverging.** Over 1500 s of straight driving on 10 m
   fixes with no heading or course anywhere — the regime the corpus is actually in, `bearing_deg`
   null on all 1,782 recorded fixes — yaw error is unobservable in the classical sense (rotating
   about up does not change a vertical specific force's ENU projection). The yaw variance
   saturates rather than diverging (sigma < 1.0 rad and below twice its halfway value), the gyro
   bias it is coupled to settles (|z| < 0.05 rad/s), and the position error stays at the scale of
   the fix noise. That is why the unobservable yaw is reported honestly in diagnostics rather than
   papered over.
3. **The gate believes its own covariance, and that is a documented limitation.** A sustained lie
   is eventually admitted because the covariance honestly grows until the refused measurement
   falls inside the gate; the rejection guard does not resolve that case, it stops the run. The
   companion test shows the other direction: a 3 km cross-track lie is refused position-update
   after position-update while course-velocity rows of the *same* fixes are accepted (they say how
   the vehicle moves, not where it is), and the state stays within 1 m of truth.

## What this does not claim

- **The noise densities are chosen engineering values, not measurements of this device.** They are
  the only numbers in this stage a datasheet or an Allan-variance run could replace, and until one
  exists the covariance is a model output, not an error bound.
- **No accuracy claim against ground truth.** Every fusion test is deterministic and synthetic;
  there is no surveyed trajectory in the repository to validate metres against. The corpus has no
  recorded GNSS outage either (4 of 45 recordings carried GNSS, no GPS interval exceeded
  1.001026 s), so outage and recovery are synthetic by necessity, as in the quality stage.
- **A gate is not robustness against a persistent lie.** The sustained-lie test exists to say
  this out loud rather than imply otherwise; the guard bounds the damage, it does not remove it.
- **Yaw is unobserved** without a supplied heading, GNSS course or motion alignment, and the
  reported heading is only as good as its covariance says it is.
- **No heading on a device yet.** `heading_deg` requires a valid calibration — the vehicle
  attitude — and no screen collects or composes a calibration record, so the engine publishes
  position, speed and localization mode while `heading_deg` stays null. The map shows `—` rather
  than approximating course as heading.
- **One device, one mount, one operator** for everything upstream of this filter.

## Reproduce

```bash
cd mobile && bash gradlew testDebugUnitTest lintDebug --offline --console=plain
```

`FusionFilterTest` covers the filter alone (perfect / noisy / biased GNSS, outage, recovery,
covariance growth, bias convergence, repeated timestamps, time gaps, long-drive boundedness);
`FusionNavigationEngineTest` covers the engine boundary (canonical output through the frozen
codec, refusal policy, calibration gating, outage status, the lying stream); `GeodesyTest` pins
the gravity, radii, Earth-rate and transport-rate values against the Python baseline to the digit.
The localization-mode sequence (gnss -> fused -> dr -> recovery -> fused), the null-mode states and
both modes through the frozen codec are pinned by
`FusionNavigationEngineTest.localizationModeNamesTheRegimeHoldingUpThePosition` and
`noPublishedPositionCarriesNoModeAndBothSurviveTheFrozenCodec`, and the version seam by
`NavigationRuntimeTest.engineSessionRefusesAContractVersionThatCannotCarryEngineOutput`.
