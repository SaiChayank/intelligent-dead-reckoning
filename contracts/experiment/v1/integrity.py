"""SHA-256 integrity manifest for one experiment directory.

`integrity.sha256` is a plain text manifest, one artifact per line:

    <64-lowercase-hex>  <POSIX-relative-path>

Two spaces between digest and path, exactly as `sha256sum` writes it, LF line endings,
sorted by path so the file is diffable and byte-stable. Every regular file inside the
experiment directory is covered **except** `integrity.sha256` itself. `experiment.json`
*is* covered: it is written before the manifest is computed, so there is no circularity,
and the manifest is what proves the manifest was not edited afterwards.

This module reads and hashes. Writing the manifest is the sealer's job
(`tools/seal_experiment.py`), so a reader that verified a bad experiment can never
"fix" it.

Error codes raised here:

    UNSAFE_PATH, MISSING_ARTIFACT, MALFORMED_INTEGRITY, DUPLICATE_ENTRY,
    UNLISTED_FILE, HASH_MISMATCH, IO_ERROR
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

INTEGRITY_NAME = "integrity.sha256"

#: 1 MiB: large enough that hashing a multi-hundred-megabyte recording is I/O bound,
#: small enough that peak memory stays independent of session size.
CHUNK_BYTES = 1 << 20

_LINE = re.compile(r"([0-9a-f]{64})  (\S.*)\Z")


class IntegrityError(ValueError):
    """A manifest that cannot be trusted, or an artifact that does not match it."""

    def __init__(self, code: str, path: str = ""):
        self.code = code
        self.path = path
        super().__init__(f"{code}: {path}" if path else code)


def _fail(code: str, path: str = ""):
    raise IntegrityError(code, path) from None


def sha256_file(path: Path) -> str:
    """Streaming digest, so verification never loads a recording into memory."""
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
    except OSError:
        _fail("IO_ERROR", path.name)
    return digest.hexdigest()


def _validate_relative_name(name: str) -> str:
    """Validate canonical POSIX-relative syntax before encoding or resolving a path."""
    if (not isinstance(name, str) or not name or name.startswith(("/", "~"))
            or "\\" in name or ":" in name
            or any(ord(char) < 32 or ord(char) == 127 for char in name)):
        _fail("UNSAFE_PATH", str(name))
    segments = name.split("/")
    if any(segment in ("", ".", "..") for segment in segments):
        _fail("UNSAFE_PATH", name)
    return name


def _safe_relative(root: Path, name: str) -> str:
    """A listed path must stay inside `root` and must not be a symlink bait."""
    _validate_relative_name(name)
    base = root.resolve()
    target = root / name
    current = root
    for segment in name.split("/"):
        current = current / segment
        if current.is_symlink():
            _fail("UNSAFE_PATH", name)
    if not target.resolve().is_relative_to(base):
        _fail("UNSAFE_PATH", name)
    return name


def encode_integrity(entries: dict[str, str]) -> bytes:
    """Serialise digests. Paths are sorted so identical content yields identical bytes."""
    for name, digest in entries.items():
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            _fail("MALFORMED_INTEGRITY", name)
        _validate_relative_name(name)
    body = "".join(f"{entries[name]}  {name}\n" for name in sorted(entries))
    return body.encode("utf-8")


def decode_integrity(data: bytes) -> dict[str, str]:
    """Parse a manifest strictly. Unknown line shapes are rejected, never skipped."""
    if not isinstance(data, bytes):
        _fail("MALFORMED_INTEGRITY")
    try:
        text = data.decode("utf-8", "strict")
    except UnicodeDecodeError:
        _fail("MALFORMED_INTEGRITY")
    entries: dict[str, str] = {}
    for number, line in enumerate(text.split("\n"), 1):
        if not line:
            continue
        if line.endswith("\r"):
            line = line[:-1]
        if not line:
            continue
        match = _LINE.fullmatch(line)
        if match is None:
            _fail("MALFORMED_INTEGRITY", f"line {number}")
        digest, name = match.groups()
        _validate_relative_name(name)
        if name in entries:
            _fail("DUPLICATE_ENTRY", name)
        if name == INTEGRITY_NAME:
            # A manifest cannot contain itself.
            _fail("UNSAFE_PATH", name)
        entries[name] = digest
    return entries


def _walk(root: Path) -> list[str]:
    """Every regular file under `root`, excluding the manifest itself."""
    if root.is_symlink():
        _fail("UNSAFE_PATH", root.name)
    found: list[str] = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            _fail("UNSAFE_PATH", str(path.relative_to(root)))
        if path.name == INTEGRITY_NAME and path.parent == root:
            continue
        if path.is_dir():
            continue
        if not path.is_file():
            _fail("MISSING_ARTIFACT", str(path.relative_to(root)))
        found.append(path.relative_to(root).as_posix())
    return found


def _safe_root(root: Path) -> Path:
    """Reject symlinks in the root or any ancestor before traversing a tree."""
    root = Path(root).absolute()
    if any(path.is_symlink() for path in (root, *root.parents)):
        _fail("UNSAFE_PATH", root.name)
    return root


def build_integrity(root: Path) -> bytes:
    """Hash every artifact in a finished experiment directory into manifest bytes."""
    root = _safe_root(Path(root))
    if not root.is_dir():
        _fail("MISSING_ARTIFACT", root.name)
    return encode_integrity({name: sha256_file(root / name) for name in _walk(root)})


def verify_integrity(root: Path, declared: dict[str, str]) -> None:
    """Compare a directory against its manifest. Raises on the first mismatch.

    A file present but unlisted fails as hard as a listed file that is missing: an
    artifact nobody committed to is an artifact nobody can reproduce.
    """
    root = _safe_root(Path(root))
    if not root.is_dir():
        _fail("UNSAFE_PATH", root.name)
    present = _walk(root)
    for name in declared:
        _safe_relative(root, name)
    listed = set(declared)
    for name in present:
        if name not in listed:
            _fail("UNLISTED_FILE", name)
    for name in sorted(listed):
        target = root / name
        if not target.is_file() or target.is_symlink():
            _fail("MISSING_ARTIFACT", name)
        if sha256_file(target) != declared[name]:
            _fail("HASH_MISMATCH", name)
