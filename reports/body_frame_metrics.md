# Measured frame evidence

Confirmed from data: these results use same-row pairs without fitting or applying a time warp.
Each sequence is processed separately. Three non-overlapping temporal thirds supply the most
maneuver-rich eligible window per third; selection uses reference maneuver strength and clock quality,
not phone correlation. These are development diagnostics, not a held-out evaluation.
Lag search is ±10 rows (nominal ±1 s), with common central support. Positive lag compares
phone[i+lag] against VBOX[i]. Zero-lag errors use the full window. No lag is applied.
Regression convention: prediction = slope × reference + intercept. RMSE is not bias/scale corrected.
Gyro scores combine signed correlation, slope distance from +1, intercept/scale, NRMSE, and speed×yaw consistency.
Acceptance: corr≥0.8, slope 0.7–1.3, NRMSE≤0.7, |intercept|≤0.03 rad/s, reference std≥0.025 rad/s.
These are declared engineering gates, not statistical confidence probabilities.

## M — Driver B

Phase 0 alignment: approximate. Rows: 105974. Gravity mean: [-0.00014959518372430973, -0.00022312737086455174, 9.806492526468757] m/s²; std: [0.026986950945446998, 0.033723275340731426, 0.009823515077485339].
Stationary: 6070 samples / 607.0 nominal seconds. Accel norm median: 9.8626; minus-gravity norm: 0.2735; plus-gravity norm: 19.6652 m/s².
Changed-row fractions: `{'accel': 0.9999905636341332, 'gyro': 0.9999905636341332, 'gravity': 0.9998678908778651, 'gps_position': 0.010285638794787352, 'vbox_yaw': 0.7812084210128996, 'vbox_long': 0.4411689770035764, 'vbox_lat': 0.6160908910760288}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.

Independent stationary selection: both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples. Mounting rotation maximum between-window separation: 96.9895 degrees. Accepted gyro/acceleration windows: 1/0.

| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |
|---|---:|---:|---:|---:|---:|
| yaw_vs_negative_heading_derivative | 0.9838 | 1.0197 | -0.0010 | 0.0298 | 0.1870 |
| yaw_vs_positive_heading_derivative | -0.9838 | -1.0197 | -0.0010 | 0.3266 | 2.0494 |
| lateral_vs_speed_yaw | 0.9729 | 1.0327 | -0.0041 | 0.3221 | 0.2478 |
| longitudinal_g_scaled_vs_dvdt | 0.8538 | 0.9414 | -0.0391 | 0.4311 | 0.5793 |
| longitudinal_raw_vs_dvdt | 0.8538 | 0.0960 | -0.0040 | 0.6741 | 0.9059 |

### Rows 1500–2699 (150.0–269.9 s)

Left/right maneuver samples: 235/747; reference yaw std 0.3450 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9096 | 0.9202 | -0.0200 | 0.1476 | 0.4279 | -9 | 0.9923 | 1.0038 | 0.0001 | 0.0432 | 0.1251 |
| -1 × gyro_channel_roll | 0.3878 | 0.0671 | -0.0009 | 0.4267 | 1.2368 | -10 | 0.4771 | 0.0826 | 0.0035 | 0.4227 | 1.2240 |
| +1 × gyro_channel_yaw | 0.2298 | 0.0592 | 0.0061 | 0.4399 | 1.2751 | -10 | 0.2520 | 0.0651 | 0.0082 | 0.4391 | 1.2713 |
| -1 × gyro_channel_yaw | -0.2298 | -0.0592 | -0.0061 | 0.4850 | 1.4056 | 10 | -0.1621 | -0.0419 | -0.0014 | 0.4810 | 1.3927 |
| +1 × gyro_channel_roll | -0.3878 | -0.0671 | 0.0009 | 0.4884 | 1.4156 | 10 | -0.3418 | -0.0592 | 0.0033 | 0.4870 | 1.4102 |
| -1 × gyro_channel_pitch | -0.9096 | -0.9202 | 0.0200 | 0.8972 | 2.6003 | 10 | -0.7909 | -0.7995 | 0.0497 | 0.8778 | 2.5417 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: 27.750°; det 1.00000000; orthogonality error 6.7e-16; excitation ratio 0.1371; accepted: False.
```text
[[ 8.8498558e-01 -4.6561842e-01  8.7193843e-05]
 [ 4.6561842e-01  8.8498559e-01  4.4038810e-06]
 [-7.9215823e-05  3.6701688e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.2101 | 0.5588 | -0.3803 | 2.3167 | 2.6540 |
| lateral | 0.5077 | 0.4138 | -0.4485 | 2.4042 | 0.9386 |
| filtered_longitudinal | 0.2282 | 0.5903 | -0.3720 | 2.1908 | 2.5687 |
| filtered_lateral | 0.5395 | 0.4160 | -0.4418 | 2.2908 | 0.8988 |
| first-half fit / second-half longitudinal | 0.1945 | 0.3476 | -0.0514 | 2.0489 | 1.8735 |
| first-half fit / second-half lateral | 0.0231 | 0.0181 | -0.2770 | 2.6456 | 1.2969 |

Acceleration best lag (not applied): long=-9, lateral=-9 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 1.1331 m/s²; norm distribution {'n': 1196, 'mean': 0.8678863289750576, 'median': 0.6779472758487841, 'std': 0.7284892731533368, 'p95': 2.197033193531433, 'maximum': 5.911559033111988}. This includes filter delay, not just vibration. Gravity mapped: [1.962073241264808e-19, 9.559317633045897e-21, 9.806542537373524].

### Rows 53624–54823 (5364.7–5484.6 s)

Left/right maneuver samples: 169/231; reference yaw std 0.2699 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.5517 | 0.4489 | -0.0056 | 0.2386 | 0.8841 | -10 | 0.6686 | 0.5729 | -0.0011 | 0.2001 | 0.7745 |
| -1 × gyro_channel_roll | 0.3194 | 0.0587 | 0.0001 | 0.2679 | 0.9924 | -10 | 0.3963 | 0.0761 | 0.0002 | 0.2509 | 0.9711 |
| +1 × gyro_channel_yaw | 0.2960 | 0.0392 | -0.0010 | 0.2711 | 1.0042 | -10 | 0.3519 | 0.0486 | -0.0007 | 0.2560 | 0.9910 |
| -1 × gyro_channel_yaw | -0.2960 | -0.0392 | 0.0010 | 0.2934 | 1.0869 | 10 | -0.1288 | -0.0167 | 0.0012 | 0.2738 | 1.0598 |
| +1 × gyro_channel_roll | -0.3194 | -0.0587 | -0.0001 | 0.3003 | 1.1125 | 10 | -0.1036 | -0.0186 | 0.0007 | 0.2760 | 1.0683 |
| -1 × gyro_channel_pitch | -0.5517 | -0.4489 | 0.0056 | 0.4467 | 1.6549 | 10 | -0.2889 | -0.1898 | 0.0082 | 0.3587 | 1.3885 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: False.
Mounting yaw relative to deterministic leveled basis: 11.630°; det 1.00000000; orthogonality error 6.7e-16; excitation ratio 0.0895; accepted: False.
```text
[[ 9.7947100e-01 -2.0158510e-01  8.4520285e-06]
 [ 2.0158510e-01  9.7947100e-01  1.9303812e-06]
 [-8.6676529e-06 -1.8694938e-07  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.2805 | 0.4051 | -0.3351 | 1.4266 | 1.5519 |
| lateral | 0.4080 | 0.3206 | -0.0891 | 2.0862 | 0.9967 |
| filtered_longitudinal | 0.3074 | 0.4187 | -0.3393 | 1.3369 | 1.4690 |
| filtered_lateral | 0.4194 | 0.3111 | -0.0874 | 2.0121 | 0.9724 |
| first-half fit / second-half longitudinal | 0.3935 | 0.4407 | -0.2889 | 1.1017 | 1.2330 |
| first-half fit / second-half lateral | -0.2569 | -0.3316 | -0.2260 | 1.5809 | 1.8487 |

Acceleration best lag (not applied): long=-10, lateral=-10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.7462 m/s²; norm distribution {'n': 1196, 'mean': 0.6266547778846444, 'median': 0.5327775941209208, 'std': 0.4050787222763445, 'p95': 1.3985940464506286, 'maximum': 2.878540158003339}. This includes filter delay, not just vibration. Gravity mapped: [1.617991302613121e-20, 1.3454065870762367e-21, 9.806576333701882].

### Rows 82349–83548 (8237.2–8357.1 s)

Left/right maneuver samples: 283/228; reference yaw std 0.1697 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_yaw | 0.1060 | 0.0199 | -0.0041 | 0.1703 | 1.0031 | -10 | 0.2154 | 0.0404 | -0.0036 | 0.1681 | 0.9825 |
| -1 × gyro_channel_roll | 0.0885 | 0.0182 | 0.0020 | 0.1719 | 1.0125 | -10 | 0.2288 | 0.0470 | 0.0025 | 0.1683 | 0.9837 |
| +1 × gyro_channel_pitch | 0.1063 | 0.1082 | -0.0087 | 0.2293 | 1.3507 | -10 | 0.3817 | 0.3887 | -0.0026 | 0.1924 | 1.1243 |
| +1 × gyro_channel_roll | -0.0885 | -0.0182 | -0.0020 | 0.1775 | 1.0456 | 10 | -0.0060 | -0.0012 | -0.0016 | 0.1761 | 1.0291 |
| -1 × gyro_channel_yaw | -0.1060 | -0.0199 | 0.0041 | 0.1780 | 1.0486 | 10 | -0.0235 | -0.0044 | 0.0044 | 0.1769 | 1.0338 |
| -1 × gyro_channel_pitch | -0.1063 | -0.1082 | 0.0087 | 0.2569 | 1.5136 | 10 | 0.0218 | 0.0222 | 0.0117 | 0.2439 | 1.4253 |

Zero-lag selection: +1 × gyro_channel_yaw; accepted: False.
Mounting yaw relative to deterministic leveled basis: -69.239°; det 1.00000000; orthogonality error 1.6e-16; excitation ratio 0.1028; accepted: False.
```text
[[ 3.5446589e-01  9.3506895e-01  1.2988952e-05]
 [-9.3506895e-01  3.5446589e-01  1.7637647e-05]
 [ 1.1888275e-05 -1.8397510e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.3900 | 1.3142 | 0.0494 | 1.2406 | 3.1195 |
| lateral | -0.0173 | -0.0103 | 0.2646 | 1.5844 | 1.1983 |
| filtered_longitudinal | 0.4107 | 1.3461 | 0.0520 | 1.1832 | 3.0089 |
| filtered_lateral | -0.0167 | -0.0090 | 0.2656 | 1.5351 | 1.1704 |
| first-half fit / second-half longitudinal | -0.0034 | -0.0066 | 0.4515 | 1.1238 | 2.4985 |
| first-half fit / second-half lateral | -0.2236 | -0.2273 | 0.0497 | 2.4149 | 1.5986 |

Acceleration best lag (not applied): long=-10, lateral=10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.5369 m/s²; norm distribution {'n': 1196, 'mean': 0.43047417450968944, 'median': 0.3577664018155514, 'std': 0.32094519623630174, 'p95': 0.9740895815942694, 'maximum': 3.813369644998152}. This includes filter delay, not just vibration. Gravity mapped: [-1.13209534410749e-20, -1.518368876227962e-20, 9.80658083568593].
## S1 — Driver A

Phase 0 alignment: uncertain. Rows: 51746. Gravity mean: [-0.00017001700614540256, 3.469833417075718e-05, 9.806545477911337] m/s²; std: [0.01949274661583956, 0.022921523726294607, 0.0001766533578189854].
Stationary: 2333 samples / 233.3 nominal seconds. Accel norm median: 9.8750; minus-gravity norm: 0.2531; plus-gravity norm: 19.6762 m/s².
Changed-row fractions: `{'accel': 0.9999613489226012, 'gyro': 0.9999226978452024, 'gravity': 0.9999420233839018, 'gps_position': 0.010261861049376752, 'vbox_yaw': 0.7974683544303798, 'vbox_long': 0.48400811672625377, 'vbox_lat': 0.6431732534544401}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.

Independent stationary selection: both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples. Mounting rotation maximum between-window separation: 40.8659 degrees. Accepted gyro/acceleration windows: 3/0.

| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |
|---|---:|---:|---:|---:|---:|
| yaw_vs_negative_heading_derivative | 0.9819 | 1.0308 | 0.0007 | 0.0259 | 0.2013 |
| yaw_vs_positive_heading_derivative | -0.9819 | -1.0308 | 0.0007 | 0.2629 | 2.0410 |
| lateral_vs_speed_yaw | 0.9221 | 1.0139 | -0.0053 | 0.3235 | 0.4259 |
| longitudinal_g_scaled_vs_dvdt | 0.8503 | 0.8907 | 0.0074 | 0.3868 | 0.5622 |
| longitudinal_raw_vs_dvdt | 0.8503 | 0.0908 | 0.0008 | 0.6267 | 0.9109 |

### Rows 14100–15299 (1410.0–1529.9 s)

Left/right maneuver samples: 328/234; reference yaw std 0.1877 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9614 | 0.9852 | -0.0074 | 0.0535 | 0.2852 | -2 | 0.9757 | 0.9990 | -0.0079 | 0.0431 | 0.2284 |
| -1 × gyro_channel_roll | 0.4595 | 0.1315 | -0.0051 | 0.1699 | 0.9052 | -5 | 0.4766 | 0.1357 | -0.0049 | 0.1699 | 0.9000 |
| +1 × gyro_channel_yaw | 0.1501 | 0.0718 | 0.0045 | 0.1956 | 1.0423 | -5 | 0.1598 | 0.0753 | 0.0044 | 0.1955 | 1.0357 |
| -1 × gyro_channel_yaw | -0.1501 | -0.0718 | -0.0045 | 0.2199 | 1.1719 | 10 | -0.0982 | -0.0469 | -0.0044 | 0.2170 | 1.1496 |
| +1 × gyro_channel_roll | -0.4595 | -0.1315 | 0.0051 | 0.2177 | 1.1602 | 10 | -0.3445 | -0.0985 | 0.0049 | 0.2135 | 1.1314 |
| -1 × gyro_channel_pitch | -0.9614 | -0.9852 | 0.0074 | 0.3764 | 2.0057 | 10 | -0.7256 | -0.7451 | 0.0075 | 0.3555 | 1.8836 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: 22.120°; det 1.00000000; orthogonality error 6.7e-16; excitation ratio 0.3523; accepted: False.
```text
[[ 9.2639632e-01 -3.7654994e-01  1.1757455e-05]
 [ 3.7654994e-01  9.2639632e-01  5.6220515e-05]
 [-3.2061894e-05 -4.7655209e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.3939 | 0.4624 | -0.0902 | 1.0930 | 1.2055 |
| lateral | 0.5538 | 0.5905 | -0.1695 | 1.0677 | 0.9941 |
| filtered_longitudinal | 0.4872 | 0.4722 | -0.0902 | 0.8963 | 0.9976 |
| filtered_lateral | 0.6611 | 0.6120 | -0.1699 | 0.8633 | 0.8159 |
| first-half fit / second-half longitudinal | 0.2511 | 0.2422 | -0.2779 | 1.2517 | 1.2499 |
| first-half fit / second-half lateral | 0.3282 | 0.3322 | 0.1675 | 1.2015 | 1.1674 |

Acceleration best lag (not applied): long=-3, lateral=-3 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.8580 m/s²; norm distribution {'n': 1196, 'mean': 0.7072367005880644, 'median': 0.6037086750766524, 'std': 0.48575622237033134, 'p95': 1.587114751098229, 'maximum': 4.320335782757107}. This includes filter delay, not just vibration. Gravity mapped: [5.35903353588692e-20, 6.380600418578066e-20, 9.806553016175831].

### Rows 30148–31347 (3014.8–3134.7 s)

Left/right maneuver samples: 414/228; reference yaw std 0.1960 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9715 | 0.9799 | -0.0049 | 0.0473 | 0.2414 | -2 | 0.9854 | 0.9930 | -0.0048 | 0.0337 | 0.1735 |
| -1 × gyro_channel_roll | 0.5748 | 0.1329 | -0.0031 | 0.1746 | 0.8910 | -6 | 0.6111 | 0.1425 | -0.0035 | 0.1715 | 0.8823 |
| +1 × gyro_channel_yaw | 0.1146 | 0.0510 | 0.0011 | 0.2055 | 1.0488 | 0 | 0.1159 | 0.0517 | 0.0008 | 0.2041 | 1.0499 |
| -1 × gyro_channel_yaw | -0.1146 | -0.0510 | -0.0011 | 0.2240 | 1.1431 | 10 | -0.0886 | -0.0398 | -0.0016 | 0.2210 | 1.1365 |
| +1 × gyro_channel_roll | -0.5748 | -0.1329 | 0.0031 | 0.2255 | 1.1505 | 10 | -0.3716 | -0.0827 | 0.0011 | 0.2150 | 1.1061 |
| -1 × gyro_channel_pitch | -0.9715 | -0.9799 | 0.0049 | 0.3915 | 1.9978 | 10 | -0.7092 | -0.6850 | -0.0055 | 0.3551 | 1.8263 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: 56.506°; det 1.00000000; orthogonality error 1.3e-15; excitation ratio 0.5706; accepted: False.
```text
[[ 5.5185185e-01 -8.3394217e-01 -5.4222466e-05]
 [ 8.3394217e-01  5.5185185e-01  1.1884066e-05]
 [ 2.0012145e-05 -5.1776645e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.5816 | 0.9171 | -0.2566 | 0.8956 | 1.3417 |
| lateral | 0.6641 | 0.6869 | -0.0998 | 0.8614 | 0.8444 |
| filtered_longitudinal | 0.7210 | 0.9714 | -0.2578 | 0.6622 | 1.0139 |
| filtered_lateral | 0.7651 | 0.7017 | -0.0979 | 0.6769 | 0.6743 |
| first-half fit / second-half longitudinal | 0.6472 | 0.9294 | -0.3281 | 0.8875 | 1.1827 |
| first-half fit / second-half lateral | 0.7046 | 0.7313 | -0.2661 | 0.7779 | 0.8576 |

Acceleration best lag (not applied): long=-3, lateral=-3 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.7828 m/s²; norm distribution {'n': 1196, 'mean': 0.607810879327147, 'median': 0.48630440185300805, 'std': 0.49322670508119226, 'p95': 1.50699650582147, 'maximum': 4.338549180478732}. This includes filter delay, not just vibration. Gravity mapped: [6.240542893515967e-20, -2.2659075264916373e-20, 9.806545098441822].

### Rows 44697–45896 (4469.7–4589.6 s)

Left/right maneuver samples: 97/316; reference yaw std 0.1936 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9560 | 0.9767 | -0.0034 | 0.0582 | 0.3007 | -3 | 0.9649 | 0.9852 | -0.0030 | 0.0524 | 0.2687 |
| -1 × gyro_channel_roll | 0.4406 | 0.1332 | 0.0006 | 0.1893 | 0.9775 | -6 | 0.4605 | 0.1388 | 0.0009 | 0.1890 | 0.9701 |
| +1 × gyro_channel_yaw | 0.0590 | 0.0328 | -0.0012 | 0.2290 | 1.1828 | -6 | 0.0658 | 0.0365 | -0.0009 | 0.2296 | 1.1780 |
| -1 × gyro_channel_yaw | -0.0590 | -0.0328 | 0.0012 | 0.2421 | 1.2504 | 8 | -0.0518 | -0.0288 | 0.0014 | 0.2427 | 1.2455 |
| +1 × gyro_channel_roll | -0.4406 | -0.1332 | -0.0006 | 0.2430 | 1.2547 | 10 | -0.3528 | -0.1065 | 0.0014 | 0.2400 | 1.2314 |
| -1 × gyro_channel_pitch | -0.9560 | -0.9767 | 0.0034 | 0.4195 | 2.1666 | 10 | -0.8127 | -0.8292 | 0.0134 | 0.4074 | 2.0905 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: 62.986°; det 1.00000000; orthogonality error 2.2e-15; excitation ratio 0.6606; accepted: False.
```text
[[ 4.5420769e-01 -8.9089583e-01  7.1791428e-05]
 [ 8.9089582e-01  4.5420769e-01  1.2242287e-04]
 [-1.4167424e-04  8.3532739e-06  9.9999999e-01]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.4219 | 0.7078 | 0.0854 | 1.2749 | 1.5545 |
| lateral | 0.3935 | 0.4001 | -0.1395 | 1.4313 | 1.1145 |
| filtered_longitudinal | 0.4902 | 0.7216 | 0.0859 | 1.0752 | 1.3196 |
| filtered_lateral | 0.4570 | 0.4039 | -0.1354 | 1.2637 | 0.9910 |
| first-half fit / second-half longitudinal | 0.1051 | 0.2576 | 0.0398 | 1.5786 | 2.5494 |
| first-half fit / second-half lateral | 0.3796 | 0.3997 | 0.0350 | 1.9460 | 1.1983 |

Acceleration best lag (not applied): long=-3, lateral=-3 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.9465 m/s²; norm distribution {'n': 1196, 'mean': 0.7756615100154545, 'median': 0.6596309777997217, 'std': 0.5423438152740258, 'p95': 1.8922101598720824, 'maximum': 4.042547591208503}. This includes filter delay, not just vibration. Gravity mapped: [-2.7480572995585816e-20, -2.824144900295433e-19, 9.806534265425178].
## S2 — Driver A

Phase 0 alignment: approximate. Rows: 93876. Gravity mean: [-3.29423920916954e-05, -0.0002619966764668286, 9.806450469768631] m/s²; std: [0.0377312107113705, 0.03690310366102827, 0.002507596259621893].
Stationary: 4353 samples / 435.3 nominal seconds. Accel norm median: 9.8721; minus-gravity norm: 0.1816; plus-gravity norm: 19.6771 m/s².
Changed-row fractions: `{'accel': 0.9999147802929428, 'gyro': 0.999925432756325, 'gravity': 0.999861517976032, 'gps_position': 0.010013315579227697, 'vbox_yaw': 0.7839147802929427, 'vbox_long': 0.46945406125166445, 'vbox_lat': 0.6112596537949401}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.

Independent stationary selection: both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples. Mounting rotation maximum between-window separation: 79.5989 degrees. Accepted gyro/acceleration windows: 0/0.

| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |
|---|---:|---:|---:|---:|---:|
| yaw_vs_negative_heading_derivative | 0.9846 | 1.0385 | 0.0000 | 0.0255 | 0.1881 |
| yaw_vs_positive_heading_derivative | -0.9846 | -1.0385 | 0.0000 | 0.2776 | 2.0468 |
| lateral_vs_speed_yaw | 0.9405 | 1.0202 | -0.0141 | 0.3238 | 0.3697 |
| longitudinal_g_scaled_vs_dvdt | 0.9255 | 1.0218 | -0.0127 | 0.2929 | 0.4190 |
| longitudinal_raw_vs_dvdt | 0.9255 | 0.1042 | -0.0013 | 0.6269 | 0.8968 |

### Rows 23400–24599 (2341.1–2461.0 s)

Left/right maneuver samples: 95/343; reference yaw std 0.2229 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.4069 | 0.4088 | -0.0624 | 0.2435 | 1.0922 | -10 | 0.4535 | 0.4572 | -0.0595 | 0.2343 | 1.0501 |
| -1 × gyro_channel_roll | 0.1509 | 0.0539 | -0.0090 | 0.2482 | 1.1136 | -10 | 0.1723 | 0.0613 | -0.0092 | 0.2458 | 1.1020 |
| +1 × gyro_channel_yaw | 0.0110 | 0.0094 | -0.0040 | 0.3134 | 1.4058 | 2 | 0.0122 | 0.0104 | -0.0044 | 0.3127 | 1.4016 |
| -1 × gyro_channel_yaw | -0.0110 | -0.0094 | 0.0040 | 0.3202 | 1.4363 | 10 | -0.0025 | -0.0022 | 0.0045 | 0.3177 | 1.4243 |
| +1 × gyro_channel_roll | -0.1509 | -0.0539 | 0.0090 | 0.2825 | 1.2671 | 10 | -0.1343 | -0.0471 | 0.0099 | 0.2804 | 1.2568 |
| -1 × gyro_channel_pitch | -0.4069 | -0.4088 | 0.0624 | 0.4405 | 1.9763 | 10 | -0.3856 | -0.3869 | 0.0689 | 0.4393 | 1.9694 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: False.
Mounting yaw relative to deterministic leveled basis: 69.089°; det 1.00000000; orthogonality error 6.7e-16; excitation ratio 0.1028; accepted: False.
```text
[[ 3.5692444e-01 -9.3413325e-01  6.2855137e-05]
 [ 9.3413325e-01  3.5692445e-01  4.2246269e-05]
 [-6.1898180e-05  4.3636347e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | -0.1276 | -0.4845 | 0.2979 | 1.6841 | 4.1091 |
| lateral | 0.3267 | 0.3660 | 0.2969 | 2.2355 | 1.3479 |
| filtered_longitudinal | -0.1576 | -0.5031 | 0.2969 | 1.4457 | 3.5651 |
| filtered_lateral | 0.4011 | 0.3699 | 0.2979 | 1.9594 | 1.1849 |
| first-half fit / second-half longitudinal | 0.2221 | 0.9256 | -0.4751 | 2.0302 | 4.1743 |
| first-half fit / second-half lateral | -0.2650 | -0.2612 | -0.5723 | 3.4761 | 1.7107 |

Acceleration best lag (not applied): long=-10, lateral=-10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 1.3738 m/s²; norm distribution {'n': 1196, 'mean': 1.1184462331914333, 'median': 0.9265983796198155, 'std': 0.7977190128349894, 'p95': 2.6934170080520876, 'maximum': 5.493670836438142}. This includes filter delay, not just vibration. Gravity mapped: [8.653432665283093e-20, 1.2170921384464242e-19, 9.80642727812246].

### Rows 57992–59191 (5800.3–5920.2 s)

Left/right maneuver samples: 269/212; reference yaw std 0.1988 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| -1 × gyro_channel_roll | 0.0414 | 0.0126 | -0.0032 | 0.2065 | 1.0385 | 10 | 0.0582 | 0.0181 | -0.0032 | 0.2031 | 1.0335 |
| +1 × gyro_channel_roll | -0.0414 | -0.0126 | 0.0032 | 0.2108 | 1.0601 | -10 | 0.0468 | 0.0146 | 0.0026 | 0.2033 | 1.0349 |
| +1 × gyro_channel_yaw | 0.0005 | 0.0003 | 0.0019 | 0.2389 | 1.2014 | -9 | 0.0099 | 0.0067 | 0.0018 | 0.2362 | 1.2022 |
| -1 × gyro_channel_yaw | -0.0005 | -0.0003 | -0.0019 | 0.2393 | 1.2034 | 1 | 0.0003 | 0.0002 | -0.0022 | 0.2372 | 1.2071 |
| -1 × gyro_channel_pitch | 0.0194 | 0.0196 | -0.0048 | 0.2804 | 1.4104 | -10 | 0.1024 | 0.1052 | -0.0076 | 0.2675 | 1.3617 |
| +1 × gyro_channel_pitch | -0.0194 | -0.0196 | 0.0048 | 0.2853 | 1.4352 | 10 | 0.0245 | 0.0252 | 0.0042 | 0.2786 | 1.4178 |

Zero-lag selection: -1 × gyro_channel_roll; accepted: False.
Mounting yaw relative to deterministic leveled basis: 11.272°; det 1.00000000; orthogonality error 4.4e-16; excitation ratio 0.4322; accepted: False.
```text
[[ 9.8070860e-01 -1.9547541e-01  8.0395360e-05]
 [ 1.9547542e-01  9.8070860e-01 -1.6139708e-05]
 [-7.5689505e-05  3.1543667e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.0722 | 0.0893 | -0.0557 | 1.6320 | 1.5332 |
| lateral | 0.1711 | 0.1882 | -0.0431 | 1.4970 | 1.3553 |
| filtered_longitudinal | 0.0865 | 0.0929 | -0.0545 | 1.4735 | 1.4030 |
| filtered_lateral | 0.2109 | 0.1981 | -0.0432 | 1.3205 | 1.2208 |
| first-half fit / second-half longitudinal | -0.2009 | -0.2587 | 0.0629 | 2.1386 | 1.7821 |
| first-half fit / second-half lateral | -0.2122 | -0.2027 | 0.1257 | 1.9450 | 1.5676 |

Acceleration best lag (not applied): long=-6, lateral=2 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.9793 m/s²; norm distribution {'n': 1196, 'mean': 0.780746659949155, 'median': 0.6279909464432426, 'std': 0.5912284162814331, 'p95': 1.9126663797519565, 'maximum': 4.357159792289496}. This includes filter delay, not just vibration. Gravity mapped: [-5.847070995171129e-20, 1.7267313489855588e-20, 9.806511449635689].

### Rows 75784–76983 (7579.5–7699.4 s)

Left/right maneuver samples: 346/472; reference yaw std 0.2491 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_roll | 0.0569 | 0.0154 | 0.0126 | 0.2620 | 1.0517 | 9 | 0.0703 | 0.0191 | 0.0128 | 0.2589 | 1.0453 |
| -1 × gyro_channel_yaw | 0.0140 | 0.0069 | 0.0012 | 0.2811 | 1.1287 | 10 | 0.0262 | 0.0130 | 0.0015 | 0.2778 | 1.1218 |
| -1 × gyro_channel_pitch | 0.0959 | 0.0993 | 0.0703 | 0.3601 | 1.4459 | 10 | 0.1201 | 0.1260 | 0.0720 | 0.3553 | 1.4346 |
| +1 × gyro_channel_yaw | -0.0140 | -0.0069 | -0.0012 | 0.2839 | 1.1397 | -10 | 0.0100 | 0.0049 | -0.0015 | 0.2789 | 1.1261 |
| -1 × gyro_channel_roll | -0.0569 | -0.0154 | -0.0126 | 0.2646 | 1.0625 | -10 | -0.0187 | -0.0051 | -0.0122 | 0.2602 | 1.0509 |
| +1 × gyro_channel_pitch | -0.0959 | -0.0993 | -0.0703 | 0.3756 | 1.5079 | -10 | -0.0328 | -0.0344 | -0.0677 | 0.3653 | 1.4751 |

Zero-lag selection: +1 × gyro_channel_roll; accepted: False.
Mounting yaw relative to deterministic leveled basis: 90.871°; det 1.00000000; orthogonality error 5.6e-16; excitation ratio 0.7673; accepted: False.
```text
[[-1.5207925e-02 -9.9988435e-01 -3.2431611e-05]
 [ 9.9988435e-01 -1.5207921e-02 -9.5517478e-05]
 [ 9.5013214e-05 -3.3880483e-05  9.9999999e-01]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | -0.0259 | -0.0473 | 0.0989 | 1.8763 | 2.1356 |
| lateral | 0.0451 | 0.0438 | -0.1442 | 2.1221 | 1.3636 |
| filtered_longitudinal | -0.0288 | -0.0488 | 0.0979 | 1.7626 | 2.0233 |
| filtered_lateral | 0.0538 | 0.0472 | -0.1425 | 1.9927 | 1.2956 |
| first-half fit / second-half longitudinal | -0.2050 | -0.3081 | -0.0881 | 2.0225 | 1.9699 |
| first-half fit / second-half lateral | -0.1807 | -0.1700 | 0.1297 | 2.3073 | 1.5206 |

Acceleration best lag (not applied): long=-7, lateral=-10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.9631 m/s²; norm distribution {'n': 1196, 'mean': 0.7576721714442257, 'median': 0.6053886445660854, 'std': 0.5945886895322349, 'p95': 1.759136403128631, 'maximum': 6.705848325138208}. This includes filter delay, not just vibration. Gravity mapped: [4.160285492240492e-20, 1.7346430210502392e-19, 9.806530716559344].
## S3C — Driver A

Phase 0 alignment: uncertain. Rows: 37183. Gravity mean: [2.8252158244358992e-05, -1.287954172605761e-05, 9.806586445418606] m/s²; std: [0.01029463144557725, 0.010025211558547178, 4.9965292253217144e-05].
Stationary: 2142 samples / 214.2 nominal seconds. Accel norm median: 9.8464; minus-gravity norm: 0.2027; plus-gravity norm: 19.6496 m/s².
Changed-row fractions: `{'accel': 0.9981442633532355, 'gyro': 0.9976332634070249, 'gravity': 0.9975525792049916, 'gps_position': 0.010488946264321446, 'vbox_yaw': 0.794873863697488, 'vbox_long': 0.44247216395029854, 'vbox_lat': 0.6228013554945941}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.

Independent stationary selection: both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples. Mounting rotation maximum between-window separation: 169.8722 degrees. Accepted gyro/acceleration windows: 3/0.

| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |
|---|---:|---:|---:|---:|---:|
| yaw_vs_negative_heading_derivative | 0.9304 | 0.9249 | 0.0006 | 0.0446 | 0.3722 |
| yaw_vs_positive_heading_derivative | -0.9304 | -0.9249 | 0.0006 | 0.2349 | 1.9591 |
| lateral_vs_speed_yaw | 0.9410 | 0.9546 | 0.0067 | 0.3139 | 0.3463 |
| longitudinal_g_scaled_vs_dvdt | 0.7984 | 0.7558 | 0.0044 | 0.4342 | 0.6201 |
| longitudinal_raw_vs_dvdt | 0.7984 | 0.0771 | 0.0005 | 0.6474 | 0.9248 |

### Rows 5100–6299 (510.0–629.9 s)

Left/right maneuver samples: 467/362; reference yaw std 0.1997 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9204 | 0.9267 | -0.0030 | 0.0800 | 0.4008 | -5 | 0.9987 | 1.0059 | -0.0032 | 0.0107 | 0.0533 |
| +1 × gyro_channel_roll | 0.5326 | 0.0720 | -0.0011 | 0.1868 | 0.9355 | -1 | 0.5326 | 0.0719 | -0.0012 | 0.1883 | 0.9355 |
| +1 × gyro_channel_yaw | 0.0872 | 0.0108 | -0.0026 | 0.1992 | 0.9977 | -7 | 0.1316 | 0.0163 | -0.0027 | 0.1997 | 0.9921 |
| -1 × gyro_channel_yaw | -0.0872 | -0.0108 | 0.0026 | 0.2033 | 1.0184 | 10 | 0.0252 | 0.0031 | 0.0024 | 0.2022 | 1.0044 |
| -1 × gyro_channel_roll | -0.5326 | -0.0720 | 0.0011 | 0.2153 | 1.0784 | -10 | -0.2805 | -0.0379 | 0.0009 | 0.2106 | 1.0463 |
| -1 × gyro_channel_pitch | -0.9204 | -0.9267 | 0.0030 | 0.3927 | 1.9669 | 10 | -0.4522 | -0.4554 | 0.0008 | 0.3443 | 1.7105 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: 47.882°; det 1.00000000; orthogonality error 2e-15; excitation ratio 0.2070; accepted: False.
```text
[[ 6.7066354e-01 -7.4176169e-01 -2.3844787e-05]
 [ 7.4176169e-01  6.7066354e-01  1.8443198e-05]
 [ 2.3113717e-06 -3.0056330e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.4355 | 0.7046 | 0.0973 | 0.9809 | 1.5111 |
| lateral | 0.7432 | 0.6918 | 0.1170 | 1.0047 | 0.6994 |
| filtered_longitudinal | 0.4741 | 0.7248 | 0.1054 | 0.8957 | 1.4027 |
| filtered_lateral | 0.7785 | 0.7028 | 0.1162 | 0.9155 | 0.6448 |
| first-half fit / second-half longitudinal | 0.3278 | 0.5177 | -0.0391 | 0.9808 | 1.5860 |
| first-half fit / second-half lateral | 0.5755 | 0.5063 | 0.0385 | 1.0425 | 0.8731 |

Acceleration best lag (not applied): long=-5, lateral=-5 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.5872 m/s²; norm distribution {'n': 1196, 'mean': 0.4918700485246469, 'median': 0.4263106390638658, 'std': 0.3206735545054936, 'p95': 1.1336498515844793, 'maximum': 2.214724764380142}. This includes filter delay, not just vibration. Gravity mapped: [-1.1005606715711139e-20, 1.82280111496916e-20, 9.80658658778908].

### Rows 16594–17793 (1659.4–1779.3 s)

Left/right maneuver samples: 191/257; reference yaw std 0.1840 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9444 | 0.9559 | 0.0024 | 0.0618 | 0.3360 | -5 | 0.9976 | 1.0092 | 0.0027 | 0.0134 | 0.0727 |
| +1 × gyro_channel_roll | 0.3036 | 0.0570 | -0.0001 | 0.1767 | 0.9605 | -3 | 0.3128 | 0.0589 | -0.0002 | 0.1766 | 0.9590 |
| +1 × gyro_channel_yaw | 0.1540 | 0.0295 | 0.0000 | 0.1821 | 0.9895 | -7 | 0.1830 | 0.0350 | 0.0000 | 0.1812 | 0.9844 |
| -1 × gyro_channel_yaw | -0.1540 | -0.0295 | -0.0000 | 0.1927 | 1.0475 | 10 | -0.0689 | -0.0133 | 0.0001 | 0.1901 | 1.0326 |
| -1 × gyro_channel_roll | -0.3036 | -0.0570 | 0.0001 | 0.1974 | 1.0728 | 10 | -0.1793 | -0.0337 | 0.0004 | 0.1936 | 1.0515 |
| -1 × gyro_channel_pitch | -0.9444 | -0.9559 | -0.0024 | 0.3652 | 1.9850 | 10 | -0.6163 | -0.6286 | -0.0012 | 0.3346 | 1.8175 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: -39.258°; det 1.00000000; orthogonality error 1.6e-15; excitation ratio 0.1053; accepted: False.
```text
[[ 7.7430798e-01  6.3280894e-01  5.6385137e-05]
 [-6.3280893e-01  7.7430798e-01 -4.4182571e-05]
 [-7.1618587e-05 -1.4701015e-06  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.0600 | 0.0670 | -0.0862 | 1.3751 | 1.4578 |
| lateral | 0.3516 | 0.3822 | -0.0111 | 1.3828 | 1.1943 |
| filtered_longitudinal | 0.0721 | 0.0730 | -0.0855 | 1.2815 | 1.3745 |
| filtered_lateral | 0.3794 | 0.3892 | -0.0102 | 1.2926 | 1.1328 |
| first-half fit / second-half longitudinal | 0.2773 | 0.2417 | -0.0397 | 1.1057 | 1.1305 |
| first-half fit / second-half lateral | 0.2952 | 0.3551 | 0.0471 | 1.5798 | 1.3198 |

Acceleration best lag (not applied): long=-6, lateral=-5 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.6880 m/s²; norm distribution {'n': 1196, 'mean': 0.5643886044585918, 'median': 0.49141745388806124, 'std': 0.39343215580166724, 'p95': 1.326202407239145, 'maximum': 2.811594232835338}. This includes filter delay, not just vibration. Gravity mapped: [3.2122849168435236e-20, 8.363333596339942e-20, 9.806579025160659].

### Rows 25388–26587 (2538.8–2658.7 s)

Left/right maneuver samples: 164/218; reference yaw std 0.1361 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.9641 | 0.9683 | 0.0008 | 0.0366 | 0.2688 | -5 | 0.9978 | 1.0068 | 0.0003 | 0.0090 | 0.0666 |
| +1 × gyro_channel_roll | 0.3032 | 0.0565 | -0.0002 | 0.1308 | 0.9611 | 4 | 0.3108 | 0.0584 | -0.0003 | 0.1301 | 0.9589 |
| +1 × gyro_channel_yaw | 0.0195 | 0.0045 | -0.0007 | 0.1393 | 1.0233 | -10 | 0.0504 | 0.0119 | -0.0007 | 0.1379 | 1.0162 |
| -1 × gyro_channel_yaw | -0.0195 | -0.0045 | 0.0007 | 0.1406 | 1.0327 | 10 | 0.0130 | 0.0031 | 0.0005 | 0.1391 | 1.0249 |
| -1 × gyro_channel_roll | -0.3032 | -0.0565 | 0.0002 | 0.1460 | 1.0727 | -10 | -0.2033 | -0.0382 | 0.0004 | 0.1432 | 1.0552 |
| -1 × gyro_channel_pitch | -0.9641 | -0.9683 | -0.0008 | 0.2707 | 1.9885 | 10 | -0.7233 | -0.7161 | -0.0017 | 0.2507 | 1.8480 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: True.
Mounting yaw relative to deterministic leveled basis: 130.615°; det 1.00000000; orthogonality error 1.3e-15; excitation ratio 0.2588; accepted: False.
```text
[[-6.5096720e-01 -7.5910586e-01 -4.4216268e-05]
 [ 7.5910586e-01 -6.5096720e-01  5.1807971e-06]
 [-3.2716114e-05 -3.0192299e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.5430 | 0.6435 | 0.1008 | 0.6517 | 1.0613 |
| lateral | 0.7613 | 0.7842 | -0.2847 | 0.7136 | 0.7719 |
| filtered_longitudinal | 0.6151 | 0.6615 | 0.0986 | 0.5546 | 0.9179 |
| filtered_lateral | 0.8262 | 0.8012 | -0.2846 | 0.6051 | 0.6669 |
| first-half fit / second-half longitudinal | 0.4781 | 0.5275 | 0.1635 | 0.6268 | 1.1048 |
| first-half fit / second-half lateral | 0.6607 | 0.6941 | -0.2826 | 0.6735 | 0.9560 |

Acceleration best lag (not applied): long=-5, lateral=-5 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 0.5006 m/s²; norm distribution {'n': 1196, 'mean': 0.40655162431758823, 'median': 0.3454863055994555, 'std': 0.292119583610254, 'p95': 0.9715973160163385, 'maximum': 2.3962478761387827}. This includes filter delay, not just vibration. Gravity mapped: [-1.1338743633344803e-20, 9.763215606189719e-20, 9.806584426384598].
## VTA2 — Driver E

Phase 0 alignment: approximate. Rows: 10991. Gravity mean: [5.102356473478297e-05, 2.1353834955873e-05, 9.806438240378492] m/s²; std: [0.03711386182429382, 0.04140096964262056, 0.0003678432654035491].
Stationary: 476 samples / 47.6 nominal seconds. Accel norm median: 9.8572; minus-gravity norm: 0.2654; plus-gravity norm: 19.6593 m/s².
Changed-row fractions: `{'accel': 0.9996360327570518, 'gyro': 0.9992720655141037, 'gravity': 0.9992720655141037, 'gps_position': 0.0929936305732484, 'vbox_yaw': 0.8121929026387625, 'vbox_long': 0.5057324840764331, 'vbox_lat': 0.6843494085532302}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.

Independent stationary selection: both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples. Mounting rotation maximum between-window separation: 57.2610 degrees. Accepted gyro/acceleration windows: 0/0.

| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |
|---|---:|---:|---:|---:|---:|
| yaw_vs_negative_heading_derivative | 0.9754 | 1.0520 | 0.0012 | 0.0197 | 0.2438 |
| yaw_vs_positive_heading_derivative | -0.9754 | -1.0520 | 0.0012 | 0.1672 | 2.0680 |
| lateral_vs_speed_yaw | 0.9173 | 0.9987 | -0.0214 | 0.2816 | 0.4347 |
| longitudinal_g_scaled_vs_dvdt | 0.9123 | 1.0539 | -0.0166 | 0.3054 | 0.4767 |
| longitudinal_raw_vs_dvdt | 0.9123 | 0.1075 | -0.0017 | 0.5727 | 0.8938 |

### Rows 300–1499 (30.0–149.9 s)

Left/right maneuver samples: 195/184; reference yaw std 0.0835 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_yaw | 0.0218 | 0.0300 | -0.0042 | 0.1402 | 1.6799 | -4 | 0.0347 | 0.0614 | -0.0045 | 0.1296 | 2.0015 |
| -1 × gyro_channel_yaw | -0.0218 | -0.0300 | 0.0042 | 0.1435 | 1.7201 | -10 | -0.0171 | -0.0301 | 0.0044 | 0.1324 | 2.0452 |
| +1 × gyro_channel_roll | 0.0108 | 0.0339 | -0.0055 | 0.2746 | 3.2910 | -1 | 0.0305 | 0.1245 | -0.0060 | 0.2699 | 4.1685 |
| -1 × gyro_channel_roll | -0.0108 | -0.0339 | 0.0055 | 0.2766 | 3.3148 | 10 | 0.0071 | 0.0290 | 0.0057 | 0.2719 | 4.1988 |
| +1 × gyro_channel_pitch | 0.1211 | 0.5922 | 0.0034 | 0.4066 | 4.8723 | -9 | 0.1436 | 0.9098 | 0.0026 | 0.4059 | 6.2698 |
| -1 × gyro_channel_pitch | -0.1211 | -0.5922 | -0.0034 | 0.4264 | 5.1099 | 8 | -0.0415 | -0.2625 | -0.0048 | 0.4172 | 6.4433 |

Zero-lag selection: +1 × gyro_channel_yaw; accepted: False.
Mounting yaw relative to deterministic leveled basis: 6.230°; det 1.00000000; orthogonality error 7.8e-16; excitation ratio 0.4549; accepted: False.
```text
[[ 9.9409499e-01 -1.0851338e-01  1.4097914e-05]
 [ 1.0851338e-01  9.9409497e-01 -1.8443984e-04]
 [ 5.9995247e-06  1.8488054e-04  9.9999998e-01]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.2711 | 0.7151 | 0.7764 | 1.7215 | 2.8165 |
| lateral | 0.1741 | 0.6796 | -0.7667 | 3.5840 | 3.9399 |
| filtered_longitudinal | 0.4342 | 0.7117 | 0.7770 | 1.1650 | 1.9218 |
| filtered_lateral | 0.4598 | 0.7237 | -0.7617 | 1.4489 | 1.6502 |
| first-half fit / second-half longitudinal | 0.2455 | 0.6995 | 0.7957 | 1.9079 | 3.0386 |
| first-half fit / second-half lateral | 0.0515 | 0.3037 | -0.9611 | 3.5108 | 6.2165 |

Acceleration best lag (not applied): long=-8, lateral=-8 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 3.4051 m/s²; norm distribution {'n': 1196, 'mean': 2.703329089651958, 'median': 2.1587825120634436, 'std': 2.0705138792649684, 'p95': 6.968882166335734, 'maximum': 15.79882953244508}. This includes filter delay, not just vibration. Gravity mapped: [-1.0349588341879522e-20, -1.90689742477846e-20, 9.806332417770694].

### Rows 5163–6362 (516.3–636.2 s)

Left/right maneuver samples: 168/113; reference yaw std 0.1020 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.2844 | 0.8601 | 0.0054 | 0.2962 | 2.9030 | -8 | 0.3585 | 1.0889 | 0.0031 | 0.2897 | 2.8373 |
| -1 × gyro_channel_yaw | 0.0119 | 0.0102 | 0.0034 | 0.1336 | 1.3096 | -10 | 0.0502 | 0.0429 | 0.0033 | 0.1312 | 1.2847 |
| +1 × gyro_channel_yaw | -0.0119 | -0.0102 | -0.0034 | 0.1358 | 1.3315 | 10 | 0.0290 | 0.0249 | -0.0038 | 0.1335 | 1.3075 |
| -1 × gyro_channel_roll | 0.0223 | 0.0396 | 0.0028 | 0.2061 | 2.0200 | -8 | 0.0483 | 0.0860 | 0.0018 | 0.2045 | 2.0026 |
| +1 × gyro_channel_roll | -0.0223 | -0.0396 | -0.0028 | 0.2104 | 2.0627 | 10 | -0.0129 | -0.0230 | -0.0029 | 0.2109 | 2.0652 |
| -1 × gyro_channel_pitch | -0.2844 | -0.8601 | -0.0054 | 0.3527 | 3.4566 | 10 | -0.1500 | -0.4562 | -0.0087 | 0.3421 | 3.3507 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: False.
Mounting yaw relative to deterministic leveled basis: -1.129°; det 1.00000000; orthogonality error 1.6e-15; excitation ratio 0.5340; accepted: False.
```text
[[ 9.9980571e-01  1.9711615e-02 -3.1330470e-05]
 [-1.9711617e-02  9.9980570e-01 -7.0344304e-05]
 [ 2.9937783e-05  7.0948211e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.2737 | 0.5185 | 0.4253 | 1.5316 | 1.9506 |
| lateral | 0.2514 | 0.7369 | -0.6332 | 2.6204 | 2.9334 |
| filtered_longitudinal | 0.4039 | 0.5328 | 0.4280 | 1.0793 | 1.3920 |
| filtered_lateral | 0.5746 | 0.7564 | -0.6341 | 1.1503 | 1.3144 |
| first-half fit / second-half longitudinal | 0.2839 | 0.4886 | 0.3335 | 1.7333 | 1.7665 |
| first-half fit / second-half lateral | 0.2646 | 0.6374 | -0.4760 | 2.0719 | 2.4214 |

Acceleration best lag (not applied): long=-8, lateral=-8 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 2.4983 m/s²; norm distribution {'n': 1196, 'mean': 1.9174193814779736, 'median': 1.5420848925316812, 'std': 1.6015611837968866, 'p95': 4.9587132412582156, 'maximum': 17.335190567664537}. This includes filter delay, not just vibration. Gravity mapped: [6.314791971876943e-20, -1.1531744207945555e-19, 9.80644886240906].

### Rows 9727–10926 (972.7–1092.6 s)

Left/right maneuver samples: 137/149; reference yaw std 0.1428 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.3621 | 0.8760 | 0.0012 | 0.3226 | 2.2585 | -7 | 0.4030 | 0.9727 | 0.0008 | 0.3162 | 2.2093 |
| +1 × gyro_channel_yaw | 0.0312 | 0.0161 | -0.0011 | 0.1595 | 1.1169 | 6 | 0.0427 | 0.0220 | -0.0010 | 0.1592 | 1.1122 |
| -1 × gyro_channel_yaw | -0.0312 | -0.0161 | 0.0011 | 0.1639 | 1.1474 | -2 | -0.0304 | -0.0157 | 0.0008 | 0.1644 | 1.1487 |
| -1 × gyro_channel_roll | 0.0063 | 0.0087 | 0.0006 | 0.2441 | 1.7090 | 9 | 0.0129 | 0.0177 | 0.0016 | 0.2434 | 1.7005 |
| +1 × gyro_channel_roll | -0.0063 | -0.0087 | -0.0006 | 0.2455 | 1.7188 | -5 | 0.0035 | 0.0049 | -0.0004 | 0.2450 | 1.7116 |
| -1 × gyro_channel_pitch | -0.3621 | -0.8760 | -0.0012 | 0.4202 | 2.9420 | 10 | -0.2261 | -0.5460 | 0.0034 | 0.4042 | 2.8246 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: False.
Mounting yaw relative to deterministic leveled basis: 56.132°; det 1.00000000; orthogonality error 1e-15; excitation ratio 0.5574; accepted: False.
```text
[[ 5.5728780e-01 -8.3031939e-01 -1.2706836e-04]
 [ 8.3031939e-01  5.5728781e-01 -4.7557482e-05]
 [ 1.1030155e-04 -7.9004123e-05  9.9999999e-01]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.2098 | 0.4062 | 0.0467 | 1.7576 | 1.9865 |
| lateral | 0.1639 | 0.5015 | -0.0662 | 2.6095 | 3.0596 |
| filtered_longitudinal | 0.3609 | 0.4074 | 0.0485 | 1.0574 | 1.2119 |
| filtered_lateral | 0.3663 | 0.4878 | -0.0694 | 1.1094 | 1.3417 |
| first-half fit / second-half longitudinal | 0.1132 | 0.2079 | -0.0211 | 1.8775 | 1.9938 |
| first-half fit / second-half lateral | 0.1854 | 0.4293 | -0.2027 | 2.5679 | 2.3518 |

Acceleration best lag (not applied): long=-9, lateral=-6 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 2.6220 m/s²; norm distribution {'n': 1196, 'mean': 1.9601832814300606, 'median': 1.4611240389704858, 'std': 1.7414979651140885, 'p95': 5.410413712329948, 'maximum': 11.458272560691766}. This includes filter delay, not just vibration. Gravity mapped: [-8.898391610472264e-20, 1.9866800046913735e-19, 9.806450256925643].
## VW11 — Driver E

Phase 0 alignment: approximate. Rows: 4909. Gravity mean: [-0.00077484212670605, -0.0005287838663678957, 9.806473905072316] m/s²; std: [0.032512721997226005, 0.03622180179122776, 0.0002257723620259046].
Stationary: 231 samples / 23.1 nominal seconds. Accel norm median: 9.8524; minus-gravity norm: 0.2068; plus-gravity norm: 19.6583 m/s².
Changed-row fractions: `{'accel': 1.0, 'gyro': 1.0, 'gravity': 1.0, 'gps_position': 0.009779951100244499, 'vbox_yaw': 0.7330888345558272, 'vbox_long': 0.4539527302363488, 'vbox_lat': 0.5731458842705787}`. Small change fractions indicate holding/quantization, not necessarily hardware update rates.

Independent stationary selection: both GPS speeds <0.3 m/s, gyro norm <0.05 rad/s for >=2s; acceleration is not used to select samples. Mounting rotation maximum between-window separation: 51.1319 degrees. Accepted gyro/acceleration windows: 0/0.

| Reference check | Signed correlation | Slope | Intercept | RMSE | NRMSE |
|---|---:|---:|---:|---:|---:|
| yaw_vs_negative_heading_derivative | 0.9890 | 1.0296 | 0.0003 | 0.0159 | 0.1571 |
| yaw_vs_positive_heading_derivative | -0.9890 | -1.0296 | 0.0003 | 0.2061 | 2.0377 |
| lateral_vs_speed_yaw | 0.9547 | 0.9432 | -0.0352 | 0.2834 | 0.3020 |
| longitudinal_g_scaled_vs_dvdt | 0.9322 | 1.0654 | -0.0009 | 0.3304 | 0.4189 |
| longitudinal_raw_vs_dvdt | 0.9322 | 0.1086 | -0.0001 | 0.7051 | 0.8939 |

### Rows 300–1499 (30.0–149.9 s)

Left/right maneuver samples: 132/105; reference yaw std 0.0789 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.2469 | 0.5479 | 0.0070 | 0.1733 | 2.1974 | -10 | 0.3423 | 0.7717 | 0.0029 | 0.1652 | 2.1304 |
| +1 × gyro_channel_yaw | 0.0703 | 0.0692 | 0.0007 | 0.1074 | 1.3624 | 9 | 0.0678 | 0.0680 | 0.0007 | 0.1067 | 1.3752 |
| +1 × gyro_channel_roll | 0.0030 | 0.0043 | -0.0013 | 0.1392 | 1.7648 | -3 | 0.0313 | 0.0450 | -0.0017 | 0.1345 | 1.7342 |
| -1 × gyro_channel_yaw | -0.0703 | -0.0692 | -0.0007 | 0.1156 | 1.4657 | -5 | -0.0518 | -0.0519 | -0.0005 | 0.1134 | 1.4627 |
| -1 × gyro_channel_roll | -0.0030 | -0.0043 | 0.0013 | 0.1393 | 1.7665 | 10 | 0.0189 | 0.0279 | 0.0018 | 0.1372 | 1.7684 |
| -1 × gyro_channel_pitch | -0.2469 | -0.5479 | -0.0070 | 0.2109 | 2.6750 | 9 | -0.1839 | -0.4150 | -0.0105 | 0.2061 | 2.6573 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: False.
Mounting yaw relative to deterministic leveled basis: -3.832°; det 1.00000000; orthogonality error 1.1e-15; excitation ratio 0.3845; accepted: False.
```text
[[ 9.9776475e-01  6.6824413e-02 -1.3701250e-05]
 [-6.6824414e-02  9.9776475e-01 -5.6936744e-05]
 [ 9.8658601e-06  5.7725054e-05  1.0000000e+00]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.3742 | 0.4449 | 0.1463 | 1.1312 | 1.2483 |
| lateral | 0.2534 | 0.7874 | -0.0928 | 1.5581 | 3.0207 |
| filtered_longitudinal | 0.5022 | 0.4620 | 0.1446 | 0.8685 | 0.9784 |
| filtered_lateral | 0.4831 | 0.8462 | -0.0969 | 0.7793 | 1.5560 |
| first-half fit / second-half longitudinal | 0.3503 | 0.4487 | 0.0103 | 1.2075 | 1.3205 |
| first-half fit / second-half lateral | 0.2775 | 0.9212 | -0.0389 | 1.9391 | 3.1912 |

Acceleration best lag (not applied): long=-10, lateral=-10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 1.5193 m/s²; norm distribution {'n': 1196, 'mean': 0.9178514488923236, 'median': 0.45967108601107676, 'std': 1.210740504265431, 'p95': 3.1038531483489527, 'maximum': 13.46684348851115}. This includes filter delay, not just vibration. Gravity mapped: [-1.3424305720809783e-20, 4.957737601977341e-21, 9.806544933482526].

### Rows 1636–2835 (163.6–283.5 s)

Left/right maneuver samples: 217/99; reference yaw std 0.1198 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| +1 × gyro_channel_pitch | 0.3380 | 0.7757 | 0.0022 | 0.2602 | 2.1719 | -10 | 0.4523 | 1.0293 | 0.0020 | 0.2452 | 2.0300 |
| -1 × gyro_channel_roll | 0.0794 | 0.1189 | 0.0044 | 0.2077 | 1.7336 | -7 | 0.1145 | 0.1700 | 0.0045 | 0.2045 | 1.6931 |
| +1 × gyro_channel_yaw | 0.0122 | 0.0129 | 0.0061 | 0.1736 | 1.4489 | -4 | 0.0197 | 0.0207 | 0.0063 | 0.1736 | 1.4376 |
| -1 × gyro_channel_yaw | -0.0122 | -0.0129 | -0.0061 | 0.1760 | 1.4689 | 4 | -0.0066 | -0.0069 | -0.0055 | 0.1760 | 1.4567 |
| +1 × gyro_channel_roll | -0.0794 | -0.1189 | -0.0044 | 0.2237 | 1.8672 | 10 | -0.0070 | -0.0105 | -0.0050 | 0.2180 | 1.8046 |
| -1 × gyro_channel_pitch | -0.3380 | -0.7757 | -0.0022 | 0.3351 | 2.7975 | 10 | -0.1666 | -0.3818 | -0.0043 | 0.3200 | 2.6494 |

Zero-lag selection: +1 × gyro_channel_pitch; accepted: False.
Mounting yaw relative to deterministic leveled basis: 18.514°; det 1.00000000; orthogonality error 8.9e-16; excitation ratio 0.5884; accepted: False.
```text
[[ 9.4824526e-01 -3.1753884e-01  1.0392979e-04]
 [ 3.1753883e-01  9.4824526e-01  1.3543261e-04]
 [-1.4155605e-04 -9.5421591e-05  9.9999999e-01]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.2071 | 0.3946 | 0.0930 | 1.9918 | 1.9605 |
| lateral | 0.2890 | 0.6024 | -0.0126 | 2.0202 | 2.0349 |
| filtered_longitudinal | 0.3920 | 0.3982 | 0.0944 | 1.1211 | 1.1130 |
| filtered_lateral | 0.4572 | 0.6146 | -0.0101 | 1.2354 | 1.2560 |
| first-half fit / second-half longitudinal | 0.1570 | 0.3228 | 0.0766 | 2.3501 | 2.1422 |
| first-half fit / second-half lateral | 0.2355 | 0.5014 | 0.1059 | 2.1653 | 2.1286 |

Acceleration best lag (not applied): long=-10, lateral=-10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 2.2490 m/s²; norm distribution {'n': 1196, 'mean': 1.762172823214781, 'median': 1.418845688450924, 'std': 1.3974668494637814, 'p95': 4.580011904353193, 'maximum': 12.070978036725657}. This includes filter delay, not just vibration. Gravity mapped: [-3.7535947766490047e-19, -1.9375887797543574e-19, 9.806480809563736].

### Rows 3272–4471 (327.2–447.1 s)

Left/right maneuver samples: 192/144; reference yaw std 0.0872 rad/s.
| Channel/sign | Zero corr | Slope | Intercept rad/s | RMSE rad/s | NRMSE | Best lag rows | Lag corr | Lag slope | Lag intercept | Lag RMSE | Lag NRMSE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| -1 × gyro_channel_yaw | 0.0114 | 0.0198 | -0.0120 | 0.1750 | 2.0063 | 9 | 0.0699 | 0.1344 | -0.0130 | 0.1676 | 2.1192 |
| +1 × gyro_channel_yaw | -0.0114 | -0.0198 | 0.0120 | 0.1762 | 2.0204 | -4 | 0.0029 | 0.0055 | 0.0103 | 0.1709 | 2.1606 |
| -1 × gyro_channel_roll | 0.0307 | 0.0789 | 0.0083 | 0.2380 | 2.7298 | -1 | 0.0416 | 0.1184 | 0.0069 | 0.2358 | 2.9806 |
| +1 × gyro_channel_roll | -0.0307 | -0.0789 | -0.0083 | 0.2433 | 2.7899 | 10 | -0.0011 | -0.0032 | -0.0079 | 0.2394 | 3.0267 |
| +1 × gyro_channel_pitch | 0.1333 | 0.5110 | 0.0193 | 0.3344 | 3.8347 | -10 | 0.1985 | 0.8428 | 0.0140 | 0.3297 | 4.1684 |
| -1 × gyro_channel_pitch | -0.1333 | -0.5110 | -0.0193 | 0.3573 | 4.0974 | 10 | -0.0487 | -0.2068 | -0.0192 | 0.3500 | 4.4252 |

Zero-lag selection: -1 × gyro_channel_yaw; accepted: False.
Mounting yaw relative to deterministic leveled basis: -32.618°; det 1.00000000; orthogonality error 6.7e-16; excitation ratio 0.4176; accepted: False.
```text
[[ 8.4228500e-01  5.3903242e-01  1.6234124e-04]
 [-5.3903244e-01  8.4228500e-01  1.2129401e-04]
 [-7.1356186e-05 -1.8967132e-04  9.9999998e-01]]
```

| Acceleration comparison | Corr | Slope | Intercept m/s² | RMSE m/s² | NRMSE |
|---|---:|---:|---:|---:|---:|
| longitudinal | 0.1865 | 0.3699 | -0.0251 | 1.6160 | 2.0577 |
| lateral | 0.1509 | 0.5190 | 0.1299 | 2.8985 | 3.4374 |
| filtered_longitudinal | 0.3180 | 0.3898 | -0.0319 | 1.0276 | 1.3291 |
| filtered_lateral | 0.3372 | 0.5519 | 0.1258 | 1.3176 | 1.6095 |
| first-half fit / second-half longitudinal | 0.0728 | 0.2222 | -0.0572 | 1.5329 | 3.2100 |
| first-half fit / second-half lateral | 0.1505 | 0.6394 | 0.1389 | 3.2713 | 4.2164 |

Acceleration best lag (not applied): long=-10, lateral=-10 rows. Filter: trailing 5-row mean on both streams, nominal 0.2 s group delay.
Deviation from trailing mean: RMS 2.8366 m/s²; norm distribution {'n': 1196, 'mean': 2.040401775825629, 'median': 1.510502133446463, 'std': 1.9705498129870538, 'p95': 5.961162395583199, 'maximum': 15.068728372132755}. This includes filter delay, not just vibration. Gravity mapped: [2.579989585077886e-19, 4.1031726597005117e-19, 9.806437868026741].
