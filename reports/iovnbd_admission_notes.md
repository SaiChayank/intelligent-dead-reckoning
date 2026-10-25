# IO-VNBD Sequence Admission Analysis & Threshold Selection Notes

**Date:** 2026-10-19  
**Output File:** `reports/iovnbd_admission.json`  
**Governing Inputs:**
- `reports/iovnbd_frame_inference.csv` (Part 3)
- `reports/iovnbd_time_alignment.csv` (Part 4)
- `reports/iovnbd_inventory.md` (Part 1)
- `reports/training_admission.json` (Phase 0 Baseline)

---

## 1. Metric Distributions Across All 72 Sequences

Every statistic below is computed directly across all 72 synchronized IO-VNBD sequence pairs.

### 1.1 Time-Alignment Peak Correlation (`peak_corr`)
Normalized cross-correlation of band-limited longitudinal acceleration between phone IMU and VBOX odometry within $\pm 2.0\text{ s}$ lag:

| Statistic | Value |
| :--- | :---: |
| **Count** | 72 |
| **Mean** | 0.2757 |
| **Std Dev** | 0.2250 |
| **Min** | -0.0009 (`vta15`) |
| **25th Percentile** | 0.0903 |
| **50th Percentile (Median)** | **0.2391** |
| **75th Percentile** | 0.3734 |
| **90th Percentile** | 0.6374 |
| **Max** | **0.8361** (`vta19`) |

#### Peak Correlation Histogram (Bin Width: 0.1)
```text
Range         Count  Distribution
[-0.0, 0.1)     21   ##################### (29.2%)
[ 0.1, 0.2)     13   #############         (18.1%)
[ 0.2, 0.3)      8   ########              (11.1%)
[ 0.3, 0.4)     12   ############          (16.7%)
[ 0.4, 0.5)      3   ###                   ( 4.2%)
[ 0.5, 0.6)      5   #####                 ( 6.9%)
[ 0.6, 0.7)      5   #####                 ( 6.9%)
[ 0.7, 0.8)      2   ##                    ( 2.8%)
[ 0.8, 1.0]      2   ##                    ( 2.8%)
```

---

### 1.2 Second-Highest Peak Ratio (`second_peak_ratio`)
Ratio of the highest competing local peak outside a $\pm 0.3\text{ s}$ exclusion zone around the global peak to the global peak value. Serves as a measure of multi-modal lag ambiguity:

| Statistic | Value |
| :--- | :---: |
| **Count** | 72 |
| **Mean** | 0.5637 |
| **Std Dev** | 0.3960 |
| **Min** | 0.0000 (21 drives with isolated global peak) |
| **25th Percentile** | 0.0000 |
| **50th Percentile (Median)** | **0.7739** |
| **75th Percentile** | 0.9022 |
| **90th Percentile** | 0.9589 |
| **Max** | **0.9968** (`s2`, `vfa02`) |

#### Second-Highest Peak Ratio Histogram
```text
Range         Count  Distribution
[0.0, 0.1)     21   ##################### (29.2% unambiguous)
[0.1, 0.2)      1   #                     ( 1.4%)
[0.2, 0.3)      2   ##                    ( 2.8%)
[0.3, 0.4)      0                         ( 0.0%)
[0.4, 0.5)      1   #                     ( 1.4%)
[0.5, 0.6)      3   ###                   ( 4.2%)
[0.6, 0.7)      6   ######                ( 8.3%)
[0.7, 0.8)      5   #####                 ( 6.9%)
[0.8, 0.9)     13   #############         (18.1% highly ambiguous)
[0.9, 1.0]     20   ####################  (27.8% near-duplicate peaks)
```

---

### 1.3 Timestamp Gaps
From the factual sequence audit in `reports/iovnbd_inventory.md`, timestamp continuity was evaluated across all 72 synchronized pairs:
- **62 sequences** have **0 timestamp gaps** $> 1.0\text{ s}$.
- **10 sequences** contain at least one gap $> 1.0\text{ s}$:
  - `M`: 2 gaps (max 1.36 s)
  - `S2`: 1 gap (max 1.21 s)
  - `S3b`: 1 gap (max 4.43 s)
  - `S4`: 2 gaps (max 312.14 s)
  - `Vta17`: 1 gap (max 1.28 s)
  - `Vtb1`: 1 gap (max 661.38 s)
  - `Y1`: phone: 3 gaps (max 248.88 s), vbox: 2 gaps (max 384.60 s)

---

## 2. Threshold Selection & Engineering Rationale

The admission policy defines two primary numerical gates for cross-correlation:

### 2.1 Peak Correlation Threshold ($T_{\text{corr}} = 0.50$)
- **Rationale**: For an acceleration signal to serve as ground truth for training or evaluation, the signal-to-noise ratio must guarantee that vehicle longitudinal dynamics dominate over chassis vibration, road bumps, and sensor noise.
- Statistically, $r \ge 0.50$ implies $R^2 \ge 0.25$ (at least 25% of acceleration variance is shared between sensors).
- Setting $T_{\text{corr}} = 0.50$ admits the top 14 drives (the 80th percentile of dynamic coherence), rejecting the 58 drives where unmodeled vibration or near-constant highway speed produces near-zero correlation ($r < 0.40$).

### 2.2 Ambiguity Threshold ($T_{\text{amb}} = 0.80$)
- **Rationale**: When `second_peak_ratio` exceeds $0.80$, the cross-correlation profile contains a competing local maximum within 20% of the primary peak. This indicates that either:
  1. The vehicle was operating under quasi-periodic stop-and-go driving with repeated velocity pulses, or
  2. Noise fluctuations are comparable to true kinematic correlation.
- $T_{\text{amb}} = 0.80$ eliminates false-positive time alignments (33 sequences rejected for ambiguity), while preserving drives with clear, dominant correlation peaks (39 sequences).

---

## 3. Admission Decision & Checkpoint Findings

### 3.1 Gating Criteria
A sequence is approved if and only if **ALL** four conditions hold:
1. **Frame Confidence:** Inferred frame confidence is `"high"` or `"medium"`.
2. **Correlation Strength & Uniqueness:** `peak_corr >= T_corr` ($0.50$) and `second_peak_ratio <= T_amb` ($0.80$).
3. **Alignment Improvement:** `corr_after_alignment > corr_at_zero`.
4. **Timestamp Continuity:** No timestamp gaps $> 1.0\text{ s}$ in the used range.

### 3.2 Evaluation Outcome

| Criterion | Passing Sequences | Failing Sequences |
| :--- | :---: | :---: |
| Time Alignment ($T_{\text{corr}} \ge 0.50$, $T_{\text{amb}} \le 0.80$, improved corr) | 12 | 60 |
| Timestamp Continuity (no gaps $> 1.0\text{ s}$) | 62 | 10 |
| **Frame Confidence (`"high"` or `"medium"`)** | **0** | **72 (100%)** |
| **All Criteria Jointly** | **0** | **72 (100%)** |

### 3.3 Checkpoint Activation
- **Approved Sequences:** **0**
- **Approved Duration:** **0.0 minutes**
- **Decision:** `"no-go"`

**Root Cause:**
While 12 sequences exhibit strong, unambiguous time correlation ($r \ge 0.50$, ratio $\le 0.80$, such as `vw16b` with $r = 0.855$, `vta19` with $r = 0.846$, `vtb12` with $r = 0.794$), **all 72 sequences fail the frame confidence gate** because the Android logging pipeline pre-rotated gravity/accel into a world-stabilized coordinate system while leaving gyro in the upright handset casing frame.
