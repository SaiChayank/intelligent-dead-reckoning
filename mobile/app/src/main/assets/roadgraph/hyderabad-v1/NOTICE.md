# Central Hyderabad offline road-graph package

Coverage: query bounds west 78.35, south 17.30, east 78.60, north 17.55
(WGS84 degrees), the same bounded region as the rendering pack. This is not all
Hyderabad, Telangana or India. Ways that touch the bounds extend outside it;
`data_bounds` in the manifest records the actual geometry extent.

This is a **matching graph for map matching only**. It is deliberately separate
from `offline/hyderabad/hyderabad.mbtiles`: MBTiles hold display tiles and are
not a routable/matchable graph. The graph is undirected (matching geometry does
not need oneway semantics) and carries no turn restrictions, speeds, or
elevations. It is not a routing graph.

Data: © OpenStreetMap contributors, ODbL 1.0.
https://www.openstreetmap.org/copyright
https://opendatacommons.org/licenses/odbl/1-0/

Derivation: Overpass API `out geom` extract of `highway` ways filtered to
drivable classes (motorway..service, including link variants), split at shared
nodes, packed by `tools/build_road_graph.py`. The exact query, mirror, planet
timestamp, file checksum and counts are recorded in `manifest.json`; rebuilding
from the same extract is byte-identical (deterministic builder).
