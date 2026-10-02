# Navigation confidence — evaluation report

Date: 2026-10-01. Scope: the uncertainty the fusion engine publishes (`Confidence`), what the map
and the engine panel are allowed to do with it, and the first measured comparison of published
uncertainty against actual horizontal error per regime. Semantics:
[mobile/CONFIDENCE.md](../mobile/CONFIDENCE.md). Architecture requirements:
[docs/PS26168_Navigation_Output_and_Evidence_Architecture.md §3](../docs/PS26168_Navigation_Output_and_Evidence_Architecture.md).

## Result

**The confidence semantics are implemented and evidence-backed; no `CALIBRATED` state is
published, and no calibrated 95% accuracy is claimed.** The engine publishes its covariance as
`UNVALIDATED` with the stated one-sigma → 95% conversion, `probability` stays `null` (a contract
invariant in both languages), the presentation carries the unvalidated radius in its own field,
and the map draws it as its own dashed ring labelled as a model claim. The evaluation that would
have to precede a calibrated claim was built and run on the strongest truth the repository
contains — a scripted trajectory the filter never sees — and its numbers say the covariance is
miscalibrated in opposite directions across regimes, so no calibration is supported yet.

| Stage | State |
|---|---|
| covariance exposed as `UNVALIDATED` | **IMPLEMENTED** — `FusionNavigationEngine.confidence()` |
| provider accuracy kept out of fused confidence | **IMPLEMENTED + TESTED** — 5 tests, listed in `mobile/CONFIDENCE.md` |
| unvalidated radius distinct in the display path | **IMPLEMENTED + TESTED** — own field, own map feature, own label |
| predicted uncertainty vs actual horizontal error, per regime | **MEASURED** (scripted truth, 692 samples) |
| empirical coverage | **MEASURED** — 97.0% all-regime; 64.5% recovery (worst) |
| `CALIBRATED` / `probability` publication | **NOT SUPPORTED BY THE DATA — deliberately not implemented** |

## What was already true, and what changed

The engine already published `Confidence(UNVALIDATED, null, sqrt(5.991) × sigma, speedStd)` — that
part of the initial stage was in place and pinned by
`FusionNavigationEngineTest.confidenceIsTheFilterOwnCovarianceWithAStatedConversion`. What was
missing was any way for an operator to *see* it: `NavigationPresentation` admitted an accuracy only
when the paired state was `CALIBRATED`, so the engine view showed "confidence not published" while
the engine was in fact publishing a covariance, and the evaluation matcher's precision gate could
never fire on real engine output.

Changed:

- `MapPresentation` gained `confidenceState`, `unvalidatedAccuracy95Metres` and
  `speedStdMetresPerSecond`. `accuracy95Metres` keeps its old meaning exactly: calibrated only.
- `NavigationPresentation` splits the paired record by state. Both fields can never be non-null,
  the state travels with the number, and a state that claims nothing (`UNAVAILABLE`) fills neither
  the radius nor the sigma.
- `MapOverlay` emits the unvalidated ring as its own `kind = "uncertainty"`; `MapLibreRenderer`
  draws it as a dashed outline, distinct from the filled area a calibrated or platform radius
  draws.
- `EngineSessionMap` gates the evaluation matcher on `accuracy95Metres ?: unvalidatedAccuracy95Metres`
  — the published uncertainty, never the platform fix radius — which also fixes the overlay only
  ever firing when a `CALIBRATED` record existed.
- `OfflineMapScreen`'s engine panel labels the line with the state it came from, including the
  speed sigma, and the disclaimer states that the radius is unvalidated until an independent
  reference has shown it matches real error.

## The evaluation

`ConfidenceCoverageTest` (host, deterministic, no device and no collected data) replays a
closed-form drive through the production engine:

- truth: 20 s straight north at 12 m/s, an 8 s left turn at 0.2 rad/s, then straight; 70 s total;
- IMU at 100 Hz from the truth, white noise at the engine's own configured densities divided by
  `sqrt(dt)` so the process-noise model matches the data;
- a constant accelerometer bias of 0.02 m/s² and gyroscope bias of 0.001 rad/s injected and never
  disclosed, both inside the filter's own unknown-bias priors (σ 0.1 m/s², σ 0.01 rad/s);
- GNSS at 1 Hz, circular 3 m one-sigma noise reported as a 3 m horizontal accuracy, with speed and
  course (so heading is observable — a stated optimism, since the recorded corpus has no course at
  all);
- a 20 s outage from t = 30 s to t = 50 s;
- error measured against the truth at the same instant; the engine never sees it.

Measured output (`692` published samples, seed 26168; `k95` is the 95th percentile of
`error / radius`, i.e. the scalar the radius would need to make that regime nominal here):

```
regime        n   cover   med err   med r95   max err      k95
GNSS          1  100.0%     4.21 m      7.3 m     4.21 m     0.57
FUSED       470   97.9%     2.62 m      5.7 m     6.22 m     0.72
DR          190  100.0%    40.55 m     49.9 m    84.82 m     0.98
RECOVERY     31   64.5%     5.65 m      6.7 m    11.77 m     1.37
ALL         692   97.0%     3.36 m      6.3 m    84.82 m     0.98
status x mode samples: degraded | dr = 190, tracking | fused = 470,
                       tracking | gnss = 1, tracking | recovery = 31
```

What the numbers say:

- **The `degraded` status and the `dr` mode are the same samples** (190 of them) — the status falls
  exactly when the accepted fix goes outside the provider's stale bound. The DR row *is* the
  degraded-GNSS regime.
- **The outage is real and its covariance grew**: median error 40.6 m, median radius 49.9 m,
  coverage 100%. The dominant term is the undisclosed gyroscope bias leaking tilt into the
  horizontal channels (9.79 m/s² × ≈20 mrad × 20 s² / 2 ≈ 39 m), which is the physically expected
  size for a consumer MEMS bias over a 20 s denial.
- **FUSED is conservative** (`k95 = 0.72`): the radius is about 1.4× larger than the error needs.
- **RECOVERY is optimistic**: 64.5% coverage, `k95 = 1.37`. The radius collapses at the first
  returning fix while the attitude/bias error that caused the drift is still being estimated away,
  so for the three-fix recovery window the filter is more confident than it has earned.
- **No single scalar inflation reconciles them**: FUSED needs a correction below 1 and RECOVERY one
  above 1. A scalar calibration published from this data would be wrong in a way that is invisible
  in the all-regime number (97.0%, which looks healthy and hides the 64.5% recovery row).

## Truth available, and truth missing

The request asks for evaluation against independent ground truth. What exists in this repository:

| Candidate | Available | Verdict |
|---|---|---|
| Sealed experiment with a declared RTK/VBOX reference (`reference/reference.jsonl`) | **no** — `experiments/` contains only its README; no `experiment.json` anywhere | would qualify; must be collected |
| Surveyed/traced trajectory for the recorded drives | **no** — `reports/recording_corpus_2026_09_29.md` states none exists | cannot qualify |
| Real GNSS outage in the corpus | **no** — 41 of 44 sessions carry no GNSS; no GPS interval exceeded 1.001 s | outages must be software masks |
| Scripted truth generated independently of the filter | **yes** — this harness | qualifies as model-consistent evidence only; cannot license a claim about a real drive |

So the harness is the strongest available truth, and its limits are stated in the test header, in
`mobile/CONFIDENCE.md`, and here. The real-drive path (replaying the three GNSS-carrying sessions
with a nominal calibration and comparing against held-out fixes) was considered and rejected as
evidence for calibration: no session carries a real calibration record, so the vehicle attitude is
unobservable and the covariance would be dominated by that prior rather than by the sensor errors
a real drive would exercise. It would measure the harness, not the engine.

## What the next stage must produce before `CALIBRATED`

1. **Collect** at least one sealed experiment per regime with a declared reference
   (`tools/seal_experiment.py`, `tools/validate_experiment.py`), including a genuine denial stretch.
2. **Measure** error against the reference, coverage per regime with a binomial interval, and `k95`,
   using the same definitions this report uses so the numbers are comparable.
3. **Pick the simplest method the split supports** — scalar inflation if one `k` covers every
   regime's interval; otherwise isotonic calibration if the correction is monotone and stable;
   otherwise conformal prediction, which is the only one with a finite-sample coverage guarantee
   and requires the evaluation drives to be exchangeable with deployment.
4. **Freeze the calibration with the filter version**, publish `CALIBRATED` with `probability`, draw
   the filled calibrated ring instead of the dashed unvalidated one, and re-verify on drives that
   were not used to fit it.

Until then the status quo is deliberate: the number is published, labelled `UNVALIDATED`, drawn
dashed, and never called an accuracy.

## Verification

- `:app:testDebugUnitTest` — **331 tests, 0 failures** (was 324; the 7 new ones are the coverage
  harness and the substitution guards).
- `:app:lintDebug` — 0 errors, 5 pre-existing warnings.
- Python suite, contract interop, repository hygiene and the AST compile gate are unchanged by this
  stage and re-run with the results recorded in [CI.md](../CI.md).

Reproduce the measurement:

```powershell
cd mobile
bash gradlew :app:testDebugUnitTest --tests "com.intelligentdeadreckoning.app.ConfidenceCoverageTest" -i
```
