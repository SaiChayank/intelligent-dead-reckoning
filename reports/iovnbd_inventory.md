# IO-VNBD Dataset Inventory and Frame Problem Analysis

**Date:** 2026-10-19  
**Dataset Root:** `data/raw/iovnbd`  
**Total Available Unique Sequences:** 115 (72 Synchronized Pairs + 43 Unsynchronized-Only)  
**Total Phone Inertial Duration:** 221107.5 s (61.42 hours)  
**Total VBOX Odometry Duration:** 141834.1 s (39.40 hours)  
**Ground Truth & Mechanization Status:** Full 3-D raw-INS baseline remains blocked by unresolved body frame and export transforms.

## 1. Factual Sequence Inventory Table

Every row and metric in this table was extracted by directly loading the raw CSV files from disk. Sample rates and gaps are computed from empirical timestamps, not from documentation.

### 1.1 Synchronized Benchmark Sequences (72 Pairs)

| sequence | duration | IMU rate | odometry rate | units | gaps | notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `M` | 10599.6s | 10.0 Hz | 10.0 Hz | acc=9.94 m/s², spd=100.7 km/h | phone:2 (max 1.36s) | Driver B; conditional candidate; 105974 samples |
| `S1` | 5174.5s | 10.0 Hz | 10.0 Hz | acc=9.93 m/s², spd=93.8 km/h | 0 | Driver A; no-go; 51746 samples |
| `S2` | 9388.6s | 10.0 Hz | 10.0 Hz | acc=9.93 m/s², spd=105.2 km/h | phone:1 (max 1.21s) | Driver A; conditional candidate; 93876 samples |
| `S3a` | 2462.0s | 10.0 Hz | 10.0 Hz | acc=9.90 m/s², spd=98.0 km/h | 0 | Driver A; no-go; 24621 samples |
| `S3b` | 685.5s | 10.0 Hz | 10.0 Hz | acc=9.90 m/s², spd=44.7 km/h | phone:1 (max 4.43s) | Driver A; no-go; 6813 samples |
| `S3c` | 3718.2s | 10.0 Hz | 10.0 Hz | acc=9.90 m/s², spd=117.1 km/h | 0 | Driver A; no-go; 37183 samples |
| `S4` | 9773.1s | 10.0 Hz | 10.0 Hz | acc=9.92 m/s², spd=109.6 km/h | phone:2 (max 312.14s) | Driver A; no-go; 94600 samples |
| `Vfa01` | 1148.5s | 10.0 Hz | 10.0 Hz | acc=10.08 m/s², spd=98.4 km/h | 0 | Driver E; no-go; 11486 samples |
| `Vfa02` | 6752.2s | 10.0 Hz | 10.0 Hz | acc=10.08 m/s², spd=117.9 km/h | 0 | Driver E; no-go; 67523 samples |
| `Vta1a` | 2567.5s | 10.0 Hz | 10.0 Hz | acc=10.21 m/s², spd=103.4 km/h | 0 | Driver E; no-go; 25676 samples |
| `Vta1b` | 95.3s | 10.0 Hz | 10.0 Hz | acc=10.27 m/s², spd=77.7 km/h | 0 | Driver E; no-go; 954 samples |
| `Vta2` | 1099.0s | 10.0 Hz | 10.0 Hz | acc=10.07 m/s², spd=81.6 km/h | 0 | Driver E; conditional candidate; 10991 samples |
| `Vta3` | 64.4s | 10.0 Hz | 10.0 Hz | acc=9.90 m/s², spd=45.8 km/h | 0 | Driver E; no-go; 645 samples |
| `Vta4` | 178.8s | 10.0 Hz | 10.0 Hz | acc=10.27 m/s², spd=51.7 km/h | 0 | Driver E; no-go; 1789 samples |
| `Vta5` | 30.6s | 10.0 Hz | 10.0 Hz | acc=10.11 m/s², spd=51.1 km/h | 0 | Driver E; no-go; 307 samples |
| `Vta6` | 137.5s | 10.0 Hz | 10.0 Hz | acc=10.30 m/s², spd=103.9 km/h | 0 | Driver E; no-go; 1376 samples |
| `Vta7` | 83.9s | 10.0 Hz | 10.0 Hz | acc=10.30 m/s², spd=113.1 km/h | 0 | Driver E; no-go; 840 samples |
| `Vta8` | 367.5s | 10.0 Hz | 10.0 Hz | acc=9.96 m/s², spd=77.6 km/h | 0 | Driver E; conditional candidate; 3676 samples |
| `Vta9` | 15.5s | 10.0 Hz | 10.0 Hz | acc=10.05 m/s², spd=87.4 km/h | 0 | Driver E; no-go; 156 samples |
| `Vta10` | 150.1s | 10.0 Hz | 10.0 Hz | acc=10.24 m/s², spd=118.0 km/h | 0 | Driver E; no-go; 1502 samples |
| `Vta11` | 50.9s | 10.0 Hz | 10.0 Hz | acc=10.30 m/s², spd=97.7 km/h | 0 | Driver E; no-go; 510 samples |
| `Vta12` | 60.9s | 10.0 Hz | 10.0 Hz | acc=10.09 m/s², spd=85.3 km/h | 0 | Driver E; no-go; 610 samples |
| `Vta13` | 40.3s | 10.0 Hz | 10.0 Hz | acc=10.19 m/s², spd=103.6 km/h | 0 | Driver E; no-go; 404 samples |
| `Vta14` | 288.4s | 10.0 Hz | 10.0 Hz | acc=10.17 m/s², spd=91.0 km/h | 0 | Driver E; no-go; 2885 samples |
| `Vta15` | 83.2s | 10.0 Hz | 10.0 Hz | acc=10.15 m/s², spd=78.8 km/h | 0 | Driver E; no-go; 833 samples |
| `Vta16` | 1133.4s | 10.0 Hz | 10.0 Hz | acc=10.08 m/s², spd=93.9 km/h | 0 | Driver E; conditional candidate; 11335 samples |
| `Vta17` | 453.0s | 10.0 Hz | 10.0 Hz | acc=10.16 m/s², spd=56.2 km/h | phone:1 (max 1.28s) | Driver E; no-go; 4519 samples |
| `Vta19` | 29.1s | 10.0 Hz | 10.0 Hz | acc=10.03 m/s², spd=54.3 km/h | 0 | Driver E; no-go; 292 samples |
| `Vta20` | 322.2s | 10.0 Hz | 10.0 Hz | acc=9.88 m/s², spd=44.8 km/h | 0 | Driver E; no-go; 3223 samples |
| `Vta21` | 207.8s | 10.0 Hz | 10.0 Hz | acc=10.28 m/s², spd=74.8 km/h | 0 | Driver E; no-go; 2079 samples |
| `Vta22` | 156.3s | 10.0 Hz | 10.0 Hz | acc=10.33 m/s², spd=55.8 km/h | 0 | Driver E; no-go; 1564 samples |
| `Vta23` | 110.9s | 10.0 Hz | 10.0 Hz | acc=10.30 m/s², spd=51.9 km/h | 0 | Driver E; no-go; 1110 samples |
| `Vta24` | 117.0s | 10.0 Hz | 10.0 Hz | acc=9.92 m/s², spd=56.4 km/h | 0 | Driver E; no-go; 1171 samples |
| `Vta25` | 64.5s | 10.0 Hz | 10.0 Hz | acc=9.90 m/s², spd=48.6 km/h | 0 | Driver E; no-go; 646 samples |
| `Vta26` | 193.4s | 10.0 Hz | 10.0 Hz | acc=9.91 m/s², spd=55.1 km/h | 0 | Driver E; conditional candidate; 1935 samples |
| `Vta27` | 253.9s | 10.0 Hz | 10.0 Hz | acc=10.26 m/s², spd=65.0 km/h | 0 | Driver E; no-go; 2540 samples |
| `Vta28` | 420.9s | 10.0 Hz | 10.0 Hz | acc=10.14 m/s², spd=66.0 km/h | 0 | Driver E; no-go; 4210 samples |
| `Vta29` | 2369.8s | 10.0 Hz | 10.0 Hz | acc=10.14 m/s², spd=102.0 km/h | 0 | Driver E; no-go; 23705 samples |
| `Vta30` | 1713.5s | 10.0 Hz | 10.0 Hz | acc=9.92 m/s², spd=100.0 km/h | 0 | Driver E; conditional candidate; 17136 samples |
| `Vtb1` | 3245.8s | 10.0 Hz | 10.0 Hz | acc=10.16 m/s², spd=101.2 km/h | phone:1 (max 661.38s) | Driver E; no-go; 32459 samples |
| `Vtb2` | 571.1s | 10.0 Hz | 10.0 Hz | acc=10.01 m/s², spd=61.1 km/h | 0 | Driver E; conditional candidate; 5712 samples |
| `Vtb3` | 823.9s | 10.0 Hz | 10.0 Hz | acc=9.87 m/s², spd=37.5 km/h | 0 | Driver E; no-go; 8240 samples |
| `Vtb4` | 55.5s | 10.0 Hz | 10.0 Hz | acc=10.21 m/s², spd=32.7 km/h | 0 | Driver E; no-go; 556 samples |
| `Vtb5` | 6438.7s | 10.0 Hz | 10.0 Hz | acc=10.04 m/s², spd=112.9 km/h | 0 | Driver E; no-go; 64388 samples |
| `Vtb6` | 49.7s | 10.0 Hz | 10.0 Hz | acc=10.04 m/s², spd=73.0 km/h | 0 | Driver E; no-go; 498 samples |
| `Vtb7` | 46.0s | 10.0 Hz | 10.0 Hz | acc=10.07 m/s², spd=69.2 km/h | 0 | Driver E; no-go; 461 samples |
| `Vtb8` | 66.7s | 10.0 Hz | 10.0 Hz | acc=10.17 m/s², spd=76.5 km/h | 0 | Driver E; no-go; 668 samples |
| `Vtb9` | 45.1s | 10.0 Hz | 10.0 Hz | acc=10.07 m/s², spd=92.0 km/h | 0 | Driver E; no-go; 452 samples |
| `Vtb10` | 19.5s | 10.0 Hz | 10.0 Hz | acc=10.12 m/s², spd=58.5 km/h | 0 | Driver E; no-go; 196 samples |
| `Vtb11` | 36.0s | 10.0 Hz | 10.0 Hz | acc=10.18 m/s², spd=75.3 km/h | 0 | Driver E; no-go; 361 samples |
| `Vtb12` | 44.6s | 10.0 Hz | 10.0 Hz | acc=10.06 m/s², spd=71.0 km/h | 0 | Driver E; no-go; 447 samples |
| `Vw1` | 2047.5s | 10.0 Hz | 10.0 Hz | acc=9.85 m/s², spd=0.3 km/h | 0 | Driver E; no-go; 20476 samples |
| `Vw2` | 5271.2s | 10.0 Hz | 10.0 Hz | acc=10.07 m/s², spd=115.4 km/h | 0 | Driver E; no-go; 52713 samples |
| `Vw3` | 386.0s | 10.0 Hz | 10.0 Hz | acc=10.18 m/s², spd=77.4 km/h | 0 | Driver E; no-go; 3861 samples |
| `Vw4` | 12652.5s | 10.0 Hz | 10.0 Hz | acc=10.14 m/s², spd=131.9 km/h | 0 | Driver E; no-go; 126526 samples |
| `Vw5` | 101.1s | 10.0 Hz | 10.0 Hz | acc=10.13 m/s², spd=37.4 km/h | 0 | Driver E; no-go; 1012 samples |
| `Vw6` | 128.0s | 10.0 Hz | 10.0 Hz | acc=10.14 m/s², spd=40.7 km/h | 0 | Driver E; no-go; 1281 samples |
| `Vw7` | 160.1s | 10.0 Hz | 10.0 Hz | acc=10.16 m/s², spd=42.2 km/h | 0 | Driver E; no-go; 1602 samples |
| `Vw8` | 152.8s | 10.0 Hz | 10.0 Hz | acc=10.22 m/s², spd=46.4 km/h | 0 | Driver E; no-go; 1529 samples |
| `Vw9` | 55.1s | 10.0 Hz | 10.0 Hz | acc=10.15 m/s², spd=42.0 km/h | 0 | Driver E; no-go; 552 samples |
| `Vw10` | 65.1s | 10.0 Hz | 10.0 Hz | acc=10.29 m/s², spd=58.9 km/h | 0 | Driver E; no-go; 652 samples |
| `Vw11` | 490.8s | 10.0 Hz | 10.0 Hz | acc=9.98 m/s², spd=98.4 km/h | 0 | Driver E; conditional candidate; 4909 samples |
| `Vw12` | 91.7s | 10.0 Hz | 10.0 Hz | acc=10.20 m/s², spd=97.4 km/h | 0 | Driver E; no-go; 918 samples |
| `Vw13` | 28.3s | 10.0 Hz | 10.0 Hz | acc=10.43 m/s², spd=113.5 km/h | 0 | Driver E; no-go; 284 samples |
| `Vw14a` | 313.7s | 10.0 Hz | 10.0 Hz | acc=10.19 m/s², spd=109.4 km/h | 0 | Driver E; no-go; 3138 samples |
| `Vw14b` | 1958.7s | 10.0 Hz | 10.0 Hz | acc=10.08 m/s², spd=120.1 km/h | 0 | Driver E; no-go; 19588 samples |
| `Vw14c` | 1582.5s | 10.0 Hz | 10.0 Hz | acc=9.92 m/s², spd=100.5 km/h | 0 | Driver E; conditional candidate; 15826 samples |
| `Vw15` | 137.9s | 10.0 Hz | 10.0 Hz | acc=9.85 m/s², spd=0.8 km/h | 0 | Driver E; no-go; 1380 samples |
| `Vw16a` | 587.8s | 10.0 Hz | 10.0 Hz | acc=10.01 m/s², spd=83.5 km/h | 0 | Driver E; no-go; 5879 samples |
| `Vw16b` | 112.5s | 10.0 Hz | 10.0 Hz | acc=10.19 m/s², spd=86.3 km/h | 0 | Driver E; no-go; 1126 samples |
| `Vw17` | 32.8s | 10.0 Hz | 10.0 Hz | acc=10.20 m/s², spd=72.7 km/h | 0 | Driver E; no-go; 329 samples |
| `Y1` | 7391.3s | 10.0 Hz | 10.0 Hz | acc=9.89 m/s², spd=87.5 km/h | phone:3 (max 248.88s), vbox:2 (max 384.60s) | Driver D; no-go; 70285 samples |

### 1.2 Unsynchronized-Only Sequences (43 Sequences)

| sequence | duration | IMU rate | odometry rate | units | gaps | notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `A1` | 2970.6s | 2.0 Hz | N/A (unpaired) | acc=9.79 m/s² | 0 | Driver H; phone-only; 5942 samples |
| `A10` | 4442.5s | 2.0 Hz | N/A (unpaired) | acc=9.83 m/s² | 0 | Driver H; phone-only; 8886 samples |
| `A11` | 2577.0s | 2.0 Hz | N/A (unpaired) | acc=9.70 m/s² | 0 | Driver H; phone-only; 5155 samples |
| `A12` | 2336.0s | 2.0 Hz | N/A (unpaired) | acc=9.70 m/s² | 0 | Driver H; phone-only; 4673 samples |
| `A13` | 4591.5s | 2.0 Hz | N/A (unpaired) | acc=9.72 m/s² | 0 | Driver H; phone-only; 9184 samples |
| `A2` | 5814.5s | 2.0 Hz | N/A (unpaired) | acc=9.82 m/s² | 0 | Driver H; phone-only; 11630 samples |
| `A3` | 5566.0s | 2.0 Hz | N/A (unpaired) | acc=9.83 m/s² | 0 | Driver H; phone-only; 11132 samples |
| `A4` | 3282.8s | 10.0 Hz | N/A (unpaired) | acc=9.82 m/s² | 0 | Driver H; phone-only; 32829 samples (schema column-shift quarantined) |
| `A5` | 8504.3s | 10.0 Hz | N/A (unpaired) | acc=9.82 m/s² | 1 (max 0.56s) | Driver H; phone-only; 85041 samples |
| `A6` | 8537.4s | 10.0 Hz | N/A (unpaired) | acc=9.81 m/s² | 0 | Driver H; phone-only; 86034 samples |
| `A7` | 4387.1s | 10.0 Hz | N/A (unpaired) | acc=9.78 m/s² | 0 | Driver H; phone-only; 43872 samples |
| `A8` | 6661.6s | 10.0 Hz | N/A (unpaired) | acc=9.74 m/s² | 0 | Driver H; phone-only; 66617 samples |
| `A9` | 5980.5s | 2.0 Hz | N/A (unpaired) | acc=9.76 m/s² | 0 | Driver H; phone-only; 11962 samples |
| `I` | 579.9s | 10.0 Hz | N/A (unpaired) | acc=9.86 m/s² | 1 (max 285.38s) | Driver G; phone-only; 5800 samples |
| `St1` | 5721.2s | N/A (unpaired) | 10.0 Hz | spd=73.3 km/h | 0 | Driver C; VBOX-only; 57213 samples |
| `St4` | 1359.0s | N/A (unpaired) | 10.0 Hz | spd=101.4 km/h | 0 | Driver C; VBOX-only; 13591 samples |
| `St6` | 5135.9s | N/A (unpaired) | 10.0 Hz | spd=122.1 km/h | 0 | Driver C; VBOX-only; 51360 samples |
| `St7` | 4509.7s | N/A (unpaired) | 10.0 Hz | spd=117.9 km/h | 128 (max 5.10s) | Driver C; VBOX-only; 44427 samples |
| `T1` | 738.4s | 1000.0 Hz | N/A (unpaired) | acc=9.74 m/s² | 6377 (max 0.45s) | Driver F; phone-only; 25759 samples (missing mag/orient) |
| `T10` | 669.8s | 10.0 Hz | N/A (unpaired) | acc=9.87 m/s² | 0 | Driver F; phone-only; 6699 samples (missing mag/orient) |
| `T11` | 568.9s | 10.0 Hz | N/A (unpaired) | acc=9.87 m/s² | 0 | Driver F; phone-only; 5690 samples (missing mag/orient) |
| `T2` | 7554.9s | 10.0 Hz | N/A (unpaired) | acc=9.77 m/s² | 0 | Driver F; phone-only; 75555 samples (missing mag/orient) |
| `T3` | 6469.2s | 10.0 Hz | N/A (unpaired) | acc=9.80 m/s² | 0 | Driver F; phone-only; 65859 samples (missing mag/orient) |
| `T4` | 3580.2s | 10.1 Hz | N/A (unpaired) | acc=9.84 m/s² | 2 (max 1.58s) | Driver F; phone-only; 73482 samples (missing mag/orient) |
| `T5` | 2037.0s | 23.8 Hz | N/A (unpaired) | acc=9.86 m/s² | 1578 (max 0.45s) | Driver F; phone-only; 46387 samples (missing mag/orient) |
| `T6` | 2510.9s | 1000.0 Hz | N/A (unpaired) | acc=9.89 m/s² | 18175 (max 3.45s) | Driver F; phone-only; 86440 samples (missing mag/orient) |
| `T7` | 10170.1s | 10.0 Hz | N/A (unpaired) | acc=9.88 m/s² | 2 (max 0.83s) | Driver F; phone-only; 101690 samples (missing mag/orient) |
| `T8` | 4833.8s | 10.0 Hz | N/A (unpaired) | acc=9.85 m/s² | 1 (max 0.71s) | Driver F; phone-only; 48344 samples (missing mag/orient) |
| `T9` | 7990.7s | 10.0 Hz | N/A (unpaired) | acc=9.80 m/s² | 0 | Driver F; phone-only; 79909 samples (missing mag/orient) |
| `Vfb01a` | 1699.9s | N/A (unpaired) | 10.0 Hz | spd=68.9 km/h | 0 | Driver E; VBOX-only; 17000 samples |
| `Vfb01b` | 387.9s | N/A (unpaired) | 10.0 Hz | spd=83.0 km/h | 0 | Driver E; VBOX-only; 3880 samples |
| `Vfb01c` | 631.9s | N/A (unpaired) | 10.0 Hz | spd=104.5 km/h | 0 | Driver E; VBOX-only; 6320 samples |
| `Vfb01d` | 1071.2s | N/A (unpaired) | 10.0 Hz | spd=56.0 km/h | 0 | Driver E; VBOX-only; 10713 samples |
| `Vfb02a` | 3595.9s | N/A (unpaired) | 10.0 Hz | spd=122.3 km/h | 0 | Driver E; VBOX-only; 35960 samples |
| `Vfb02b` | 1099.9s | N/A (unpaired) | 10.0 Hz | spd=84.3 km/h | 0 | Driver E; VBOX-only; 11000 samples |
| `Vfb02c` | 63.9s | N/A (unpaired) | 10.0 Hz | spd=52.8 km/h | 0 | Driver E; VBOX-only; 640 samples |
| `Vfb02d` | 87.9s | N/A (unpaired) | 10.0 Hz | spd=57.3 km/h | 0 | Driver E; VBOX-only; 880 samples |
| `Vfb02e` | 97.9s | N/A (unpaired) | 10.0 Hz | spd=73.9 km/h | 0 | Driver E; VBOX-only; 980 samples |
| `Vfb02f` | 65.9s | N/A (unpaired) | 10.0 Hz | spd=49.5 km/h | 0 | Driver E; VBOX-only; 660 samples |
| `Vfb02g` | 2715.8s | N/A (unpaired) | 10.0 Hz | spd=119.4 km/h | 0 | Driver E; VBOX-only; 27159 samples |
| `Vta18` | 9.9s | N/A (unpaired) | 10.0 Hz | spd=51.6 km/h | 0 | Driver E; VBOX-only; 100 samples |
| `Vtb13` | 124.4s | N/A (unpaired) | 10.0 Hz | spd=43.3 km/h | 0 | Driver E; VBOX-only; 1245 samples |
| `Y2` | 5721.2s | N/A (unpaired) | 10.0 Hz | spd=73.3 km/h | 0 | Driver D; VBOX-only; 57213 samples |

## 2. Columns: IMU vs Ground Truth Mapping

The IO-VNBD dataset records data from two independent physical systems: a windshield/dash-mounted Android smartphone (`S-*.csv`) and a vehicle-integrated Racelogic VBOX system with CAN bus taps (`V-*.csv`). The schema boundaries between client IMU features and supervisory ground truth are strictly defined as follows:

### 2.1 Smartphone Client Streams (`S-*.csv` — 24 Columns Nominal)

| Source Column Header | Normalized Field Name | Logical Sensor / Stream | Verified Physical Unit | Feature / Ground Truth Role |
| :--- | :--- | :--- | :--- | :--- |
| `TIME SINCE START (ms)` | `elapsed_counter` | Monotonic Clock | Milliseconds (ms) | Timing diagnostic (exhibits backward resets in some drives) |
| `DATE (YYYY-MO-DD HH-MI-SS_SSS)` | `datetime_raw` | Wall Clock Timestamp | Seconds (from start) | Primary sample clock and synchronization anchor |
| `ACCELEROMETER X (m/s²)` | `accel_x` | Specific Force Triad | m/s² (norm ~9.81 m/s² at rest) | **Candidate IMU Feature** (Blocked: export frame transform unverified) |
| `ACCELEROMETER Y (m/s²)` | `accel_y` | Specific Force Triad | m/s² | **Candidate IMU Feature** (Blocked: export frame transform unverified) |
| `ACCELEROMETER Z (m/s²)` | `accel_z` | Specific Force Triad | m/s² | **Candidate IMU Feature** (Blocked: export frame transform unverified) |
| `GRAVITY X (m/s²)` | `gravity_x` | Android Synthetic Gravity | m/s² (norm ~9.807 m/s²) | Diagnostic only; world-aligned export (tilt <0.32° from +Z) |
| `GRAVITY Y (m/s²)` | `gravity_y` | Android Synthetic Gravity | m/s² | Diagnostic only; world-aligned export |
| `GRAVITY Z (m/s²)` | `gravity_z` | Android Synthetic Gravity | m/s² | Diagnostic only; world-aligned export |
| `GYROSCOPE Yaw (rad/s)` / `X` | `gyro_channel_x` | Angular Velocity Triad | rad/s (source declared) | **Candidate IMU Feature** (Blocked: physical axes uncalibrated) |
| `GYROSCOPE Pitch (rad/s)` / `Y` | `gyro_channel_y` | Angular Velocity Triad | rad/s | **Candidate IMU Feature** (Blocked: physical axes uncalibrated) |
| `GYROSCOPE Roll (rad/s)` / `Z` | `gyro_channel_z` | Angular Velocity Triad | rad/s | **Candidate IMU Feature** (Blocked: physical axes uncalibrated) |
| `MAGNETIC FIELD X (μT)` | `magnetic_x` | 3-Axis Magnetometer | Microtesla (μT) | Context feature (absent in Driver F / T1–T9) |
| `MAGNETIC FIELD Y (μT)` | `magnetic_y` | 3-Axis Magnetometer | Microtesla (μT) | Context feature |
| `MAGNETIC FIELD Z (μT)` | `magnetic_z` | 3-Axis Magnetometer | Microtesla (μT) | Context feature |
| `ORIENTATION (Yaw/Azimuth) (°)` | `orientation_azimuth` | Android Fused Orientation | Degrees (0°–360°) | Diagnostic only; Android magnetic-world orientation |
| `ORIENTATION (Pitch) (°)` | `orientation_pitch` | Android Fused Orientation | Degrees (-180°–+180°) | Diagnostic only |
| `ORIENTATION (Roll) (°)` | `orientation_roll` | Android Fused Orientation | Degrees (-90°–+90°) | Diagnostic only |
| `GPS LATITUDE (degrees)` | `gps_latitude` | Smartphone GNSS Receiver | Degrees (WGS-84) | Context / Initial Fix only (**NEVER Ground Truth**) |
| `GPS LONGITUDE (degrees)` | `gps_longitude` | Smartphone GNSS Receiver | Degrees (WGS-84) | Context / Initial Fix only (**NEVER Ground Truth**) |
| `GPS ALTITUDE (m)` | `gps_altitude` | Smartphone GNSS Receiver | Metres | Context only |
| `GPS SPEED (Kmh)` | `gps_speed_raw` | Smartphone GNSS Receiver | km/h | Low-speed standstill detection candidate |
| `GPS ACCURACY (m)` | `gps_accuracy` | Smartphone GNSS Receiver | Metres | Phone GNSS quality gating |
| `GPS ORIENTATION (°)` | `gps_course` | Smartphone GNSS Receiver | Degrees | Diagnostic course over ground |
| `GPS SATELLITES IN RANGE` | `gps_satellites_raw` | Smartphone GNSS Receiver | Integer count | Diagnostic context |

### 2.2 VBOX Reference & Odometry Streams (`V-*.csv` — 29 Columns Fixed)

| Source Column Header | Normalized Field Name | Logical Sensor / Subsystem | Verified Physical Unit | Role in Dead Reckoning Benchmarking |
| :--- | :--- | :--- | :--- | :--- |
| `Time Since Start of Day (seconds)` | `reference_time_of_day` | High-Precision VBOX Clock | Seconds (s) | **Ground Truth Reference Time Base** |
| `Sample period (seconds)` | `reference_sample_period` | Acquisition Clock | Seconds (nominal 0.1 s) | Sampling interval monitor |
| `Latitude (degrees)` | `reference_latitude` | Dual-Antenna RTK/VBOX GNSS | Degrees (WGS-84) | **Ground Truth Position Reference (Lat)** |
| `Longitude (degrees)` | `reference_longitude` | Dual-Antenna RTK/VBOX GNSS | Degrees (WGS-84) | **Ground Truth Position Reference (Lon)** |
| `Height (km)` | `reference_height_raw` | VBOX Surveyed Height | km (provisional scale) | Ground Truth Vertical Profile |
| `Velocity (km/hr)` | `reference_speed` | VBOX Doppler Speed | km/h (verified against wheels) | **Ground Truth Speed Reference** |
| `Vertical velocity (km/hr)` | `reference_vertical_speed` | VBOX Doppler Vertical Speed | km/h | Ground Truth Vertical Velocity |
| `Heading (degrees)` | `reference_heading` | VBOX Dual-Antenna True Heading | Degrees (0°–360° True North) | **Ground Truth Vehicle Heading** |
| `No of GPS Satellites Available` | `reference_satellites_raw` | VBOX Base Satellite Count | Integer count | VBOX GNSS fix quality verification |
| `Wheel Speed Front Left (rad/sec)` | `vehicle_wheel_speed_fl` | Vehicle Wheel Ticks (CAN) | rad/s (gives ~0.277m tire radius) | Supervisory Odometry / Wheel Speed |
| `Wheel Speed Front Right (rad/sec)` | `vehicle_wheel_speed_fr` | Vehicle Wheel Ticks (CAN) | rad/s | Supervisory Odometry / Wheel Speed |
| `Wheel Speed Rear Left (rad/sec)` | `vehicle_wheel_speed_rl` | Vehicle Wheel Ticks (CAN) | rad/s | Supervisory Odometry / Wheel Speed |
| `Wheel Speed Rear Right (rad/sec)` | `vehicle_wheel_speed_rr` | Vehicle Wheel Ticks (CAN) | rad/s | Supervisory Odometry / Wheel Speed |
| `Indicated Vehicle Speed (km/hr)` | `vehicle_indicated_speed` | Vehicle Speedometer (CAN) | km/h | Secondary Vehicle Speed Reference |
| `Steering Angle (degrees)` | `vehicle_steering_angle` | Steering Column Sensor (CAN)| Degrees | Vehicle Dynamics / Ackerman Model |
| `Yaw Rate (deg/sec)` | `vehicle_yaw_rate` | Chassis IMU (CAN) | deg/s | **Ground Truth Vehicle Yaw Rate** |
| `Indicated Longitudinal Accel (g)` | `vehicle_accel_long` | Chassis Accelerometer (CAN) | g (~9.81 m/s² scale) | Chassis Longitudinal Acceleration |
| `Indicated Lateral Accel (g)` | `vehicle_accel_lat` | Chassis Accelerometer (CAN) | g (~9.81 m/s² scale) | Chassis Lateral Acceleration |
| `Engine Speed (rev/min)` | `vehicle_engine_speed` | Engine Tachometer (CAN) | rpm | Powertrain state context |
| `Gear Requested` / `Gear` | `vehicle_gear` | Transmission Controller (CAN) | Discrete gear number | Powertrain state context |
| `Accelerator Pedal Position` | `vehicle_accelerator_pedal`| Throttle Sensor (CAN) | Percent-like scale (0–100) | Driver intent context |
| `Brake Position` / `Brake Pressure` | `vehicle_brake_pressure` | Hydraulic Brake Transducer | Flag (0/1) / psi | Braking dynamics context |
| `Clutch Position` / `Handbrake` | `vehicle_clutch` / `handbrake`| Switch sensors (CAN) | Discrete flags (0/1) | Powertrain & parking state |
| `Battery Voltage` / `Air/Coolant Temp` | Diagnostic channels (CAN) | Voltage (V) / deg C | Auxiliary diagnostics |

## 3. Known Frame Problem Summary

The repository's investigation in `reports/frame_resolution_followup.md` established that while label-level ambiguity was resolved (e.g., categorized gyro `Yaw/Pitch/Roll` corresponds strictly to `X/Y/Z` in uncategorized files), the physical coordinate frames of the phone exports suffer from a fundamental, disqualifying contradiction: across all 72 paired drives, the exported gravity vector's median tilt relative to the exported +Z axis is nearly zero (0.017°–0.315°), yet the device's true spatial orientation derived from the orientation matrix indicates device-up is tilted 85.7°–98.8° away from +Z (reflecting an upright windshield/dashboard phone mount). This physical impossibility demonstrates that the recording application (AndroSensor) did not record raw IMU measurements in the phone's physical body frame, but instead pre-rotated linear acceleration and gravity into an unverified, partially world-stabilized coordinate system, while leaving gyroscope and magnetometer channels in an uncalibrated, unaligned device frame without an accompanying mounting rotation.

Quoting the exact blocking statement from `reports/frame_resolution_followup.md` (lines 15–17 and 281–282):

> "Still unresolved: a verified complete gyro triad in physical device axes, the precise acceleration export transform and timing, and a stable validated device-to-vehicle mounting rotation. Full 3-D strapdown Phase 1 remains blocked."

> "Readiness: source-label ambiguity reduced and export-frame mismatch better explained; a credible full 3-D raw-INS baseline is still not approved."

## 4. Open Questions and Unverified Aspects

1. **Android Logger Transform Pipeline:** The raw dataset distributions lack application source code, APK binaries, or configuration logs for AndroSensor v1.9.6.1. It cannot be mathematically proven whether the acceleration export is a pure software-filtered linear acceleration, an Android `Sensor.TYPE_LINEAR_ACCELERATION` fusion output, or a partial ENU projection.

2. **Physical Sensor Axis Triad vs Handset Body:** While `GYROSCOPE Yaw/Pitch/Roll` was matched to `X/Y/Z`, the signs and physical handedness of the gyroscope hardware axes relative to the phone casing were never calibrated against a turn-table or static precision fixture.

3. **Mounting Calibration Stability:** Because the smartphone was mounted by different drivers (A through H) with different physical mounts, the device-to-vehicle mounting rotation matrix $C_{v}^{b}$ is non-constant across drivers. Applying an estimated rotation from Driver B (`M`) to Driver A (`S1`-`S4`) or Driver E (`Vta`/`Vtb`/`Vw`) produces catastrophic velocity drift.

4. **Unsynchronized Partitions:** 43 sequences in `Unsynchronised V and S Dataset` are completely unpaired (25 smartphone-only drives including Driver H and Driver F, and 18 VBOX-only drives). They cannot be used for supervised dead reckoning training because they lack concurrent reference trajectories.

5. **Quarantined Drives:** Sequence `S-A4` has a corrupt CSV header that shifts columns by one index starting at column 6, and Driver F sequences (`T1`–`T9`) completely omit the magnetometer and orientation columns.

6. **VBOX Height Datum:** The VBOX `Height (km)` column has values between 0.05 and 0.30, but whether this represents ellipsoidal height, orthometric height, or raw altitude in kilometers or meters remains unverified by ground survey.

