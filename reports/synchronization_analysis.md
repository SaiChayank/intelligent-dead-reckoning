# Phase 0 - Synchronization analysis

## Method and interpretation

**Stated by dataset documentation.** README_1.pdf page 3 says simultaneously collected pairs were manually synchronized. **Confirmed from data.** All available synchronized pairs in both layouts are evaluated here. Reference coordinates outside valid bounds or equal to (0,0) are excluded, with coverage reported. Nonfinite speed is excluded. Zero speed is retained. No satellite-count threshold is imposed because its encoding is unresolved.

Smartphone DATE and VBOX time-of-day are independently rebased to first valid time. Absolute wall-clock offsets therefore do not drive elapsed-time comparisons. Duplicate VBOX times are averaged for interpolation; backward reference clocks are rejected. Interpolation never extrapolates or bridges gaps exceeding max(0.25 s, 2.5 times median positive VBOX dt). Invalid coordinate spans are excluded by the same rule.

Constant-lag search spans -10 to +10 s, coarse 0.5 s then local 0.1 s refinement. Positive lag maps smartphone elapsed t to VBOX t+lag. A fixed deterministic stride caps coarse-search phone samples at approximately 6,000; final metrics use all rows. Candidates require >=80% coordinate coverage and >=30 valid points. The score combines normalized median position separation and speed MAE with equal weights. Position-only and speed-only optima and disjoint-third optima expose ambiguity. These fitted results are descriptive, not approved corrections.

Affine endpoint mapping is tested only when durations differ by more than 0.25 s. It assumes endpoint correspondence, which is not established. A piecewise hypothesis subtracts excess smartphone DATE gaps using smartphone timestamps alone; it assumes clock discontinuities instead of physical data loss. Neither hypothesis changes raw or processed data.

## Pair results

**Confirmed from data** for metrics. **Inferred with supporting evidence** for approximate/uncertain/unusable classifications. No pair is declared exact solely because row counts match.

| Pair | S / V rows | Duration difference (s) | Same-row position median / mean / p95 / max (m) | Same-row speed MAE (m/s) | Best joint lag (s) | Best-lag position median / mean / p95 / max (m) | Class |
| --- | --- | --- | --- | --- | --- | --- | --- |
| categorized/m | 105974 / 105974 | 2.323 | 21.782 / 29.923 / 87.236 / 168.906 | 1.173 | -3.500 | 18.361 / 22.812 / 60.267 / 121.687 | approximate |
| categorized/s1 | 51746 / 51746 | -0.001 | 24.415 / 32.523 / 90.982 / 163.355 | 1.718 | -4.100 | 13.084 / 17.467 / 47.235 / 102.611 | uncertain |
| categorized/s2 | 93876 / 93876 | 1.108 | 22.666 / 33.471 / 101.218 / 246.896 | 1.608 | 3.400 | 13.321 / 18.946 / 54.826 / 141.584 | approximate |
| categorized/s3a | 24621 / 24621 | 0.000 | 109.320 / 115.526 / 250.821 / 412.158 | 2.813 | -10.000 | 19.651 / 26.424 / 72.826 / 154.011 | uncertain |
| categorized/s3b | 6813 / 6813 | 4.328 | 12.209 / 16.504 / 47.301 / 98.261 | 1.429 | -3.600 | 11.310 / 14.032 / 35.839 / 60.973 | uncertain |
| categorized/s3c | 37183 / 37183 | -0.001 | 34.073 / 49.391 / 151.586 / 279.408 | 1.488 | -4.200 | 21.365 / 28.938 / 81.979 / 144.163 | uncertain |
| categorized/s4 | 94600 / 94600 | 313.205 | 613.686 / 1,299.668 / 5,160.266 / 6,437.411 | 4.480 | -1.900 | 16.202 / 23.628 / 69.116 / 162.090 | unusable |
| categorized/vfa01 | 11486 / 11535 | -4.900 | 32.504 / 37.868 / 93.129 / 136.272 | 0.953 | 0.800 | 32.420 / 37.081 / 89.276 / 124.822 | unusable |
| categorized/vfa02 | 67523 / 67755 | -23.199 | 95.519 / 102.020 / 219.183 / 270.802 | 0.775 | 4.000 | 51.845 / 55.100 / 114.707 / 168.510 | unusable |
| categorized/vta10 | 1502 / 1502 | 0.000 | 99.460 / 106.401 / 222.890 / 271.396 | 1.625 | -4.800 | 52.223 / 56.430 / 119.139 / 158.636 | uncertain |
| categorized/vta11 | 510 / 510 | -0.000 | 44.841 / 56.024 / 166.118 / 223.912 | 2.921 | -4.900 | 26.293 / 31.007 / 82.528 / 122.905 | uncertain |
| categorized/vta12 | 610 / 610 | -0.000 | 76.638 / 79.314 / 162.795 / 200.640 | 2.383 | -6.400 | 40.225 / 48.545 / 115.828 / 144.607 | uncertain |
| categorized/vta13 | 404 / 404 | 0.001 | 95.484 / 101.830 / 205.575 / 242.769 | 1.632 | -2.900 | 50.105 / 58.040 / 134.040 / 161.573 | uncertain |
| categorized/vta14 | 2885 / 2885 | 0.000 | 76.852 / 78.504 / 162.682 / 209.192 | 0.895 | -4.400 | 41.637 / 42.498 / 85.073 / 112.703 | uncertain |
| categorized/vta15 | 833 / 833 | 0.000 | 43.128 / 51.762 / 119.111 / 136.169 | 0.480 | -0.400 | 43.288 / 48.929 / 110.753 / 127.714 | uncertain |
| categorized/vta16 | 11335 / 11335 | -0.000 | 27.810 / 39.142 / 111.034 / 190.079 | 1.086 | -3.000 | 22.441 / 27.491 / 69.845 / 114.226 | approximate |
| categorized/vta17 | 4519 / 4519 | 1.186 | 26.557 / 33.247 / 86.928 / 119.337 | 1.706 | -4.200 | 17.450 / 20.429 / 49.783 / 71.957 | uncertain |
| categorized/vta19 | 292 / 292 | 0.001 | 23.801 / 38.716 / 98.792 / 110.048 | 2.860 | -3.700 | 19.274 / 22.293 / 55.444 / 74.251 | uncertain |
| categorized/vta1a | 25676 / 25676 | -0.001 | 234.612 / 224.105 / 333.508 / 407.719 | 3.334 | -10.000 | 72.327 / 69.606 / 105.633 / 140.575 | unusable |
| categorized/vta1b | 954 / 953 | 0.100 | 4.846 / 5.631 / 12.699 / 15.873 | 0.411 | -0.300 | 4.411 / 4.623 / 8.706 / 10.748 | unusable |
| categorized/vta2 | 10991 / 10991 | -0.002 | 3.408 / 3.840 / 8.752 / 14.770 | 0.282 | 0.200 | 3.129 / 3.457 / 7.612 / 17.790 | approximate |
| categorized/vta20 | 3223 / 3223 | 0.000 | 5.271 / 35.502 / 205.156 / 284.101 | 1.895 | 10.000 | 5.193 / 20.066 / 117.067 / 187.898 | uncertain |
| categorized/vta21 | 2079 / 2079 | -0.000 | 61.367 / 70.356 / 159.775 / 308.195 | 2.555 | 5.100 | 28.823 / 43.488 / 88.880 / 391.532 | uncertain |
| categorized/vta22 | 1564 / 1564 | 0.001 | 22.690 / 24.233 / 51.045 / 69.097 | 1.126 | -0.200 | 22.715 / 24.335 / 51.525 / 72.036 | uncertain |
| categorized/vta23 | 1110 / 1110 | -0.000 | 23.328 / 29.700 / 73.562 / 98.192 | 1.114 | -0.700 | 20.978 / 26.335 / 65.826 / 88.231 | uncertain |
| categorized/vta24 | 1171 / 1171 | 0.001 | 6.921 / 22.893 / 85.584 / 114.977 | 1.762 | -3.700 | 6.020 / 14.792 / 50.710 / 70.295 | uncertain |
| categorized/vta25 | 646 / 646 | -0.002 | 5.567 / 11.375 / 48.332 / 88.619 | 3.158 | -6.600 | 2.101 / 7.120 / 39.504 / 67.281 | uncertain |
| categorized/vta26 | 1935 / 1935 | -0.000 | 5.406 / 15.521 / 63.366 / 102.886 | 0.843 | -2.800 | 5.242 / 12.564 / 44.443 / 72.640 | approximate |
| categorized/vta27 | 2540 / 2540 | 0.000 | 396.148 / 353.237 / 507.283 / 562.404 | 3.256 | -10.000 | 269.909 / 244.936 / 362.320 / 406.776 | unusable |
| categorized/vta28 | 4210 / 4210 | 0.000 | 39.072 / 47.556 / 115.973 / 175.206 | 2.518 | -5.700 | 18.175 / 22.341 / 57.997 / 92.935 | uncertain |
| categorized/vta29 | 23705 / 23705 | -0.622 | 92.177 / 98.837 / 219.549 / 370.607 | 2.974 | -9.000 | 20.381 / 25.516 / 67.371 / 131.994 | uncertain |
| categorized/vta3 | 645 / 645 | -0.000 | 90.248 / 89.509 / 174.514 / 214.390 | 7.494 | -10.000 | 50.105 / 52.637 / 128.282 / 131.614 | uncertain |
| categorized/vta30 | 17136 / 17136 | 0.000 | 11.074 / 21.485 / 73.741 / 140.468 | 0.959 | -1.400 | 10.694 / 20.631 / 68.858 / 138.701 | approximate |
| categorized/vta4 | 1789 / 1789 | -0.001 | 29.021 / 34.941 / 80.633 / 104.070 | 1.107 | -4.300 | 23.590 / 28.277 / 65.286 / 77.483 | uncertain |
| categorized/vta5 | 307 / 307 | 0.000 | 44.034 / 47.020 / 96.029 / 109.949 | 1.542 | -4.700 | 25.867 / 26.710 / 53.013 / 62.521 | uncertain |
| categorized/vta6 | 1376 / 1376 | 0.000 | 46.123 / 56.571 / 134.869 / 191.259 | 1.021 | -2.100 | 40.494 / 43.234 / 91.668 / 137.081 | uncertain |
| categorized/vta7 | 840 / 840 | -0.000 | 47.625 / 64.107 / 179.253 / 226.938 | 2.388 | -2.600 | 32.243 / 42.341 / 111.232 / 157.237 | uncertain |
| categorized/vta8 | 3676 / 3676 | 0.000 | 22.177 / 35.978 / 112.923 / 169.475 | 1.356 | -3.900 | 14.886 / 21.956 / 65.584 / 100.922 | approximate |
| categorized/vta9 | 156 / 156 | -0.002 | 71.126 / 73.097 / 144.587 / 158.389 | 3.086 | -3.000 | 38.716 / 40.890 / 86.904 / 98.782 | uncertain |
| categorized/vtb1 | 32459 / 32459 | -0.001 | 54.320 / 322.643 / 2,491.759 / 4,280.256 | 2.797 | -4.200 | 38.199 / 513.367 / 4,925.731 / 4,929.638 | uncertain |
| categorized/vtb10 | 196 / 195 | 0.100 | 26.330 / 36.292 / 91.426 / 102.014 | 1.798 | -2.500 | 22.073 / 24.433 / 49.815 / 54.633 | unusable |
| categorized/vtb11 | 361 / 361 | 0.000 | 82.435 / 83.324 / 161.111 / 174.033 | 0.693 | -5.000 | 42.285 / 44.290 / 91.879 / 102.912 | uncertain |
| categorized/vtb12 | 447 / 447 | 0.001 | 40.803 / 47.769 / 120.545 / 153.062 | 1.694 | -1.900 | 25.425 / 33.634 / 88.928 / 126.019 | uncertain |
| categorized/vtb2 | 5712 / 5712 | 0.000 | 19.685 / 29.269 / 84.979 / 131.078 | 1.374 | -4.300 | 14.192 / 19.594 / 54.307 / 86.134 | approximate |
| categorized/vtb3 | 8240 / 8240 | -0.000 | 13.386 / 40.904 / 465.415 / 465.668 | 0.424 | -6.200 | 11.764 / 38.640 / 465.382 / 465.574 | uncertain |
| categorized/vtb4 | 556 / 556 | 0.000 | 12.572 / 18.403 / 50.545 / 72.236 | 1.025 | -1.500 | 11.751 / 15.975 / 37.886 / 61.328 | uncertain |
| categorized/vtb5 | 64388 / 64388 | 0.001 | 513.108 / 529.187 / 885.460 / 1,065.330 | 3.588 | -10.000 | 355.408 / 360.734 / 622.702 / 766.639 | unusable |
| categorized/vtb6 | 498 / 498 | -0.000 | 69.226 / 73.142 / 147.030 / 172.225 | 0.768 | -2.300 | 38.202 / 47.232 / 107.088 / 126.889 | uncertain |
| categorized/vtb7 | 461 / 461 | 0.001 | 64.971 / 69.867 / 147.359 / 161.356 | 1.620 | -6.500 | 34.455 / 42.137 / 102.110 / 113.583 | uncertain |
| categorized/vtb8 | 668 / 668 | 0.000 | 78.467 / 81.891 / 162.510 / 178.517 | 0.520 | -2.100 | 42.709 / 54.203 / 121.390 / 136.712 | uncertain |
| categorized/vtb9 | 452 / 452 | -0.001 | 93.201 / 93.862 / 179.643 / 209.061 | 1.178 | -6.500 | 46.724 / 58.814 / 132.986 / 158.488 | uncertain |
| categorized/vw1 | 20476 / 20475 | 0.100 | 1.713 / 1.706 / 2.241 / 2.772 | 0.011 | 9.500 | 1.711 / 1.704 / 2.241 / 2.772 | unusable |
| categorized/vw10 | 652 / 652 | 0.000 | 44.110 / 50.511 / 111.680 / 125.984 | 1.813 | -5.400 | 22.823 / 26.015 / 59.570 / 72.082 | uncertain |
| categorized/vw11 | 4909 / 4909 | -0.000 | 21.571 / 37.217 / 129.143 / 192.127 | 1.454 | -3.600 | 18.870 / 27.710 / 84.204 / 140.813 | approximate |
| categorized/vw12 | 918 / 918 | -0.000 | 55.236 / 56.184 / 110.016 / 132.854 | 0.360 | -0.000 | 55.236 / 56.183 / 110.016 / 132.854 | uncertain |
| categorized/vw13 | 284 / 284 | -0.000 | 89.153 / 94.321 / 203.710 / 228.058 | 1.059 | -5.500 | 62.974 / 77.830 / 167.846 / 180.910 | uncertain |
| categorized/vw14a | 3138 / 3138 | -0.000 | 56.391 / 57.736 / 115.970 / 143.679 | 0.673 | 0.500 | 56.457 / 56.984 / 109.628 / 133.301 | uncertain |
| categorized/vw14b | 19588 / 19588 | 0.001 | 43.172 / 47.734 / 105.433 / 157.617 | 0.553 | -0.600 | 43.186 / 47.385 / 102.410 / 145.394 | uncertain |
| categorized/vw14c | 15826 / 15826 | 0.000 | 15.562 / 31.356 / 117.556 / 184.874 | 1.105 | -2.000 | 13.843 / 25.030 / 83.991 / 131.422 | approximate |
| categorized/vw15 | 1380 / 1391 | -1.100 | 3.591 / 4.240 / 6.891 / 7.378 | 0.023 | -10.000 | 3.474 / 4.021 / 6.051 / 6.561 | unusable |
| categorized/vw16a | 5879 / 5879 | -0.001 | 213.957 / 203.641 / 360.979 / 419.873 | 3.159 | -10.000 | 54.397 / 63.582 / 156.852 / 200.661 | unusable |
| categorized/vw16b | 1126 / 1126 | 0.001 | 68.542 / 73.408 / 168.943 / 206.585 | 2.263 | -3.700 | 34.932 / 39.022 / 89.815 / 119.204 | uncertain |
| categorized/vw17 | 329 / 329 | 0.001 | 43.559 / 50.106 / 112.988 / 127.111 | 1.842 | -4.600 | 37.076 / 44.843 / 103.355 / 112.078 | uncertain |
| categorized/vw2 | 52713 / 52712 | 0.099 | 40.400 / 55.011 / 145.700 / 218.826 | 0.861 | -2.500 | 37.624 / 42.411 / 97.525 / 141.205 | unusable |
| categorized/vw3 | 3861 / 3861 | 0.001 | 46.578 / 56.435 / 137.452 / 181.543 | 1.961 | -5.000 | 25.528 / 30.204 / 73.699 / 106.079 | uncertain |
| categorized/vw4 | 126526 / 126527 | -0.100 | 51.388 / 68.562 / 197.860 / 305.834 | 1.435 | -3.800 | 29.877 / 38.668 / 105.989 / 169.389 | unusable |
| categorized/vw5 | 1012 / 1012 | 0.001 | 13.398 / 17.553 / 42.708 / 58.907 | 1.482 | -2.200 | 13.294 / 15.115 / 33.757 / 44.473 | uncertain |
| categorized/vw6 | 1281 / 1281 | 0.000 | 45.072 / 46.277 / 89.285 / 106.092 | 1.694 | -4.100 | 17.770 / 21.028 / 50.720 / 64.068 | uncertain |
| categorized/vw7 | 1602 / 1602 | -0.001 | 73.447 / 79.137 / 144.200 / 178.100 | 3.343 | -10.000 | 26.882 / 29.488 / 68.525 / 105.827 | uncertain |
| categorized/vw8 | 1529 / 1529 | -0.000 | 21.951 / 26.617 / 68.233 / 102.060 | 2.463 | -3.700 | 16.694 / 21.811 / 62.952 / 85.437 | uncertain |
| categorized/vw9 | 552 / 553 | -0.099 | 29.546 / 30.517 / 64.756 / 79.844 | 2.152 | -5.800 | 18.157 / 22.082 / 50.273 / 67.084 | unusable |
| categorized/y1 | 70285 / 70285 | -275.482 | 651.624 / 657.072 / 1,242.412 / 1,533.026 | 5.433 | 1.600 | 16.188 / 21.334 / 56.397 / 145.917 | unusable |
| uncategorized/m | 105974 / 105974 | 2.323 | 21.782 / 29.923 / 87.236 / 168.906 | 1.173 | -3.500 | 18.361 / 22.812 / 60.267 / 121.687 | approximate |
| uncategorized/s1 | 51746 / 51746 | -0.001 | 24.415 / 32.523 / 90.982 / 163.355 | 1.718 | -4.100 | 13.084 / 17.467 / 47.235 / 102.611 | uncertain |
| uncategorized/s2 | 93876 / 93876 | 1.108 | 22.666 / 33.471 / 101.218 / 246.896 | 1.608 | 3.400 | 13.321 / 18.946 / 54.826 / 141.584 | approximate |
| uncategorized/s3a | 24621 / 24621 | 0.000 | 109.320 / 115.526 / 250.821 / 412.158 | 2.813 | -10.000 | 19.651 / 26.424 / 72.826 / 154.011 | uncertain |
| uncategorized/s3b | 6813 / 6813 | 4.328 | 12.209 / 16.504 / 47.301 / 98.261 | 1.429 | -3.600 | 11.310 / 14.032 / 35.839 / 60.973 | uncertain |
| uncategorized/s3c | 37183 / 37183 | -0.001 | 34.073 / 49.391 / 151.586 / 279.408 | 1.488 | -4.200 | 21.365 / 28.938 / 81.979 / 144.163 | uncertain |
| uncategorized/s4 | 94600 / 94600 | 313.205 | 613.686 / 1,299.668 / 5,160.266 / 6,437.411 | 4.480 | -1.900 | 16.202 / 23.628 / 69.116 / 162.090 | unusable |
| uncategorized/vfa01 | 11486 / 11535 | -4.900 | 32.504 / 37.868 / 93.129 / 136.272 | 0.953 | 0.800 | 32.420 / 37.081 / 89.276 / 124.822 | unusable |
| uncategorized/vfa02 | 67523 / 67755 | -23.199 | 95.519 / 102.020 / 219.183 / 270.802 | 0.775 | 4.000 | 51.845 / 55.100 / 114.707 / 168.510 | unusable |
| uncategorized/vta10 | 1502 / 1502 | 0.000 | 99.460 / 106.401 / 222.890 / 271.396 | 1.625 | -4.800 | 52.223 / 56.430 / 119.139 / 158.636 | uncertain |
| uncategorized/vta11 | 510 / 510 | -0.000 | 44.841 / 56.024 / 166.118 / 223.912 | 2.921 | -4.900 | 26.293 / 31.007 / 82.528 / 122.905 | uncertain |
| uncategorized/vta12 | 610 / 610 | -0.000 | 76.638 / 79.314 / 162.795 / 200.640 | 2.383 | -6.400 | 40.225 / 48.545 / 115.828 / 144.607 | uncertain |
| uncategorized/vta13 | 404 / 404 | 0.001 | 95.484 / 101.830 / 205.575 / 242.769 | 1.632 | -2.900 | 50.105 / 58.040 / 134.040 / 161.573 | uncertain |
| uncategorized/vta14 | 2885 / 2885 | 0.000 | 76.852 / 78.504 / 162.682 / 209.192 | 0.895 | -4.400 | 41.637 / 42.498 / 85.073 / 112.703 | uncertain |
| uncategorized/vta15 | 833 / 833 | 0.000 | 43.128 / 51.762 / 119.111 / 136.169 | 0.480 | -0.400 | 43.288 / 48.929 / 110.753 / 127.714 | uncertain |
| uncategorized/vta16 | 11335 / 11335 | -0.000 | 27.810 / 39.142 / 111.034 / 190.079 | 1.086 | -3.000 | 22.441 / 27.491 / 69.845 / 114.226 | approximate |
| uncategorized/vta17 | 4519 / 4519 | 1.186 | 26.557 / 33.247 / 86.928 / 119.337 | 1.706 | -4.200 | 17.450 / 20.429 / 49.783 / 71.957 | uncertain |
| uncategorized/vta19 | 292 / 292 | 0.001 | 23.801 / 38.716 / 98.792 / 110.048 | 2.860 | -3.700 | 19.274 / 22.293 / 55.444 / 74.251 | uncertain |
| uncategorized/vta1a | 25676 / 25676 | -0.001 | 234.612 / 224.105 / 333.508 / 407.719 | 3.334 | -10.000 | 72.327 / 69.606 / 105.633 / 140.575 | unusable |
| uncategorized/vta1b | 954 / 953 | 0.100 | 4.846 / 5.631 / 12.699 / 15.873 | 0.411 | -0.300 | 4.411 / 4.623 / 8.706 / 10.748 | unusable |
| uncategorized/vta2 | 10991 / 10991 | -0.002 | 3.408 / 3.840 / 8.752 / 14.770 | 0.282 | 0.200 | 3.129 / 3.457 / 7.612 / 17.790 | approximate |
| uncategorized/vta20 | 3223 / 3223 | 0.000 | 5.271 / 35.502 / 205.156 / 284.101 | 1.895 | 10.000 | 5.193 / 20.066 / 117.067 / 187.898 | uncertain |
| uncategorized/vta21 | 2079 / 2079 | -0.000 | 61.367 / 70.356 / 159.775 / 308.195 | 2.555 | 5.100 | 28.823 / 43.488 / 88.880 / 391.532 | uncertain |
| uncategorized/vta22 | 1564 / 1564 | 0.001 | 22.690 / 24.233 / 51.045 / 69.097 | 1.126 | -0.200 | 22.715 / 24.335 / 51.525 / 72.036 | uncertain |
| uncategorized/vta23 | 1110 / 1110 | -0.000 | 23.328 / 29.700 / 73.562 / 98.192 | 1.114 | -0.700 | 20.978 / 26.335 / 65.826 / 88.231 | uncertain |
| uncategorized/vta24 | 1171 / 1171 | 0.001 | 6.921 / 22.893 / 85.584 / 114.977 | 1.762 | -3.700 | 6.020 / 14.792 / 50.710 / 70.295 | uncertain |
| uncategorized/vta25 | 646 / 646 | -0.002 | 5.567 / 11.375 / 48.332 / 88.619 | 3.158 | -6.600 | 2.101 / 7.120 / 39.504 / 67.281 | uncertain |
| uncategorized/vta26 | 1935 / 1935 | -0.000 | 5.406 / 15.521 / 63.366 / 102.886 | 0.843 | -2.800 | 5.242 / 12.564 / 44.443 / 72.640 | approximate |
| uncategorized/vta27 | 2540 / 2540 | 0.000 | 396.148 / 353.237 / 507.283 / 562.404 | 3.256 | -10.000 | 269.909 / 244.936 / 362.320 / 406.776 | unusable |
| uncategorized/vta28 | 4210 / 4210 | 0.000 | 39.072 / 47.556 / 115.973 / 175.206 | 2.518 | -5.700 | 18.175 / 22.341 / 57.997 / 92.935 | uncertain |
| uncategorized/vta29 | 23705 / 23705 | -0.622 | 92.177 / 98.837 / 219.549 / 370.607 | 2.974 | -9.000 | 20.381 / 25.516 / 67.371 / 131.994 | uncertain |
| uncategorized/vta3 | 645 / 645 | -0.000 | 90.248 / 89.509 / 174.514 / 214.390 | 7.494 | -10.000 | 50.105 / 52.637 / 128.282 / 131.614 | uncertain |
| uncategorized/vta30 | 17136 / 17136 | 0.000 | 11.074 / 21.485 / 73.741 / 140.468 | 0.959 | -1.400 | 10.694 / 20.631 / 68.858 / 138.701 | approximate |
| uncategorized/vta4 | 1789 / 1789 | -0.001 | 29.021 / 34.941 / 80.633 / 104.070 | 1.107 | -4.300 | 23.590 / 28.277 / 65.286 / 77.483 | uncertain |
| uncategorized/vta5 | 307 / 307 | 0.000 | 44.034 / 47.020 / 96.029 / 109.949 | 1.542 | -4.700 | 25.867 / 26.710 / 53.013 / 62.521 | uncertain |
| uncategorized/vta6 | 1376 / 1376 | 0.000 | 46.123 / 56.571 / 134.869 / 191.259 | 1.021 | -2.100 | 40.494 / 43.234 / 91.668 / 137.081 | uncertain |
| uncategorized/vta7 | 840 / 840 | -0.000 | 47.625 / 64.107 / 179.253 / 226.938 | 2.388 | -2.600 | 32.243 / 42.341 / 111.232 / 157.237 | uncertain |
| uncategorized/vta8 | 3676 / 3676 | 0.000 | 22.177 / 35.978 / 112.923 / 169.475 | 1.356 | -3.900 | 14.886 / 21.956 / 65.584 / 100.922 | approximate |
| uncategorized/vta9 | 156 / 156 | -0.002 | 71.126 / 73.097 / 144.587 / 158.389 | 3.086 | -3.000 | 38.716 / 40.890 / 86.904 / 98.782 | uncertain |
| uncategorized/vtb1 | 32459 / 32459 | -0.001 | 54.320 / 322.643 / 2,491.759 / 4,280.256 | 2.797 | -4.200 | 38.199 / 513.367 / 4,925.731 / 4,929.638 | uncertain |
| uncategorized/vtb10 | 196 / 195 | 0.100 | 26.330 / 36.292 / 91.426 / 102.014 | 1.798 | -2.500 | 22.073 / 24.433 / 49.815 / 54.633 | unusable |
| uncategorized/vtb11 | 361 / 361 | 0.000 | 82.435 / 83.324 / 161.111 / 174.033 | 0.693 | -5.000 | 42.285 / 44.290 / 91.879 / 102.912 | uncertain |
| uncategorized/vtb12 | 447 / 447 | 0.001 | 40.803 / 47.769 / 120.545 / 153.062 | 1.694 | -1.900 | 25.425 / 33.634 / 88.928 / 126.019 | uncertain |
| uncategorized/vtb2 | 5712 / 5712 | 0.000 | 19.685 / 29.269 / 84.979 / 131.078 | 1.374 | -4.300 | 14.192 / 19.594 / 54.307 / 86.134 | approximate |
| uncategorized/vtb3 | 8240 / 8240 | -0.000 | 13.386 / 40.904 / 465.415 / 465.668 | 0.424 | -6.200 | 11.764 / 38.640 / 465.382 / 465.574 | uncertain |
| uncategorized/vtb4 | 556 / 556 | 0.000 | 12.572 / 18.403 / 50.545 / 72.236 | 1.025 | -1.500 | 11.751 / 15.975 / 37.886 / 61.328 | uncertain |
| uncategorized/vtb5 | 64388 / 64388 | 0.001 | 513.108 / 529.187 / 885.460 / 1,065.330 | 3.588 | -10.000 | 355.408 / 360.734 / 622.702 / 766.639 | unusable |
| uncategorized/vtb6 | 498 / 498 | -0.000 | 69.226 / 73.142 / 147.030 / 172.225 | 0.768 | -2.300 | 38.202 / 47.232 / 107.088 / 126.889 | uncertain |
| uncategorized/vtb7 | 461 / 461 | 0.001 | 64.971 / 69.867 / 147.359 / 161.356 | 1.620 | -6.500 | 34.455 / 42.137 / 102.110 / 113.583 | uncertain |
| uncategorized/vtb8 | 668 / 668 | 0.000 | 78.467 / 81.891 / 162.510 / 178.517 | 0.520 | -2.100 | 42.709 / 54.203 / 121.390 / 136.712 | uncertain |
| uncategorized/vtb9 | 452 / 452 | -0.001 | 93.201 / 93.862 / 179.643 / 209.061 | 1.178 | -6.500 | 46.724 / 58.814 / 132.986 / 158.488 | uncertain |
| uncategorized/vw1 | 20476 / 20475 | 0.100 | 1.713 / 1.706 / 2.241 / 2.772 | 0.011 | 9.500 | 1.711 / 1.704 / 2.241 / 2.772 | unusable |
| uncategorized/vw10 | 652 / 652 | 0.000 | 44.110 / 50.511 / 111.680 / 125.984 | 1.813 | -5.400 | 22.823 / 26.015 / 59.570 / 72.082 | uncertain |
| uncategorized/vw11 | 4909 / 4909 | -0.000 | 21.571 / 37.217 / 129.143 / 192.127 | 1.454 | -3.600 | 18.870 / 27.710 / 84.204 / 140.813 | approximate |
| uncategorized/vw12 | 918 / 918 | -0.000 | 55.236 / 56.184 / 110.016 / 132.854 | 0.360 | -0.000 | 55.236 / 56.183 / 110.016 / 132.854 | uncertain |
| uncategorized/vw13 | 284 / 284 | -0.000 | 89.153 / 94.321 / 203.710 / 228.058 | 1.059 | -5.500 | 62.974 / 77.830 / 167.846 / 180.910 | uncertain |
| uncategorized/vw14a | 3138 / 3138 | -0.000 | 56.391 / 57.736 / 115.970 / 143.679 | 0.673 | 0.500 | 56.457 / 56.984 / 109.628 / 133.301 | uncertain |
| uncategorized/vw14b | 19588 / 19588 | 0.001 | 43.172 / 47.734 / 105.433 / 157.617 | 0.553 | -0.600 | 43.186 / 47.385 / 102.410 / 145.394 | uncertain |
| uncategorized/vw14c | 15826 / 15826 | 0.000 | 15.562 / 31.356 / 117.556 / 184.874 | 1.105 | -2.000 | 13.843 / 25.030 / 83.991 / 131.422 | approximate |
| uncategorized/vw15 | 1380 / 1391 | -1.100 | 3.591 / 4.240 / 6.891 / 7.378 | 0.023 | -10.000 | 3.474 / 4.021 / 6.051 / 6.561 | unusable |
| uncategorized/vw16a | 5879 / 5879 | -0.001 | 213.957 / 203.641 / 360.979 / 419.873 | 3.159 | -10.000 | 54.397 / 63.582 / 156.852 / 200.661 | unusable |
| uncategorized/vw16b | 1126 / 1126 | 0.001 | 68.542 / 73.408 / 168.943 / 206.585 | 2.263 | -3.700 | 34.932 / 39.022 / 89.815 / 119.204 | uncertain |
| uncategorized/vw17 | 329 / 329 | 0.001 | 43.559 / 50.106 / 112.988 / 127.111 | 1.842 | -4.600 | 37.076 / 44.843 / 103.355 / 112.078 | uncertain |
| uncategorized/vw2 | 52713 / 52712 | 0.099 | 40.400 / 55.011 / 145.700 / 218.826 | 0.861 | -2.500 | 37.624 / 42.411 / 97.525 / 141.205 | unusable |
| uncategorized/vw3 | 3861 / 3861 | 0.001 | 46.578 / 56.435 / 137.452 / 181.543 | 1.961 | -5.000 | 25.528 / 30.204 / 73.699 / 106.079 | uncertain |
| uncategorized/vw4 | 126526 / 126527 | -0.100 | 51.388 / 68.562 / 197.860 / 305.834 | 1.435 | -3.800 | 29.877 / 38.668 / 105.989 / 169.389 | unusable |
| uncategorized/vw5 | 1012 / 1012 | 0.001 | 13.398 / 17.553 / 42.708 / 58.907 | 1.482 | -2.200 | 13.294 / 15.115 / 33.757 / 44.473 | uncertain |
| uncategorized/vw6 | 1281 / 1281 | 0.000 | 45.072 / 46.277 / 89.285 / 106.092 | 1.694 | -4.100 | 17.770 / 21.028 / 50.720 / 64.068 | uncertain |
| uncategorized/vw7 | 1602 / 1602 | -0.001 | 73.447 / 79.137 / 144.200 / 178.100 | 3.343 | -10.000 | 26.882 / 29.488 / 68.525 / 105.827 | uncertain |
| uncategorized/vw8 | 1529 / 1529 | -0.000 | 21.951 / 26.617 / 68.233 / 102.060 | 2.463 | -3.700 | 16.694 / 21.811 / 62.952 / 85.437 | uncertain |
| uncategorized/vw9 | 552 / 553 | -0.099 | 29.546 / 30.517 / 64.756 / 79.844 | 2.152 | -5.800 | 18.157 / 22.082 / 50.273 / 67.084 | unusable |
| uncategorized/y1 | 70285 / 70285 | -275.482 | 651.624 / 657.072 / 1,242.412 / 1,533.026 | 5.433 | 1.600 | 16.188 / 21.334 / 56.397 / 145.917 | unusable |

### Unequal row counts

| Pair | S rows | V rows |
| --- | --- | --- |
| categorized/vfa01 | 11486 | 11535 |
| categorized/vfa02 | 67523 | 67755 |
| categorized/vta1b | 954 | 953 |
| categorized/vtb10 | 196 | 195 |
| categorized/vw1 | 20476 | 20475 |
| categorized/vw15 | 1380 | 1391 |
| categorized/vw2 | 52713 | 52712 |
| categorized/vw4 | 126526 | 126527 |
| categorized/vw9 | 552 | 553 |
| uncategorized/vfa01 | 11486 | 11535 |
| uncategorized/vfa02 | 67523 | 67755 |
| uncategorized/vta1b | 954 | 953 |
| uncategorized/vtb10 | 196 | 195 |
| uncategorized/vw1 | 20476 | 20475 |
| uncategorized/vw15 | 1380 | 1391 |
| uncategorized/vw2 | 52713 | 52712 |
| uncategorized/vw4 | 126526 | 126527 |
| uncategorized/vw9 | 552 | 553 |

**Still unresolved.** 18 lag searches reach the +/-10 s boundary: categorized/s3a, categorized/vta1a, categorized/vta20, categorized/vta27, categorized/vta3, categorized/vtb5, categorized/vw15, categorized/vw16a, categorized/vw7, uncategorized/s3a, uncategorized/vta1a, uncategorized/vta20, uncategorized/vta27, uncategorized/vta3, uncategorized/vtb5, uncategorized/vw15, uncategorized/vw16a, uncategorized/vw7. These are search-limited diagnostics, not measured unique offsets. Disagreement between speed-only, position-only and segment-wise optima also limits confidence; all are recorded in the evidence JSON.

## M (Driver B): categorized

**Confirmed from data.** S duration 10,599.623 s; V duration 10,597.300 s; difference 2.323 s. Smartphone elapsed-counter backward steps: 1. DATE gaps: 2. VBOX repeated timestamps: 1; maximum interval: 0.200 s.

| Gap after row (zero based) | DATE delta (s) | Excess over median dt (s) |
| --- | --- | --- |
| 44225 | 1.158 | 1.058 |
| 53467 | 1.365 | 1.265 |

GPS typical observed change interval: 9.000 s; longest held run: 1,300 rows. This is an observation of stored values, not a measured hardware acquisition rate.

Joint lag: -3.500 s; position-only optimum: -3.500 s; speed-only optimum: -3.500 s. Disjoint thirds choose -3.000, -4.000, -3.500 s.

| Clock hypothesis | Median (m) | Mean (m) | p95 (m) | Max (m) | Speed MAE (m/s) |
| --- | --- | --- | --- | --- | --- |
| zero elapsed offset | 28.131 | 37.374 | 106.118 | 202.564 | 1.362 |
| best constant | 18.361 | 22.812 | 60.267 | 121.687 | 0.992 |
| endpoint affine hypothesis | 20.866 | 29.744 | 87.043 | 174.836 | 1.155 |
| DATE-gap piecewise hypothesis | 21.784 | 29.924 | 87.242 | 168.916 | 1.173 |

## M (Driver B): uncategorized

**Confirmed from data.** S duration 10,599.623 s; V duration 10,597.300 s; difference 2.323 s. Smartphone elapsed-counter backward steps: 1. DATE gaps: 2. VBOX repeated timestamps: 1; maximum interval: 0.200 s.

| Gap after row (zero based) | DATE delta (s) | Excess over median dt (s) |
| --- | --- | --- |
| 44225 | 1.158 | 1.058 |
| 53467 | 1.365 | 1.265 |

GPS typical observed change interval: 9.000 s; longest held run: 1,300 rows. This is an observation of stored values, not a measured hardware acquisition rate.

Joint lag: -3.500 s; position-only optimum: -3.500 s; speed-only optimum: -3.500 s. Disjoint thirds choose -3.000, -4.000, -3.500 s.

| Clock hypothesis | Median (m) | Mean (m) | p95 (m) | Max (m) | Speed MAE (m/s) |
| --- | --- | --- | --- | --- | --- |
| zero elapsed offset | 28.131 | 37.374 | 106.118 | 202.564 | 1.362 |
| best constant | 18.361 | 22.812 | 60.267 | 121.687 | 0.992 |
| endpoint affine hypothesis | 20.866 | 29.744 | 87.043 | 174.836 | 1.155 |
| DATE-gap piecewise hypothesis | 21.784 | 29.924 | 87.242 | 168.916 | 1.173 |

## Suitability and leakage controls

**Inferred with supporting evidence.** Classification counts: {'approximate': 20, 'uncertain': 94, 'unusable': 30}. Approximate means equal rows, no backward/missing main clock, >=80% position coverage, speed correlation >=0.9, median position disagreement <=30 m and a supported speed-unit hypothesis. These conservative audit thresholds do not certify supervised label accuracy or the competition drift target.

Conditional candidates for subsequent body-frame and inertial-label lag validation:

categorized/m, categorized/s2, categorized/vta16, categorized/vta2, categorized/vta26, categorized/vta30, categorized/vta8, categorized/vtb2, categorized/vw11, categorized/vw14c, uncategorized/m, uncategorized/s2, uncategorized/vta16, uncategorized/vta2, uncategorized/vta26, uncategorized/vta30, uncategorized/vta8, uncategorized/vtb2, uncategorized/vw11, uncategorized/vw14c

Unique sequence names among these candidates (10): m, s2, vta16, vta2, vta26, vta30, vta8, vtb2, vw11, vw14c. Copies do not increase the number of independent recordings.

Quarantine from direct supervised velocity training pending investigation:

categorized/s1, categorized/s3a, categorized/s3b, categorized/s3c, categorized/s4, categorized/vfa01, categorized/vfa02, categorized/vta10, categorized/vta11, categorized/vta12, categorized/vta13, categorized/vta14, categorized/vta15, categorized/vta17, categorized/vta19, categorized/vta1a, categorized/vta1b, categorized/vta20, categorized/vta21, categorized/vta22, categorized/vta23, categorized/vta24, categorized/vta25, categorized/vta27, categorized/vta28, categorized/vta29, categorized/vta3, categorized/vta4, categorized/vta5, categorized/vta6, categorized/vta7, categorized/vta9, categorized/vtb1, categorized/vtb10, categorized/vtb11, categorized/vtb12, categorized/vtb3, categorized/vtb4, categorized/vtb5, categorized/vtb6, categorized/vtb7, categorized/vtb8, categorized/vtb9, categorized/vw1, categorized/vw10, categorized/vw12, categorized/vw13, categorized/vw14a, categorized/vw14b, categorized/vw15, categorized/vw16a, categorized/vw16b, categorized/vw17, categorized/vw2, categorized/vw3, categorized/vw4, categorized/vw5, categorized/vw6, categorized/vw7, categorized/vw8, categorized/vw9, categorized/y1, uncategorized/s1, uncategorized/s3a, uncategorized/s3b, uncategorized/s3c, uncategorized/s4, uncategorized/vfa01, uncategorized/vfa02, uncategorized/vta10, uncategorized/vta11, uncategorized/vta12, uncategorized/vta13, uncategorized/vta14, uncategorized/vta15, uncategorized/vta17, uncategorized/vta19, uncategorized/vta1a, uncategorized/vta1b, uncategorized/vta20, uncategorized/vta21, uncategorized/vta22, uncategorized/vta23, uncategorized/vta24, uncategorized/vta25, uncategorized/vta27, uncategorized/vta28, uncategorized/vta29, uncategorized/vta3, uncategorized/vta4, uncategorized/vta5, uncategorized/vta6, uncategorized/vta7, uncategorized/vta9, uncategorized/vtb1, uncategorized/vtb10, uncategorized/vtb11, uncategorized/vtb12, uncategorized/vtb3, uncategorized/vtb4, uncategorized/vtb5, uncategorized/vtb6, uncategorized/vtb7, uncategorized/vtb8, uncategorized/vtb9, uncategorized/vw1, uncategorized/vw10, uncategorized/vw12, uncategorized/vw13, uncategorized/vw14a, uncategorized/vw14b, uncategorized/vw15, uncategorized/vw16a, uncategorized/vw16b, uncategorized/vw17, uncategorized/vw2, uncategorized/vw3, uncategorized/vw4, uncategorized/vw5, uncategorized/vw6, uncategorized/vw7, uncategorized/vw8, uncategorized/vw9, uncategorized/y1

Stationary sequences may still be useful for bias/gravity studies even when lag or speed units are unidentifiable. Any row-mismatched pair requires explicit correspondence reconstruction before supervised use; truncating to min row count here is a diagnostic comparison, not alignment.

Keep all categorized/uncategorized and synchronized/unsynchronized versions of a sequence in one split group. Merge groups connected by exact/numeric duplicate or overlap evidence. Adjacent route fragments from the same driver/session can also overlap without matching exact row fingerprints; partition by acquisition session/date/driver where appropriate and verify boundaries before constructing windows. No train/test split is created by this audit.

Fit alignment parameters and normalization statistics using training or independent calibration data only. Freeze them before held-out evaluation. Never import per-test-sequence best lags from this report into evaluation preprocessing. A test sequence without known correspondence should remain excluded or have results explicitly marked alignment-uncertain. Separate hardware reference truth from deployable sensor features; do not use VBOX/CAN motion channels as model inputs.

## Readiness verdict

Phase 0 inventory and empirical audit are complete when the repeat/integrity verification passes. Body-frame diagnosis can proceed with the documented uncertainty. Mechanization correction is conditional on resolving gyro/acceleration frame semantics and selecting a defensible time model. AI training is not yet approved: diagnostic GNSS agreement alone cannot certify IMU-label synchronization.

Detailed unit/lag/coverage results and per-pair classification reasons are in `phase0_evidence.json`; full file-level sampling and duplicates are in `phase0_inventory.json`.
