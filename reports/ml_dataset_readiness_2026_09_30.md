# ML dataset readiness for speed/vibration correction — **BLOCKED**

Date: 2026-09-30. Scope: prepare the first production ML dataset (target: reference
forward speed / correction signal) from canonical/live data and approved IO-VNBD data.

## Verdict

**STOP before schema.** The IO-VNBD frame semantics required for forward acceleration
still cannot be defended, and the dataset's own admission gate approves nothing. No
feature schema, dataset manifest, split manifest, preprocessing script or summary
statistics were produced, because every one of them would have to state a physical
frame for the input channels — and that frame is exactly what is unknown. Nothing was
trained; no model exists. All operations this stage were read-only; raw data is
untouched.

The brief anticipated this outcome explicitly: *"If the IO-VNBD frame semantics
required for forward acceleration still cannot be defended, STOP and report the
blocker rather than guessing."* This report is that stop.

## Requested artifact → status

| Requested | Status | Why |
|---|---|---|
| Training schema (fields, units, frames) | **not produced** | The input frame column cannot be filled without guessing; see blocker 1 |
| Deployable-input vs reference-label separation | **not produced** | The reference side is fine (below); the deployable side is the blocked half |
| Dataset manifest | **not produced** | Would certify readiness for data the admission gate refuses |
| Split manifest | **not produced** | Group structure is knowable (below) but a manifest implies an admitted dataset |
| Feature-schema version | **not produced** | A versioned schema freezes unknown frames |
| Reproducible preprocessing script | **not produced** | Preprocessing requires the frame contract it would encode |
| Leakage-check tests | **not produced** | They test a pipeline that must not exist yet; writing them now would imply one could |
| Summary statistics | **not produced** | Statistics over inputs whose meaning is unknown are not interpretable |
| Training | **not run** | Forbidden this stage regardless; also blocked |

## Fresh verification performed this stage

All numbers below were measured by this stage, read-only, against the current
`data/raw/iovnbd/` tree (not copied from earlier reports).

**1. The export-frame contradiction, reproduced.** On `Synchronised …/M (Driver
B)/S-M.csv` (105,974 rows):

| Measurement | Result |
|---|---|
| GRAVITY norm | median 9.8066 m/s² (p05–p95: 9.8065–9.8066) |
| angle(GRAVITY, accelerometer +Z) | median **0.063°**, p95 0.282° |
| GRAVITY direction spread about its mean, in the accelerometer basis | median **0.063°** |
| angle(GRAVITY, orientation-implied device up) | median **97.209°** (p05 93.404°, p95 100.968°) |
| ORIENTATION pitch / roll range over the same file | −89.93° … +73.64° / −180° … +180° |
| ‖accelerometer − gravity‖ | median 1.089 m/s², p95 3.840 m/s² |

Interpretation: the exported gravity never leaves the accelerometer basis's +Z axis
(0.063°) even while the orientation export sweeps through ~160° of pitch and full
±180° of roll — the ACCELEROMETER/GRAVITY pair lives in one gravity-aligned basis that
does **not** follow the orientation export. And the orientation-implied device up sits
~97° away from the exported gravity. The two candidate "device-frame" readings
contradict each other by a right angle; they cannot both be ordinary device-frame
quantities. At most one of the two is device-frame, nothing in the files arbitrates,
so **the physical axes of `ACCELEROMETER X/Y/Z` relative to the phone — and therefore
to the vehicle — are not recoverable from the dataset.** These numbers reproduce the
recorded findings in [frame_resolution_followup.md](frame_resolution_followup.md)
(M: 97.209°, exported-gravity tilt 0.063°) with independent code.

**2. The label side is defensible.** The VBOX file header was read directly:
`Velocity (km/hr)`, `Indicated Vehicle Speed (km/hr)`, `Indicated Longitudinal /
Lateral Acceleration (g)`, `Heading (degrees)`, `Yaw Rate (deg/sec)`. The conversions
(`v = km/h ÷ 3.6`, `a = g × 9.80665`) and the sign conventions were verified against
physics in [body_frame_conventions.md](body_frame_conventions.md) (forward speed ×
left-positive yaw rate reproduces lateral acceleration at correlation 0.917–0.973
across six sequences). **Reference forward speed as a *label* is sound.** The blocked
half is the input side.

**3. Duplicates confirmed.** Byte-identical copies exist across the categorized and
uncategorized trees (e.g. `V-S1.csv` 10,967,129 bytes in both; `V-Vw2.csv` 11,341,576
in both), matching the row-level audit: 241 smartphone CSVs reduce to **97 recording
groups** (72 × 3 copies + 25 unpaired), all 144 copy comparisons equal to 1e-12.
Grouping by payload, not filename, is mandatory — and is knowable without frames.

**4. No pair is synchronised exactly.** [synchronization_analysis.md](synchronization_analysis.md)
classes every pair `approximate` or `uncertain`; no pair is declared exact, and its
fitted lags are explicitly "descriptive, not approved corrections". Supervised windows
would join inputs to labels at an unverified time offset.

**5. The admission gate refuses everything.** [training_admission.json](training_admission.json):
`decision: "no-go"`, `approved_sequence_names: []` — **0 of 144 pairs approved**;
blocking reason: "Verified complete IMU frame, IMU-to-label alignment, reproducible
corrected baseline and deployable preprocessing parity have not passed."

**6. Canonical/live data cannot substitute.** 44 recorded sessions (688,345 records,
[corpus report](recording_corpus_2026_09_29.md)) carry no reference-grade speed: this
project has no reference instrument on the phone, and 41 of 44 sessions contain no
GNSS at all. There is nothing to use as a *label* outside IO-VNBD, so the input-side
blocker blocks the whole supervised dataset.

**7. Evidence freshness.** The frame follow-up and admission policy were committed
2026-09-18 (5b6add0), the most recent frame work in git history; HEAD (e122ccc) adds
only CI/docs. Nothing has changed the verdict since.

## Why the tempting workarounds are guesses

- **Rotation-invariant features** (‖accel − gravity‖, ‖gyro‖) dodge the frame question
  but also dodge the target: magnitude discards sign and direction, so braking and
  accelerating become identical and *forward* speed is no longer physically connected
  to the input. That is a different task than the one specified, chosen to avoid the
  blocker — guessing.
- **"The export is world-frame"** is contradicted by the 97° measurement above: the
  gravity/orientation pair is internally inconsistent, so no single world rotation
  repairs both.
- **The paper's mounting figure** (travel ≈ device x) cannot set CSV axes: measured
  gravity lies on export +Z, which the figure's labeling contradicts; a diagram is an
  intended mounting description, not an export contract.
- **Fitting a mounting against VBOX** is exactly what the audit already attempted:
  all three unfiltered hypotheses passed **0 of 127** windows; the filtered
  reconstruction passed **1 of 127**, no recording passing all its windows; fitted
  rotations for one recording spread up to **169.87°** between windows. Doing it again
  with the same data would still be fitting inputs to the labels we would then evaluate
  against — leakage by construction, and forbidden by admission ("no per-test-sequence
  fitted lags may be imported").

## What is preserved for when this unblocks

- **Label contract**: VBOX speed/heading/yaw/acceleration channels, units and signs as
  specified in body_frame_conventions.md — ready to adopt unchanged.
- **Group structure**: 97 unique recording groups with their copy clusters; split
  policy already written in
  [PS26168_Model_Development_Methodology.md](../docs/PS26168_Model_Development_Methodology.md)
  (group by drive/recording payload, never row-random, copies never cross splits,
  `train_groups` / `validation_groups` / `test_groups`).
- **Causality/leakage policy**: already specified there too (causal windows, train-only
  normalization, no future GNSS/reference in inputs) — implementable the day the frame
  contract exists, and independent of it.
- **The deployable input contract already exists** for *Android* data:
  `contracts/v1` device-frame channels with verified acquisition semantics. The durable
  fix is ground-truthed Android drives (Gap 4 of the readiness review), not a cleverer
  interpretation of the old export.

## Acceptance checklist to resume this stage

1. Recover the original export contract (AndroSensor version/settings, export
   equations, matrix direction, units) from the dataset maintainers, **or** run the
   controlled calibration protocol specified in frame_resolution_followup.md
   (six-face rest poses, ±90° signed rotations, documented mount, straight runs with
   synchronized reference).
2. A device-to-vehicle rotation that passes the existing declared gates on
   calibration *and* validation halves (corr ≥ 0.7, slope 0.7–1.3, NRMSE ≤ 0.8, both
   axes, no refitting) — or replacement ground-truthed Android data.
3. IMU-to-label time alignment classed *exact* (or a shared monotonic clock), with no
   fitted per-test lag imported.
4. `training_admission.json` lists at least one approved sequence.
5. Then re-run this stage end-to-end: schema → dedup/groups → splits → causal windows →
   train-only normalization → leakage tests → manifests and summary statistics.

## Reproduction

```bash
# read-only; no file under data/raw/ is modified
.venv/Scripts/python.exe -B -X utf8 -c "<the measurement snippet in this stage's transcript>"
git log --oneline -- reports/frame_resolution_followup.md reports/training_admission.json
```

**Nothing in this stage trained a model. The dataset is not ready; the blocker is the
same one recorded at Phase 0, now re-measured against today's data.**
