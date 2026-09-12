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


def find_column(df: pd.DataFrame, keyword: str, exact_preference: str | None = None) -> str:
    """
    Find a column using a keyword.

    If exact_preference is supplied and exists, use it.
    Otherwise search case-insensitively.
    """
    # First try exact match.
    if exact_preference is not None:
        for column in df.columns:
            if column == exact_preference:
                return column

    matches = [
        column
        for column in df.columns
        if keyword.lower() in str(column).lower()
    ]

    if not matches:
        raise KeyError(
            f"Could not find a column containing {keyword!r}.\n"
            f"Available columns:\n{list(df.columns)}"
        )

    if len(matches) > 1:
        print(f"\nMultiple columns matched {keyword!r}:")
        for column in matches:
            print(f"  {repr(column)}")

    return matches[0]


def haversine_m(
    lat1: np.ndarray,
    lon1: np.ndarray,
    lat2: np.ndarray,
    lon2: np.ndarray,
) -> np.ndarray:
    """Calculate point-to-point great-circle distance in meters."""

    earth_radius = 6_371_000.0

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

    return 2.0 * earth_radius * np.arcsin(
        np.sqrt(a)
    )


def main() -> None:

    print("=" * 100)
    print("FINAL IO-VNBD ALIGNMENT CHECK")
    print("=" * 100)

    if not S_FILE.exists():
        raise FileNotFoundError(f"Smartphone file not found:\n{S_FILE}")

    if not V_FILE.exists():
        raise FileNotFoundError(f"Vehicle file not found:\n{V_FILE}")

    s = pd.read_csv(
        S_FILE,
        encoding="cp1252"
    )

    v = pd.read_csv(
        V_FILE,
        encoding="utf-8"
    )

    print("\nROW COUNTS")
    print("-" * 100)
    print(f"Smartphone: {len(s):,}")
    print(f"Vehicle:    {len(v):,}")

    if len(s) != len(v):
        raise ValueError(
            "The smartphone and vehicle files have different row counts."
        )

    # ------------------------------------------------------------------
    # Resolve actual columns from the loaded files
    # ------------------------------------------------------------------

    s_date_col = find_column(s, "DATE")
    s_lat_col = find_column(s, "GPS LATITUDE")
    s_lon_col = find_column(s, "GPS LONGITUDE")
    s_speed_col = find_column(s, "GPS SPEED")

    v_time_col = find_column(v, "Time Since Start of Day")
    v_lat_col = find_column(v, "Latitude")
    v_lon_col = find_column(v, "Longitude")
    v_speed_col = find_column(
        v,
        "Velocity (km/hr)"
    )
    v_sample_col = find_column(v, "Sample period")

    print("\nRESOLVED COLUMNS")
    print("-" * 100)

    print(f"Smartphone DATE:      {repr(s_date_col)}")
    print(f"Smartphone latitude:  {repr(s_lat_col)}")
    print(f"Smartphone longitude: {repr(s_lon_col)}")
    print(f"Smartphone speed:     {repr(s_speed_col)}")

    print(f"Vehicle time:         {repr(v_time_col)}")
    print(f"Vehicle latitude:     {repr(v_lat_col)}")
    print(f"Vehicle longitude:    {repr(v_lon_col)}")
    print(f"Vehicle velocity:     {repr(v_speed_col)}")
    print(f"Vehicle sample:      {repr(v_sample_col)}")

    # ------------------------------------------------------------------
    # Smartphone datetime
    # ------------------------------------------------------------------

    smartphone_dt = pd.to_datetime(
        s[s_date_col].astype(str).str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    valid = smartphone_dt.notna().sum()

    print("\nSMARTPHONE DATE")
    print("-" * 100)

    print(
        f"Successfully parsed: {valid:,}/{len(s):,}"
    )

    if valid != len(s):
        raise ValueError(
            "Some smartphone DATE values could not be parsed."
        )

    print(
        f"First: {smartphone_dt.iloc[0]}"
    )

    print(
        f"Last:  {smartphone_dt.iloc[-1]}"
    )

    # ------------------------------------------------------------------
    # Smartphone DATE intervals
    # ------------------------------------------------------------------

    smartphone_intervals = (
        smartphone_dt.diff()
        .dt.total_seconds()
        .dropna()
    )

    print("\nSMARTPHONE DATE INTERVALS")
    print("-" * 100)
    print(
        smartphone_intervals.describe()
        .to_string()
    )

    date_anomalies = smartphone_intervals[
        (smartphone_intervals <= 0)
        | (smartphone_intervals > 0.2)
    ]

    print(
        f"\nNon-standard intervals: {len(date_anomalies):,}"
    )

    if len(date_anomalies) > 0:
        print(
            date_anomalies.head(20)
            .to_string()
        )

    # ------------------------------------------------------------------
    # Vehicle time
    # ------------------------------------------------------------------

    vehicle_seconds = pd.to_numeric(
        v[v_time_col],
        errors="coerce"
    )

    if vehicle_seconds.isna().any():
        raise ValueError(
            "Vehicle time column contains non-numeric values."
        )

    base_date = smartphone_dt.iloc[0].normalize()

    vehicle_dt = (
        base_date
        + pd.to_timedelta(
            vehicle_seconds,
            unit="s"
        )
    )

    print("\nVEHICLE TIME")
    print("-" * 100)

    print(
        f"First: {vehicle_dt.iloc[0]}"
    )

    print(
        f"Last:  {vehicle_dt.iloc[-1]}"
    )

    vehicle_intervals = (
        vehicle_seconds.diff()
        .dropna()
    )

    print("\nVehicle time interval statistics:")
    print(
        vehicle_intervals.describe()
        .to_string()
    )

    # ------------------------------------------------------------------
    # Timestamp offset
    # ------------------------------------------------------------------

    offset_seconds = (
        smartphone_dt
        - vehicle_dt
    ).dt.total_seconds()

    print("\nTIMESTAMP OFFSET")
    print("-" * 100)

    print(
        "Definition: smartphone DATE - vehicle reconstructed time"
    )

    print("\nOffset statistics:")
    print(
        offset_seconds.describe()
        .to_string()
    )

    median_offset = offset_seconds.median()

    print(
        f"\nMedian offset: {median_offset:.6f} seconds"
    )

    print(
        f"Median offset: {median_offset / 3600:.6f} hours"
    )

    offset_error = (
        offset_seconds
        - median_offset
    )

    print("\nOffset error after removing median:")
    print(
        offset_error.describe()
        .to_string()
    )

    # ------------------------------------------------------------------
    # GPS position comparison
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

    distance_m = haversine_m(
        s_lat,
        s_lon,
        v_lat,
        v_lon
    )

    print("\nSAME-ROW POSITION SEPARATION")
    print("-" * 100)

    print(
        pd.Series(distance_m)
        .describe()
        .to_string()
    )

    print(
        f"\nMedian: {np.median(distance_m):.6f} m"
    )

    print(
        f"Mean:   {np.mean(distance_m):.6f} m"
    )

    print(
        f"95th percentile: "
        f"{np.percentile(distance_m, 95):.6f} m"
    )

    print(
        f"Maximum: "
        f"{np.max(distance_m):.6f} m"
    )

    # ------------------------------------------------------------------
    # Correct velocity comparison
    # ------------------------------------------------------------------

    smartphone_speed = pd.to_numeric(
        s[s_speed_col],
        errors="coerce"
    )

    vehicle_speed = pd.to_numeric(
        v[v_speed_col],
        errors="coerce"
    )

    speed_difference = (
        smartphone_speed.to_numpy()
        - vehicle_speed.to_numpy()
    )

    print("\nSPEED COMPARISON")
    print("-" * 100)

    print(
        f"Smartphone: {repr(s_speed_col)}"
    )

    print(
        f"Vehicle:    {repr(v_speed_col)}"
    )

    print("\nDifference statistics:")
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

    print("\nVEHICLE SAMPLE PERIOD")
    print("-" * 100)

    print(
        sample_period.describe()
        .to_string()
    )

    # ------------------------------------------------------------------
    # Selected rows
    # ------------------------------------------------------------------

    selected_indices = [
        0,
        len(s) // 4,
        len(s) // 2,
        3 * len(s) // 4,
        len(s) - 1,
    ]

    print("\nSELECTED ROW ALIGNMENT CHECK")
    print("-" * 100)

    for idx in selected_indices:

        print(f"\nRow {idx:,}")

        print(
            f"Smartphone time: {smartphone_dt.iloc[idx]}"
        )

        print(
            f"Vehicle time:    {vehicle_dt.iloc[idx]}"
        )

        print(
            f"Offset:          {offset_seconds.iloc[idx]:.6f} s"
        )

        print(
            f"Position separation: {distance_m[idx]:.6f} m"
        )

        print(
            f"Smartphone speed: {smartphone_speed.iloc[idx]:.3f} km/h"
        )

        print(
            f"Vehicle velocity:  {vehicle_speed.iloc[idx]:.3f} km/h"
        )


if __name__ == "__main__":
    main()