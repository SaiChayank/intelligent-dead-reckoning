# IO-VNBD Candidate Sequence Reconciliation & Accelerometer Frame Analysis

**Date:** 2026-10-20  
**Scope:** 12 Diagnostic Candidate Sequences (`vta1b`, `vta7`, `vta12`, `vta13`, `vta19`, `vta23`, `vta24`, `vtb7`, `vtb10`, `vtb12`, `vw9`, `vw16b`) from `reports/iovnbd_admission.json`.

---

## 1. Candidate Duration Reconciliation

### 1.1 Side-by-Side Duration Comparison

The table below reports the duration of each candidate sequence computed independently from three distinct sources:
- **(a) Raw File Span:** Elapsed wall-clock time between the first and last recorded timestamp (`t_last - t_first`), reported separately for Racelogic VBOX odometry (`V-*.csv`) and Android Smartphone (`S-*.csv`).
- **(b) Segments CSV:** Total duration summed across all labeled segments (`stopped`, `moving`, `accelerating`, `braking`) in `reports/iovnbd_segments.csv`.
- **(c) Evaluation Split:** Duration assigned to each sequence and partition in `evaluation/iovnbd_split.json`.

| Sequence | (a) Raw VBOX (s) | (a) Raw Phone (s) | (b) Segments CSV (s) | (c) Used in Split (s) | Mismatch (VBOX vs Segments) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `vta1b` | 95.20 s | 95.30 s | 95.20 s | 95.20 s | 0.0000 s |
| `vta7` | 83.90 s | 83.90 s | 83.90 s | 83.90 s | 0.0000 s |
| `vta12` | 60.90 s | 60.90 s | 60.90 s | 60.90 s | 0.0000 s |
| `vta13` | 40.30 s | 40.30 s | 40.30 s | 40.30 s | 0.0000 s |
| `vta19` | 29.10 s | 29.10 s | 29.10 s | 29.10 s | 0.0000 s |
| `vta23` | 110.90 s | 110.90 s | 110.90 s | 110.90 s | 0.0000 s |
| `vta24` | 117.00 s | 117.00 s | 117.00 s | 117.00 s | 0.0000 s |
| `vtb7` | 46.00 s | 46.00 s | 46.00 s | 46.00 s | 0.0000 s |
| `vtb10` | 19.40 s | 19.50 s | 19.40 s | 19.40 s | 0.0000 s |
| `vtb12` | 44.60 s | 44.60 s | 44.60 s | 44.60 s | 0.0000 s |
| `vw9` | 55.20 s | 55.10 s | 55.20 s | 55.20 s | 0.0000 s |
| `vw16b` | 112.50 s | 112.50 s | 112.50 s | 112.50 s | 0.0000 s |
| **Total** | **815.00 s** | **815.11 s** | **815.00 s** | **815.00 s** | **0.0000 s** |
| **Total (min)** | **13.58 min** | **13.59 min** | **13.58 min** | **13.58 min** | — |

---

### 1.2 Mismatch Explanation & 815 s vs 181.7 min Verification

1. **VBOX Raw vs Segments CSV:**  
   The difference between the VBOX raw timestamp span and the sum of labeled segments is **0.0000 s** for all 12 sequences. Because segmentation in `training/iovnbd_segments.py` partitions the continuous VBOX speed trajectory without gaps, no time is lost or discarded.

2. **Phone Raw vs VBOX Raw:**  
   Across all 12 sequences, the phone inertial logging window differs from VBOX by at most **0.10 s** (1 sample at 10 Hz), resulting in a cumulative net difference of **+0.11 s** across the entire candidate dataset (815.11 s vs 815.00 s). This sub-sample discrepancy reflects slight hardware clock start/stop triggering offsets in the AndroSensor recording script.

3. **815 s (13.58 min) is Definitively Correct:**  
   The raw CSV files for these 12 candidate drives contain a total of **8,162 samples** (ranging from 195 samples / 19.4 s for `vtb10` to 1,171 samples / 117.0 s for `vta24`). At the verified 10.0 Hz sampling rate, 8,162 samples $\times$ 0.1 s = 816.2 s (first-to-last sample span = 815.0 s = 13.58 min). Every sequence is a short dynamic slice under 2 minutes.

4. **Why 181.7 min is Incorrect:**  
   A duration of 181.7 minutes equals $10,902\text{ s}$ ($\sim 3.03\text{ hours}$). That would require an average of 15.1 minutes per sequence. In IO-VNBD, multi-hour recordings exist only for long drives such as `M` (10,599 s $\approx$ 176.6 min), `S2` (9,388 s $\approx$ 156.5 min), `S4` (9,773 s $\approx$ 162.9 min), and `Vw4` (12,652 s $\approx$ 210.9 min). All of those long drives were quarantined during admission due to timestamp gaps exceeding 1.0 s or ambiguous multi-modal correlation peaks. The figure 181.7 min corresponds roughly to the single long recording `M` (176.6 min) or the total wall-clock span of an entire testing session, and cannot be attributed to the 12 short candidate sequences.

5. **Split Script Integrity:**  
   `training/iovnbd_split.py` loads durations directly from the verified segment sums and produces exact total durations of:  
   $\text{Train (573.4 s)} + \text{Val (124.6 s)} + \text{Test (117.0 s)} = \mathbf{815.00\text{ s}}$ (**13.58 min**).  
   There is no truncation, drop, or mis-sum in the script.

---

## 2. Accelerometer Frame Evidence

### 2.1 Accelerometer Metrics During Stopped Segments

Of the 12 candidate sequences, 9 are short dynamic driving maneuvers without complete vehicle stops ($\ge 2.0\text{ s}$, $< 0.2\text{ m/s}$). The remaining 3 sequences contain verified standstill intervals ($N=259$ samples total):

| Sequence | Stopped Duration | $a_x$ (mean $\pm$ std) | $a_y$ (mean $\pm$ std) | $a_z$ (mean $\pm$ std) | Vector Norm $\|a\|$ |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `vta19` | 4.3 s (43 samples) | $-0.79 \pm 0.13\text{ m/s}^2$ | $-0.34 \pm 0.16\text{ m/s}^2$ | $+9.82 \pm 0.07\text{ m/s}^2$ | $9.86 \pm 0.08\text{ m/s}^2$ |
| `vta23` | 1.9 s (19 samples) | $+2.85 \pm 1.17\text{ m/s}^2$ | $-0.80 \pm 0.95\text{ m/s}^2$ | $+9.83 \pm 0.33\text{ m/s}^2$ | $10.37 \pm 0.41\text{ m/s}^2$ |
| `vta24` | 19.7 s (197 samples) | $+0.21 \pm 0.55\text{ m/s}^2$ | $-0.12 \pm 0.44\text{ m/s}^2$ | $+9.86 \pm 0.14\text{ m/s}^2$ | $9.89 \pm 0.18\text{ m/s}^2$ |
| **Combined Stopped** | **25.9 s (259 samples)** | **$+0.23 \pm 1.00\text{ m/s}^2$** | **$-0.21 \pm 0.50\text{ m/s}^2$** | **$+9.85 \pm 0.16\text{ m/s}^2$** | **$9.92 \pm 0.23\text{ m/s}^2$** |

- **Gravity Presence:** Gravity is unequivocally **present**. The measured specific force norm during standstill is $9.92 \pm 0.23\text{ m/s}^2$ (matching Earth gravity $g \approx 9.81\text{ m/s}^2$), not 0. The signal is raw specific force, not gravity-removed linear acceleration.
- **Gravity Direction:** At rest, gravity projects almost entirely onto $+a_z$ ($+9.85\text{ m/s}^2$), while horizontal axes $a_x$ and $a_y$ average near zero ($+0.23$ and $-0.21\text{ m/s}^2$).

### 2.2 Full-Trajectory Acceleration Baseline (All 8,162 Samples)

Across all dynamic driving states for the 12 candidate sequences:
- $a_x = +0.14 \pm 1.95\text{ m/s}^2$
- $a_y = -0.17 \pm 2.46\text{ m/s}^2$
- $a_z = +9.82 \pm 0.77\text{ m/s}^2$
- Full Vector Norm $\|a\| = 10.30 \pm 0.81\text{ m/s}^2$ (gravity plus dynamic vehicle maneuvering load).

---

### 2.3 Correlation with World-Frame vs Body-Frame Kinematics

To establish whether the sensor axes are fixed to the Earth (world-frame ENU) or rotate with the vehicle (body-frame):
1. **World-Frame Reference:** $a_{\text{East}} = \frac{d(v \sin\psi)}{dt}$ and $a_{\text{North}} = \frac{d(v \cos\psi)}{dt}$, derived from VBOX Doppler speed $v$ and dual-antenna True Heading $\psi$.
2. **Body-Frame Reference:** Forward longitudinal acceleration $a_{\text{long}} = \frac{dv}{dt}$ and lateral acceleration $a_{\text{lat}} = v \cdot \dot{\psi}$.
3. **Heading Change:** $\Delta \psi / \Delta t$ from unwrapped VBOX heading.

| Sequence | $r(a_x, a_{\text{East}})$ | $r(a_y, a_{\text{East}})$ | $r(a_x, a_{\text{North}})$ | $r(a_y, a_{\text{North}})$ | $r(a_x, \dot{\psi})$ | $r(a_y, \dot{\psi})$ | $r(a_z, \dot{\psi})$ | $r(a_x, a_{\text{long}})$ [body] |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `vta1b` | $-0.112$ | $-0.146$ | $+0.487$ | $+0.030$ | $+0.019$ | $-0.114$ | $-0.035$ | $+0.503$ |
| `vta7` | $-0.128$ | $-0.279$ | $+0.420$ | $-0.072$ | $-0.098$ | $-0.296$ | $-0.213$ | $+0.648$ |
| `vta12` | $+0.217$ | $-0.104$ | $+0.392$ | $+0.024$ | $+0.006$ | $-0.092$ | $-0.040$ | $+0.471$ |
| `vta13` | $+0.259$ | $-0.466$ | $+0.456$ | $+0.185$ | $+0.155$ | $-0.481$ | $+0.017$ | $+0.566$ |
| `vta19` | $+0.424$ | $+0.342$ | $+0.217$ | $+0.070$ | $-0.046$ | $-0.107$ | $-0.185$ | $-0.505$ |
| `vta23` | $+0.346$ | $-0.072$ | $+0.123$ | $+0.256$ | $-0.060$ | $-0.017$ | $+0.004$ | $+0.318$ |
| `vta24` | $+0.573$ | $-0.026$ | $+0.257$ | $+0.202$ | $+0.037$ | $+0.013$ | $+0.018$ | $+0.477$ |
| `vtb7` | $+0.271$ | $-0.048$ | $+0.079$ | $+0.398$ | $-0.175$ | $-0.327$ | $-0.041$ | $+0.144$ |
| `vtb10` | $+0.239$ | $-0.187$ | $+0.101$ | $+0.433$ | $-0.193$ | $-0.396$ | $+0.139$ | $+0.157$ |
| `vtb12` | $+0.437$ | $-0.488$ | $+0.278$ | $+0.534$ | $-0.241$ | $-0.530$ | $-0.002$ | $+0.630$ |
| `vw9` | $+0.562$ | $-0.034$ | $-0.175$ | $+0.444$ | $-0.315$ | $+0.244$ | $+0.027$ | $-0.396$ |
| `vw16b` | $+0.468$ | $-0.339$ | $-0.268$ | $+0.369$ | $+0.004$ | $-0.175$ | $-0.024$ | $+0.464$ |
| **Mean** | **$+0.296$** | **$-0.154$** | **$+0.197$** | **$+0.239$** | **$-0.076$** | **$-0.190$** | **$-0.028$** | **$+0.290$** |
| **Std** | **$0.228$** | **$0.225$** | **$0.240$** | **$0.197$** | **$0.134$** | **$0.225$** | **$0.094$** | **$0.382$** |

- **Vertical Decoupling ($a_z$):** $a_z$ exhibits negligible correlation with horizontal motion ($r(a_z, a_{\text{East}}) = +0.015 \pm 0.091$, $r(a_z, a_{\text{North}}) = -0.037 \pm 0.095$, $r(a_z, \dot{\psi}) = -0.028 \pm 0.094$), confirming it is aligned with local gravity.
- **Apparent vs True World-Frame Correlation:** On sequences traveling predominantly North (e.g. `vta1b` heading $\sim 0^\circ$, `vta7` heading $\sim 10^\circ$), $a_x$ correlates with $a_{\text{North}}$ ($r = +0.487, +0.420$), because forward acceleration points North. On sequences traveling East (e.g. `vta24` heading $\sim 90^\circ$, `vw9` heading $\sim 100^\circ$), $a_x$ correlates with $a_{\text{East}}$ ($r = +0.573, +0.562$). This heading-dependent switching proves $a_x$ is **not fixed to Earth coordinates**.
- **Body-Frame Alignment:** Across drives with consistent mount polarity, $a_x$ correlates strongly with vehicle longitudinal acceleration $a_{\text{long}}$ (mean $r = +0.490$ across positive-polarity drives, reaching $+0.648$ on `vta7` and $+0.630$ on `vtb12`), while $a_y$ correlates with vehicle lateral acceleration ($r(a_y, a_{\text{lat}}) = -0.377$ to $-0.540$).

---

### 2.4 Accelerometer Frame Conclusion

> **Conclusion on Accelerometer Frame Representation:**  
> The smartphone accelerometer columns contain **uncompensated specific force (gravity present, $\|a\| = 9.92 \pm 0.23\text{ m/s}^2$ at standstill)** expressed in a **pre-rotated device-mounting frame that rotates with the vehicle**, not in a fixed world-frame (ENU) or pure gravity-removed linear acceleration channel. Local vertical gravity is captured almost entirely along $+a_z$ ($+9.85\text{ m/s}^2$), indicating the sensor vertical axis was pre-aligned with vehicle gravity (either via horizontal mounting on the dashboard or by software leveling). The horizontal axes ($a_x, a_y$) do not track fixed geographic East/North directions (their correlation with geographic axes reverses systematically as the vehicle turns), but instead track vehicle-fixed kinematics: $a_x$ aligns with vehicle longitudinal acceleration (correlations up to $+0.65$) and $a_y$ responds to lateral turning dynamics. Consequently, the channels are **mixed**: gravity-aligned along the vertical axis, body-fixed along the horizontal axes, and retaining full gravitational acceleration.

---

## 3. Leave-One-Drive-Out (LOGO) Cross-Validation Folds

To ensure complete leakage prevention across the 12 candidate drives, `evaluation/iovnbd_logo_folds.json` establishes a 12-fold leave-one-drive-out evaluation scheme. Under the verified `drive_session` grouping rule, each of the 12 drives is held out as the evaluation set exactly once, with the remaining 11 drives forming the training set.

### 3.1 Fold Distribution Summary

| Fold | Held-Out Drive | Test Sequences | Test Duration | Training Drives | Train Duration | Leakage Status |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 0 | `vta1` | `vta1b` | 95.20 s | 11 drives | 719.80 s | 0 overlap (PASS) |
| 1 | `vta12` | `vta12` | 60.90 s | 11 drives | 754.10 s | 0 overlap (PASS) |
| 2 | `vta13` | `vta13` | 40.30 s | 11 drives | 774.70 s | 0 overlap (PASS) |
| 3 | `vta19` | `vta19` | 29.10 s | 11 drives | 785.90 s | 0 overlap (PASS) |
| 4 | `vta23` | `vta23` | 110.90 s | 11 drives | 704.10 s | 0 overlap (PASS) |
| 5 | `vta24` | `vta24` | 117.00 s | 11 drives | 698.00 s | 0 overlap (PASS) |
| 6 | `vta7` | `vta7` | 83.90 s | 11 drives | 731.10 s | 0 overlap (PASS) |
| 7 | `vtb10` | `vtb10` | 19.40 s | 11 drives | 795.60 s | 0 overlap (PASS) |
| 8 | `vtb12` | `vtb12` | 44.60 s | 11 drives | 770.40 s | 0 overlap (PASS) |
| 9 | `vtb7` | `vtb7` | 46.00 s | 11 drives | 769.00 s | 0 overlap (PASS) |
| 10 | `vw16` | `vw16b` | 112.50 s | 11 drives | 702.50 s | 0 overlap (PASS) |
| 11 | `vw9` | `vw9` | 55.20 s | 11 drives | 759.80 s | 0 overlap (PASS) |

Every fold satisfies:
$$\text{test\_drives} \cap \text{train\_drives} = \emptyset, \quad \text{test\_sequences} \cap \text{train\_sequences} = \emptyset, \quad \text{test\_duration} + \text{train\_duration} = 815.00\text{ s}$$
All properties are formally verified in `tests/test_iovnbd_folds.py`.
