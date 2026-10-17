"""Tests for Google Earth KML and GPX track exporter."""
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.export_kml_gpx import (
    radii,
    geodetic_from_enu,
    generate_kml,
    generate_gpx,
    simulate_trajectories,
    export_drive_to_kml_gpx,
)


class TestKmlGpxExport(unittest.TestCase):

    def test_geodesy_consistency(self):
        # Hyderabad anchor
        lat0, lon0, alt0 = 17.4350, 78.4450, 505.0
        # 0 east, 0 north should return the anchor
        lat, lon, alt = geodetic_from_enu(lat0, lon0, alt0, 0.0, 0.0, 0.0)
        self.assertAlmostEqual(lat, lat0, places=6)
        self.assertAlmostEqual(lon, lon0, places=6)
        self.assertAlmostEqual(alt, alt0, places=2)

        # 110660 m North should move ~1 degree latitude
        lat_n, lon_n, _ = geodetic_from_enu(lat0, lon0, alt0, 0.0, 110660.0, 0.0)
        self.assertAlmostEqual(lat_n - lat0, 1.0, places=2)

    def test_kml_xml_validity(self):
        truth, classical, model_a, gnss = simulate_trajectories(total_s=10.0, dt=1.0)
        kml_str = generate_kml("test-session", truth, classical, model_a, gnss)

        # Must parse as valid XML
        root = ET.fromstring(kml_str)
        # Check namespace tag
        self.assertTrue(root.tag.endswith("kml"))
        doc = root.find("{http://www.opengis.net/kml/2.2}Document")
        self.assertIsNotNone(doc)

        # Check placemarks
        placemarks = doc.findall(".//{http://www.opengis.net/kml/2.2}Placemark")
        self.assertGreaterEqual(len(placemarks), 3)

    def test_gpx_xml_validity(self):
        truth, classical, model_a, gnss = simulate_trajectories(total_s=10.0, dt=1.0)
        gpx_str = generate_gpx("test-session", truth, model_a)

        # Must parse as valid XML
        root = ET.fromstring(gpx_str)
        self.assertTrue(root.tag.endswith("gpx"))

        tracks = root.findall("{http://www.topografix.com/GPX/1/1}trk")
        self.assertEqual(len(tracks), 2)  # Ground truth and Model A

    def test_export_pipeline_creates_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            kml_p, gpx_p = export_drive_to_kml_gpx("test-drive", Path(tmpdir))
            self.assertTrue(kml_p.is_file())
            self.assertTrue(gpx_p.is_file())
            self.assertGreater(kml_p.stat().st_size, 500)
            self.assertGreater(gpx_p.stat().st_size, 500)


if __name__ == "__main__":
    unittest.main()
