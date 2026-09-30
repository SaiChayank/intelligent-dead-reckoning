# PS26168 — Experiment Data Collection Protocol

How to collect a canonical real driving session as an **experiment**: a recording
the app produced, plus the mounting, clock, annotation, mask and integrity context
that makes the drive reusable months later by someone who was not in the car.

This is a field protocol, not an analysis. Nothing here produces a navigation
result, and no experiment makes an accuracy claim. What it produces is evidence
that is structured enough to be evaluated later, and honest enough that the
evaluation cannot quietly become fiction.

The normative schema is [contracts/experiment/v1/README.md](../contracts/experiment/v1/README.md).
Read that first if you need the exact field semantics; this document is the
procedure that produces conforming artifacts.

## 1. The one rule that cannot be broken

**Never alter a raw recording.** A recording is evidence, and evidence that has
been rewritten is no longer evidence. Concretely:

- no editing, truncating, sorting, resampling, re-timestamping or "cleaning" a
  session directory;
- no repairing a corrupt session by hand — recovery is the recorder's own
  explicit, marked operation, and a session it cannot recover stays unrecoverable;
- no masking a recording to "make" a degraded-GNSS condition. Masking happens at
  read time, in the consumer, and never touches the bytes;
- copying a session into an experiment is fine; the copy is what gets hashed, and
  the original is untouched.

If a session is unusable, say so and exclude it. Do not fix it.

## 2. Equipment

| Item | Requirement |
|---|---|
| Phone | The device you intend to make claims about. Record manufacturer/model, OS version and API level (the recorder captures these). |
| Mount | Mechanical, rigid, and the same one for every drive you intend to compare. A phone in a cupholder is not a known mounting. |
| Power | The phone must not thermally throttle mid-drive; GNSS and recording are power-hungry. |
| Reference (optional) | An independent RTK or VBOX-class receiver, with its own antenna placement and its own log. |
| Route notes | Whatever you use to remember what happened where. Write annotations the same day. |

## 3. Mounting and orientation

The mount is the single largest source of silently wrong attitude results, so it
gets recorded before the drive, not remembered after.

**Choose a position** and record it (`mount.position`). There is no `unknown`
option in the contract: if you cannot say where the phone was, you cannot build a
usable experiment.

**Establish the orientation** and record how, in `mount.orientation_method`:

| Method | Meaning | When to use |
|---|---|---|
| `measured` | The device reported its own orientation (device attitude recorded while stationary on the mount) and you read the angles from it | Default. Cheapest honest option. |
| `surveyed` | Angles established against the vehicle's own axes with a physical reference (level, protractor, vehicle body lines) | When you need the best available attitude prior. |
| `assumed` | A convention was applied, e.g. "phone flat, screen up, top toward the windscreen" | Only when nothing better exists. **Requires a note** naming the convention. |

Record `roll_deg`, `pitch_deg`, `yaw_deg` in degrees, in the **vehicle** frame,
using `(-180, 180]`, and `uncertainty_deg` for how well you actually know it. If
you are unsure between 1° and 10°, write 10 — an understated uncertainty is worse
than an honest one, because it is the number a later reader trusts.

Procedure:

1. Fit the mount. Drive 2–3 minutes on a straight, open road to settle the phone.
2. Stop the car, engine off, on level ground. Do not touch the phone.
3. Record the stationary session or note the reported device attitude.
4. Read the angles. Write them down with the method and a realistic uncertainty.
5. Keep the mount untouched for the whole campaign. A changed mount invalidates
   comparison with earlier drives.

## 4. Exact timestamps and the clock

Every measurement carries `t_ns` and `received_ns` as Android **elapsed-realtime
nanoseconds since boot**, as signed Int64 decimal strings. That is the exact
monotonic clock; nothing else may be substituted for it.

- UTC fields (`created_utc_ms`, `started_utc_ms`) are provenance only. Never use
  them to order or align measurements.
- Do **not** reboot the phone between sessions you intend to compare. Elapsed
  realtime is boot-relative, so two sessions are comparable only on the same boot
  with the same boot identity.
- The experiment records one `clock.boot_id`, and every session must match it. If
  a session is on another boot, it does not belong in the same experiment: start a
  new experiment instead of forcing the comparison.
- If the phone's boot identity is unavailable, that session cannot be used in a
  multi-session experiment. Single-session experiments are still fine.

## 5. The drive

There is no single correct route; there is a correct way to describe it.

- Record at least one segment of **open-sky, good GNSS** driving. Without a good
  reference stretch there is nothing to measure drift against.
- Deliberately include **GNSS-degrading environments** if the route offers them:
  urban canyons, tree cover, underpasses, tunnels, parking structures.
- Include **stationary** time (calibration, at a stop) and **manoeuvres** you will
  later annotate: turns, lane changes, roundabouts, braking, reversing.
- Keep the app in the foreground. Recording contract 1.0.0 is
  `foreground_only = true`; there is no background recording and no foreground
  service.
- Stop the session explicitly. A clean stop finalizes the session properly; an
  interrupted one is recovered with an explicit mark, and both are valid, but a
  clean stop is what you want by default.

## 6. Annotations

Annotate in the same session the drive happened, while you still remember it.
Annotations go in `annotations.jsonl` as intervals, with `start_ns`/`end_ns` in
the same elapsed-realtime nanoseconds, bounded by the session's own span.

### Motion

`motion` intervals describe the vehicle: `stationary`, `straight`,
`turning_left`, `turning_right`, `roundabout`, `lane_change`, `accelerating`,
`braking`, `reversing`. Intervals of one kind must not overlap, so if the car
brakes while turning, annotate the turn as `turning_left` and mention the braking
in the `note` rather than creating an overlap.

### Scenario

`scenario` intervals describe the environment: `open_sky`, `suburban`,
`urban_canyon`, `tree_cover`, `tunnel`, `underpass`, `parking_garage`, `bridge`,
`highway`, `rural`. Scenario and motion run in parallel and are annotated
independently.

### GNSS availability

`gnss_state` intervals describe what the receiver actually did. The four states
are observations, and the interval boundaries should be derivable from the
recording itself rather than from memory:

| State | Observable meaning |
|---|---|
| `good` | Continuous fixes, healthy reported accuracy and satellite count |
| `degraded` | Fixes still arriving, but poorer accuracy, fewer satellites, or intermittent gaps |
| `lost` | No fix at all: a gap in GNSS measurements, not merely a bad one |
| `recovered` | Fixes have returned but have not yet settled to `good` |

The reported accuracy is the platform's own estimate, not ground truth, and the
specific thresholds that separate `good` from `degraded` are a **convention**.
Apply one convention consistently and record it in the annotation `note` the first
time you use it, so a later reader can tell what you meant. A state timeline that
is internally consistent and honestly labelled is useful; one that is
retrospectively tuned to make a result look better is not.

State transitions are expressed by touching intervals with different values, e.g.

```json
{"kind":"gnss_state","session_id":"rec-primary","start_ns":"...","end_ns":"...",
 "note":"convention: good >= 5 sats and <= 10 m reported accuracy",
 "calibration_id":null,"motion":null,"scenario":null,"gnss_state":"good"}
```

Leave gaps where you genuinely do not know. Coverage is reported, including the
**unannotated** remainder, so a gap is visible rather than silently absorbed.

### Calibration intervals

Record a `calibration` interval (with a `calibration_id`) for any period where a
calibration procedure was performed — typically stationary, before or during the
drive. Every session currently records `calibration: not_applied` in its metadata;
that is separate from, and must not be confused with, an experiment-level interval.
The interval says *when* a procedure ran; the session metadata says *whether* a
project calibration transformed the measurements, and today it did not.

## 7. Optional independent reference

An RTK or VBOX-class receiver gives the one thing the phone cannot: an independent
position stream to measure against. It is **optional**, and its absence is
recorded as absence so nothing implies accuracy was verified.

If you fly one:

1. Record the class (`rtk`, `vbox`, `survey_grade`, `other`), the equipment, its
   declared `horizontal_accuracy_m` and its `rate_hz`. These are the
   equipment's own claims; record them as claims.
2. State the `timebase`:
   - `experiment_clock` if you have already aligned it — which then **requires**
     `clock.reference_alignment` with a method, an offset and an uncertainty;
   - `gps_utc` if it keeps its own timebase. Cross-domain comparison is then not
     permitted unless an alignment is also declared.
3. Write the reference rows to `reference/reference.jsonl` as ordinary
   `contracts/v1` `Record` envelopes carrying `gnss` measurements with
   `source = real`. There is no reference-specific schema. Any other payload type
   is refused.
4. Mount the reference antenna as far from the phone as practical and record where
   it was. An antenna next to the phone is not independent.

If you cannot align the reference's clock confidently, leave the alignment out and
let the file say so. An unaligned reference is still useful; a wrongly aligned one
is a fabricated result waiting to happen.

## 8. After the drive

1. **Copy** each session directory into the experiment at
   `sessions/<session_id>/`. Keep `metadata.json` and `measurements.jsonl` exactly
   as exported. Do not rename, reformat or re-encode.
2. **Author** `experiment.json`: identity, description, mount, clock, sessions with
   their roles (exactly one `primary`), the reference if any, and the mask IDs.
   Put the all-zero digest placeholder in each session's `measurements_sha256`;
   you do not need the real value and must not guess it.
3. **Annotate** `annotations.jsonl`.
4. **Author masks** under `masks/`. A mask is the designed condition you will
   evaluate repeatedly. Two are usually worth having for a drive that passes
   through an underpass: a `synthetic` mask over a stretch where GNSS was actually
   good, so dead reckoning has a known reference to be scored against, and an
   `observed` mask over the real outage, with a note saying where it came from.
5. **Seal**:

   ```powershell
   python tools/seal_experiment.py experiments/<experiment_id>
   ```

   This computes the real digests, rewrites `experiment.json`, writes
   `integrity.sha256` over the whole directory, and then re-opens the result
   through the reader so a sealing mistake is caught immediately.

6. **Validate and report**:

   ```powershell
   python tools/validate_experiment.py --corpus experiments
   python tools/experiment_report.py --experiment experiments/<experiment_id> --mask <mask_id>
   ```

   The corpus form also catches the one error no single directory can see: two
   experiments claiming the same ID.

7. **Keep the report** with the experiment. It is the provenance of the drive, and
   it states what was annotated and what was not.

## 9. Checklist

- [ ] Mount position chosen, mechanical, unchanged since the last comparable drive
- [ ] Orientation established and the **method** recorded; `assumed` carries a note
- [ ] Realistic `uncertainty_deg`, not an optimistic one
- [ ] No reboot between sessions that share an experiment
- [ ] All sessions on the same boot identity as the experiment clock
- [ ] At least one open-sky segment; degrading environments included if available
- [ ] Session stopped cleanly
- [ ] `motion`, `scenario`, `gnss_state` intervals annotated, with the GNSS
      convention recorded in a note
- [ ] Calibration intervals recorded where a procedure ran
- [ ] Reference declared, or explicitly absent
- [ ] Sessions copied byte-for-byte; originals untouched
- [ ] `seal_experiment.py` run; `validate_experiment.py --corpus` clean
- [ ] `experiment_report.py` output kept, including `unannotated` coverage

## 10. What an experiment still does not establish

An accepted experiment means the artifacts are consistent, complete and unaltered.
It does **not** mean:

- the drive is suitable for training — that is a separate, later judgement;
- the GNSS annotations are ground truth — they are labelled observations;
- the phone's reported accuracy is real accuracy;
- navigation performance was measured. Nothing in this stage runs an engine, and
  no number produced here is a dead-reckoning result.

Those claims require the evaluation stage that consumes these experiments, and
start from the boundary recorded here.
