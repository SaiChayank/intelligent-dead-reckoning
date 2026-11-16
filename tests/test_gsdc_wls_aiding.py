import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import gsdc_wls_aiding as wls  # noqa: E402


def geodetic_to_ecef(lat_deg, lon_deg, h):
    lat, lon = np.radians(lat_deg), np.radians(lon_deg)
    n = wls.A / np.sqrt(1 - wls.E2 * np.sin(lat) ** 2)
    return np.array([(n + h) * np.cos(lat) * np.cos(lon), (n + h) * np.cos(lat) * np.sin(lon), (n * (1 - wls.E2) + h) * np.sin(lat)])


def constellation(user, count=10, seed=2):
    """Satellites spread over the sky at GNSS orbit radius with realistic velocities, as seen from `user`."""
    rng = np.random.default_rng(seed)
    up = user / np.linalg.norm(user)
    positions, velocities = [], []
    for _ in range(count):
        direction = rng.normal(size=3)
        direction -= direction @ up * up * rng.uniform(0.0, 1.0)         # keep most above the horizon
        direction /= np.linalg.norm(direction)
        if direction @ up < 0.2:
            direction = direction + up
            direction /= np.linalg.norm(direction)
        positions.append(user + direction * 2.0e7)
        tangent = np.cross(direction, rng.normal(size=3))
        velocities.append(tangent / np.linalg.norm(tangent) * 3000.0)
    return np.array(positions), np.array(velocities)


def rates(user, v_user, drift, sv_pos, sv_vel):
    u = (sv_pos - user) / np.linalg.norm(sv_pos - user, axis=1, keepdims=True)
    return np.einsum("ij,ij->i", u, sv_vel - v_user) + drift


class GeodesyTest(unittest.TestCase):
    def test_ecef_geodetic_round_trip_at_several_places(self):
        for lat, lon, h in ((17.44, 78.35, 540.0), (37.4, -122.07, -28.0), (-33.9, 151.2, 20.0), (64.1, -21.9, 5.0), (0.0, 0.0, 0.0)):
            xyz = geodetic_to_ecef(lat, lon, h)
            la, lo, he = wls.ecef_to_geodetic(*(np.array([c]) for c in xyz))
            self.assertAlmostEqual(float(la[0]), lat, places=8)
            self.assertAlmostEqual(float(lo[0]), lon, places=8)
            self.assertAlmostEqual(float(he[0]), h, places=3)

    def test_the_enu_matrix_is_a_rotation_with_east_north_up_rows(self):
        m = wls.enu_matrix(37.4, -122.07)
        np.testing.assert_allclose(m @ m.T, np.eye(3), atol=1e-12)
        self.assertAlmostEqual(float(np.linalg.det(m)), 1.0)
        lat, lon = np.radians(37.4), np.radians(-122.07)       # "up" is the ellipsoid normal, not the geocentric direction
        np.testing.assert_allclose(m[2], [np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)], atol=1e-12)


class DopplerVelocityTest(unittest.TestCase):
    def setUp(self):
        self.user = geodetic_to_ecef(37.4, -122.07, 20.0)
        self.sv_pos, self.sv_vel = constellation(self.user, count=24)       # a phone sees 20 to 30 signals; with 10 the worst axis errs ~0.2 m/s
        self.v_user = np.array([12.0, -5.0, 1.5])

    def test_a_known_velocity_and_clock_drift_are_recovered_from_noisy_doppler(self):
        rng = np.random.default_rng(5)
        r = rates(self.user, self.v_user, 37.0, self.sv_pos, self.sv_vel) + rng.normal(0, 0.1, len(self.sv_pos))
        sol = wls.solve_velocity(self.user, self.sv_pos, self.sv_vel, r, np.full(len(r), 0.1))
        self.assertIsNotNone(sol)
        np.testing.assert_allclose(sol["v_ecef"], self.v_user, atol=0.3)
        self.assertAlmostEqual(sol["drift"], 37.0, delta=0.5)

    def test_one_gross_outlier_is_rejected_instead_of_dragging_the_solution(self):
        r = rates(self.user, self.v_user, 0.0, self.sv_pos, self.sv_vel)
        r[3] += 25.0                                                    # a reflected signal with a wild rate
        sol = wls.solve_velocity(self.user, self.sv_pos, self.sv_vel, r, np.full(len(r), 0.1))
        self.assertIsNotNone(sol)
        np.testing.assert_allclose(sol["v_ecef"], self.v_user, atol=0.05)    # no noise here, so a rejected outlier leaves an exact fit
        self.assertEqual(sol["rows"], len(r) - 1)

    def test_too_few_satellites_or_a_degenerate_geometry_gives_no_solution(self):
        r = rates(self.user, self.v_user, 0.0, self.sv_pos, self.sv_vel)
        self.assertIsNone(wls.solve_velocity(self.user, self.sv_pos[:4], self.sv_vel[:4], r[:4], np.full(4, 0.1)))
        # every satellite in almost the same direction: the clock drift and the velocity along it cannot be separated
        near = self.user + np.array([2.0e7, 0, 0]) + np.random.default_rng(1).normal(0, 1000.0, (8, 3))
        sol = wls.solve_velocity(self.user, near, np.tile(self.sv_vel[0], (8, 1)), rates(self.user, self.v_user, 0.0, near, np.tile(self.sv_vel[0], (8, 1))),
                                 np.full(8, 0.1))
        self.assertIsNone(sol)


class QualityTest(unittest.TestCase):
    def test_quality_reports_the_error_against_a_reference_and_ignores_epochs_outside_it(self):
        t = np.arange(0.0, 100.0)
        ref = {"t": t, "lat": 37.0 + t * 1e-5, "lon": np.full_like(t, -122.0), "speed": np.full_like(t, 15.0), "bearing": np.zeros_like(t)}
        offset_m = 4.0
        epochs = {"t": np.concatenate([t, [500.0]]), "lat": np.concatenate([ref["lat"] + np.degrees(offset_m / 6_371_000.0), [38.0]]),
                  "lon": np.concatenate([ref["lon"], [-122.0]]), "alt": np.zeros(101), "speed": np.concatenate([np.full(100, 15.1), [50.0]]),
                  "course": np.concatenate([np.full(100, 1.0), [90.0]]), "satellites": np.full(101, 20)}
        q = wls.quality(epochs, ref)
        self.assertEqual(q["epochs"], 100)
        self.assertAlmostEqual(q["position_error_m"]["median"], offset_m, delta=0.05)
        self.assertAlmostEqual(q["speed_error_m_s"]["median_abs"], 0.1, delta=0.01)
        self.assertAlmostEqual(q["course_error_deg_moving"]["median_abs"], 1.0, delta=0.01)


if __name__ == "__main__":
    unittest.main()
