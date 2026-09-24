"""Explicit developer-only download/build; never called by Android or a Gradle task.

OpenFreeMap permits unlimited requests and provides downloadable OSM-derived data.
This bounded extract is NOT downloaded from tile.openstreetmap.org.
Generated binary/data artifacts are written only to mobile/app/src/main/assets/offline.
"""
import argparse
import concurrent.futures
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import urllib.request

BOUNDS = (78.35, 17.30, 78.60, 17.55)  # west, south, east, north; central Hyderabad only
TILES = "https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf"
ROOT = Path(__file__).resolve().parents[1] / "app/src/main/assets/offline/hyderabad"
FONT = "Noto Sans Regular"
RANGES = ("0-255", "256-511", "512-767", "768-1023")


def xy(lon, lat, z):
    n = 1 << z
    return int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)


def tile_keys():
    west, south, east, north = BOUNDS
    for z in range(10, 15):
        x0, y0 = xy(west, north, z)
        x1, y1 = xy(east, south, z)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                yield z, x, y


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "IDR-offline-pack-builder/1.0 (bounded Hyderabad research prototype)"})
    with urllib.request.urlopen(request, timeout=45) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    if not data or len(data) > 4 * 1024 * 1024:
        raise ValueError("Empty/oversized map resource")
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download", action="store_true", help="Explicitly download the bounded pack")
    args = parser.parse_args()
    keys = list(tile_keys())
    print(json.dumps({"coverage": BOUNDS, "zoom": [10, 14], "tiles": len(keys), "destination": str(ROOT)}))
    if not args.download:
        return
    if (ROOT / "manifest.json").exists():
        raise SystemExit("Pack already exists; refusing to overwrite. Review/version an intentional refresh separately.")
    ROOT.mkdir(parents=True, exist_ok=True)
    db_path = ROOT / "hyderabad.mbtiles"
    if db_path.exists():
        raise SystemExit("Partial MBTiles already exists; inspect it before retrying. No automatic overwrite.")
    with sqlite3.connect(db_path) as db:
        db.executescript("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT); CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row));")
        info = {"name": "Hyderabad central prototype", "format": "pbf", "type": "baselayer", "version": "1.3",
                "minzoom": "10", "maxzoom": "14", "bounds": ",".join(map(str, BOUNDS)),
                "center": "78.475,17.425,11", "attribution": "OpenFreeMap | © OpenMapTiles | © OpenStreetMap contributors (ODbL)"}
        db.executemany("INSERT INTO metadata VALUES(?,?)", info.items())
        def download(key):
            z, x, y = key
            return z, x, (1 << z) - 1 - y, fetch(TILES.format(z=z, x=x, y=y))
        total = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            for index, row in enumerate(pool.map(download, keys), 1):
                total += len(row[3])
                if total > 100 * 1024 * 1024:
                    raise ValueError("Pack exceeds 100 MiB developer download budget")
                db.execute("INSERT INTO tiles VALUES(?,?,?,?)", row)
                if index % 25 == 0:
                    print(f"Downloaded {index}/{len(keys)} tiles", flush=True)
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    font_dir = ROOT / "fonts" / FONT
    font_dir.mkdir(parents=True, exist_ok=True)
    for span in RANGES:
        (font_dir / f"{span}.pbf").write_bytes(fetch(f"https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/{span}.pbf"))
    resources = [db_path] + sorted(font_dir.glob("*.pbf"))
    manifest = {"version": 1, "id": "hyderabad-v1", "bounds": BOUNDS, "min_zoom": 10, "max_zoom": 14,
                "tile_count": len(keys), "source": TILES, "source_snapshot": "20260913_164504_pt",
                "files": [{"path": p.relative_to(ROOT).as_posix(), "bytes": p.stat().st_size,
                           "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in resources]}
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"complete": True, "bytes": sum(p.stat().st_size for p in resources)}))


if __name__ == "__main__":
    main()
