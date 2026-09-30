"""Synthetic physics verification for the strapdown INS baseline.

Every case here is closed-form: a trajectory is chosen, the inertial measurements that
*physics* would produce are synthesized from the equations of motion, and the propagated
result is compared with the analytic answer rather than with another estimate. The
generator and the mechanization share the standard-gravity constant and the frame-rate
formulas; neither is taken on trust. The gravity constant is asserted against published
WGS-84 values, and the Earth rate and transport rate are checked against closed forms and,
for the transport rate, against a finite difference of the ENU triad itself, so a sign or
convention error cannot hide behind the two implementations agreeing.

Cases, in the order the brief lists them: stationary, constant velocity, constant
acceleration, constant-rate left turn, constant-rate right turn, gyroscope bias,
accelerometer bias, irregular timestamps, missing interval, reset/reinitialize. Beyond
those: Earth-rate observability, numerical stability, the gravity model against published
WGS-84 values, the contract-record adapter (including that GNSS records cannot reach the
propagation), and the initial-attitude convention.
"""

from __future__ import annotations

import math
import unittest

import numpy as np

from contracts.v1.models import (
    AltitudeReference,
    DeviceFrame,
    Event,
    GnssMeasurement,
    Header,
    ImuMeasurement,
    ImuUnit,
    Quaternion,
    Record,
    Sensor,
    SensorAccuracy,
    Source,
    Vector3,
)
from training import strapdown_ins as ins
from training.frame_math import proper_rotation

G = 9.80665
DEG = math.pi / 180.0
NS = 1_000_000_000


def q_yaw_pitch_roll(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    """`Rz(yaw) Ry(pitch) Rx(roll)`, the composition the vehicle frame uses."""
    return ins.quat_multiply(
        ins.quat_multiply(
            ins.quat_from_axis_angle([0.0, 0.0, 1.0], yaw_deg * DEG),
            ins.quat_from_axis_angle([0.0, 1.0, 0.0], pitch_deg * DEG),
        ),
        ins.quat_from_axis_angle([1.0, 0.0, 0.0], roll_deg * DEG),
    )


class Rig:
    """Ground truth plus IMU synthesis, written from the equations of motion.

    `specific_force_enu` is the physical quantity the accelerometer measures expressed in ENU:
    the kinematic acceleration plus the Coriolis term minus local gravity. Gravity points down,
    so at rest the specific force points *up* with magnitude g — which is what a real
    accelerometer reports and what the on-device calibration engine relies on.
    """

    def __init__(
        self,
        *,
        gravity_m_s2: float = G,
        latitude_deg: float = 0.0,
        omega_earth: float = 0.0,
        transport_rate: bool = False,
        earth_radius_m: float = ins.WGS84_SEMI_MAJOR_M,
    ) -> None:
        self.gravity_m_s2 = gravity_m_s2
        self.latitude_rad = latitude_deg * DEG
        self.omega_earth = omega_earth
        self.transport_rate = transport_rate
        self.earth_radius_m = earth_radius_m

    def gravity_enu(self) -> np.ndarray:
        return np.array([0.0, 0.0, -self.gravity_m_s2])

    def earth_rate_enu(self) -> np.ndarray:
        if self.omega_earth == 0.0:
            return np.zeros(3)
        return np.array([
            0.0,
            self.omega_earth * math.cos(self.latitude_rad),
            self.omega_earth * math.sin(self.latitude_rad),
        ])

    def radii(self) -> tuple[float, float]:
        """Prime-vertical and meridional radii, from the ellipsoid definition, locally."""
        e2 = ins.WGS84_FLATTENING * (2.0 - ins.WGS84_FLATTENING)
        s = math.sin(self.latitude_rad)
        denominator = 1.0 - e2 * s * s
        return (
            ins.WGS84_SEMI_MAJOR_M / math.sqrt(denominator),
            ins.WGS84_SEMI_MAJOR_M * (1.0 - e2) / denominator ** 1.5,
        )

    def transport_rate_enu(self, velocity_enu: np.ndarray) -> np.ndarray:
        """Ground-truth frame rate, spelled out here so the module is not its own witness.

        This copy is *not* an independent derivation: it is the same closed form the mechanism
        uses, written out in the generator. What makes it trustworthy is that `TransportRateTests`
        checks that closed form against a finite-difference of the ENU triad itself, so a sign or
        radius error has to survive that check as well.
        """
        if not self.transport_rate:
            return np.zeros(3)
        velocity = np.asarray(velocity_enu, dtype=float)
        r_e, r_n = self.radii()
        return np.array([
            -velocity[1] / r_n,
            velocity[0] / r_e,
            velocity[0] * math.tan(self.latitude_rad) / r_e,
        ])

    def nav_rate_enu(self, velocity_enu: np.ndarray) -> np.ndarray:
        return self.earth_rate_enu() + self.transport_rate_enu(velocity_enu)

    def specific_force_enu(
        self, acceleration_enu: np.ndarray, velocity_enu: np.ndarray,
    ) -> np.ndarray:
        velocity = np.asarray(velocity_enu, dtype=float)
        frame_rate = 2.0 * self.earth_rate_enu() + self.transport_rate_enu(velocity)
        return (
            np.asarray(acceleration_enu, dtype=float)
            + np.cross(frame_rate, velocity)
            - self.gravity_enu()
        )

    def accel_device(
        self,
        q_nav_from_device: np.ndarray,
        acceleration_enu: np.ndarray,
        velocity_enu: np.ndarray | None = None,
        bias_device: np.ndarray | None = None,
    ) -> np.ndarray:
        velocity = np.zeros(3) if velocity_enu is None else velocity_enu
        force_enu = self.specific_force_enu(acceleration_enu, velocity)
        device = quat_to_matrix(q_nav_from_device).T @ force_enu
        return device if bias_device is None else device + bias_device

    def gyro_device(
        self,
        q_nav_from_device: np.ndarray,
        omega_nb_enu: np.ndarray | None = None,
        velocity_enu: np.ndarray | None = None,
        bias_device: np.ndarray | None = None,
    ) -> np.ndarray:
        velocity = np.zeros(3) if velocity_enu is None else velocity_enu
        relative = np.zeros(3) if omega_nb_enu is None else np.asarray(omega_nb_enu, dtype=float)
        total_enu = self.nav_rate_enu(velocity) + relative
        device = quat_to_matrix(q_nav_from_device).T @ total_enu
        return device if bias_device is None else device + bias_device


def quat_to_matrix(q: np.ndarray) -> np.ndarray:
    return ins.quat_to_matrix(q)


def samples_at(
    rig: Rig,
    t0_ns: int,
    step_durations_s,
    *,
    q: np.ndarray,
    acceleration_enu,
    velocity_enu=None,
    omega_nb_enu=None,
    accel_bias_device=None,
    gyro_bias_device=None,
) -> list[ins.InsSample]:
    """Synthesize one sample per requested interval, with exact nanosecond timestamps."""
    out: list[ins.InsSample] = []
    t = int(t0_ns)
    for duration in step_durations_s:
        t += int(round(duration * NS))
        out.append(ins.InsSample(
            t_ns=t,
            accel_m_s2=rig.accel_device(q, acceleration_enu, velocity_enu, accel_bias_device),
            gyro_rad_s=rig.gyro_device(q, omega_nb_enu, velocity_enu, gyro_bias_device),
        ))
    return out


def uniform_steps(duration_s: float, rate_hz: float) -> list[float]:
    count = int(round(duration_s * rate_hz))
    return [1.0 / rate_hz] * count


def flatInitial(rig: Rig, t0_ns: int = 0, velocity=(0.0, 0.0, 0.0), q=None) -> ins.InsInitialState:
    return ins.InsInitialState.create(
        t_ns=t0_ns,
        latitude_deg=0.0,
        longitude_deg=0.0,
        altitude_m=0.0,
        velocity_enu_m_s=velocity,
        q_nav_from_device_wxyz=ins.identity_quaternion() if q is None else q,
    )


def flat_config(**overrides) -> ins.InsConfig:
    """Flat, gravity-only mechanization: the generator shares only `G` with the module."""
    base = dict(
        gravity_model="constant",
        constant_gravity_m_s2=G,
        earth_rotation=False,
        transport_rate=False,
    )
    base.update(overrides)
    return ins.InsConfig(**base)


def assert_proper_rotation(case: unittest.TestCase, matrix) -> None:
    """Assert a matrix is a proper rotation, using the repository's own definition.

    `proper_rotation` raises on a reflection or a non-orthogonal matrix and *returns* the matrix,
    so it cannot be handed to `assertTrue`; wrapping it here keeps one definition of "proper
    rotation" in the tree rather than a second, weaker one written for the tests.
    """
    checked = proper_rotation(np.asarray(matrix, dtype=float))
    case.assertAlmostEqual(float(np.linalg.det(checked)), 1.0, places=12)


def wrapped_angle(angle_rad: float) -> float:
    """Wrap an angle into `(-pi, pi]`, so a heading change can be compared across north."""
    return (float(angle_rad) + math.pi) % (2.0 * math.pi) - math.pi


# ======================================================================================
# 1-3: the closed-form trajectory cases
# ======================================================================================


class TrajectoryTests(unittest.TestCase):
    def test_01_stationary_does_not_move_or_tilt(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(20.0, -7.0, 11.0)
        # A tilted phone in a stationary vehicle: the accelerometer reads the gravity reaction
        # tilted into device axes, and the mechanization must cancel it exactly.
        run = ins.propagate(
            samples_at(rig, 0, uniform_steps(600.0, 100.0), q=q0, acceleration_enu=[0.0, 0.0, 0.0]),
            flatInitial(rig, q=q0),
            config=flat_config(),
        )

        self.assertEqual(ins.InsStatus.PROPAGATING, run.status)
        self.assertEqual(0, run.held_steps)
        np.testing.assert_allclose(run.final_velocity_enu_m_s, [0.0, 0.0, 0.0], atol=1e-9)
        np.testing.assert_allclose(run.final_position_enu_m, [0.0, 0.0, 0.0], atol=1e-9)
        self.assertLess(run.maximum_attitude_drift_deg(), 1e-9)
        np.testing.assert_allclose(run.altitude_m, 0.0, atol=1e-9)

    def test_02_constant_velocity_tracks_exactly(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(-140.0, 6.0, -3.0)
        velocity = np.array([5.0, 2.0, 0.0])
        duration = 300.0
        run = ins.propagate(
            samples_at(
                rig, 0, uniform_steps(duration, 100.0),
                q=q0, acceleration_enu=[0.0, 0.0, 0.0], velocity_enu=velocity,
            ),
            flatInitial(rig, velocity=velocity, q=q0),
            config=flat_config(),
        )

        np.testing.assert_allclose(run.final_velocity_enu_m_s, velocity, atol=1e-9)
        np.testing.assert_allclose(run.final_position_enu_m, velocity * duration, atol=1e-6)
        self.assertAlmostEqual(run.duration_s, duration, places=6)

    def test_03_constant_acceleration_tracks_closed_form(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(35.0, 4.0, 2.0)
        acceleration = np.array([1.5, 0.0, 0.0])
        duration = 60.0
        run = ins.propagate(
            samples_at(
                rig, 0, uniform_steps(duration, 100.0),
                q=q0, acceleration_enu=acceleration, velocity_enu=acceleration,
            ),
            flatInitial(rig, q=q0),
            config=flat_config(),
        )

        np.testing.assert_allclose(run.final_velocity_enu_m_s, acceleration * duration, atol=1e-9)
        np.testing.assert_allclose(
            run.final_position_enu_m, 0.5 * acceleration * duration ** 2, atol=1e-6,
        )
        # Gravity is removed, not integrated into the vertical channel.
        self.assertLess(abs(run.final_position_enu_m[2]), 1e-9)

    def test_the_generator_itself_matches_physical_expectations(self):
        """The synthesized specific force must look like what a real accelerometer reports.

        Without this, agreement between the generator and the mechanization could be a shared
        mistake. The checks below are physical facts, not properties of either implementation.
        """
        rig = Rig()
        identity = ins.identity_quaternion()

        # At rest, ENU specific force is straight up with magnitude g.
        np.testing.assert_allclose(
            rig.specific_force_enu([0.0, 0.0, 0.0], [0.0, 0.0, 0.0]), [0.0, 0.0, G], atol=1e-12,
        )
        # Hard forward (north) acceleration tilts the specific force forward.
        np.testing.assert_allclose(
            rig.specific_force_enu([2.0, 0.0, 0.0], [0.0, 0.0, 0.0]), [2.0, 0.0, G], atol=1e-12,
        )
        # A brake (negative north acceleration) tilts it backwards.
        np.testing.assert_allclose(
            rig.specific_force_enu([-2.0, 0.0, 0.0], [0.0, 0.0, 0.0]), [-2.0, 0.0, G], atol=1e-12,
        )
        # A face-up phone lying flat reads the gravity reaction on its +Z axis, which is the
        # Android convention and the one the frozen contract records.
        np.testing.assert_allclose(
            rig.accel_device(identity, [0.0, 0.0, 0.0]), [0.0, 0.0, G], atol=1e-12,
        )
        # Face down, the same phone reads -Z.
        face_down = ins.quat_from_axis_angle([1.0, 0.0, 0.0], math.pi)
        np.testing.assert_allclose(
            rig.accel_device(face_down, [0.0, 0.0, 0.0]), [0.0, 0.0, -G], atol=1e-12,
        )
        # A stationary device turning with the Earth reports the Earth rate in device axes only
        # when the Earth rate is part of the ground truth.
        turning = Rig(latitude_deg=17.5, omega_earth=ins.OMEGA_EARTH_RAD_S)
        self.assertGreater(np.linalg.norm(turning.gyro_device(identity)), 1e-5)
        self.assertEqual(0.0, float(np.linalg.norm(rig.gyro_device(identity))))


# ======================================================================================
# 4-5: turns
# ======================================================================================


class TurnTests(unittest.TestCase):
    """A level turn at constant rate: the accelerometer's lateral channel must curve the path.

    Starting north at speed V and yawing at rate w about ENU up, the analytic solution is

        v(t) = V (-sin wt, cos wt, 0)
        p(t) = V/w (cos wt - 1, sin wt, 0)

    which curves to the *west* for w > 0. A left turn therefore ends with eastward position
    negative, and a sign error anywhere in the attitude or rotation composition shows up
    immediately as a path curving the wrong way.
    """

    def _turn_samples(
        self, rig: Rig, q0: np.ndarray, speed: float, rate: float, duration: float, rate_hz: float,
    ):
        samples = []
        interval = 1.0 / rate_hz
        t = 0
        count = int(round(duration * rate_hz))
        for index in range(count):
            t += int(round(interval * NS))
            elapsed = (index + 1) * interval
            angle = rate * elapsed
            yaw = ins.quat_from_axis_angle([0.0, 0.0, 1.0], angle)
            q = ins.quat_multiply(yaw, q0)
            velocity = speed * np.array([-math.sin(angle), math.cos(angle), 0.0])
            acceleration = speed * rate * np.array([-math.cos(angle), -math.sin(angle), 0.0])
            samples.append(ins.InsSample(
                t_ns=t,
                accel_m_s2=rig.accel_device(q, acceleration, velocity),
                gyro_rad_s=rig.gyro_device(q, omega_nb_enu=[0.0, 0.0, rate], velocity_enu=velocity),
            ))
        return samples

    def _closed_form(self, speed: float, rate: float, duration: float):
        angle = rate * duration
        velocity = speed * np.array([-math.sin(angle), math.cos(angle), 0.0])
        position = speed / rate * np.array([math.cos(angle) - 1.0, math.sin(angle), 0.0])
        return velocity, position

    def test_04_constant_rate_left_turn_curves_west(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(0.0, 3.0, 1.0)
        speed, rate, duration = 10.0, 0.1, 60.0
        velocity, position = self._closed_form(speed, rate, duration)
        # A positive rate about ENU up is a left turn from north, which is toward the west.
        self.assertLess(position[0], -1.0)

        run = ins.propagate(
            self._turn_samples(rig, q0, speed, rate, duration, 100.0),
            flatInitial(rig, velocity=[0.0, speed, 0.0], q=q0),
            config=flat_config(),
        )

        np.testing.assert_allclose(run.final_velocity_enu_m_s, velocity, atol=1e-3)
        np.testing.assert_allclose(run.final_position_enu_m, position, atol=1e-3)
        self.assertLess(abs(run.final_position_enu_m[2]), 1e-6)
        # The heading really did rotate by 0.1 rad/s * 60 s. A positive rate about ENU up is a
        # left turn, which *decreases* the compass heading: that sign is asserted, not assumed
        # away as a magnitude, because a reversed yaw sign is exactly the failure this catches.
        start_heading = ins.heading_of_device_axis(q0)
        end_heading = ins.heading_of_device_axis(run.final_q_nav_from_device)
        self.assertAlmostEqual(
            wrapped_angle(end_heading - start_heading), wrapped_angle(-rate * duration), places=9,
        )

    def test_05_constant_rate_right_turn_curves_east(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(0.0, -2.0, -4.0)
        speed, rate, duration = 12.0, -0.08, 45.0
        velocity, position = self._closed_form(speed, rate, duration)
        self.assertGreater(position[0], 1.0)

        run = ins.propagate(
            self._turn_samples(rig, q0, speed, rate, duration, 100.0),
            flatInitial(rig, velocity=[0.0, speed, 0.0], q=q0),
            config=flat_config(),
        )

        np.testing.assert_allclose(run.final_velocity_enu_m_s, velocity, atol=1e-3)
        np.testing.assert_allclose(run.final_position_enu_m, position, atol=1e-3)

    def test_trapezoid_error_converges_at_second_order(self):
        """Halving the step must quarter the integration error: this pins the scheme's order.

        A first-order scheme would only halve it, and a scheme that silently used a nominal
        rate would not converge at all.
        """
        rig = Rig()
        q0 = ins.identity_quaternion()
        speed, rate, duration = 10.0, 0.12, 30.0
        _, position = self._closed_form(speed, rate, duration)
        errors = []
        for rate_hz in (50.0, 100.0, 200.0):
            run = ins.propagate(
                self._turn_samples(rig, q0, speed, rate, duration, rate_hz),
                flatInitial(rig, velocity=[0.0, speed, 0.0], q=q0),
                config=flat_config(),
            )
            errors.append(float(np.linalg.norm(run.final_position_enu_m - position)))

        self.assertGreater(errors[0], errors[1])
        self.assertGreater(errors[1], errors[2])
        for coarse, fine in ((errors[0], errors[1]), (errors[1], errors[2])):
            ratio = coarse / fine
            self.assertGreater(ratio, 3.0, f"error ratio {ratio:.2f} is not second order")
            self.assertLess(ratio, 5.5, f"error ratio {ratio:.2f} is not second order")


# ======================================================================================
# 6-7: biases
# ======================================================================================


class BiasTests(unittest.TestCase):
    def test_06_gyro_bias_drifts_attitude_and_calibration_removes_it(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(0.0, 0.0, 0.0)
        bias = np.array([0.01, 0.004, -0.006])
        duration = 60.0
        steps = uniform_steps(duration, 100.0)
        samples = samples_at(
            rig, 0, steps, q=q0, acceleration_enu=[0.0, 0.0, 0.0], gyro_bias_device=bias,
        )

        uncalibrated = ins.propagate(samples, flatInitial(rig, q=q0), config=flat_config())
        expected_angle = float(np.linalg.norm(bias)) * duration
        measured = math.radians(uncalibrated.maximum_attitude_drift_deg())
        self.assertAlmostEqual(measured, expected_angle, delta=expected_angle * 1e-6)
        # The drift is about the *bias axis*, not merely of the right size: a transposed or
        # inverted composition would drift by the same angle about a different axis and every
        # attitude downstream would still be wrong. The error rotation is q_end (x) q_0^*.
        error_rotation = ins.quat_multiply(
            uncalibrated.final_q_nav_from_device, ins.quat_conjugate(q0),
        )
        np.testing.assert_allclose(
            error_rotation[1:] / float(np.linalg.norm(error_rotation[1:])),
            bias / float(np.linalg.norm(bias)),
            atol=1e-12,
        )

        # A bias along the device up axis is a *pure heading* error: the gravity reaction is
        # vertical, and a rotation about the vertical cannot tilt it into the horizontal, so the
        # position stays put exactly while the heading is wrong by the whole drift angle. This is
        # the sharpest available separation of a heading error from a leveling error.
        vertical_bias = np.array([0.0, 0.0, 0.005])
        heading_run = ins.propagate(
            samples_at(
                rig, 0, steps, q=q0, acceleration_enu=[0.0, 0.0, 0.0],
                gyro_bias_device=vertical_bias,
            ),
            flatInitial(rig, q=q0),
            config=flat_config(),
        )
        vertical_drift_deg = math.degrees(float(vertical_bias[2]) * duration)
        self.assertAlmostEqual(
            heading_run.maximum_attitude_drift_deg(), vertical_drift_deg, delta=1e-5,
        )
        self.assertAlmostEqual(
            math.degrees(wrapped_angle(
                ins.heading_of_device_axis(heading_run.final_q_nav_from_device)
                - ins.heading_of_device_axis(q0)
            )),
            -vertical_drift_deg,
            delta=1e-7,
        )
        self.assertLess(float(np.linalg.norm(heading_run.final_position_enu_m)), 1e-12)

        # A calibration carrying exactly that bias removes it, and the position error it caused
        # with it: this is the whole point of feeding a validated calibration in.
        calibrated = ins.propagate(
            samples,
            flatInitial(rig, q=q0),
            calibration=ins.InsCalibration(bias, np.zeros(3), source="test:true-bias"),
            config=flat_config(),
        )
        self.assertLess(calibrated.maximum_attitude_drift_deg(), 1e-9)
        self.assertLess(float(np.linalg.norm(calibrated.final_position_enu_m)), 1e-9)

        # And the uncalibrated error is not small: a 0.0125 rad/s bias tilts a stationary phone
        # by 43 degrees in a minute, which throws the gravity cancellation off by metres.
        self.assertGreater(float(np.linalg.norm(uncalibrated.final_position_enu_m)), 1.0)

    def test_07_accelerometer_bias_integrates_into_velocity_and_position(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(50.0, 5.0, -8.0)
        bias = np.array([0.04, -0.03, 0.02])
        duration = 30.0
        samples = samples_at(
            rig, 0, uniform_steps(duration, 100.0),
            q=q0, acceleration_enu=[0.0, 0.0, 0.0], accel_bias_device=bias,
        )

        # The device-frame bias is rotated into ENU by the attitude, then integrated.
        bias_enu = quat_to_matrix(q0) @ bias
        uncalibrated = ins.propagate(samples, flatInitial(rig, q=q0), config=flat_config())
        np.testing.assert_allclose(
            uncalibrated.final_velocity_enu_m_s, bias_enu * duration, atol=1e-9,
        )
        np.testing.assert_allclose(
            uncalibrated.final_position_enu_m, 0.5 * bias_enu * duration ** 2, atol=1e-9,
        )

        calibrated = ins.propagate(
            samples,
            flatInitial(rig, q=q0),
            calibration=ins.InsCalibration(np.zeros(3), bias, source="test:true-bias"),
            config=flat_config(),
        )
        np.testing.assert_allclose(calibrated.final_velocity_enu_m_s, [0.0, 0.0, 0.0], atol=1e-9)
        np.testing.assert_allclose(calibrated.final_position_enu_m, [0.0, 0.0, 0.0], atol=1e-9)


# ======================================================================================
# 8: timestamps
# ======================================================================================


class TimestampTests(unittest.TestCase):
    def _jittered_steps(self, count: int) -> list[float]:
        # No nominal rate: most steps are short and a tenth are five times longer, so the mean
        # and the median disagree and a fixed-step assumption cannot be right by accident.
        steps = []
        for index in range(count):
            base = 0.025 if index % 10 == 0 else 0.005
            jitter = ((index * 7919) % 11 - 5) * 1e-4
            steps.append(base + jitter)
        return steps

    def test_08_irregular_timestamps_are_integrated_without_a_nominal_rate(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(0.0, 0.0, 0.0)
        acceleration = np.array([1.5, 0.0, 0.0])
        steps = self._jittered_steps(12_000)
        true_duration = math.fsum(steps)
        samples = samples_at(
            rig, 0, steps, q=q0, acceleration_enu=acceleration, velocity_enu=acceleration,
        )

        run = ins.propagate(samples, flatInitial(rig, q=q0), config=flat_config())

        # Elapsed time is the sum of the real intervals, to the nanosecond the clock carried.
        self.assertAlmostEqual(run.duration_s, true_duration, places=9)
        np.testing.assert_allclose(
            run.final_velocity_enu_m_s, acceleration * true_duration, atol=1e-9,
        )
        np.testing.assert_allclose(
            run.final_position_enu_m, 0.5 * acceleration * true_duration ** 2, atol=1e-6,
        )

        summary = run.step_duration_summary()
        self.assertFalse(summary["assumed_fixed_step"])
        self.assertGreater(summary["distinct_values"], 5)
        self.assertAlmostEqual(summary["maximum_s"], max(steps), places=9)

        # Counterfactual: the same measurements relabelled onto a uniform 10 ms grid — what an
        # implementation that assumed a fixed step would see — is wrong by tens of m/s, because
        # its elapsed time is not the elapsed time.
        uniform = [
            ins.InsSample(t_ns=index * 10_000_000, accel_m_s2=s.accel_m_s2, gyro_rad_s=s.gyro_rad_s)
            for index, s in enumerate(samples, start=1)
        ]
        mislabelled = ins.propagate(
            uniform, flatInitial(rig, t0_ns=0, q=q0), config=flat_config(),
        )
        wrong = float(np.linalg.norm(
            mislabelled.final_velocity_enu_m_s - acceleration * true_duration,
        ))
        self.assertGreater(wrong, 20.0)

    def test_duplicate_and_backwards_timestamps_are_not_smoothed_over(self):
        rig = Rig()
        q0 = ins.identity_quaternion()
        samples = samples_at(
            rig, 0, uniform_steps(1.0, 100.0), q=q0, acceleration_enu=[0.0, 0.0, 0.0],
        )
        duplicate = ins.InsSample(samples[10].t_ns, samples[10].accel_m_s2, samples[10].gyro_rad_s)
        run = ins.propagate(
            samples[:10] + [duplicate] + samples[10:], flatInitial(rig, q=q0), config=flat_config(),
        )
        self.assertEqual(1, run.rejected_samples)
        self.assertEqual(ins.InsStatus.PROPAGATING, run.status)

        machine = ins.StrapdownIns(flatInitial(rig, q=q0), config=flat_config())
        with self.assertRaises(ValueError):
            # The machine starts at t_ns = 0, so only a negative stamp moves its clock backwards.
            machine.step(samples[0].accel_m_s2, samples[0].gyro_rad_s, -1)
        self.assertEqual(ins.InsStatus.FAILED, machine.status)
        self.assertIn("NON_MONOTONIC_TIMESTAMP", machine.result().reasons[0])


# ======================================================================================
# 9: missing intervals
# ======================================================================================


class GapTests(unittest.TestCase):
    def test_09_missing_interval_is_held_and_reported_never_extrapolated(self):
        rig = Rig()
        q0 = ins.identity_quaternion()
        velocity = np.array([8.0, 0.0, 0.0])

        def constant_velocity_steps(step_list):
            return samples_at(
                rig, 0, step_list, q=q0, acceleration_enu=[0.0, 0.0, 0.0], velocity_enu=velocity,
            )

        # (a) The vehicle is stationary relative to inertial space throughout: nothing was lost.
        stationary_steps = uniform_steps(5.0, 100.0) + [1.2] + uniform_steps(5.0, 100.0)
        run = ins.propagate(
            constant_velocity_steps(stationary_steps),
            flatInitial(rig, velocity=velocity, q=q0),
            config=flat_config(max_step_s=0.2, failure_gap_s=5.0),
        )
        self.assertEqual(ins.InsStatus.DEGRADED, run.status)
        self.assertIn("TIME_GAP_HELD", run.reasons)
        self.assertEqual(1, len(run.gaps))
        self.assertAlmostEqual(run.gaps[0].duration_s, 1.2, places=9)
        self.assertEqual(1, run.held_steps)
        # The state did not move through the gap and did not invent motion.
        self.assertAlmostEqual(
            float(run.gaps[0].after_t_ns + 1.2 * NS), float(run.gaps[0].resumed_t_ns), delta=1,
        )

        # (b) The vehicle really moves during the gap. The error is then exactly the unobserved
        # displacement: quantified and reported, not smoothed and not invented.
        moved = ins.propagate(
            constant_velocity_steps(stationary_steps),
            flatInitial(rig, velocity=velocity, q=q0),
            config=flat_config(max_step_s=0.2, failure_gap_s=5.0),
        )
        np.testing.assert_allclose(moved.final_velocity_enu_m_s, velocity, atol=1e-9)
        expected_missing = velocity * run.gaps[0].duration_s
        np.testing.assert_allclose(
            moved.final_position_enu_m, velocity * (10.0 + 1.2) - expected_missing, atol=1e-6,
        )
        self.assertAlmostEqual(
            float(np.linalg.norm(expected_missing)), 9.6, places=6,
        )

        # (c) A gap past the failure threshold ends the run: nothing beyond it is claimed.
        failing = ins.propagate(
            constant_velocity_steps(uniform_steps(1.0, 100.0) + [7.0] + uniform_steps(1.0, 100.0)),
            flatInitial(rig, velocity=velocity, q=q0),
            config=flat_config(max_step_s=0.2, failure_gap_s=5.0),
        )
        self.assertEqual(ins.InsStatus.FAILED, failing.status)
        self.assertTrue(failing.reasons[0].startswith("TIME_GAP_EXCEEDS_LIMIT"))
        self.assertEqual(100, failing.steps)
        self.assertEqual(101, len(failing.t_ns))
        self.assertEqual(failing.t_ns[-1], failing.failed_at_ns)

    def test_a_step_longer_than_nominal_but_inside_the_limit_is_integrated(self):
        rig = Rig()
        q0 = ins.identity_quaternion()
        acceleration = np.array([0.0, 2.0, 0.0])
        # 80 ms: four times the acquisition step, still a measured interval, so it propagates.
        run = ins.propagate(
            samples_at(rig, 0, [0.08] * 250, q=q0, acceleration_enu=acceleration),
            flatInitial(rig, q=q0),
            config=flat_config(max_step_s=0.2),
        )
        self.assertEqual(ins.InsStatus.PROPAGATING, run.status)
        np.testing.assert_allclose(run.final_velocity_enu_m_s, acceleration * 20.0, atol=1e-9)


# ======================================================================================
# 10: reset / reinitialize
# ======================================================================================


class ResetTests(unittest.TestCase):
    def test_10_reset_leaves_no_state_behind(self):
        rig = Rig()
        first_q = q_yaw_pitch_roll(0.0, 0.0, 0.0)
        second_q = q_yaw_pitch_roll(90.0, 10.0, -5.0)
        first = samples_at(
            rig, 0, uniform_steps(10.0, 100.0), q=first_q,
            acceleration_enu=[1.0, 0.0, 0.0], velocity_enu=[1.0, 0.0, 0.0],
        )
        start_ns = first[-1].t_ns + int(0.5 * NS)
        second = samples_at(
            rig, start_ns, uniform_steps(10.0, 100.0), q=second_q,
            acceleration_enu=[0.0, 0.0, 0.0], velocity_enu=[3.0, 1.0, 0.0],
        )
        second_initial = ins.InsInitialState.create(
            start_ns, 0.0, 0.0, 0.0, [3.0, 1.0, 0.0], second_q,
        )

        machine = ins.StrapdownIns(flatInitial(rig, q=first_q), config=flat_config())
        machine.run(first)
        self.assertGreater(machine.result().steps, 0)
        machine.reset(second_initial)
        after_reset = machine.run(second)

        fresh = ins.propagate(second, second_initial, config=flat_config())

        # Bit-exact: a reset is a new run, not a continuation with a different initial state.
        np.testing.assert_array_equal(after_reset.t_ns, fresh.t_ns)
        np.testing.assert_array_equal(after_reset.q_nav_from_device, fresh.q_nav_from_device)
        np.testing.assert_array_equal(after_reset.position_enu_m, fresh.position_enu_m)
        np.testing.assert_array_equal(after_reset.velocity_enu_m_s, fresh.velocity_enu_m_s)
        self.assertEqual(fresh.steps, after_reset.steps)
        self.assertEqual(fresh.held_steps, after_reset.held_steps)
        self.assertEqual(fresh.rejected_samples, after_reset.rejected_samples)
        self.assertEqual(fresh.gaps, after_reset.gaps)
        self.assertEqual(0, after_reset.steps - fresh.steps)

    def test_reset_clears_a_failed_status_and_its_reasons(self):
        rig = Rig()
        q0 = ins.identity_quaternion()
        machine = ins.StrapdownIns(flatInitial(rig, q=q0), config=flat_config(failure_gap_s=1.0))
        machine.run([
            ins.InsSample(10_000_000, np.array([0.0, 0.0, G]), np.zeros(3)),
            ins.InsSample(9_000_000_000, np.array([0.0, 0.0, G]), np.zeros(3)),
        ])
        failed = machine.result()
        self.assertEqual(ins.InsStatus.FAILED, failed.status)
        self.assertEqual(1, failed.steps)

        machine.reset(flatInitial(rig, q=q0))
        self.assertEqual(ins.InsStatus.UNINITIALIZED, machine.status)
        self.assertEqual([], machine.result().reasons)
        self.assertEqual([], machine.result().gaps)


# ======================================================================================
# Earth rate, stability, gravity, adapters, conventions
# ======================================================================================


def enu_triad(latitude_rad: float, longitude_rad: float) -> np.ndarray:
    """ECEF rotation matrix whose *columns* are the local East, North and Up axes.

    Constructed from the geodetic angles alone, with no ellipsoid in it: the directions of the
    local axes are a property of the sphere's angular coordinates, which is what makes the
    finite difference below an independent witness for the frame-rate formula.
    """
    sin_lat, cos_lat = math.sin(latitude_rad), math.cos(latitude_rad)
    sin_lon, cos_lon = math.sin(longitude_rad), math.cos(longitude_rad)
    east = np.array([-sin_lon, cos_lon, 0.0])
    north = np.array([-sin_lat * cos_lon, -sin_lat * sin_lon, cos_lat])
    up = np.array([cos_lat * cos_lon, cos_lat * sin_lon, sin_lat])
    return np.column_stack([east, north, up])


def rotation_vector(matrix: np.ndarray) -> np.ndarray:
    """Rotation vector of a small rotation matrix, via its skew part."""
    skew = 0.5 * (np.asarray(matrix, dtype=float) - np.asarray(matrix, dtype=float).T)
    return np.array([skew[2, 1], skew[0, 2], skew[1, 0]])


class TransportRateTests(unittest.TestCase):
    """The ENU frame's own rotation, checked against the geometry instead of a formula.

    The transport rate is small and easy to get wrong by a sign, and a sign error there is
    invisible to a stationarity test (v = 0 makes the term vanish) while making the velocity
    equation's quadratic part destabilising rather than stabilising. So it is measured here the
    only way that cannot share a mistake with the implementation: move the vehicle along the
    ellipsoid using the definition of the radii of curvature, build the ENU triad at both ends of
    the interval from the geodetic angles, and read the frame's rotation rate off the difference.
    """

    def _geometric_rate(self, latitude_deg: float, velocity_enu, dt: float = 1e-3):
        latitude = math.radians(latitude_deg)
        longitude = math.radians(77.0)
        rig = Rig(latitude_deg=latitude_deg)
        r_e, r_n = rig.radii()
        velocity = np.asarray(velocity_enu, dtype=float)
        before = enu_triad(latitude, longitude)
        after = enu_triad(
            latitude + velocity[1] / r_n * dt,
            longitude + velocity[0] / (r_e * math.cos(latitude)) * dt,
        )
        # w_en is the rate of the triad relative to the Earth, so it is the rate of change of the
        # triad expressed in the triad's own axes.
        # w_en is the rate of the triad relative to the Earth *expressed in the triad's own
        # axes*, so the difference has to be taken in the local frame rather than in ECEF:
        # `before.T @ after` puts the local axes on the left. Reading it the other way round
        # reports the same rotation in the wrong basis -- a rotation about up instead of about
        # north for eastward motion at the equator.
        return rotation_vector(before.T @ after) / dt

    def test_geodetic_position_stays_glued_to_the_tangent_plane(self):
        """The geodetic state is a function of the displacement, not an accumulator.

        A 10-minute run at 25 m/s drifts 15 km, which is 0.14 degrees of latitude. An update
        that added the *running total* of the displacement each step instead of the step's
        increment would leave the latitude hundreds of degrees away, and the gravity model and
        the Earth rate -- both evaluated at that latitude -- with it. Nothing else in this file
        asserts the geodetic state, which is why that failure mode is pinned here explicitly.
        """
        rig = Rig()
        q0 = q_yaw_pitch_roll(20.0, 0.0, 0.0)
        velocity = np.array([13.0, 21.0, -0.4])
        duration = 600.0
        latitude_deg, longitude_deg, altitude_m = 17.5, 78.3, 512.0
        initial = ins.InsInitialState.create(
            0, latitude_deg, longitude_deg, altitude_m, velocity, q0,
        )
        run = ins.propagate(
            samples_at(
                rig, 0, uniform_steps(duration, 100.0), q=q0,
                acceleration_enu=[0.0, 0.0, 0.0], velocity_enu=velocity,
            ),
            initial,
            config=flat_config(),
        )

        r_e, r_n = ins.wgs84_radii(math.radians(latitude_deg))
        expected_latitude = latitude_deg + math.degrees(velocity[1] * duration / (r_n + altitude_m))
        expected_longitude = longitude_deg + math.degrees(
            velocity[0] * duration / ((r_e + altitude_m) * math.cos(math.radians(latitude_deg)))
        )
        self.assertAlmostEqual(run.latitude_deg, expected_latitude, delta=1e-4)
        self.assertAlmostEqual(run.longitude_deg, expected_longitude, delta=1e-4)
        self.assertAlmostEqual(run.altitude_m, altitude_m + velocity[2] * duration, places=9)
        # The displacement itself is the tangent-plane integral, and the two views agree.
        np.testing.assert_allclose(run.final_position_enu_m, velocity * duration, atol=1e-6)

    def test_transport_rate_equals_the_measured_triad_rotation(self):
        for latitude_deg in (0.0, 17.5, 40.0, -56.0, 70.0):
            for velocity in (
                [8.0, 0.0, 0.0], [0.0, 27.0, 0.0], [-13.0, 21.0, 0.0], [3.0, -9.0, 0.0],
            ):
                expected = self._geometric_rate(latitude_deg, velocity)
                measured = ins.transport_rate_enu(
                    math.radians(latitude_deg), 0.0, np.asarray(velocity, dtype=float),
                )
                # atol is the finite difference's own O(dt^2) floor (~1e-15 rad/s), four orders
                # of magnitude below the term being measured.
                np.testing.assert_allclose(measured, expected, rtol=1e-6, atol=1e-13)
                # And the term is not accidentally zero, nor a pure east/north rotation: at
                # latitude 0 with eastward motion it is a rotation about the north axis.
                if latitude_deg == 0.0 and velocity == [8.0, 0.0, 0.0]:
                    self.assertGreater(abs(measured[1]), 0.0)
                    self.assertAlmostEqual(measured[0], 0.0, places=15)
                    self.assertAlmostEqual(measured[2], 0.0, places=15)

    def test_transport_rate_vanishes_when_asked_to_and_is_a_rate(self):
        rig = Rig(latitude_deg=17.5, transport_rate=False)
        np.testing.assert_array_equal(rig.transport_rate_enu([5.0, 5.0, 0.0]), np.zeros(3))
        machine = ins.StrapdownIns(flatInitial(rig), config=flat_config())
        np.testing.assert_array_equal(
            machine._transport_rate_enu(np.array([5.0, 5.0, 0.0])), np.zeros(3),
        )
        # Units: metres per second over a radius gives radians per second.
        rate = ins.transport_rate_enu(0.0, 0.0, np.array([100.0, 0.0, 0.0]))
        self.assertAlmostEqual(float(np.linalg.norm(rate)), 100.0 / 6_378_137.0, places=18)

    def test_a_fast_constant_velocity_trajectory_is_exact_with_every_term_on(self):
        """Earth rate and transport rate together must cancel cleanly on a constant velocity.

        23 m/s at 40 degrees of latitude: a sign error in either term leaves a residual of order
        1e-4 m/s^2, which is 30 mm/s of velocity after five minutes -- four orders of magnitude
        above this tolerance, so the test is discriminating rather than decorative.
        """
        latitude_deg = 40.0
        velocity = np.array([-13.0, 21.0, 0.0])
        duration = 300.0
        rate_hz = 100.0
        q0 = q_yaw_pitch_roll(0.0, 2.0, 1.0)
        # The generator follows the true latitude along the path, because the Earth rate it
        # synthesizes changes with it. A generator frozen at the starting latitude would leave a
        # residual an order of magnitude above this tolerance for reasons that belong to the
        # generator, not to the mechanism.
        _, r_n = Rig(latitude_deg=latitude_deg).radii()
        samples = []
        for index in range(int(round(duration * rate_hz))):
            elapsed = (index + 1) / rate_hz
            rig_now = Rig(
                latitude_deg=latitude_deg + math.degrees(velocity[1] * elapsed / r_n),
                omega_earth=ins.OMEGA_EARTH_RAD_S,
                transport_rate=True,
            )
            samples.append(ins.InsSample(
                t_ns=int(round(elapsed * NS)),
                accel_m_s2=rig_now.accel_device(q0, [0.0, 0.0, 0.0], velocity),
                gyro_rad_s=rig_now.gyro_device(
                    q0, omega_nb_enu=[0.0, 0.0, 0.0], velocity_enu=velocity,
                ),
            ))
        initial = ins.InsInitialState.create(0, latitude_deg, 77.0, 0.0, velocity, q0)
        run = ins.propagate(
            samples, initial,
            config=ins.InsConfig(
                gravity_model="constant", earth_rotation=True, transport_rate=True,
            ),
        )

        # The tolerances are the generator's half-step latitude offset (2e-8 m/s of vertical
        # residual), not slack: a sign error in either frame-rate term shows up as 1e-2 m/s or
        # more, five orders of magnitude above this.
        np.testing.assert_allclose(run.final_velocity_enu_m_s, velocity, atol=1e-7)
        np.testing.assert_allclose(run.final_position_enu_m, velocity * duration, atol=1e-4)
        # 2.4e-6 degrees of drift is the same half-step latitude offset; an unmodelled Earth rate
        # would be 1.25 degrees over this run.
        self.assertLess(run.maximum_attitude_drift_deg(), 1e-5)

        # Counterfactual: with the frame rotation switched off, the very same measurements no
        # longer describe a constant velocity, so the terms are load-bearing rather than inert.
        without = ins.propagate(
            samples, initial,
            config=ins.InsConfig(
                gravity_model="constant", earth_rotation=False, transport_rate=False,
            ),
        )
        self.assertGreater(
            float(np.linalg.norm(without.final_velocity_enu_m_s - velocity)), 1e-3,
        )


class EarthRateTests(unittest.TestCase):
    def test_earth_rate_is_modelled_and_omitting_it_is_visible(self):
        latitude = 17.5
        duration = 3600.0
        rate_hz = 5.0
        rig = Rig(latitude_deg=latitude, omega_earth=ins.OMEGA_EARTH_RAD_S)
        q0 = q_yaw_pitch_roll(0.0, 0.0, 0.0)
        # A perfectly stationary phone still reports the Earth rate, because the gyroscope
        # measures inertial rotation.
        samples = samples_at(
            rig, 0, uniform_steps(duration, rate_hz), q=q0, acceleration_enu=[0.0, 0.0, 0.0],
        )
        initial = ins.InsInitialState.create(0, latitude, 0.0, 0.0, [0.0, 0.0, 0.0], q0)

        modelled = ins.propagate(
            samples, initial,
            config=ins.InsConfig(
                gravity_model="constant", earth_rotation=True, transport_rate=True,
            ),
        )
        self.assertEqual(ins.InsStatus.PROPAGATING, modelled.status)
        self.assertLess(modelled.maximum_attitude_drift_deg(), 1e-6)
        self.assertLess(float(np.linalg.norm(modelled.final_position_enu_m)), 1e-3)

        omitted = ins.propagate(
            samples, initial,
            config=ins.InsConfig(
                gravity_model="constant", earth_rotation=False, transport_rate=False,
            ),
        )
        # Omitting it is not a rounding difference. With the term omitted, every step applies the
        # full inertial rate as a device-frame increment. The device is stationary, so those
        # increments share one axis and the estimated attitude is the true one composed with the
        # single rotation exp(C0^T w_ie tau); C0 is the identity here, so the ENU image of that
        # rotation is exactly a rotation about the Earth-rate axis by |w_ie| tau. The heading
        # change of the device X axis follows from Rodrigues and is computed below in closed form
        # rather than asserted to first order: the leading term is the vertical component
        # Omega sin(lat) tau = 4.52 deg, and the O(angle^2) coupling contributes the other 0.10.
        expected_total_deg = math.degrees(ins.OMEGA_EARTH_RAD_S * duration)
        self.assertAlmostEqual(omitted.maximum_attitude_drift_deg(), expected_total_deg, delta=0.05)
        axis = np.array([
            0.0, math.cos(math.radians(latitude)), math.sin(math.radians(latitude)),
        ])
        axis = axis / float(np.linalg.norm(axis))
        angle = ins.OMEGA_EARTH_RAD_S * duration
        device_x = np.array([1.0, 0.0, 0.0])
        drifted_x = (
            device_x * math.cos(angle)
            + np.cross(axis, device_x) * math.sin(angle)
            + axis * float(np.dot(axis, device_x)) * (1.0 - math.cos(angle))
        )
        self.assertAlmostEqual(
            ins.heading_of_device_axis(omitted.final_q_nav_from_device) / DEG,
            math.degrees(math.atan2(float(drifted_x[0]), float(drifted_x[1]))),
            delta=1e-6,
        )
        # And the position error that follows is metres, not microns.
        self.assertGreater(float(np.linalg.norm(omitted.final_position_enu_m)), 10.0)


class StabilityTests(unittest.TestCase):
    def test_quaternion_stays_unit_and_its_matrix_orthonormal(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(30.0, 12.0, -9.0)
        samples = samples_at(
            rig, 0, uniform_steps(600.0, 100.0), q=q0,
            acceleration_enu=[0.0, 0.0, 0.0], omega_nb_enu=[0.05, -0.02, 0.3],
        )
        with_normalization = ins.propagate(samples, flatInitial(rig, q=q0), config=flat_config())
        without = ins.propagate(
            samples, flatInitial(rig, q=q0), config=flat_config(renormalize=False),
        )

        self.assertLessEqual(float(with_normalization.attitude_norm_errors.max()), 1e-15)
        self.assertLessEqual(float(with_normalization.orthonormality_errors.max()), 1e-14)
        # Without renormalization the norm error can only grow; the flag is load-bearing.
        self.assertGreaterEqual(
            float(without.attitude_norm_errors.max()),
            float(with_normalization.attitude_norm_errors.max()),
        )
        self.assertGreaterEqual(without.attitude_norm_errors.max() > 0.0, True)

    def test_rotation_helpers_obey_their_invariants(self):
        q = q_yaw_pitch_roll(37.0, -21.0, 14.0)
        rotation = ins.quat_to_matrix(q)
        np.testing.assert_allclose(rotation @ rotation.T, np.eye(3), atol=1e-14)
        self.assertAlmostEqual(float(np.linalg.det(rotation)), 1.0, places=14)
        # The quaternion and the matrix are two spellings of one rotation.
        for vector in ([1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0], [0.3, -0.7, 0.4]):
            np.testing.assert_allclose(
                ins.quat_rotate(q, vector), rotation @ np.asarray(vector), atol=1e-15,
            )
        # Rotations preserve length and compose right-hand first.
        vector = np.array([0.3, -0.7, 0.4])
        self.assertAlmostEqual(
            float(np.linalg.norm(ins.quat_rotate(q, vector))),
            float(np.linalg.norm(vector)),
            places=15,
        )
        yaw = ins.quat_from_axis_angle([0.0, 0.0, 1.0], 0.7)
        composed = ins.quat_multiply(yaw, q)
        np.testing.assert_allclose(
            ins.quat_rotate(composed, vector),
            ins.quat_rotate(yaw, ins.quat_rotate(q, vector)),
            atol=1e-15,
        )
        # Inverse undoes it, and a rotation and its negation are the same rotation.
        np.testing.assert_allclose(
            ins.quat_rotate(ins.quat_conjugate(q), ins.quat_rotate(q, vector)), vector, atol=1e-15,
        )
        # A rotation and its negation are the same rotation: the matrices agree bit for bit. The
        # geodesic angle between them is bounded by `acos`'s own floor near 1 (~sqrt(2 eps) =
        # 1.5e-8 rad), not by any error in the composition, so the tolerance says so explicitly.
        np.testing.assert_allclose(ins.quat_to_matrix(q), ins.quat_to_matrix(-q), atol=1e-15)
        self.assertLess(ins.quat_angle_between(q, -q), 1e-7)
        self.assertAlmostEqual(
            float(np.linalg.norm(ins.quat_normalize([2.0, 0.0, 0.0, 0.0]))), 1.0, places=15,
        )
        for degenerate in (np.zeros(4), np.array([np.nan, 0.0, 0.0, 0.0])):
            with self.assertRaises(ValueError):
                ins.quat_normalize(degenerate)
        # The module's matrices are proper rotations by the repository's own definition.
        assert_proper_rotation(self, rotation)

    def test_rotation_between_vectors_handles_the_degenerate_cases(self):
        for a, b in (
            ([0.0, 0.0, 1.0], [0.0, 0.0, 1.0]),
            ([0.0, 0.0, 1.0], [0.0, 0.0, -1.0]),
            ([1.0, 2.0, -3.0], [-1.0, -2.0, 3.0]),
            ([1.0, 0.0, 0.0], [0.0, 1.0, 0.0]),
        ):
            rotation = ins.rotation_between_vectors(a, b)
            # A rotation preserves length, so the image of `a` is `b`'s direction at `a`'s length.
            # These cases are deliberately not all unit vectors; normalizing the expectation
            # would hide a rotation that also rescaled its input.
            expected = (
                np.asarray(b, dtype=float) / np.linalg.norm(b) * np.linalg.norm(a)
            )
            np.testing.assert_allclose(ins.quat_rotate(rotation, a), expected, atol=1e-14)
            # Including the antiparallel case, where a naive cross-product axis is degenerate and
            # a reflection would be the easy wrong answer.
            assert_proper_rotation(self, ins.quat_to_matrix(rotation))
        with self.assertRaises(ValueError):
            ins.rotation_between_vectors([0.0, 0.0, 0.0], [0.0, 0.0, 1.0])


class GravityModelTests(unittest.TestCase):
    def test_wgs84_normal_gravity_matches_published_values(self):
        # Published WGS-84 normal gravity: 9.7803253359 m/s^2 at the equator and
        # 9.8321849378 m/s^2 at the pole, with a free-air fall of ~0.3086 mGal per metre.
        self.assertAlmostEqual(ins.normal_gravity_m_s2(0.0), 9.7803253359, places=9)
        self.assertAlmostEqual(ins.normal_gravity_m_s2(math.pi / 2), 9.8321849378, places=9)
        # A mid latitude is cross-checked against the classical series expansion
        # `9.780327 (1 + 0.0053024 sin^2 - 0.0000058 sin^2 2phi)`, a different formula family that
        # shares none of Somigliana's constants, and against the tabulated value at 45 degrees.
        mid = math.radians(17.5)
        series = 9.780327 * (
            1 + 0.0053024 * math.sin(mid) ** 2 - 0.0000058 * math.sin(2 * mid) ** 2
        )
        self.assertAlmostEqual(ins.normal_gravity_m_s2(mid), series, delta=1e-5)
        self.assertAlmostEqual(ins.normal_gravity_m_s2(math.radians(45.0)), 9.806199, places=5)
        self.assertAlmostEqual(
            ins.normal_gravity_m_s2(0.0) - ins.normal_gravity_m_s2(0.0, 1000.0),
            3.086e-3, places=6,
        )
        # The two radii of curvature must agree at the pole and differ by the flattening.
        r_e, r_n = ins.wgs84_radii(math.pi / 2)
        self.assertAlmostEqual(r_e, r_n, places=6)
        r_e, r_n = ins.wgs84_radii(0.0)
        self.assertLess(r_n, r_e)

    def test_the_two_gravity_models_differ_by_the_expected_amount(self):
        # The ground truth must be the *same* quantity the model evaluates: normal gravity at
        # the initial latitude and altitude. Using the sea-level value against a 475 m anchor
        # leaves a 1.5e-3 m/s^2 vertical mismatch that integrates to 2.6 m in a minute.
        rig = Rig(gravity_m_s2=ins.normal_gravity_m_s2(math.radians(17.5), 475.0))
        q0 = ins.identity_quaternion()
        samples = samples_at(
            rig, 0, uniform_steps(60.0, 100.0), q=q0, acceleration_enu=[0.0, 0.0, 0.0],
        )
        initial = ins.InsInitialState.create(0, 17.5, 78.3, 475.0, [0.0, 0.0, 0.0], q0)

        matching = ins.propagate(
            samples, initial, config=ins.InsConfig(gravity_model="wgs84", earth_rotation=False),
        )
        # The modelled gravity is evaluated at the initial latitude and altitude, so a stationary
        # run with the WGS-84 model must stay put.
        self.assertLess(float(np.linalg.norm(matching.final_position_enu_m)), 1e-6)

        wrong = ins.propagate(
            samples, initial, config=ins.InsConfig(gravity_model="constant", earth_rotation=False),
        )
        # Using standard gravity away from the equator leaves a vertical error, and it doubles
        # with time: this is why the model is evaluated rather than assumed.
        self.assertGreater(abs(float(wrong.final_position_enu_m[2])), 0.1)


class RecordAdapterTests(unittest.TestCase):
    header = Header("synthetic", Source.REAL)

    def imu(self, event_id: int, sensor: Sensor, xyz, t_ns: int, unit: ImuUnit) -> Record:
        return Record(self.header, Event(
            str(event_id), t_ns, t_ns,
            ImuMeasurement(
                sensor, DeviceFrame.ANDROID_DEVICE, unit, Vector3(*xyz), SensorAccuracy.HIGH,
            ),
        ))

    def gnss(self, event_id: int, t_ns: int) -> Record:
        return Record(self.header, Event(
            str(event_id), t_ns, t_ns,
            GnssMeasurement(17.5, 78.3, 475.0, AltitudeReference.ELLIPSOID, 30.0, 90.0, 4.0,
                            3.0, 12, "gps", 1_790_000_000_000),
        ))

    def test_pairs_accelerometer_and_gyroscope_samples(self):
        records = [
            self.imu(1, Sensor.ACCELEROMETER, [0.0, 0.0, G], 10_000_000,
                     ImuUnit.METRES_PER_SECOND_SQUARED),
            self.imu(2, Sensor.GYROSCOPE, [0.01, 0.0, 0.0], 11_000_000,
                     ImuUnit.RADIANS_PER_SECOND),
            self.imu(3, Sensor.ACCELEROMETER, [0.1, 0.0, G], 20_000_000,
                     ImuUnit.METRES_PER_SECOND_SQUARED),
            self.imu(4, Sensor.GYROSCOPE, [0.02, 0.0, 0.0], 21_000_000,
                     ImuUnit.RADIANS_PER_SECOND),
        ]
        samples = list(ins.samples_from_records(records))
        self.assertEqual(2, len(samples))
        self.assertEqual(11_000_000, samples[0].t_ns)
        np.testing.assert_allclose(samples[0].accel_m_s2, [0.0, 0.0, G], atol=1e-15)
        np.testing.assert_allclose(samples[0].gyro_rad_s, [0.01, 0.0, 0.0], atol=1e-15)

    def test_gnss_records_cannot_reach_the_propagation(self):
        """The strongest available statement that no fix influences the trajectory.

        The same stream with and without GNSS payloads must produce the identical trajectory,
        and the module must not even name a satellite fix, a reference instrument or a file.
        """
        imu_only = []
        with_fixes = []
        for index in range(300):
            t_ns = (index + 1) * 10_000_000
            imu_only.append(self.imu(
                2 * index + 1, Sensor.ACCELEROMETER, [0.0, 0.0, G], t_ns,
                ImuUnit.METRES_PER_SECOND_SQUARED,
            ))
            imu_only.append(self.imu(
                2 * index + 2, Sensor.GYROSCOPE, [0.0, 0.0, 0.0], t_ns + 1_000,
                ImuUnit.RADIANS_PER_SECOND,
            ))
            with_fixes.extend(imu_only[-2:])
            if index % 10 == 0:
                with_fixes.append(self.gnss(1000 + index, t_ns))

        rig = Rig()
        initial = flatInitial(rig)
        clean = ins.propagate(ins.samples_from_records(imu_only), initial, config=flat_config())
        derived = ins.propagate(ins.samples_from_records(with_fixes), initial, config=flat_config())

        np.testing.assert_array_equal(clean.position_enu_m, derived.position_enu_m)
        np.testing.assert_array_equal(clean.velocity_enu_m_s, derived.velocity_enu_m_s)
        self.assertEqual([], ins.forbidden_identifiers())

    def test_unpaired_or_misframed_samples_do_not_slip_through(self):
        # A gyroscope sample 100 ms from its accelerometer partner is dropped, not mixed.
        records = [
            self.imu(1, Sensor.ACCELEROMETER, [0.0, 0.0, G], 10_000_000,
                     ImuUnit.METRES_PER_SECOND_SQUARED),
            self.imu(2, Sensor.GYROSCOPE, [0.0, 0.0, 0.0], 110_000_000,
                     ImuUnit.RADIANS_PER_SECOND),
        ]
        self.assertEqual([], list(ins.samples_from_records(records)))

        alien = Record(self.header, Event(
            "9", 10_000_000, 10_000_000,
            ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                           ImuUnit.RADIANS_PER_SECOND, Vector3(0.0, 0.0, G), SensorAccuracy.HIGH),
        ))
        # A wrong unit is not converted here: the contract carries units, and this layer trusts
        # the frozen validation rather than reinterpreting numbers.
        self.assertEqual([], list(ins.samples_from_records([alien])))


class InitialAttitudeTests(unittest.TestCase):
    def test_measured_up_is_mapped_to_up_and_heading_is_the_callers_choice(self):
        up_device = np.array([0.1, 0.2, 0.97])
        up_device = up_device / np.linalg.norm(up_device)
        for heading_deg in (0.0, 90.0, -135.0, 179.0):
            q = ins.initial_attitude_from_up_and_heading(up_device, heading_deg * DEG)
            # Two axes came from the measurement: the device up lands exactly on ENU up.
            np.testing.assert_allclose(ins.quat_rotate(q, up_device), [0.0, 0.0, 1.0], atol=1e-14)
            # The third is the caller's stated convention: device +X faces that compass heading.
            self.assertAlmostEqual(
                ins.heading_of_device_axis(q) / DEG, heading_deg, places=9,
            )
            assert_proper_rotation(self, ins.quat_to_matrix(q))
        with self.assertRaises(ValueError):
            ins.initial_attitude_from_up_and_heading([0.0, 0.0, 0.0])

    def test_vehicle_frame_is_recovered_only_from_a_supplied_mounting(self):
        rig = Rig()
        q0 = q_yaw_pitch_roll(10.0, 0.0, 0.0)
        mounting = q_yaw_pitch_roll(25.0, 8.0, -6.0)
        samples = samples_at(
            rig, 0, uniform_steps(2.0, 100.0), q=q0, acceleration_enu=[0.0, 0.0, 0.0],
        )

        without = ins.propagate(samples, flatInitial(rig, q=q0), config=flat_config())
        self.assertIsNone(without.final_q_enu_from_vehicle_wxyz)

        with_mount = ins.propagate(
            samples,
            flatInitial(rig, q=q0),
            calibration=ins.InsCalibration(np.zeros(3), np.zeros(3), mounting, source="test:mount"),
            config=flat_config(),
        )
        expected = ins.quat_multiply(q0, ins.quat_conjugate(mounting))
        np.testing.assert_allclose(with_mount.final_q_enu_from_vehicle_wxyz, expected, atol=1e-15)


class CalibrationProvenanceTests(unittest.TestCase):
    def test_a_result_record_carries_its_own_limits_into_the_run(self):
        from contracts.v1.models import CalibrationResult, CalibrationStatus

        valid = CalibrationResult(
            id="session-cal-1", status=CalibrationStatus.VALID,
            q_vehicle_from_device_wxyz=Quaternion(1.0, 0.0, 0.0, 0.0),
            gyro_bias_rad_s=Vector3(0.001, -0.002, 0.0005),
            accelerometer_bias_m_s2=None, confidence=0.5,
        )
        calibration = ins.InsCalibration.from_calibration_result(valid)
        np.testing.assert_allclose(calibration.gyro_bias_rad_s, [0.001, -0.002, 0.0005], atol=1e-15)
        # A payload without a justified accelerometer bias leaves that bias unknown, and the
        # source string says so rather than implying the sensor was perfect.
        np.testing.assert_allclose(calibration.accel_bias_m_s2, [0.0, 0.0, 0.0], atol=1e-15)
        self.assertIn("accel-bias-absent", calibration.source)
        self.assertIn("session-cal-1", calibration.source)
        self.assertIn("valid", calibration.source)

        pending = CalibrationResult(
            id="session-cal-2", status=CalibrationStatus.PENDING,
            q_vehicle_from_device_wxyz=None, gyro_bias_rad_s=None,
            accelerometer_bias_m_s2=None, confidence=None,
        )
        unknown = ins.InsCalibration.from_calibration_result(pending)
        self.assertIsNone(unknown.q_vehicle_from_device_wxyz)
        self.assertIn("pending", unknown.source)
        self.assertIn("gyro-bias-absent", unknown.source)

        assumed = ins.InsCalibration.unknown()
        self.assertEqual("assumed-zero", assumed.source)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
