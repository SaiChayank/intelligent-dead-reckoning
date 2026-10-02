"""Seal one experiment directory: compute session digests and write `integrity.sha256`.

An experiment is authored in two steps, because the digests cannot be known before the
recordings are in place:

1. Write `experiment.json`, placing all-zero digests in `sessions[*].measurements_sha256`.
   An all-zero digest is the documented "not yet computed" marker: no real file hashes to
   it, so a manifest that was never sealed fails validation instead of passing quietly.
2. Run this tool. It fills in each session's real digest, rewrites `experiment.json`, and
   writes `integrity.sha256` over the whole directory, then re-opens the result through
   the read-only loader so the sealed experiment is verified before you leave the seat.

This tool writes only inside the experiment directory. It reads recordings and never
writes, moves, repairs or deletes one: `measurements.jsonl` is opened read-only to be
hashed, exactly like validation does.

Run from the repository root:

    python tools/seal_experiment.py <experiment-directory> [--dry-run]

Exit status: 0 sealed and verified, 2 usage, 3 the experiment violates the contract,
4 the directory or a recording could not be read.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from contracts.experiment.v1.codec import (  # noqa: E402
    ExperimentContractError,
    decode_manifest,
    encode_manifest,
)
from contracts.experiment.v1.experiment import (  # noqa: E402
    MANIFEST_NAME,
    SESSIONS_DIRNAME,
    ExperimentError,
    open_experiment,
)
from contracts.experiment.v1.integrity import (  # noqa: E402
    INTEGRITY_NAME,
    IntegrityError,
    build_integrity,
    sha256_file,
)
from contracts.recording.v1.session import MEASUREMENTS_NAME  # noqa: E402

#: The documented authoring placeholder. No real digest ever takes this value.
UNSEALED = "0" * 64


def seal(directory: Path, dry_run: bool = False) -> dict:
    """Fill in session digests, then write the integrity manifest. Returns a report."""
    root = Path(directory).absolute()
    if any(path.is_symlink() for path in (root, *root.parents)) or not root.is_dir():
        raise ExperimentError("UNSAFE_PATH", root.name)
    root = root.resolve()
    manifest_path = root / MANIFEST_NAME
    if manifest_path.is_symlink():
        raise ExperimentError("UNSAFE_PATH", MANIFEST_NAME)
    try:
        manifest = decode_manifest(manifest_path.read_bytes())
    except OSError:
        raise ExperimentError("IO_ERROR", MANIFEST_NAME) from None
    if manifest.experiment_id != root.name:
        raise ExperimentError("ID_MISMATCH", MANIFEST_NAME,
                              f"declares {manifest.experiment_id}")

    computed: list[dict] = []
    updated = []
    for entry in manifest.sessions:
        relative = f"{SESSIONS_DIRNAME}/{entry.session_id}/{MEASUREMENTS_NAME}"
        target = root / relative
        current = root
        for segment in relative.split("/"):
            current = current / segment
            if current.is_symlink():
                raise ExperimentError("UNSAFE_PATH", relative)
        if not target.is_file():
            raise ExperimentError("MISSING_ARTIFACT", relative)
        digest = sha256_file(target)
        computed.append({
            "session_id": entry.session_id,
            "role": entry.role.value,
            "digest": digest,
            "was_unsealed": entry.measurements_sha256 == UNSEALED,
            "changed": entry.measurements_sha256 != digest,
        })
        updated.append(
            entry if entry.measurements_sha256 == digest
            else type(entry)(entry.session_id, entry.role, digest)
        )

    resealed = type(manifest)(
        manifest.experiment_id,
        manifest.created_utc_ms,
        manifest.description,
        manifest.operator,
        manifest.vehicle,
        manifest.mount,
        manifest.clock,
        tuple(updated),
        manifest.reference,
        manifest.masks,
    )
    manifest_bytes = encode_manifest(resealed)
    manifest_changed = manifest_bytes != manifest_path.read_bytes()
    if not dry_run:
        integrity_path = root / INTEGRITY_NAME
        manifest_temporary: Path | None = None
        integrity_temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(prefix=".experiment-", suffix=".tmp", dir=root,
                                             delete=False) as handle:
                manifest_temporary = Path(handle.name)
                handle.write(manifest_bytes)
            manifest_temporary.replace(manifest_path)
            # Hash the final experiment tree before creating the in-directory temp file,
            # so the temp can never become an artifact in its own integrity manifest.
            integrity_bytes = build_integrity(root)
            with tempfile.NamedTemporaryFile(prefix=".integrity-", suffix=".tmp", dir=root,
                                             delete=False) as handle:
                integrity_temporary = Path(handle.name)
                handle.write(integrity_bytes)
            integrity_temporary.replace(integrity_path)
        finally:
            if manifest_temporary is not None:
                manifest_temporary.unlink(missing_ok=True)
            if integrity_temporary is not None:
                integrity_temporary.unlink(missing_ok=True)
        # Verify with the reader, not with the writer's own reasoning.
        open_experiment(root)
    return {
        "experiment_id": manifest.experiment_id,
        "dry_run": dry_run,
        "manifest_rewritten": manifest_changed,
        "integrity_written": not dry_run,
        "sessions": computed,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Seal one experiment directory: session digests and integrity.sha256.")
    parser.add_argument("experiment", type=Path, help="experiment directory")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would change without writing anything")
    args = parser.parse_args(argv)
    try:
        report = seal(args.experiment, args.dry_run)
    except ExperimentError as exc:
        print(json.dumps({"rejected": exc.code, "path": exc.path, "detail": exc.detail}),
              flush=True)
        return 3
    except (ExperimentContractError, IntegrityError) as exc:
        print(json.dumps({"rejected": getattr(exc, "code", "CONTRACT"),
                          "path": getattr(exc, "path", "")}), flush=True)
        return 3
    except OSError as exc:
        print(json.dumps({"io_error": exc.__class__.__name__}), flush=True)
        return 4
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
