# Export-frame investigation and remaining calibration requirements

## Verdict

**Confirmed from data:** the source-header correspondence is resolved for the
72 synchronized smartphone recordings: categorized gyro `Yaw/Pitch/Roll`
correspond to `X/Y/Z`, respectively, in matching copies. These are export labels,
not vehicle yaw/pitch/roll axes. No change was made to the raw headers or values.

**Inferred with supporting evidence:** those recordings contain world-like
acceleration/gravity exports alongside device-oriented magnetic/orientation
information. A single fixed rotation applied to all these fields is not a
defensible raw-IMU contract. The exact logger configuration remains unavailable.

**Still unresolved:** a verified complete gyro triad in physical device axes,
the precise acceleration export transform and timing, and a stable validated
device-to-vehicle mounting rotation. Full 3-D strapdown Phase 1 remains blocked.
There is a promising local filtered reconstruction, but no whole-recording
calibration approval. No INS propagation, AI, fusion or Android implementation
was performed in this follow-up.

This supplements, rather than overwrites, the historical
[body-frame conventions](body_frame_conventions.md). In particular, the earlier
prototype `CausalCalibration` requires device-frame inputs. **Do not feed it
the paired CSV gravity vector as if that vector were raw device-frame gravity.**
Likewise, do not rotate apparently world-aligned acceleration with an integrated
device attitude and call that raw strapdown mechanization.

## Evidence and scope

**Confirmed from data:** all 241 smartphone CSV files were considered. Complete
row-by-row comparisons reduce them to 97 recording groups: 72 groups with three
copies each, plus 25 unpaired recordings. All 144 copy comparisons passed. Each
comparison requires matching dimensions, semantic fields, timestamps and text
values, and numeric agreement with absolute tolerance 1e-12 and zero relative
tolerance. It does not identify recordings from filenames alone. The evidence
retains original normalized header lists, individual checksums and the maximum
numeric difference for every compared field.

For example, S1 gyro differences between categorized and uncategorized copies
are at most 1.11e-16 rad/s. Its orientation and time fields match. Thus the
source correspondence is supported by measurements, not just header vocabulary:

- Categorized `GYROSCOPE Yaw` corresponds to the alternate `GYROSCOPE X`.
- Categorized `GYROSCOPE Pitch` corresponds to the alternate `GYROSCOPE Y`.
- Categorized `GYROSCOPE Roll` corresponds to the alternate `GYROSCOPE Z`.
- Categorized `ORIENTATION (Yaw)` corresponds to alternate `ORIENTATION (azimuth)`.

**Limitation:** equality of copies proves their source-label correspondence. It
does not independently prove Android physical signs, device mounting, or that
each field is an unprocessed hardware reading. These checks do not upgrade
Phase 0 synchronization labels or validate all channel units.

All 72 categorized smartphone/VBOX pairs were considered. Nine had unequal row
counts and were excluded from same-row fitting. Of the remaining 63, 43 supplied
127 eligible windows; 20 supplied no eligible window under the duration/clock
requirements. Very short recordings cannot provide a 30-second window in each
temporal third. These exclusions are not declarations that those data are useless.
The paired diagnostics use one pair at a time, not the full dataset in memory.

The full results are in [frame_export_evidence.json](frame_export_evidence.json).
All conclusions below refer to this development investigation, not an untouched
test set. The 127 reference-selected windows must not later be described as
unseen test data. Freeze future splits and calibration policies independently.

## What the logger and supplied files establish

**Stated by logger documentation:** AndroSensor's author lists world-coordinate
acceleration as a feature introduced in version 1.9.6.1. The changelog also
distinguishes orientation azimuth/pitch/roll naming from vector XYZ fields.
This establishes an available export option, not the setting used for IO-VNBD.
Source: [AndroSensor author documentation](https://www.fivasim.com/androsensor.html).

**Confirmed by source inspection:** the local dataset repository at commit
`1189396` contains one Python utility, `Data Checker Table 2.py`. It trims
recordings and exports latitude/longitude comparisons; it does not implement
the smartphone logger's sensor export. It was read, not executed. The synchronized
ZIP contains 288 CSVs and 72 images. The unsynchronized ZIP contains 276 CSVs,
89 images and that utility. Neither archive contains logger source, an APK,
settings/configuration files, or an alternative unprocessed sensor export.
Archive directories were inspected without extraction or modification.

The existing paper-diagram interpretation is preserved in the earlier frame
report. A mounting illustration cannot override the measured export behavior.

## Orientation and gravity consistency

**Stated by Android documentation:** `SensorManager.getRotationMatrix` maps device
vectors into a magnetic world frame: approximately east, magnetic north, up.
This is not automatically true-north ENU. See
[Android SensorManager](https://developer.android.com/reference/android/hardware/SensorManager).

**Implemented mathematical hypothesis:** invert Android's orientation extraction
using a proper rotation

```text
C_WD = Rz(-azimuth) @ Rx(-pitch) @ Ry(roll)
u_D = C_WD.T @ [0, 0, 1]
```

Angles enter in degrees and are converted explicitly. The Android extraction
equations are verified against the
[AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/SensorManager.java).
Synthetic round-trip, signed-turn, gravity and determinant tests check this
mathematics. Compatibility with the CSV remains a tested hypothesis.

**Confirmed from data, conditional on that interpretation:** across all 72 paired
recordings, per-record median gravity tilt from exported +Z is only 0.017–0.315°.
The median angle between that exported vector and orientation-implied device up
is 85.665–98.752°. They cannot both be ordinary, same-axis device gravity.

Representative per-record median discrepancies are M 97.209°, S1 97.473° and
S3c 87.066°. Their exported gravity tilts are 0.063°, 0.089° and 0.048°,
respectively. Near-upright physical mounting and an almost +Z export can coexist
if gravity has already been transformed out of the device frame.

As a separate internal compatibility check, transforming the recorded magnetic
vector with C_WD leaves median absolute east components of only 0.390–0.715%
of magnetic-vector magnitude across the paired recordings. This is consistent
with Android's magnetic-world construction. It is not independent ground truth:
the orientation computation may itself consume the same magnetometer.

The strict combined world-like signature occurs in 72 groups. Sixteen unpaired
groups do not meet it, which does not by itself prove device-frame export.
Nine (T1–T9) lack orientation/magnetic fields needed for this check. S-A4 remains
under its pre-existing schema-shift quarantine. No universal conversion is approved
for these other recordings.

## Acceleration hypotheses and validation

**Declared diagnostic method:** select up to three non-overlapping maneuver-rich
windows, one per temporal third, using VBOX maneuver strength and clock quality,
not phone fit quality. Target duration is 120 seconds; minimum is 300 rows.
Phone and VBOX timestamp intervals must be finite, positive and at most 0.25 s.
No offsets, affine warps, missing-time substitution or best-lag corrections are
fitted or applied.

Each window uses its first half to estimate one proper rotation and evaluates
the second half without refitting. No reflected matrices, scale factors or
regression-intercept corrections are fitted. The same raw input contract is
used throughout: `linear_export = accelerometer_export - gravity_export` in m/s².
VBOX reference acceleration is explicitly multiplied by 9.80665.

The three unfiltered hypotheses are:

1. **Fixed export-to-vehicle:** rotate linear_export directly into forward/left/up.
2. **Export is world:** compare linear_export against VBOX acceleration rotated
   to true ENU using VBOX heading, with one fitted horizontal offset. This heading
   is used only to construct the diagnostic reference, not as a phone measurement.
3. **World export inverted to device:** compute C_WD.T @ linear_export, then fit
   a fixed device-to-vehicle proper rotation using similarly inverted gravity.

The evidence retains calibration/validation correlation, slope, intercept, RMSE,
NRMSE and excitation. It also applies the first window's unfiltered matrix to
later windows without refitting, and records changes between estimated matrices.
Acceptance requires both axes to have reference standard deviation >=0.1 m/s²,
correlation >=0.7, slope in [0.7,1.3], NRMSE <=0.8, and fit excitation ratio >=0.1,
on both calibration and validation halves. These are engineering gates.

**Confirmed from data:** each unfiltered hypothesis passes **0 of 127 windows**.
This rejects their unqualified adoption under this protocol; it does not prove
that no future calibration or synchronization treatment can work.

An additional sensitivity test applies a trailing five-row mean to reconstructed
device acceleration and to the reference, discarding four warm-up rows. The
filter has approximately 0.2 s group delay at nominal 10 Hz. It is separate from
the raw hypothesis and introduces no optimized time offset.

**Confirmed from data:** the filtered reconstruction passes **1 of 127 windows**:
S3c rows 5100–6299. Its second-half longitudinal/lateral correlations are
approximately 0.777/0.844, slopes 0.858/0.833, and NRMSE 0.742/0.573.
No recording passes in all its tested windows. This local success warrants
follow-up, not a general mounting matrix or a raw-strapdown approval.

A separate sensitivity calculation subtracts mean reconstructed-device residual
acceleration from prior stationary candidates only. Candidates require both
phone and VBOX speed <0.3 m/s and gyro norm <0.05 rad/s for at least two nominal
seconds, split at bad timestamps. Acceleration residual is not used for selection.
No samples at or after the tested window are used for this estimate. The result
passes zero windows. It is a bias-sensitivity diagnostic, not a verified bias or
deployable initialization: VBOX assists selection, and a mean residual can mix
bias, gravity-estimation error and imperfect stationarity.

## Gyroscope result and why full propagation is still blocked

The source correspondence makes the earlier `+Pitch` yaw finding consistent
with a near-upright phone whose device Y is approximately vertical. This is a
physical interpretation, not proof of every axis and sign.

The new evidence compares adjacent orientation matrices through SO(3) differences,
not differences of Euler labels. It excludes invalid timestamp intervals and
compares those rates with the explicit hypothesis `device XYZ = source Yaw,
Pitch, Roll`. It also compares the orientation-implied vertical projection of
that hypothesized triad with VBOX yaw in the paired windows.

**Confirmed from data:** those checks do not establish a stable complete triad.
For M, the whole-record device X/Y/Z rate comparisons have correlations
0.366/0.109/0.407 and slopes 0.348/0.022/0.189. Magnetic/fused-orientation
changes, sensor timing and measurement noise can contaminate this comparison.
It is deliberately not treated as an independent roll/pitch reference or used
to override the previous accepted local yaw checks.

**Still unresolved:** exact export transform, sensor timestamp association,
logger version/settings, and independently verified mounting. Applying the
logged orientation at every row is also a different input contract from raw
strapdown: it incorporates the logger's orientation estimation and potentially
magnetometer assistance. It cannot silently become the later raw-INS baseline.

## Evidence needed to remove the remaining gate

First request original collection metadata from the dataset maintainers:

- AndroSensor version, phone/OS version and saved settings, especially world-
  coordinate acceleration and sensor update/recording settings for each recording.
- Original, unedited logger exports with sensor names and timestamps, plus the
  export/processing code or equations, matrix direction and units.
- Whether acceleration and gravity were rotated together, whether gyroscope and
  magnetometer were also rotated, and which orientation sample was used.
- Mounting photos/axis definitions per recording and independent signed-axis
  calibration evidence. Clarify any differences between the three directory copies.

No maintainer message was sent. Those materials are not present in the inspected
repository or archives.

If the original export contract cannot be recovered, obtain controlled calibration
data before approving a new phone-based baseline. Proposed protocol:

1. Save original headers, logger settings, phone/OS/sensor identifiers and monotonic
   event timestamps. Keep device-frame and world-coordinate exports explicitly
   separate. Document recording and sensor-event frequencies independently.
2. At rest on a fixture, record at least 10 seconds with each of the six device
   faces up. Confirm which accelerometer/gravity channel approaches +g or -g.
   Repeat each pose. Opposite-pose means support axis bias/scale estimation;
   one unspecified stationary pose does not identify all calibration parameters.
3. Rotate slowly by known +90° and -90° about each documented device axis,
   three repeats per direction, with stationary intervals between turns. Record
   independent direction/angle evidence, such as a marked fixture and video.
   Test integrated angular rate after independently measured stationary gyro bias.
   A proposed initial gate is angle error <=5° and correct sign on every repeat;
   this is a proposed engineering tolerance, not a measured guarantee.
4. Fix the phone in a documented vehicle mount. Verify a proper device-to-vehicle
   rotation with determinant +1 using gravity and an independently known forward
   direction. Gravity alone cannot identify mounting yaw.
5. Capture separate straight acceleration/braking and left/right-turn validation
   runs with suitable synchronized reference measurements. Fit on calibration
   runs only and evaluate stability on untouched runs. Operate the logger only
   while safely parked or have a passenger handle it.

Fresh controlled data can validate the future app's acquisition contract. It does
**not** retroactively certify the old IO-VNBD exporter or repair missing metadata.
Keep the old dataset as diagnostic/experimental data until its own contract is
resolved, or explicitly choose a different baseline dataset in a later task.

## Reproduction and verification

Use the repository environment and pinned requirements. The bundled document
Python lacks SciPy; this audit uses the existing `.venv`, which has the required
SciPy Rotation implementation. No new dependencies were installed.

```powershell
python -B -X utf8 -m training.frame_export_audit --no-write
python -B -X utf8 -m training.frame_export_audit --write-report
python -B -X utf8 -m unittest discover -s tests -q
```

The first command is read-only. The second writes only the new follow-up JSON.
Both compare all 1,186 raw files' SHA-256, sizes and modification timestamps with
the reviewed Phase 0 manifest before and after analysis. The JSON includes source
hashes, source-record identities, all results and explicit missing/skip reasons.

Verification completed: **70 tests passed**, all **24** training/test Python files
parsed, CLI help passed, and import side-effect checks passed. A full independent
read-only repeat was canonically identical to the written JSON; report snapshots
were unchanged during that repeat. Raw data and all pre-existing reports remain
unchanged. The historical Phase 0 and body-frame reports were not regenerated.

Files added: `training/frame_export_audit.py`, `tests/test_frame_export.py`,
`reports/frame_export_evidence.json`, and this report. README now links the new
restriction and reproduction command. Existing INS implementation is unchanged.

**Readiness: source-label ambiguity reduced and export-frame mismatch better
explained; a credible full 3-D raw-INS baseline is still not approved.**
