import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import gsdc_outage_eval as ge  # noqa: E402
import gsdc_to_recording as conv  # noqa: E402
from contracts.v1.models import GnssMeasurement, ImuMeasurement  # noqa: E402


def streams(seconds=20.0, hz=50.0):
    t = np.arange(0.0, seconds, 1.0 / hz) + 1_000.0
    accel = np.column_stack([t, np.zeros_like(t), np.zeros_like(t), np.full_like(t, 9.8)])
    gyro = np.column_stack([t, np.zeros((len(t), 3))])
    truth = {"t": np.arange(1_000.0, 1_000.0 + seconds), "lat": 17.0 + np.arange(seconds) * 1e-4, "lon": np.full(int(seconds), 78.0),
             "alt": np.full(int(seconds), 500.0), "speed": np.full(int(seconds), 11.0), "bearing": np.zeros(int(seconds))}
    return {"accelerometer": accel, "gyroscope": gyro}, truth


def aiding(seconds=20, accuracy=3.1):
    t = 1_000.0 + np.arange(seconds) + 0.35                     # epochs on their own times, not the reference's
    speed = np.full(seconds, 10.9)
    speed[5] = np.nan                                           # one epoch without a Doppler solution
    return {"epochs": {"t": t, "lat": 17.0 + np.arange(seconds) * 1e-4 + 2e-5, "lon": np.full(seconds, 78.0), "alt": np.full(seconds, 501.0), "speed": speed,
                       "course": np.full(seconds, 1.0), "satellites": np.concatenate([np.full(seconds - 1, 22), [3]])}, "accuracy_m": accuracy}


def gnss_of(records):
    return [r.event.data for r in records if isinstance(r.event.data, GnssMeasurement)]


class AidingConversionTest(unittest.TestCase):
    def test_reference_aiding_is_unchanged_and_carries_the_declared_values(self):
        imu, truth = streams()
        records, fixes = conv.build_records("s", imu, truth, 0.0)
        g = gnss_of(records)
        self.assertEqual(fixes, len(g))
        self.assertTrue(all(x.horizontal_accuracy_m == conv.DECLARED_ACCURACY_M and x.satellites_used == conv.DECLARED_SATELLITES for x in g))

    def test_phone_grade_aiding_replaces_the_reference_with_the_epochs_and_their_own_quality(self):
        imu, truth = streams()
        records, fixes = conv.build_records("s", imu, truth, 0.0, aiding())
        g = gnss_of(records)
        self.assertEqual(fixes, len(g))
        self.assertEqual(len(g), 19)                             # the epoch reporting 3 satellites is refused: the engine needs 4
        self.assertTrue(all(x.horizontal_accuracy_m == 3.1 for x in g))
        self.assertTrue(all(x.satellites_used == 22 for x in g))
        self.assertNotAlmostEqual(g[0].latitude_deg, 17.0)       # the aiding, not the reference
        self.assertAlmostEqual(g[0].latitude_deg, 17.0 + 2e-5, places=6)
        no_doppler = [x for x in g if x.speed_m_s is None]
        self.assertEqual(len(no_doppler), 1)
        self.assertIsNone(no_doppler[0].bearing_deg)             # a course without a speed would claim more than was measured
        times = [r.event.t_ns for r in records if isinstance(r.event.data, GnssMeasurement)]
        self.assertEqual(times, sorted(times))

    def test_the_reference_rows_use_the_recordings_clock_and_the_references_own_values(self):
        imu, truth = streams()
        rows = conv.reference_rows(truth, imu, 0.0)
        self.assertEqual(len(rows), 20)
        self.assertEqual(rows[0][0], conv.ORIGIN_NS)
        self.assertAlmostEqual(rows[1][0] - rows[0][0], 1_000_000_000, delta=1)
        self.assertAlmostEqual(rows[3][1], 17.0 + 3e-4, places=9)
        self.assertEqual(rows[0][3], 11.0)

    def test_the_evaluator_scores_against_reference_csv_when_present_and_the_recordings_own_fixes_otherwise(self):
        imu, truth = streams(40.0)                                # the scorer refuses drives with fewer than 30 GPS fixes
        with tempfile.TemporaryDirectory() as tmp:
            session = Path(tmp) / "rec"
            records, fixes = conv.build_records("rec", imu, truth, 0.0, aiding(40))
            conv.write_recording(Path(tmp), "rec", "test", records, fixes, {"accelerometer": 50.0, "gyroscope": 50.0}, conv.reference_rows(truth, imu, 0.0))
            self.assertTrue((session / "reference.csv").is_file())
            ref = ge.load_truth(session)
            self.assertEqual(len(ref["t"]), 40)
            self.assertAlmostEqual(float(ref["lat"][0]), 17.0, places=8)       # the reference, not the 2e-5 degree aiding offset
            self.assertTrue(np.all(np.diff(ref["dist"]) > 0))
            (session / "reference.csv").unlink()
            own = ge.load_truth(session)                                        # no reference file: the recording's own fixes
            self.assertEqual(len(own["t"]), 39)
            self.assertAlmostEqual(float(own["lat"][0]), 17.0 + 2e-5, places=6)


if __name__ == "__main__":
    unittest.main()
