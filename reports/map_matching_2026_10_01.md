# Offline map matching (MVP) — validation report

Date: 2026-10-01. Scope: the offline OSM road-graph package and the MVP causal
map matcher (`mobile/.../matching/`), per the stage-12 brief. Raw fused
position and map-matched position are separate outputs; navigation truth is
never overwritten. The MapLibre renderer is untouched. Design and API:
[mobile/MAP_MATCHING.md](../mobile/MAP_MATCHING.md).

## Result

**Map matching MVP validated.** Every behaviour the brief names is exercised by
a deterministic test, on synthetic metre-space fixtures and on the real
packaged Hyderabad graph (217,316 nodes / 285,175 edges):

| Required behaviour | Evidence (test) | Outcome |
|---|---|---|
| Fix on a road matches it | `aFixOnARoadMatchesItAndKeepsRawSeparate` | MATCHED; raw and matched fields both populated, raw unchanged |
| Parallel roads | `parallelRoadsAreChosenByProximityAndHeading` | heading breaks the tie toward the driven road |
| Parallel roads, ambiguous | `anAmbiguousFixBetweenParallelRoadsIsRefusedNotGuessed` | LOW_CONFIDENCE, matched fields null — refusal, not a coin flip |
| Parallel roads, with history | `previousEdgeContinuityResolvesParallelAmbiguity` | continuity (×1.5 shared-edge/node bonus) picks the driven road |
| Intersections | `headingSelectsTheDrivenRoadAtACrossing`, `withoutHeadingACrossingIsAdmittedOnlyWithHistory` | heading selects the driven leg; without heading only prior-edge continuity decides |
| Service roads | `serviceRoadsMatchLikeAnyOtherRoadWhenClosest` | `service` is a first-class matchable class |
| No candidate | `aFixWithNoRoadWithinReachIsReportedNotSnapped` | NO_CANDIDATE; raw position reported, nothing invented |
| Low confidence / huge uncertainty | `hugeReportedUncertaintyRefusesToDecide` | accuracy95 > 50 m refuses before scoring |
| Outside coverage | `aFixOutsideCoverageIsReportedNotProjected`, `theRealGraphReportsPointsOutsideItsRegion` | OUTSIDE_COVERAGE; no projection attempted |
| Causality | `matchingIsCausalAndIndependentOfFutureFixes` | a drive prefix matches identically inside the full run — safe to run live |
| Low-speed heading stand-down | `atLowSpeedTheHeadingTermStandsDown` | heading ignored below 2 m/s |
| Real-graph match | `theRealGraphMatchesAnIsolatedEdgeExactly` | exact projection onto a real packaged edge |
| Package integrity | `thePackagedGraphMatchesItsManifest`, `aTamperedGraphIsRefusedByChecksum` | size + SHA-256 verified; tampering refused |

Coverage, projection and topology invariants are held by `RoadGraphTest` (6
tests) and the Python asset suite (`tests/test_road_graph_asset.py`, 6 tests).

## The road-graph package

- Separate from the render pack: `assets/roadgraph/hyderabad-v1/`, derived from
  an OSM Overpass extract of the same bounded region (bounds identical to the
  map pack's `[78.35, 17.3, 78.6, 17.55]`). MBTiles remain rendering-only.
- 285,175 edges over 14 matchable classes (incl. `service` and `_link`
  variants), ways split at shared nodes; 7,675,094 bytes gzipped.
- **Version/checksum/licensing metadata**: `manifest.json` records id
  `hyderabad-v1`, version 1, source query + mirror + `timestamp_osm_base`
  (2026-10-01T02:09:04Z), license ODbL 1.0 with the required attribution,
  builder identity, and per-file SHA-256
  (`road-graph.json.gz` = `b0584248…ed066f`); `NOTICE.md` states matching-only
  purpose. Verified by `RoadGraph.verifyManifest` at load and by both test
  suites.
- Build is deterministic: two builds byte-identical
  (`tests/test_build_road_graph.py`, 10 tests).

## Optional follow-up evaluated: online HMM / Viterbi

The brief permits evaluating an HMM/Viterbi upgrade after the MVP passes.
**Evaluation result: defer implementation; the MVP is the right stopping
point now.** Reasoning:

- The MVP's continuity term is already a one-step greedy approximation of the
  Viterbi recurrence; the tests show it resolves the hard cases (parallel
  roads, crossings) through continuity + heading, which is where a trellis
  would also gain.
- An *offline* (forward–backward) HMM would violate the causality requirement
  (`matchingIsCausal…`); only **online Viterbi** with a bounded beam and a
  fixed decision delay would be admissible, and that adds state, latency and a
  new set of thresholds with nothing measured yet to justify them.
- There is no ground-truthed real drive to score either implementation against;
  without a scoring corpus the upgrade cannot demonstrate improvement, only
  complexity.

Revisit criteria (any one triggers the evaluation again): a ground-truthed
in-coverage drive corpus exists; measured MVP mis-assignments concentrate at
multi-road junctions where a trellis demonstrably helps; or live use needs
retro-consistent edge sequences (e.g. for road-level reporting).

## Limitations

- **Synthetic + real-graph tests, not field accuracy.** Mechanism is proven;
  no measured matching-error number exists without ground truth.
- **Not wired into the runtime or UI.** The matcher is host-validated and
  standalone; the app still shows only raw fused positions.
- **Undirected graph**: no wrong-way detection; one-way semantics need a
  directed graph format version.
- **Thresholds are chosen, not measured** (`MapMatchConfig`): radii 15–60 m,
  confidence floor 0.55, accuracy ceiling 50 m, heading σ 20°, continuity
  bonus 1.5, travel slack 50 m + speed·dt.
- OSM snapshot frozen at the extract's `timestamp_osm_base`.

## Tests and gates

20 new Kotlin tests (`RoadGraphTest` 6, `MapMatcherTest` 14) + 16 new Python
tests (`test_build_road_graph.py`, `test_road_graph_asset.py`). Full gates
green on 2026-10-01: **302 Kotlin tests, 0 failures; 254 Python tests OK
(1 skip); `lintDebug` 0 errors** (5 pre-existing warnings, none in matching
code); repo hygiene clean; whitespace clean; AST parse over 61 Python files.
