"""Golden contract tests, not production ingestion or navigation validation."""
import json
import math
from pathlib import Path
import unittest

from training.frame_math import heading_to_enu

FIXTURE = Path(__file__).resolve().parents[1] / "contracts" / "v1" / "golden.json"

# Python-native construction is serialized and compared with the shared wire fixture.
EXPECTED = {
  "contract_version": "1.0.0",
  "session_id": "golden-sim",
  "source": "simulation",
  "events": [
    {
      "event_id": "0",
      "type": "imu",
      "t_ns": "9007199254740993",
      "received_ns": "9007199259740993",
      "data": {
        "sensor": "accelerometer",
        "frame": "android_device",
        "unit": "m/s^2",
        "xyz": [
          0,
          0,
          9.80665
        ],
        "accuracy": "high"
      }
    },
    {
      "event_id": "1",
      "type": "imu",
      "t_ns": "9007199264740993",
      "received_ns": "9007199269740993",
      "data": {
        "sensor": "gyroscope",
        "frame": "android_device",
        "unit": "rad/s",
        "xyz": [
          0,
          0,
          -0.5
        ],
        "accuracy": "unreliable"
      }
    },
    {
      "event_id": "2",
      "type": "gnss",
      "t_ns": "9007199274740993",
      "received_ns": "9007199279740993",
      "data": {
        "latitude_deg": 12.9716,
        "longitude_deg": 77.5946,
        "altitude_m": None,
        "altitude_reference": None,
        "speed_m_s": None,
        "bearing_deg": None,
        "horizontal_accuracy_m": 3.5,
        "vertical_accuracy_m": None,
        "satellites_used": None,
        "provider": "fixture",
        "utc_ms": None
      }
    },
    {
      "event_id": "3",
      "type": "calibration",
      "t_ns": "9007199284740993",
      "received_ns": "9007199289740993",
      "data": {
        "id": "fixture-cal",
        "status": "valid",
        "q_vehicle_from_device_wxyz": [
          1,
          0,
          0,
          0
        ],
        "gyro_bias_rad_s": [
          0.01,
          -0.02,
          0.03
        ],
        "accelerometer_bias_m_s2": None,
        "confidence": 0.8
      }
    },
    {
      "event_id": "4",
      "type": "navigation",
      "t_ns": "9007199294740993",
      "received_ns": "9007199299740993",
      "data": {
        "status": "tracking",
        "initialization_mode": "deployable",
        "origin_wgs84_deg_m": [
          12.9716,
          77.5946,
          0
        ],
        "position_enu_m": [
          1.25,
          -2.5,
          0
        ],
        "velocity_enu_m_s": [
          0,
          10,
          0
        ],
        "q_enu_from_vehicle_wxyz": [
          0.7071067811865476,
          0,
          0,
          0.7071067811865476
        ],
        "heading_deg": 0,
        "calibration_id": "fixture-cal",
        "gnss_used_after_initialization": False
      }
    },
    {
      "event_id": "5",
      "type": "gnss_quality",
      "t_ns": "9007199304740993",
      "received_ns": "9007199309740993",
      "data": {
        "state": "unavailable",
        "fix_age_s": None,
        "satellites_used": None,
        "reasons": [
          "NO_FIX"
        ]
      }
    },
    {
      "event_id": "6",
      "type": "confidence",
      "t_ns": "9007199314740993",
      "received_ns": "9007199319740993",
      "data": {
        "state": "unvalidated",
        "probability": None,
        "horizontal_accuracy_95_m": None,
        "speed_std_m_s": None
      }
    },
    {
      "event_id": "7",
      "type": "diagnostic",
      "t_ns": "9007199324740993",
      "received_ns": "9007199329740993",
      "data": {
        "severity": "warning",
        "code": "SIMULATED_INPUT",
        "message": "Synthetic contract fixture; not live sensor data.",
        "dropped_count": 1
      }
    }
  ]
}


class NavigationContractTest(unittest.TestCase):
    def setUp(self):
        self.wire = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_python_native_fixture_matches_every_wire_value(self):
        self.assertEqual(json.loads(json.dumps(EXPECTED, allow_nan=False)), self.wire)

    def test_nanosecond_timestamp_is_lossless_and_events_are_distinct(self):
        events = self.wire["events"]
        first = int(events[0]["t_ns"])
        self.assertEqual(first, 9007199254740993)
        self.assertNotEqual(int(float(events[0]["t_ns"])), first)
        self.assertEqual(len({e["event_id"] for e in events}), len(events))
        for i, event in enumerate(events):
            self.assertEqual(int(event["t_ns"]), first + i * 10_000_000)
            self.assertEqual(int(event["received_ns"]) - int(event["t_ns"]), 5_000_000)

    def test_missing_gnss_and_uncertainty_are_not_zero_or_north(self):
        gnss = self.wire["events"][2]["data"]
        for name in ("speed_m_s", "bearing_deg", "altitude_m", "satellites_used"):
            self.assertIsNone(gnss[name])
        self.assertIsNone(self.wire["events"][6]["data"]["probability"])

    def test_signed_imu_units_and_heading_agree_with_navigation_fixture(self):
        acceleration, gyro = [e["data"] for e in self.wire["events"][:2]]
        self.assertEqual(acceleration["unit"], "m/s^2")
        self.assertAlmostEqual(acceleration["xyz"][2], 9.80665, delta=1e-12)
        self.assertEqual(gyro["unit"], "rad/s")
        self.assertEqual(gyro["xyz"][2], -0.5)
        nav = self.wire["events"][4]["data"]
        yaw = heading_to_enu(nav["heading_deg"])
        for actual, expected in zip([10 * math.cos(yaw), 10 * math.sin(yaw), 0], nav["velocity_enu_m_s"]):
            self.assertAlmostEqual(actual, expected, delta=1e-12)
        q = nav["q_enu_from_vehicle_wxyz"]
        self.assertAlmostEqual(sum(x*x for x in q), 1, delta=1e-12)
        # +90 degree ENU yaw: vehicle forward X maps to navigation North Y.
        w, x, y, z = q
        self.assertAlmostEqual(1-2*(y*y+z*z), 0, delta=1e-12)
        self.assertAlmostEqual(2*(x*y+w*z), 1, delta=1e-12)


if __name__ == "__main__":
    unittest.main()

