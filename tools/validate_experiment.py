"""Read-only acceptance report for one experiment, or for a corpus of them.

    python tools/validate_experiment.py --experiment experiments/exp-2026-09-30-urban-01
    python tools/validate_experiment.py --corpus experiments

Validating one experiment checks the manifest, the whole-directory integrity manifest,
the annotation stream, every declared mask, every referenced recording and the optional
reference stream. The corpus mode adds the one check no single directory can make:
no two experiments may claim the same `experiment_id`.

Nothing is written. Recordings are opened through the read-only recording reader, so an
experiment is accepted or rejected for exactly the reasons the device replay reader
would apply to the same session.

Exit status: 0 accepted, 2 usage (argparse), 3 the experiment violates the contract,
4 the path could not be read.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from contracts.experiment.v1.codec import ExperimentContractError  # noqa: E402
from contracts.experiment.v1.experiment import (  # noqa: E402
    ExperimentError,
    corpus_label,
    find_duplicate_ids,
    iter_experiments,
    open_experiment,
)
from contracts.experiment.v1.integrity import IntegrityError  # noqa: E402
from contracts.recording.v1.session import SessionError  # noqa: E402


def _code(exc: Exception) -> tuple[str, str, str]:
    return (getattr(exc, "code", "CONTRACT"),
            getattr(exc, "path", ""),
            getattr(exc, "detail", getattr(exc, "message", "")))


def describe_experiment(directory: Path) -> dict:
    """One accepted experiment, summarised from what the loader verified."""
    experiment = open_experiment(directory)
    manifest = experiment.manifest
    return {
        "experiment_id": manifest.experiment_id,
        "description": manifest.description,
        "mount": {
            "position": manifest.mount.position.value,
            "orientation_method": manifest.mount.orientation_method.value,
            "uncertainty_deg": manifest.mount.uncertainty_deg,
        },
        "clock": {
            "domain": manifest.clock.domain,
            "boot_id": manifest.clock.boot_id,
            "reference_alignment": manifest.clock.reference_alignment is not None,
        },
        "sessions": [
            {
                "session_id": info.session_id,
                "role": info.role.value,
                "records": info.metadata.record_count,
                "duration_s": None if info.ended_ns is None
                else (info.ended_ns - info.started_ns) / 1e9,
                "source": info.metadata.source.value,
                "completion": info.metadata.completion_state.value,
            } for info in (experiment.sessions[key] for key in sorted(experiment.sessions))
        ],
        "annotations": len(experiment.annotations),
        "annotation_kinds": sorted({i.kind.value for i in experiment.annotations}),
        "masks": sorted(experiment.masks),
        "reference": None if experiment.reference is None else {
            "equipment_class": experiment.reference.descriptor.equipment_class.value,
            "records": experiment.reference.records,
            "horizontal_accuracy_m": experiment.reference.descriptor.horizontal_accuracy_m,
        },
        "integrity": {
            "artifacts": len(experiment.integrity),
            "verified": bool(experiment.integrity),
        },
    }


def validate_one(directory: Path) -> tuple[int, dict]:
    try:
        return 0, {"accepted": True, "experiment": describe_experiment(directory)}
    except (ExperimentError, ExperimentContractError, SessionError, IntegrityError) as exc:
        code, path, detail = _code(exc)
        return 3, {"accepted": False, "rejected": code, "path": path, "detail": detail}
    except OSError as exc:
        return 4, {"io_error": exc.__class__.__name__}


def validate_corpus(corpus: Path) -> tuple[int, dict]:
    """Validate every experiment, plus the ID uniqueness only the corpus can decide.

    Findings are collected per directory and stay aligned with it: an experiment that
    passes contributes nothing, which is why this does not reuse a filtered list.
    """
    if not corpus.is_dir():
        return 4, {"io_error": "corpus directory not found"}
    total = 0
    findings: list[dict] = []
    for directory, summary in iter_experiments(corpus):
        total += 1
        label = corpus_label(corpus, directory)
        if summary.manifest is None:
            findings.append({"experiment": label,
                             "rejected": summary.error_code or "MISSING_EXPERIMENT",
                             "detail": summary.error or ""})
            continue
        status, report = validate_one(directory)
        if status != 0:
            findings.append({"experiment": label,
                             "rejected": report.get("rejected", "CONTRACT"),
                             "path": report.get("path", ""),
                             "detail": report.get("detail", "")})
    duplicates = [
        {"experiment_id": experiment_id, "rejected": "DUPLICATE_EXPERIMENT_ID",
         "directories": sorted(names)}
        for experiment_id, names in sorted(find_duplicate_ids(corpus).items())
    ]
    report = {
        "corpus": str(corpus),
        "experiments": total,
        "accepted": not findings and not duplicates,
        "findings": findings,
        "duplicate_experiment_ids": duplicates,
    }
    return (3 if findings or duplicates else 0), report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate an experiment directory, read-only.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--experiment", type=Path, help="validate one experiment directory")
    group.add_argument("--corpus", type=Path,
                       help="validate every experiment under a directory, including ID uniqueness")
    args = parser.parse_args(argv)
    if args.experiment is not None:
        if not args.experiment.exists():
            print(json.dumps({"io_error": "experiment directory not found"}), flush=True)
            return 4
        status, report = validate_one(args.experiment)
    else:
        status, report = validate_corpus(args.corpus)
    print(json.dumps(report, indent=2), flush=True)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
