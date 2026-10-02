# Offline map matching: an OSM road graph and a causal matcher

This document describes the offline road-graph package and the MVP causal map
matcher in `mobile/.../matching/`. The matcher takes the **raw fused position**
and emits a **map-matched position as a separate output**. It never overwrites
navigation truth: the fused `NavigationState` stays exactly as the filter
produced it, and `MapMatchResult` carries both positions side by side so a
consumer can always show or compare the raw trail.

What this is not:

- **Not the renderer.** The MapLibre stack (`map/`, `ui/OfflineMapScreen.kt`,
  `assets/offline/`) is untouched. The bundled MBTiles are a rendering pack and
  are not a routable or matchable graph; nothing here reads them.
- **Not a router.** The graph has no turn restrictions, no one-way direction
  logic and no cost model. It exists to answer "which road segment is this fix
  on, and how confident am I".
- **Not a NavigationEngine.** The matcher is deliberately outside the engine
  seam: it consumes published positions and produces a parallel output. It never
  runs unless the map's evaluation toggle is on, and even then it only reads the
  published position and the published 95% radius — see the next section.

## The road-graph package

`mobile/app/src/main/assets/roadgraph/hyderabad-v1/`:

| File | Content |
|---|---|
| `road-graph.json.gz` | The graph: 217,316 nodes, 285,175 edges, 772,623 geometry points, 7,675,094 bytes |
| `manifest.json` | id `hyderabad-v1`, version 1, bounds, counts, source/licensing provenance, builder identity, per-file size + SHA-256 |
| `NOTICE.md` | States matching-only purpose, undirected semantics, ODbL attribution |

The graph is derived from OpenStreetMap extract
`data/raw/osm/hyderabad_highways_20260930.osm.json` (Overpass
`way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|
residential|living_street|service)(_link)?$"](17.3,78.35,17.55,78.6);out geom;`,
`timestamp_osm_base` 2026-10-01T02:09:04Z). Licensing: **ODbL 1.0**,
attribution "© OpenStreetMap contributors (ODbL)". The query bounds match the
map render pack's `manifest.json` bounds `[78.35, 17.3, 78.6, 17.55]` exactly;
the packaged graph's own data bounds are asserted equal to the render pack's
bounds by `tests/test_road_graph_asset.py`.

### Format: `idr-road-graph/1`

Columnar JSON inside a deterministic gzip stream (mtime pinned to 0):

- `classes`, `names` — string tables; edges reference them by index.
- `nodes` — flat `lat_e6` / `lon_e6` pairs.
- `edge_u`, `edge_v`, `edge_class`, `edge_name`, `edge_way`, `edge_part` —
  per-edge columns. Ways are split into edges at every shared node
  (`node_use >= 2`), so each edge runs between junctions; `(edge_way,
  edge_part)` is unique per edge.
- `edge_geom_offset` — `E + 1` offsets into `geom`.
- `geom` — flattened geometry in delta microdegrees (first point of each edge
  absolute).

14 matchable road classes are kept (the motorway…residential ladder plus
`living_street`, `service` and all `_link` variants). Footways, paths and steps
are excluded; edges shorter than 5 cm are dropped. The build is deterministic:
two runs from the same extract produce byte-identical files (asserted by
`tests/test_build_road_graph.py`).

### Integrity and verification

`RoadGraph.verifyManifest(graphFile, manifestFile)` re-checks the file size and
SHA-256 of `road-graph.json.gz` against the manifest and refuses to load a
tampered graph (`IllegalStateException`). The packaged asset
(sha256 `b05842482abf96805342925dfd91a48660d26ca365d1f92d898b4a5201ed066f`)
is verified by the JVM suite (`RoadGraphTest.thePackagedGraphMatchesItsManifest`)
and by the Python suite (`tests/test_road_graph_asset.py`), the same
one-place-for-the-check discipline the map pack uses.

Rebuild with:

```
python tools/build_road_graph.py --input data/raw/osm/hyderabad_highways_20260930.osm.json \
    --out-dir mobile/app/src/main/assets/roadgraph/hyderabad-v1 --id hyderabad-v1 \
    --version 1 --bounds 78.35,17.3,78.6,17.55 --mirror https://maps.mail.ru/osm/tools/overpass/api/interpreter
```

## Candidate search

`RoadGraph` keeps a uniform grid index (0.001° cells with a 5 m slack for cell
boundary effects). `candidatesNear(lat, lon, radiusM)` returns only edges whose
footprint can reach the fix; `project(edge, lat, lon)` returns the closest
point on the edge in a local tangent plane (via `Geodesy.radii`), its distance
and the road heading at that point.

`MapMatcher.match(...)` clamps the search radius between 15 m and 60 m,
scaled by the reported uncertainty (`radius = clamp(3σ, min, max)` with
`σ = accuracy95M / √5.991`).

## Scoring

Each candidate edge gets a score, and the winner's share of the total becomes
the confidence:

- **Distance** — `exp(-0.5 · (d / σ_d)²)`, `σ_d = max(5 m, σ)`.
- **Heading compatibility** — only above 2 m/s (a stationary phone's heading is
  meaningless). The residual is undirected — roads have no direction here — so
  it folds into `[0, π/2]`; σ is 20°.
- **Previous-edge continuity** — candidates continuing the last matched edge
  (same edge, or sharing a node with it) get ×1.5, the same OSM way ×1.05, and
  a travel-plausibility penalty applies beyond `50 m + speed·dt` of straight-line
  separation from the last fix (soft exponential decay, 30 s horizon).

Confidence = `bestScore / totalScore`. Below 0.55 the matcher refuses to
decide. All thresholds live in `MapMatchConfig` and are **chosen engineering
values, not measured ones** — stated as such, like every other gate in this
project.

## Uncertainty gating

In order, before any scoring:

1. Outside the graph's data bounds (plus a 0.005° margin) →
   `OUTSIDE_COVERAGE`. No projection is attempted.
2. `accuracy95M > 50 m` → `LOW_CONFIDENCE`. No candidate is trusted.
3. Zero candidates within reach → `NO_CANDIDATE`. The raw position is reported,
   never snapped.
4. Best confidence below 0.55 → `LOW_CONFIDENCE` with matched fields null.

## Statuses and outputs

`MapMatchStatus`: `MATCHED`, `LOW_CONFIDENCE`, `NO_CANDIDATE`,
`OUTSIDE_COVERAGE`. `MapMatchResult` carries, per input fix:

`timestampNs`, `rawLatitudeDeg`, `rawLongitudeDeg`, `matchedLatitudeDeg?`,
`matchedLongitudeDeg?`, `matchedEdgeId?`, `confidence`, `status`,
`distanceToEdgeM?`, `candidateCount`, `matcherVersion`
(`idr-map-match/1`).

The raw fields are always populated; matched fields are nullable and null
whenever the matcher refused to decide. `reset()` clears the continuity state
between drives.

## Causality

The matcher only sees the current fix and past state — no lookahead, no
smoothing, no retro-deletion. `MapMatcherTest.matchingIsCausalAndIndependentOfFutureFixes`
asserts a prefix of a drive matches identically inside a full run, so the MVP is
safe to run live. (This is also why the HMM/Viterbi upgrade would have to stay
*online* Viterbi — see the [validation report](../reports/map_matching_2026_10_01.md).)

## In the engine map view: the evaluation overlay

Since 2026-10-01 the matcher is reachable from the map, and only as an evaluation
overlay. The Map screen's fourth source (`MapMode.ENGINE`) draws what the navigation
engine published; `map/EngineSessionMap.kt` folds that output and, **when the user
enables the `map_evaluation` toggle**, runs the matcher over the published positions
and records the matched claim beside them. The rules that keep it honest:

- matching is off by default, and the panel says so: with it off, the drawn position
  and trail are exactly what the engine published;
- the matcher reads the published position and the uncertainty from the paired
  `Confidence` record — the calibrated radius if there is one, otherwise the covariance
  the engine published as `UNVALIDATED` — and a position with neither is left unmatched
  rather than matched with an invented uncertainty. The platform's own fix radius is
  never used as that gate: it is a provider figure, not fused confidence
  ([CONFIDENCE.md](CONFIDENCE.md));
- the raw `point` and `trail` are never written: matching adds two GeoJSON kinds
  (`matched`, `matched-trail`) beside them and the renderer draws the matched claim in
  the comparison colour, dashed, with the note that the raw position is still the
  navigation truth;
- enabling the toggle installs and SHA-256-verifies the packaged graph on the device
  (`matching/RoadGraphPack.kt`, private storage, I/O dispatcher) — the 7.7 MB gzip asset
  is not resident until then;
- the map's data contract is unchanged: the two kinds are additive, and
  `MapRenderer.present(state, overlays)` keeps its signature.

## What this does not claim

- **No field accuracy.** Scenario behaviour (parallel roads, crossings, service
  roads, refusals) is proven on synthetic fixtures and on the real packaged
  graph; there is no ground-truthed real drive in the corpus yet, so there is
  no measured matching-error number.
- **Not navigation.** The matched claim is an evaluation overlay on published
  positions, drawn beside the raw one and never in its place; nothing downstream
  consumes it, and the engine's own position remains what the map treats as truth.
- **Undirected graph.** Heading compatibility cannot tell a wrong-way match
  from a right-way one; one-way semantics would need a directed graph version.
- **OSM snapshot.** The graph is frozen at the extract's
  `timestamp_osm_base`; roads built after that do not exist for the matcher.

## Reproduce

```
# Python: builder + packaged asset
.venv/Scripts/python.exe -B -X utf8 -m unittest discover -s tests

# Kotlin: road graph + matcher (in the full JVM gate)
cd mobile && bash gradlew testDebugUnitTest lintDebug --offline --console=plain
```
