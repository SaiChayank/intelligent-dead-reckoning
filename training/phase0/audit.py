"""Run: python -m training.phase0.audit --verify-repeat.

Reads raw data one CSV/pair at a time. Writes only the requested report directory.
No calibration, INS changes, training, or data repair is performed.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import platform
import re

import numpy as np
import pandas as pd

from training.common import add_dataset_argument

from .schema import PROJECT_ROOT, G0, column_spec, read_header, resolve_root, convert_unit, observed_schema
from .statistics import agreement, change_stats, clock_stats, haversine, monotonic_interp, summary, valid_coordinates

CHUNK_ROWS = 25000


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def snapshot(root):
    return [{"path": p.relative_to(root).as_posix(), "size_bytes": p.stat().st_size,
             "mtime_ns": p.stat().st_mtime_ns, "sha256": sha256(p)}
            for p in sorted(root.rglob("*")) if p.is_file()]


def metadata(path, root):
    rel = path.relative_to(root).as_posix()
    driver = re.search(r"\(Driver ([A-H])\)", rel)
    parts = rel.split("/")
    seq = path.stem[2:].lower()
    return {"path": rel, "source": "smartphone" if path.name.lower().startswith("s-") else "vbox",
            "sequence": seq, "original_sequence_name": path.stem[2:],
            "partition": "unsynchronized" if parts[0].lower().startswith("unsynchron") else "synchronized",
            "layout": "uncategorized" if any("uncategor" in x.lower() for x in parts) else "categorized",
            "driver": driver.group(1) if driver else None, "size_bytes": path.stat().st_size}


def read_frame(path):
    encoding, headers = read_header(path)
    df = pd.read_csv(path, encoding=encoding, low_memory=False)
    source = "smartphone" if path.name.lower().startswith("s-") else "vbox"
    specs = observed_schema(headers, source, df.head(20))
    if len(df.columns) != len(specs):
        raise ValueError(f"Header width changed in {path}")
    df.columns = [c["normalized_name"] for c in specs]
    if df.columns.duplicated().any():
        raise ValueError(f"Ambiguous normalized columns in {path}")
    df.attrs["source_column_registry"] = specs
    return df


def time_array(frame, source):
    if source == "smartphone":
        s = pd.to_datetime(frame["datetime_raw"].astype(str).str.strip(),
                           format="%Y-%m-%d %H:%M:%S:%f", errors="coerce")
        # Anchor to the first valid timestamp, retaining NaT as NaN.
        first = s.dropna()
        return (s - first.iloc[0]).dt.total_seconds().to_numpy() if len(first) else np.full(len(s), np.nan)
    a = pd.to_numeric(frame["reference_time_of_day"], errors="coerce").to_numpy(float)
    valid = a[np.isfinite(a)]
    return a - valid[0] if len(valid) else a


def scan_file(path, root):
    info = metadata(path, root)
    encoding, headers = read_header(path)
    sample = pd.read_csv(path, encoding=encoding, nrows=20)
    specs = observed_schema(headers, info["source"], sample)
    names = [c["normalized_name"] for c in specs]
    family = "schema-" + hashlib.sha256(canonical(headers).encode()).hexdigest()[:10]
    info.update(encoding=encoding, schema_family=family, rows=0)
    info["schema_mapping_status"] = "empirical_column_shift_quarantined" if any(c["normalized_name"] != c["header_normalized_name"] for c in specs) else "header_consistent"
    columns = [{"missing_count": 0, "non_numeric_count": 0, "finite_count": 0,
                "nonfinite_numeric_count": 0, "minimum": None, "maximum": None,
                "identical_adjacent_count": 0, "valid_adjacent_count": 0,
                "observed_dtypes": []} for _ in headers]
    digest = hashlib.sha256()
    times, counters, gps, imu = [], [], [], []
    last_values = [None] * len(headers)
    fingerprint = np.array([], dtype=np.uint64)
    for df in pd.read_csv(path, encoding=encoding, chunksize=CHUNK_ROWS, low_memory=False):
        if len(df.columns) != len(specs):
            raise ValueError(f"Inconsistent columns: {path}")
        df.columns = names
        if info["schema_mapping_status"] == "empirical_column_shift_quarantined":
            if not df["unexpected_empty_column"].isna().all():
                raise ValueError(f"S-A4 shift hypothesis fails within {path}")
        info["rows"] += len(df)
        hashed = df.copy()
        for j, name in enumerate(names):
            s, st = df[name], columns[j]
            missing = s.isna() | s.astype(str).str.strip().eq("")
            num = pd.to_numeric(s, errors="coerce")
            a = num.to_numpy(dtype=float, na_value=np.nan)
            finite = np.isfinite(a)
            st["missing_count"] += int(missing.sum())
            st["non_numeric_count"] += int((~missing & num.isna()).sum())
            st["nonfinite_numeric_count"] += int((num.notna().to_numpy() & ~finite).sum())
            st["finite_count"] += int(finite.sum())
            if str(s.dtype) not in st["observed_dtypes"]:
                st["observed_dtypes"].append(str(s.dtype))
            if finite.any():
                lo, hi = float(a[finite].min()), float(a[finite].max())
                st["minimum"] = min(st["minimum"], lo) if st["minimum"] is not None else lo
                st["maximum"] = max(st["maximum"], hi) if st["maximum"] is not None else hi
            previous = s.shift(1)
            if last_values[j] is not None:
                previous.iloc[0] = last_values[j]
            adjacent = s.notna() & previous.notna()
            st["valid_adjacent_count"] += int(adjacent.sum())
            st["identical_adjacent_count"] += int((s.eq(previous) & adjacent).sum())
            last_values[j] = s.iloc[-1]
            hashed[name] = num.astype(float) if num.notna().sum() == (~missing).sum() else s.astype(str).str.strip()
        row_hash = pd.util.hash_pandas_object(hashed, index=False).to_numpy(dtype="uint64")
        digest.update(row_hash.astype("<u8").tobytes())
        fingerprint = np.unique(np.r_[fingerprint, row_hash])[:64]
        if info["source"] == "smartphone":
            times.append(df["datetime_raw"].astype(str))
            counters.append(pd.to_numeric(df["elapsed_counter"], errors="coerce").to_numpy(float) / 1000)
            gps.append(df[["gps_latitude", "gps_longitude", "gps_speed_raw"]].apply(pd.to_numeric, errors="coerce").to_numpy(float))
            gyro = [n for n in names if n.startswith("gyro_channel_")]
            imu.append(df[["accel_x", "accel_y", "accel_z", "gravity_x", "gravity_y", "gravity_z"] + gyro].apply(pd.to_numeric, errors="coerce").to_numpy(float))
        else:
            times.append(pd.to_numeric(df["reference_time_of_day"], errors="coerce"))
            gps.append(df[["reference_latitude", "reference_longitude", "reference_speed"]].apply(pd.to_numeric, errors="coerce").to_numpy(float))
    if info["source"] == "smartphone":
        t = time_array(pd.DataFrame({"datetime_raw": pd.concat(times, ignore_index=True)}), "smartphone")
        info["elapsed_counter_timing"] = clock_stats(np.concatenate(counters))
        imu_data = np.concatenate(imu)
        info["imu_value_changes"] = change_stats(imu_data[:, [0, 1, 2] + list(range(6, imu_data.shape[1]))], t)
        phone_stationary = np.abs(np.concatenate(gps)[:, 2]) < .1
        info["unit_magnitude_checks"] = {
            "accel_norm_all": summary(np.linalg.norm(imu_data[:, :3], axis=1)),
            "gravity_norm_all": summary(np.linalg.norm(imu_data[:, 3:6], axis=1)),
            "accel_norm_phone_low_speed": summary(np.linalg.norm(imu_data[phone_stationary, :3], axis=1)),
            "selection_note": "Phone speed <0.1 source units is only a low-speed candidate, not independently verified stationary ground truth."}
        if info["schema_mapping_status"] == "empirical_column_shift_quarantined" and not np.isfinite(t).all():
            raise ValueError(f"S-A4 recovered datetime is not valid on all rows: {path}")
    else:
        t = time_array(pd.DataFrame({"reference_time_of_day": pd.concat(times, ignore_index=True)}), "vbox")
    info["timing"] = clock_stats(t)
    data = np.concatenate(gps)
    info["gps_value_changes"] = change_stats(data, t)
    info["invalid_coordinates"] = int((~valid_coordinates(data[:, 0], data[:, 1])).sum())
    info["column_observations"] = columns
    info["numeric_payload_sha256"] = digest.hexdigest()
    info["row_fingerprint_64"] = [int(x) for x in fingerprint]
    return info, {"id": family, "source": info["source"], "columns": specs}


def array(df, name):
    return pd.to_numeric(df[name], errors="coerce").to_numpy(dtype=float, na_value=np.nan, copy=True)


def position_stats(slat, slon, vlat, vlon):
    valid = valid_coordinates(slat, slon) & valid_coordinates(vlat, vlon)
    return summary(haversine(slat[valid], slon[valid], vlat[valid], vlon[valid]))


def alignment_candidate(st, slat, slon, speed_ms, vt, vlat, vlon, vms, offset=0., scale=1., mapped=None):
    target = st * scale + offset if mapped is None else mapped
    dt = np.diff(vt)
    positive = dt[np.isfinite(dt) & (dt > 0)]
    max_gap = max(.25, 2.5 * float(np.median(positive))) if len(positive) else .25
    vl = monotonic_interp(vt, vlat, target, max_gap_s=max_gap)
    vn = monotonic_interp(vt, vlon, target, max_gap_s=max_gap)
    vv = monotonic_interp(vt, vms, target, max_gap_s=max_gap)
    position = position_stats(slat, slon, vl, vn)
    speed = agreement(speed_ms, vv)
    return {"offset_s": float(offset), "scale": float(scale), "position_separation_m": position,
            "speed_error_m_s": speed, "position_coverage": position["count"] / len(st),
            "maximum_interpolation_gap_s": max_gap}


def unit_evidence(s, v):
    n = min(len(s), len(v))
    s, v = s.iloc[:n], v.iloc[:n]
    gps, ref = array(s, "gps_speed_raw"), array(v, "reference_speed")
    raw = agreement(gps, ref)
    scaled = agreement(convert_unit(gps, "m/s", "km/h"), ref)
    # Only make a unit inference on moving and varying data.
    dynamic = np.nanstd(ref) > 5 and np.nanmean(ref) > 5
    supports_ms = bool(dynamic and scaled["mae"] is not None and scaled["mae"] < .5 * raw["mae"])
    supports_kmh = bool(dynamic and raw["mae"] is not None and raw["mae"] < .5 * scaled["mae"])
    inference = "m/s" if supports_ms else "km/h" if supports_kmh else "unresolved"
    accel = s[["accel_x", "accel_y", "accel_z"]].apply(pd.to_numeric, errors="coerce").to_numpy(float)
    gravity = s[["gravity_x", "gravity_y", "gravity_z"]].apply(pd.to_numeric, errors="coerce").to_numpy(float)
    stationary = np.isfinite(ref) & (np.abs(ref) < .5)
    # Require a continuous run of >= 3 seconds at nominal 10 Hz, not isolated low-speed rows.
    stationary_runs = np.convolve(stationary.astype(int), np.ones(min(n, 30), dtype=int), mode="same") >= 30
    stationary &= stationary_runs
    vt = time_array(v, "vbox")
    dt = np.diff(vt)
    vms = ref / 3.6
    derivative = np.full(n, np.nan)
    good_dt = (dt > .05) & (dt < .2)
    derivative[1:] = np.divide(np.diff(vms), dt, out=np.full(n - 1, np.nan), where=good_dt)
    accel_long = array(v, "vehicle_accel_long")
    smoothing = min(n, 11)
    # Require a fully valid smoothing window; do not invent zero acceleration
    # at missing timestamps, duplicate timestamps, gaps, or convolution edges.
    finite_deriv = np.isfinite(derivative) & np.isfinite(accel_long)
    valid_window = np.convolve(finite_deriv.astype(int), np.ones(smoothing, dtype=int), "same") == smoothing
    deriv_smooth = np.convolve(np.where(finite_deriv, derivative, 0), np.ones(smoothing) / smoothing, "same")
    long_smooth = np.convolve(np.where(finite_deriv, accel_long, 0), np.ones(smoothing) / smoothing, "same")
    deriv_smooth[~valid_window], long_smooth[~valid_window] = np.nan, np.nan
    long_raw = agreement(long_smooth, deriv_smooth)
    long_g = agreement(long_smooth * G0, deriv_smooth)
    yaw_ref = array(v, "vehicle_yaw_rate")
    lateral = array(v, "vehicle_accel_lat")
    lat_physics = vms * np.radians(yaw_ref)
    lateral_tests = {f"{sign:+d}g": agreement(sign * lateral * G0, lat_physics) for sign in (1, -1)}
    gyro_tests = []
    for name in [c for c in s if c.startswith("gyro_channel_")]:
        a = array(s, name)
        for sign in (1, -1):
            gyro_tests.append({"channel": name, "sign": sign,
                               "assuming_rad_s": agreement(np.degrees(a) * sign, yaw_ref),
                               "assuming_deg_s": agreement(a * sign, yaw_ref)})
    gyro_tests.sort(key=lambda d: d["assuming_rad_s"]["rmse"] if d["assuming_rad_s"]["rmse"] is not None else float("inf"))
    height = agreement(array(v, "reference_height_raw"), array(s, "gps_altitude"))
    height_km = agreement(array(v, "reference_height_raw") * 1000, array(s, "gps_altitude"))
    return {"speed_unit_inference": inference, "speed_unit_evidence": "Inferred with supporting evidence" if inference != "unresolved" else "Still unresolved",
            "speed_raw_assumed_kmh_vs_vbox_kmh": raw, "speed_x3_6_vs_vbox_kmh": scaled,
            "stationary_reference_threshold_kmh": .5, "stationary_min_run_s": 3,
            "stationary_samples": int(stationary.sum()),
            "stationary_accel_norm_m_s2": summary(np.linalg.norm(accel[stationary], axis=1)),
            "stationary_gravity_norm_m_s2": summary(np.linalg.norm(gravity[stationary], axis=1)),
            "stationary_accel_minus_gravity_norm_m_s2": summary(np.linalg.norm((accel - gravity)[stationary], axis=1)),
            "stationary_accel_plus_gravity_norm_m_s2": summary(np.linalg.norm((accel + gravity)[stationary], axis=1)),
            "long_accel_raw_vs_speed_derivative": long_raw,
            "long_accel_times_g0_vs_speed_derivative": long_g,
            "acceleration_test_note": "11-row boxcar smoothing; windows containing invalid dt or nonfinite acceleration and boundary windows excluded; row pairing approximate; magnitude evidence only, not calibration or frame validation.",
            "lateral_accel_g_vs_v_times_omega": lateral_tests,
            "gyro_channel_tests": gyro_tests,
            "gyro_note": "Tests raw channels independently. A good scale fit supports rad/s for a channel/sequence, not physical XYZ mapping or all schema variants.",
            "height_assumed_m_vs_phone_altitude_m": height,
            "height_assumed_km_vs_phone_altitude_m": height_km,
            "height_note": "Magnitude comparison only: different antenna heights and vertical datums are unresolved. Do not use either height for 3-D truth."}


def audit_pair(root, phone, vehicle):
    s, v = read_frame(root / phone["path"]), read_frame(root / vehicle["path"])
    st, vt = time_array(s, "smartphone"), time_array(v, "vbox")
    slat, slon = array(s, "gps_latitude"), array(s, "gps_longitude")
    vlat, vlon = array(v, "reference_latitude"), array(v, "reference_longitude")
    bad_s, bad_v = ~valid_coordinates(slat, slon), ~valid_coordinates(vlat, vlon)
    slat[bad_s], slon[bad_s], vlat[bad_v], vlon[bad_v] = np.nan, np.nan, np.nan, np.nan
    unit = unit_evidence(s, v)
    # m/s is a diagnostic hypothesis only when unit inference is unresolved.
    speed_scale = 1 / 3.6 if unit["speed_unit_inference"] == "km/h" else 1.
    speed_ms, vms = array(s, "gps_speed_raw") * speed_scale, array(v, "reference_speed") / 3.6
    n = min(len(s), len(v))
    same = {"position_separation_m": position_stats(slat[:n], slon[:n], vlat[:n], vlon[:n]),
            "speed_error_m_s": agreement(speed_ms[:n], vms[:n])}
    args = (st, slat, slon, speed_ms, vt, vlat, vlon, vms)
    zero = alignment_candidate(*args)
    # Sample at fixed row stride only for coarse alignment search. Final statistics use all rows.
    stride = max(1, len(s) // 6000)
    sample = tuple(a[::stride] if i < 4 else a for i, a in enumerate(args))
    base_p = max(1., zero["position_separation_m"]["median"] or 1.)
    base_s = max(.5, zero["speed_error_m_s"]["mae"] or .5)

    def score(result):
        p, sp = result["position_separation_m"], result["speed_error_m_s"]
        if p["count"] < 30 or sp["count"] < 30 or result["position_coverage"] < .8:
            return float("inf")
        return p["median"] / base_p + sp["mae"] / base_s

    coarse = [alignment_candidate(*sample, offset=float(x)) for x in np.arange(-10, 10.001, .5)]
    best = min(coarse, key=score)
    center = best["offset_s"]
    fine = [alignment_candidate(*sample, offset=float(round(x, 4)))
            for x in np.arange(max(-10, center - .5), min(10, center + .5) + .001, .1)]
    best = alignment_candidate(*args, offset=min(fine, key=score)["offset_s"])
    best["normalized_multi_metric_score"] = score(best) if np.isfinite(score(best)) else None
    position_best = min(coarse, key=lambda r: r["position_separation_m"]["median"] if r["position_separation_m"]["median"] is not None else float("inf"))
    speed_best = min(coarse, key=lambda r: r["speed_error_m_s"]["mae"] if r["speed_error_m_s"]["mae"] is not None else float("inf"))
    # Check lag stability in disjoint thirds using the same joint metric.
    thirds = []
    for indices in np.array_split(np.arange(len(s))[::stride], 3):
        subset = tuple(a[indices] if i < 4 else a for i, a in enumerate(args))
        candidates = [alignment_candidate(*subset, offset=float(x)) for x in np.arange(-10, 10.001, .5)]
        winner = min(candidates, key=score)
        thirds.append({"start_row": int(indices[0]), "end_row": int(indices[-1]),
                       "best_offset_s": winner["offset_s"], "finite_joint_score": bool(np.isfinite(score(winner)))})
    # Endpoint affine is justified only as a diagnostic of unequal durations.
    duration_difference = st[-1] - vt[-1]
    affine = None
    if np.isfinite(duration_difference) and abs(duration_difference) > .25 and st[-1] > 0:
        affine = alignment_candidate(*args, scale=vt[-1] / st[-1])
        affine["justification"] = "Unequal endpoint durations; endpoint correspondence is hypothetical, not established. No data are warped."
    gap_hypothesis = None
    if phone["timing"]["large_gap_count"]:
        mapped = st.copy()
        dt_nominal = phone["timing"]["positive_interval_median_s"]
        for gap in phone["timing"]["large_gaps"]:
            mapped[gap["after_row_0based"] + 1:] -= gap["delta_s"] - dt_nominal
        gap_hypothesis = alignment_candidate(*args, mapped=mapped)
        gap_hypothesis["justification"] = "Piecewise removal of excess DATE gaps inferred from smartphone clock alone; assumes clock discontinuities, not physical data loss. Diagnostic only."
    p = same["position_separation_m"]
    corr = same["speed_error_m_s"]["correlation"]
    row_equal = len(s) == len(v)
    bad_clock = any(r["timing"]["negative_intervals"] or r["timing"]["missing_timestamps"] for r in (phone, vehicle))
    if not row_equal or bad_clock or zero["position_coverage"] < .8 or (p["median"] is not None and p["median"] > 200):
        status = "unusable"
        issues = []
        if not row_equal:
            issues.append("unequal row counts")
        if bad_clock:
            issues.append("backward/missing main clock")
        if zero["position_coverage"] < .8:
            issues.append("less than 80% position coverage after excluding invalid coordinates and interpolation gaps")
        if p["median"] is not None and p["median"] > 200:
            issues.append("same-row median position disagreement above 200 m")
        reason = "; ".join(issues)
    elif corr is not None and corr >= .9 and p["median"] is not None and p["median"] <= 30 and unit["speed_unit_inference"] != "unresolved":
        status = "approximate"
        reason = "Equal rows, speed correlation >=0.9, median separation <=30 m, supported speed unit; still not proof of synchronous IMU labels."
    else:
        status = "uncertain"
        reason = "Insufficient dynamics/unit evidence or cross-sensor agreement outside conservative diagnostic thresholds."
    return {"id": phone["layout"] + "/" + phone["sequence"], "sequence": phone["sequence"],
            "layout": phone["layout"], "driver": phone["driver"],
            "smartphone_file": phone["path"], "vbox_file": vehicle["path"],
            "smartphone_rows": len(s), "vbox_rows": len(v), "row_count_match": row_equal,
            "smartphone_duration_s": float(st[-1]), "vbox_duration_s": float(vt[-1]),
            "duration_difference_s": float(duration_difference),
            "same_row": same, "zero_elapsed_offset": zero, "best_constant_offset": best,
            "single_metric_optima_s": {"position_only": position_best["offset_s"], "speed_only": speed_best["offset_s"]},
            "disjoint_third_lags": thirds, "affine_diagnostic": affine, "piecewise_gap_diagnostic": gap_hypothesis,
            "units": unit, "alignment_class": status, "classification_reason": reason,
            "constant_offset_at_search_boundary": abs(best["offset_s"]) >= 9.999,
            "speed_comparison_unit_policy": "m/s is only an unverified diagnostic hypothesis" if unit["speed_unit_inference"] == "unresolved" else "source speed converted explicitly using per-pair empirical unit evidence",
            "supervised_learning_suitability": "conditional candidate; validate inertial-label lag on training/calibration data" if status == "approximate" else "quarantine from supervised velocity training pending investigation",
            "split_group": "recording-" + phone["sequence"],
            "evaluation_policy": "All fitted lags here are descriptive audit results, never test-set preprocessing parameters. Freeze transforms using training/calibration data only."}


def collect(root, manifest):
    files, families = [], {}
    paths = sorted(root.rglob("*.csv"))
    for index, path in enumerate(paths, 1):
        info, family = scan_file(path, root)
        files.append(info)
        families.setdefault(family["id"], family)
        if index % 25 == 0 or index == len(paths):
            print(f"CSV scan {index}/{len(paths)}: {path.name}", flush=True)
    # Inherit folder-established driver labels by sequence; use paper for F/G/H only.
    drivers = {f["sequence"]: f["driver"] for f in files if f["driver"]}
    for f in files:
        if not f["driver"]:
            seq = f["sequence"]
            f["driver"] = drivers.get(seq)
            f["driver_evidence"] = "Inferred from same sequence in categorized folders"
            if not f["driver"]:
                f["driver"] = "F" if re.fullmatch(r"t\d+", seq) else "G" if seq == "i" else "H" if re.fullmatch(r"a\d+", seq) else "unknown"
                f["driver_evidence"] = "Stated by dataset documentation (README_1.pdf Table A7)" if f["driver"] != "unknown" else "Still unresolved"
        else:
            f["driver_evidence"] = "Confirmed from data: folder label"
    for family in families.values():
        members = [f for f in files if f["schema_family"] == family["id"]]
        family["files"] = [f["path"] for f in members]
        family["file_count"] = len(members)
        family["total_rows_including_duplicate_copies"] = sum(f["rows"] for f in members)
        family["representative_file"] = members[0]["path"]
        for i, spec in enumerate(family["columns"]):
            observations = [f["column_observations"][i] for f in members]
            spec["observed_dtypes"] = sorted(set(t for o in observations for t in o["observed_dtypes"]))
            spec["data_type"] = "string" if spec["measurement_type"] in {"categorical", "timestamp"} and family["source"] == "smartphone" else "float64"
            for key in ("missing_count", "non_numeric_count", "nonfinite_numeric_count", "finite_count", "identical_adjacent_count", "valid_adjacent_count"):
                spec[key] = sum(o[key] for o in observations)
            for key, fun in (("minimum", min), ("maximum", max)):
                values = [o[key] for o in observations if o[key] is not None]
                spec[key] = fun(values) if values else None
            spec["missing_fraction"] = spec["missing_count"] / family["total_rows_including_duplicate_copies"]
            spec["missing_value_behavior"] = "retained; no imputation; nonnumeric and nonfinite counts reported separately"
            spec["unchanged_adjacent_fraction"] = spec["identical_adjacent_count"] / spec["valid_adjacent_count"] if spec["valid_adjacent_count"] else None
            spec["forward_fill_assessment"] = "held/quantized values suspected; see per-file GPS cadence" if spec["normalized_name"].startswith("gps_") and (spec["unchanged_adjacent_fraction"] or 0) > .8 else "not established"
    groups = defaultdict(list)
    for f in files:
        if f["partition"] == "synchronized":
            groups[f["layout"], f["sequence"]].append(f)
    pairs, unmatched = [], []
    for key in sorted(groups):
        members = groups[key]
        ps, vs = [f for f in members if f["source"] == "smartphone"], [f for f in members if f["source"] == "vbox"]
        if len(ps) == len(vs) == 1:
            pairs.append(audit_pair(root, ps[0], vs[0]))
        else:
            unmatched.append({"group": list(key), "files": [f["path"] for f in members]})
        if len(pairs) % 12 == 0:
            print(f"Pair analysis {len(pairs)}/{len(groups)}", flush=True)
    exact = defaultdict(list)
    numeric = defaultdict(list)
    hashes = {m["path"]: m["sha256"] for m in manifest}
    for f in files:
        f["sha256"] = hashes[f["path"]]
        exact[f["sha256"]].append(f["path"])
        numeric[f["numeric_payload_sha256"]].append(f["path"])
    overlap = []
    recording = defaultdict(list)
    for f in files:
        recording[f["source"], f["sequence"]].append(f)
    for key, members in sorted(recording.items()):
        for i, a in enumerate(members):
            for b in members[i + 1:]:
                if a["numeric_payload_sha256"] != b["numeric_payload_sha256"]:
                    common = len(set(a["row_fingerprint_64"]) & set(b["row_fingerprint_64"]))
                    if common >= 8:
                        overlap.append({"file_a": a["path"], "file_b": b["path"], "common_bottom64_row_hashes": common,
                                        "assessment": "Inferred overlapping recording; row fingerprints are candidate evidence, not an exact overlap extent"})
    for family in families.values():
        unit_decisions(family, pairs)
    # File-name matches in the unsynchronized tree are an inventory relationship,
    # not evidence of sample correspondence or permission to use them as labels.
    unsync = defaultdict(lambda: {"smartphone": [], "vbox": []})
    for f in files:
        if f["partition"] == "unsynchronized":
            unsync[f["sequence"]][f["source"]].append(f)
    unsync_matches, unsync_unmatched = [], []
    for seq, sources in sorted(unsync.items()):
        if sources["smartphone"] and sources["vbox"]:
            for s in sources["smartphone"]:
                for v in sources["vbox"]:
                    unsync_matches.append({"sequence": seq, "smartphone_file": s["path"], "vbox_file": v["path"],
                        "smartphone_rows": s["rows"], "vbox_rows": v["rows"], "row_count_match": s["rows"] == v["rows"],
                        "alignment_class": "uncertain", "assessment": "Filename-only match; unsynchronized by dataset designation; not suitable for direct supervised labels"})
        else:
            unsync_unmatched.extend(f["path"] for source in sources.values() for f in source)
    inventory = {"method": "All CSVs parsed in 25000-row chunks; complete dataset is never held as dataframes simultaneously.",
                 "csv_file_count": len(files), "csv_bytes": sum(f["size_bytes"] for f in files),
                 "counts": dict(sorted(Counter(f["partition"] + "/" + f["source"] for f in files).items())),
                 "route_image_count": sum(p.suffix.lower() in {".jpg", ".jpeg", ".png"} for p in root.rglob("*") if p.is_file()),
                 "raw_regular_files_including_nested_git": len(manifest),
                 "dataset_files_excluding_nested_git": sum(".git" not in Path(m["path"]).parts for m in manifest),
                 "csv_rows_including_duplicate_copies": sum(f["rows"] for f in files),
                 "drivers": {d: sorted(set(f["sequence"] for f in files if f["driver"] == d)) for d in sorted(set(f["driver"] for f in files))},
                 "exact_byte_duplicate_groups": [g for _, g in sorted(exact.items()) if len(g) > 1],
                 "same_numeric_payload_groups": [g for _, g in sorted(numeric.items()) if len(g) > 1],
                 "overlap_candidates": overlap, "unmatched_synchronized_groups": unmatched,
                 "unsynchronized_filename_matches": unsync_matches, "unmatched_unsynchronized_files": unsync_unmatched,
                 "files": files}
    registry = {"version": 1, "policy": "Source names/units retained. Empirical units scoped to evidence files, never silently applied globally. Gyro channel names do not establish physical axes.",
                "families": sorted(families.values(), key=lambda f: f["id"])}
    evidence = {"version": 1, "lag_search": {"range_s": [-10, 10], "coarse_step_s": .5, "fine_step_s": .1,
                 "score": "median_position/max(1,zero_median_position) + speed_MAE/max(0.5,zero_speed_MAE)",
                 "note": "equal-weight diagnostics, not a deployable synchronization model; constant-speed/stationary data cannot identify lag"},
                "classification": "No pair is called exact. approximate requires equal rows, finite forward clocks, >=80% valid position coverage, corr>=0.9, median position<=30m and inferred speed units. Unusable/uncertain are diagnostic thresholds, not competition criteria.",
                "pairs": pairs}
    return inventory, registry, evidence


def unit_decisions(family, pairs):
    members = set(family["files"])
    observed = [p for p in pairs if p["smartphone_file"] in members or p["vbox_file"] in members]
    for spec in family["columns"]:
        name = spec["normalized_name"]
        spec["unit_evidence_pair_ids"] = [p["id"] for p in observed]
        if name == "gps_speed_raw":
            choices = Counter(p["units"]["speed_unit_inference"] for p in observed)
            spec["unit_inference_counts"] = dict(sorted(choices.items()))
            resolved = [u for u in choices if u != "unresolved"]
            if len(resolved) == 1:
                spec.update(empirically_inferred_unit=resolved[0], normalized_unit="m/s",
                            unit_confidence="supported on listed moving synchronized pairs; unpaired sequences remain unverified",
                            evidence_label="Inferred with supporting evidence")
            else:
                spec.update(normalized_unit="unresolved", unit_confidence="Still unresolved: insufficient or conflicting paired evidence", evidence_label="Still unresolved")
        elif name == "reference_height_raw":
            spec.update(empirically_inferred_unit="m (magnitude hypothesis only)", normalized_unit="unresolved",
                        unit_confidence="provisional: source says km; datum/scale not independently established",
                        evidence_label="Inferred with supporting evidence")
        elif name.startswith(("accel_", "gravity_")) and observed:
            spec.update(empirically_inferred_unit="m/s2", unit_confidence="supported by gravity and stationary norm tests; frame unresolved", evidence_label="Inferred with supporting evidence")
        elif name.startswith("gyro_channel_") and observed:
            supported = []
            for p in observed:
                tests = [g for g in p["units"]["gyro_channel_tests"] if g["channel"] == name]
                for test in tests:
                    rad, deg = test["assuming_rad_s"], test["assuming_deg_s"]
                    if (rad["correlation"] is not None and rad["correlation"] >= .6
                            and rad["slope_predicted_per_reference"] is not None and .4 <= rad["slope_predicted_per_reference"] <= 1.6
                            and rad["reference"]["std"] >= 2 and rad["rmse"] < .95 * deg["rmse"]):
                        supported.append(p["id"])
                        break
            spec["rad_s_supporting_pairs"] = supported
            spec.update(empirically_inferred_unit="rad/s" if supported else None,
                        unit_confidence="supported by channel-specific yaw scale/regression; not physical axis calibration" if supported else "Still unresolved empirically: rad/s is the source label; this channel has no qualifying yaw comparison",
                        evidence_label="Inferred with supporting evidence" if supported else "Still unresolved")
        elif name in {"vehicle_accel_long", "vehicle_accel_lat"}:
            spec.update(empirically_inferred_unit="g (hypothesis tested against motion)", unit_confidence="provisional: derivative/turn consistency does not establish frame or latency", evidence_label="Inferred with supporting evidence")
        elif name == "vehicle_accelerator_pedal_raw":
            spec.update(empirically_inferred_unit="percent-like scale", normalized_unit="unresolved",
                        unit_confidence="header says flag but measured range is not binary; paper Table 3 says percent", evidence_label="Inferred with supporting evidence")


def build_outputs(inventory, registry, evidence):
    from .reporting import render_reports
    output = {"phase0_inventory.json": canonical(inventory), "schema_registry.json": canonical(registry),
              "phase0_evidence.json": canonical(evidence)}
    output.update(render_reports(inventory, registry, evidence))
    for name in ("data_schema.md", "data_dictionary.md", "synchronization_analysis.md"):
        if len(output[name]) < 1000 or re.search(r"\b(?:TODO|TBD|PLACEHOLDER)\b", output[name], flags=re.I):
            raise ValueError(f"Incomplete generated report: {name}")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_dataset_argument(parser)
    parser.add_argument("--report-dir", type=Path, default=PROJECT_ROOT / "reports")
    parser.add_argument("--verify-repeat", action="store_true", help="Repeat entire audit and compare every output byte")
    parser.add_argument("--no-write", action="store_true", help="Analyze and check integrity without saving reports")
    args = parser.parse_args()
    try:
        root = resolve_root(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))
    out = args.report_dir.resolve()
    # Neither a child nor ancestor of the dataset is a valid output directory.
    raw_parent = PROJECT_ROOT / "data/raw"
    if out == root or root in out.parents or out in root.parents or out == raw_parent or raw_parent in out.parents:
        raise ValueError("Report directory must be outside data/raw and the chosen dataset tree")
    print("Taking raw SHA-256/size/mtime snapshot (including archives and nested .git)...", flush=True)
    before = snapshot(root)
    first = build_outputs(*collect(root, before))
    first_hashes = {name: hashlib.sha256(data.encode()).hexdigest() for name, data in first.items()}
    second_hashes = None
    if args.verify_repeat:
        print("Starting independent second analysis pass...", flush=True)
        second = build_outputs(*collect(root, before))
        second_hashes = {name: hashlib.sha256(data.encode()).hexdigest() for name, data in second.items()}
        if first_hashes != second_hashes:
            raise RuntimeError("Nondeterministic outputs; no reports written")
    print("Verifying raw SHA-256/size/mtime snapshot...", flush=True)
    after = snapshot(root)
    if before != after:
        raise RuntimeError("Raw tree changed during analysis; no reports written")
    verification = {"raw_files_checked": len(before), "raw_sha256_size_mtime_unchanged": True,
                    "generated_reports_checked_for_placeholder_markers": True,
                    "analysis_source_sha256": {p.relative_to(PROJECT_ROOT).as_posix(): sha256(p)
                        for p in sorted([*Path(__file__).parent.glob("*.py"), PROJECT_ROOT / "training/common.py"])},
                    "raw_manifest_sha256_before": hashlib.sha256(canonical(before).encode()).hexdigest(),
                    "raw_manifest_sha256_after": hashlib.sha256(canonical(after).encode()).hexdigest(),
                    "full_analysis_runs": 2 if args.verify_repeat else 1,
                    "byte_identical_repeat": first_hashes == second_hashes if args.verify_repeat else None,
                    "first_output_sha256": first_hashes, "second_output_sha256": second_hashes,
                    "runtime": {"python": platform.python_version(), "numpy": np.__version__, "pandas": pd.__version__}}
    if args.no_write:
        print("Phase 0 analysis complete; raw integrity verified; no reports written.", flush=True)
        return
    out.mkdir(parents=True, exist_ok=True)
    for name, data in first.items():
        (out / name).write_text(data, encoding="utf-8", newline="\n")
    (out / "phase0_raw_manifest.json").write_text(canonical(before), encoding="utf-8", newline="\n")
    (out / "phase0_verification.json").write_text(canonical(verification), encoding="utf-8", newline="\n")
    print(f"Phase 0 reports written to {out}; raw integrity verified.", flush=True)


if __name__ == "__main__":
    main()
