"""Read-only verification of bundled tiles, hashes, metadata and protobuf structure."""
import gzip
import hashlib
import json
import sqlite3
from build_hyderabad_pack import ROOT, tile_keys


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError("truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("oversized protobuf varint")


def fields(data):
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        number, wire = tag >> 3, tag & 7
        assert number > 0
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire in (1, 5):
            length = 8 if wire == 1 else 4
            value = data[offset:offset + length]
            offset += length
        elif wire == 2:
            length, offset = varint(data, offset)
            value = data[offset:offset + length]
            offset += length
            assert len(value) == length
        else:
            raise ValueError("unsupported protobuf wire type")
        assert offset <= len(data)
        yield number, wire, value


def main():
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        file = ROOT / entry["path"]
        assert file.stat().st_size == entry["bytes"]
        with file.open("rb") as stream:
            assert hashlib.file_digest(stream, "sha256").hexdigest() == entry["sha256"]
    path = ROOT / "hyderabad.mbtiles"
    with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as db:
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert db.execute("SELECT value FROM metadata WHERE name='format'").fetchone()[0] == "pbf"
        actual = set()
        layers = set()
        features = 0
        for z, x, y, tile in db.execute("SELECT * FROM tiles"):
            actual.add((z, x, (1 << z) - 1 - y))
            raw = gzip.decompress(tile) if tile[:2] == b"\x1f\x8b" else tile
            for number, wire, layer in fields(raw):
                if number == 3 and wire == 2:
                    for number, wire, value in fields(layer):
                        if number == 1 and wire == 2:
                            layers.add(value.decode("utf-8"))
                        elif number == 2 and wire == 2:
                            features += 1
        assert actual == set(tile_keys())
        assert {"transportation", "transportation_name", "place", "water", "building"} <= layers
    for file in (ROOT / "fonts/Noto Sans Regular").glob("*.pbf"):
        assert list(fields(file.read_bytes()))
    print(json.dumps({"verified_files": len(manifest["files"]), "tiles": len(actual),
                      "vector_features": features, "layers": sorted(layers), "read_only": True}))


if __name__ == "__main__":
    main()
