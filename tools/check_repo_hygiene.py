"""Repository hygiene gate for CI and local use.

Three checks, all read-only, all stdlib-only:

1. Tracked-file policy: no generated, cached, raw-dataset, experiment or
   secret-bearing file is committed. The patterns mirror .gitignore, which is the
   policy source.
2. Secret scan: conservative patterns for well-known credential formats over
   tracked text files. Deliberately high-precision: a false positive that fails
   CI is worse than a missed exotic format, and no scanner replaces review.
3. Basic artifact/schema sanity: the offline map manifest parses, its declared
   files exist inside the pack, and the contract golden fixtures parse.

Byte-size/SHA-256 verification of the map pack belongs to OfflineMapTest (JVM
suite) and is intentionally NOT duplicated here: one check, one home.

Exit status: 0 clean, 1 findings. Run from the repository root:
    python tools/check_repo_hygiene.py
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# --- 1. Tracked-file policy (.gitignore mirrors this list) -------------------
FORBIDDEN_DIR_PARTS = [
    ".venv", "venv", "env", "__pycache__", ".gradle", ".gradle-user-home",
    ".kotlin", ".bootstrap", ".freebuff", ".idea", ".vscode", ".pytest_cache",
    ".mypy_cache", ".ruff_cache", "artifacts", "position_plots",
]
# data/raw and data/processed are ignored whole; model weights are caught by
# suffix below so that models/README.md and future model code stay legal.
# Collected experiments are local evidence, not source: they hold real recordings,
# device identifiers and timestamps. Only the directory README is tracked.
FORBIDDEN_EXPERIMENT_PREFIX = "experiments/"
FORBIDDEN_EXPERIMENT_ALLOWLIST = {"experiments/README.md"}
FORBIDDEN_PREFIXES = ["data/raw/", "data/processed/"]
FORBIDDEN_NAMES = {"local.properties", "thumbs.db", ".ds_store", "desktop.ini"}
FORBIDDEN_SUFFIXES = (".pyc", ".pyo", ".pyd", ".log", ".tmp", ".temp",
                      ".pem", ".key", ".h5", ".keras", ".tflite", ".onnx",
                      ".pt", ".pth", ".ckpt", ".jar")
# Generated but deliberately tracked: the Gradle wrapper is pinned by SHA-256 in
# gradle-wrapper.properties and must ship with the source.
TRACKED_GENERATED_ALLOWLIST = {"mobile/gradle/wrapper/gradle-wrapper.jar"}

# --- 2. Secret patterns (high precision, well-known formats only) -----------
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{22,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
]
SCAN_SUFFIXES = {".py", ".kt", ".kts", ".md", ".json", ".jsonl", ".xml",
                 ".txt", ".yml", ".yaml", ".properties", ".gradle"}
SCAN_MAX_BYTES = 2 * 1024 * 1024


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True,
                         text=True, check=True)
    return [line for line in out.stdout.splitlines() if line]


def check_tracked_policy(files: list[str]) -> list[str]:
    findings = []
    for rel in files:
        if rel in TRACKED_GENERATED_ALLOWLIST:
            continue
        if rel.startswith(FORBIDDEN_EXPERIMENT_PREFIX) and rel not in FORBIDDEN_EXPERIMENT_ALLOWLIST:
            findings.append(f"experiment content must not be committed: {rel}")
            continue
        lower = rel.lower()
        parts = lower.split("/")
        if any(part in FORBIDDEN_DIR_PARTS for part in parts[:-1]):
            findings.append(f"forbidden directory in path: {rel}")
        elif rel.startswith(tuple(FORBIDDEN_PREFIXES)):
            findings.append(f"forbidden top-level area: {rel}")
        elif parts[-1] in FORBIDDEN_NAMES:
            findings.append(f"forbidden file name: {rel}")
        elif lower.endswith(FORBIDDEN_SUFFIXES):
            findings.append(f"forbidden generated/secret file type: {rel}")
    return findings


def check_secrets(files: list[str]) -> list[str]:
    findings = []
    for rel in files:
        path = ROOT / rel
        if path.suffix.lower() not in SCAN_SUFFIXES:
            continue
        try:
            if path.stat().st_size > SCAN_MAX_BYTES:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for pattern in SECRET_PATTERNS:
            match = pattern.search(text)
            if match:
                findings.append(f"possible secret ({match.group(0)[:12]}…) in {rel}")
    return findings


def check_artifacts() -> list[str]:
    findings = []
    pack = ROOT / "mobile/app/src/main/assets/offline/hyderabad"
    manifest_path = pack / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [f"map manifest unreadable: {exc}"]

    if manifest.get("tile_count", 0) < 1:
        findings.append("map manifest declares no tiles")
    if not (pack / "OFL.txt").is_file():
        findings.append("map pack missing OFL.txt attribution licence")
    for entry in manifest.get("files", []):
        rel_path = entry.get("path", "")
        target = (pack / rel_path).resolve()
        if not rel_path or not target.is_relative_to(pack.resolve()):
            findings.append(f"map manifest path escapes pack: {rel_path!r}")
            continue
        if not target.is_file():
            findings.append(f"map manifest file missing: {rel_path}")
            continue
        declared = entry.get("bytes")
        if isinstance(declared, int) and declared != target.stat().st_size:
            findings.append(f"map manifest size mismatch: {rel_path}")

    for name in ("contracts/v1/golden.json", "contracts/v1/invalid_records.json",
                 "contracts/recording/v1/golden_metadata.json",
                 "contracts/experiment/v1/golden_experiment.json",
                 "contracts/experiment/v1/golden_mask.json",
                 "contracts/experiment/v1/invalid_manifests.json"):
        try:
            json.loads((ROOT / name).read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            findings.append(f"contract fixture not valid JSON: {name} ({exc})")
    for name in ("contracts/v1/golden_records.jsonl", "contracts/v1/edge_records.jsonl",
                 "contracts/experiment/v1/golden_annotations.jsonl"):
        try:
            with (ROOT / name).open(encoding="utf-8") as handle:
                for number, line in enumerate(handle, 1):
                    if line.strip():
                        json.loads(line)
        except (OSError, ValueError) as exc:
            findings.append(f"contract fixture not valid JSONL: {name}:{number} ({exc})")
    return findings


def main() -> int:
    files = tracked_files()
    findings = (check_tracked_policy(files)
                + check_secrets(files)
                + check_artifacts())
    print(f"checked {len(files)} tracked files")
    if findings:
        for finding in findings:
            print(f"FINDING: {finding}")
        print(f"{len(findings)} finding(s)")
        return 1
    print("clean: no forbidden tracked files, no secret patterns, artifacts parse")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
