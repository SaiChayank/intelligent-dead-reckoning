"""Physical checks for export hypotheses; real-data fit quality is not assumed."""
import unittest
import json
import importlib
from unittest.mock import patch

import numpy as np
import pandas as pd

from training.frame_export_audit import (acceleration_gate, android_orientation_matrix,
    compare_copies, diagnostic_view, model_window, orientation_rates, pair_models,
    sensor_summary, to_navigation_xy, vector_angles)
from training.frame_math import G0, metrics, rz


class ExportFrameTests(unittest.TestCase):
    def test_import_does_not_read_dataset_or_write_outputs(self):
        from training import frame_export_audit
        with patch('pandas.read_csv', side_effect=AssertionError('dataset read on import')), \
             patch('pathlib.Path.write_text', side_effect=AssertionError('write on import')), \
             patch('pathlib.Path.mkdir', side_effect=AssertionError('mkdir on import')), \
             patch('pathlib.Path.rglob', side_effect=AssertionError('scan on import')):
            importlib.reload(frame_export_audit)

    def test_small_schema_pair_produces_finite_serializable_evidence(self):
        n = 1200
        t = np.arange(n)*.1
        p = pd.DataFrame({'datetime_raw': pd.date_range('2020-01-01', periods=n, freq='100ms').strftime('%Y-%m-%d %H:%M:%S:%f'),
                          'orientation_yaw': t*0, 'orientation_pitch': t*0, 'orientation_roll': t*0,
                          'gps_speed_raw': t*0+10})
        for c in ('gyro_channel_yaw', 'gyro_channel_pitch', 'gyro_channel_roll'):
            p[c] = .1*np.sin(t)
        for axis in 'xyz':
            p['gravity_'+axis] = G0 if axis == 'z' else 0.
            p['accel_'+axis] = p['gravity_'+axis] + np.sin(t*(1 if axis == 'x' else 1.7))
        v = pd.DataFrame({'reference_time_of_day': t, 'reference_speed': 36.,
            'vehicle_yaw_rate': np.sin(t)*10, 'vehicle_accel_long': np.sin(t)/G0,
            'vehicle_accel_lat': np.cos(t)/G0, 'reference_heading': t*0})
        result = pair_models(p, v)
        self.assertEqual(len(result['windows']), 3)
        json.dumps(result, allow_nan=False)

    def test_incomplete_schema_reports_missing_not_success(self):
        p = pd.DataFrame({'gravity_x': [0.], 'gravity_y': [0.], 'gravity_z': [G0]})
        result = sensor_summary(p)
        self.assertIn('orientation_yaw', result['missing_fields'])
        self.assertNotIn('world_like_gravity_signature', result)
        self.assertAlmostEqual(result['gravity_up_tilt_deg']['median'], 0)

    def test_header_correspondence_requires_values_and_times(self):
        original = pd.DataFrame({'datetime_raw': ['t0', 't1'], 'gyro_channel_yaw': [1., 2.],
                                 'gyro_channel_pitch': [3., 4.], 'gyro_channel_roll': [5., 6.]})
        copy = original.rename(columns={'gyro_channel_yaw': 'gyro_channel_x',
            'gyro_channel_pitch': 'gyro_channel_y', 'gyro_channel_roll': 'gyro_channel_z'})
        copy.loc[0, 'gyro_channel_x'] += 1e-15
        self.assertTrue(compare_copies(original, copy)['equivalent'])
        copy.loc[0, 'datetime_raw'] = 'future'
        self.assertFalse(compare_copies(original, copy)['equivalent'])
        copy.loc[0, 'datetime_raw'] = 't0'
        copy.loc[0, 'gyro_channel_x'] += .1
        self.assertFalse(compare_copies(original, copy)['equivalent'])

    def test_source_view_is_non_mutating_and_rejects_ambiguity(self):
        p = pd.DataFrame({'gyro_channel_x': [1.]})
        self.assertIn('gyro_channel_yaw', diagnostic_view(p))
        self.assertEqual(list(p.columns), ['gyro_channel_x'])
        p['gyro_channel_yaw'] = 2.
        with self.assertRaises(ValueError):
            diagnostic_view(p)

    def test_android_extraction_round_trip(self):
        angles = np.array([[0, 0, 0], [60, -83, -141], [170, 34, 120], [-40, 12, -50.]])
        r = android_orientation_matrix(angles)
        extracted = np.degrees(np.column_stack([
            np.arctan2(r[:, 0, 1], r[:, 1, 1]), np.arcsin(-r[:, 2, 1]),
            np.arctan2(-r[:, 2, 0], r[:, 2, 2])]))
        np.testing.assert_allclose(extracted, angles, atol=1e-12)
        np.testing.assert_allclose(r @ r.transpose(0, 2, 1), np.broadcast_to(np.eye(3), r.shape), atol=1e-14)
        np.testing.assert_allclose(np.linalg.det(r), 1., atol=1e-14)

    def test_upright_phone_gravity_is_not_device_z(self):
        r = android_orientation_matrix([[0, -90, 0]])[0]
        device_gravity = r.T @ [0, 0, G0]
        np.testing.assert_allclose(device_gravity, [0, G0, 0], atol=1e-12)
        self.assertAlmostEqual(float(vector_angles(device_gravity, [0, 0, G0])), 90)
        np.testing.assert_allclose(r @ device_gravity, [0, 0, G0], atol=1e-12)

    def test_magnetic_world_geometry(self):
        r = android_orientation_matrix([[55, -80, 145]])[0]
        # Magnetic north has no east component, but may have vertical dip.
        field_device = r.T @ [0, 25, -40]
        np.testing.assert_allclose(r @ field_device, [0, 25, -40], atol=1e-12)

    def test_bad_orientation_input_rejected(self):
        for x in ([1, 2, 3], [[0, np.nan, 0]], [[0, 0]]):
            with self.assertRaises(ValueError):
                android_orientation_matrix(x)

    def test_cardinal_reference_acceleration(self):
        # North-facing forward acceleration is north; left is west.
        result = to_navigation_xy([[2, 0], [0, 3], [2, 0], [0, 3]], [0, 0, 90, 90])
        np.testing.assert_allclose(result, [[0, 2], [-3, 0], [2, 0], [0, 3]], atol=1e-12)

    def test_stationary_orientation_has_zero_rate(self):
        r = android_orientation_matrix(np.tile([40, -80, 120], (30, 1)))
        np.testing.assert_allclose(orientation_rates(r, np.arange(30)*.1), 0, atol=1e-12)

    def test_signed_yaw_and_wrap_are_continuous(self):
        t = np.arange(100)*.1
        for rate in (.2, -.2):
            # Start near azimuth wrap; matrices have no 360-degree jump.
            azimuth = (179-np.degrees(rate*t)+180) % 360-180
            r = android_orientation_matrix(np.column_stack([azimuth, t*0, t*0]))
            expected = np.tile([0, 0, rate], (99, 1))
            np.testing.assert_allclose(orientation_rates(r, t), expected, atol=1e-12)

    def test_invalid_intervals_are_not_bridged(self):
        t = [0, .1, .1, .05, 2, np.nan, 2.1, 2.2]
        r = android_orientation_matrix(np.zeros((len(t), 3)))
        omega = orientation_rates(r, t)
        self.assertTrue(np.isnan(omega[1:6]).all())
        np.testing.assert_allclose(omega[[0, 6]], 0)

    def test_fit_cannot_see_validation_samples(self):
        t = np.linspace(0, 24, 600)
        x = np.column_stack([np.sin(t), np.cos(1.7*t), t*0])
        ref = (x @ rz(.4).T)[:, :2]
        g = np.tile([0, 0, G0], (600, 1))
        first = model_window(x, g, ref)
        self.assertTrue(first['accepted'])
        broken = ref.copy()
        broken[300:] *= -1
        second = model_window(x, g, broken)
        np.testing.assert_array_equal(first['matrix'], second['matrix'])
        self.assertFalse(second['accepted'])

    def test_reflected_acceleration_cannot_be_approved(self):
        t = np.linspace(0, 24, 600)
        x = np.column_stack([np.sin(t), np.cos(1.7*t), t*0])
        ref = x[:, :2]*[1, -1]
        result = model_window(x, np.tile([0, 0, G0], (600, 1)), ref)
        self.assertAlmostEqual(np.linalg.det(result['matrix']), 1)
        self.assertFalse(result['accepted'])

    def test_constant_reference_not_a_healthy_zero(self):
        m = metrics(np.zeros(100), np.zeros(100))
        self.assertFalse(acceleration_gate([m, m], 1))


if __name__ == '__main__':
    unittest.main()
