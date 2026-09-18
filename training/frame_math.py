"""Diagnostic frame mathematics. Column vectors: v_target = C_target_source @ v_source.

Vehicle: forward/left/up. Navigation: east/north/up. No INS propagation lives here.
"""
from __future__ import annotations

from collections import deque
import numpy as np

if __package__:
    from .common import haversine
else:
    from common import haversine

G0 = 9.80665


def proper_rotation(matrix, atol=1e-7):
    c = np.asarray(matrix, float)
    if c.shape != (3, 3) or not np.isfinite(c).all():
        raise ValueError("A finite 3x3 rotation is required")
    if not np.allclose(c @ c.T, np.eye(3), atol=atol, rtol=0) or not np.isclose(np.linalg.det(c), 1, atol=atol):
        raise ValueError("Rotation must be orthogonal with determinant +1; reflections are invalid")
    return c


def rz(angle_rad):
    c, s = np.cos(angle_rad), np.sin(angle_rad)
    return np.array([[c, -s, 0.], [s, c, 0.], [0., 0., 1.]])


def heading_to_enu(heading_deg):
    """North/clockwise bearing -> east/counterclockwise ENU yaw, radians."""
    return (np.pi / 2 - np.radians(heading_deg) + np.pi) % (2 * np.pi) - np.pi


def level_rotation(gravity, forward_hint=(1., 0., 0.)):
    """Map Android's upward-at-rest gravity-like vector onto vehicle +Z.

    The hint must come from mounting knowledge; gravity cannot identify yaw.
    """
    up = np.asarray(gravity, float)
    if not np.isfinite(up).all() or np.linalg.norm(up) < 1:
        raise ValueError("Gravity vector is missing or too small")
    up = up / np.linalg.norm(up)
    forward = np.asarray(forward_hint, float)
    forward = forward - up * np.dot(up, forward)
    if not np.isfinite(forward).all() or np.linalg.norm(forward) < 1e-5:
        raise ValueError("Forward direction is parallel to gravity or invalid")
    forward /= np.linalg.norm(forward)
    return proper_rotation(np.stack([forward, np.cross(up, forward), up]))


def metrics(prediction, reference):
    """Regression is prediction = slope * reference + intercept; errors are unadjusted."""
    x, y = np.asarray(prediction, float), np.asarray(reference, float)
    mask = np.isfinite(x) & np.isfinite(y)
    x, y = x[mask], y[mask]
    result = dict(n=len(x), correlation=None, slope=None, intercept=None, rmse=None,
                  nrmse=None, reference_std=None, score=None)
    if len(x) < 20 or np.std(y) < 1e-5 or np.std(x) < 1e-8:
        return result
    scale = float(np.std(y))
    corr = float(np.corrcoef(x, y)[0, 1])
    slope = float(np.mean((x-x.mean())*(y-y.mean())) / np.var(y))
    intercept = float(x.mean() - slope*y.mean())
    error = float(np.sqrt(np.mean((x-y)**2)))
    nrmse = error / scale
    # Wrong-sign solutions lose on signed correlation, slope and unadjusted error.
    score = corr - .25*abs(slope-1) - .15*abs(intercept)/scale - .25*nrmse
    result.update(correlation=corr, slope=slope, intercept=intercept, rmse=error,
                  nrmse=nrmse, reference_std=scale, score=float(score))
    return result


def score(metric):
    return metric['score'] if metric['score'] is not None else -float('inf')


def lag_metrics(prediction, reference, max_lag=10):
    """Positive lag compares phone[i+lag] to reference[i]; diagnostic only.

    Keep the same central reference support for every candidate lag.
    """
    x, y = np.asarray(prediction, float), np.asarray(reference, float)
    n = min(len(x), len(y))
    if n <= 2*max_lag+20:
        return dict(lag_samples=0, metrics=metrics(x[:n], y[:n]))
    reference = y[max_lag:n-max_lag]
    results = [(lag, metrics(x[max_lag+lag:n-max_lag+lag], reference))
               for lag in range(-max_lag, max_lag+1)]
    lag, metric = max(results, key=lambda item: (score(item[1]), -abs(item[0]), -item[0]))
    return dict(lag_samples=lag, metrics=metric)


def gyro_candidates(channels, yaw_reference, speed=None, lateral_reference=None, max_lag=10):
    results = []
    for label, values in sorted(channels.items()):
        for sign in (1, -1):
            prediction = sign*np.asarray(values, float)
            zero = metrics(prediction, yaw_reference)
            physical = (metrics(np.asarray(speed)*prediction, lateral_reference)
                        if speed is not None and lateral_reference is not None else None)
            # Speed*yaw is a second, physical check; keep its component visible.
            joint = score(zero)
            if physical is not None and physical['score'] is not None:
                joint += .15*score(physical)
            results.append(dict(channel=label, sign=sign, zero_lag=zero,
                                best_lag=lag_metrics(prediction, yaw_reference, max_lag),
                                turn_consistency=physical,
                                ranking_score=joint if np.isfinite(joint) else None))
    return sorted(results, key=lambda r: (-(r['ranking_score'] if r['ranking_score'] is not None else -1e10), r['channel'], -r['sign']))


def unique_rotations(matrices, atol=1e-6):
    result = []
    for matrix in matrices:
        c = proper_rotation(matrix)
        if not any(np.allclose(c, other, atol=atol, rtol=0) for other in result):
            result.append(c)
    return result


def fit_mounting(linear_phone, gravity_mean, reference_xy):
    """One identifiable SO(2) mounting yaw after gravity leveling, not permutations.

    Fit centered vectors with a proper 2-D Procrustes rotation. Do not fit scale,
    reflect axes, or subtract the fitted intercept when reporting error.
    """
    up = np.asarray(gravity_mean, float)
    # Avoid switching the horizontal gauge by 90 degrees when tiny gravity X/Y
    # noise changes their ordering. Prefer raw +X unless it is near vertical.
    hint = (1., 0., 0.) if abs(up[0])/np.linalg.norm(up) < .9 else (0., 1., 0.)
    level = level_rotation(up, hint)
    xy = (np.asarray(linear_phone) @ level.T)[:, :2]
    reference = np.asarray(reference_xy)
    mask = np.isfinite(xy).all(axis=1) & np.isfinite(reference).all(axis=1)
    x, y = xy[mask], reference[mask]
    if len(x) < 50:
        raise ValueError("Insufficient finite acceleration samples")
    x, y = x-x.mean(axis=0), y-y.mean(axis=0)
    u, singular, vt = np.linalg.svd(x.T @ y)
    correction = np.diag([1., np.linalg.det(u @ vt)])
    row_rotation = u @ correction @ vt
    c2 = row_rotation.T
    horizontal = np.eye(3)
    horizontal[:2, :2] = c2
    total = proper_rotation(horizontal @ level)
    mapped = np.asarray(linear_phone) @ total.T
    return dict(matrix=total, yaw_rad=float(np.arctan2(c2[1, 0], c2[0, 0])),
                singular_values=singular,
                excitation_ratio=float(singular[-1]/singular[0]) if singular[0] else 0.,
                longitudinal=metrics(mapped[:, 0], reference[:, 0]),
                lateral=metrics(mapped[:, 1], reference[:, 1]))


def stationary_mask(speed, accel, gravity, gyro, dt=.1, minimum_seconds=2., linear_threshold=.3):
    """Conservative retrospective detector; no bias is inferred from moving windows."""
    valid = np.isfinite(speed) & (np.asarray(speed) >= 0) & (np.asarray(speed) < .3)
    if linear_threshold is not None:
        valid &= np.linalg.norm(np.asarray(accel)-gravity, axis=1) < linear_threshold
    valid &= np.linalg.norm(gyro, axis=1) < .05
    minimum = int(np.ceil(minimum_seconds/dt))
    result = np.zeros(len(valid), bool)
    edges = np.diff(np.r_[False, valid, False].astype(int))
    for start, stop in zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1)):
        if stop-start >= minimum:
            result[start:stop] = True
    return result


def bearing(latitude1, longitude1, latitude2, longitude2):
    p1, p2, dl = np.radians([latitude1, latitude2, longitude2-longitude1])
    return float(np.degrees(np.arctan2(np.sin(dl)*np.cos(p2),
                 np.cos(p1)*np.sin(p2)-np.sin(p1)*np.cos(p2)*np.cos(dl))) % 360)


class CausalCalibration:
    """Mathematical calibration prototype; never looks ahead or propagates INS.

    Inputs must be timestamped arrivals. An independently known phone-forward
    mounting direction is required; GNSS course alone cannot determine mounting.
    Confidence scores are engineering gates, not calibrated probabilities.
    """
    def __init__(self, phone_forward=None):
        self.phone_forward = phone_forward
        self.samples = deque()
        self.level = None
        self.level_time = None
        self.anchor = None
        self.last_fix_time = None
        self.latest_imu_time = None
        self.result = dict(state='waiting_for_level', level_confidence=0., yaw_confidence=0.)

    def add_imu(self, timestamp, accel, gravity, gyro):
        a, g, w = [np.asarray(x, float) for x in (accel, gravity, gyro)]
        if not np.isfinite(timestamp):
            raise ValueError("IMU timestamp must be finite")
        if self.latest_imu_time is not None and timestamp <= self.latest_imu_time:
            raise ValueError("IMU timestamps must increase")
        gap = self.latest_imu_time is not None and timestamp-self.latest_imu_time > .25
        self.latest_imu_time = timestamp
        quiet = np.isfinite(np.r_[a,g,w]).all() and 9.3 < np.linalg.norm(g) < 10.3
        quiet = quiet and np.linalg.norm(a-g) < .3 and np.linalg.norm(w) < .05
        if gap or not quiet:
            self.samples.clear()
            if gap:
                self.level = None
                self.anchor = None
                self.result = dict(state='waiting_for_level', level_confidence=0., yaw_confidence=0.)
            return self.result
        self.samples.append((timestamp,g))
        while self.samples and timestamp-self.samples[0][0] > 2.1:
            self.samples.popleft()
        if len(self.samples) >= 20 and timestamp-self.samples[0][0] >= 2.:
            gs = np.stack([x[1] for x in self.samples])
            if np.linalg.norm(gs.std(axis=0)) < .1:
                self.level_time = timestamp
                if self.phone_forward is None:
                    self.result = dict(state='mounting_unresolved', level_confidence=.9, yaw_confidence=0.)
                else:
                    self.level = level_rotation(gs.mean(axis=0), self.phone_forward)
                    self.result = dict(state='waiting_for_course', level_confidence=.9, yaw_confidence=0.)
        return self.result

    def add_fix(self, timestamp, latitude, longitude, speed, accuracy, *, forward_motion=False, straight_motion=False):
        if not np.isfinite(timestamp):
            raise ValueError("GNSS timestamp must be finite")
        if self.last_fix_time is not None and timestamp <= self.last_fix_time:
            raise ValueError("GNSS timestamps must increase")
        stale_gap = self.last_fix_time is not None and timestamp-self.last_fix_time > 3.
        self.last_fix_time = timestamp
        good = np.isfinite([timestamp,latitude,longitude,speed,accuracy]).all()
        good = good and abs(latitude)<=90 and abs(longitude)<=180 and 0<accuracy<=10 and speed>=3
        good = good and forward_motion and straight_motion and self.level is not None
        good = good and self.latest_imu_time is not None and -1e-9<=timestamp-self.latest_imu_time<=.25
        if not good or stale_gap:
            self.anchor = None
            self.result = dict(state='waiting_for_course' if self.level is not None else 'waiting_for_level',
                               level_confidence=.9 if self.level is not None else 0., yaw_confidence=0.)
            return self.result
        fix = (timestamp,latitude,longitude,accuracy)
        if self.anchor is None:
            self.anchor = fix
            return self.result
        t0, lat0, lon0, acc0 = self.anchor
        distance = float(haversine(lat0,lon0,latitude,longitude))
        threshold = max(15.,3*np.hypot(acc0,accuracy))
        if timestamp-t0 > 30:
            self.anchor = fix
            return self.result
        if distance >= threshold:
            course = bearing(lat0,lon0,latitude,longitude)
            c = proper_rotation(rz(heading_to_enu(course)) @ self.level)
            self.result = dict(state='ready_at_fix', available_at_s=timestamp, heading_deg=course,
                               C_enu_phone=c.tolist(), level_confidence=.9, yaw_confidence=.8,
                               course_uncertainty_rad=float(np.arctan2(np.hypot(acc0,accuracy),distance)))
        return self.result
