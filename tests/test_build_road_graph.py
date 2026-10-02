"""The road-graph builder must produce a matchable graph, not merely bytes.

The graph feeds a safety-adjacent matcher, so the building blocks are tested
individually: the highway class filter (a footpath must never become a road
candidate), splitting at shared nodes (connectivity drives previous-edge
continuity), the delta-microdegree geometry encoding (round-trip), degenerate
edge removal, and determinism — two builds from one extract must be
byte-identical or the manifest checksum is meaningless. The manifest must carry
the ODbL licensing and the exact source provenance before the package is legal
to ship.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.build_road_graph import (
    ATTRIBUTION,
    GRAPH_FORMAT,
    LICENSE,
    build_graph,
    main,
    matchable_ways,
    microdegrees,
    write_gzip_json,
    write_json,
)

BOUNDS = (78.35, 17.3, 78.6, 17.55)


def way(way_id: int, highway: str, node_ids: list[int],
        points: list[tuple[float, float]], name: str | None = None) -> dict:
    tags = {"highway": highway}
    if name is not None:
        tags["name"] = name
    return {
        "type": "way",
        "id": way_id,
        "nodes": node_ids,
        "geometry": [{"lat": lat, "lon": lon} for lat, lon in points],
        "tags": tags,
    }


def extract(*ways: dict) -> dict:
    return {
        "elements": list(ways),
        "osm3s": {"timestamp_osm_base": "2026-10-01T02:09:04Z"},
    }


def decode_geometry(entries: list[list[int]]) -> list[tuple[int, int]]:
    """Independent decode of the delta-microdegree polyline encoding."""
    out: list[tuple[int, int]] = []
    lat = lon = 0
    for index, (dlat, dlon) in enumerate(entries):
        if index == 0:
            lat, lon = dlat, dlon
        else:
            lat += dlat
            lon += dlon
        out.append((lat, lon))
    return out


def two_ways_sharing_a_node() -> dict:
    """Way 20 branches off node 2, which is INTERIOR to way 10: the builder
    must split way 10 there so the two edges share a node for continuity."""
    return extract(
        way(10, "residential", [1, 2, 3],
            [(17.40, 78.45), (17.401, 78.45), (17.402, 78.45)], name="North St"),
        way(20, "service", [2, 4],
            [(17.401, 78.45), (17.401, 78.451)], name="North St"),
        way(30, "footway", [5, 6],
            [(17.41, 78.46), (17.411, 78.46)]),
    )


class ClassFilterTest(unittest.TestCase):
    def test_only_drivable_highways_become_candidates(self) -> None:
        ways = matchable_ways(two_ways_sharing_a_node())
        self.assertEqual([w["id"] for w in ways], [10, 20])

    def test_link_variants_and_service_are_included(self) -> None:
        source = extract(
            way(1, "motorway_link", [1, 2], [(17.4, 78.45), (17.401, 78.45)]),
            way(2, "service", [3, 4], [(17.41, 78.45), (17.411, 78.45)]),
            way(3, "path", [5, 6], [(17.42, 78.45), (17.421, 78.45)]),
            way(4, "steps", [7, 8], [(17.43, 78.45), (17.431, 78.45)]),
        )
        self.assertEqual([w["id"] for w in matchable_ways(source)], [1, 2])


class SplittingTest(unittest.TestCase):
    def test_shared_node_splits_ways_into_connected_edges(self) -> None:
        graph, _ = build_graph(two_ways_sharing_a_node(), BOUNDS, "t", 1)
        self.assertEqual(graph["counts"]["edges"], 3)
        self.assertEqual(graph["counts"]["nodes"], 4)
        ways = graph["edge_way"]
        self.assertEqual(ways, [10, 10, 20])
        self.assertEqual(graph["edge_part"], [0, 1, 0])
        # Node 2 is interior to way 10 but shared with way 20: osm/10/0,
        # osm/10/1 and osm/20/0 all meet there, so continuity can traverse.
        self.assertEqual(graph["edge_v"][0], graph["edge_u"][1])
        self.assertEqual(graph["edge_u"][1], graph["edge_u"][2])
        self.assertEqual(graph["edge_v"][1], 2)  # node 3, not shared

    def test_degenerate_edges_are_dropped(self) -> None:
        source = extract(
            way(1, "residential", [1, 2], [(17.4, 78.45), (17.4, 78.45)]),
        )
        with self.assertRaises(SystemExit):
            build_graph(source, BOUNDS, "t", 1)


class GeometryEncodingTest(unittest.TestCase):
    def test_geometry_round_trips_through_delta_encoding(self) -> None:
        graph, _ = build_graph(two_ways_sharing_a_node(), BOUNDS, "t", 1)
        offsets = graph["edge_geom_offset"]
        geom = graph["geom"]
        first = decode_geometry(geom[offsets[0]:offsets[1]])
        self.assertEqual(
            first,
            [(microdegrees(17.40), microdegrees(78.45)),
             (microdegrees(17.401), microdegrees(78.45))],
        )
        self.assertEqual(geom[0][0], microdegrees(17.40))

    def test_names_are_unique_in_the_table(self) -> None:
        graph, _ = build_graph(two_ways_sharing_a_node(), BOUNDS, "t", 1)
        self.assertEqual(graph["names"], ["North St"])
        self.assertEqual(graph["edge_name"], [0, 0, 0])


class ManifestTest(unittest.TestCase):
    def build(self, root: Path) -> dict:
        source = root / "extract.json"
        source.write_text(json.dumps(two_ways_sharing_a_node()), encoding="utf-8")
        out = root / "pack"
        argv = ["build_road_graph.py", "--input", str(source),
                "--out-dir", str(out), "--id", "test-graph",
                "--mirror", "https://example.invalid/api"]
        with mock.patch.object(sys, "argv", argv):
            self.assertEqual(main(), 0)
        return json.loads((out / "manifest.json").read_text(encoding="utf-8"))

    def test_manifest_records_licensing_provenance_and_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = self.build(root)
            graph_path = root / "pack" / "road-graph.json.gz"
            self.assertEqual(manifest["format"], GRAPH_FORMAT)
            self.assertEqual(manifest["id"], "test-graph")
            self.assertEqual(manifest["bounds"], list(BOUNDS))
            self.assertEqual(manifest["source"]["dataset"], "OpenStreetMap")
            self.assertEqual(manifest["source"]["license"], LICENSE)
            self.assertIn("OpenStreetMap", manifest["source"]["attribution"])
            self.assertEqual(ATTRIBUTION, manifest["source"]["attribution"])
            self.assertEqual(
                manifest["source"]["timestamp_osm_base"], "2026-10-01T02:09:04Z")
            self.assertEqual(
                manifest["source"]["mirror"], "https://example.invalid/api")
            entry = manifest["files"][0]
            self.assertEqual(entry["path"], "road-graph.json.gz")
            self.assertEqual(entry["bytes"], graph_path.stat().st_size)
            digest = hashlib.sha256(graph_path.read_bytes()).hexdigest()
            self.assertEqual(entry["sha256"], digest)

    def test_output_is_byte_identical_across_builds(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = root / "a.json.gz"
            second = root / "b.json.gz"
            graph, _ = build_graph(two_ways_sharing_a_node(), BOUNDS, "t", 1)
            write_gzip_json(first, graph)
            write_gzip_json(second, graph)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first.read_bytes()[:2], b"\x1f\x8b")

    def test_graph_json_parses_back(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.build(root)
            raw = gzip.decompress(
                (root / "pack" / "road-graph.json.gz").read_bytes())
            graph = json.loads(raw)
            self.assertEqual(graph["counts"]["nodes"], 4)
            self.assertEqual(graph["counts"]["geom_points"], 6)
            self.assertEqual(graph["coordinate_encoding"],
                             "delta-microdegrees")

    def test_write_json_is_stable_text(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "x.json"
            write_json(path, {"b": 1, "a": [1, 2]})
            self.assertEqual(path.read_text(encoding="utf-8"),
                             '{"a":[1,2],"b":1}\n')


if __name__ == "__main__":
    unittest.main()
