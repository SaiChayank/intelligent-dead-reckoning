# Phase 0 - Verified dataset schema

## Scope and reproducibility

**Confirmed from data.** The audit parses every CSV in 25,000-row chunks, then loads one synchronized pair at a time for signal analysis. No raw file is repaired, resampled, relabelled or overwritten. All statistics are descriptive; source channels are retained.

Run from the repository root with Python 3.12, NumPy and pandas:

```powershell
python -m training.phase0.audit --data-root data/raw/iovnbd --verify-repeat
python -m unittest discover -s tests -p 'test_phase0*.py' -v
```

`phase0_verification.json` records runtime versions, two-pass output hashes, and before/after SHA-256, size and modification-time equality for the entire raw tree, including the nested Git repository and archives. `phase0_raw_manifest.json` contains the individual file hashes. Running without --verify-repeat explicitly records repeat verification as unperformed.

## Dataset inventory

**Confirmed from data.** Physical files include repeated copies; counts below are not independent drives.

| Item | Measured count |
| --- | --- |
| Synchronized smartphone CSVs | 144 |
| Synchronized VBOX/vehicle CSVs | 144 |
| Unsynchronized smartphone CSVs | 97 |
| Unsynchronized vehicle CSVs | 179 |
| Route images | 161 |
| CSV files | 564 |
| CSV bytes | 1794609089 |
| CSV rows including duplicates | 9189756 |
| Schema families (exact decoded headers) | 6 |
| Synchronized pairs (categorized + uncategorized) | 144 |
| Pairs with unequal row counts | 18 |
| Unmatched/ambiguous synchronized groups | 0 |
| Unsynchronized filename-only S/V matches | 144 |
| Unsynchronized filename matches with unequal rows | 132 |
| Unmatched unsynchronized files | 60 |
| Byte-identical duplicate groups / redundant CSV copies | 230 / 235 |
| Same normalized numeric payload groups / redundant CSV copies | 230 / 250 |
| Candidate partial recording overlaps | 390 |

Full per-file paths, byte sizes, row counts, drivers, original and normalized sequence IDs, encodings, timing statistics, coordinate validity and column missing-value counts are in `phase0_inventory.json`. Unsynchronized filename matches include both paths and row counts but do not establish alignment. Exact duplicate groups use SHA-256 of raw bytes. Numeric-payload groups hash ordered pandas row hashes after numeric dtype normalization and trimming text; header names are deliberately excluded. Candidate partial overlaps use the 64 smallest distinct row fingerprints within the same sequence/source and are not an exhaustive subsequence search.

## Drivers and sequences

**Confirmed from data** for explicit folder labels. **Inferred with supporting evidence** when inherited from another categorized copy. **Stated by dataset documentation** for drivers F/G/H from Table A7. Unknown assignments remain unknown.

| Driver | Sequence IDs |
| --- | --- |
| A | s1, s2, s3a, s3b, s3c, s4 |
| B | m |
| C | st1, st4, st6, st7 |
| D | y1, y2 |
| E | vfa01, vfa02, vfb01a, vfb01b, vfb01c, vfb01d, vfb02a, vfb02b, vfb02c, vfb02d, vfb02e, vfb02f, vfb02g, vta10, vta11, vta12, vta13, vta14, vta15, vta16, vta17, vta19, vta1a, vta1b, vta2, vta20, vta21, vta22, vta23, vta24, vta25, vta26, vta27, vta28, vta29, vta3, vta30, vta4, vta5, vta6, vta7, vta8, vta9, vtb1, vtb10, vtb11, vtb12, vtb13, vtb2, vtb3, vtb4, vtb5, vtb6, vtb7, vtb8, vtb9, vw1, vw10, vw11, vw12, vw13, vw14a, vw14b, vw14c, vw15, vw16a, vw16b, vw17, vw2, vw3, vw4, vw5, vw6, vw7, vw8, vw9 |
| F | t1, t10, t11, t2, t3, t4, t5, t6, t7, t8, t9 |
| G | i |
| H | a1, a10, a11, a12, a13, a2, a3, a4, a5, a6, a7, a8, a9 |
| unknown | vta18 |

## Schema families

| ID | Source | Files | Columns | Rows including duplicates | Representative |
| --- | --- | --- | --- | --- | --- |
| schema-12fc4114f2 | smartphone | 71 | 24 | 998549 | Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-M.csv |
| schema-1f9d435b70 | smartphone | 1 | 25 | 32829 | Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A4.csv |
| schema-7e8ac482b0 | smartphone | 158 | 24 | 2502994 | Synchronised V abd S datasets/Categorised IOVNB Dataset/M (Driver B)/S-M.csv |
| schema-94eaa4638e | smartphone | 9 | 18 | 603425 | Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-T1.csv |
| schema-bddf605e47 | vbox | 323 | 29 | 4972950 | Synchronised V abd S datasets/Categorised IOVNB Dataset/M (Driver B)/V-M.csv |
| schema-cbfccaebcf | smartphone | 2 | 24 | 79009 | Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-Vfa01.csv |

### Header/data contradictions

**Confirmed from data.** S-A4 has 25 header/data cells, but the extra empty data cell is at zero-based index 6 while the extra empty header is at index 24. A header-only loader incorrectly reads elapsed milliseconds as DATE, DATE as acceleration, and gravity as gyro. **Inferred with supporting evidence.** The audit records a one-column suffix remapping: satellites at 7, elapsed counter at 8, DATE at 9, acceleration beginning at 10, and the last orientation at 24. All recovered DATE values and the empty column are validated across the complete file. Original positional headers and header-derived names remain in the registry; no CSV is rewritten. S-A4 remains quarantined from training pending independent structural review.

| File | Rows | Mapping status | Recovered missing DATE count |
| --- | --- | --- | --- |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A4.csv | 32829 | empirical_column_shift_quarantined | 0 |

## Missing and nonfinite values

**Confirmed from data.** 6 files contain parsed missing or nonfinite numeric cells. Empty trailing CSV columns are counted separately in the registry. Nonnumeric satellite strings and DATE text are expected; they are not interpreted as missing numeric sensor measurements.

| File | Missing cells | Nonfinite numeric cells |
| --- | --- | --- |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Y (Driver D)/Y1/S-Y1.csv | 98 | 0 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-Y1.csv | 98 | 0 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A4.csv | 32829 | 0 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A5.csv | 700 | 0 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A6.csv | 1820 | 0 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-Y1.csv | 98 | 0 |

## Sampling evidence

**Stated by dataset documentation.** README_1.pdf page 2 describes 10 Hz sensor logging and a 1 Hz smartphone GPS update. **Confirmed from data.** The tables below report timestamp spacing and observed value-change spacing. Repeated values are consistent with held or quantized measurements but do not independently identify the physical sensor's update rate. Stationary GPS can legitimately remain unchanged.

| File | Rows | Median dt (s) | Rate (Hz) | GPS median change interval (s) | Repeated / backward / missing time | Max dt (s) | Gaps |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/M (Driver B)/S-M.csv | 105974 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 1.365 | 2 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/M (Driver B)/V-M.csv | 105974 | 0.100 | 10.000 | 0.100 | 1 / 0 / 0 | 0.200 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S1/S-S1.csv | 51746 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 0.111 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S1/V-S1.csv | 51746 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.101 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S2/S-S2.csv | 93876 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 1.208 | 1 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S2/V-S2.csv | 93876 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.101 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S3a/S-S3a.csv | 24621 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 0.112 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S3a/V-S3a.csv | 24621 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.100 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S3c/S-S3c.csv | 37183 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 0.121 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S3c/V-S3c.csv | 37183 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.101 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vf (Driver E)/V-Vfa01/S-Vfa01.csv | 11486 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 0.113 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vf (Driver E)/V-Vfa01/V-Vfa01.csv | 11535 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.100 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta01a/S-Vta1a.csv | 25676 | 0.100 | 10.000 | 1.000 | 0 / 0 / 0 | 0.111 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta01a/V-Vta1a.csv | 25676 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.100 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta25/S-Vta25.csv | 646 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 0.103 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta25/V-vta25.csv | 646 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.100 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vtb (Driver E)/Vtb01/S-Vtb1.csv | 32459 | 0.100 | 10.000 | 9.000 | 5456 / 0 / 0 | 661.376 | 1 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vtb (Driver E)/Vtb01/V-vtb1.csv | 32459 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.101 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vw (Driver E)/Vw01/S-Vw1.csv | 20476 | 0.100 | 10.000 | unavailable | 0 / 0 / 0 | 0.115 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vw (Driver E)/Vw01/V-Vw1.csv | 20475 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.100 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vw (Driver E)/Vw15/S-Vw15.csv | 1380 | 0.100 | 10.000 | unavailable | 0 / 0 / 0 | 0.102 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vw (Driver E)/Vw15/V-Vw15.csv | 1391 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 0.100 | 0 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Y (Driver D)/Y1/S-Y1.csv | 70285 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 248.880 | 3 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Y (Driver D)/Y1/V-Y1.csv | 70285 | 0.100 | 10.000 | 0.100 | 0 / 0 / 0 | 384.600 | 2 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-M.csv | 105974 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 1.365 | 2 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-Vfa01.csv | 11486 | 0.100 | 10.000 | 9.000 | 0 / 0 / 0 | 0.113 | 0 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A4.csv | 32829 | 0.100 | 10.000 | 1.000 | 0 / 0 / 0 | 0.111 | 0 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-T1.csv | 25759 | 0.001 | 1,000.000 | 1.462 | 10362 / 0 / 0 | 0.452 | 466 |

| Smartphone representative | Rows/elapsed second | IMU value-change rate (Hz) | GPS value-change rate (Hz) | Unchanged GPS adjacent fraction |
| --- | --- | --- | --- | --- |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/M (Driver B)/S-M.csv | 9.998 | 10.000 | 0.111 | 0.98966 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S1/S-S1.csv | 10.000 | 10.000 | 0.111 | 0.98970 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S2/S-S2.csv | 9.999 | 10.000 | 0.111 | 0.98988 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S3a/S-S3a.csv | 10.000 | 10.000 | 0.111 | 0.98972 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S3c/S-S3c.csv | 10.000 | 10.000 | 0.111 | 0.98951 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vf (Driver E)/V-Vfa01/S-Vfa01.csv | 10.000 | 10.000 | 0.111 | 0.98938 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta01a/S-Vta1a.csv | 10.000 | 10.000 | 1.000 | 0.90185 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta25/S-Vta25.csv | 10.000 | 10.000 | 0.111 | 0.99225 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vtb (Driver E)/Vtb01/S-Vtb1.csv | 10.000 | 10.000 | 0.111 | 0.99140 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vw (Driver E)/Vw01/S-Vw1.csv | 10.000 | 10.000 | unavailable | 1.00000 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vw (Driver E)/Vw15/S-Vw15.csv | 10.000 | 10.000 | unavailable | 1.00000 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Y (Driver D)/Y1/S-Y1.csv | 9.509 | 10.000 | 0.111 | 0.98970 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-M.csv | 9.998 | 10.000 | 0.111 | 0.98966 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-Vfa01.csv | 10.000 | 10.000 | 0.111 | 0.98938 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A4.csv | 10.000 | 10.000 | 1.000 | 0.90368 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-T1.csv | 34.885 | 10.000 | 0.684 | 0.99037 |

**Confirmed from data.** Synchronized smartphone observed GPS change-rate counts (Hz rounded to 0.001; physical copies counted): {0.077: 2, 0.111: 124, 0.112: 6, 0.182: 2, 1.0: 6, 'unidentifiable': 4}. GPS values commonly persist for many 10 Hz IMU rows. The recorded cadence does not support assuming that every tenth row contains a fresh 1 Hz fix.

**Inferred with supporting evidence.** Nominal synchronized IMU logging is approximately 10 Hz where DATE spacing and changing IMU values agree. Some unsynchronized A-series files are logged at 2 Hz. T1/T6 contain bursts with 1 ms positive timestamp differences and many identical timestamps; the inverse-positive-median statistic near 1,000 Hz is not a credible hardware sampling-rate conclusion. Use whole-duration row throughput, repetition counts, and interval distributions together. Their acquisition timing remains unresolved.

| Largest recorded intervals: file | Maximum dt (s) | Large-gap count |
| --- | --- | --- |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Vtb (Driver E)/Vtb01/S-Vtb1.csv | 661.376 | 1 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/S-Dataset/S-Vtb1.csv | 661.376 | 1 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-Vtb1.csv | 661.376 | 1 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/Y (Driver D)/Y1/V-Y1.csv | 384.600 | 2 |
| Synchronised V abd S datasets/Uncategorised IOVNB Dataset/V-Dataset/V-Y1.csv | 384.600 | 2 |
| Unsynchronised V and S Dataset/Categorised IOVNB (V) Dataset/V Dataset/Y (Driver D)/Y1/V-Y1.csv | 384.600 | 2 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/V-Dataset/V-Y1.csv | 384.600 | 2 |
| Synchronised V abd S datasets/Categorised IOVNB Dataset/S (Driver A)/S4/S-S4.csv | 312.142 | 2 |

Every file, including every schema family's representative, has interval distributions (mean, median, min, p01, p95, p99, max, standard deviation), missing/repeated/backward timestamps, gap indices, GPS hold runs and estimated missing slots in the inventory. Smartphone files also have elapsed-counter and IMU value-change diagnostics.

**Still unresolved.** Equal row counts or a nominal 10 Hz clock cannot establish synchronized sensor acquisition. A row clock is acceptable for plotting row-index diagnostics, not automatically for mechanization or supervised labels. A recorded gap may be a clock discontinuity, dropped samples, manual editing, or a combination. No synthetic samples are inserted.

## Phase 0 handoff

Proceed to body-frame investigation using the explicitly retained raw gyro channels and independently checked timing. Mechanization correction must wait for frame, gravity-source semantics and causal initialization decisions. Training remains conditional on verified inertial-label timing, recording-group splits and a credible baseline.
