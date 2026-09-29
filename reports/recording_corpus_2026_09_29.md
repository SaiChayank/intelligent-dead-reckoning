# Real recording corpus check — 2026-09-29

44 recordings were pulled off the phone (OnePlus CPH2585, Android 16, API 36) and
validated with `tools/validate_phone_recording.py --local`, which runs the Python
session reader in `contracts/recording/v1/session.py` — the same rules the
on-device `ReplayReader` enforces. Raw sessions and per-session JSON reports are
kept locally under ignored `mobile/artifacts/`.

## Result

**44 of 44 accepted, 0 rejected.** 242.5 MB, 688,345 records, every session
`source: real`, `calibration: not_applied`. Completion was 42 `completed` and 2
`incomplete` + `recovered`, and both incomplete sessions replayed.

The reader only accepts a stream whose record count, per-channel counts, event-ID
uniqueness, timestamp origin and end time all agree with the metadata, so this is
a statement about the writer meeting the contract on real hardware, not merely
about files parsing.

## Corpus shape

| | min | median | max |
|---|---|---|---|
| duration | 0.12 s | 0.24 s | 1,660.21 s |
| records | 33 | 71 | 494,895 |

One capture holds 494,895 of the 688,345 records (72%). The other 43 are
sub-second start/stop stubs, so the materially useful corpus is currently a
**single ~27.7-minute session** plus one ~10-minute session.

Measured rates are stable and match the acquisition target:

- accelerometer and gyroscope 98.5–98.9 Hz (all 44 sessions)
- gravity 49.2–49.4 Hz, magnetometer 50.0 Hz
- `gnss_gps` 1.0 Hz in 1 session; `gnss_network` 0.1 Hz in 3 sessions

41 of 44 sessions contain no GNSS at all. Receipt delay (received minus measured
timestamp): IMU median-of-maxima 9 ms (worst 57 ms), GPS 69 ms, network 945–1,459 ms.

## Policy checks against real bytes

The 100 ms reorder window (`LATE_NS`) and the 100 ms / 5 s gap thresholds
(`TIME_GAP`) were checked rather than assumed.

- **Lateness holds.** Exactly 2 rows of 688,345 exceeded 100 ms, both on
  `gnss_network`, and exactly 2 `LATE_MEASUREMENT` records exist, in those same 2
  sessions. The producer flags precisely what breaks the window; the window itself
  remains unvalidated as a navigation threshold.
- **Gaps reproduce.** 115 `TIME_GAP` records; 111 are reproduced directly from the
  recorded timestamps and all 111 are on `gnss_network`, consistent with the 5 s
  stale-fix policy for a 0.05–0.1 Hz location provider. **No IMU channel produced
  a >100 ms gap in any session.**
- The 4 remaining `TIME_GAP` records are explained: 3 are the first fix on the
  location channel, gapped against a sample from before the recording started (the
  acquisition stream outlives the recorder, so that gap is real but not visible
  inside the file), and 1 is the already-documented session where the recorder
  discarded the triggering measurement.

## Two findings that constrain the navigation work

1. **GNSS course is never available.** `bearing_deg` was null in all 1,775 fixes;
   `speed_m_s` was null in 115 of them. Initial heading therefore cannot come from
   GNSS course in this corpus, which forces the two-mode initialisation design to
   derive heading from magnetic/gyro evidence and to say so explicitly.
2. **This is not yet a GNSS-loss corpus.** With 41 sessions carrying no GNSS and
   one long session carrying 1 Hz GPS, the data cannot yet support the
   GNSS-loss/recovery outcome of the problem statement. Genuine loss segments —
   GPS on, then denied, then recovered, on the move — still have to be captured.
3. **None of the recorded fixes are inside the bundled map.** Every one of the
   1,775 fixes in the corpus is at 17.5143° N, 78.2973–78.2976° E, while the
   bundled vector pack covers 17.30–17.55° N, 78.35–78.60° E. The recording area
   is about 5.6 km west of the pack's western edge, so the offline map has no
   tiles there and the map screen correctly refuses to draw them. This is a
   data-versus-asset mismatch, not a rendering defect: either recordings have to
   be made inside the covered box, or a pack covering 78.29° E has to be built.

## Limits

One device, one operator, largely stationary and indoors; nothing here resolves the
unsettled IO-VNBD frame/mounting semantics, and no navigation or fused record
exists because `NavigationEngine` is still an interface only. These are observations
about recorded data, not acceptance of any navigation behaviour.
