"""The packaged road graph must be exactly what its manifest claims.

The matcher trusts this artifact blind on device, so the package is validated
here the way the map pack is validated by its own tests: byte size and SHA-256
against the manifest, ODbL licensing present, array invariants of the columnar
format, and — the decisive one — every edge's decoded polyline endpoints must be
its declared u/v nodes. A malformed graph would make previous-edge continuity
and connectivity silently wrong. The declared bounds must also match the
rendering pack's bounds: both describe the same bounded Hyderabad region.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "mobile/app/src/main/assets/roadgraph/hyderabad-v1"
MAP_PACK = ROOT / "mobile/app/src/main/assets/offline/hyderabad"
MATCHABLE_CLASSES = {
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "service",
}


class RoadGraphAssetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = json.loads(
            (PACK / "manifest.json").read_text(encoding="utf-8"))
        raw = gzip.decompress((PACK / "road-graph.json.gz").read_bytes())
        cls.graph = json.loads(raw)

    def test_manifest_checksum_and_size_match_the_package(self) -> None:
        entry = self.manifest["files"][0]
        self.assertEqual(entry["path"], "road-graph.json.gz")
        path = PACK / entry["path"]
        self.assertTrue(path.is_file())
        self.assertEqual(entry["bytes"], path.stat().st_size)
        self.assertEqual(entry["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())

    def test_manifest_declares_licensing_and_provenance(self) -> None:
        source = self.manifest["source"]
        self.assertEqual(source["dataset"], "OpenStreetMap")
        self.assertEqual(source["license"], "ODbL-1.0")
        self.assertIn("OpenStreetMap contributors", source["attribution"])
        self.assertTrue(source["query"])
        self.assertTrue(source["timestamp_osm_base"])
        self.assertTrue((PACK / "NOTICE.md").is_file())

    def test_bounds_match_the_rendering_pack_region(self) -> None:
        map_manifest = json.loads(
            (MAP_PACK / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(self.manifest["bounds"], map_manifest["bounds"])
        self.assertEqual(self.graph["query_bounds"], self.manifest["bounds"])

    def test_counts_and_array_invariants(self) -> None:
        graph = self.graph
        counts = graph["counts"]
        self.assertEqual(counts["nodes"], len(graph["nodes"]))
        self.assertEqual(counts["edges"], len(graph["edge_u"]))
        self.assertEqual(counts["geom_points"], len(graph["geom"]))
        edges = counts["edges"]
        for key in ("edge_v", "edge_class", "edge_name", "edge_way",
                    "edge_part"):
            self.assertEqual(len(graph[key]), edges, key)
        self.assertEqual(len(graph["edge_geom_offset"]), edges + 1)
        offsets = graph["edge_geom_offset"]
        self.assertEqual(offsets[0], 0)
        self.assertEqual(offsets[-1], counts["geom_points"])
        self.assertTrue(all(a < b for a, b in zip(offsets, offsets[1:])))
        self.assertEqual(graph["format"], "idr-road-graph/1")
        self.assertEqual(graph["coordinate_encoding"], "delta-microdegrees")
        self.assertTrue(set(graph["classes"]) <= MATCHABLE_CLASSES)

    def test_edge_endpoints_are_their_declared_nodes(self) -> None:
        graph = self.graph
        nodes = graph["nodes"]
        geom = graph["geom"]
        offsets = graph["edge_geom_offset"]
        edge_u, edge_v = graph["edge_u"], graph["edge_v"]
        broken: list[str] = []
        seen_ids: set[tuple[int, int]] = set()
        duplicate_ids = 0
        for edge in range(len(edge_u)):
            start, end = offsets[edge], offsets[edge + 1]
            lat = lon = 0
            for index in range(start, end):
                dlat, dlon = geom[index]
                if index == start:
                    lat, lon = dlat, dlon
                else:
                    lat += dlat
                    lon += dlon
            if tuple(geom[start]) != tuple(nodes[edge_u[edge]]):
                broken.append(f"edge {edge} start")
            if (lat, lon) != tuple(nodes[edge_v[edge]]):
                broken.append(f"edge {edge} end")
            key = (graph["edge_way"][edge], graph["edge_part"][edge])
            if key in seen_ids:
                duplicate_ids += 1
            seen_ids.add(key)
        self.assertEqual(broken[:5], [])
        self.assertEqual(duplicate_ids, 0)

    def test_nodes_lie_within_declared_data_bounds(self) -> None:
        west, south, east, north = self.graph["data_bounds"]
        self.assertLess(west, east)
        self.assertLess(south, north)
        lats = [lat for lat, _ in self.graph["nodes"]]
        lons = [lon for _, lon in self.graph["nodes"]]
        self.assertGreaterEqual(min(lats) / 1e6, south - 1e-4)
        self.assertLessEqual(max(lats) / 1e6, north + 1e-4)
        self.assertGreaterEqual(min(lons) / 1e6, west - 1e-4)
        self.assertLessEqual(max(lons) / 1e6, east + 1e-4)


if __name__ == "__main__":
    unittest.main()
