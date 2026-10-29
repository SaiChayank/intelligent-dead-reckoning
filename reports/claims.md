# Results, generated from result files

Every number here is read from a file in `reports/` by `tools/build_claims.py`. Nothing is on a device or in a vehicle yet. IO-VNBD GNSS is the VBOX receiver at 1 Hz (the phone logged GPS only about every 9 s).

**IO-VNBD, vehicle-frame inertial input (external-IMU case)** (11 IO-VNBD drives, 13.9 h, 462 km). Median drift as a percentage of distance travelled in the outage, and the share of outages under the 10 % gate. GNSS-aided error while fixes are available: EKF + NHC/ZUPT 1.39 m median, App configuration 0.92 m median.

| Arm | 50 m outage | 30 s outage | 60 s outage | 1 km outage |
|---|---:|---:|---:|---:|
| INS only (no constraints) | 13 % (37%) | 43 % (8%) | 84 % (3%) | 112 % (2%) |
| EKF + NHC/ZUPT | 14 % (33%) | 42 % (6%) | 84 % (3%) | 123 % (3%) |
| EKF + NHC/ZUPT + Model A v1 | 14 % (33%) | 44 % (7%) | 223 % (1%) | 420 % (3%) |
| EKF + NHC/ZUPT + speed correction v3 (in-sample weights) | 14 % (37%) | 23 % (20%) | 24 % (17%) | 26 % (17%) |
| EKF + NHC/ZUPT + speed correction v3 (held-out weights) | 13 % (36%) | 24 % (17%) | 24 % (16%) | 27 % (12%) |
| App configuration | 32 % (21%) | 767 % (0%) | 1169 % (0%) | 1407 % (1%) |
| Baseline: hold last GNSS velocity | 18 % (39%) | 53 % (6%) | 71 % (3%) | 69 % (2%) |

_The in-sample v3 row uses weights trained on these same drives (optimistic). The held-out row retrains without the drive being run, so it is the one to quote._

**IO-VNBD, phone IMU export** (13 IO-VNBD drives, 13.2 h, 444 km). Median drift as a percentage of distance travelled in the outage, and the share of outages under the 10 % gate. GNSS-aided error while fixes are available: EKF + NHC/ZUPT 1.81 m median, App configuration 0.88 m median.

| Arm | 50 m outage | 30 s outage | 60 s outage | 1 km outage |
|---|---:|---:|---:|---:|
| INS only (no constraints) | 19 % (26%) | 70 % (2%) | 133 % (1%) | 183 % (2%) |
| EKF + NHC/ZUPT | 19 % (26%) | 75 % (3%) | 125 % (1%) | 177 % (2%) |
| EKF + NHC/ZUPT + Model A v1 | 19 % (24%) | 75 % (3%) | 129 % (1%) | 409 % (3%) |
| App configuration | 28 % (25%) | 771 % (1%) | 1944 % (0%) | 2292 % (2%) |
| Baseline: hold last GNSS velocity | 16 % (41%) | 49 % (7%) | 68 % (4%) | 70 % (2%) |

**Speed model v3** (learned correction of integrated acceleration, vehicle-frame inertial input, leave-one-drive-out over 11 drives):

| | Hold last speed | Integrated acceleration | Learned v3 |
|---|---:|---:|---:|
| Speed RMSE, all horizons (m/s) | 4.785 | 4.273 | 2.554 |
| Median drift over 30 s outages (%) | 22.07 | 17.8 | 12.0 |
| Median drift over 60 s outages (%) | 29.24 | 29.61 | 17.36 |
| Outages under 10 % drift, 30 s | 27% | 30% | 42% |
| Outages under 10 % drift, 60 s | 14% | 14% | 25% |

**Map matching on real Hyderabad road geometry** (10 routes from the shipped OSM graph). Simulated vehicle at 12 m/s (constant speed) on routes built from the shipped real Hyderabad OSM road graph; truth is the centreline with rounded corners shifted right by the lane offset of each condition; synthetic sensors with declared noise and biases; 1 Hz GNSS with 3 m noise withheld for a 30 s and a 60 s outage; three noise seeds. Median drift as a percentage of outage distance (share of outages under 10 %); `lane6m` puts the true lane 6 m from the mapped centreline.

| Arm | lane1.5m: 30 s | lane1.5m: 60 s | lane6m: 30 s | lane6m: 60 s |
|---|---:|---:|---:|---:|
| Constraints only (no map) | 13.6 % (33%) | 33.1 % (3%) | 15.1 % (37%) | 31.6 % (7%) |
| Greedy matcher, heading feedback | 12.4 % (37%) | 32.1 % (3%) | 11.9 % (37%) | 31.2 % (7%) |
| HMM matcher, heading feedback | 12.0 % (40%) | 31.8 % (3%) | 11.8 % (40%) | 31.4 % (7%) |
| HMM matcher, heading + cross-track | 4.0 % (90%) | 4.6 % (67%) | 6.7 % (67%) | 4.1 % (63%) |
| HMM matcher, cross-track only | 4.9 % (90%) | 5.1 % (67%) | 7.6 % (67%) | 5.3 % (60%) |

_Synthetic motion at constant speed along real road geometry, not a recorded drive._

**Learned GNSS trust** (research artifact, not in the engine; leave-one-drive-out, 6283 phone fixes): rank correlation with true fix error 0.28 (reported accuracy: 0.048); mean absolute error 2.487 m (reported accuracy: 2.638 m).

**Scripted synthetic drive** (70 s scripted drive: straight cruise at 12 m/s with one turn, 20 s GNSS outage, seeded noise and fixed sensor biases). Measured by the JVM test `SyntheticOutageMeasurementTest`; this is a straight cruise, so it says little about real roads.

| Arm | End error (m) | Drift (%) | Max error (m) |
|---|---:|---:|---:|
| Classical INS (one alignment fix, then no GNSS) | 310.87 | 129.53 | 310.87 |
| Classical fusion (GNSS+INS filter, no motion constraints) | 73.68 | 30.70 | 73.68 |
| Fusion + vehicle-motion constraints | 73.68 | 30.70 | 73.68 |
| Fusion + constraints + Model A v1 forward speed | 73.68 | 30.70 | 73.68 |
