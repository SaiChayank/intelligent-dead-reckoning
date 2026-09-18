"""Investigate export-frame hypotheses, not an INS or a calibration authority.

Default: read-only, one phone recording/pair at a time. --write-report creates
only frame_export_evidence.json. Existing Phase 0/frame evidence is not refreshed.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.spatial.transform import Rotation

from .common import PROJECT_ROOT, add_dataset_argument, resolve_dataset_root
from .frame_audit import choose_windows, finite_summary, smooth
from .frame_math import G0, fit_mounting, heading_to_enu, metrics, proper_rotation, stationary_mask
from .phase0.audit import canonical, read_frame, sha256, snapshot, time_array

ORIENTATION = ['orientation_yaw', 'orientation_pitch', 'orientation_roll']
GYRO = ['gyro_channel_yaw', 'gyro_channel_pitch', 'gyro_channel_roll']
MODELS = ('fixed_export_to_vehicle', 'export_is_world', 'world_export_inverted_to_device')


def diagnostic_view(phone):
    """Source-header correspondence hypothesis, NOT physical-axis approval."""
    aliases = {'orientation_azimuth': 'orientation_yaw', 'gyro_channel_x': 'gyro_channel_yaw',
               'gyro_channel_y': 'gyro_channel_pitch', 'gyro_channel_z': 'gyro_channel_roll'}
    result = phone.rename(columns=aliases)
    if result.columns.duplicated().any():
        raise ValueError('Ambiguous simultaneous XYZ and named gyro columns')
    return result


def compare_copies(left, right, atol=1e-12):
    """Check every row/field, including timestamps, before treating copies as one.

    Small decimal serialization differences are retained as measured maxima.
    No sequence is equated from a filename or similarity correlation alone.
    """
    a, b = diagnostic_view(left), diagnostic_view(right)
    result = dict(equivalent=False, absolute_tolerance=atol, columns={})
    if a.shape != b.shape or list(a.columns) != list(b.columns):
        result['reason'] = 'different dimensions or semantic header layout'
        return result
    okay = True
    for col in a:
        if pd.api.types.is_numeric_dtype(a[col]) and pd.api.types.is_numeric_dtype(b[col]):
            x, y = a[col].to_numpy(float), b[col].to_numpy(float)
            same = bool(np.allclose(x, y, rtol=0, atol=atol, equal_nan=True))
            finite = np.isfinite(x) & np.isfinite(y)
            maximum = float(np.max(abs(x[finite]-y[finite]))) if finite.any() else None
            result['columns'][col] = dict(equal_within_tolerance=same, maximum_absolute_difference=maximum)
        else:
            same = bool(a[col].fillna('<MISSING>').astype(str).str.strip().equals(
                b[col].fillna('<MISSING>').astype(str).str.strip()))
            result['columns'][col] = dict(exact_text_match=same)
        okay = okay and same
    result['equivalent'] = okay
    return result


def android_orientation_matrix(angles_deg):
    """Hypothesis: CSV angles implement Android SensorManager.getOrientation.

    Invert its documented extraction: az=atan2(R01,R11), p=asin(-R21),
    r=atan2(-R20,R22). R maps device to magnetic world, not true-north ENU.
    CSV compatibility is tested, never presumed merely from the labels.
    """
    a = np.asarray(angles_deg, float)
    if a.ndim != 2 or a.shape[1] != 3 or not np.isfinite(a).all():
        raise ValueError('Expected finite N x 3 azimuth/pitch/roll in degrees')
    return Rotation.from_euler('ZXY', np.column_stack([-a[:, 0], -a[:, 1], a[:, 2]]),
                               degrees=True).as_matrix()


def vector_angles(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    den = np.linalg.norm(a, axis=-1) * np.linalg.norm(b, axis=-1)
    with np.errstate(invalid='ignore', divide='ignore'):
        return np.degrees(np.arccos(np.clip(np.sum(a*b, axis=-1)/den, -1, 1)))


def to_navigation_xy(vehicle_xy, heading_deg):
    """Reference-only FLU -> true ENU; never used as a phone measurement."""
    v = np.asarray(vehicle_xy, float)
    psi = heading_to_enu(heading_deg)
    c, s = np.cos(psi), np.sin(psi)
    return np.column_stack([c*v[:, 0]-s*v[:, 1], s*v[:, 0]+c*v[:, 1]])


def orientation_rates(rotations, timestamps):
    """Offline adjacent SO(3) differences, device frame at interval start.

    No Euler-angle differentiation, interpolation, clock repair or lag fitting.
    Results from fused orientation are not independent gyro ground truth.
    """
    r, t = np.asarray(rotations, float), np.asarray(timestamps, float)
    delta = np.diff(t)
    good = np.isfinite(delta) & (delta > 0) & (delta <= .25)
    output = np.full((len(delta), 3), np.nan)
    relative = np.einsum('nji,njk->nik', r[:-1][good], r[1:][good])
    if good.any():
        output[good] = Rotation.from_matrix(relative).as_rotvec()/delta[good, None]
    return output


def sensor_summary(phone):
    phone = diagnostic_view(phone)
    result = dict(rows=len(phone))
    result['gyro_channel_values'] = {c: finite_summary(phone[c]) for c in GYRO if c in phone}
    gravity_columns = [f'gravity_{a}' for a in 'xyz']
    if all(c in phone for c in gravity_columns):
        g = phone[gravity_columns].to_numpy(float)
        tilt = vector_angles(g, np.broadcast_to([0, 0, 1.], g.shape))
        result.update(gravity_up_tilt_deg=finite_summary(tilt),
                      gravity_magnitude=finite_summary(np.linalg.norm(g, axis=1)))
    required = ORIENTATION + GYRO + [f'{s}_{a}' for s in ('accel', 'gravity', 'magnetic') for a in 'xyz']
    missing = [c for c in required if c not in phone]
    if missing:
        result.update(status='orientation compatibility unresolved: missing fields', missing_fields=missing)
        return result
    valid = np.isfinite(phone[required].to_numpy(float)).all(axis=1)
    # Do not bridge missing samples in the angular-rate consistency diagnostic.
    result['finite_joint_rows'] = int(valid.sum())
    if not valid.all():
        result['orientation_rate_check'] = 'skipped: missing joint sensor values'
    p = phone.loc[valid]
    if not len(p):
        result['status'] = 'no finite joint sensor rows'
        return result
    r = android_orientation_matrix(p[ORIENTATION].to_numpy(float))
    g = p[[f'gravity_{a}' for a in 'xyz']].to_numpy(float)
    mag = p[[f'magnetic_{a}' for a in 'xyz']].to_numpy(float)
    m_world = np.einsum('nij,nj->ni', r, mag)
    tilt = vector_angles(g, np.broadcast_to([0, 0, 1.], g.shape))
    mismatch = vector_angles(g, r[:, 2, :])
    norm = np.linalg.norm(mag, axis=1)
    with np.errstate(divide='ignore', invalid='ignore'):
        east_fraction = abs(m_world[:, 0])/norm
    result.update(gravity_up_tilt_deg=finite_summary(tilt),
                  gravity_vs_android_device_up_deg=finite_summary(mismatch),
                  magnetic_world_east_fraction=finite_summary(east_fraction),
                  magnetic_world_north_positive_fraction=float(np.mean(m_world[:, 1] > 0)),
                  gravity_magnitude=finite_summary(np.linalg.norm(g, axis=1)),
                  fraction_gravity_within_2deg_of_export_up=float(np.mean(tilt < 2)),
                  orientation_pitch_deg=finite_summary(p['orientation_pitch']),
                  orientation_roll_deg=finite_summary(p['orientation_roll']))
    # A description of observations, never a promoted frame transform.
    result['world_like_gravity_signature'] = bool(np.mean(tilt < 2) > .95 and
        np.median(mismatch) > 20 and np.nanmedian(east_fraction) < .05)
    if valid.all():
        omega = orientation_rates(r, time_array(phone, 'smartphone'))
        measured = phone[GYRO].to_numpy(float)
        measured = (measured[:-1]+measured[1:])/2
        result['orientation_rate_check'] = {
            'hypothesis': 'device XYZ = source Yaw,Pitch,Roll; not confirmed',
            'metrics': [metrics(measured[:, i], omega[:, i]) for i in range(3)]}
    return result


def acceleration_gate(items, excitation_ratio):
    """Predeclared two-axis gate; constant/weak references cannot pass."""
    return bool(excitation_ratio >= .1 and all(
        m['correlation'] is not None and m['reference_std'] >= .1 and
        m['correlation'] >= .7 and .7 <= m['slope'] <= 1.3 and m['nrmse'] <= .8
        for m in items))


def evaluate_rotation(matrix, values, reference):
    mapped = np.asarray(values) @ proper_rotation(matrix).T
    return [metrics(mapped[:, i], reference[:, i]) for i in range(2)]


def model_window(values, gravity, reference):
    mid = len(values)//2
    fit = fit_mounting(values[:mid], np.mean(gravity[:mid], axis=0), reference[:mid])
    train = [fit['longitudinal'], fit['lateral']]
    held = evaluate_rotation(fit['matrix'], values[mid:], reference[mid:])
    return dict(matrix=fit['matrix'].tolist(), excitation_ratio=fit['excitation_ratio'],
                calibration_rows=mid, validation_rows=len(values)-mid,
                calibration_metrics=train, validation_metrics=held,
                accepted=acceleration_gate(train+held, fit['excitation_ratio']))


def pair_models(phone, vbox):
    if len(phone) != len(vbox):
        return dict(status='skipped: unequal paired row counts', windows=[])
    needed = ORIENTATION+GYRO+[f'{s}_{a}' for s in ('accel', 'gravity') for a in 'xyz']
    if not np.isfinite(phone[needed].to_numpy(float)).all():
        return dict(status='skipped: nonfinite phone fields', windows=[])
    a = phone[[f'accel_{x}' for x in 'xyz']].to_numpy(float)
    g = phone[[f'gravity_{x}' for x in 'xyz']].to_numpy(float)
    r = android_orientation_matrix(phone[ORIENTATION].to_numpy(float))
    linear = a-g
    device_linear = np.einsum('nji,nj->ni', r, linear)
    device_gravity = np.einsum('nji,nj->ni', r, g)
    reference = vbox[['vehicle_accel_long', 'vehicle_accel_lat']].to_numpy(float)*G0
    heading = vbox['reference_heading'].to_numpy(float)
    world_reference = to_navigation_xy(reference, heading)
    yaw = np.radians(vbox['vehicle_yaw_rate'].to_numpy(float))
    speed = vbox['reference_speed'].to_numpy(float)/3.6
    st, vt = time_array(phone, 'smartphone'), time_array(vbox, 'vbox')
    stationary_speed = np.maximum(speed, phone['gps_speed_raw'].to_numpy(float))
    for clock in (st, vt):
        dt = np.diff(clock)
        stationary_speed[np.r_[False, (~np.isfinite(dt)) | (dt <= 0) | (dt > .25)]] = np.nan
    arrays = [(linear, g, reference), (linear, g, world_reference),
              (device_linear, device_gravity, reference)]
    windows, first = [], {}
    for start, end in choose_windows(st, vt, yaw, speed):
        if not np.isfinite(np.c_[reference[start:end], heading[start:end], yaw[start:end], speed[start:end]]).all():
            continue
        w = dict(start_row=int(start), end_row_exclusive=int(end),
                 start_s=float(st[start]), end_s=float(st[end-1]), models={})
        for name, (x, gravity, target) in zip(MODELS, arrays):
            try:
                fit = model_window(x[start:end], gravity[start:end], target[start:end])
            except ValueError as exc:
                w['models'][name] = dict(error=str(exc), accepted=False)
                continue
            if name not in first:
                first[name] = fit['matrix']
            fit['first_window_frozen_matrix_metrics'] = evaluate_rotation(
                first[name], x[start:end], target[start:end])
            fit['rotation_distance_from_first_deg'] = float(np.degrees(np.arccos(np.clip(
                (np.trace(np.array(fit['matrix']) @ np.array(first[name]).T)-1)/2, -1, 1))))
            w['models'][name] = fit
        # A separate sensitivity test: don't claim every possible preprocessing
        # fails just because unsmoothed samples fail. Both streams get the same
        # trailing 5-row filter, with 4 warm-up rows excluded; no best lag is used.
        filtered = smooth(device_linear[start:end])[4:]
        filtered_ref = smooth(reference[start:end])[4:]
        w['filtered_reconstruction'] = model_window(filtered, device_gravity[start+4:end], filtered_ref)
        prior = stationary_mask(stationary_speed[:start], a[:start], g[:start],
                                phone[GYRO].to_numpy(float)[:start], linear_threshold=None)
        if prior.any():
            residual = device_linear[:start][prior].mean(axis=0)
            sensitivity = model_window(filtered-residual, device_gravity[start+4:end], filtered_ref)
            sensitivity.update(prior_stationary_samples=int(prior.sum()),
                               subtracted_residual_device_m_s2=residual.tolist(),
                               note='Diagnostic sensitivity only, not a verified accelerometer bias or deployable calibration')
            w['filtered_prior_stationary_residual_sensitivity'] = sensitivity
        raw = phone[GYRO].to_numpy(float)[start:end]
        up = r[start:end, 2, :]
        w['yaw_hypotheses'] = {
            'positive_source_pitch': metrics(raw[:, 1], yaw[start:end]),
            'device_xyz_is_yaw_pitch_roll_projected_onto_up': metrics(np.sum(raw*up, axis=1), yaw[start:end])}
        windows.append(w)
    return dict(status='diagnostic only; no alignment fitted or applied', windows=windows)


def analyze(root):
    inventory = json.loads((PROJECT_ROOT/'reports/phase0_inventory.json').read_text(encoding='utf-8'))
    pairing = json.loads((PROJECT_ROOT/'reports/phase0_evidence.json').read_text(encoding='utf-8'))
    phones = [f for f in inventory['files'] if f['source'] == 'smartphone']
    pairs = {p['smartphone_file']: p for p in pairing['pairs'] if p['layout'] == 'categorized'}
    # Candidate copies share sequence identity, but must pass full value/time checks.
    groups = {}
    for f in phones:
        groups.setdefault(f['sequence'], []).append(f)
    records = []
    pending = list(groups.values())
    while pending:
        files = pending.pop(0)
        info = next((f for f in files if f['path'] in pairs), files[0])
        phone = read_frame(root/info['path'])
        copies, comparisons = [info], []
        for other in files:
            if other['path'] == info['path']:
                continue
            candidate = read_frame(root/other['path'])
            check = compare_copies(phone, candidate)
            check.update(file=other['path'], original_columns=candidate.columns.tolist())
            comparisons.append(check)
            if check['equivalent']:
                copies.append(other)
            else:
                pending.append([other])
        record = dict(sequence=info['sequence'], driver=info['driver'], file=info['path'],
                      payload_sha256=info['numeric_payload_sha256'],
                      original_columns=phone.columns.tolist(), copy_comparisons=comparisons,
                      copies=[dict(path=f['path'], sha256=f['sha256']) for f in copies],
                      schema_mapping_status=info['schema_mapping_status'], sensors=sensor_summary(phone))
        if info['path'] in pairs:
            pair = pairs[info['path']]
            record['vbox_file'] = pair['vbox_file']
            record['phase0_alignment'] = pair['alignment_class']
            record['paired_analysis'] = pair_models(phone, read_frame(root/pair['vbox_file']))
        records.append(record)
        print(f"{info['sequence']}: copies={len(copies)}, world-like={record['sensors'].get('world_like_gravity_signature')}, "
              f"windows={len(record.get('paired_analysis', {}).get('windows', []))}", flush=True)
    windows = [w for r in records for w in r.get('paired_analysis', {}).get('windows', [])]
    return dict(method='frame-export-audit-v1', source_phone_files=len(phones),
                recording_groups=len(records), copy_comparison_atol=1e-12, records=records,
                summary=dict(paired_recordings=sum('paired_analysis' in r for r in records),
                    evaluated_windows=len(windows),
                    world_like_gravity_recordings=sum(r['sensors'].get('world_like_gravity_signature') is True for r in records),
                    accepted_windows_by_model={m: sum(w['models'][m]['accepted'] for w in windows) for m in MODELS},
                    filtered_reconstruction_accepted_windows=sum(w['filtered_reconstruction']['accepted'] for w in windows),
                    prior_residual_sensitivity_accepted_windows=sum(w.get('filtered_prior_stationary_residual_sensitivity', {}).get('accepted', False) for w in windows)),
                approved_full_3d_calibrations=0,
                approval_note='No automatic approval: device/export contract and full gyro triad remain unverified.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_dataset_argument(parser)
    parser.add_argument('--write-report', action='store_true')
    parser.add_argument('--no-write', action='store_true')
    args = parser.parse_args()
    if args.write_report and args.no_write:
        parser.error('--write-report and --no-write are mutually exclusive')
    try:
        root = resolve_dataset_root(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))
    reviewed = json.loads((PROJECT_ROOT/'reports/phase0_raw_manifest.json').read_text(encoding='utf-8'))
    # Verify inventory assumptions even on read-only runs, without updating raw metadata.
    if snapshot(root) != reviewed:
        raise ValueError('Raw data differs from reviewed manifest; stop and review provenance')
    evidence = analyze(root)
    if snapshot(root) != reviewed:
        raise RuntimeError('Raw data changed during investigation; no output written')
    evidence['raw_manifest_sha256'] = sha256(PROJECT_ROOT/'reports/phase0_raw_manifest.json')
    evidence['source_sha256'] = {p: sha256(PROJECT_ROOT/p) for p in (
        'training/frame_export_audit.py', 'training/frame_math.py', 'training/frame_audit.py',
        'training/common.py', 'training/phase0/audit.py', 'training/phase0/schema.py',
        'training/phase0/statistics.py', 'reports/phase0_inventory.json', 'reports/phase0_evidence.json')}
    if args.write_report:
        (PROJECT_ROOT/'reports/frame_export_evidence.json').write_text(canonical(evidence), encoding='utf-8')
    print(f"Investigated {evidence['recording_groups']} recording groups; raw integrity verified.")
    return evidence


if __name__ == '__main__':
    main()
