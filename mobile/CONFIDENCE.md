# Navigation confidence — what the engine claims, and what it has earned

The fusion engine publishes an uncertainty about its own solution in the contract's `Confidence`
record. This document is the single place that says what those numbers are, which one of them is a
claim about the vehicle and which is a claim about the filter's own model, where each one is
allowed to travel, and what evidence has actually been gathered. The measured evidence and the
verdict live in
[reports/confidence_evaluation_2026_10_01.md](../reports/confidence_evaluation_2026_10_01.md); this
document is the semantics.

## The record as published

| Field | `UNVALIDATED` today | Notes |
|---|---|---|
| `state` | `unvalidated` | `unavailable` before alignment and after a failed run |
| `probability` | `null` | the contract forbids it unless the state is `calibrated`, in both languages |
| `horizontal_accuracy_95_m` | `sqrt(5.991) × sigma` | a **stated conversion** of the filter's covariance: circular-equivalent sigma `sqrt((sE² + sN²)/2)`, then the 95% circular factor |
| `speed_std_m_s` | `sqrt(svE² + svN²)` | the filter's horizontal velocity sigma, no conversion |

`horizontal_accuracy_95_m` is therefore *the filter restating its own covariance in metres*. It is
not a provider figure, and it is not a validated error bound. The state says exactly that: the
number exists, and nothing has yet shown that it matches real error. `probability` is absent
precisely because a probability is a calibrated claim the state has not earned.

Before alignment and after a `FAILED` run the engine publishes `UNAVAILABLE` with every field
`null`: there is no solution, so there is no honest uncertainty about one.

## Where it is allowed to travel

| Stage | What it does with the record |
|---|---|
| `NavigationRuntime` | forwards the engine's records; it computes nothing |
| `EngineSessionMap` | pairs each `NavigationState` with the `Confidence` of the **same measurement time**; a state whose confidence never arrives is published without an accuracy rather than with an invented one |
| `NavigationPresentation` | reads the paired record only on an exact session / exact timestamp / not-future match, then **splits by state**: a `CALIBRATED` radius lands in `accuracy95Metres`, an `UNVALIDATED` radius lands in `unvalidatedAccuracy95Metres`, and the two fields are never both set. A state that claims nothing (`UNAVAILABLE`) fills neither |
| `MapOverlay` / `MapLibreRenderer` | draws a calibrated radius or a platform fix radius as the filled ring (`kind = "accuracy"`); draws the engine's unvalidated covariance as its own dashed outline (`kind = "uncertainty"`) |
| `OfflineMapScreen` `EnginePanel` | labels the line with the state it came from: `95%, CALIBRATED`, or `filter covariance, UNVALIDATED — not a calibrated accuracy`, plus the speed sigma; `UNAVAILABLE` says so instead of showing a number |
| `EngineSessionMap` matcher gate | consumes `accuracy95Metres ?: unvalidatedAccuracy95Metres`: whichever uncertainty the engine published, never the platform fix radius |

Two invariants make substitution impossible rather than merely discouraged:

- **The Android provider accuracy is never fused confidence.** `GnssMeasurement.horizontal_accuracy_m`
  is a measurement input: it sets that fix's variance inside the filter. It is not copied into
  `Confidence`, and the presentation has no path from `fixRadiusMetres` to either engine field.
- **An unvalidated radius never becomes a calibrated one.** They occupy different fields, are set
  from different states, and are labelled differently wherever they are shown.

`FusionNavigationEngineTest.theProviderAccuracyIsNeverThePublishedFusedAccuracy`,
`FusionNavigationEngineTest.anExcellentProviderAccuracyCannotBuyACalibratedConfidence`,
`NavigationPresentationTest.confidenceRequiresSameSessionSameTimeAndItsStatePicksTheField`,
`NavigationPresentationTest.theUnvalidatedRingIsItsOwnFeatureAndNeverTheProviderRadius` and
`EngineSessionMapTest.anUnvalidatedCovarianceFillsItsOwnFieldAndStillGatesTheMatcher` pin all of it.

## The evaluation

`ConfidenceCoverageTest` replays a **scripted truth** through the production engine: a closed-form
drive (straight, a 92° turn, straight), IMU generated at 100 Hz from the truth, GNSS at 1 Hz with
3 m one-sigma noise reported as 3 m accuracy, a 20 s outage, and a constant accelerometer/gyroscope
bias the filter is not told about but whose size sits inside the filter's own bias priors. Every
published position is compared with the truth at the same instant, so the error is measured against
a quantity the filter cannot have fitted to. The declaration of that model — and its limits — is in
the test's header comment.

Measured 2026-10-01 (692 samples, one drive, seed 26168):

| Regime | n | coverage | median error | median r95 | max error | k95 |
|---|---|---|---|---|---|---|
| GNSS | 1 | 100.0% | 4.21 m | 7.3 m | 4.21 m | 0.57 |
| FUSED | 470 | 97.9% | 2.62 m | 5.7 m | 6.22 m | 0.72 |
| DR (`degraded`) | 190 | 100.0% | 40.55 m | 49.9 m | 84.82 m | 0.98 |
| RECOVERY | 31 | 64.5% | 5.65 m | 6.7 m | 11.77 m | 1.37 |
| **ALL** | **692** | **97.0%** | 3.36 m | 6.3 m | 84.82 m | 0.98 |

`k95` is the 95th percentile of `error / radius`: the scalar the radius would have to be multiplied
by to make that regime's coverage nominal on this data.

## Verdict — not calibrated, and why the message is in the numbers

**No `CALIBRATED` state is published, and no map radius claims a validated 95%.** Two facts from the
table block it, and they are worth stating precisely because they are the first real evidence this
project has about its own uncertainty:

1. **The regimes are miscalibrated in opposite directions.** FUSED is conservative (`k95 = 0.72`:
   the published radius is about 1.4× larger than the error needs) and RECOVERY is optimistic
   (`k95 = 1.37`, coverage 64.5%). No single scalar inflation can fix both — the correction needed
   has a different sign per regime.
2. **The evidence is one synthetic drive under a declared sensor model.** Even a per-regime
   correction fitted here would be fitted to that model, not measured against a real reference. It
   would not transfer, and publishing it as calibration would be exactly the substitution the
   architecture document forbids.

A second, physical point the table makes: the outage's median error is 40.6 m in 20 s, and it is
driven by the undisclosed gyroscope bias leaking tilt into the horizontal channels (9.79 m/s² ×
≈20 mrad × 20 s² / 2 ≈ 39 m). The covariance did grow to bound it — which is why DR coverage is
100% — so the filter's inflation is doing its job; what is optimistic is the *post-recovery* radius,
which collapses at the first returning fix while the attitude/bias error that caused the drift is
still being estimated away.

## What would license `CALIBRATED`

The contract's `calibrated` state is a claim that the published radius has been checked against
held-out, independent ground truth under a defined protocol
([architecture §3.3](../docs/PS26168_Navigation_Output_and_Evidence_Architecture.md)). The
repository's own experiment contract already names the only thing that qualifies: a sealed
experiment with a declared RTK/VBOX-class `reference/reference.jsonl` and software masks for the
degraded/denied regimes (`tools/seal_experiment.py`, `tools/validate_experiment.py`,
[docs/PS26168_Experiment_Data_Collection_Protocol.md](../docs/PS26168_Experiment_Data_Collection_Protocol.md)).
No such experiment exists yet — `experiments/` holds only its README, and the corpus report records
that there is no surveyed trajectory and no real GNSS outage in the recordings
([reports/recording_corpus_2026_09_29.md](../reports/recording_corpus_2026_09_29.md)).

The order of work, and the rule for choosing a method — the simplest one that holds:

1. **Collect the evidence.** Several independent drives with a declared reference, sealed, at least
   one of them long enough to contain a genuine outage; software masks for the repeatable
   degraded/denied cases.
2. **Split and measure.** Per regime (GNSS-good, degraded, DR, recovery), the error against the
   reference, the published radius, coverage, and `k95` — with a binomial interval on coverage, not
   just the point estimate. A regime with too few samples is reported as too few, not as coverage.
3. **Choose the method by what the split shows.**
   - one `k` whose confidence interval covers the needed correction in **every** regime → scalar
     inflation (the simplest thing that could work);
   - a monotone correction that is stable across drives → isotonic calibration of predicted
     uncertainty against error;
   - different corrections per regime, or no distributional assumption that survives → conformal
     prediction on held-out drives, which is the only one of the three with a finite-sample
     coverage guarantee, and the only one whose guarantee requires the evaluation drives to be
     exchangeable with deployment.
4. **Freeze and re-verify.** The calibration method and version are frozen with the filter version
   that produced the covariance, published as `CALIBRATED` with `probability`, and re-measured on
   drives that were not used to fit it. Until step 3 has real truth at its input, the state stays
   `UNVALIDATED`.

## Reproduce

```powershell
cd mobile
bash gradlew :app:testDebugUnitTest --tests "com.intelligentdeadreckoning.app.ConfidenceCoverageTest" -i
```

The coverage table above is printed by that run; nothing in the test asserts the numbers, so a
regression that changes them is visible in the output rather than hidden behind a threshold.
