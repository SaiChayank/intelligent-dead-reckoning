#!/usr/bin/env python3
"""
tools/pull_device_telemetry.py
===============================================================================
Automated ADB Telemetry & Field Drive Extraction Utility
Intelligent Dead Reckoning System (SIH #26168 - ISRO / Department of Space)

Extracts Google Earth KML trajectories, GPX tracks, and raw recorded sessions
from a connected Android device over ADB into reports/exports/pulled/.
===============================================================================
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "reports" / "exports" / "pulled"


def find_adb() -> str:
    """Locate ADB executable in PATH or standard Android SDK locations."""
    if shutil.which("adb"):
        return "adb"

    local_app_data = os.environ.get("LOCALAPPDATA", "")
    if local_app_data:
        candidate = Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        if candidate.is_file():
            return str(candidate)

    home = os.environ.get("USERPROFILE") or os.environ.get("HOME", "")
    if home:
        for subpath in [
            Path(home) / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe",
            Path(home) / "Android" / "Sdk" / "platform-tools" / "adb",
        ]:
            if subpath.is_file():
                return str(subpath)

    raise FileNotFoundError("ADB executable not found. Ensure Android SDK platform-tools are installed.")


def get_connected_device(adb: str) -> str:
    """Returns the serial ID of the first authorized connected Android device."""
    res = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True)
    lines = res.stdout.strip().splitlines()[1:]
    devices = [line.split()[0] for line in lines if "\tdevice" in line]

    if not devices:
        raise RuntimeError("No authorized Android device connected via ADB. Please connect your phone with USB debugging enabled.")
    return devices[0]


def pull_telemetry() -> None:
    adb = find_adb()
    device_id = get_connected_device(adb)
    print(f"[IDR Telemetry] Connected Device: {device_id}")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # Remote app paths
    remote_export_dirs = [
        "/sdcard/Android/data/com.intelligentdeadreckoning.app/files/exports",
        "/sdcard/Android/data/com.intelligentdeadreckoning.app/files/recordings",
    ]

    total_pulled = 0

    for remote_dir in remote_export_dirs:
        print(f"[IDR Telemetry] Querying remote directory: {remote_dir}")
        list_res = subprocess.run(
            [adb, "-s", device_id, "shell", "ls", remote_dir],
            capture_output=True,
            text=True,
        )

        if list_res.returncode != 0 or "No such file" in list_res.stdout or not list_res.stdout.strip():
            continue

        files = [f.strip() for f in list_res.stdout.split() if f.strip()]
        for f in files:
            remote_path = f"{remote_dir}/{f}"
            local_target = OUTPUT_DIR / f
            print(f"  -> Pulling: {f} ...", end=" ", flush=True)

            pull_res = subprocess.run(
                [adb, "-s", device_id, "pull", remote_path, str(local_target)],
                capture_output=True,
                text=True,
            )

            if pull_res.returncode == 0 and local_target.exists():
                size_kb = local_target.stat().st_size / 1024.0
                print(f"OK ({size_kb:.1f} KB)")
                total_pulled += 1
            else:
                print(f"FAILED: {pull_res.stderr.strip()}")

    print("-------------------------------------------------------------------------------")
    if total_pulled > 0:
        print(f"[SUCCESS] Successfully pulled {total_pulled} telemetry artifacts into:\n  {OUTPUT_DIR}")
        print("\nTo view in Google Earth:")
        print(f"  Double-click or open any .kml file in {OUTPUT_DIR}")
        print("\nTo view in Web Visualizer:")
        print("  Run: python tools/launch_showcase.py")
    else:
        print("[INFO] No telemetry files found on device yet.")
        print("Run the app on the phone, tap 'Simulate Outage (30s)', and tap 'Export KML'.")
        print("Then rerun this script to pull the telemetry onto your PC.")
    print("-------------------------------------------------------------------------------")


if __name__ == "__main__":
    try:
        pull_telemetry()
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
