from pathlib import Path
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


def show_exact_columns(df: pd.DataFrame, name: str) -> None:
    print("\n" + "=" * 100)
    print(f"{name} — EXACT COLUMN NAMES")
    print("=" * 100)

    for i, column in enumerate(df.columns):
        print(f"{i:2d}: {repr(column)}")


def find_columns_containing(df: pd.DataFrame, text: str) -> None:
    matches = [
        column
        for column in df.columns
        if text.lower() in column.lower()
    ]

    print(f"\nColumns containing {text!r}:")
    if matches:
        for column in matches:
            print(f"  {repr(column)}")
    else:
        print("  None found.")


def inspect_smartphone(df: pd.DataFrame) -> None:
    print("\n" + "=" * 100)
    print("SMARTPHONE TIME ANALYSIS")
    print("=" * 100)

    find_columns_containing(df, "TIME")
    find_columns_containing(df, "DATE")

    print("\nFirst 10 rows of every time/date-related field:")

    for column in df.columns:
        if "time" in column.lower() or "date" in column.lower():
            print(f"\n{repr(column)}")
            print(df[column].head(10).tolist())

            if pd.api.types.is_numeric_dtype(df[column]):
                diff = df[column].diff().dropna()

                print("Difference statistics:")
                print(diff.describe().to_string())

            else:
                parsed = pd.to_datetime(
                    df[column],
                    errors="coerce"
                )

                valid = parsed.notna().sum()

                print(
                    f"Datetime values successfully parsed: "
                    f"{valid:,}/{len(parsed):,}"
                )

                if valid > 1:
                    date_diff = (
                        parsed.diff()
                        .dropna()
                        .dt.total_seconds()
                    )

                    print("Datetime interval statistics (seconds):")
                    print(date_diff.describe().to_string())


def inspect_vehicle(df: pd.DataFrame) -> None:
    print("\n" + "=" * 100)
    print("VEHICLE TIME ANALYSIS")
    print("=" * 100)

    find_columns_containing(df, "TIME")
    find_columns_containing(df, "SAMPLE")

    print("\nFirst 10 values for time-related fields:")

    for column in df.columns:
        if (
            "time" in column.lower()
            or "sample" in column.lower()
        ):
            print(f"\n{repr(column)}")
            print(df[column].head(10).tolist())

            if pd.api.types.is_numeric_dtype(df[column]):
                diff = df[column].diff().dropna()

                print("Difference statistics:")
                print(diff.describe().to_string())


def inspect_satellite_field(df: pd.DataFrame) -> None:
    print("\n" + "=" * 100)
    print("SMARTPHONE SATELLITE FIELD ANALYSIS")
    print("=" * 100)

    matches = [
        column
        for column in df.columns
        if "satellite" in column.lower()
    ]

    for column in matches:
        print(f"\nColumn: {repr(column)}")
        print(f"Data type: {df[column].dtype}")

        print("\nFirst 30 raw values:")
        for i, value in enumerate(df[column].head(30)):
            print(f"{i:3d}: {repr(value)}")

        print("\nTop value counts:")
        print(
            df[column]
            .value_counts(dropna=False)
            .head(30)
            .to_string()
        )


def main() -> None:

    print("=" * 100)
    print("IO-VNBD SYNCHRONIZATION ANALYSIS")
    print("=" * 100)

    if not S_FILE.exists():
        raise FileNotFoundError(
            f"Smartphone file not found:\n{S_FILE}"
        )

    if not V_FILE.exists():
        raise FileNotFoundError(
            f"Vehicle file not found:\n{V_FILE}"
        )

    s_df = pd.read_csv(
        S_FILE,
        encoding="cp1252"
    )

    v_df = pd.read_csv(
        V_FILE,
        encoding="utf-8"
    )

    print("\nROW COUNTS")
    print("-" * 100)
    print(f"S-M.csv: {len(s_df):,}")
    print(f"V-M.csv: {len(v_df):,}")

    print("\nCOLUMN COUNT")
    print("-" * 100)
    print(f"S-M.csv: {len(s_df.columns)}")
    print(f"V-M.csv: {len(v_df.columns)}")

    show_exact_columns(s_df, "S-M.csv")
    show_exact_columns(v_df, "V-M.csv")

    inspect_smartphone(s_df)
    inspect_vehicle(v_df)
    inspect_satellite_field(s_df)

    print("\n" + "=" * 100)
    print("ROW COUNT RELATIONSHIP")
    print("=" * 100)

    if len(s_df) == len(v_df):
        print("MATCH: S-M.csv and V-M.csv have identical row counts.")
    else:
        print("DO NOT MATCH: row counts are different.")

    print("\nFIRST ROW COMPARISON")
    print("-" * 100)

    print("\nS-M first row:")
    print(s_df.iloc[0].to_dict())

    print("\nV-M first row:")
    print(v_df.iloc[0].to_dict())


if __name__ == "__main__":
    main()