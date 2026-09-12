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


def find_column(df: pd.DataFrame, contains: str) -> str:
    """Find exactly one column containing a case-insensitive substring."""
    matches = [
        col for col in df.columns
        if contains.lower() in str(col).lower()
    ]

    if not matches:
        raise KeyError(
            f"No column containing {contains!r} was found.\n"
            f"Available columns:\n{list(df.columns)}"
        )

    if len(matches) > 1:
        print(f"\nMultiple columns matched {contains!r}:")
        for col in matches:
            print(f"  {repr(col)}")

    return matches[0]


def smartphone_time_analysis(df: pd.DataFrame) -> None:
    print("\n" + "=" * 100)
    print("SMARTPHONE TIME ANALYSIS")
    print("=" * 100)

    time_col = find_column(df, "TIME SINCE START")
    date_col = find_column(df, "DATE")

    print(f"\nUsing time column: {repr(time_col)}")
    print(f"Using date column: {repr(date_col)}")

    # ------------------------------------------------------------
    # TIME SINCE START
    # ------------------------------------------------------------

    time_values = pd.to_numeric(
        df[time_col],
        errors="coerce"
    )

    print("\nTIME SINCE START")
    print("-" * 100)

    print("First 10 values:")
    print(time_values.head(10).tolist())

    print("\nLast 10 values:")
    print(time_values.tail(10).tolist())

    time_diff = time_values.diff().dropna()

    print("\nInterval statistics (ms):")
    print(time_diff.describe().to_string())

    anomalies = time_diff[
        (time_diff <= 0) |
        (time_diff > 200)
    ]

    print(
        f"\nNon-standard intervals: {len(anomalies):,}"
    )

    if len(anomalies) > 0:
        print("\nFirst anomalies:")
        print(anomalies.head(20).to_string())

        print("\nRows around first anomalies:")

        for idx in anomalies.head(5).index:
            start = max(0, idx - 2)
            end = min(len(df), idx + 3)

            print(
                df.loc[
                    start:end,
                    [time_col, date_col]
                ].to_string()
            )

    # ------------------------------------------------------------
    # DATE
    # ------------------------------------------------------------

    print("\nDATE FIELD")
    print("-" * 100)

    print("First 10 raw values:")
    print(df[date_col].head(10).tolist())

    parsed = pd.to_datetime(
        df[date_col].astype(str).str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce"
    )

    valid_count = parsed.notna().sum()

    print(
        f"\nSuccessfully parsed: "
        f"{valid_count:,}/{len(df):,}"
    )

    if valid_count > 0:

        print(f"First parsed: {parsed.iloc[0]}")
        print(f"Last parsed:  {parsed.iloc[-1]}")

        date_diff = (
            parsed.diff()
            .dt.total_seconds()
            .dropna()
        )

        print("\nDATE interval statistics (seconds):")
        print(date_diff.describe().to_string())

        date_anomalies = date_diff[
            (date_diff <= 0) |
            (date_diff > 0.2)
        ]

        print(
            f"\nNon-standard DATE intervals: "
            f"{len(date_anomalies):,}"
        )

        if len(date_anomalies) > 0:
            print(
                date_anomalies.head(20).to_string()
            )


def vehicle_time_analysis(df: pd.DataFrame) -> None:
    print("\n" + "=" * 100)
    print("VEHICLE TIME ANALYSIS")
    print("=" * 100)

    time_col = find_column(
        df,
        "Time Since Start of Day"
    )

    sample_col = find_column(
        df,
        "Sample period"
    )

    print(f"\nUsing time column: {repr(time_col)}")
    print(f"Using sample period column: {repr(sample_col)}")

    time_values = pd.to_numeric(
        df[time_col],
        errors="coerce"
    )

    print("\nTIME SINCE START OF DAY")
    print("-" * 100)

    print("First 10 values:")
    print(time_values.head(10).tolist())

    print("\nLast 10 values:")
    print(time_values.tail(10).tolist())

    time_diff = time_values.diff().dropna()

    print("\nInterval statistics (seconds):")
    print(time_diff.describe().to_string())

    sample_period = pd.to_numeric(
        df[sample_col],
        errors="coerce"
    )

    print("\nSample period statistics:")
    print(sample_period.describe().to_string())


def satellite_analysis(df: pd.DataFrame) -> None:
    print("\n" + "=" * 100)
    print("SMARTPHONE SATELLITE FIELD")
    print("=" * 100)

    satellite_col = find_column(
        df,
        "SATELLITE"
    )

    print(f"Using column: {repr(satellite_col)}")
    print(f"Data type: {df[satellite_col].dtype}")

    print("\nFirst 30 values:")
    for i, value in enumerate(df[satellite_col].head(30)):
        print(f"{i:3d}: {value!r}")

    print("\nTop value counts:")
    print(
        df[satellite_col]
        .value_counts(dropna=False)
        .head(30)
        .to_string()
    )


def same_row_comparison(
    s: pd.DataFrame,
    v: pd.DataFrame
) -> None:

    print("\n" + "=" * 100)
    print("SAME-ROW COMPARISON")
    print("=" * 100)

    s_lat = find_column(s, "GPS LATITUDE")
    s_lon = find_column(s, "GPS LONGITUDE")
    s_speed = find_column(s, "GPS SPEED")

    v_lat = find_column(v, "Latitude")
    v_lon = find_column(v, "Longitude")
    v_speed = find_column(v, "Velocity")

    # ------------------------------------------------------------
    # Position
    # ------------------------------------------------------------

    lat_diff = (
        s[s_lat].to_numpy()
        - v[v_lat].to_numpy()
    )

    lon_diff = (
        s[s_lon].to_numpy()
        - v[v_lon].to_numpy()
    )

    print("\nLatitude difference:")
    print(
        pd.Series(lat_diff)
        .describe()
        .to_string()
    )

    print("\nLongitude difference:")
    print(
        pd.Series(lon_diff)
        .describe()
        .to_string()
    )

    # ------------------------------------------------------------
    # Speed
    # ------------------------------------------------------------

    speed_diff = (
        s[s_speed].to_numpy()
        - v[v_speed].to_numpy()
    )

    print("\nSmartphone GPS speed - Vehicle velocity:")
    print(
        pd.Series(speed_diff)
        .describe()
        .to_string()
    )

    # ------------------------------------------------------------
    # First 10 corresponding rows
    # ------------------------------------------------------------

    comparison = pd.DataFrame({
        "row": np.arange(10),
        "s_lat": s[s_lat].head(10).to_numpy(),
        "v_lat": v[v_lat].head(10).to_numpy(),
        "s_lon": s[s_lon].head(10).to_numpy(),
        "v_lon": v[v_lon].head(10).to_numpy(),
        "s_speed": s[s_speed].head(10).to_numpy(),
        "v_speed": v[v_speed].head(10).to_numpy(),
    })

    print("\nFirst 10 corresponding rows:")
    print(comparison.to_string(index=False))


def main() -> None:

    print("=" * 100)
    print("IO-VNBD SYNCHRONIZATION VERIFICATION")
    print("=" * 100)

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

    if len(s) == len(v):
        print("Result: row counts MATCH exactly.")
    else:
        print("Result: row counts DO NOT match.")

    smartphone_time_analysis(s)
    vehicle_time_analysis(v)
    satellite_analysis(s)
    same_row_comparison(s, v)


if __name__ == "__main__":
    main()