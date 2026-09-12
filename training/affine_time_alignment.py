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


def haversine_m(lat1, lon1, lat2, lon2):
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


def main():

    print("=" * 100)
    print("IO-VNBD AFFINE TIME ALIGNMENT ANALYSIS")
    print("=" * 100)

    # ------------------------------------------------------------
    # Load
    # ------------------------------------------------------------

    s = pd.read_csv(
        S_FILE,
        encoding="cp1252"
    )

    v = pd.read_csv(
        V_FILE,
        encoding="utf-8"
    )

    # ------------------------------------------------------------
    # Resolve columns
    # ------------------------------------------------------------

    s_date = next(
        c for c in s.columns
        if "date" in c.lower()
    )

    s_lat = next(
        c for c in s.columns
        if "gps latitude" in c.lower()
    )

    s_lon = next(
        c for c in s.columns
        if "gps longitude" in c.lower()
    )

    v_time = next(
        c for c in v.columns
        if "time since start of day" in c.lower()
    )

    v_lat = next(
        c for c in v.columns
        if c.strip().lower() == "latitude (degrees)"
    )

    v_lon = next(
        c for c in v.columns
        if c.strip().lower() == "longitude (degrees)"
    )

    # ------------------------------------------------------------
    # Smartphone elapsed time
    # ------------------------------------------------------------

    smartphone_datetime = pd.to_datetime(
        s[s_date].astype(str).str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    if smartphone_datetime.isna().any():
        raise ValueError(
            "Some smartphone timestamps failed to parse."
        )

    smartphone_t = (
        smartphone_datetime
        - smartphone_datetime.iloc[0]
    ).dt.total_seconds().to_numpy()

    # ------------------------------------------------------------
    # Vehicle elapsed time
    # ------------------------------------------------------------

    vehicle_clock = pd.to_numeric(
        v[v_time],
        errors="coerce"
    ).to_numpy()

    vehicle_t = (
        vehicle_clock
        - vehicle_clock[0]
    )

    # ------------------------------------------------------------
    # Endpoint-derived affine mapping
    #
    # Force the first timestamps to correspond and the final
    # timestamps to correspond.
    #
    # t_vehicle = a * t_smartphone + b
    # ------------------------------------------------------------

    t_s0 = smartphone_t[0]
    t_s1 = smartphone_t[-1]

    t_v0 = vehicle_t[0]
    t_v1 = vehicle_t[-1]

    a_endpoint = (
        (t_v1 - t_v0)
        / (t_s1 - t_s0)
    )

    b_endpoint = (
        t_v0
        - a_endpoint * t_s0
    )

    mapped_vehicle_t = (
        a_endpoint * smartphone_t
        + b_endpoint
    )

    print("\nENDPOINT-DERIVED AFFINE MAPPING")
    print("-" * 100)

    print(f"a = {a_endpoint:.12f}")
    print(f"b = {b_endpoint:.12f}")

    print(
        f"Clock-rate difference ≈ "
        f"{(1.0 - a_endpoint) * 1_000_000:.3f} ppm"
    )

    # ------------------------------------------------------------
    # GPS arrays
    # ------------------------------------------------------------

    smartphone_lat = pd.to_numeric(
        s[s_lat],
        errors="coerce"
    ).to_numpy()

    smartphone_lon = pd.to_numeric(
        s[s_lon],
        errors="coerce"
    ).to_numpy()

    vehicle_lat = pd.to_numeric(
        v[v_lat],
        errors="coerce"
    ).to_numpy()

    vehicle_lon = pd.to_numeric(
        v[v_lon],
        errors="coerce"
    ).to_numpy()

    # ------------------------------------------------------------
    # Interpolate vehicle trajectory onto smartphone time axis
    # ------------------------------------------------------------

    valid_vehicle = (
        np.isfinite(vehicle_t)
        & np.isfinite(vehicle_lat)
        & np.isfinite(vehicle_lon)
    )

    vt = vehicle_t[valid_vehicle]
    vlat = vehicle_lat[valid_vehicle]
    vlon = vehicle_lon[valid_vehicle]

    valid_target = (
        np.isfinite(mapped_vehicle_t)
        & (
            mapped_vehicle_t >= vt.min()
        )
        & (
            mapped_vehicle_t <= vt.max()
        )
    )

    interp_lat = np.interp(
        mapped_vehicle_t[valid_target],
        vt,
        vlat,
    )

    interp_lon = np.interp(
        mapped_vehicle_t[valid_target],
        vt,
        vlon,
    )

    distances = haversine_m(
        smartphone_lat[valid_target],
        smartphone_lon[valid_target],
        interp_lat,
        interp_lon,
    )

    # ------------------------------------------------------------
    # Report affine result
    # ------------------------------------------------------------

    print("\nAFFINE-MAPPED POSITION ERROR")
    print("-" * 100)

    stats = pd.Series(distances).describe()

    print(stats.to_string())

    print(
        f"\nMedian: "
        f"{np.median(distances):.3f} m"
    )

    print(
        f"Mean:   "
        f"{np.mean(distances):.3f} m"
    )

    print(
        f"95th percentile: "
        f"{np.percentile(distances, 95):.3f} m"
    )

    print(
        f"Maximum: "
        f"{np.max(distances):.3f} m"
    )

    # ------------------------------------------------------------
    # Selected rows
    # ------------------------------------------------------------

    print("\nSELECTED ROWS")
    print("-" * 100)

    indices = [
        0,
        len(s) // 4,
        len(s) // 2,
        3 * len(s) // 4,
        len(s) - 1,
    ]

    for idx in indices:

        print(f"\nRow {idx:,}")
        print(
            f"Smartphone elapsed: "
            f"{smartphone_t[idx]:.3f} s"
        )

        print(
            f"Mapped vehicle time: "
            f"{mapped_vehicle_t[idx]:.3f} s"
        )

        print(
            f"Original vehicle elapsed: "
            f"{vehicle_t[idx]:.3f} s"
        )

        print(
            f"Difference: "
            f"{mapped_vehicle_t[idx] - vehicle_t[idx]:.6f} s"
        )


if __name__ == "__main__":
    main()