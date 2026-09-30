"""Read-only measurement of GNSS quality observables across real recordings.

`docs/PS26168_Non_ML_Baseline_Navigation_System.md` section 3 fixes the shape of the
GNSS quality state machine and states that the exact thresholds require validation.
This tool produces that validation evidence: it streams every real session through the
same reader the phone uses and reports the distributions of the observables the state
machine consumes (fix age, horizontal/vertical accuracy, satellites used, speed/bearing
availability, receipt delay), then evaluates a grid of candidate thresholds against
those measured bytes.

It is deliberately a measurement, not a fit: nothing here tunes anything, and no
threshold is chosen automatically. The numbers are printed so a policy can cite them,
and the candidate grids show what each candidate would have cost on real data.

Nothing in a recording is modified, and no position is printed.

    python tools/gnss_quality_thresholds.py --root mobile/artifacts
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from contracts.recording.v1.session import (  # noqa: E402
    MEASUREMENTS_NAME,
    METADATA_NAME,
    SessionError,
    open_session,
)

#: Candidate staleness bounds in seconds. 5 s is the value the acquisition layer
#: currently applies (`FIX_STALE_NS` in Acquisition.kt, `ACQUISITION.md`), which the
#: documentation itself labels unvalidated diagnostic policy; the grid shows what
#: every alternative would have meant on the recorded corpus.
STALE_CANDIDATES_S = (1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0, 60.0)

#: Candidate horizontal-accuracy bounds in metres. `Location.getAccuracy()` is the
#: provider's own 68% horizontal radius estimate, so these are 1-sigma radii, not
#: guaranteed errors.
HORIZONTAL_ACCURACY_CANDIDATES_M = (3.0, 5.0, 10.0, 15.0, 20.0, 30.0, 50.0, 100.0)

#: Candidate "usable geometry" bounds on satellites used. Four is the arithmetic
#: minimum for a 3D position fix; higher bounds demand redundancy.
SATELLITE_CANDIDATES = (3, 4, 5, 6, 8, 10)


def percentile(values: Sequence[float], fraction: float) -> float | None:
    """Nearest-rank percentile on a copy of `values`. No interpolation is invented."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


def profile(values: Sequence[float]) -> dict:
    """Distribution summary with one stated method: nearest-rank percentiles."""
    ordered = sorted(values)
    return {
        "count": len(ordered),
        "min": ordered[0] if ordered else None,
        "p50": percentile(ordered, 0.50),
        "p90": percentile(ordered, 0.90),
        "p95": percentile(ordered, 0.95),
        "p99": percentile(ordered, 0.99),
        "max": ordered[-1] if ordered else None,
        "method": "nearest-rank percentile",
    }


@dataclass(slots=True)
class Fix:
    """One recorded GNSS measurement, reduced to what the quality policy consumes."""

    session: str
    provider: str
    t_ns: int
    received_ns: int
    horizontal_accuracy_m: float | None
    vertical_accuracy_m: float | None
    satellites_used: int | None
    speed_m_s: float | None
    bearing_deg: float | None


@dataclass(slots=True)
class SessionFacts:
    recording_id: str
    source: str
    completion: str
    duration_s: float | None
    calibration: str
    fix_count: int = 0
    providers: Counter[str] = field(default_factory=Counter)


def discover_sessions(root: Path) -> list[Path]:
    """Every directory that carries both session files, newest name order irrelevant."""
    return sorted(
        directory
        for directory in root.rglob("*")
        if directory.is_dir()
        and (directory / METADATA_NAME).is_file()
        and (directory / MEASUREMENTS_NAME).is_file()
    )


def read_session(directory: Path) -> tuple[SessionFacts, list[Fix]]:
    content = open_session(directory)
    try:
        metadata = content.metadata
        clock = metadata.clock
        facts = SessionFacts(
            recording_id=metadata.recording_id,
            source=metadata.source.value,
            completion=metadata.completion_state.value,
            duration_s=(
                None if clock.ended_ns is None else (clock.ended_ns - clock.started_ns) / 1e9
            ),
            calibration=metadata.calibration.state.value,
        )
        fixes: list[Fix] = []
        for record in content.records:
            data = record.event.data
            if data.TYPE != "gnss":
                continue
            facts.fix_count += 1
            facts.providers[data.provider] += 1
            fixes.append(
                Fix(
                    session=metadata.recording_id,
                    provider=data.provider,
                    t_ns=record.event.t_ns,
                    received_ns=record.event.received_ns,
                    horizontal_accuracy_m=data.horizontal_accuracy_m,
                    vertical_accuracy_m=data.vertical_accuracy_m,
                    satellites_used=data.satellites_used,
                    speed_m_s=data.speed_m_s,
                    bearing_deg=data.bearing_deg,
                )
            )
        return facts, fixes
    finally:
        content.close()


def channel_intervals_s(fixes: Iterable[Fix]) -> dict[str, list[float]]:
    """Inter-fix intervals in seconds per provider channel, in timestamp order.

    Intervals are formed within one session only. Consecutive fixes from different
    recordings are days apart and would otherwise be reported as one enormous
    inter-fix gap, which is a property of the corpus, not of the provider.
    """
    per_channel: dict[tuple[str, str], list[int]] = {}
    for fix in fixes:
        per_channel.setdefault((fix.session, fix.provider), []).append(fix.t_ns)
    intervals: dict[str, list[float]] = {}
    for (_, provider), times in per_channel.items():
        times.sort()
        intervals.setdefault(provider, []).extend(
            (later - earlier) / 1e9 for earlier, later in zip(times, times[1:])
        )
    return intervals


def known(values: Iterable[float | int | None]) -> list[float]:
    return [float(value) for value in values if value is not None]


def repeats(values: Iterable[float | int | None], limit: int = 5) -> list[dict]:
    """Most frequently repeated exact values.

    Providers emit placeholder radii (a round 100 m, a round 1 m) instead of admitting
    that accuracy is unknown. Repeats are how that shows up here, so they are reported
    rather than averaged away.
    """
    counts = Counter(known(values))
    return [
        {"value": value, "count": count}
        for value, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:limit]
    ]


def availability(fixes: Sequence[Fix], attribute: str) -> dict:
    """Present/absent/null counts for one optional field. n/a is measured, not assumed."""
    absent = sum(1 for fix in fixes if getattr(fix, attribute) is None)
    return {"present": len(fixes) - absent, "null": absent, "total": len(fixes)}


def staleness_grid(intervals: dict[str, list[float]]) -> dict:
    """What each candidate stale bound would have done to real fix intervals.

    `over_bound` counts intervals longer than the bound, which is exactly the set of
    arrivals at which the newest fix was already stale. It is a false-STALE cost: a
    normal-operating interval above the bound means the policy calls a healthy channel
    stale between two perfectly good fixes.
    """
    grid: dict[str, dict] = {}
    for bound in STALE_CANDIDATES_S:
        entry: dict[str, dict] = {}
        for provider, values in sorted(intervals.items()):
            over = [value for value in values if value > bound]
            entry[provider] = {
                "intervals": len(values),
                "over_bound": len(over),
                "over_bound_fraction": (
                    None if not values else round(len(over) / len(values), 4)
                ),
                "max_over_bound_s": max(over) if over else None,
            }
        grid[f"{bound:g}s"] = entry
    return grid


def accuracy_grid(fixes: Sequence[Fix], attribute: str, candidates: Sequence[float]) -> dict:
    """Classification cost of each candidate bound, including unvalidatable fixes."""
    by_provider: dict[str, list[Fix]] = {}
    for fix in fixes:
        by_provider.setdefault(fix.provider, []).append(fix)
    grid: dict[str, dict] = {}
    for bound in candidates:
        entry: dict[str, dict] = {}
        for provider, group in sorted(by_provider.items()):
            values = known(getattr(fix, attribute) for fix in group)
            within = sum(1 for value in values if value <= bound)
            entry[provider] = {
                "known": len(values),
                "unknown": len(group) - len(values),
                "within_bound": within,
                "within_bound_fraction": (
                    None if not values else round(within / len(values), 4)
                ),
            }
        grid[f"{bound:g}m"] = entry
    return grid


def satellite_grid(fixes: Sequence[Fix], candidates: Sequence[int]) -> dict:
    by_provider: dict[str, list[Fix]] = {}
    for fix in fixes:
        by_provider.setdefault(fix.provider, []).append(fix)
    grid: dict[str, dict] = {}
    for bound in candidates:
        entry: dict[str, dict] = {}
        for provider, group in sorted(by_provider.items()):
            values = known(fix.satellites_used for fix in group)
            at_least = sum(1 for value in values if value >= bound)
            entry[provider] = {
                "known": len(values),
                "unknown": len(group) - len(values),
                "at_least_bound": at_least,
                "at_least_bound_fraction": (
                    None if not values else round(at_least / len(values), 4)
                ),
            }
        grid[str(bound)] = entry
    return grid


def build(root: Path) -> dict:
    directories = discover_sessions(root)
    sessions: list[dict] = []
    all_fixes: list[Fix] = []
    missing: list[str] = []
    for directory in directories:
        try:
            facts, fixes = read_session(directory)
        except SessionError as exc:
            missing.append(f"{directory.name}:{exc.code}")
            continue
        all_fixes.extend(fixes)
        sessions.append(
            {
                "recording_id": facts.recording_id,
                "source": facts.source,
                "completion": facts.completion,
                "duration_s": None if facts.duration_s is None else round(facts.duration_s, 3),
                "calibration": facts.calibration,
                "fix_count": facts.fix_count,
                "providers": dict(sorted(facts.providers.items())),
            }
        )
    intervals = channel_intervals_s(all_fixes)
    receipt_delay_ms = [
        (fix.received_ns - fix.t_ns) / 1e6 for fix in all_fixes if fix.received_ns >= fix.t_ns
    ]
    provider_counts = Counter(fix.provider for fix in all_fixes)
    per_provider = {}
    for provider in sorted(provider_counts):
        group = [fix for fix in all_fixes if fix.provider == provider]
        per_provider[provider] = {
            "fixes": len(group),
            "inter_fix_interval_s": profile(intervals.get(provider, [])),
            "horizontal_accuracy_m": profile(known(fix.horizontal_accuracy_m for fix in group)),
            "vertical_accuracy_m": profile(known(fix.vertical_accuracy_m for fix in group)),
            "satellites_used": profile(known(fix.satellites_used for fix in group)),
            "horizontal_accuracy_repeats": repeats(
                fix.horizontal_accuracy_m for fix in group
            ),
            "vertical_accuracy_repeats": repeats(fix.vertical_accuracy_m for fix in group),
            "receipt_delay_ms": profile(
                [
                    (fix.received_ns - fix.t_ns) / 1e6
                    for fix in group
                    if fix.received_ns >= fix.t_ns
                ]
            ),
        }
    sessions_with_gnss = sum(1 for session in sessions if session["fix_count"])
    return {
        "corpus": {
            "root": str(root),
            "sessions_discovered": len(directories),
            "sessions_read": len(sessions),
            "sessions_unreadable": missing,
            "sessions_with_gnss": sessions_with_gnss,
            "sessions_without_gnss": len(sessions) - sessions_with_gnss,
            "fixes": len(all_fixes),
            "providers": dict(sorted(provider_counts.items())),
        },
        "census": {
            "horizontal_accuracy_m": profile(known(f.horizontal_accuracy_m for f in all_fixes)),
            "vertical_accuracy_m": profile(known(f.vertical_accuracy_m for f in all_fixes)),
            "satellites_used": profile(known(f.satellites_used for f in all_fixes)),
            "receipt_delay_ms": profile(receipt_delay_ms),
            # Pooled across providers on purpose only in name: the per-provider rows below
            # are the ones a stale bound may be derived from, because a 1 Hz channel and a
            # 0.05 Hz channel have no shared cadence.
            "inter_fix_interval_all_providers_s": profile(
                [value for values in intervals.values() for value in values]
            ),
            "horizontal_accuracy_repeats": repeats(f.horizontal_accuracy_m for f in all_fixes),
            "vertical_accuracy_repeats": repeats(f.vertical_accuracy_m for f in all_fixes),
        },
        "availability": {
            "speed_m_s": availability(all_fixes, "speed_m_s"),
            "bearing_deg": availability(all_fixes, "bearing_deg"),
            "horizontal_accuracy_m": availability(all_fixes, "horizontal_accuracy_m"),
            "vertical_accuracy_m": availability(all_fixes, "vertical_accuracy_m"),
            "satellites_used": availability(all_fixes, "satellites_used"),
        },
        "per_provider": per_provider,
        "candidate_stale_bounds": staleness_grid(intervals),
        "candidate_horizontal_accuracy_bounds": accuracy_grid(
            all_fixes, "horizontal_accuracy_m", HORIZONTAL_ACCURACY_CANDIDATES_M
        ),
        "candidate_satellite_bounds": satellite_grid(all_fixes, SATELLITE_CANDIDATES),
        "sessions": sessions,
    }


def markdown(report: dict) -> str:
    corpus = report["corpus"]
    census = report["census"]
    lines = [
        "# GNSS quality thresholds — measurement against recorded data",
        "",
        "Produced by `tools/gnss_quality_thresholds.py` (read-only). Percentiles are",
        "nearest-rank; `Location.getAccuracy()` is the provider's own 68% horizontal",
        "radius estimate, so accuracy figures are 1-sigma radii and not guaranteed errors.",
        "",
        "## Corpus",
        "",
        f"- sessions discovered {corpus['sessions_discovered']}, read {corpus['sessions_read']}",
        f"- sessions carrying GNSS {corpus['sessions_with_gnss']}, "
        f"carrying none {corpus['sessions_without_gnss']}",
        f"- fixes {corpus['fixes']} across providers {corpus['providers']}",
        "",
        "| observable | count | min | p50 | p90 | p95 | p99 | max |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for name, stats in census.items():
        if "count" not in stats:
            continue
        if not stats["count"]:
            lines.append(f"| {name} | 0 | — | — | — | — | — | — |")
            continue
        lines.append(
            f"| {name} | {stats['count']} | {stats['min']:.4g} | {stats['p50']:.4g} | "
            f"{stats['p90']:.4g} | {stats['p95']:.4g} | {stats['p99']:.4g} | "
            f"{stats['max']:.4g} |"
        )
    lines += ["", "## Optional-field availability", "", "| field | present | null | total |",
              "|---|---|---|---|"]
    for name, entry in report["availability"].items():
        lines.append(f"| {name} | {entry['present']} | {entry['null']} | {entry['total']} |")
    lines += ["", "## Candidate stale bounds: intervals a bound would misreport", ""]
    lines.append("`over_bound` counts real inter-fix intervals longer than the bound; each one")
    lines.append("is an arrival at which a healthy channel was already called stale.")
    lines += ["", "| bound | provider | intervals | over bound | fraction | max over (s) |",
              "|---|---|---|---|---|---|"]
    for bound, providers in report["candidate_stale_bounds"].items():
        for provider, entry in providers.items():
            fraction = entry["over_bound_fraction"]
            worst = entry["max_over_bound_s"]
            lines.append(
                f"| {bound} | {provider} | {entry['intervals']} | {entry['over_bound']} | "
                f"{'—' if fraction is None else f'{fraction:.4f}'} | "
                f"{'—' if worst is None else round(worst, 3)} |"
            )
    lines += ["", "## Candidate horizontal-accuracy bounds", "", "| bound | provider | known |",
              "unknown | within bound | fraction |", "|---|---|---|---|---|---|"]
    for bound, providers in report["candidate_horizontal_accuracy_bounds"].items():
        for provider, entry in providers.items():
            fraction = entry["within_bound_fraction"]
            lines.append(
                f"| {bound} | {provider} | {entry['known']} | {entry['unknown']} | "
                f"{entry['within_bound']} | {'—' if fraction is None else f'{fraction:.4f}'} |"
            )
    lines += ["", "## Candidate satellite-count bounds", "", "| bound | provider | known |",
              "unknown | at least | fraction |", "|---|---|---|---|---|---|"]
    for bound, providers in report["candidate_satellite_bounds"].items():
        for provider, entry in providers.items():
            fraction = entry["at_least_bound_fraction"]
            lines.append(
                f"| {bound} | {provider} | {entry['known']} | {entry['unknown']} | "
                f"{entry['at_least_bound']} | {'—' if fraction is None else f'{fraction:.4f}'} |"
            )
    lines += ["", "## Per-provider detail", ""]
    for provider, entry in report["per_provider"].items():
        interval = entry["inter_fix_interval_s"]
        horizontal = entry["horizontal_accuracy_m"]
        satellites = entry["satellites_used"]
        lines.append(f"### {provider}")
        lines.append("")
        lines.append(f"- fixes {entry['fixes']}")
        lines.append(
            f"- inter-fix interval s: min {interval['min']} p50 {interval['p50']} "
            f"p99 {interval['p99']} max {interval['max']}"
        )
        lines.append(
            f"- horizontal accuracy m: min {horizontal['min']} p50 {horizontal['p50']} "
            f"max {horizontal['max']}"
        )
        lines.append(
            f"- satellites used: min {satellites['min']} p50 {satellites['p50']} "
            f"max {satellites['max']}"
        )
        lines.append(f"- horizontal accuracy repeats: {entry['horizontal_accuracy_repeats']}")
        lines.append(f"- vertical accuracy repeats: {entry['vertical_accuracy_repeats']}")
        lines.append("")
    lines += ["", "## Session inventory", "", "| recording | duration s | fixes | providers |",
              "|---|---|---|---|"]
    for session in report["sessions"]:
        lines.append(
            f"| {session['recording_id']} | {session['duration_s']} | {session['fix_count']} | "
            f"{session['providers']} |"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Measure GNSS quality observables, read-only.")
    parser.add_argument("--root", type=Path, default=Path("mobile/artifacts"),
                        help="directory to search for session directories")
    parser.add_argument("--out", type=Path,
                        default=Path("reports/gnss_quality_thresholds_2026_09_30.json"))
    parser.add_argument("--md", type=Path,
                        default=Path("reports/gnss_quality_thresholds_2026_09_30.md"))
    args = parser.parse_args(argv)
    if not args.root.is_dir():
        parser.error(f"not a directory: {args.root}")
    report = build(args.root)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    args.md.write_text(markdown(report), encoding="utf-8")
    print(json.dumps(report["corpus"], indent=2))
    print(json.dumps(report["availability"], indent=2))
    print(json.dumps(report["census"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
