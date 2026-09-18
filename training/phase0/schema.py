"""Source-preserving column registry. Gyro names deliberately do not imply axes."""

from __future__ import annotations

import re

import numpy as np

G0 = 9.80665
from training.common import (
    PROJECT_ROOT, normalize_header, read_header, resolve_dataset_root as resolve_root,
)


def convert_unit(values, source: str, target: str):
    """Convert only explicitly specified units; never infer from values here."""
    values = np.asarray(values, dtype=float)
    if source == target:
        return values.copy()
    factors = {("km/h", "m/s"): 1 / 3.6, ("m/s", "km/h"): 3.6,
               ("g", "m/s2"): G0, ("deg/s", "rad/s"): np.pi / 180,
               ("rad/s", "deg/s"): 180 / np.pi, ("km", "m"): 1000,
               ("ms", "s"): .001}
    if (source, target) not in factors:
        raise ValueError(f"Unsupported conversion: {source} -> {target}")
    return values * factors[source, target]


def column_spec(original: str, source: str) -> dict:
    h = normalize_header(original)
    base = h.split("(")[0].strip()
    name, unit, target, kind = None, None, None, "continuous"
    feature, policy = False, "metadata/diagnostics only"
    if not h:
        name, kind = "unused_trailing_column", "empty"
    elif source == "smartphone":
        gps = {"gps latitude": ("gps_latitude", "deg"), "gps longitude": ("gps_longitude", "deg"),
               "gps altitude": ("gps_altitude", "m"), "gps speed": ("gps_speed_raw", "km/h"),
               "gps accuracy": ("gps_accuracy", "m"), "gps orientation": ("gps_course", "deg")}
        if base in gps:
            name, unit = gps[base]
            policy = "exclude from blackout-model features; GNSS-available initialization/context only"
        elif "satellite" in h:
            name, kind = "gps_satellites_raw", "categorical"
        elif "time since start" in h:
            name, unit = "elapsed_counter", "ms"
            target = "s"
        elif base == "date":
            name, kind = "datetime_raw", "timestamp"
        else:
            for prefix, internal, measure in [("accelerometer", "accel", "m/s2"),
                    ("gravity", "gravity", "m/s2"), ("gyroscope", "gyro_channel", "rad/s"),
                    ("magnetic field", "magnetic", "uT")]:
                if base.startswith(prefix):
                    axis = base[len(prefix):].strip()
                    name, unit = f"{internal}_{axis}", measure
                    feature = True
                    policy = "candidate only after frame, timing, causal preprocessing and split validation"
                    break
            if base == "orientation":
                m = re.search(r"orientation\s*\(\s*(.*?)\s*\)", h)
                name, unit = "orientation_" + (m.group(1) if m else "unknown"), "deg"
                policy = "diagnostic only until source orientation/filter convention is verified"
    else:
        mapping = {
            "no of gps satellites available": ("reference_satellites_raw", None, "discrete"),
            "time since start of day": ("reference_time_of_day", "s", "timestamp"),
            "latitude": ("reference_latitude", "deg", "continuous"),
            "longitude": ("reference_longitude", "deg", "continuous"),
            "velocity": ("reference_speed", "km/h", "continuous"),
            "heading": ("reference_heading", "deg", "continuous"),
            "height": ("reference_height_raw", "km", "continuous"),
            "vertical velocity": ("reference_vertical_speed", "km/h", "continuous"),
            "sample period": ("reference_sample_period", "s", "continuous"),
            "steering angle": ("vehicle_steering_angle", "deg", "continuous"),
            "yaw rate": ("vehicle_yaw_rate", "deg/s", "continuous"),
            "indicated vehicle speed": ("vehicle_indicated_speed", "km/h", "continuous"),
            "indicated longitudinal acceleration": ("vehicle_accel_long", "g", "continuous"),
            "indicated lateral acceleration": ("vehicle_accel_lat", "g", "continuous"),
            "handbrake": ("vehicle_handbrake", "flag", "discrete"),
            "gear requested": ("vehicle_requested_gear", "gear", "discrete"),
            "gear": ("vehicle_gear", "gear", "discrete"),
            "engine speed": ("vehicle_engine_speed", "rpm", "continuous"),
            "coolant temperature": ("vehicle_coolant_temperature", "degC", "continuous"),
            "clutch position": ("vehicle_clutch", "flag", "discrete"),
            "brake pressure": ("vehicle_brake_pressure", "psi", "continuous"),
            "brake position": ("vehicle_brake", "flag", "discrete"),
            "battery voltage": ("vehicle_battery_voltage", "V", "continuous"),
            "air temperature": ("vehicle_air_temperature", "degC", "continuous"),
            "accelerator pedal position": ("vehicle_accelerator_pedal_raw", "flag", "continuous"),
        }
        if base in mapping:
            name, unit, kind = mapping[base]
        elif base.startswith("wheel speed "):
            name = "vehicle_wheel_speed_" + base.removeprefix("wheel speed ").replace(" ", "_")
            unit = "rad/s"
        policy = "reference/diagnostic only; never a deployable smartphone-model feature"
        target = {"km/h": "m/s", "g": "m/s2", "deg/s": "rad/s"}.get(unit, unit)
    if name is None:
        raise ValueError(f"Unregistered {source} column: {original!r}")
    if target is None:
        target = unit
    return {"original_name": original, "normalized_name": name, "documented_unit": unit,
            "normalized_unit": target, "empirically_inferred_unit": None,
            "unit_confidence": "unresolved; source label only" if unit else "not applicable",
            "evidence_label": "Stated by dataset documentation" if unit else "Confirmed from data",
            "measurement_type": kind, "permitted_as_training_feature": feature,
            "training_feature_policy": policy,
            "reference_only_ground_truth": source == "vbox" and name in {
                "reference_latitude", "reference_longitude", "reference_speed", "reference_heading"},
            "reference_or_diagnostic_only": source == "vbox"}


def observed_schema(headers: list[str], source: str, sample) -> list[dict]:
    """Preserve positional headers, explicitly record the observed S-A4 shift.

    S-A4 inserts an empty cell BEFORE satellites, but puts the extra header at
    the END. Detect the payload pattern, not just the misleading header width.
    Values are not altered; the registry retains both header and semantic names.
    """
    specs = [column_spec(h, source) for h in headers]
    for i, spec in enumerate(specs):
        spec.update(source_column_index=i, header_normalized_name=spec["normalized_name"],
                    mapping_evidence="Confirmed from data: header layout consistent unless flagged by observations")
    if source != "smartphone" or len(headers) != 25 or headers[-1].strip():
        return specs
    import pandas as pd
    dates = pd.to_datetime(sample.iloc[:, 9].astype(str).str.strip(), format="%Y-%m-%d %H:%M:%S:%f", errors="coerce")
    pattern = (len(sample) > 0 and sample.iloc[:, 6].isna().all()
               and sample.iloc[:, 7].astype(str).str.fullmatch(r"\s*\d+\s*/\s*\d+\s*").all()
               and pd.to_numeric(sample.iloc[:, 8], errors="coerce").notna().all() and dates.notna().all())
    if not pattern:
        raise ValueError("Ambiguous 25-column smartphone layout: expected S-A4 payload evidence not found")
    result = []
    for i in range(25):
        semantic = i if i < 6 else 24 if i == 6 else i - 1
        spec = dict(specs[semantic])
        spec.update(original_name=headers[i], source_column_index=i,
                    header_normalized_name=specs[i]["normalized_name"],
                    documented_unit=specs[i]["documented_unit"],
                    semantic_expected_unit=specs[semantic]["documented_unit"],
                    mapping_evidence="Inferred with supporting evidence: empty payload column 6, satellite ratio column 7, numeric counter column 8, valid datetime column 9; suffix shifted one place relative to headers",
                    permitted_as_training_feature=False,
                    training_feature_policy="quarantine: recovered S-A4 column shift requires independent structural review before training")
        if i == 6:
            spec["normalized_name"] = "unexpected_empty_column"
        result.append(spec)
    return result
