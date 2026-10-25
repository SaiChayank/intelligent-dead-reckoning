"""Test that reports/iovnbd_inventory.md exists, is complete, and matches empirical data."""

from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
REPORT_PATH = PROJECT_ROOT / "reports" / "iovnbd_inventory.md"
FOLLOWUP_PATH = PROJECT_ROOT / "reports" / "frame_resolution_followup.md"


def test_iovnbd_inventory_exists():
    assert REPORT_PATH.is_file(), f"Expected report at {REPORT_PATH}"
    content = REPORT_PATH.read_text(encoding="utf-8")
    assert len(content) > 5000, "Report content appears truncated"


def test_iovnbd_inventory_required_sections():
    content = REPORT_PATH.read_text(encoding="utf-8")
    assert "## 1. Factual Sequence Inventory Table" in content
    assert "## 2. Columns: IMU vs Ground Truth Mapping" in content
    assert "## 3. Known Frame Problem Summary" in content
    assert "## 4. Open Questions and Unverified Aspects" in content


def test_iovnbd_inventory_table_coverage():
    content = REPORT_PATH.read_text(encoding="utf-8")
    # All key sequences must be listed in the table
    required_sequences = [
        "`M`", "`S1`", "`S2`", "`S3a`", "`S3b`", "`S3c`", "`S4`",
        "`Vfa01`", "`Vta2`", "`Vtb2`", "`Vw1`", "`Vw4`", "`Y1`",
        "`A1`", "`A4`", "`I`", "`St1`", "`T1`", "`Vfb01a`", "`Y2`"
    ]
    for seq in required_sequences:
        assert seq in content, f"Missing sequence {seq} in inventory table"


def test_iovnbd_inventory_exact_blocking_statement():
    content = REPORT_PATH.read_text(encoding="utf-8")
    followup_content = FOLLOWUP_PATH.read_text(encoding="utf-8")

    blocking_quote = (
        "Still unresolved: a verified complete gyro triad in physical device axes, "
        "the precise acceleration export transform and timing, and a stable validated "
        "device-to-vehicle mounting rotation. Full 3-D strapdown Phase 1 remains blocked."
    )
    import re
    norm_content = re.sub(r"\s+", " ", re.sub(r"[*_\"]", "", content))
    norm_followup = re.sub(r"\s+", " ", re.sub(r"[*_\"]", "", followup_content))
    assert blocking_quote in norm_followup, "Reference blocking quote missing from followup"
    assert blocking_quote in norm_content, "Blocking quote missing from iovnbd_inventory.md"
