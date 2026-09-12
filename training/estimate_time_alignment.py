from pathlib import Path

import numpy as np
import pandas as pd


DATA_ROOT = Path("data/raw/iovnbd_git")

BASE = (
    DATA_ROOT
    / "Synchronised V abd S datasets"
    / "Categorised IOVNB Dataset"
    / "M (Driver B)"
)

S_FILE = BASE / "S-M.csv"
V_FILE = BASE / "V-M.csv"

EARTH_RADIUS_M = 6_371_000.0


def find_column(
    df: pd.DataFrame,
    keywords: list[str],
    preferred: str | None = None,
) -> str:
    """
    Robustly locate a column without relying on fragile exact
    string matching.

    Matching is case-insensitive and ignores leading/trailing
    whitespace.
    """

    normalized = {
        str(col).strip().lower(): col
        for col in df.columns
    }

    # Try preferred name first after normalization.
    if preferred is not None:
        preferred_key = preferred.strip().lower()

        if preferred_key in normalized:
            return normalized[preferred_key]

    # Search using all keywords.
    candidates = []

    for col in df.columns:
        name = str(col).strip().lower()

        if all(keyword.lower() in name for keyword in keywords):
            candidates.append(col)

    if not candidates:
        raise KeyError(
            f"Could not find a column matching keywords: {keywords}\n"
            f"Available columns:\n"
            + "\n".join(
                f"  {repr(c)}" for c in df.columns
            )
        )

    if len(candidates) > 1:
        print(
            f"\nMultiple columns matched {keywords}:"
        )

        for candidate in candidates:
            print(f"  {repr(candidate)}")

    return candidates[0]


def haversine_m(
    lat1: np.ndarray,
    lon1: np.ndarray,
    lat2: np.ndarray,
    lon2: np.ndarray,
) -> np.ndarray:
    """Vectorized great-circle distance in meters."""

    lat1 = np.radians(lat1)
    lon1 = np.radians(lon1)
    lat2 = np.radians(lat2)
    lon2 = np.radians(lon2)

    dlat = lat2 - lat1
    dlon = lon2 - lon1

    a = (
        np.sin(dlat / 2.0) ** 2
        + np.cos(lat1)
        * np.cos(lat2)
        * np.sin(dlon / 2.0) ** 2
    )

    return (
        2.0
        * EARTH_RADIUS_M
        * np.arcsin(np.sqrt(a))
    )


def main() -> None:

    print("=" * 100)
    print("IO-VNBD TIME ALIGNMENT SEARCH")
    print("=" * 100)

    if not S_FILE.exists():
        raise FileNotFoundError(
            f"Smartphone file not found:\n{S_FILE}"
        )

    if not V_FILE.exists():
        raise FileNotFoundError(
            f"Vehicle file not found:\n{V_FILE}"
        )

    # ------------------------------------------------------------------
    # Load files
    # ------------------------------------------------------------------

    print("\nLoading smartphone data...")
    s = pd.read_csv(
        S_FILE,
        encoding="cp1252"
    )

    print("Loading vehicle data...")
    v = pd.read_csv(
        V_FILE,
        encoding="utf-8"
    )

    print("\nRows:")
    print(f"  Smartphone: {len(s):,}")
    print(f"  Vehicle:    {len(v):,}")

    if len(s) != len(v):
        raise ValueError(
            "Smartphone and vehicle row counts differ."
        )

    # ------------------------------------------------------------------
    # Resolve columns robustly
    # ------------------------------------------------------------------

    s_date_col = find_column(
        s,
        ["date"],
    )

    s_lat_col = find_column(
        s,
        ["gps", "latitude"],
    )

    s_lon_col = find_column(
        s,
        ["gps", "longitude"],
    )

    s_speed_col = find_column(
        s,
        ["gps", "speed"],
    )

    v_time_col = find_column(
        v,
        ["time", "start", "day"],
    )

    v_lat_col = find_column(
        v,
        ["latitude"],
    )

    v_lon_col = find_column(
        v,
        ["longitude"],
    )

    v_speed_col = find_column(
        v,
        ["velocity"],
        preferred="Velocity (km/hr)",
    )

    v_sample_col = find_column(
        v,
        ["sample", "period"],
    )

    print("\nRESOLVED COLUMNS")
    print("-" * 100)

    print(f"Smartphone DATE      : {repr(s_date_col)}")
    print(f"Smartphone latitude  : {repr(s_lat_col)}")
    print(f"Smartphone longitude : {repr(s_lon_col)}")
    print(f"Smartphone speed     : {repr(s_speed_col)}")

    print(f"Vehicle time         : {repr(v_time_col)}")
    print(f"Vehicle latitude     : {repr(v_lat_col)}")
    print(f"Vehicle longitude    : {repr(v_lon_col)}")
    print(f"Vehicle velocity     : {repr(v_speed_col)}")
    print(f"Vehicle sample       : {repr(v_sample_col)}")

    # ------------------------------------------------------------------
    # Smartphone absolute time
    # ------------------------------------------------------------------

    smartphone_time = pd.to_datetime(
        s[s_date_col].astype(str).str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    if smartphone_time.isna().any():
        bad_count = int(smartphone_time.isna().sum())

        raise ValueError(
            f"{bad_count} smartphone timestamps could not be parsed."
        )

    smartphone_elapsed = (
        smartphone_time
        - smartphone_time.iloc[0]
    ).dt.total_seconds().to_numpy()

    # ------------------------------------------------------------------
    # Vehicle elapsed time
    # ------------------------------------------------------------------

    vehicle_time_of_day = pd.to_numeric(
        v[v_time_col],
        errors="coerce"
    )

    if vehicle_time_of_day.isna().any():
        raise ValueError(
            "Vehicle time column contains non-numeric values."
        )

    vehicle_elapsed = (
        vehicle_time_of_day
        - vehicle_time_of_day.iloc[0]
    ).to_numpy()

    # ------------------------------------------------------------------
    # Duration comparison
    # ------------------------------------------------------------------

    print("\nRECORDING DURATIONS")
    print("-" * 100)

    print(
        f"Smartphone duration: "
        f"{smartphone_elapsed[-1]:.3f} s"
    )

    print(
        f"Vehicle duration:    "
        f"{vehicle_elapsed[-1]:.3f} s"
    )

    print(
        f"Duration difference: "
        f"{smartphone_elapsed[-1] - vehicle_elapsed[-1]:.3f} s"
    )

    # ------------------------------------------------------------------
    # GPS arrays
    # ------------------------------------------------------------------

    s_lat = pd.to_numeric(
        s[s_lat_col],
        errors="coerce"
    ).to_numpy()

    s_lon = pd.to_numeric(
        s[s_lon_col],
        errors="coerce"
    ).to_numpy()

    v_lat = pd.to_numeric(
        v[v_lat_col],
        errors="coerce"
    ).to_numpy()

    v_lon = pd.to_numeric(
        v[v_lon_col],
        errors="coerce"
    ).to_numpy()

    # ------------------------------------------------------------------
    # Search constant time offsets
    #
    # Positive offset means:
    #
    # vehicle time = smartphone elapsed time + offset
    # ------------------------------------------------------------------

    candidate_offsets = np.arange(
        -10.0,
        10.0001,
        0.1
    )

    valid_vehicle = (
        np.isfinite(vehicle_elapsed)
        & np.isfinite(v_lat)
        & np.isfinite(v_lon)
    )

    vehicle_t = vehicle_elapsed[valid_vehicle]
    vehicle_lat = v_lat[valid_vehicle]
    vehicle_lon = v_lon[valid_vehicle]

    results = []

    print("\nSearching candidate time offsets...")

    for offset in candidate_offsets:

        target_vehicle_time = (
            smartphone_elapsed + offset
        )

        valid = (
            np.isfinite(target_vehicle_time)
            & (
                target_vehicle_time >= vehicle_t.min()
            )
            & (
                target_vehicle_time <= vehicle_t.max()
            )
        )

        if valid.sum() < 1000:
            continue

        interpolated_lat = np.interp(
            target_vehicle_time[valid],
            vehicle_t,
            vehicle_lat,
        )

        interpolated_lon = np.interp(
            target_vehicle_time[valid],
            vehicle_t,
            vehicle_lon,
        )

        distances = haversine_m(
            s_lat[valid],
            s_lon[valid],
            interpolated_lat,
            interpolated_lon,
        )

        results.append({
            "offset_seconds": offset,
            "median_error_m": np.median(distances),
            "mean_error_m": np.mean(distances),
            "p95_error_m": np.percentile(distances, 95),
        })

    results_df = pd.DataFrame(results)

    if results_df.empty:
        raise RuntimeError(
            "No valid candidate offsets were found."
        )

    results_df = results_df.sort_values(
        "median_error_m"
    )

    # ------------------------------------------------------------------
    # Results
    # ------------------------------------------------------------------

    print("\n" + "=" * 100)
    print("BEST CONSTANT TIME OFFSETS")
    print("=" * 100)

    print(
        results_df.head(20).to_string(
            index=False
        )
    )

    best = results_df.iloc[0]

    print("\n" + "=" * 100)
    print("BEST CONSTANT OFFSET")
    print("=" * 100)

    print(
        f"Offset:          "
        f"{best['offset_seconds']:.3f} s"
    )

    print(
        f"Median error:    "
        f"{best['median_error_m']:.3f} m"
    )

    print(
        f"Mean error:      "
        f"{best['mean_error_m']:.3f} m"
    )

    print(
        f"95th percentile: "
        f"{best['p95_error_m']:.3f} m"
    )

    # ------------------------------------------------------------------
    # Correct velocity comparison
    # ------------------------------------------------------------------

    smartphone_speed = pd.to_numeric(
        s[s_speed_col],
        errors="coerce"
    ).to_numpy()

    vehicle_speed = pd.to_numeric(
        v[v_speed_col],
        errors="coerce"
    ).to_numpy()

    speed_difference = (
        smartphone_speed
        - vehicle_speed
    )

    print("\n" + "=" * 100)
    print("RAW SAME-ROW SPEED COMPARISON")
    print("=" * 100)

    print(
        pd.Series(speed_difference)
        .describe()
        .to_string()
    )

    # ------------------------------------------------------------------
    # Sample period
    # ------------------------------------------------------------------

    sample_period = pd.to_numeric(
        v[v_sample_col],
        errors="coerce"
    )

    print("\n" + "=" * 100)
    print("VEHICLE SAMPLE PERIOD")
    print("=" * 100)

    print(
        sample_period.describe()
        .to_string()
    )


if __name__ == "__main__":
    main()