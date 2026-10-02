"""Run the project-local vulnerability auditor against the pinned Python requirements.

This is a developer tool, not a runtime dependency. `pip-audit` is installed separately
by the operator in an isolated environment; this wrapper never installs or upgrades it.
The audit needs network access to retrieve vulnerability advisories and package metadata.

    python tools/audit_dependencies.py
    python tools/audit_dependencies.py --pip-audit /path/to/pip-audit
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pip-audit", default="pip-audit",
                        help="path/name of an already installed pip-audit executable")
    args = parser.parse_args(argv)
    candidate = Path(args.pip_audit)
    executable = str(candidate.resolve()) if candidate.is_file() else shutil.which(args.pip_audit)
    if executable is None:
        print("pip-audit is not installed; install it in a disposable audit environment first.",
              file=sys.stderr)
        return 2
    command = [executable, "--requirement", str(ROOT / "requirements.txt"), "--strict", "--progress-spinner", "off"]
    return subprocess.run(command, cwd=ROOT, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
