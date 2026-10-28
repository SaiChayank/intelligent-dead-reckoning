# PS #26168 gap-closure log

One entry per phase. Each records the gate the phase had to pass and the measured result,
so later phases are compared against recorded numbers rather than remembered ones.

## Phase 0 — environment and data (2026-10-29)

Toolchain on the Windows development machine:

| Component | Version |
|---|---|
| JDK | Eclipse Temurin 25.0.5+8 |
| Android SDK | cmdline-tools 23.0.0, platform `android-37.0`, build-tools 36.0.0, platform-tools 37.0.1 |
| Python | CPython 3.13.15 in `.venv` (CI uses 3.12) with `requirements.txt` |
| PyTorch | 2.15.0+cpu, training only, from `requirements-train.txt` |

IO-VNBD: `Synchronised V abd S datasets.zip` and `Unsynchronised V and S Dataset.zip` from the
dataset's Google Drive folder, extracted to `data/raw/iovnbd/`. Both archives and all 728
extracted files match the SHA-256 and size in `reports/phase0_raw_manifest.json`; none are
missing or extra. The dataset's own `.git` folder and READMEs were not downloaded.

Baseline gate, all green with no source changes:

| Suite | Result |
|---|---|
| Python `unittest discover -s tests` | 295 passed, 5 skipped |
| Python `pytest tests` (includes the IO-VNBD data tests) | 343 passed, 5 skipped |
| Mobile `testDebugUnitTest` | 419 passed |
| Mobile `lintDebug`, `assembleDebug` | passed |
| Edge `test` | 10 passed |
| `tools/check_repo_hygiene.py` | clean |

Not run: `connectedDebugAndroidTest` and installation, which need a connected phone.
