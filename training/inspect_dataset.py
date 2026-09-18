from pathlib import Path
import pandas as pd


if __package__:
    from .common import dataset_parser, m_pair_from_args, detect_encoding
else:
    from common import dataset_parser, m_pair_from_args, detect_encoding


REPORT_DIR = Path("reports")
REPORT_FILE = REPORT_DIR / "data_schema_raw.txt"


def inspect_csv(path: Path) -> str:
    output = []

    output.append("=" * 100)
    output.append(f"FILE: {path}")
    output.append("=" * 100)

    if not path.exists():
        output.append(f"ERROR: FILE NOT FOUND: {path}")
        return "\n".join(output)

    encoding = detect_encoding(path, sample_chars=10000)
    output.append(f"\nEncoding: {encoding}")

    df = pd.read_csv(path, encoding=encoding)

    output.append(f"\nRows: {len(df):,}")
    output.append(f"Columns: {len(df.columns)}")

    output.append("\nCOLUMN NAMES")
    output.append("-" * 100)

    for i, column in enumerate(df.columns):
        output.append(f"{i:3d} | {column}")

    output.append("\nDATA TYPES")
    output.append("-" * 100)
    output.append(df.dtypes.to_string())

    output.append("\nFIRST 5 ROWS")
    output.append("-" * 100)
    output.append(df.head().to_string())

    output.append("\nLAST 5 ROWS")
    output.append("-" * 100)
    output.append(df.tail().to_string())

    output.append("\nMISSING VALUES")
    output.append("-" * 100)

    missing = df.isna().sum()

    for column, count in missing.items():
        if count > 0:
            percentage = count / len(df) * 100
            output.append(
                f"{column}: {count:,} "
                f"({percentage:.3f}%)"
            )

    if missing.sum() == 0:
        output.append("No missing values detected.")

    output.append("\nNUMERIC SUMMARY")
    output.append("-" * 100)
    output.append(df.describe().transpose().to_string())

    return "\n".join(output)


def main() -> None:
    parser = dataset_parser("IO-VNBD inspect dataset (M, Driver B)")
    parser.add_argument("--no-write", action="store_true", help="Print results without saving a report.")
    args = parser.parse_args()
    _, S_FILE, V_FILE = m_pair_from_args(parser, args)

    print("IO-VNBD DATASET INSPECTION")
    print("=" * 100)

    sections = [
        inspect_csv(S_FILE),
        inspect_csv(V_FILE),
    ]

    final_report = "\n\n".join(sections)

    if not args.no_write:
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        REPORT_FILE.write_text(final_report, encoding="utf-8")

    print(final_report)

    print("\n" + "=" * 100)
    if not args.no_write:
        print(f"REPORT SAVED TO: {REPORT_FILE}")
    print("=" * 100)


if __name__ == "__main__":
    main()
