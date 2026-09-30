"""Timeline and summary report for one sealed experiment.

    python tools/experiment_report.py --experiment experiments/exp-2026-09-30-urban-01
    python tools/experiment_report.py --experiment <dir> --mask tunnel_a tunnel_b
    python tools/experiment_report.py --experiment <dir> --json

The report describes how a drive was collected and what was annotated: mounting and how
its orientation was established, each session's span, the observed GNSS availability
timeline, motion and scenario coverage, calibration intervals, the optional independent
reference, and the declared masks with the duration each one covers.

It reports annotations and counts. It never estimates an accuracy figure, and it never
infers a navigation result: this stage has no engine output to summarise. Named masks are
applied to the primary session's input stream so the withheld-record counts in the report
are measured, not predicted.

Reading only. Exit status: 0 report produced, 2 usage, 3 the experiment is invalid,
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
    Experiment,
    ExperimentError,
    open_experiment,
)
from contracts.experiment.v1.integrity import IntegrityError  # noqa: E402
from contracts.experiment.v1.masking import mask_duration_ns  # noqa: E402
from contracts.experiment.v1.models import IntervalKind  # noqa: E402
from contracts.recording.v1.session import SessionError  # noqa: E402


def _seconds(ns: int) -> float:
    return round(ns / 1e9, 1)


def mask_stream_counts(experiment: Experiment, session_id: str,
                       mask_ids: list[str]) -> dict:
    """Measure what each mask withholds, by streaming the session's input once per mask.

    The baseline (`mask_id = None`) is returned under the key `unmasked` so a report always
    shows what was actually recorded next to what each mask removes.
    """
    counts: dict = {}
    for mask_id in [None, *mask_ids]:
        replay = experiment.replay_input(session_id, mask_id)
        try:
            for _ in replay:
                pass
        finally:
            replay.close()
        key = "unmasked" if mask_id is None else mask_id
        counts[key] = {
            "total_gnss": replay.total_gnss,
            "withheld_gnss": replay.suppressed_gnss,
            "records_yielded": replay.yielded,
        }
    return counts


def summarize(experiment: Experiment, mask_ids: list[str]) -> dict:
    """Fold a verified experiment into a structured report. Pure; prints nothing."""
    manifest = experiment.manifest
    clock = manifest.clock
    primary = experiment.primary()
    sessions = []
    for key in sorted(experiment.sessions):
        info = experiment.sessions[key]
        metadata = info.metadata
        sessions.append({
            "session_id": info.session_id,
            "role": info.role.value,
            "records": metadata.record_count,
            "origin_ns": info.origin_ns,
            "started_ns": info.started_ns,
            "ended_ns": info.ended_ns,
            "duration_s": None if info.ended_ns is None
            else _seconds(info.ended_ns - info.started_ns),
            "source": metadata.source.value,
            "completion": metadata.completion_state.value,
            "channels": (None if metadata.channel_counts is None
                         else {c.channel: c.count for c in metadata.channel_counts}),
        })

    timeline = []
    for interval in experiment.annotations:
        timeline.append({
            "kind": interval.kind.value,
            "session_id": interval.session_id,
            "start_ns": interval.start_ns,
            "end_ns": interval.end_ns,
            "start_s": _seconds(interval.start_ns - primary.started_ns),
            "duration_s": _seconds(interval.end_ns - interval.start_ns),
            "label": (interval.calibration_id or
                      getattr(interval.motion, "value", None) or
                      getattr(interval.scenario, "value", None) or
                      getattr(interval.gnss_state, "value", None)),
            "note": interval.note,
        })

    coverage: dict[str, dict[str, float]] = {}
    for kind in (IntervalKind.GNSS_STATE, IntervalKind.MOTION, IntervalKind.SCENARIO):
        totals: dict[str, int] = {}
        for interval in experiment.intervals(kind, primary.session_id):
            # `IntervalKind.gnss_state` names the payload field on the interval, so the
            # coverage key is read from the contract rather than hard-coded per kind.
            payload = getattr(interval, kind.value, None)
            label = payload.value if payload is not None else "unknown"
            totals[label] = totals.get(label, 0) + (interval.end_ns - interval.start_ns)
        covered = sum(totals.values())
        span = (primary.ended_ns or primary.started_ns) - primary.started_ns
        coverage[kind.value] = {
            **{label: _seconds(total) for label, total in sorted(totals.items())},
            "covered_s": _seconds(covered),
            # Time nobody annotated. Reported, never rounded away to look complete.
            "unannotated_s": _seconds(max(span - covered, 0)),
        }

    return {
        "experiment_id": manifest.experiment_id,
        "description": manifest.description,
        "operator": manifest.operator,
        "vehicle": manifest.vehicle,
        "created_utc_ms": manifest.created_utc_ms,
        "mount": {
            "position": manifest.mount.position.value,
            "orientation_method": manifest.mount.orientation_method.value,
            "roll_deg": manifest.mount.roll_deg,
            "pitch_deg": manifest.mount.pitch_deg,
            "yaw_deg": manifest.mount.yaw_deg,
            "uncertainty_deg": manifest.mount.uncertainty_deg,
            "note": manifest.mount.note,
        },
        "clock": {
            "domain": clock.domain,
            "boot_id": clock.boot_id,
            "reference_aligned": clock.reference_alignment is not None,
            "alignment_uncertainty_ns": (None if clock.reference_alignment is None
                                        else clock.reference_alignment.uncertainty_ns),
        },
        "integrity": {"artifacts": len(experiment.integrity), "verified": True},
        "sessions": sessions,
        "primary": primary.session_id,
        "timeline": timeline,
        "coverage": coverage,
        "calibration_intervals": len(experiment.intervals(IntervalKind.CALIBRATION)),
        "reference": None if experiment.reference is None else {
            "equipment_class": experiment.reference.descriptor.equipment_class.value,
            "equipment": experiment.reference.descriptor.equipment,
            "timebase": experiment.reference.descriptor.timebase.value,
            "horizontal_accuracy_m": experiment.reference.descriptor.horizontal_accuracy_m,
            "rate_hz": experiment.reference.descriptor.rate_hz,
            "records": experiment.reference.records,
            "first_ns": experiment.reference.first_ns,
            "last_ns": experiment.reference.last_ns,
        },
        "masks": {
            mask_id: {
                "session_id": mask.session_id,
                "provenance": mask.provenance.value,
                "policy": mask.policy.value,
                "intervals": len(mask.intervals),
                "masked_duration_s": _seconds(mask_duration_ns(mask)),
            } for mask_id, mask in sorted(experiment.masks.items())
        },
        "mask_effect": ({} if not mask_ids
                        else mask_stream_counts(experiment, primary.session_id, mask_ids)),
    }


def render(report: dict) -> str:
    """Human-readable form. Every number here is annotated, counted or declared."""
    lines: list[str] = []
    add = lines.append
    add(f"experiment  {report['experiment_id']}")
    add(f"description {report['description']}")
    if report["operator"]:
        add(f"operator    {report['operator']}")
    if report["vehicle"]:
        add(f"vehicle     {report['vehicle']}")
    add("")

    mount = report["mount"]
    add("MOUNT")
    add(f"  position              {mount['position']}")
    add(f"  orientation           {mount['orientation_method']}"
        f"  (roll {mount['roll_deg']:+.1f}  pitch {mount['pitch_deg']:+.1f}"
        f"  yaw {mount['yaw_deg']:+.1f} deg)")
    add(f"  orientation tolerance {mount['uncertainty_deg']:.1f} deg")
    if mount["note"]:
        add(f"  note                  {mount['note']}")
    add("")

    clock = report["clock"]
    add("CLOCK")
    add(f"  domain                {clock['domain']}")
    add(f"  boot identity         {clock['boot_id']}")
    add(f"  reference aligned     {'yes' if clock['reference_aligned'] else 'no'}"
        + ("" if clock["alignment_uncertainty_ns"] is None
           else f"  (to {clock['alignment_uncertainty_ns'] / 1e6:.1f} ms)"))
    add(f"  integrity             {report['integrity']['artifacts']} artifact(s) verified")
    add("")

    add("SESSIONS")
    for session in report["sessions"]:
        add(f"  {session['session_id']}  [{session['role']}]"
            f"  {session['duration_s']} s"
            f"  {session['records']} records"
            f"  {session['source']}/{session['completion']}")
        if session["channels"]:
            channels = "  ".join(f"{name}={count}"
                                 for name, count in sorted(session["channels"].items()))
            add(f"      channels {channels}")
    add("")

    coverage = report["coverage"]
    add("OBSERVED GNSS AVAILABILITY  (annotated intervals, not a target)")
    for label, seconds in coverage["gnss_state"].items():
        if label in ("covered_s", "unannotated_s"):
            continue
        add(f"  {label:<12} {seconds:>8.1f} s")
    add(f"  {'covered':<12} {coverage['gnss_state']['covered_s']:>8.1f} s")
    add(f"  {'unannotated':<12} {coverage['gnss_state']['unannotated_s']:>8.1f} s")
    add("")

    for kind, title in (("motion", "MOTION"), ("scenario", "SCENARIO")):
        add(title)
        for label, seconds in coverage[kind].items():
            if label in ("covered_s", "unannotated_s"):
                continue
            add(f"  {label:<16} {seconds:>8.1f} s")
        add(f"  {'unannotated':<16} {coverage[kind]['unannotated_s']:>8.1f} s")
        add("")

    add(f"CALIBRATION  {report['calibration_intervals']} interval(s) annotated")
    for entry in report["timeline"]:
        if entry["kind"] == IntervalKind.CALIBRATION.value:
            add(f"  {entry['start_s']:>8.1f} s  {entry['label']}"
                f"  ({entry['duration_s']} s)")
    add("")

    reference = report["reference"]
    add("REFERENCE")
    if reference is None:
        # Absence is stated, so a missing reference is never read as "accuracy verified".
        add("  none declared: this experiment has no independent position reference")
    else:
        add(f"  class                 {reference['equipment_class']}"
            f"  ({reference['equipment']})")
        add(f"  declared accuracy     {reference['horizontal_accuracy_m']} m horizontal")
        add(f"  rate / timebase       {reference['rate_hz']} Hz  {reference['timebase']}")
        add(f"  records               {reference['records']}")
    add("")

    add("GNSS OUTAGE MASKS  (software, applied at read time only)")
    if not report["masks"]:
        add("  none declared")
    for mask_id, mask in report["masks"].items():
        add(f"  {mask_id}  {mask['intervals']} interval(s)"
            f"  {mask['masked_duration_s']} s"
            f"  {mask['provenance']}/{mask['policy']}")
    effect = report["mask_effect"]
    if effect:
        add("")
        add("MASKED REPLAY EFFECT  (measured on the primary session's input stream)")
        for key, counts in effect.items():
            add(f"  {key:<16} {counts['total_gnss']:>7} GNSS seen"
                f"  {counts['withheld_gnss']:>7} withheld"
                f"  {counts['records_yielded']:>9} records yielded")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Timeline and summary for one experiment.")
    parser.add_argument("--experiment", type=Path, required=True,
                        help="sealed experiment directory")
    parser.add_argument("--mask", action="append", default=[], metavar="MASK_ID",
                        help="apply a declared mask and report what it withholds "
                             "(repeatable)")
    parser.add_argument("--json", action="store_true", help="emit the report as JSON")
    args = parser.parse_args(argv)
    try:
        experiment = open_experiment(args.experiment)
        for mask_id in args.mask:
            if mask_id not in experiment.masks:
                print(json.dumps({"rejected": "UNKNOWN_MASK", "path": mask_id}), flush=True)
                return 3
        report = summarize(experiment, list(args.mask))
    except (ExperimentError, ExperimentContractError, SessionError, IntegrityError) as exc:
        print(json.dumps({"rejected": getattr(exc, "code", "CONTRACT"),
                          "path": getattr(exc, "path", ""),
                          "detail": getattr(exc, "detail", "")}), flush=True)
        return 3
    except OSError as exc:
        print(json.dumps({"io_error": exc.__class__.__name__}), flush=True)
        return 4
    print(json.dumps(report, indent=2) if args.json else render(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
