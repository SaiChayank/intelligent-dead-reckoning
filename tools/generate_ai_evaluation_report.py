"""Build the shipped Hyderabad evaluation report from MEASURED results and validate it.

    cd mobile && bash gradlew testDebugUnitTest --tests "*SyntheticOutageMeasurementTest"
    python tools/generate_ai_evaluation_report.py

The JVM test writes mobile/app/build/synthetic_outage_measured.json by running each arm on the scripted
drive. This tool copies it to reports/synthetic_outage_measured.json (the tracked record), then writes
mobile/app/src/main/assets/sample_drive/hyderabad-demo-drive/evaluation_report.json from it and validates
the result with the evaluation contract codec. Nothing in the report is typed in here: a value the test did
not measure is null, and an arm that was not run says so (contracts/evaluation/v1/README.md).

An earlier version of this file carried hand-typed numbers (including a 1.44 % drift for the AI arm that came
from a measurement window containing the first returning GNSS fix). See reports/gap_closure_log.md.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from contracts.evaluation.v1 import decode_json  # noqa: E402

BUILD_RESULT = ROOT / "mobile" / "app" / "build" / "synthetic_outage_measured.json"
RECORD = ROOT / "reports" / "synthetic_outage_measured.json"
OUTPUT_PATH = (ROOT / "mobile" / "app" / "src" / "main" / "assets" / "sample_drive" / "hyderabad-demo-drive"
               / "evaluation_report.json")

IMPLEMENTATION = {
    "classical_ins": ("FusionNavigationEngine, one alignment fix then no GNSS, constraints off", "1.1.0"),
    "classical_fusion": ("FusionNavigationEngine, motionConstraintsEnabled = false", "1.1.0"),
    "fusion_constraints": ("FusionNavigationEngine, motionConstraintsEnabled = true", "1.1.0"),
    "fusion_ai": ("FusionNavigationEngine + AiSpeedEstimator (Model A v1, synthetic-trained)", "1.1.0"),
}


def arm_entry(arm: dict, outage_s: float, outage_m: float, threshold_m: float) -> dict:
    return {
        "arm_id": arm["arm_id"], "label": arm["label"],
        "implementation": {"name": IMPLEMENTATION[arm["arm_id"]][0], "version": IMPLEMENTATION[arm["arm_id"]][1]},
        "status": "evaluated", "reason": None,
        "accuracy": {
            "reference_consumed": False, "outage_duration_s": outage_s, "outage_distance_m": outage_m,
            "final_position_error_m": arm["end_error_m"], "drift_percent": arm["drift_pct"],
            "position_rmse_m": arm["position_rmse_m"], "speed_mae_m_s": arm["speed_mae_m_s"],
            "speed_rmse_m_s": arm["speed_rmse_m_s"], "heading_error_deg": arm["heading_error_deg"],
            "recovery_convergence_s": arm["recovery_convergence_s"], "recovery_threshold_m": threshold_m,
            "samples": arm["outage_samples"],
        },
        "timing": {
            "output_hz": arm["output_hz"], "inference_latency_p50_ms": None, "inference_latency_p95_ms": None,
            "end_to_end_p50_ms": None, "end_to_end_p95_ms": None, "queue_high_water": None, "drops": None,
            "errors": None, "memory_peak_mb": None, "samples": arm["samples"],
        },
    }


def build(measured: dict) -> dict:
    arms = [arm_entry(a, measured["outage_duration_s"], measured["outage_distance_m"],
                      measured["recovery_threshold_m"]) for a in measured["arms"]]
    arms.append({
        "arm_id": "fusion_map_match", "label": "Fusion + offline map matching",
        "implementation": {"name": "MapMatcher over the bundled Hyderabad road graph", "version": "1.1.0"},
        "status": "not_run",
        "reason": "The scripted drive has no road geometry; map matching has not been scored on any drive.",
        "accuracy": None, "timing": None,
    })
    return {
        "evaluation_contract_version": "1.0.0",
        "evaluation_id": "hyderabad-ai-dead-reckoning-70s",
        "created_utc_ms": "1790812800000",
        "session": {
            "session_id": "hyderabad-demo-drive", "source": "simulation", "contract_version": "1.1.0",
            "duration_s": measured["session_duration_s"], "records": 14053,
            "description": measured["description"] + ". Scripted synthetic data, not a recorded drive.",
        },
        "platform": {
            "host": True, "device_model": None, "android_release": None,
            "note": "Host JVM replay (SyntheticOutageMeasurementTest). No device, no memory and no inference "
                    "latency measurement; those fields are not measured.",
        },
        "reference": {
            "kind": "scripted_truth", "independent": True,
            "description": "The trajectory the synthetic inputs were generated from. No arm receives it "
                           "during the outage.",
        },
        "segments": [
            {"kind": "gnss_good", "start_ns": "0", "end_ns": "30000000000"},
            {"kind": "denied", "start_ns": "30000000000", "end_ns": "50000000000"},
            {"kind": "recovery", "start_ns": "50000000000", "end_ns": "70000000000"},
        ],
        "arms": arms,
    }


def main() -> int:
    if BUILD_RESULT.is_file():
        RECORD.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(BUILD_RESULT, RECORD)
    if not RECORD.is_file():
        print("no measured result: run SyntheticOutageMeasurementTest first", file=sys.stderr)
        return 2
    measured = json.loads(RECORD.read_text(encoding="utf-8"))
    text = json.dumps(build(measured), indent=2) + "\n"
    decoded = decode_json(text)             # strict contract validation
    OUTPUT_PATH.write_text(text, encoding="utf-8", newline="\n")
    for arm in decoded.arms:
        if arm.accuracy is not None:
            print(f"{arm.arm_id.value:20s} end error {arm.accuracy.final_position_error_m:7.2f} m "
                  f"drift {arm.accuracy.drift_percent:6.2f} %")
        else:
            print(f"{arm.arm_id.value:20s} {arm.status.value}")
    print(f"wrote {OUTPUT_PATH.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
