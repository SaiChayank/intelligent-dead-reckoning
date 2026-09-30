# GNSS quality and outage state machine

`app/src/main/java/.../acquisition/GnssQuality.kt`

One question, answered deterministically from recorded observables: **may the newest fix
on the newest channel be used as a GNSS reference right now, and if not, which named
check failed?** The state set is the frozen contract's `GnssState`; no state, field or
contract was added. The machine reads no clock, holds no reference to the navigation
engine, and smooths or fills nothing, so the same inputs always produce the same
decision and every decision is reproducible from recorded bytes.

The shape is fixed by [docs/PS26168_Non_ML_Baseline_Navigation_System.md](../docs/PS26168_Non_ML_Baseline_Navigation_System.md)
section 3. That document states the exact thresholds require validation, so none of them
were chosen by taste: each cites a measurement over the real recordings, taken read-only
by `tools/gnss_quality_thresholds.py` and published in
[reports/gnss_quality_thresholds_2026_09_30.md](../reports/gnss_quality_thresholds_2026_09_30.md).

## States

| State | Means | Produced by |
|---|---|---|
| `UNAVAILABLE` | Location is switched off, or the channel's provider label has no profile | `PROVIDER_DISABLED`, `PROVIDER_UNKNOWN` |
| `ACQUIRING` | Access and provider are fine but no fix has arrived on any channel | `NO_FIX` |
| `GOOD` | A fix arrived and passed every named check | no findings |
| `DEGRADED` | A fix arrived but failed a named check; it is still evidence, just not trusted | the remaining findings |
| `STALE` | The newest fix is older than this provider's stale bound | `STALE_FIX` |
| `DENIED` | Location permission was not requested, was refused, or was withdrawn | the three permission codes |

`DEGRADED` is deliberately **not** an outage. `UNAVAILABLE`, `ACQUIRING`, `STALE` and
`DENIED` are, because in those states no fix may be used at all.

Permission problems report `DENIED` and an unavailable provider reports `UNAVAILABLE`.
The design document's illustrative sketch assigns those two the other way round; the
implemented mapping is kept because it is already device-verified and pinned by tests,
and the sketch is labelled illustrative.

## Precedence: the whole specification

Phase one, **preconditions**, short-circuits in this order. A failing precondition returns
immediately with no age and no satellite count, because no fix was evaluated as evidence.

1. permission not requested / denied / revoked → `DENIED`
2. provider disabled → `UNAVAILABLE`
3. no fix → `ACQUIRING`
4. a fix exists but its provider has no profile → `UNAVAILABLE`
5. the caller's timestamp validation failed, receipt precedes measurement, or the
   measurement is ahead of `now` → `DEGRADED`

A provider label is judged only once a fix exists on that channel, so "granted but nothing
has arrived yet" stays `ACQUIRING` rather than becoming an unknown provider.

Phase two, **evidence**, runs every check on an accepted fix. Every finding is reported and
the first one in order decides the state:

1. the fix predates this session → `PRE_SESSION_FIX` (never re-sampled as new evidence)
2. age exceeds the provider's stale bound → `STALE_FIX` (**the only finding that yields `STALE`**)
3. approximate access → `APPROXIMATE_LOCATION`
4. horizontal accuracy absent → `HORIZONTAL_ACCURACY_UNKNOWN`, or above the bound →
   `HORIZONTAL_ACCURACY_POOR`
5. satellites expected but absent → `SATELLITES_UNKNOWN`, or below the minimum →
   `SATELLITES_LOW`
6. an externally supplied corroboration rejected the fix → `INNOVATION_REJECTED`

Age is computed from the measurement timestamp against the supplied `now`, never from
receipt time. A cached or stale fix is named before its metadata is judged, so an old fix
is never re-reported as merely imprecise.

## Reason codes

Stable strings, uppercase snake case, unique, and covered by a test that fails on
duplication or on a careless edit.

**Decisive** — these select the state: `LOCATION_NOT_REQUESTED`, `PERMISSION_DENIED`,
`PERMISSION_REVOKED`, `PROVIDER_DISABLED`, `PROVIDER_UNKNOWN`, `TIMESTAMP_INVALID`,
`NO_FIX`, `PRE_SESSION_FIX`, `STALE_FIX`, `APPROXIMATE_LOCATION`,
`HORIZONTAL_ACCURACY_UNKNOWN`, `HORIZONTAL_ACCURACY_POOR`, `SATELLITES_UNKNOWN`,
`SATELLITES_LOW`, `INNOVATION_REJECTED`.

**Supplementary** — reported so missing evidence is never silently pretended complete, but
they never change the state: `SPEED_UNAVAILABLE`, `BEARING_UNAVAILABLE`,
`VERTICAL_ACCURACY_UNKNOWN`, `VERTICAL_ACCURACY_POOR`.

Speed and bearing are supplementary on purpose. A fix without them still gives a position;
it just cannot support course or velocity aiding. This matters on real data: `bearing_deg`
was null on **all 1,782** recorded fixes, and `speed_m_s` on 122 of them, so treating
either as decisive would have degraded essentially every fix in the corpus.

Vertical accuracy is supplementary because no altitude reference exists in the corpus, so
gating on it would be a policy this stage cannot validate. It is reported so a consumer
that needs altitude can gate on it itself.

The retired `QUALITY_NOT_VALIDATED` catch-all is replaced by the named checks above.
`LOCATION_UNAVAILABLE` was removed with it: the exhaustive `when` over `LocationAccess`
made it unreachable.

## Diagnostics

`GnssQualityManager` publishes at most one diagnostic per real change of state, and never
for a repeated state:

| Code | Severity | When |
|---|---|---|
| `GNSS_OUTAGE` | warning | entering `UNAVAILABLE`, `ACQUIRING`, `STALE` or `DENIED` |
| `GNSS_RECOVERED` | info | leaving one of those |
| `GNSS_QUALITY_CHANGED` | info | moving between two usable states |

Acquisition evaluates quality in `snapshot`, which is the only place it is computed, so it
is the only place that can publish a transition. A UI poll that reads an unchanged state
publishes nothing.

## Thresholds, and where they came from

| Threshold | Value | Source and measured validation |
|---|---|---|
| `gps` nominal interval | 1.0 s | 1,659 recorded intervals, 0.998666–1.001026 s, p50 1.000007 s |
| `network` nominal interval | 20.1 s | 118 recorded intervals, p50 20.103928 s; the subscription asks for 1 Hz and the provider delivers 0.05 Hz |
| stale bound | 2 nominal intervals | Tolerates exactly one lost fix. Measured cost on the corpus: **0** of 1,659 GPS intervals exceed 2.0 s and **0** of 118 network intervals exceed 40.2 s |
| maximum horizontal accuracy | 15 m | Above the corpus's GPS p99 (13.836 m) and 16.065 m maximum, so healthy recorded fixes stay usable. A 5 m bound would reject 96.8% of them |
| maximum vertical accuracy | 15 m | Same figure, supplementary only; GPS vertical p99 9.814 m, max 13.069 m |
| minimum satellites | 4 | Arithmetic minimum for four unknowns, a stated requirement; the corpus never recorded fewer than five, so it is consistent with every recorded fix |
| satellites expected | `gps` yes, `network` no | The measured satellite count was null on **all 122** network fixes |

The single most consequential measurement is the stale bound. One shared 5 s bound — the
value acquisition applies to its *diagnostic* gap label — calls the healthy 0.05 Hz network
channel stale in **117 of 118** intervals. The per-provider bound does not, and it still
catches real silence on the 1 Hz GPS channel after two missed fixes.

`policyThresholdsAreTheDocumentedValidatedValues` asserts each number, so a silent
threshold change fails the suite rather than passing unnoticed.

## How a later EKF may augment this, one way only

`GnssInnovationTrust` is an optional **input**. The manager never queries a navigation
engine, holds no reference to one, and emits nothing that an engine reads to compute the
corroboration, so no cycle can form between a quality decision and the innovation test
that consumes it. A caller that owns both pushes it in.

It can only ever **lower** trust. `accepted = false` downgrades a `GOOD` fix to `DEGRADED`
with `INNOVATION_REJECTED`; `accepted = true` is corroboration and cannot promote a fix the
recorded evidence already rejects. Tests pin both directions, including a corroborated
fix with 100 m accuracy staying `DEGRADED` and a corroborated stale fix staying `STALE`.

## What this does not claim

- **`GOOD` is not an accuracy claim.** It means every named check passed, not that the fix
  is correct to any distance. No threshold here is a real-world error bound.
- **The provider's accuracy field is weak evidence.** 1,202 of the 1,660 GPS values were
  the identical number 9.935046 m, and 119 of the 122 network values the identical 100.0 m;
  the provider repeats a cached estimate rather than measuring. The document's own warning
  stands, so fusion must still corroborate this input.
- **The corpus cannot exercise loss.** 4 of 45 recordings carried GNSS at all, 1,660 of
  1,782 fixes sat on one channel, and no GPS interval ever exceeded 1.001026 s. There is
  no recorded GNSS outage to validate a recovery transition against, so those tests are
  synthetic by necessity.
- **One device, one mount, one operator.** The thresholds are measurements, not constants
  of nature, and a different device may repeat the accuracy field differently.

## Reproduce

```bash
python tools/gnss_quality_thresholds.py --root mobile/artifacts
python -B -X utf8 -m unittest discover -s tests -p "test_gnss_quality_thresholds.py" -v
```

The tool is read-only: a test asserts the recorded bytes are identical before and after it
runs.
