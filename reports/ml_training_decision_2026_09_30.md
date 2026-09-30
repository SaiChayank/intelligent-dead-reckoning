# First AI speed/vibration correction model — **NOT RUN: precondition failure**

Date: 2026-09-30. Stage requested: train and compare a classical/integration
baseline, a Ridge/linear baseline, a tree/boosting or compact MLP baseline, and a
small causal 1D CNN/TCN on the *approved* dataset; evaluate by regime; inject the
winner into the classical navigation pipeline; record an experiment and make a
model-selection decision.

## Verdict

**No training was performed, because the approved dataset does not exist.** The
stage's own precondition — "using the approved dataset" — is unmet, and the
preceding stage did not pass: [ml_dataset_readiness_2026_09_30.md](ml_dataset_readiness_2026_09_30.md)
stopped at the IO-VNBD frame-semantics blocker, and the dataset brief of that stage
said in advance, "Do NOT train a model until this stage passes."

Fresh verification performed this stage, all read-only:

| Check | Result |
|---|---|
| `reports/training_admission.json` | `decision: "no-go"`, `approved_sequence_names: []` |
| pair-level flags | **0 of 144** pairs have `approved_for_training: true` (all `false`) |
| other approval artifacts in `reports/`, `models/`, `training/` | none found |
| `models/README.md` policy | "No model has been trained or approved... Do not add weights here before that gate opens" |
| `data/processed/` | empty (no dataset manifest, no splits, no preprocessing output) |
| git HEAD | `e122ccc` — unchanged since the stage-8 blocker report |

## Experiment record (as far as it honestly goes)

| Requested field | Recorded value |
|---|---|
| experiment ID | **none assigned** — no experiment was created |
| git SHA | `e122ccc` (2026-09-30 02:15:05 +0530) |
| data manifest | **does not exist** — stage 8 blocked before producing it |
| split manifest | **does not exist** — same |
| preprocessing version | **does not exist** — same |
| hyperparameters | none searched (the brief's "do not over-search" is moot) |
| model hash | no model artifact; `models/` untouched |
| validation/test metrics | none measured |

## Why running the comparison anyway would produce unrecordable numbers

Each of the four requested model families would have to consume input features
whose physical meaning is undefined:

1. **Undefined inputs.** The IO-VNBD `ACCELEROMETER/GRAVITY` export basis cannot be
   mapped to the phone, let alone to vehicle forward (fresh measurement: gravity
   pinned 0.063° to export +Z while orientation-implied up sits 97.2° away).
   A "forward acceleration" feature would be a guess, and every downstream metric
   (speed MAE/RMSE/bias, per-regime breakdowns) would inherit that guess.
2. **Unverified label alignment.** No phone/VBOX pair is synchronized exactly —
   all are `approximate` or `uncertain`, fitted lags explicitly "not approved
   corrections" — so targets would be joined to inputs at unknown offsets.
3. **Splits over refused data.** A held-out route/drive evaluation on pairs the
   admission gate refuses (including the 10 `conditional_diagnostic_candidate_only`
   sequences) would lend false credibility to numbers the project's own governance
   marks no-go, and the 127 audit windows are additionally pre-spent as development
   data and must not become a test set.
4. **Injection into the navigation pipeline.** Feeding a correction derived from
   undefined features into the stage-7 classical pipeline would put an unvalidated
   term into a safety-relevant output (`NavigationState`), which stage 7 carefully
   keeps free of unapproved measurements. The injection harness should exist only
   after a model is legitimately selected.

Any of the four would be fabricated evidence. The brief's integrity requirements
(no fabricated claims, approved data only) and `models/README.md`'s gate both
forbid it.

## What unblocks this stage

Everything in the stage-8 acceptance checklist
([ml_dataset_readiness_2026_09_30.md](ml_dataset_readiness_2026_09_30.md)), in order:

1. Frame/export contract recovered **or** controlled calibration run (six-face rest,
   ±90° signed rotations, documented mount, straight reference runs).
2. A device-to-vehicle rotation passing the existing declared gates on calibration
   and validation halves — or replacement ground-truthed Android drives.
3. IMU-to-label alignment classed exact (shared monotonic clock).
4. `training_admission.json` lists at least one approved sequence.
5. Stage 8 re-run end-to-end: schema → dedup/groups → splits → causal windows →
   train-only normalization → leakage tests → manifests + summary statistics.

Once the manifests exist, this stage can run verbatim as specified: the four-model
comparison, the regime evaluation matrix (speed regimes, stationary,
acceleration/braking, turns, rough segments, held-out drive), the injection into the
classical pipeline (final outage error, drift %, position RMSE), and the selection
decision — with the experiment record fields above filled with real values.

## Confirmations

- **No model trained. No weights written.** `models/` contains only its README.
- **No data modified.** Raw tree untouched; `data/processed/` remains empty.
- **No pipeline scripts half-built** that could be mistaken for a ready preprocessing
  version; the gate opens with a clean stage-8 re-run.
