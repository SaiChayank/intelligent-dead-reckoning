# GNSS quality thresholds — measurement against recorded data

Produced by `tools/gnss_quality_thresholds.py` (read-only). Percentiles are
nearest-rank; `Location.getAccuracy()` is the provider's own 68% horizontal
radius estimate, so accuracy figures are 1-sigma radii and not guaranteed errors.

## Corpus

- sessions discovered 45, read 45
- sessions carrying GNSS 4, carrying none 41
- fixes 1782 across providers {'gps': 1660, 'network': 122}

| observable | count | min | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|
| horizontal_accuracy_m | 1782 | 3.79 | 9.935 | 13.09 | 100 | 100 | 100 |
| vertical_accuracy_m | 1782 | 1 | 2.791 | 6.678 | 100 | 100 | 100 |
| satellites_used | 1660 | 5 | 22 | 42 | 44 | 47 | 53 |
| receipt_delay_ms | 1782 | 3.865 | 31.81 | 44.7 | 47.99 | 55.9 | 1459 |
| inter_fix_interval_all_providers_s | 1777 | 0.9987 | 1 | 1.001 | 20.1 | 20.11 | 20.14 |

## Optional-field availability

| field | present | null | total |
|---|---|---|---|
| speed_m_s | 1660 | 122 | 1782 |
| bearing_deg | 0 | 1782 | 1782 |
| horizontal_accuracy_m | 1782 | 0 | 1782 |
| vertical_accuracy_m | 1782 | 0 | 1782 |
| satellites_used | 1660 | 122 | 1782 |

## Candidate stale bounds: intervals a bound would misreport

`over_bound` counts real inter-fix intervals longer than the bound; each one
is an arrival at which a healthy channel was already called stale.

| bound | provider | intervals | over bound | fraction | max over (s) |
|---|---|---|---|---|---|
| 1s | gps | 1659 | 863 | 0.5202 | 1.001 |
| 1s | network | 118 | 118 | 1.0000 | 20.135 |
| 2s | gps | 1659 | 0 | 0.0000 | — |
| 2s | network | 118 | 117 | 0.9915 | 20.135 |
| 3s | gps | 1659 | 0 | 0.0000 | — |
| 3s | network | 118 | 117 | 0.9915 | 20.135 |
| 5s | gps | 1659 | 0 | 0.0000 | — |
| 5s | network | 118 | 117 | 0.9915 | 20.135 |
| 10s | gps | 1659 | 0 | 0.0000 | — |
| 10s | network | 118 | 116 | 0.9831 | 20.135 |
| 15s | gps | 1659 | 0 | 0.0000 | — |
| 15s | network | 118 | 116 | 0.9831 | 20.135 |
| 30s | gps | 1659 | 0 | 0.0000 | — |
| 30s | network | 118 | 0 | 0.0000 | — |
| 60s | gps | 1659 | 0 | 0.0000 | — |
| 60s | network | 118 | 0 | 0.0000 | — |

## Candidate horizontal-accuracy bounds

| bound | provider | known |
unknown | within bound | fraction |
|---|---|---|---|---|---|
| 3m | gps | 1660 | 0 | 0 | 0.0000 |
| 3m | network | 122 | 0 | 0 | 0.0000 |
| 5m | gps | 1660 | 0 | 53 | 0.0319 |
| 5m | network | 122 | 0 | 0 | 0.0000 |
| 10m | gps | 1660 | 0 | 1270 | 0.7651 |
| 10m | network | 122 | 0 | 0 | 0.0000 |
| 15m | gps | 1660 | 0 | 1656 | 0.9976 |
| 15m | network | 122 | 0 | 0 | 0.0000 |
| 20m | gps | 1660 | 0 | 1660 | 1.0000 |
| 20m | network | 122 | 0 | 1 | 0.0082 |
| 30m | gps | 1660 | 0 | 1660 | 1.0000 |
| 30m | network | 122 | 0 | 1 | 0.0082 |
| 50m | gps | 1660 | 0 | 1660 | 1.0000 |
| 50m | network | 122 | 0 | 1 | 0.0082 |
| 100m | gps | 1660 | 0 | 1660 | 1.0000 |
| 100m | network | 122 | 0 | 122 | 1.0000 |

## Candidate satellite-count bounds

| bound | provider | known |
unknown | at least | fraction |
|---|---|---|---|---|---|
| 3 | gps | 1660 | 0 | 1660 | 1.0000 |
| 3 | network | 0 | 122 | 0 | — |
| 4 | gps | 1660 | 0 | 1660 | 1.0000 |
| 4 | network | 0 | 122 | 0 | — |
| 5 | gps | 1660 | 0 | 1660 | 1.0000 |
| 5 | network | 0 | 122 | 0 | — |
| 6 | gps | 1660 | 0 | 1658 | 0.9988 |
| 6 | network | 0 | 122 | 0 | — |
| 8 | gps | 1660 | 0 | 1651 | 0.9946 |
| 8 | network | 0 | 122 | 0 | — |
| 10 | gps | 1660 | 0 | 1650 | 0.9940 |
| 10 | network | 0 | 122 | 0 | — |

## Per-provider detail

### gps

- fixes 1660
- inter-fix interval s: min 0.998665832 p50 1.000006926 p99 1.000894949 max 1.001025259
- horizontal accuracy m: min 3.7900924682617188 p50 9.935046195983887 max 16.064613342285156
- satellites used: min 5.0 p50 22.0 max 53.0
- horizontal accuracy repeats: [{'value': 9.935046195983887, 'count': 1202}, {'value': 3.7900924682617188, 'count': 53}, {'value': 9.935094833374023, 'count': 1}, {'value': 9.949745178222656, 'count': 1}, {'value': 9.95644760131836, 'count': 1}]
- vertical accuracy repeats: [{'value': 2.5, 'count': 744}, {'value': 2.5009207725524902, 'count': 1}, {'value': 2.5029714107513428, 'count': 1}, {'value': 2.5059573650360107, 'count': 1}, {'value': 2.5061838626861572, 'count': 1}]

### network

- fixes 122
- inter-fix interval s: min 1.002625052 p50 20.103927544 p99 20.115587336 max 20.135143326
- horizontal accuracy m: min 17.145999908447266 p50 100.0 max 100.0
- satellites used: min None p50 None max None
- horizontal accuracy repeats: [{'value': 100.0, 'count': 119}, {'value': 17.145999908447266, 'count': 1}, {'value': 68.4000015258789, 'count': 1}, {'value': 96.5498046875, 'count': 1}]
- vertical accuracy repeats: [{'value': 100.0, 'count': 104}, {'value': 1.0, 'count': 1}, {'value': 7.636115550994873, 'count': 1}, {'value': 14.673564910888672, 'count': 1}, {'value': 21.70961570739746, 'count': 1}]


## Session inventory

| recording | duration s | fixes | providers |
|---|---|---|---|
| f50068f4-f977-4090-9add-e109efa75a69 | 139.026 | 7 | {'network': 7} |
| 02edb616-0a6d-4cd1-82a9-aae0f55340b3 | 23.286 | 1 | {'network': 1} |
| 0309c1f1-2475-49a5-9f88-71fe6dbfb156 | 0.199 | 0 | {} |
| 06f7e84a-cb4c-40a9-97f5-ba7b7d36c31a | 8.983 | 0 | {} |
| 1d2828f6-5ea2-4d95-9b67-af3bfe9e70c8 | 0.904 | 0 | {} |
| 26e912f7-f3eb-4a42-9154-a4b128115939 | 0.219 | 0 | {} |
| 2bb36636-4268-4342-9299-e39be7f640ce | 0.157 | 0 | {} |
| 3058a392-bd3d-4af9-aec8-55be0f4d85a1 | 0.243 | 0 | {} |
| 31b0b817-8f8c-40e1-9934-ca45d2db91ea | 0.907 | 0 | {} |
| 3666f222-3ce2-4217-8288-bcfab3bda8c5 | 0.198 | 0 | {} |
| 3b68c148-03d3-4ba3-a211-1987d1cc629a | 0.24 | 0 | {} |
| 42cabe3e-eb6b-46d9-8c0a-9f0b3323b483 | 0.246 | 0 | {} |
| 42de0f40-d578-4964-ab4a-ba4c7efca95f | 0.116 | 0 | {} |
| 436dd463-8db7-4083-a053-1d37f561d94a | 0.208 | 0 | {} |
| 47a5f751-7f02-40b1-8ac3-0b87a86753cd | 0.281 | 0 | {} |
| 4a6a273c-763b-4060-ac36-dc8604de7751 | 0.888 | 0 | {} |
| 4fbceac4-a2d5-4745-b544-e5531eeb9f6f | 0.245 | 0 | {} |
| 5c2a81b0-0075-4375-aafe-3ffcdd5b3212 | 0.874 | 0 | {} |
| 6be39eaf-fb97-4c62-851c-13369ad6a271 | 0.827 | 0 | {} |
| 6be83e31-acd4-40f5-aba5-59d8aa2165ec | 0.229 | 0 | {} |
| 6e31e754-82f9-404c-952e-5ede6aca7c25 | 0.231 | 0 | {} |
| 799b22ab-3c16-4d82-97c7-8f0ef4f1b207 | 0.245 | 0 | {} |
| 7f300930-7506-4b88-93cb-55bbc2dd1327 | 0.212 | 0 | {} |
| 80eba24f-d8cd-461e-9922-cce7c6d60f41 | 0.246 | 0 | {} |
| 816b358e-379a-4765-98e4-75c33832c7b9 | 0.227 | 0 | {} |
| 8c6eb81d-bb6f-4b36-a1fe-82af523613a7 | 0.861 | 0 | {} |
| 9dc790e2-b1f0-40ee-9387-4e8e48f97681 | 0.259 | 0 | {} |
| a4bd2e41-31da-42de-b415-7f4b8b0933a0 | 0.145 | 0 | {} |
| ab57576e-cccd-4e12-9452-ef19649e9e20 | 0.754 | 0 | {} |
| ad5a629a-0272-4adc-bff3-6155b8732def | 0.234 | 0 | {} |
| afff558b-dbb2-40ea-84d0-b2049bf1d98f | 0.869 | 0 | {} |
| b8158b36-ed9c-40e7-ae74-7d91892d5442 | 0.293 | 0 | {} |
| c3db6fd7-bbcd-4b93-93cd-694f34d97108 | 603.757 | 30 | {'network': 30} |
| c630f009-2d88-419a-bc0e-f1ddfc1e2b07 | 0.205 | 0 | {} |
| c920150a-841e-4359-a449-29c53b488ba3 | 0.284 | 0 | {} |
| cd486840-5516-4a31-adb3-bf5750b518ff | 0.203 | 0 | {} |
| d9f0b4d7-5515-45af-8188-196884f94d58 | 0.241 | 0 | {} |
| e3e38239-40ef-40c1-9ce9-ac8b0ab8489c | 0.19 | 0 | {} |
| e85f7c8d-a1bd-4762-8762-0607826b74a4 | 0.133 | 0 | {} |
| ebca7e5a-9d74-4683-808a-693f81b510c4 | 0.888 | 0 | {} |
| ec89d515-0f2d-49f6-bd90-9a213a282f1f | 0.181 | 0 | {} |
| f2608548-d1c8-4007-9e72-87045a094f47 | 1660.211 | 1744 | {'gps': 1660, 'network': 84} |
| f7e9c8a1-a12a-433e-8ee2-7660853d4938 | 0.244 | 0 | {} |
| fab5ff5e-bca9-4ffb-9941-15ab0102e80d | 0.291 | 0 | {} |
| fb20974f-d5d5-4823-bc12-71bd384ac228 | 0.876 | 0 | {} |
