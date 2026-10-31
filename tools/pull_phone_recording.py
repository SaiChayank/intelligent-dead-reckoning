"""Copy a recording the app made from a connected phone into data/raw/phone/ (untracked), then validate it.

    python tools/pull_phone_recording.py --list
    python tools/pull_phone_recording.py --label hyderabad-morning-drive          # the newest real recording
    python tools/pull_phone_recording.py --label desk-test --id <recording-id> [--serial <adb serial>]

Needs a debuggable build (adb `run-as`) and one phone, or --serial when several devices are attached. The files are written byte for byte
(`adb exec-out run-as ... cat`), never changed on the phone, and then checked with tools/validate_phone_recording.py --local. The pulled
folder is what tools/field_drive_eval.py takes.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.intelligentdeadreckoning.app"
RECORDINGS = "no_backup/recordings"
ARTIFACTS = ("metadata.json", "measurements.jsonl")
ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{3,127}$")
DEMO_IDS = {"hyderabad-demo-drive", "stop-and-go-outage", "urban-canyon-multipath"}


def find_adb() -> str:
    found = shutil.which("adb")
    if found:
        return found
    properties = ROOT / "mobile" / "local.properties"
    if properties.is_file():
        match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(encoding="utf-8"), re.M)
        if match:
            sdk = Path(match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\"))
            for name in ("adb.exe", "adb"):
                if (sdk / "platform-tools" / name).is_file():
                    return str(sdk / "platform-tools" / name)
    raise SystemExit("adb not found: put platform-tools on PATH or set sdk.dir in mobile/local.properties")


def adb(base: list[str], *args: str, binary: bool = False) -> bytes | str:
    result = subprocess.run([*base, *args], capture_output=True, check=False)
    if result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args[:3])} failed: {result.stderr.decode(errors='replace').strip() or result.stdout.decode(errors='replace').strip()}")
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def adb_base(serial: str | None) -> list[str]:
    exe = find_adb()
    if serial:
        return [exe, "-s", serial]
    devices = [line.split()[0] for line in subprocess.run([exe, "devices"], capture_output=True, text=True, check=False).stdout.splitlines()[1:]
               if line.strip().endswith("device")]
    if len(devices) != 1:
        raise SystemExit(f"{len(devices)} devices attached ({', '.join(devices) or 'none'}); pass --serial")
    return [exe, "-s", devices[0]]


def list_recordings(base: list[str]) -> list[str]:
    """Recording folder names, oldest first, without the bundled demo sessions."""
    names = adb(base, "shell", "run-as", PACKAGE, "ls", "-tr", RECORDINGS).split()
    return [n for n in names if n not in DEMO_IDS and ID_PATTERN.match(n)]


def pull(base: list[str], recording_id: str, destination: Path) -> Path:
    destination.mkdir(parents=True, exist_ok=False)
    for name in ARTIFACTS:
        (destination / name).write_bytes(adb(base, "exec-out", "run-as", PACKAGE, "cat", f"{RECORDINGS}/{recording_id}/{name}", binary=True))
    return destination


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--list", action="store_true", help="list the recordings on the phone and stop")
    parser.add_argument("--id", help="recording folder name (default: the newest)")
    parser.add_argument("--label", help="folder under data/raw/phone/ to write into, e.g. hyderabad-morning-drive")
    parser.add_argument("--serial")
    args = parser.parse_args(argv)
    base = adb_base(args.serial)
    found = list_recordings(base)
    if args.list:
        print("\n".join(found) or "no recordings on the phone")
        return 0
    if not found:
        raise SystemExit("no recordings on the phone (record one in the app first)")
    recording_id = args.id or found[-1]
    if recording_id not in found:
        raise SystemExit(f"{recording_id} is not on the phone; found: {', '.join(found)}")
    if not args.label or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{1,63}", args.label):
        raise SystemExit("--label is required: letters, digits, '-' and '_' (it names the folder under data/raw/phone/)")
    destination = pull(base, recording_id, ROOT / "data" / "raw" / "phone" / args.label / recording_id)
    print(f"pulled {recording_id} to {destination}")
    check = subprocess.run([sys.executable, str(ROOT / "tools" / "validate_phone_recording.py"), "--local", str(destination)],
                           capture_output=True, text=True, check=False)
    print("validator:", "accepted" if check.returncode == 0 else f"REJECTED (exit {check.returncode})")
    if check.returncode != 0:
        print(check.stdout[-1500:] or check.stderr[-1500:])
    return check.returncode


if __name__ == "__main__":
    raise SystemExit(main())
