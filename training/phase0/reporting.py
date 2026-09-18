"""Render the requested Markdown reports from measured, machine-readable evidence."""

from __future__ import annotations

from collections import Counter


def fmt(value, digits=3):
    if value is None:
        return "unavailable"
    return f"{value:,.{digits}f}" if isinstance(value, (float, int)) else str(value)


def table(headers, rows):
    def escape(x):
        return str(x).replace("|", "\\|").replace("\n", " ")
    return ["| " + " | ".join(map(escape, headers)) + " |",
            "| " + " | ".join("---" for _ in headers) + " |",
            *["| " + " | ".join(map(escape, r)) + " |" for r in rows], ""]


def render_reports(inv, reg, ev):
    files, pairs = inv["files"], ev["pairs"]
    counts = inv["counts"]
    mismatches = [p for p in pairs if not p["row_count_match"]]
    exact_extra = sum(len(g) - 1 for g in inv["exact_byte_duplicate_groups"])
    payload_extra = sum(len(g) - 1 for g in inv["same_numeric_payload_groups"])
    schema = ["# Phase 0 - Verified dataset schema", "",
        "## Scope and reproducibility", "",
        "**Confirmed from data.** The audit parses every CSV in 25,000-row chunks, then loads one synchronized pair at a time for signal analysis. No raw file is repaired, resampled, relabelled or overwritten. All statistics are descriptive; source channels are retained.", "",
        "Run from the repository root with Python 3.12, NumPy and pandas:", "",
        "```powershell", "python -m training.phase0.audit --data-root data/raw/iovnbd --verify-repeat",
        "python -m unittest discover -s tests -p 'test_phase0*.py' -v", "```", "",
        "`phase0_verification.json` records runtime versions, two-pass output hashes, and before/after SHA-256, size and modification-time equality for the entire raw tree, including the nested Git repository and archives. `phase0_raw_manifest.json` contains the individual file hashes. Running without --verify-repeat explicitly records repeat verification as unperformed.", "",
        "## Dataset inventory", "", "**Confirmed from data.** Physical files include repeated copies; counts below are not independent drives.", ""]
    schema += table(["Item", "Measured count"], [
        ["Synchronized smartphone CSVs", counts.get("synchronized/smartphone", 0)],
        ["Synchronized VBOX/vehicle CSVs", counts.get("synchronized/vbox", 0)],
        ["Unsynchronized smartphone CSVs", counts.get("unsynchronized/smartphone", 0)],
        ["Unsynchronized vehicle CSVs", counts.get("unsynchronized/vbox", 0)],
        ["Route images", inv["route_image_count"]], ["CSV files", inv["csv_file_count"]],
        ["CSV bytes", inv["csv_bytes"]], ["CSV rows including duplicates", inv["csv_rows_including_duplicate_copies"]],
        ["Schema families (exact decoded headers)", len(reg["families"])],
        ["Synchronized pairs (categorized + uncategorized)", len(pairs)],
        ["Pairs with unequal row counts", len(mismatches)],
        ["Unmatched/ambiguous synchronized groups", len(inv["unmatched_synchronized_groups"])],
        ["Unsynchronized filename-only S/V matches", len(inv["unsynchronized_filename_matches"])],
        ["Unsynchronized filename matches with unequal rows", sum(not p["row_count_match"] for p in inv["unsynchronized_filename_matches"])],
        ["Unmatched unsynchronized files", len(inv["unmatched_unsynchronized_files"])],
        ["Byte-identical duplicate groups / redundant CSV copies", f"{len(inv['exact_byte_duplicate_groups'])} / {exact_extra}"],
        ["Same normalized numeric payload groups / redundant CSV copies", f"{len(inv['same_numeric_payload_groups'])} / {payload_extra}"],
        ["Candidate partial recording overlaps", len(inv["overlap_candidates"])]])
    schema += ["Full per-file paths, byte sizes, row counts, drivers, original and normalized sequence IDs, encodings, timing statistics, coordinate validity and column missing-value counts are in `phase0_inventory.json`. Unsynchronized filename matches include both paths and row counts but do not establish alignment. Exact duplicate groups use SHA-256 of raw bytes. Numeric-payload groups hash ordered pandas row hashes after numeric dtype normalization and trimming text; header names are deliberately excluded. Candidate partial overlaps use the 64 smallest distinct row fingerprints within the same sequence/source and are not an exhaustive subsequence search.", "",
        "## Drivers and sequences", "",
        "**Confirmed from data** for explicit folder labels. **Inferred with supporting evidence** when inherited from another categorized copy. **Stated by dataset documentation** for drivers F/G/H from Table A7. Unknown assignments remain unknown.", ""]
    schema += table(["Driver", "Sequence IDs"], [[d, ", ".join(seqs)] for d, seqs in inv["drivers"].items()])
    schema += ["## Schema families", ""]
    schema += table(["ID", "Source", "Files", "Columns", "Rows including duplicates", "Representative"],
        [[f["id"], f["source"], f["file_count"], len(f["columns"]), f["total_rows_including_duplicate_copies"], f["representative_file"]] for f in reg["families"]])
    shifted = [f for f in files if f["schema_mapping_status"] != "header_consistent"]
    schema += ["### Header/data contradictions", "",
        "**Confirmed from data.** S-A4 has 25 header/data cells, but the extra empty data cell is at zero-based index 6 while the extra empty header is at index 24. A header-only loader incorrectly reads elapsed milliseconds as DATE, DATE as acceleration, and gravity as gyro. **Inferred with supporting evidence.** The audit records a one-column suffix remapping: satellites at 7, elapsed counter at 8, DATE at 9, acceleration beginning at 10, and the last orientation at 24. All recovered DATE values and the empty column are validated across the complete file. Original positional headers and header-derived names remain in the registry; no CSV is rewritten. S-A4 remains quarantined from training pending independent structural review.", ""]
    schema += table(["File", "Rows", "Mapping status", "Recovered missing DATE count"],
        [[f["path"], f["rows"], f["schema_mapping_status"], f["timing"]["missing_timestamps"]] for f in shifted])
    missing = [(f["path"], sum(c["missing_count"] for c in f["column_observations"]),
                sum(c["nonfinite_numeric_count"] for c in f["column_observations"])) for f in files]
    problematic = [r for r in missing if r[1] or r[2]]
    schema += ["## Missing and nonfinite values", "", f"**Confirmed from data.** {len(problematic)} files contain parsed missing or nonfinite numeric cells. Empty trailing CSV columns are counted separately in the registry. Nonnumeric satellite strings and DATE text are expected; they are not interpreted as missing numeric sensor measurements.", ""]
    if problematic:
        schema += table(["File", "Missing cells", "Nonfinite numeric cells"], problematic)
    schema += ["## Sampling evidence", "",
        "**Stated by dataset documentation.** README_1.pdf page 2 describes 10 Hz sensor logging and a 1 Hz smartphone GPS update. **Confirmed from data.** The tables below report timestamp spacing and observed value-change spacing. Repeated values are consistent with held or quantized measurements but do not independently identify the physical sensor's update rate. Stationary GPS can legitimately remain unchanged.", ""]
    selected = set()
    for family in reg["families"]:
        selected.add(family["representative_file"])
    wanted = {"m", "s1", "s2", "s3a", "s3c", "y1", "vfa01", "vta1a", "vta25", "vtb1", "vw1", "vw15"}
    selected.update(f["path"] for f in files if f["partition"] == "synchronized" and f["layout"] == "categorized" and f["sequence"] in wanted)
    sampling = [f for f in files if f["path"] in selected]
    schema += table(["File", "Rows", "Median dt (s)", "Rate (Hz)", "GPS median change interval (s)", "Repeated / backward / missing time", "Max dt (s)", "Gaps"],
        [[f["path"], f["rows"], fmt(f["timing"]["positive_interval_median_s"]), fmt(f["timing"]["nominal_observed_rate_hz"]),
          fmt(f["gps_value_changes"]["between_change_intervals_s"]["median"]),
          f"{f['timing']['repeated_timestamps']} / {f['timing']['negative_intervals']} / {f['timing']['missing_timestamps']}",
          fmt(f["timing"]["intervals_s"]["max"]), f["timing"]["large_gap_count"]] for f in sampling])
    schema += table(["Smartphone representative", "Rows/elapsed second", "IMU value-change rate (Hz)", "GPS value-change rate (Hz)", "Unchanged GPS adjacent fraction"],
        [[f["path"], fmt(f["timing"]["rows_per_elapsed_second"]), fmt(f["imu_value_changes"]["typical_value_change_rate_hz"]),
          fmt(f["gps_value_changes"]["typical_value_change_rate_hz"]), fmt(f["gps_value_changes"]["unchanged_fraction"], 5)] for f in sampling if f["source"] == "smartphone"])
    synchronized_phones = [f for f in files if f["source"] == "smartphone" and f["partition"] == "synchronized"]
    gps_rates = Counter(round(f["gps_value_changes"]["typical_value_change_rate_hz"], 3) if f["gps_value_changes"]["typical_value_change_rate_hz"] is not None else "unidentifiable" for f in synchronized_phones)
    schema += [f"**Confirmed from data.** Synchronized smartphone observed GPS change-rate counts (Hz rounded to 0.001; physical copies counted): {dict(sorted(gps_rates.items(), key=lambda x: str(x[0])))}. GPS values commonly persist for many 10 Hz IMU rows. The recorded cadence does not support assuming that every tenth row contains a fresh 1 Hz fix.", "",
        "**Inferred with supporting evidence.** Nominal synchronized IMU logging is approximately 10 Hz where DATE spacing and changing IMU values agree. Some unsynchronized A-series files are logged at 2 Hz. T1/T6 contain bursts with 1 ms positive timestamp differences and many identical timestamps; the inverse-positive-median statistic near 1,000 Hz is not a credible hardware sampling-rate conclusion. Use whole-duration row throughput, repetition counts, and interval distributions together. Their acquisition timing remains unresolved.", ""]
    largest_gaps = sorted(files, key=lambda f: f["timing"]["intervals_s"]["max"] or 0, reverse=True)[:8]
    schema += table(["Largest recorded intervals: file", "Maximum dt (s)", "Large-gap count"],
        [[f["path"], fmt(f["timing"]["intervals_s"]["max"]), f["timing"]["large_gap_count"]] for f in largest_gaps])
    schema += ["Every file, including every schema family's representative, has interval distributions (mean, median, min, p01, p95, p99, max, standard deviation), missing/repeated/backward timestamps, gap indices, GPS hold runs and estimated missing slots in the inventory. Smartphone files also have elapsed-counter and IMU value-change diagnostics.", "",
        "**Still unresolved.** Equal row counts or a nominal 10 Hz clock cannot establish synchronized sensor acquisition. A row clock is acceptable for plotting row-index diagnostics, not automatically for mechanization or supervised labels. A recorded gap may be a clock discontinuity, dropped samples, manual editing, or a combination. No synthetic samples are inserted.", "",
        "## Phase 0 handoff", "",
        "Proceed to body-frame investigation using the explicitly retained raw gyro channels and independently checked timing. Mechanization correction must wait for frame, gravity-source semantics and causal initialization decisions. Training remains conditional on verified inertial-label timing, recording-group splits and a credible baseline.", ""]

    dictionary = ["# Phase 0 - Data dictionary and unit evidence", "",
        "Each registry family preserves the exact decoded source header. Leading whitespace and mojibake are normalized for internal identifiers only. Gyro channel labels such as pitch or X remain labels; they are not a physical phone-to-vehicle mapping.", "",
        "`schema_registry.json` stores, for every observed column: original/normalized name, source and inferred units, normalized unit, confidence, evidence label, observed dtypes, missing/nonfinite/nonnumeric counts, range, measurement type, held-value assessment, training-feature policy, and reference-only status. Empirical units are scoped to listed paired sequences. A shared header alone cannot prove identical units in all files.", "",
        "## Common data contract", "",
        "Canonical numeric units are m, s, m/s, m/s², rad/s, degrees for geographic/course angles, and µT. Unresolved speed/height values retain `_raw` identifiers. `convert_unit` requires an explicit source and target unit and never guesses. `read_frame` renames columns but does not silently convert source values. Conversion decisions must be selected from sequence evidence.", "",
        "All VBOX/CAN fields are excluded from deployable smartphone-model inputs. VBOX latitude, longitude, speed and heading are reference labels only, subject to validity checks. Smartphone GNSS fields are excluded from outage-model features and may be used only for trusted pre-outage initialization or explicitly defined GNSS-available context. Raw IMU/gravity/magnetic fields are candidate features after frame and causal preprocessing validation; device orientation and satellite strings are diagnostic until their semantics are resolved.", ""]
    for family in reg["families"]:
        dictionary += [f"## {family['id']} ({family['source']}; {family['file_count']} files)", "",
                       "**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.", ""]
        dictionary += table(["Original header", "Internal name", "Dtypes", "Documented unit", "Inferred unit", "Normalized unit", "Type", "Missing", "Training policy"],
            [[c["original_name"] or "(blank trailing column)", c["normalized_name"], ", ".join(c["observed_dtypes"]),
              c["documented_unit"] or "n/a", c["empirically_inferred_unit"] or "unresolved", c["normalized_unit"] or "n/a",
              c["measurement_type"], c["missing_count"], "candidate after validation" if c["permitted_as_training_feature"] else "excluded / metadata"] for c in family["columns"]])
    dictionary += ["## Smartphone speed hypotheses", "",
        "**Inferred with supporting evidence.** Compare the same numeric smartphone speed as km/h versus multiplying it by 3.6, both against VBOX km/h, before fitted time shifts. The latter tests the m/s hypothesis. Positive scaling leaves correlation unchanged, so correlation alone cannot distinguish the units. Unit decisions require moving data (reference standard deviation >5 km/h and mean >5 km/h), and one hypothesis must at least halve MAE. Stationary/ambiguous files remain unresolved.", ""]
    unit_counts = Counter(p["units"]["speed_unit_inference"] for p in pairs)
    dictionary += [f"**Inferred with supporting evidence.** Per-pair unit decisions: {dict(sorted(unit_counts.items()))}. These are physical pair copies, not independent experiments. Choose m/s only for the sequences supported by the evidence. Unresolved files retain raw speed; any m/s comparison shown for them is explicitly a diagnostic hypothesis, not a conversion decision.", ""]
    dictionary += table(["Pair", "Raw MAE / RMSE (km/h)", "×3.6 MAE / RMSE (km/h)", "Correlation", "Means raw / ×3.6 / VBOX", "Inference"],
        [[p["id"], f"{fmt(p['units']['speed_raw_assumed_kmh_vs_vbox_kmh']['mae'])} / {fmt(p['units']['speed_raw_assumed_kmh_vs_vbox_kmh']['rmse'])}",
          f"{fmt(p['units']['speed_x3_6_vs_vbox_kmh']['mae'])} / {fmt(p['units']['speed_x3_6_vs_vbox_kmh']['rmse'])}",
          fmt(p["units"]["speed_x3_6_vs_vbox_kmh"]["correlation"]),
          " / ".join(fmt(x) for x in [p["units"]["speed_raw_assumed_kmh_vs_vbox_kmh"]["predicted"]["mean"], p["units"]["speed_x3_6_vs_vbox_kmh"]["predicted"]["mean"], p["units"]["speed_x3_6_vs_vbox_kmh"]["reference"]["mean"]]),
          p["units"]["speed_unit_inference"]] for p in pairs])
    m_pair = next(p for p in pairs if p["id"] == "categorized/m")
    m_units = m_pair["units"]
    dictionary += ["### M speed distributions", "", "**Confirmed from data.** Same-row comparisons before any fitted lag:", ""]
    dictionary += table(["Series / hypothesis", "Mean", "Median", "Maximum", "MAE vs VBOX (km/h)", "RMSE vs VBOX (km/h)", "Correlation"],
        [[label, *[fmt(metric["predicted"][k]) for k in ("mean", "median", "max")], fmt(metric["mae"]), fmt(metric["rmse"]), fmt(metric["correlation"], 5)]
          for label, metric in [("Smartphone raw, falsely treated as km/h", m_units["speed_raw_assumed_kmh_vs_vbox_kmh"]),
                                ("Smartphone raw ×3.6 as km/h", m_units["speed_x3_6_vs_vbox_kmh"])]]
        + [["VBOX km/h", *[fmt(m_units["speed_x3_6_vs_vbox_kmh"]["reference"][k]) for k in ("mean", "median", "max")], "reference", "reference", "reference"]])
    dictionary += ["For every pair and hypothesis, `phase0_evidence.json` additionally records input/reference mean, median, minimum and maximum; signed and absolute error mean, median, p95 and maximum; sample count; regression slope/intercept. These figures include all finite paired rows and therefore quantify forward-fill/lag effects as well as units.", "",
        "## Stationary gravity, acceleration, gyro and height", "",
        "**Inferred with supporting evidence.** Stationary candidates use reference speed <0.5 km/h for a contiguous nominal 3 s window; this is an audit selection, not an on-device stationary detector. Reference faults and sensor latency may contaminate these windows. Acceleration tests compare raw and ×9.80665 vehicle longitudinal acceleration with the reference speed derivative (11-row smoothing). Turn tests compare ±vehicle lateral acceleration ×9.80665 against v×yaw-rate. Gyro scale tests compare each raw channel, both signs, under rad/s and deg/s hypotheses against vehicle yaw rate. No frame is selected or changed.", ""]
    diagnostic_pairs = [p for p in pairs if p["layout"] == "categorized" and p["sequence"] in wanted]
    dictionary += table(["Pair", "Stationary rows", "Accel / gravity norm means (m/s²)", "Residual minus / plus gravity means", "Long accel MAE raw / ×g0 (m/s²)", "Best rad/s channel (diagnostic)", "Gyro MAE rad/s hypothesis / deg/s hypothesis (deg/s)", "Height MAE as m / as km (m)"],
        [[p["id"], p["units"]["stationary_samples"],
          f"{fmt(p['units']['stationary_accel_norm_m_s2']['mean'])} / {fmt(p['units']['stationary_gravity_norm_m_s2']['mean'])}",
          f"{fmt(p['units']['stationary_accel_minus_gravity_norm_m_s2']['mean'])} / {fmt(p['units']['stationary_accel_plus_gravity_norm_m_s2']['mean'])}",
          f"{fmt(p['units']['long_accel_raw_vs_speed_derivative']['mae'])} / {fmt(p['units']['long_accel_times_g0_vs_speed_derivative']['mae'])}",
          f"{p['units']['gyro_channel_tests'][0]['sign']:+d} {p['units']['gyro_channel_tests'][0]['channel']}",
          f"{fmt(p['units']['gyro_channel_tests'][0]['assuming_rad_s']['mae'])} / {fmt(p['units']['gyro_channel_tests'][0]['assuming_deg_s']['mae'])}",
          f"{fmt(p['units']['height_assumed_m_vs_phone_altitude_m']['mae'])} / {fmt(p['units']['height_assumed_km_vs_phone_altitude_m']['mae'])}"] for p in diagnostic_pairs])
    gyro_rows = []
    for p in diagnostic_pairs:
        g = max(p["units"]["gyro_channel_tests"], key=lambda x: x["assuming_rad_s"]["correlation"] if x["assuming_rad_s"]["correlation"] is not None else -2)
        gyro_rows.append([p["id"], f"{g['sign']:+d} {g['channel']}", fmt(g["assuming_rad_s"]["correlation"], 5),
                         fmt(g["assuming_rad_s"]["slope_predicted_per_reference"]), fmt(g["assuming_deg_s"]["slope_predicted_per_reference"]),
                         fmt(g["assuming_rad_s"]["rmse"]), fmt(g["assuming_deg_s"]["rmse"])])
    dictionary += ["**Inferred with supporting evidence.** The highest-correlation gyro channel below is a scale diagnostic, not a proposed frame mapping. Near-zero yaw channels can win an RMSE ranking without representing yaw. In S1 and S3c, the Pitch-labelled channel's rad/s-to-deg/s slope is near one, whereas treating its raw values as deg/s yields a slope near 0.017. This supports rad/s there. The registry lists qualifying evidence per channel (correlation >=0.6, slope 0.4-1.6, reference yaw standard deviation >=2 deg/s, and rad/s RMSE <95% of the deg/s-hypothesis RMSE). Other channels remain empirically unresolved.", ""]
    dictionary += table(["Pair", "Highest-correlation channel", "Correlation", "Slope rad/s hypothesis", "Slope deg/s hypothesis", "RMSE rad/s hypothesis (deg/s)", "RMSE deg/s hypothesis (deg/s)"], gyro_rows)
    independent = [p for p in pairs if p["layout"] == "categorized"]
    long_support = sum(p["units"]["long_accel_times_g0_vs_speed_derivative"]["mae"] < p["units"]["long_accel_raw_vs_speed_derivative"]["mae"] for p in independent)
    dictionary += [f"**Inferred with supporting evidence.** Multiplying longitudinal acceleration by g0 reduces speed-derivative MAE in {long_support}/{len(independent)} categorized pairs. This supports g in many sequences, but the failures rule out claiming universal calibrated consistency. Smoothing excludes invalid-timestamp windows and boundary windows. Lateral results below test the g hypothesis against v×yaw rate using both possible signs; turn geometry, frame and latency remain relevant.", ""]
    dictionary += table(["Pair", "Lateral +g / -g MAE (m/s²)", "+g correlation", "Mean height difference VBOX minus phone (m), metre hypothesis"],
        [[p["id"], " / ".join(fmt(p["units"]["lateral_accel_g_vs_v_times_omega"][k]["mae"]) for k in ("+1g", "-1g")),
          fmt(p["units"]["lateral_accel_g_vs_v_times_omega"]["+1g"]["correlation"]),
          fmt(p["units"]["height_assumed_m_vs_phone_altitude_m"]["signed_error"]["mean"])] for p in diagnostic_pairs])
    unpaired_reps = [f for f in sampling if f["source"] == "smartphone" and f["partition"] == "unsynchronized"]
    dictionary += ["**Confirmed from data.** Additional unpaired schema representatives have these raw acceleration/gravity magnitudes. **Still unresolved.** Their low-speed selection relies on the phone itself, not an independent stationary reference.", ""]
    dictionary += table(["Unpaired schema representative", "All-row accel norm mean", "All-row gravity norm mean", "Phone-low-speed accel norm mean"],
        [[f["path"], *[fmt(f["unit_magnitude_checks"][k]["mean"]) for k in ("accel_norm_all", "gravity_norm_all", "accel_norm_phone_low_speed")]] for f in unpaired_reps])
    dictionary += ["**Still unresolved.** Rad/s consistency is assessed per channel and sequence; it cannot establish all gyro axes. Height magnitudes can reject an implausible kilometre interpretation in representative files but cannot resolve vertical datum, antenna offset, or validate 3-D ground truth. Vehicle satellite values and accelerator-pedal values must not be treated as literal satellite counts or binary flags without decoding. The paper's gyro table itself repeats Pitch; source labels do not settle conventions.", "",
        "**Stated by dataset documentation.** README_1.pdf pages 2-5, Tables 3-4: smartphone sensors 10 Hz, smartphone GPS 1 Hz, VBOX/vehicle velocity km/h, accelerations g, gyros rad/s, height labelled km. The paper's Table 3 identifies accelerator pedal as percent, while the CSV header labels it 0/1. Original source labels are retained even where contradicted.", ""]

    sync = ["# Phase 0 - Synchronization analysis", "",
        "## Method and interpretation", "",
        "**Stated by dataset documentation.** README_1.pdf page 3 says simultaneously collected pairs were manually synchronized. **Confirmed from data.** All available synchronized pairs in both layouts are evaluated here. Reference coordinates outside valid bounds or equal to (0,0) are excluded, with coverage reported. Nonfinite speed is excluded. Zero speed is retained. No satellite-count threshold is imposed because its encoding is unresolved.", "",
        "Smartphone DATE and VBOX time-of-day are independently rebased to first valid time. Absolute wall-clock offsets therefore do not drive elapsed-time comparisons. Duplicate VBOX times are averaged for interpolation; backward reference clocks are rejected. Interpolation never extrapolates or bridges gaps exceeding max(0.25 s, 2.5 times median positive VBOX dt). Invalid coordinate spans are excluded by the same rule.", "",
        "Constant-lag search spans -10 to +10 s, coarse 0.5 s then local 0.1 s refinement. Positive lag maps smartphone elapsed t to VBOX t+lag. A fixed deterministic stride caps coarse-search phone samples at approximately 6,000; final metrics use all rows. Candidates require >=80% coordinate coverage and >=30 valid points. The score combines normalized median position separation and speed MAE with equal weights. Position-only and speed-only optima and disjoint-third optima expose ambiguity. These fitted results are descriptive, not approved corrections.", "",
        "Affine endpoint mapping is tested only when durations differ by more than 0.25 s. It assumes endpoint correspondence, which is not established. A piecewise hypothesis subtracts excess smartphone DATE gaps using smartphone timestamps alone; it assumes clock discontinuities instead of physical data loss. Neither hypothesis changes raw or processed data.", "",
        "## Pair results", "", "**Confirmed from data** for metrics. **Inferred with supporting evidence** for approximate/uncertain/unusable classifications. No pair is declared exact solely because row counts match.", ""]
    sync += table(["Pair", "S / V rows", "Duration difference (s)", "Same-row position median / mean / p95 / max (m)", "Same-row speed MAE (m/s)", "Best joint lag (s)", "Best-lag position median / mean / p95 / max (m)", "Class"],
        [[p["id"], f"{p['smartphone_rows']} / {p['vbox_rows']}", fmt(p["duration_difference_s"]),
          " / ".join(fmt(p["same_row"]["position_separation_m"][k]) for k in ("median", "mean", "p95", "max")),
          fmt(p["same_row"]["speed_error_m_s"]["mae"]), fmt(p["best_constant_offset"]["offset_s"]),
          " / ".join(fmt(p["best_constant_offset"]["position_separation_m"][k]) for k in ("median", "mean", "p95", "max")),
          p["alignment_class"]] for p in pairs])
    if mismatches:
        sync += ["### Unequal row counts", ""] + table(["Pair", "S rows", "V rows"], [[p["id"], p["smartphone_rows"], p["vbox_rows"]] for p in mismatches])
    boundary = [p["id"] for p in pairs if p["constant_offset_at_search_boundary"]]
    sync += [f"**Still unresolved.** {len(boundary)} lag searches reach the +/-10 s boundary: {', '.join(boundary)}. These are search-limited diagnostics, not measured unique offsets. Disagreement between speed-only, position-only and segment-wise optima also limits confidence; all are recorded in the evidence JSON.", ""]
    for p in [p for p in pairs if p["sequence"] == "m"]:
        phone = next(f for f in files if f["path"] == p["smartphone_file"])
        vehicle = next(f for f in files if f["path"] == p["vbox_file"])
        sync += [f"## M (Driver B): {p['layout']}", "",
            f"**Confirmed from data.** S duration {fmt(p['smartphone_duration_s'])} s; V duration {fmt(p['vbox_duration_s'])} s; difference {fmt(p['duration_difference_s'])} s. Smartphone elapsed-counter backward steps: {phone['elapsed_counter_timing']['negative_intervals']}. DATE gaps: {phone['timing']['large_gap_count']}. VBOX repeated timestamps: {vehicle['timing']['repeated_timestamps']}; maximum interval: {fmt(vehicle['timing']['intervals_s']['max'])} s.", ""]
        sync += table(["Gap after row (zero based)", "DATE delta (s)", "Excess over median dt (s)"],
            [[g["after_row_0based"], fmt(g["delta_s"]), fmt(g["delta_s"] - phone["timing"]["positive_interval_median_s"])] for g in phone["timing"]["large_gaps"]])
        sync += [f"GPS typical observed change interval: {fmt(phone['gps_value_changes']['between_change_intervals_s']['median'])} s; longest held run: {fmt(phone['gps_value_changes']['held_run_lengths_rows']['max'], 0)} rows. This is an observation of stored values, not a measured hardware acquisition rate.", "",
                 f"Joint lag: {fmt(p['best_constant_offset']['offset_s'])} s; position-only optimum: {fmt(p['single_metric_optima_s']['position_only'])} s; speed-only optimum: {fmt(p['single_metric_optima_s']['speed_only'])} s. Disjoint thirds choose {', '.join(fmt(t['best_offset_s']) for t in p['disjoint_third_lags'])} s.", ""]
        rows = []
        for title, result in [("zero elapsed offset", p["zero_elapsed_offset"]), ("best constant", p["best_constant_offset"]), ("endpoint affine hypothesis", p["affine_diagnostic"]), ("DATE-gap piecewise hypothesis", p["piecewise_gap_diagnostic"])]:
            if result:
                rows.append([title, *[fmt(result["position_separation_m"][k]) for k in ("median", "mean", "p95", "max")], fmt(result["speed_error_m_s"]["mae"])])
        sync += table(["Clock hypothesis", "Median (m)", "Mean (m)", "p95 (m)", "Max (m)", "Speed MAE (m/s)"], rows)
    classes = Counter(p["alignment_class"] for p in pairs)
    candidates = [p["id"] for p in pairs if p["alignment_class"] == "approximate"]
    unique_candidates = sorted(set(p["sequence"] for p in pairs if p["alignment_class"] == "approximate"))
    quarantine = [p["id"] for p in pairs if p["alignment_class"] != "approximate"]
    sync += ["## Suitability and leakage controls", "",
        f"**Inferred with supporting evidence.** Classification counts: {dict(sorted(classes.items()))}. Approximate means equal rows, no backward/missing main clock, >=80% position coverage, speed correlation >=0.9, median position disagreement <=30 m and a supported speed-unit hypothesis. These conservative audit thresholds do not certify supervised label accuracy or the competition drift target.", "",
        "Conditional candidates for subsequent body-frame and inertial-label lag validation:", "", ", ".join(candidates) or "None satisfy the conservative screening thresholds.", "",
        f"Unique sequence names among these candidates ({len(unique_candidates)}): {', '.join(unique_candidates)}. Copies do not increase the number of independent recordings.", "",
        "Quarantine from direct supervised velocity training pending investigation:", "", ", ".join(quarantine) or "None under these screening thresholds.", "",
        "Stationary sequences may still be useful for bias/gravity studies even when lag or speed units are unidentifiable. Any row-mismatched pair requires explicit correspondence reconstruction before supervised use; truncating to min row count here is a diagnostic comparison, not alignment.", "",
        "Keep all categorized/uncategorized and synchronized/unsynchronized versions of a sequence in one split group. Merge groups connected by exact/numeric duplicate or overlap evidence. Adjacent route fragments from the same driver/session can also overlap without matching exact row fingerprints; partition by acquisition session/date/driver where appropriate and verify boundaries before constructing windows. No train/test split is created by this audit.", "",
        "Fit alignment parameters and normalization statistics using training or independent calibration data only. Freeze them before held-out evaluation. Never import per-test-sequence best lags from this report into evaluation preprocessing. A test sequence without known correspondence should remain excluded or have results explicitly marked alignment-uncertain. Separate hardware reference truth from deployable sensor features; do not use VBOX/CAN motion channels as model inputs.", "",
        "## Readiness verdict", "",
        "Phase 0 inventory and empirical audit are complete when the repeat/integrity verification passes. Body-frame diagnosis can proceed with the documented uncertainty. Mechanization correction is conditional on resolving gyro/acceleration frame semantics and selecting a defensible time model. AI training is not yet approved: diagnostic GNSS agreement alone cannot certify IMU-label synchronization.", "",
        "Detailed unit/lag/coverage results and per-pair classification reasons are in `phase0_evidence.json`; full file-level sampling and duplicates are in `phase0_inventory.json`.", ""]
    return {"data_schema.md": "\n".join(schema), "data_dictionary.md": "\n".join(dictionary),
            "synchronization_analysis.md": "\n".join(sync)}
