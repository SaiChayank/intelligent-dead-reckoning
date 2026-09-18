"""Side-effect-free IO-VNBD utilities shared by offline diagnostics.

No dataset is opened or validated at import time. Unit conversion and physical
axis interpretation remain explicit decisions in the calling analysis.
"""

from __future__ import annotations

import argparse
import csv
import re
import unicodedata
from pathlib import Path

import numpy as np
import pandas as pd

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA_ROOT = PROJECT_ROOT / "data" / "raw" / "iovnbd"
SYNC_DIRECTORY = "Synchronised V abd S datasets"
M_SEQUENCE = Path(SYNC_DIRECTORY) / "Categorised IOVNB Dataset" / "M (Driver B)"
EARTH_RADIUS_M = 6_371_000.0


def resolve_dataset_root(value: str | Path | None = None) -> Path:
    """Use an explicit root (relative to cwd), otherwise the repository default.

    Never search for or silently select another dataset. Existing directory
    names, including iovnbd_git, work through an explicit --data-root override.
    """
    root = (Path(value).expanduser() if value is not None else DEFAULT_DATA_ROOT).resolve()
    if not (root / SYNC_DIRECTORY).is_dir():
        raise FileNotFoundError(
            f"IO-VNBD dataset not found at: {root}\n"
            "Pass --data-root PATH to the extracted dataset, not a CSV or archive.\n"
            "Expected structure (the spelling 'abd' is from IO-VNBD):\n"
            f"  {root}/\n"
            f"    {SYNC_DIRECTORY}/\n"
            "      Categorised IOVNB Dataset/M (Driver B)/S-M.csv\n"
            "      Categorised IOVNB Dataset/M (Driver B)/V-M.csv\n"
            "    Unsynchronised V and S Dataset/  (needed for the full audit)"
        )
    return root


def sequence_paths(value: str | Path | None = None) -> tuple[Path, Path, Path]:
    """Resolve and validate the M pair used by the legacy diagnostics."""
    root = resolve_dataset_root(value)
    smartphone, vbox = root / M_SEQUENCE / "S-M.csv", root / M_SEQUENCE / "V-M.csv"
    missing = [str(p) for p in (smartphone, vbox) if not p.is_file()]
    if missing:
        raise FileNotFoundError(
            "Missing synchronized M (Driver B) files:\n  " + "\n  ".join(missing)
            + "\nUse --data-root PATH containing the original IO-VNBD directory structure."
        )
    return root, smartphone, vbox


def add_dataset_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--data-root", type=Path, default=None, metavar="PATH",
        help=f"Extracted IO-VNBD root (default: {DEFAULT_DATA_ROOT}); no renaming required.",
    )


def dataset_parser(description: str) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=description)
    add_dataset_argument(parser)
    return parser


def m_pair_from_args(parser, args) -> tuple[Path, Path, Path]:
    try:
        return sequence_paths(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))


def detect_encoding(path: Path, sample_chars: int = 20000) -> str:
    """Choose the first decoding that accepts a bounded prefix, not a guess of origin.

    This preserves the legacy UTF-8/cp1252/latin-1 order. A successful prefix
    decode cannot guarantee that a mixed-encoding remainder will decode.
    """
    for encoding in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            with Path(path).open(encoding=encoding) as stream:
                stream.read(sample_chars)
            return encoding
        except UnicodeDecodeError:
            continue
    raise ValueError(f"Cannot decode {path}")


def read_header(path: Path) -> tuple[str, list[str]]:
    """Preserve Phase 0's original decoded CSV labels and BOM handling."""
    for encoding in ("utf-8-sig", "cp1252", "latin-1"):
        try:
            with Path(path).open(encoding=encoding, newline="") as stream:
                return encoding, next(csv.reader(stream))
        except UnicodeDecodeError:
            continue
    raise ValueError(f"Cannot decode {path}")


def normalize_header(value: object) -> str:
    """Normalize spelling/encoding artifacts, never infer a sensor axis or unit."""
    text = str(value).lstrip("\ufeff").strip()
    for wrong, right in [("Â°", "°"), ("Î¼", "μ"), ("Â²", "²")]:
        text = text.replace(wrong, right)
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).lower()


def normalize_name(value: object) -> str:
    """Conservative legacy matcher; unlike normalize_header, retains unit glyphs."""
    return str(value).replace("\ufeff", "").strip().lower()


def safe_numeric(values, *, finite_only: bool = False) -> np.ndarray:
    """Return an independent float array; invalid/missing values become NaN.

    Infinity is preserved by default for legacy compatibility. Callers may
    explicitly request finite-only values. No imputation or unit scaling occurs.
    """
    result = np.array(pd.to_numeric(values, errors="coerce"), dtype=float, copy=True)
    if finite_only:
        result[~np.isfinite(result)] = np.nan
    return result


def valid_coordinates(lat, lon):
    lat, lon = np.asarray(lat, float), np.asarray(lon, float)
    return (np.isfinite(lat) & np.isfinite(lon) & (abs(lat) <= 90) & (abs(lon) <= 180)
            & ~((lat == 0) & (lon == 0)))


def haversine(lat1, lon1, lat2, lon2):
    """Spherical surface distance in metres; latitude/longitude inputs in degrees."""
    lat1, lon1, lat2, lon2 = [np.radians(a) for a in (lat1, lon1, lat2, lon2)]
    a = np.sin((lat2 - lat1) / 2) ** 2 + np.cos(lat1) * np.cos(lat2) * np.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.clip(a, 0, 1)))
