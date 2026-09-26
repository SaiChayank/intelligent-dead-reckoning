# PS26168 — Model-Development Methodology

**Scope:** How IDR learning models are trained, validated, tuned, calibrated, tracked, exported, and versioned. Runtime pipeline mechanics are handled separately.

**Architecture context:** the selected architecture is physics-first with specialized learned correction modules. The first intended learning task is a lightweight speed/vibration correction model; fusion-residual and uncertainty models come only after the classical navigation stack exists.

---

## 1. Train / Validation / Test Strategy

**Splitting unit is the drive/recording group, not the row or overlapping window.**

All samples/windows derived from one physical recording must remain in one split.

Why:

- adjacent IMU windows overlap heavily,
- repeated GPS values can span many rows,
- route geometry and speed profile are strongly correlated within one drive,
- duplicate/copy dataset files can otherwise leak near-identical payload into train and test.

### Nominal split

A reasonable starting point is approximately:

```text
60% train
20% validation
20% test
```

but the exact ratio is secondary to group separation and adequate coverage.

### Group identity

For IO-VNBD, group by the actual unique drive/recording payload, not filename alone.

Byte-identical or same-payload copies stay together.

For future Android data, group by:

```text
trip / recording session
route
day
device
mount configuration
```

where possible.

---

## 2. Temporal Splitting

Within grouped data, prefer later runs for validation/test when coverage allows.

Purpose:

- approximate deployment on data collected after training,
- reduce "same-day/same-route" memorization,
- test sensor drift and environmental change.

A strict chronological split is not mandatory if it destroys scenario coverage; any exception must be logged.

---

## 3. Scenario Separation

The final test set should include conditions absent or underrepresented in training.

Examples:

- held-out route,
- held-out speed profile,
- held-out mount angle,
- different vibration/road surface,
- longer GNSS outage,
- different recovery geometry,
- later day/session,
- future different Android device when enough data exists.

Report at least:

1. same-domain held-out trip performance,
2. held-out condition/route performance.

A model that works only on a familiar route has not demonstrated robust dead reckoning.

---

## 4. Leakage Prevention

### 4.1 No row-random split

Never randomly scatter overlapping time windows across splits.

### 4.2 Reference fields are labels only

VBOX/reference:

- position,
- speed,
- heading,
- vehicle acceleration,

may be used as labels/evaluation truth but not deployable outage-model inputs.

### 4.3 GNSS masking discipline

When evaluating DR during an outage:

- remove GNSS updates at/after outage start according to the scenario,
- do not expose future GNSS to feature windows,
- do not use recovery data to correct an earlier state unless running a separately labelled offline smoother study.

### 4.4 Preprocessing fit

Fit on training only:

- normalization mean/std,
- learned filter parameters,
- feature selection,
- PCA if ever used,
- calibration mapping learned from data,
- confidence calibrator.

Validation/test statistics do not influence them.

### 4.5 Duplicate handling

Deduplicate/group before splitting.

### 4.6 Window construction

Causal windows end at prediction time `t`.

No centered window that contains future samples.

### 4.7 Train/serve equivalence

Training features should be generated through the same canonical preprocessing/feature code used by replay/live inference whenever practical.

---

## 5. Condition Imbalance Handling

IDR does not primarily have a simple class-imbalance problem; it has **condition imbalance**.

Examples:

- many straight-road samples, few sharp turns,
- lots of GNSS-good time, limited denial,
- many common speeds, few low/high-speed regimes,
- one dominant route/mount/device.

### Primary mechanism

Balanced sampling/weighting at the **window or sequence-group level** for underrepresented motion/road conditions.

### Rules

- apply only to training,
- validation/test preserve real condition distribution,
- do not duplicate an entire rare drive into several splits,
- report per-condition metrics, not only aggregate.

For classification subproblems such as remount detection, normal class-weight/oversampling methods may be used within training only.

---

## 6. Hyperparameter Tuning

Tune on validation data only.

Examples:

- sequence-window duration,
- convolution kernel/channel count,
- GRU hidden size if tested,
- learning rate,
- dropout,
- regularization,
- tree depth/estimators for classical ML baselines,
- output smoothing constant,
- loss weighting.

### Search strategy

Prefer:

1. small manual/domain-informed range,
2. random search or Optuna-style bounded search,
3. stop once validation improvements become marginal.

Do not spend hackathon time on enormous grid searches.

### Selection

Select by the metric closest to system value.

For a speed model:
- speed MAE/RMSE,
- downstream outage drift impact.

For residual correction:
- held-out position error/drift,
- stability and recovery.

---

## 7. Operating Threshold Tuning

Several modules have decision thresholds even if the main model is regression.

Examples:

- stationary detector,
- remount detector,
- GNSS quality gate,
- innovation rejection,
- model-confidence fallback,
- map-match acceptance.

Thresholds are tuned on validation only.

### Principle

Prefer asymmetric costs reflecting navigation risk:

- false "GNSS good" during a bad fix can corrupt state,
- false remount can unnecessarily interrupt navigation,
- missed remount can poison the entire trajectory,
- overaggressive map matching can snap to the wrong road.

Report the trade-off rather than selecting a threshold because it looks visually smooth.

---

## 8. Confidence / Uncertainty Calibration

Raw model scores or EKF covariance are not automatically calibrated real-world confidence.

### Calibration data

Use held-out validation sessions with independent ground truth.

Compare:

```text
predicted horizontal uncertainty
vs.
actual horizontal error
```

by regime:

- GNSS good,
- degraded,
- denied,
- recovery.

### Methods

Candidate lightweight methods:

- monotonic/isotonic calibration,
- scale-factor calibration,
- conformal-style empirical radius calibration where assumptions fit.

The method is selected only after examining validation behavior.

### Test rule

Calibration fitting uses validation.

Final test is untouched until the calibration method and parameters are frozen.

---

## 9. Experiment Tracking

Each training/evaluation run receives a stable experiment ID.

Minimum metadata:

```text
experiment_id
timestamp
git_sha
dataset_manifest_id
train_groups
validation_groups
test_groups
feature_schema_version
preprocessing_version
model_family
hyperparameters
random_seed
loss
training_metrics
validation_metrics
test_metrics (only after final evaluation)
model_artifact_hash
export_artifact_hash
notes
```

Also record:

- physical-frame interpretation/version,
- synchronization method,
- GNSS-mask definitions,
- initialization mode,
- reference source.

A metric without this provenance is not a reproducible result.

---

## 10. Model Versioning

Recommended model ID:

```text
<task>-v<major>.<minor>.<patch>
```

Example:

```text
speed_tcn-v1.0.0
```

Version changes:

- **major:** input/output semantics, architecture family, feature schema incompatible change,
- **minor:** retrained/tuned model with compatible runtime contract,
- **patch:** packaging/export correction that does not change intended semantics.

Each deployable version freezes:

```text
model file
SHA-256
input schema
normalization/preprocessing
window length
output semantics
training manifest
validation/test report
runtime target
```

Never overwrite a published artifact in place.

---

## 11. Export Validation

For each accepted training model:

1. run native framework inference on fixed golden samples,
2. export to TFLite and/or ONNX,
3. run exported artifact on the same samples,
4. compare outputs within a stated numerical tolerance,
5. measure latency on the actual target,
6. verify hash and metadata,
7. only then mark deployable.

The exported model, not the notebook checkpoint, is what the Android/edge runtime actually uses.

---

## 12. Why One Aggregate Accuracy Number Is Insufficient

A navigation system can have a good average metric while failing the actual SIH problem.

Examples:

- excellent GNSS-good accuracy but severe tunnel drift,
- low speed RMSE but heading failure in turns,
- good mean error with a few catastrophic recoveries,
- good familiar-route performance but poor held-out-route generalization.

Therefore report the actual failure-sensitive metrics.

---

## 13. Metrics

### Speed model

- MAE,
- RMSE,
- bias,
- error by speed regime,
- error by motion condition.

### Heading/orientation

- circular heading error,
- roll/pitch error where truth exists,
- yaw-rate error.

### Navigation / outage

- final outage position error,
- drift percentage,
- mean horizontal error,
- RMSE,
- maximum error,
- error vs outage duration,
- error vs outage distance.

### Recovery

- first accepted GNSS innovation,
- peak correction,
- convergence time,
- post-recovery error.

### Confidence

- empirical coverage of claimed radius,
- calibration error,
- sharpness/radius size.

### Runtime

- inference time,
- full navigation-loop time,
- p50/p95,
- output rate,
- memory,
- drops/errors.

---

## 14. Baseline / Ablation Requirements

Every learned model report includes:

```text
Baseline A: classical system without this learned module
Candidate B: identical system with the learned module
```

Same:
- drive,
- mask,
- initialization,
- map matcher setting,
- evaluation code.

Do not compare AI on an easy segment against classical on a harder one.

### Acceptance

A learned module is accepted only if:

- it improves the metric it was designed to improve on held-out data,
- it does not introduce unacceptable instability elsewhere,
- it meets runtime constraints,
- it preserves causal/deployable inputs.

---

## 15. Reproducibility

Set and record:

- Python/package versions,
- seeds,
- deterministic flags where feasible,
- dataset hashes,
- model export versions.

GPU nondeterminism, if present, is documented rather than ignored.

---

## 16. Team Targets

### Official/problem targets

- outage drift below 10% of reference distance,
- approximately 10 Hz mobile navigation output,
- approximately 200 Hz edge target.

### Internal acceptance targets

Do **not** invent fixed AI-specific accuracy thresholds before a credible baseline exists.

Instead:

- classical baseline must be physically correct,
- learned speed model must outperform its non-ML speed baseline on held-out data,
- AI-enhanced navigation must improve held-out outage performance or be removed,
- model inference must fit comfortably within the mobile loop budget,
- confidence is labelled `calibrated` only after empirical coverage testing.

Numeric secondary targets are frozen only after pilot data establishes realistic baselines.

---

## 17. Test-Set Governance

Once a final test split is frozen:

- do not inspect individual test failures to tune the model,
- do not change preprocessing based on test metrics,
- do not select the best checkpoint on test,
- do not repeatedly publish "test" scores after iterative tuning.

If the test set has been used for development, rename it validation and create a new untouched final test set.

---

**No runtime pipeline or specific final model architecture is implemented in this document.**
