"""Deterministic, finite-aware descriptive statistics and timing diagnostics."""

from __future__ import annotations

import numpy as np

from training.common import EARTH_RADIUS_M, haversine, valid_coordinates


def summary(values) -> dict:
    a = np.asarray(values, dtype=float)
    a = a[np.isfinite(a)]
    if not len(a):
        return {"count": 0, "mean": None, "median": None, "min": None, "max": None,
                "p01": None, "p95": None, "p99": None, "std": None}
    return {"count": int(len(a)), "mean": float(np.mean(a)), "median": float(np.median(a)),
            "min": float(np.min(a)), "max": float(np.max(a)), "p01": float(np.percentile(a, 1)),
            "p95": float(np.percentile(a, 95)), "p99": float(np.percentile(a, 99)),
            "std": float(np.std(a))}


def agreement(predicted, reference) -> dict:
    a, b = np.asarray(predicted, float), np.asarray(reference, float)
    valid = np.isfinite(a) & np.isfinite(b)
    a, b = a[valid], b[valid]
    if not len(a):
        return {"count": 0, "mae": None, "rmse": None, "correlation": None,
                "slope_predicted_per_reference": None, "intercept": None,
                "predicted": summary([]), "reference": summary([]), "signed_error": summary([]),
                "absolute_error": summary([])}
    corr, slope, intercept = None, None, None
    if len(a) >= 3 and np.std(a) > 1e-10 and np.std(b) > 1e-10:
        corr = float(np.corrcoef(a, b)[0, 1])
        slope = float(np.mean((a - a.mean()) * (b - b.mean())) / np.var(b))
        intercept = float(a.mean() - slope * b.mean())
    error = a - b
    return {"count": int(len(a)), "mae": float(np.mean(np.abs(error))),
            "rmse": float(np.sqrt(np.mean(error ** 2))), "correlation": corr,
            "slope_predicted_per_reference": slope, "intercept": intercept,
            "predicted": summary(a), "reference": summary(b),
            "signed_error": summary(error), "absolute_error": summary(abs(error))}


def clock_stats(t) -> dict:
    t = np.asarray(t, float)
    dt = np.diff(t)
    positive = dt[np.isfinite(dt) & (dt > 0)]
    median = float(np.median(positive)) if len(positive) else None
    threshold = max(.25, 2.5 * median) if median else .25
    gap_rows = np.flatnonzero(dt > threshold)
    valid_t = t[np.isfinite(t)]
    gaps = [{"after_row_0based": int(i), "delta_s": float(dt[i])} for i in gap_rows]
    return {"samples": int(len(t)), "missing_timestamps": int((~np.isfinite(t)).sum()),
            "intervals_s": summary(dt), "positive_interval_median_s": median,
            "nominal_observed_rate_hz": 1 / median if median else None,
            "rate_interpretation": "inverse median positive dt, not hardware sample rate; burst/repeated timestamps can grossly inflate it",
            "rows_per_elapsed_second": (len(t) - 1) / (valid_t[-1] - valid_t[0]) if len(valid_t) > 1 and valid_t[-1] > valid_t[0] else None,
            "elapsed_duration_s": float(valid_t[-1] - valid_t[0]) if len(valid_t) else None,
            "repeated_timestamps": int(np.sum(dt == 0)), "negative_intervals": int(np.sum(dt < 0)),
            "large_gap_threshold_s": threshold, "large_gap_count": len(gaps), "large_gaps": gaps,
            "estimated_missing_slots_from_large_gaps": int(sum(max(0, round(dt[i] / median) - 1)
                  for i in gap_rows)) if median else None,
            "missing_slots_note": "gap-based estimate; does not prove samples were dropped",
            "row_clock_policy": "candidate for nominal rate only; pairing and local gaps require separate validation"}


def change_stats(values, clock) -> dict:
    a, t = np.asarray(values, float), np.asarray(clock, float)
    if a.ndim == 1:
        a = a[:, None]
    valid = np.isfinite(a).all(axis=1)
    transitions = valid[1:] & valid[:-1]
    changed = transitions & np.any(a[1:] != a[:-1], axis=1)
    idx = np.r_[0, np.flatnonzero(changed) + 1]
    durations = np.diff(t[idx])
    durations = durations[np.isfinite(durations) & (durations > 0)]
    med = float(np.median(durations)) if len(durations) else None
    runs = np.diff(np.r_[idx, len(a)])
    return {"observed_changes": int(changed.sum()), "valid_adjacent_pairs": int(transitions.sum()),
            "unchanged_fraction": float(1 - changed.sum() / transitions.sum()) if transitions.sum() else None,
            "between_change_intervals_s": summary(durations),
            "typical_value_change_rate_hz": 1 / med if med else None,
            "held_run_lengths_rows": summary(runs),
            "interpretation": "Observed value changes only; repeated values suggest held/quantized data, not proof of hardware update rate."}


def monotonic_interp(t, values, target, max_gap_s=None):
    """Refuse backward clocks; average duplicate-time reference observations explicitly."""
    t, values, target = np.asarray(t, float), np.asarray(values, float), np.asarray(target, float)
    valid = np.isfinite(t) & np.isfinite(values)
    t, values = t[valid], values[valid]
    if len(t) < 2 or np.any(np.diff(t) < 0):
        return np.full_like(target, np.nan)
    unique, inv, counts = np.unique(t, return_inverse=True, return_counts=True)
    averages = np.bincount(inv, weights=values) / counts
    result = np.interp(target, unique, averages, left=np.nan, right=np.nan)
    if max_gap_s is not None:
        right = np.searchsorted(unique, target, side="left")
        inside = (right > 0) & (right < len(unique))
        ri = np.clip(right, 1, len(unique) - 1)
        crosses_gap = inside & (unique[ri] - unique[ri - 1] > max_gap_s) & (target != unique[ri])
        result[crosses_gap] = np.nan
    return result
