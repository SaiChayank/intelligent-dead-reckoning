"""Reproducible physical-frame diagnostics; no INS propagation or data repair.

Run: python -m training.frame_audit --write-report
Default is read-only. Reports are separate from historical Phase 0 outputs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import pandas as pd

from .common import PROJECT_ROOT, add_dataset_argument, resolve_dataset_root
from .phase0.audit import canonical, read_frame, snapshot, time_array
from .frame_math import G0, fit_mounting, gyro_candidates, lag_metrics, metrics, score, stationary_mask

DEFAULT_SEQUENCES = ('m','s1','s2','s3c','vta2','vw11')


def finite_summary(values):
    a = np.asarray(values, float)
    a = a[np.isfinite(a)]
    if not len(a):
        return dict(n=0, mean=None, median=None, std=None, p95=None, maximum=None)
    return dict(n=len(a), mean=float(a.mean()), median=float(np.median(a)), std=float(a.std()),
                p95=float(np.percentile(a,95)), maximum=float(a.max()))


def derivative(values, time, span=10):
    """Centered span derivative for offline convention checks, never initialization."""
    x, t = np.asarray(values,float), np.asarray(time,float)
    result = np.full(len(t),np.nan)
    bad = (~np.isfinite(np.diff(t))) | (np.diff(t)<=0) | (np.diff(t)>.25)
    count = np.r_[0,np.cumsum(bad)]
    idx = np.arange(span,len(t)-span)
    ok = (count[idx+span]-count[idx-span])==0
    k = idx[ok]
    result[k] = (x[k+span]-x[k-span])/(t[k+span]-t[k-span])
    return result


def smooth(values, width=5):
    return pd.DataFrame(values).rolling(width,min_periods=width).mean().to_numpy()


def changed_fraction(values):
    a = np.asarray(values,float)
    if a.ndim==1:
        a=a[:,None]
    finite=np.isfinite(a[:-1]).all(axis=1)&np.isfinite(a[1:]).all(axis=1)
    return float(np.mean(np.any(a[1:][finite]!=a[:-1][finite],axis=1))) if finite.any() else None


def choose_windows(s_time,v_time,yaw,speed,duration=120,offset=None):
    length=int(duration/.1)
    n=len(yaw)
    if offset is not None:
        start=int(offset/.1)
        if start>=n-300:
            raise ValueError('Requested window leaves fewer than 30 seconds of samples')
        end=min(n,start+length)
        for clock in (s_time,v_time):
            dt=np.diff(clock[start:end])
            if not (np.isfinite(dt).all() and ((dt>0)&(dt<=.25)).all()):
                raise ValueError('Requested window crosses a missing/repeated/backward timestamp or gap')
        stops=[(start,end)]
    else:
        stops=[]
        for lo,hi in zip(np.linspace(0,n,4,dtype=int)[:-1],np.linspace(0,n,4,dtype=int)[1:]):
            width=min(length,hi-lo)
            candidates=[]
            for start in range(lo,max(lo+1,hi-width+1),max(1,width//4)):
                end=start+width
                if width<300:
                    continue
                dt=np.diff(s_time[start:end]); dv=np.diff(v_time[start:end])
                if not (np.isfinite(dt).all() and np.isfinite(dv).all() and
                        ((dt>0)&(dt<=.25)).all() and ((dv>0)&(dv<=.25)).all()):
                    continue
                y=yaw[start:end]; moving=speed[start:end]>3
                strength=float(np.nanstd(y)*np.mean(moving))
                candidates.append((strength,-start,start,end))
            if candidates:
                _,_,start,end=max(candidates)
                stops.append((start,end))
    return stops


def analyze_pair(root,pair,duration=120,offset=None):
    s=read_frame(root/pair['smartphone_file']);v=read_frame(root/pair['vbox_file'])
    if len(s)!=len(v):
        raise ValueError(f"{pair['sequence']}: unequal rows require separate alignment review")
    st=time_array(s,'smartphone');vt=time_array(v,'vbox')
    a=s[['accel_x','accel_y','accel_z']].to_numpy(float)
    g=s[['gravity_x','gravity_y','gravity_z']].to_numpy(float)
    channels={c:s[c].to_numpy(float) for c in s if c.startswith('gyro_channel_')}
    gyro=np.column_stack(list(channels.values()))
    speed=v['reference_speed'].to_numpy(float)/3.6
    yaw=np.radians(v['vehicle_yaw_rate'].to_numpy(float))
    long=v['vehicle_accel_long'].to_numpy(float)*G0
    lat=v['vehicle_accel_lat'].to_numpy(float)*G0
    heading=v['reference_heading'].to_numpy(float)
    hd=derivative(np.unwrap(np.radians(heading)),vt)
    dv=derivative(speed,vt)
    move=speed>3
    # Independent of acceleration/gravity cancellation: avoid selecting the
    # samples using the very sign hypothesis this stationary check must test.
    stationary_speed=np.maximum(speed,s['gps_speed_raw'].to_numpy(float))
    for clock in (st,vt):
        dt=np.diff(clock)
        bad=np.r_[False,(~np.isfinite(dt))|(dt<=0)|(dt>.25)]
        stationary_speed[bad]=np.nan
    still=stationary_mask(stationary_speed,a,g,gyro,linear_threshold=None)
    result=dict(sequence=pair['sequence'],driver=pair['driver'],rows=len(s),
                phase0_alignment=pair['alignment_class'],smartphone_file=pair['smartphone_file'],vbox_file=pair['vbox_file'],
                gravity_mean=np.nanmean(g,axis=0).tolist(),gravity_std=np.nanstd(g,axis=0).tolist(),
                gravity_dominant_axis='XYZ'[np.argmax(abs(np.nanmean(g,axis=0)))],
                stationary_samples=int(still.sum()),stationary_seconds_nominal=float(still.sum()*.1),
                stationary_accel_norm=finite_summary(np.linalg.norm(a[still],axis=1)),
                stationary_minus_norm=finite_summary(np.linalg.norm((a-g)[still],axis=1)),
                stationary_plus_norm=finite_summary(np.linalg.norm((a+g)[still],axis=1)),
                stationary_selection='both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples',
                changed_fraction={name:changed_fraction(value) for name,value in
                    [('accel',a),('gyro',gyro),('gravity',g),('gps_position',s[['gps_latitude','gps_longitude']].to_numpy(float)),
                     ('vbox_yaw',yaw),('vbox_long',long),('vbox_lat',lat)]},
                reference_sign_checks=dict(yaw_vs_negative_heading_derivative=metrics(yaw[move],-hd[move]),
                    yaw_vs_positive_heading_derivative=metrics(yaw[move],hd[move]),
                    lateral_vs_speed_yaw=metrics(lat[move],(speed*yaw)[move]),
                    longitudinal_g_scaled_vs_dvdt=metrics(long[move],dv[move]),
                    longitudinal_raw_vs_dvdt=metrics(long[move]/G0,dv[move])),windows=[])
    for start,end in choose_windows(st,vt,yaw,speed,duration,offset):
        sl=slice(start,end)
        ranks=gyro_candidates({c:x[sl] for c,x in channels.items()},yaw[sl],speed[sl],lat[sl])
        win=dict(start_row=start,end_row_exclusive=end,start_s=float(st[start]),end_s=float(st[end-1]),
                 yaw_std_rad_s=float(np.std(yaw[sl])),left_turn_samples=int(np.sum(yaw[sl]>.05)),
                 right_turn_samples=int(np.sum(yaw[sl]<-.05)),gyro_candidates=ranks)
        winner=ranks[0]
        z=winner['zero_lag']
        accepted=z['correlation'] is not None and z['correlation']>=.8 and .7<=z['slope']<=1.3
        accepted=accepted and z['nrmse']<=.7 and abs(z['intercept'])<=.03 and win['yaw_std_rad_s']>=.025
        win['gyro_accepted_at_zero_lag']=bool(accepted)
        win['gyro_note']='No best lag or regression correction is applied; selection is diagnostic, not evaluation training.'
        linear=(a-g)[sl];ref=np.column_stack([long[sl],lat[sl]])
        fit=fit_mounting(linear,np.mean(g[sl],axis=0),ref)
        mapped=linear @ fit['matrix'].T
        filtered=smooth(mapped[:,:2]);filtered_ref=smooth(ref)
        win['acceleration']=dict(matrix_phone_to_vehicle=fit['matrix'].tolist(),yaw_deg=float(np.degrees(fit['yaw_rad'])),
            determinant=float(np.linalg.det(fit['matrix'])),orthogonality_max_error=float(np.max(abs(fit['matrix'] @ fit['matrix'].T-np.eye(3)))),
            excitation_ratio=fit['excitation_ratio'],longitudinal=fit['longitudinal'],lateral=fit['lateral'],
            filtered_longitudinal=metrics(filtered[:,0],filtered_ref[:,0]),filtered_lateral=metrics(filtered[:,1],filtered_ref[:,1]),
            longitudinal_lag=lag_metrics(mapped[:,0],ref[:,0]),lateral_lag=lag_metrics(mapped[:,1],ref[:,1]),
            deviation_from_trailing_mean_norm=finite_summary(np.linalg.norm(mapped[:,:2]-filtered,axis=1)),
            deviation_from_trailing_mean_rms_m_s2=float(np.sqrt(np.nanmean(np.sum((mapped[:,:2]-filtered)**2,axis=1)))),
            gravity_mapped=(fit['matrix'] @ np.mean(g[sl],axis=0)).tolist(),
            minus_norm=finite_summary(np.linalg.norm(linear,axis=1)),plus_norm=finite_summary(np.linalg.norm((a+g)[sl],axis=1)))
        # Stability check: fit on the first half, evaluate without refitting on the second.
        mid=len(linear)//2
        calibration=fit_mounting(linear[:mid],np.mean(g[start:start+mid],axis=0),ref[:mid])
        held=linear[mid:] @ calibration['matrix'].T
        win['acceleration']['within_window_validation']=dict(fit_rows=mid,validation_rows=len(held),
            matrix=calibration['matrix'].tolist(),longitudinal=metrics(held[:,0],ref[mid:,0]),lateral=metrics(held[:,1],ref[mid:,1]))
        win['acceleration']['accepted']=bool(fit['excitation_ratio']>=.1 and all(
            m['correlation'] is not None and m['correlation']>=.7 and .7<=m['slope']<=1.3 and m['nrmse']<=.8
            for m in (fit['longitudinal'],fit['lateral'],
                      win['acceleration']['within_window_validation']['longitudinal'],
                      win['acceleration']['within_window_validation']['lateral'])))
        result['windows'].append(win)
    rotations=[np.array(w['acceleration']['matrix_phone_to_vehicle']) for w in result['windows']]
    angles=[float(np.degrees(np.arccos(np.clip((np.trace(x @ y.T)-1)/2,-1,1))))
            for i,x in enumerate(rotations) for y in rotations[i+1:]]
    result['mounting_stability_max_pairwise_degrees']=max(angles) if angles else None
    result['accepted_gyro_windows']=sum(w['gyro_accepted_at_zero_lag'] for w in result['windows'])
    result['accepted_acceleration_windows']=sum(w['acceleration']['accepted'] for w in result['windows'])
    return result


def fmt(x):
    return 'unresolved' if x is None else f'{x:.4f}'


def render_metrics(evidence):
    lines=['# Measured frame evidence','',
      'Confirmed from data: these results use same-row pairs without fitting or applying a time warp.',
      'Each sequence is processed separately. Three non-overlapping temporal thirds supply the most',
      'maneuver-rich eligible window per third; selection uses reference maneuver strength and clock quality,',
      'not phone correlation. These are development diagnostics, not a held-out evaluation.',
      'Lag search is ±10 rows (nominal ±1 s), with common central support. Positive lag compares',
      'phone[i+lag] against VBOX[i]. Zero-lag errors use the full window. No lag is applied.',
      'Regression convention: prediction = slope × reference + intercept. RMSE is not bias/scale corrected.',
      'Gyro scores combine signed correlation, slope distance from +1, intercept/scale, NRMSE, and speed×yaw consistency.',
      'Acceptance: corr≥0.8, slope 0.7–1.3, NRMSE≤0.7, |intercept|≤0.03 rad/s, reference std≥0.025 rad/s.',
      'These are declared engineering gates, not statistical confidence probabilities.','']
    for seq in evidence['sequences']:
        lines += [f"## {seq['sequence'].upper()} — Driver {seq['driver']}",'',
          f"Phase 0 alignment: {seq['phase0_alignment']}. Rows: {seq['rows']}. Gravity mean: {seq['gravity_mean']} m/s²; std: {seq['gravity_std']}.",
          f"Stationary: {seq['stationary_samples']} samples / {seq['stationary_seconds_nominal']:.1f} nominal seconds. Accel norm median: {fmt(seq['stationary_accel_norm']['median'])}; minus-gravity norm: {fmt(seq['stationary_minus_norm']['median'])}; plus-gravity norm: {fmt(seq['stationary_plus_norm']['median'])} m/s².",
          f"Changed-row fractions: `{seq['changed_fraction']}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.",'',
          f"Independent stationary selection: {seq['stationary_selection']}. Mounting rotation maximum between-window separation: {fmt(seq['mounting_stability_max_pairwise_degrees'])} degrees. Accepted gyro/acceleration windows: {seq['accepted_gyro_windows']}/{seq['accepted_acceleration_windows']}.",'',
          '| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |',
          '|---|---:|---:|---:|---:|---:|']
        for name,m in seq['reference_sign_checks'].items():
            lines.append('| '+name+' | '+' | '.join(fmt(m[k]) for k in ['correlation','slope','intercept','rmse','nrmse'])+' |')
        for window in seq['windows']:
            lines += ['',f"### Rows {window['start_row']}–{window['end_row_exclusive']-1} ({window['start_s']:.1f}–{window['end_s']:.1f} s)",'',
              f"Left/right maneuver samples: {window['left_turn_samples']}/{window['right_turn_samples']}; reference yaw std {window['yaw_std_rad_s']:.4f} rad/s.",
              '| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
            for r in window['gyro_candidates']:
                z=r['zero_lag'];b=r['best_lag'];m=b['metrics']
                vals=[fmt(z[k]) for k in ['correlation','slope','intercept','rmse','nrmse']]
                vals += [str(b['lag_samples'])]+[fmt(m[k]) for k in ['correlation','slope','intercept','rmse','nrmse']]
                lines.append(f"| {r['sign']:+d} × {r['channel']} | "+' | '.join(vals)+' |')
            a=window['acceleration'];w=window['gyro_candidates'][0]
            lines += ['',f"Zero-lag selection: {w['sign']:+d} × {w['channel']}; accepted: {window['gyro_accepted_at_zero_lag']}.",
              f"Mounting yaw relative to deterministic leveled basis: {a['yaw_deg']:.3f}°; det {a['determinant']:.8f}; orthogonality error {a['orthogonality_max_error']:.2g}; excitation ratio {a['excitation_ratio']:.4f}; accepted: {a['accepted']}.",
              '```text',np.array2string(np.array(a['matrix_phone_to_vehicle']),precision=7),'```','',
              '| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |',
              '|---|---:|---:|---:|---:|---:|']
            comparisons={k:a[k] for k in ['longitudinal','lateral','filtered_longitudinal','filtered_lateral']}
            comparisons.update({f'first-half fit / second-half {k}':a['within_window_validation'][k] for k in ['longitudinal','lateral']})
            for name,m in comparisons.items():
                lines.append('| '+name+' | '+' | '.join(fmt(m[k]) for k in ['correlation','slope','intercept','rmse','nrmse'])+' |')
            lines += ['',f"Acceleration best lag (not applied): long={a['longitudinal_lag']['lag_samples']}, lateral={a['lateral_lag']['lag_samples']} rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.",
              f"Deviation from trailing mean: RMS {a['deviation_from_trailing_mean_rms_m_s2']:.4f} m/s²; norm distribution {a['deviation_from_trailing_mean_norm']}. This includes filter delay, not just vibration. Gravity mapped: {a['gravity_mapped']}."]
    return '\n'.join(lines)+'\n'


def main(default_sequences=None):
    parser=argparse.ArgumentParser(description=__doc__)
    add_dataset_argument(parser)
    parser.add_argument('--sequences',nargs='+',default=default_sequences or list(DEFAULT_SEQUENCES))
    parser.add_argument('--duration',type=float,default=120.)
    parser.add_argument('--start',type=float,default=None,help='Explicit nominal row offset in seconds; otherwise select three temporal thirds')
    parser.add_argument('--top',type=int,default=3,help='Compatibility option; all six signed gyro candidates are retained in evidence')
    parser.add_argument('--write-report',action='store_true')
    parser.add_argument('--no-write',action='store_true')
    args=parser.parse_args()
    if not np.isfinite(args.duration) or args.duration<30:
        parser.error('--duration must be finite and at least 30 seconds')
    if args.start is not None and (not np.isfinite(args.start) or args.start<0):
        parser.error('--start must be finite and non-negative')
    if args.no_write and args.write_report:
        parser.error('--write-report and --no-write are mutually exclusive')
    try:
        root=resolve_dataset_root(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))
    metadata=json.loads((PROJECT_ROOT/'reports/phase0_evidence.json').read_text(encoding='utf-8'))
    requested=[x.lower() for x in args.sequences]
    pairs=[p for p in metadata['pairs'] if p['layout']=='categorized' and p['sequence'] in requested]
    missing=set(requested)-{p['sequence'] for p in pairs}
    if missing:
        parser.error(f'Sequences absent from reviewed Phase 0 evidence: {sorted(missing)}')
    before=snapshot(root) if args.write_report else None
    if before is not None:
        reviewed=json.loads((PROJECT_ROOT/'reports/phase0_raw_manifest.json').read_text(encoding='utf-8'))
        if before!=reviewed:
            raise ValueError('Dataset differs from the reviewed Phase 0 manifest; review that change before fitting frames')
    evidence=dict(method='frame-audit-v1',window_seconds=args.duration,max_lag_rows=10,sequences=[])
    for pair in pairs:
        try:
            result=analyze_pair(root,pair,args.duration,args.start)
        except ValueError as exc:
            parser.error(f"{pair['sequence']}: {exc}")
        evidence['sequences'].append(result)
        print(f"{pair['sequence']}: {len(result['windows'])} windows; stationary {result['stationary_samples']} samples",flush=True)
        for w in result['windows']:
            r=w['gyro_candidates'][0];m=r['zero_lag'];a=w['acceleration']
            print(f"  row {w['start_row']}: {r['sign']:+d} {r['channel']}, corr={fmt(m['correlation'])}, slope={fmt(m['slope'])}, nrmse={fmt(m['nrmse'])}; accepted={w['gyro_accepted_at_zero_lag']}; mounting={a['yaw_deg']:.2f} deg",flush=True)
    if args.write_report:
        if before!=snapshot(root):
            raise RuntimeError('Raw dataset changed during diagnosis; no reports written')
        evidence['raw_manifest_sha256']=hashlib.sha256(canonical(before).encode()).hexdigest()
        evidence['source_sha256']={p.relative_to(PROJECT_ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [Path(__file__),PROJECT_ROOT/'training/frame_math.py',PROJECT_ROOT/'training/common.py',
                      PROJECT_ROOT/'training/phase0/audit.py',PROJECT_ROOT/'training/phase0/schema.py',
                      PROJECT_ROOT/'training/phase0/statistics.py']}
        evidence['reviewed_phase0_evidence_sha256']=hashlib.sha256((PROJECT_ROOT/'reports/phase0_evidence.json').read_bytes()).hexdigest()
        (PROJECT_ROOT/'reports/body_frame_metrics.json').write_text(canonical(evidence),encoding='utf-8')
        (PROJECT_ROOT/'reports/body_frame_metrics.md').write_text(render_metrics(evidence),encoding='utf-8')
        print('Wrote new body-frame evidence; raw integrity verified.',flush=True)
    return evidence


if __name__=='__main__':
    main()
