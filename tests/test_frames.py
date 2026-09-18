"""Hand-computable physical and causal contracts, independent of real recordings."""
import unittest
import numpy as np

from training.frame_math import (CausalCalibration, G0, fit_mounting, gyro_candidates,
    heading_to_enu, lag_metrics, level_rotation, metrics, proper_rotation, rz,
    stationary_mask, unique_rotations)
from training.frame_audit import choose_windows, derivative
from training import diagnose_body_frame, diagnose_body_frame_v2
from training.ins_mechanization import estimate_initial_heading


class FrameTests(unittest.TestCase):
    def test_rotation_orthogonality_and_determinant(self):
        for angle in np.linspace(-np.pi,np.pi,13):
            c=proper_rotation(rz(angle))
            np.testing.assert_allclose(c @ c.T,np.eye(3),atol=1e-14)
            self.assertAlmostEqual(np.linalg.det(c),1.)

    def test_reflections_and_nonrotations_rejected(self):
        for matrix in (np.diag([1,1,-1]),np.diag([2,1,1]),np.full((3,3),np.nan)):
            with self.assertRaises(ValueError):
                proper_rotation(matrix)
        matrices=list(diagnose_body_frame.all_signed_permutations())
        self.assertEqual(len(matrices),24)
        for c,_,_ in matrices:
            proper_rotation(c)

    def test_known_90_degree_transforms(self):
        np.testing.assert_allclose(rz(np.pi/2) @ [1,0,0],[0,1,0],atol=1e-14)
        np.testing.assert_allclose(rz(-np.pi/2) @ [1,0,0],[0,-1,0],atol=1e-14)

    def test_heading_to_enu_cardinal_directions(self):
        for heading,vector in [(0,[0,1,0]),(90,[1,0,0]),(180,[0,-1,0]),(270,[-1,0,0])]:
            np.testing.assert_allclose(rz(heading_to_enu(heading)) @ [1,0,0],vector,atol=1e-14)

    def test_positive_left_and_negative_right_yaw(self):
        # Heading north to west is a left turn: ENU yaw increases by 90 degrees.
        yaw=np.unwrap(heading_to_enu([0,270]))
        self.assertAlmostEqual(yaw[1]-yaw[0],np.pi/2)
        yaw=np.unwrap(heading_to_enu([0,90]))
        self.assertAlmostEqual(yaw[1]-yaw[0],-np.pi/2)

    def test_gravity_alignment_and_zero_linear_acceleration(self):
        g=np.array([2.,3.,9.]);g*=G0/np.linalg.norm(g)
        c=level_rotation(g)
        np.testing.assert_allclose(c @ g,[0,0,G0],atol=1e-12)
        np.testing.assert_allclose(c @ (g-g),[0,0,0])
        with self.assertRaises(ValueError):
            level_rotation([0,0,G0],[0,0,1])

    def test_equal_absolute_correlation_selects_correct_sign(self):
        ref=.2*np.sin(np.linspace(0,20,500))
        ranked=gyro_candidates({'Pitch':-ref,'Roll':np.cos(np.linspace(0,20,500))*.01},ref,
                                np.full(500,10.),10*ref)
        self.assertEqual((ranked[0]['channel'],ranked[0]['sign']),('Pitch',-1))
        wrong=next(r for r in ranked if r['channel']=='Pitch' and r['sign']==1)
        self.assertAlmostEqual(abs(wrong['zero_lag']['correlation']),abs(ranked[0]['zero_lag']['correlation']))
        self.assertGreater(ranked[0]['ranking_score'],wrong['ranking_score'])

    def test_legacy_gyro_entry_uses_signed_metrics(self):
        ref=.2*np.sin(np.linspace(0,20,500))
        signals={'gyro':np.column_stack([.01*np.cos(np.arange(500)),ref,np.zeros(500)]),
                 'vbox_yaw_rate':np.degrees(ref),'vbox_speed':np.full(500,10.),'vbox_lat':10*ref}
        best=diagnose_body_frame_v2.search_gyro_yaw(signals)[0]
        self.assertEqual((best['label'],best['sign']),('GYROSCOPE Pitch',1))

    def test_scale_error_loses_despite_perfect_correlation(self):
        ref=.2*np.sin(np.linspace(0,20,500))
        ranked=gyro_candidates({'wrong_scale':10*ref,'correct':ref+.001},ref)
        self.assertEqual(ranked[0]['channel'],'correct')

    def test_insufficient_excitation_is_not_a_winner(self):
        self.assertIsNone(metrics(np.ones(100),np.ones(100))['score'])

    def test_lag_sign_and_no_automatic_application(self):
        rng=np.random.default_rng(42);ref=rng.normal(size=500)*.2
        delayed=np.r_[np.zeros(3),ref[:-3]]; original=delayed.copy()
        result=lag_metrics(delayed,ref)
        self.assertEqual(result['lag_samples'],3)
        np.testing.assert_array_equal(original,delayed)
        self.assertLess(metrics(delayed,ref)['correlation'],.3)
        self.assertAlmostEqual(result['metrics']['correlation'],1.)

    def test_fit_recovers_proper_mounting_and_collapses_equivalence(self):
        rng=np.random.default_rng(12)
        phone=np.column_stack([rng.normal(size=(500,2)),np.zeros(500)])
        expected=rz(np.pi/2);reference=(phone @ expected.T)[:,:2]
        result=fit_mounting(phone,[0,0,G0],reference)
        np.testing.assert_allclose(result['matrix'],expected,atol=1e-12)
        equivalents=[]
        for offset in (0,np.pi/2,np.pi,3*np.pi/2):
            m=rz(offset);fit=fit_mounting(phone @ m.T,[0,0,G0],reference)
            equivalents.append(fit['matrix'] @ m)
        self.assertEqual(len(unique_rotations(equivalents)),1)

    def test_mounting_basis_does_not_jump_for_tiny_gravity_noise(self):
        rng=np.random.default_rng(9);phone=np.column_stack([rng.normal(size=(500,2)),np.zeros(500)])
        reference=phone[:,:2]
        x=fit_mounting(phone,[.00001,.00002,G0],reference)
        y=fit_mounting(phone,[.00002,.00001,G0],reference)
        self.assertLess(abs(x['yaw_rad']-y['yaw_rad']),.001)

    def test_reflected_acceleration_data_is_not_silently_fitted_as_rotation(self):
        rng=np.random.default_rng(3)
        phone=np.column_stack([rng.normal(size=(500,2)),np.zeros(500)])
        reflected=phone[:,:2]*[1,-1]
        fit=fit_mounting(phone,[0,0,G0],reflected)
        self.assertAlmostEqual(np.linalg.det(fit['matrix']),1)
        self.assertGreater(fit['longitudinal']['rmse']+fit['lateral']['rmse'],1.)

    def test_stationary_sign_check_can_be_independent_of_acceleration(self):
        g=np.tile([0,0,G0],(100,1));a=-g;gyro=np.zeros((100,3))
        self.assertFalse(stationary_mask(np.zeros(100),a,g,gyro).any())
        self.assertTrue(stationary_mask(np.zeros(100),a,g,gyro,linear_threshold=None).all())

    def test_reference_derivative_and_window_reject_gaps(self):
        t=np.arange(100)*.1;y=-.2*t
        np.testing.assert_allclose(derivative(y,t)[10:-10],-.2)
        t[50:]+=2
        self.assertTrue(np.isnan(derivative(y,t)[50]))
        clock=np.arange(1000)*.1;clock[500:]+=2
        with self.assertRaises(ValueError):
            choose_windows(clock,clock,np.ones(1000),np.ones(1000)*10,60,0)


class CausalCalibrationTests(unittest.TestCase):
    def leveled(self,forward=(1,0,0)):
        c=CausalCalibration(phone_forward=forward)
        for t in np.arange(22)*.1:
            c.add_imu(float(t),[0,0,G0],[0,0,G0],[0,0,0])
        return c

    def test_stationary_phone_never_invents_yaw(self):
        c=self.leveled()
        c.add_fix(2.1,52,-1,0,3,forward_motion=True,straight_motion=True)
        self.assertEqual(c.result['yaw_confidence'],0)
        self.assertNotIn('C_enu_phone',c.result)

    def test_mounting_direction_is_not_observable_from_gravity(self):
        c=self.leveled(None)
        self.assertEqual(c.result['state'],'mounting_unresolved')
        self.assertIsNone(c.level)

    def test_solution_available_only_after_displacement_arrives(self):
        c=self.leveled()
        first=c.add_fix(2.1,52,-1,10,1,forward_motion=True,straight_motion=True).copy()
        self.assertNotIn('C_enu_phone',first)
        for t in np.arange(22,42)*.1:
            c.add_imu(float(t),[0,0,G0],[0,0,G0],[0,0,0])
        result=c.add_fix(4.1,52.0002,-1,10,1,forward_motion=True,straight_motion=True)
        self.assertEqual(result['state'],'ready_at_fix')
        self.assertEqual(result['available_at_s'],4.1)
        self.assertAlmostEqual(result['heading_deg'],0)
        proper_rotation(result['C_enu_phone'])

    def test_bad_gnss_and_unknown_reverse_or_curvature_rejected(self):
        c=self.leveled()
        for accuracy,forward,straight in [(50,True,True),(1,False,True),(1,True,False)]:
            c=CausalCalibration((1,0,0));c.level=np.eye(3);c.latest_imu_time=3
            result=c.add_fix(3,52,-1,10,accuracy,forward_motion=forward,straight_motion=straight)
            self.assertEqual(result['yaw_confidence'],0)

    def test_time_gap_invalidates_level_and_nonfinite_time_rejected(self):
        c=self.leveled()
        c.add_imu(5,[0,0,G0],[0,0,G0],[0,0,0])
        self.assertIsNone(c.level)
        with self.assertRaises(ValueError):
            c.add_imu(float('nan'),[0,0,G0],[0,0,G0],[0,0,0])

    def test_old_future_heading_requires_explicit_offline_permission(self):
        with self.assertRaisesRegex(ValueError,'Future GPS'):
            estimate_initial_heading(np.array([52,52.001]),np.array([-1,-1]))


if __name__=='__main__':
    unittest.main()
