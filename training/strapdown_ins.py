"""Clean classical strapdown INS baseline.

This is the *evaluation* baseline for GNSS-denied dead reckoning: a transparent
mechanization that propagates the vehicle state from calibrated inertial
measurements alone. It is deliberately not an estimator — it has no GNSS, no
map, no Kalman filter and no learning — and it is deliberately not a rewrite of
the historical Phase-1 script.

What the historical script (`training/ins_mechanization.py`) did, and what this
module does instead
--------------------------------------------------------------------------
The Phase-1 script is kept for its own report, but four of its assumptions are
not reusable, and none of them are inherited here:

1. **It used a nominal 10 Hz sample index as the mechanization clock** and
   treated the two long DATE gaps as anomalies in the *recording* rather than
   periods with no measurements. This module integrates over exact nanosecond
   timestamps, never a nominal rate, and refuses to invent motion across an
   interval nothing was measured in.
2. **It mapped the CSV gyro columns to "body X = Roll, Y = Pitch, Z = Yaw".**
   That is an assumed axis convention. Here the body frame *is* the Android
   device frame that the frozen contract already declares, and the device-to-
   vehicle rotation arrives as a calibration product instead of being guessed.
   Nothing in this module reads a dataset-specific column or axis label.
3. **It subtracted the dataset's GRAVITY X/Y/Z channel from the accelerometer**
   and called the result linear acceleration. Gravity is not a sensor reading to
   be subtracted; it is a model evaluated at the current latitude and altitude
   and rotated into the navigation frame by the attitude estimate. This module
   never consumes a vendor gravity sensor.
4. **It seeded the initial velocity from the VBOX row-0 speed and heading**, so
   an external reference entered the propagation. Here the initial state is an
   explicit input with its provenance attached, and no reference instrument or
   GNSS is read anywhere in this module.

Frames and conventions
----------------------
* **Device frame** — the Android sensor frame of the frozen contract: X right,
  Y up the screen, Z out of the screen. Right-handed. An accelerometer at rest
  measures the gravity *reaction*, so it reads approximately `+g` along the
  device axis that points up (this module's specific-force convention).
* **Navigation frame** — local ENU (East, North, Up), right-handed, anchored at
  the initial geodetic position. `p_enu` is the Cartesian displacement in the
  tangent plane at that anchor.
* **Attitude** — `q_nav_from_device`, `(w, x, y, z)`, rotating a device-frame
  vector into ENU. This is the direct analogue of the contract's
  `q_enu_from_vehicle_wxyz`; `q_enu_from_vehicle_wxyz` is recovered by composing
  it with the calibration's `q_vehicle_from_device` (see
  `InsResult.q_enu_from_vehicle_wxyz`).

Navigation equations actually integrated
----------------------------------------
    a_enu   = C(q) (f_device - b_a) + g_enu(lat, alt) - (2 w_ie + w_en) x v_enu
    v_enu  += dt/2 (a_enu(previous) + a_enu(current))        # trapezoid
    p_enu  += dt/2 (v_enu(previous) + v_enu(current))        # trapezoid
    q      += q (x) exp((w_ib - b_g) dt / 2), then de-rotated by exp(-w_in dt / 2)

`g_enu` is the local gravity vector, `w_ie` the Earth rate in ENU,
`w_en` the transport rate of the ENU frame, and `w_in = w_ie + w_en`. The
attitude update is the standard two-rotation form: the body rotates by the
measured inertial increment while the navigation frame rotates by its own rate,
so the two are composed rather than either being ignored.

Deliberately out of scope
-------------------------
No position or velocity aiding of any kind, no smoothing, no outlier rejection,
no scale-factor or non-orthogonality estimation, no coning/sculling
compensation, no barometric height, no magnetic heading, and no device-specific
axis remapping. Large errors are reported, never filtered away.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from enum import Enum
from typing import Iterable, Iterator, Sequence

import numpy as np

# --------------------------------------------------------------------------------------
# Physical constants
# --------------------------------------------------------------------------------------

#: WGS-84 Earth rotation rate (rad/s).
OMEGA_EARTH_RAD_S = 7.292115e-5
#: WGS-84 normal gravity at the equator (m/s^2).
WGS84_EQUATORIAL_GRAVITY_M_S2 = 9.7803253359
#: Somigliana's formula as published for WGS-84.
WGS84_POLAR_GRAVITY_M_S2 = 9.8321849378
#: Standard gravity, used only by the constant-gravity model option.
STANDARD_GRAVITY_M_S2 = 9.80665
#: WGS-84 ellipsoid semi-major axis (m) and flattening.
WGS84_SEMI_MAJOR_M = 6378137.0
WGS84_FLATTENING = 1.0 / 298.257223563
#: Degenerate geometry guard: a step shorter than this is treated as a duplicate sample.
MIN_STEP_S = 1e-9


class InsStatus(str, Enum):
    """Explicit propagation state. There is no fourth "probably fine" value."""

    UNINITIALIZED = "uninitialized"
    PROPAGATING = "propagating"
    #: The solution continued, but at least one interval had no measurements and the state was
    #: held across it. Every such interval is listed in `InsResult.gaps`, so the position beyond
    #: it carries a known, quantified amount of unobserved motion.
    DEGRADED = "degraded"
    #: Propagation stopped. No state beyond `InsResult.failed_at_ns` is claimed.
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class InsConfig:
    """Mechanization options. Every one of them changes the answer, so none is implicit.

    `gravity_model`:
        ``"wgs84"`` evaluates Somigliana normal gravity plus the free-air correction at the
        current geodetic latitude and altitude, leaving it a local-level vector ``(0, 0, -g)``.
        ``"constant"`` uses `constant_gravity_m_s2` everywhere, which is what the synthetic
        physics tests use so that a shared constant cannot hide an error.
    `earth_rotation`:
        include the Earth rate in the attitude update and the Coriolis term in the velocity
        equation. Disabling it is a diagnostic, not a default: a stationary 1-hour run drifts
        about 4.5 degrees of heading at Hyderabad's latitude without it.
    `transport_rate`:
        include the ENU frame's own rotation (`w_en`). It is tiny for a road vehicle; it is
        kept separate so its contribution can be measured rather than argued about.
    `max_step_s`:
        longest interval integrated normally. A longer one has no measurements in it and is
        held, not extrapolated.
    `failure_gap_s`:
        an interval longer than this ends the run. Beyond a few seconds of unmeasured motion a
        position claim would be fiction, so it is refused rather than stretched.
    `renormalize`:
        renormalize the attitude quaternion every step. Disabling it is only for the numerical
        stability test that shows why it is on.
    """

    gravity_model: str = "wgs84"
    constant_gravity_m_s2: float = STANDARD_GRAVITY_M_S2
    earth_rotation: bool = True
    transport_rate: bool = True
    max_step_s: float = 0.2
    failure_gap_s: float = 5.0
    renormalize: bool = True
    max_steps: int = 5_000_000


@dataclass(frozen=True, slots=True)
class InsCalibration:
    """The calibrated sensor errors the mechanization removes, in the device frame.

    `source` records where the numbers came from and is carried into every result, because a
    run with assumed-zero biases is a different statement from one with a validated
    calibration, and the report must be able to tell them apart.
    """

    gyro_bias_rad_s: np.ndarray
    accel_bias_m_s2: np.ndarray
    q_vehicle_from_device_wxyz: np.ndarray | None = None
    calibration_id: str | None = None
    source: str = "assumed-zero"

    @staticmethod
    def unknown() -> "InsCalibration":
        """Zero biases with an explicit provenance: nothing was calibrated."""
        return InsCalibration(np.zeros(3), np.zeros(3))

    @staticmethod
    def from_calibration_result(result) -> "InsCalibration":
        """Build from a frozen-contract `CalibrationResult` payload.

        A payload whose status is not ``valid`` carries no transform, so it cannot supply a
        mounting; its biases are still usable when present, and its provenance records the
        state it was really in. Nothing is upgraded here.
        """
        gyro = None if result.gyro_bias_rad_s is None else np.array(
            [result.gyro_bias_rad_s.x, result.gyro_bias_rad_s.y, result.gyro_bias_rad_s.z],
            dtype=float,
        )
        accel = None if result.accelerometer_bias_m_s2 is None else np.array(
            [result.accelerometer_bias_m_s2.x, result.accelerometer_bias_m_s2.y,
             result.accelerometer_bias_m_s2.z],
            dtype=float,
        )
        transform = None
        if result.q_vehicle_from_device_wxyz is not None:
            q = result.q_vehicle_from_device_wxyz
            transform = np.array([q.w, q.x, q.y, q.z], dtype=float)
        return InsCalibration(
            gyro_bias_rad_s=np.zeros(3) if gyro is None else gyro,
            accel_bias_m_s2=np.zeros(3) if accel is None else accel,
            q_vehicle_from_device_wxyz=transform,
            calibration_id=result.id,
            source=(
                f"calibration-record:{result.id}:{result.status.value}"
                + ("" if gyro is not None else ":gyro-bias-absent")
                + ("" if accel is not None else ":accel-bias-absent")
            ),
        )


@dataclass(frozen=True, slots=True)
class InsInitialState:
    """The explicit starting state. Its provenance is the caller's to document."""

    t_ns: int
    latitude_deg: float
    longitude_deg: float
    altitude_m: float
    velocity_enu_m_s: np.ndarray
    q_nav_from_device_wxyz: np.ndarray

    @staticmethod
    def create(
        t_ns: int,
        latitude_deg: float,
        longitude_deg: float,
        altitude_m: float,
        velocity_enu_m_s: Sequence[float],
        q_nav_from_device_wxyz: Sequence[float] = (1.0, 0.0, 0.0, 0.0),
    ) -> "InsInitialState":
        return InsInitialState(
            int(t_ns),
            float(latitude_deg),
            float(longitude_deg),
            float(altitude_m),
            np.asarray(velocity_enu_m_s, dtype=float).reshape(3),
            quat_normalize(np.asarray(q_nav_from_device_wxyz, dtype=float).reshape(4)),
        )

    @property
    def enu_anchor(self) -> tuple[float, float, float]:
        return self.latitude_deg, self.longitude_deg, self.altitude_m


@dataclass(frozen=True, slots=True)
class InsSample:
    """One paired, calibrated-ready inertial sample with its exact timestamp."""

    t_ns: int
    accel_m_s2: np.ndarray
    gyro_rad_s: np.ndarray


@dataclass(frozen=True, slots=True)
class InsGap:
    """An interval with no measurements, held rather than extrapolated."""

    after_t_ns: int
    resumed_t_ns: int
    duration_s: float


# --------------------------------------------------------------------------------------
# Quaternion and rotation helpers
# --------------------------------------------------------------------------------------


def quat_normalize(q: np.ndarray) -> np.ndarray:
    """Unit quaternion. Non-finite or degenerate input raises rather than being patched."""
    q = np.asarray(q, dtype=float).reshape(4)
    if not np.all(np.isfinite(q)):
        raise ValueError("non-finite quaternion")
    norm = float(np.linalg.norm(q))
    if norm < 1e-12:
        raise ValueError("degenerate quaternion")
    return q / norm


def quat_multiply(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Hamilton product: `a (x) b` applies `b` first, then `a`."""
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return np.array([
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    ])


def quat_conjugate(q: np.ndarray) -> np.ndarray:
    """Inverse of a unit quaternion."""
    return np.array([q[0], -q[1], -q[2], -q[3]])


def quat_from_rotvec(phi: np.ndarray) -> np.ndarray:
    """Exact quaternion of a rotation vector, `(w, x, y, z)`.

    The exact half-angle form is used rather than a truncated series, so a large step or a fast
    rotation is propagated correctly instead of being normalized back into shape.
    """
    phi = np.asarray(phi, dtype=float).reshape(3)
    angle = float(np.linalg.norm(phi))
    if angle < 1e-12:
        # Second-order-safe small-angle form: exp(phi/2) ~ (1, phi/2) + O(|phi|^3).
        half = phi / 2.0
        return quat_normalize(np.array([1.0, half[0], half[1], half[2]]))
    axis = phi / angle
    return np.array([
        math.cos(angle / 2.0),
        axis[0] * math.sin(angle / 2.0),
        axis[1] * math.sin(angle / 2.0),
        axis[2] * math.sin(angle / 2.0),
    ])


def quat_to_matrix(q: np.ndarray) -> np.ndarray:
    """Row-major 3x3 rotation matrix of a unit quaternion."""
    w, x, y, z = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def quat_angle_between(a: np.ndarray, b: np.ndarray) -> float:
    """Geodesic angle in radians, in `[0, pi]`. A rotation and its negation are identical."""
    dot = abs(float(np.dot(a, b)))
    return 2.0 * math.acos(min(1.0, max(-1.0, dot)))


def quat_rotate(q: np.ndarray, vector: Sequence[float]) -> np.ndarray:
    """Rotate a vector by a unit quaternion, `v' = q v q*` in matrix terms."""
    v = np.asarray(vector, dtype=float).reshape(3)
    rotation = quat_to_matrix(q)
    return rotation @ v


def quat_from_axis_angle(axis: Sequence[float], angle_rad: float) -> np.ndarray:
    """Quaternion of a rotation about an axis, which is normalized internally."""
    a = np.asarray(axis, dtype=float).reshape(3)
    norm = float(np.linalg.norm(a))
    if not math.isfinite(norm) or norm < 1e-12:
        raise ValueError("degenerate rotation axis")
    return quat_from_rotvec(a / norm * float(angle_rad))


def rotation_between_vectors(
    from_vector: Sequence[float], to_vector: Sequence[float],
) -> np.ndarray:
    """Shortest rotation taking one direction onto another.

    Handles the two degenerate cases explicitly, because both hide real bugs otherwise: a zero
    length input, and exactly opposite directions, where the axis is not determined by the cross
    product and any perpendicular axis is a valid answer.
    """
    a = np.asarray(from_vector, dtype=float).reshape(3)
    b = np.asarray(to_vector, dtype=float).reshape(3)
    na, nb = float(np.linalg.norm(a)), float(np.linalg.norm(b))
    if na < 1e-12 or nb < 1e-12:
        raise ValueError("rotation between vectors needs non-zero directions")
    a, b = a / na, b / nb
    axis = np.cross(a, b)
    axis_norm = float(np.linalg.norm(axis))
    dot = float(np.clip(np.dot(a, b), -1.0, 1.0))
    if axis_norm < 1e-12:
        if dot > 0.0:
            return identity_quaternion()
        # Antiparallel: a half turn about any axis perpendicular to `a`.
        helper = np.array([1.0, 0.0, 0.0])
        if abs(float(np.dot(a, helper))) > 0.9:
            helper = np.array([0.0, 1.0, 0.0])
        perpendicular = np.cross(a, helper)
        perpendicular = perpendicular / float(np.linalg.norm(perpendicular))
        return quat_from_axis_angle(perpendicular, math.pi)
    return quat_from_rotvec(axis / axis_norm * math.acos(dot))


# --------------------------------------------------------------------------------------
# Geodesy
# --------------------------------------------------------------------------------------


def wgs84_radii(latitude_rad: float) -> tuple[float, float]:
    """Prime-vertical (`R_E`) and meridional (`R_N`) radii of curvature."""
    e2 = WGS84_FLATTENING * (2 - WGS84_FLATTENING)
    sin_lat = math.sin(latitude_rad)
    denom = 1.0 - e2 * sin_lat * sin_lat
    r_e = WGS84_SEMI_MAJOR_M / math.sqrt(denom)
    r_n = WGS84_SEMI_MAJOR_M * (1 - e2) / (denom ** 1.5)
    return r_e, r_n


def transport_rate_enu(
    latitude_rad: float,
    altitude_m: float,
    velocity_enu: np.ndarray,
) -> np.ndarray:
    """Angular rate of the ENU frame relative to the Earth, expressed in ENU.

    Derived by differentiating the local triad rather than quoted. With the ENU basis built from
    `(latitude, longitude)` and `d(latitude)/dt = v_N / (R_N + h)`, `d(longitude)/dt =
    v_E / ((R_E + h) cos(latitude))`, the triad's own rotation is

        w_en = ( -v_N/(R_N+h),  +v_E/(R_E+h),  +v_E tan(latitude)/(R_E+h) )

    The sign *is* the content of the term. This frame rate adds to the Earth rate in the attitude
    update and, with it, forms the quadratic part of the Coriolis term in the velocity equation;
    negating it makes that part destabilising instead of stabilising, so the sign is checked
    against a finite-difference of the actual triad in `tests/test_strapdown_ins.py` rather than
    asserted here. The two radii differ by 0.6% at mid latitudes and both are carried.
    """
    v = np.asarray(velocity_enu, dtype=float).reshape(3)
    r_e, r_n = wgs84_radii(latitude_rad)
    height_e = r_e + altitude_m
    height_n = r_n + altitude_m
    return np.array([
        -v[1] / height_n,
        v[0] / height_e,
        v[0] * math.tan(latitude_rad) / height_e,
    ])


def normal_gravity_m_s2(latitude_rad: float, altitude_m: float = 0.0) -> float:
    """Somigliana normal gravity on the WGS-84 ellipsoid, with a free-air altitude term.

    ``gamma(phi) = gamma_e (1 + k sin^2 phi) / sqrt(1 - e^2 sin^2 phi)`` with
    ``k = b gamma_p / (a gamma_e) - 1``, which is the closed form that reproduces both published
    end values exactly. The frequently mis-transcribed alternative ``k = a omega^2 / gamma_e``
    is not the same quantity and is wrong by ~1.5e-2 m/s^2 at the pole, which is why the polar
    gravity is carried as an explicit constant here instead of being replaced by that ratio.
    """
    sin_lat = math.sin(latitude_rad)
    semi_minor_m = WGS84_SEMI_MAJOR_M * (1.0 - WGS84_FLATTENING)
    k = (
        semi_minor_m * WGS84_POLAR_GRAVITY_M_S2
        / (WGS84_SEMI_MAJOR_M * WGS84_EQUATORIAL_GRAVITY_M_S2)
    ) - 1.0
    e2 = WGS84_FLATTENING * (2 - WGS84_FLATTENING)
    num = 1 + k * sin_lat * sin_lat
    den = math.sqrt(1 - e2 * sin_lat * sin_lat)
    ellipsoid = WGS84_EQUATORIAL_GRAVITY_M_S2 * num / den
    # Free-air correction: gravity falls by ~3.086e-6 m/s^2 per metre above the ellipsoid.
    return ellipsoid - 3.086e-6 * altitude_m


# --------------------------------------------------------------------------------------
# The mechanization
# --------------------------------------------------------------------------------------


class StrapdownIns:
    """One strapdown mechanization. Feed it samples in time order; ask it for a result.

    The class owns all propagation state so that `reset` is meaningful and testable: after a
    reset the object must behave exactly like a fresh one, with no counters, gaps or status
    carried over.
    """

    def __init__(
        self,
        initial: InsInitialState,
        calibration: InsCalibration | None = None,
        config: InsConfig | None = None,
    ) -> None:
        self.config = config or InsConfig()
        if self.config.gravity_model not in ("wgs84", "constant"):
            raise ValueError(f"unknown gravity model: {self.config.gravity_model}")
        if self.config.max_step_s <= 0 or self.config.failure_gap_s < self.config.max_step_s:
            raise ValueError("max_step_s and failure_gap_s must be positive and ordered")
        self.reset(initial, calibration)

    # -- lifecycle ---------------------------------------------------------------------

    def reset(
        self,
        initial: InsInitialState,
        calibration: InsCalibration | None = None,
    ) -> None:
        """Reinitialize. Everything the previous run accumulated is discarded."""
        self.initial = initial
        self.calibration = calibration or InsCalibration.unknown()
        self.t_ns = int(initial.t_ns)
        self.q = quat_normalize(np.asarray(initial.q_nav_from_device_wxyz, dtype=float))
        self.v = np.asarray(initial.velocity_enu_m_s, dtype=float).reshape(3).copy()
        self.p = np.zeros(3)
        self.latitude_deg = float(initial.latitude_deg)
        self.longitude_deg = float(initial.longitude_deg)
        self.altitude_m = float(initial.altitude_m)
        self.a_enu = self._gravity_enu()
        self._have_previous_acceleration = False
        self.status = InsStatus.UNINITIALIZED
        self.reasons: list[str] = []
        self.gaps: list[InsGap] = []
        self.steps = 0
        self.held_steps = 0
        self.rejected_samples = 0
        self.failed_at_ns: int | None = None
        self.trace_t: list[int] = []
        self.trace_q: list[np.ndarray] = []
        self.trace_v: list[np.ndarray] = []
        self.trace_p: list[np.ndarray] = []
        self._sample_times: list[float] = []
        self._norm_errors: list[float] = []
        self._orthonormality_errors: list[float] = [self._orthonormality_error()]
        self._record()

    def _record(self) -> None:
        self.trace_t.append(self.t_ns)
        self.trace_q.append(self.q.copy())
        self.trace_v.append(self.v.copy())
        self.trace_p.append(self.p.copy())

    # -- geometry helpers --------------------------------------------------------------

    @property
    def latitude_rad(self) -> float:
        return math.radians(self.latitude_deg)

    def _gravity_enu(self) -> np.ndarray:
        """Local gravity vector in ENU. Points down; magnitude is the model's."""
        if self.config.gravity_model == "constant":
            magnitude = self.config.constant_gravity_m_s2
        else:
            magnitude = normal_gravity_m_s2(self.latitude_rad, self.altitude_m)
        return np.array([0.0, 0.0, -magnitude])

    def _earth_rate_enu(self) -> np.ndarray:
        """Earth rotation rate in ENU: north- and up-pointing components only."""
        if not self.config.earth_rotation:
            return np.zeros(3)
        return np.array([0.0, OMEGA_EARTH_RAD_S * math.cos(self.latitude_rad),
                         OMEGA_EARTH_RAD_S * math.sin(self.latitude_rad)])

    def _transport_rate_enu(self, v: np.ndarray) -> np.ndarray:
        """Angular rate of the ENU frame relative to the Earth, expressed in ENU."""
        if not self.config.transport_rate:
            return np.zeros(3)
        return transport_rate_enu(self.latitude_rad, self.altitude_m, v)

    def _acceleration(self, accel_device: np.ndarray, gyro_device: np.ndarray) -> np.ndarray:
        """`a_enu` from one calibrated specific-force / rate pair at the current state."""
        specific = accel_device - self.calibration.accel_bias_m_s2
        rotation = quat_to_matrix(self.q)
        gravity = self._gravity_enu()
        coriolis = 2.0 * self._earth_rate_enu() + self._transport_rate_enu(self.v)
        return rotation @ specific + gravity - np.cross(coriolis, self.v)

    def _orthonormality_error(self) -> float:
        """Largest deviation of `C(q) C(q)^T` from the identity: rotation-matrix health."""
        rotation = quat_to_matrix(self.q)
        product = rotation @ rotation.T
        return float(np.max(np.abs(product - np.eye(3))))

    # -- propagation -------------------------------------------------------------------

    def step(
        self,
        accel_m_s2: Sequence[float],
        gyro_rad_s: Sequence[float],
        t_ns: int,
    ) -> None:
        """Advance the state to `t_ns` using one sample.

        The interval is derived from the timestamps, never from a nominal rate. A non-monotonic
        timestamp is a failure, not something to be smoothed over: the input contract is an
        exact monotonic clock.
        """
        accel = np.asarray(accel_m_s2, dtype=float).reshape(3)
        gyro = np.asarray(gyro_rad_s, dtype=float).reshape(3)
        if not (np.all(np.isfinite(accel)) and np.all(np.isfinite(gyro))):
            self.rejected_samples += 1
            # Not skipped quietly: a non-finite sample means something upstream is broken, and a
            # propagation that carried on regardless would be hiding it.
            self._fail("NON_FINITE_SAMPLE")
            raise ValueError("non-finite inertial sample")

        dt = (int(t_ns) - self.t_ns) / 1e9
        if dt < 0.0:
            self._fail("NON_MONOTONIC_TIMESTAMP")
            raise ValueError(f"timestamp went backwards by {-dt * 1e3:.3f} ms")
        if dt < MIN_STEP_S:
            # A duplicate timestamp carries no new interval. It is not propagated and not
            # counted as a gap, because no time passed.
            self.rejected_samples += 1
            return

        if dt > self.config.failure_gap_s:
            self._fail("TIME_GAP_EXCEEDS_LIMIT", f"{dt:.3f} s > {self.config.failure_gap_s} s")
            raise ValueError(
                f"unmeasured interval of {dt:.3f} s exceeds failure_gap_s "
                f"({self.config.failure_gap_s} s); the run is marked failed and no state "
                "beyond this point is claimed"
            )
        if dt > self.config.max_step_s:
            # No measurements inside this interval, so there is nothing to integrate. The state
            # is held, the interval is recorded, and the run is flagged: the *unobserved* motion
            # becomes a known error rather than an invented trajectory.
            self.gaps.append(InsGap(self.t_ns, int(t_ns), dt))
            self.held_steps += 1
            if self.status != InsStatus.FAILED:
                self.status = InsStatus.DEGRADED
                if "TIME_GAP_HELD" not in self.reasons:
                    self.reasons.append("TIME_GAP_HELD")
            self.t_ns = int(t_ns)
            self._record()
            return

        if self.steps >= self.config.max_steps:
            self._fail("STEP_LIMIT")
            raise ValueError("step limit reached")

        gyro_bias_removed = gyro - self.calibration.gyro_bias_rad_s

        # Attitude: body increment first (measured, inertial), then the navigation frame's own
        # rotation, which is what keeps a stationary run from drifting at the Earth rate.
        body_increment = quat_from_rotvec(gyro_bias_removed * dt)
        nav_rate = self._earth_rate_enu() + self._transport_rate_enu(self.v)
        nav_increment = quat_from_rotvec(-nav_rate * dt)
        self.q = quat_multiply(
            quat_multiply(nav_increment, self.q),
            body_increment,
        )
        if self.config.renormalize:
            self.q = self.q / np.linalg.norm(self.q)

        # Velocity and position: trapezoid on the acceleration and on the velocity. The first
        # integrated step has no previous acceleration to average, so it takes a one-sided
        # update: a fabricated midpoint would inject a real error, and the error is larger than
        # the truncation error the trapezoid removes.
        current_acceleration = self._acceleration(accel, gyro)
        previous_velocity = self.v
        if self._have_previous_acceleration:
            delta_velocity = 0.5 * dt * (self.a_enu + current_acceleration)
        else:
            delta_velocity = dt * current_acceleration
            self._have_previous_acceleration = True
        self.v = previous_velocity + delta_velocity
        # The *step's* displacement, not the accumulated one: the geodetic update below is an
        # increment, and adding the running total each step turns a 7 km drive into hundreds of
        # degrees of latitude, which then poisons the gravity model and the Earth rate that are
        # evaluated at it. The altitude is recomputed from the total because it is an absolute
        # height, so the two forms coexist deliberately.
        delta_position = 0.5 * dt * (previous_velocity + self.v)
        self.p = self.p + delta_position
        self.a_enu = current_acceleration

        # Geodetic position, needed by the gravity model and the transport rate. Flat-earth
        # local-level update on the radii of curvature at the current latitude: exact to
        # sub-metre over a few kilometres, and disclosed as an approximation in the baseline
        # report. Latitude first, because the longitude increment needs the updated parallel.
        r_e, r_n = wgs84_radii(self.latitude_rad)
        self.latitude_deg += math.degrees(delta_position[1] / (r_n + self.altitude_m))
        self.longitude_deg += math.degrees(
            delta_position[0] / ((r_e + self.altitude_m) * math.cos(self.latitude_rad))
        )
        self.altitude_m = self.initial.altitude_m + self.p[2]

        self.t_ns = int(t_ns)
        self.steps += 1
        if self.status == InsStatus.UNINITIALIZED:
            self.status = InsStatus.PROPAGATING
        self._sample_times.append(dt)
        self._norm_errors.append(abs(float(np.linalg.norm(self.q)) - 1.0))
        self._orthonormality_errors.append(self._orthonormality_error())
        self._record()

    def _fail(self, reason: str, detail: str = "") -> None:
        self.status = InsStatus.FAILED
        self.failed_at_ns = self.t_ns
        self.reasons.append(reason if not detail else f"{reason}:{detail}")

    def run(self, samples: Iterable[InsSample]) -> "InsResult":
        """Consume samples in order. Stops at the first failure; the result says where."""
        for sample in samples:
            if self.status == InsStatus.FAILED:
                break
            if self.steps >= self.config.max_steps:
                self._fail("STEP_LIMIT")
                break
            try:
                self.step(sample.accel_m_s2, sample.gyro_rad_s, sample.t_ns)
            except ValueError:
                break
        return self.result()

    # -- reporting ---------------------------------------------------------------------

    def result(self) -> "InsResult":
        durations = np.asarray(self._sample_times, dtype=float)
        return InsResult(
            status=self.status,
            reasons=list(self.reasons),
            initial=self.initial,
            calibration=self.calibration,
            config=self.config,
            t_ns=np.asarray(self.trace_t, dtype=np.int64),
            q_nav_from_device=np.asarray(self.trace_q, dtype=float),
            velocity_enu_m_s=np.asarray(self.trace_v, dtype=float),
            position_enu_m=np.asarray(self.trace_p, dtype=float),
            latitude_deg=self.latitude_deg,
            longitude_deg=self.longitude_deg,
            altitude_m=self.altitude_m,
            steps=self.steps,
            held_steps=self.held_steps,
            rejected_samples=self.rejected_samples,
            gaps=list(self.gaps),
            step_durations_s=durations,
            attitude_norm_errors=np.asarray(self._norm_errors, dtype=float),
            orthonormality_errors=np.asarray(self._orthonormality_errors, dtype=float),
            failed_at_ns=self.failed_at_ns,
        )


@dataclass(frozen=True)
class InsResult:
    """The propagated trajectory plus everything needed to judge it.

    The per-step arrays are the state after each sample, starting with the initial state, so
    `len(t_ns) == steps + 1 + held_steps`. Nothing here is resampled or smoothed.
    """

    status: InsStatus
    reasons: list[str]
    initial: InsInitialState
    calibration: InsCalibration
    config: InsConfig
    t_ns: np.ndarray
    q_nav_from_device: np.ndarray
    velocity_enu_m_s: np.ndarray
    position_enu_m: np.ndarray
    latitude_deg: float
    longitude_deg: float
    altitude_m: float
    steps: int
    held_steps: int
    rejected_samples: int
    gaps: list[InsGap]
    step_durations_s: np.ndarray
    attitude_norm_errors: np.ndarray
    orthonormality_errors: np.ndarray
    failed_at_ns: int | None

    # -- convenience views -------------------------------------------------------------

    @property
    def duration_s(self) -> float:
        if self.t_ns.size < 2:
            return 0.0
        return float((self.t_ns[-1] - self.t_ns[0]) / 1e9)

    @property
    def final_position_enu_m(self) -> np.ndarray:
        return self.position_enu_m[-1]

    @property
    def final_velocity_enu_m_s(self) -> np.ndarray:
        return self.velocity_enu_m_s[-1]

    @property
    def final_q_nav_from_device(self) -> np.ndarray:
        return self.q_nav_from_device[-1]

    @property
    def horizontal_position_enu_m(self) -> np.ndarray:
        return self.position_enu_m[:, :2]

    @property
    def final_q_enu_from_vehicle_wxyz(self) -> np.ndarray | None:
        """The contract's `q_enu_from_vehicle_wxyz`, when a mounting was supplied."""
        mounting = self.calibration.q_vehicle_from_device_wxyz
        if mounting is None:
            return None
        return quat_multiply(self.final_q_nav_from_device, quat_conjugate(mounting))

    def horizontal_speed_series(self) -> np.ndarray:
        return np.linalg.norm(self.velocity_enu_m_s[:, :2], axis=1)

    def vertical_position_series(self) -> np.ndarray:
        return self.position_enu_m[:, 2]

    def maximum_attitude_drift_deg(self) -> float:
        """Largest attitude change from the initial attitude anywhere in the run."""
        reference = self.q_nav_from_device[0]
        return max(
            math.degrees(quat_angle_between(reference, q)) for q in self.q_nav_from_device
        )

    def step_duration_summary(self) -> dict:
        if self.step_durations_s.size == 0:
            return {"count": 0}
        d = self.step_durations_s
        return {
            "count": int(d.size),
            "minimum_s": float(d.min()),
            "median_s": float(np.median(d)),
            "maximum_s": float(d.max()),
            # A mechanization that assumed a fixed step would report a single value here.
            "distinct_values": int(np.unique(np.round(d, 9)).size),
            "assumed_fixed_step": bool(d.max() - d.min() < 1e-12),
        }


def propagate(
    samples: Iterable[InsSample],
    initial: InsInitialState,
    calibration: InsCalibration | None = None,
    config: InsConfig | None = None,
) -> InsResult:
    """One-shot propagation: the module-level entry point for a whole sample stream."""
    return StrapdownIns(initial, calibration, config).run(samples)


# --------------------------------------------------------------------------------------
# Adapters
# --------------------------------------------------------------------------------------

_SENSOR_ACCELEROMETER = "accelerometer"
_SENSOR_GYROSCOPE = "gyroscope"


def samples_from_records(
    records: Iterable,
    *,
    max_skew_ns: int = 30_000_000,
    require_android_device_frame: bool = True,
) -> Iterator[InsSample]:
    """Pair contract records into `InsSample`s, one pair per accelerometer sample.

    Only accelerometer and gyroscope payloads are consumed, in the Android device frame and in
    the contract's units. Anything else — including any GNSS payload — is ignored, which is how
    this module stays GNSS-free at runtime: the pairing function has no branch that could use a
    position fix even if one were present in the stream.

    Pairing consumes both samples, so a sample is used once. A pair further apart than
    `max_skew_ns` is dropped and the stale sample discarded, matching the on-device engine's
    behaviour rather than silently mixing two measurement instants.
    """
    pending_accel: tuple[int, np.ndarray] | None = None
    pending_gyro: tuple[int, np.ndarray] | None = None
    for record in records:
        data = record.event.data
        sensor = getattr(data, "sensor", None)
        if sensor is None:
            continue
        name = getattr(sensor, "value", sensor)
        if name not in (_SENSOR_ACCELEROMETER, _SENSOR_GYROSCOPE):
            continue
        if require_android_device_frame:
            frame = getattr(getattr(data, "frame", None), "value", None)
            if frame != "android_device":
                raise ValueError(f"unexpected inertial frame: {frame}")
        t_ns = int(record.event.t_ns)
        xyz = np.array([data.xyz.x, data.xyz.y, data.xyz.z], dtype=float)
        if name == _SENSOR_ACCELEROMETER:
            pending_accel = (t_ns, xyz)
        else:
            pending_gyro = (t_ns, xyz)
        if pending_accel is None or pending_gyro is None:
            continue
        skew = abs(pending_accel[0] - pending_gyro[0])
        if skew > max_skew_ns:
            # Drop the older of the two so the newer one can still find a partner.
            if pending_accel[0] < pending_gyro[0]:
                pending_accel = None
            else:
                pending_gyro = None
            continue
        sample = InsSample(
            t_ns=max(pending_accel[0], pending_gyro[0]),
            accel_m_s2=pending_accel[1],
            gyro_rad_s=pending_gyro[1],
        )
        pending_accel = None
        pending_gyro = None
        yield sample


def identity_quaternion() -> np.ndarray:
    return np.array([1.0, 0.0, 0.0, 0.0])


def initial_attitude_from_up_and_heading(
    up_device: Sequence[float],
    heading_rad: float = 0.0,
) -> np.ndarray:
    """`q_nav_from_device` from a measured device-frame up and a caller-chosen heading.

    Two of the three attitude degrees of freedom are measured: `up_device` is the device-frame
    direction that points up, so the rotation must map it onto ENU up exactly. The third is the
    heading, which the IMU cannot observe at rest, so the caller supplies it and the convention
    is stated: **the device +X axis ends up pointing at compass heading `heading_rad`**, where a
    compass heading is measured clockwise from north. Passing 0 therefore means "the device's
    +X axis faces north", which is a choice, not a measurement.

    This is an *initialization* helper, not a calibration. Any assumed vehicle mounting stays
    out of it: the calibration's `q_vehicle_from_device` is composed in afterwards by
    `InsResult.final_q_enu_from_vehicle_wxyz`, so an assumed mount can never masquerade as a
    measured attitude here.
    """
    up = np.asarray(up_device, dtype=float).reshape(3)
    norm = float(np.linalg.norm(up))
    if not math.isfinite(norm) or norm < 1e-9:
        raise ValueError("degenerate up direction")
    up = up / norm

    # Tilt: the shortest rotation taking the measured up onto ENU up.
    tilt = rotation_between_vectors(up, np.array([0.0, 0.0, 1.0]))

    # Heading: where the device +X axis currently points after the tilt, in the level plane.
    device_forward = quat_rotate(tilt, np.array([1.0, 0.0, 0.0]))
    level_east, level_north = float(device_forward[0]), float(device_forward[1])
    current_heading = math.atan2(level_east, level_north)
    # A positive rotation about ENU up takes east toward north, which *decreases* the compass
    # heading, so reaching `heading_rad` needs the difference in this direction.
    yaw = quat_from_axis_angle([0.0, 0.0, 1.0], current_heading - float(heading_rad))
    return quat_multiply(yaw, tilt)


def heading_of_device_axis(
    q_nav_from_device: np.ndarray, axis: Sequence[float] = (1.0, 0.0, 0.0),
) -> float:
    """Compass heading in radians of a device axis after the rotation, or nan if it is vertical."""
    direction = quat_rotate(q_nav_from_device, axis)
    horizontal = math.hypot(float(direction[0]), float(direction[1]))
    if horizontal < 1e-9:
        return float("nan")
    return math.atan2(float(direction[0]), float(direction[1]))


#: Identifiers that would betray a forbidden input if they ever appeared in this module.
_FORBIDDEN_IDENTIFIER = re.compile(r"(gnss|gps|vbox|open\b)", re.IGNORECASE)


def forbidden_identifiers() -> list[str]:
    """Names in this module that would mean a forbidden input had crept in.

    Exposed so the test suite can assert the runtime-input contract structurally instead of
    trusting a reading of the source: no identifier here may refer to a satellite fix, a
    reference instrument, or a file handle.
    """
    import ast
    from pathlib import Path

    tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
    found = set()
    for node in ast.walk(tree):
        candidates: list[str] = []
        if isinstance(node, ast.Name):
            candidates.append(node.id)
        elif isinstance(node, ast.Attribute):
            candidates.append(node.attr)
        elif isinstance(node, ast.arg):
            candidates.append(node.arg)
        elif isinstance(node, ast.alias):
            candidates.append(node.name.split(".")[-1])
            if node.asname:
                candidates.append(node.asname)
        for name in candidates:
            if _FORBIDDEN_IDENTIFIER.search(name):
                found.add(name)
    return sorted(found)
