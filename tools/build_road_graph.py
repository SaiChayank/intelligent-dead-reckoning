"""Build the offline matchable road graph from an OSM highway extract.

The road graph is deliberately separate from the rendering pack
(``mobile/app/src/main/assets/offline/hyderabad/hyderabad.mbtiles``): MBTiles
hold display tiles and are not a routable/matchable graph. This builder reads an
Overpass JSON extract (``out geom`` format) of ``highway`` ways and emits a
compact, deterministic graph package for the map matcher:

    road-graph.json.gz   columnar nodes + intersection-split edges
    manifest.json        id/version/bounds/counts/source/licence/checksums

Schema (``idr-road-graph/1``), all arrays equal-length where applicable:

    classes           class table (highway=* values)
    names             unique road names (sorted)
    nodes             [lat_e6, lon_e6] per split node
    edge_u/edge_v     node indices, undirected topology
    edge_class        index into classes
    edge_name         index into names, -1 = unnamed
    edge_way          OSM way id
    edge_part         segment index within the way
    edge_geom_offset  start index into geom (M+1 entries, last = total)
    geom              flattened polyline: per edge first point absolute
                      microdegrees, following points delta microdegrees

Edge id is derivable as ``osm/{edge_way}/{edge_part}``. The graph is
undirected: matching geometry does not need oneway semantics.

Source data is OpenStreetMap, licensed ODbL 1.0. The manifest records the exact
Overpass query, mirror, and planet timestamp so the extract is reproducible.

Determinism: ways are processed in ascending OSM id, node indices are assigned
in first-encounter order, and the gzip member is written with mtime=0.
Building twice from the same extract produces byte-identical files.

Stdlib only. Python <= 100 columns. Run from the repository root:

    python tools/build_road_graph.py \\
        --input data/raw/osm/hyderabad_highways_20260930.osm.json \\
        --out-dir mobile/app/src/main/assets/roadgraph/hyderabad-v1
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path

BUILDER_VERSION = 1
GRAPH_FORMAT = "idr-road-graph/1"
DEFAULT_ID = "hyderabad-road-graph-v1"
DEFAULT_BOUNDS = (78.35, 17.3, 78.6, 17.55)  # west, south, east, north
MIN_EDGE_LENGTH_M = 0.05
EARTH_RADIUS_M = 6371008.8
MICRODEG = 1_000_000

MATCHABLE_CLASSES = [
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "service",
]
MATCHABLE_SET = set(MATCHABLE_CLASSES)

LICENSE = "ODbL-1.0"
ATTRIBUTION = "\u00a9 OpenStreetMap contributors (ODbL)"

DEFAULT_QUERY = (
    '[out:json][timeout:420];'
    'way["highway"~"^(motorway|trunk|primary|secondary|tertiary|'
    'unclassified|residential|living_street|service)(_link)?$"]'
    '(17.3,78.35,17.55,78.6);out geom;'
)


def microdegrees(value: float) -> int:
    return int(round(value * MICRODEG))


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    rlat1, rlon1, rlat2, rlon2 = map(math.radians, (lat1, lon1, lat2, lon2))
    dlat = rlat2 - rlat1
    dlon = rlon2 - rlon1
    a = (math.sin(dlat / 2) ** 2
         + math.cos(rlat1) * math.cos(rlat2) * math.sin(dlon / 2) ** 2)
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def polyline_length_m(points: list[tuple[float, float]]) -> float:
    total = 0.0
    for (lat1, lon1), (lat2, lon2) in zip(points, points[1:]):
        total += haversine_m(lat1, lon1, lat2, lon2)
    return total


def matchable_ways(extract: dict) -> list[dict]:
    ways = [
        element for element in extract.get("elements", [])
        if element.get("type") == "way"
        and element.get("tags", {}).get("highway") in MATCHABLE_SET
        and len(element.get("nodes", [])) >= 2
        and len(element.get("geometry", [])) == len(element.get("nodes", []))
    ]
    ways.sort(key=lambda way: way["id"])
    return ways


def split_points(way: dict, node_use: dict[int, int]) -> list[int]:
    node_ids = way["nodes"]
    last = len(node_ids) - 1
    cuts = {
        i for i, node_id in enumerate(node_ids)
        if i == 0 or i == last or node_use.get(node_id, 0) >= 2
    }
    return sorted(cuts)


def build_graph(extract: dict, bounds: tuple[float, float, float, float],
                graph_id: str, version: int) -> tuple[dict, dict]:
    ways = matchable_ways(extract)
    if not ways:
        raise SystemExit("no matchable highway ways in extract")

    node_use: dict[int, int] = {}
    coord: dict[int, tuple[float, float]] = {}
    for way in ways:
        for node_id, point in zip(way["nodes"], way["geometry"]):
            node_use[node_id] = node_use.get(node_id, 0) + 1
            if node_id not in coord:
                coord[node_id] = (point["lat"], point["lon"])

    node_ids: list[int] = []
    node_index: dict[int, int] = {}

    def node_at(osm_id: int) -> int:
        if osm_id not in node_index:
            node_index[osm_id] = len(node_ids)
            node_ids.append(osm_id)
        return node_index[osm_id]

    edge_u: list[int] = []
    edge_v: list[int] = []
    edge_class: list[int] = []
    edge_name: list[int] = []
    edge_way: list[int] = []
    edge_part: list[int] = []
    edge_geom_offset: list[int] = []
    geom: list[list[int]] = []
    names: list[str] = []
    name_index: dict[str, int] = {}
    min_lat = min_lon = math.inf
    max_lat = max_lon = -math.inf

    for way in ways:
        way_nodes = way["nodes"]
        cuts = split_points(way, node_use)
        tags = way["tags"]
        name = tags.get("name")
        if name is not None and name not in name_index:
            name_index[name] = len(names)
            names.append(name)
        for part, (a, b) in enumerate(zip(cuts, cuts[1:])):
            if b <= a:
                continue
            points = [coord[node_id] for node_id in way_nodes[a:b + 1]]
            if polyline_length_m(points) < MIN_EDGE_LENGTH_M:
                continue
            for lat, lon in points:
                min_lat = min(min_lat, lat)
                max_lat = max(max_lat, lat)
                min_lon = min(min_lon, lon)
                max_lon = max(max_lon, lon)
            edge_u.append(node_at(way_nodes[a]))
            edge_v.append(node_at(way_nodes[b]))
            edge_class.append(MATCHABLE_CLASSES.index(tags["highway"]))
            edge_name.append(name_index.get(name, -1) if name else -1)
            edge_way.append(way["id"])
            edge_part.append(part)
            edge_geom_offset.append(len(geom))
            prev_lat = prev_lon = 0
            for index, (lat, lon) in enumerate(points):
                lat_e6 = microdegrees(lat)
                lon_e6 = microdegrees(lon)
                if index == 0:
                    geom.append([lat_e6, lon_e6])
                else:
                    geom.append([lat_e6 - prev_lat, lon_e6 - prev_lon])
                prev_lat, prev_lon = lat_e6, lon_e6

    if not edge_u:
        raise SystemExit("no edges survived splitting")
    edge_geom_offset.append(len(geom))

    nodes = [[microdegrees(coord[osm_id][0]), microdegrees(coord[osm_id][1])]
             for osm_id in node_ids]
    graph = {
        "format": GRAPH_FORMAT,
        "id": graph_id,
        "version": version,
        "crs": "WGS84",
        "coordinate_encoding": "delta-microdegrees",
        "query_bounds": list(bounds),
        "data_bounds": [min_lon, min_lat, max_lon, max_lat],
        "counts": {"nodes": len(nodes), "edges": len(edge_u),
                   "geom_points": len(geom)},
        "classes": MATCHABLE_CLASSES,
        "names": names,
        "nodes": nodes,
        "edge_u": edge_u,
        "edge_v": edge_v,
        "edge_class": edge_class,
        "edge_name": edge_name,
        "edge_way": edge_way,
        "edge_part": edge_part,
        "edge_geom_offset": edge_geom_offset,
        "geom": geom,
    }
    source = {
        "dataset": "OpenStreetMap",
        "license": LICENSE,
        "attribution": ATTRIBUTION,
        "extract_tool": "Overpass API (out geom)",
        "query": extract.get("overpass_query", DEFAULT_QUERY),
        "mirror": extract.get("overpass_mirror", ""),
        "timestamp_osm_base":
            extract.get("osm3s", {}).get("timestamp_osm_base", ""),
    }
    return graph, source


def write_json(path: Path, payload: dict) -> None:
    text = json.dumps(payload, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True)
    path.write_text(text + "\n", encoding="utf-8", newline="\n")


def write_gzip_json(path: Path, payload: dict) -> None:
    text = (json.dumps(payload, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=True) + "\n").encode("utf-8")
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as out:
            out.write(text)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path,
                        help="Overpass JSON extract (out geom)")
    parser.add_argument("--out-dir", required=True, type=Path,
                        help="output directory for road-graph.json.gz + "
                             "manifest.json")
    parser.add_argument("--id", default=DEFAULT_ID)
    parser.add_argument("--version", type=int, default=1)
    parser.add_argument("--bounds", default=",".join(map(str, DEFAULT_BOUNDS)),
                        help="west,south,east,north query bounds")
    parser.add_argument("--mirror", default="",
                        help="Overpass mirror URL that served the extract")
    args = parser.parse_args()

    bounds = tuple(float(part) for part in args.bounds.split(","))
    if len(bounds) != 4:
        raise SystemExit("--bounds needs west,south,east,north")

    extract = json.loads(args.input.read_text(encoding="utf-8"))
    graph, source = build_graph(extract, bounds, args.id, args.version)
    if args.mirror:
        source["mirror"] = args.mirror

    args.out_dir.mkdir(parents=True, exist_ok=True)
    graph_path = args.out_dir / "road-graph.json.gz"
    write_gzip_json(graph_path, graph)

    manifest = {
        "format": GRAPH_FORMAT,
        "id": args.id,
        "version": args.version,
        "bounds": list(bounds),
        "data_bounds": graph["data_bounds"],
        "counts": graph["counts"],
        "source": source,
        "builder": {"name": "tools/build_road_graph.py",
                    "version": BUILDER_VERSION},
        "files": [{
            "path": "road-graph.json.gz",
            "bytes": graph_path.stat().st_size,
            "sha256": sha256_of(graph_path),
        }],
    }
    write_json(args.out_dir / "manifest.json", manifest)

    print(f"nodes: {graph['counts']['nodes']}")
    print(f"edges: {graph['counts']['edges']}")
    print(f"geom points: {graph['counts']['geom_points']}")
    print(f"road-graph.json.gz: {graph_path.stat().st_size} bytes")
    print(f"sha256: {manifest['files'][0]['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
