# Phase 0 - Data dictionary and unit evidence

Each registry family preserves the exact decoded source header. Leading whitespace and mojibake are normalized for internal identifiers only. Gyro channel labels such as pitch or X remain labels; they are not a physical phone-to-vehicle mapping.

`schema_registry.json` stores, for every observed column: original/normalized name, source and inferred units, normalized unit, confidence, evidence label, observed dtypes, missing/nonfinite/nonnumeric counts, range, measurement type, held-value assessment, training-feature policy, and reference-only status. Empirical units are scoped to listed paired sequences. A shared header alone cannot prove identical units in all files.

## Common data contract

Canonical numeric units are m, s, m/s, m/s², rad/s, degrees for geographic/course angles, and µT. Unresolved speed/height values retain `_raw` identifiers. `convert_unit` requires an explicit source and target unit and never guesses. `read_frame` renames columns but does not silently convert source values. Conversion decisions must be selected from sequence evidence.

All VBOX/CAN fields are excluded from deployable smartphone-model inputs. VBOX latitude, longitude, speed and heading are reference labels only, subject to validity checks. Smartphone GNSS fields are excluded from outage-model features and may be used only for trusted pre-outage initialization or explicitly defined GNSS-available context. Raw IMU/gravity/magnetic fields are candidate features after frame and causal preprocessing validation; device orientation and satellite strings are diagnostic until their semantics are resolved.

## schema-12fc4114f2 (smartphone; 71 files)

**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.

| Original header | Internal name | Dtypes | Documented unit | Inferred unit | Normalized unit | Type | Missing | Training policy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GPS LATITUDE (degrees) | gps_latitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS LONGITUDE (degrees) | gps_longitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS ALTITUDE (m) | gps_altitude | float64, int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS SPEED (Kmh) | gps_speed_raw | float64, int64 | km/h | m/s | m/s | continuous | 4 | excluded / metadata |
|  GPS ACCURACY (m) | gps_accuracy | float64, int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS ORIENTATION (Â°) | gps_course | float64, int64 | deg | unresolved | deg | continuous | 94 | excluded / metadata |
| GPS SATELLITES IN RANGE | gps_satellites_raw | str | n/a | unresolved | n/a | categorical | 0 | excluded / metadata |
|  TIME SINCE START (ms) | elapsed_counter | int64 | ms | unresolved | s | continuous | 0 | excluded / metadata |
|  DATE (YYYY-MO-DD HH-MI-SS_SSS) | datetime_raw | str | n/a | unresolved | n/a | timestamp | 0 | excluded / metadata |
|  ACCELEROMETER X (m/s²)  | accel_x | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Y (m/s²) | accel_y | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Z (m/s²) | accel_z | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY X (m/s²) | gravity_x | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Y (m/s²) | gravity_y | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Z (m/s²) | gravity_z | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GYROSCOPE X (rad/s) | gyro_channel_x | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Y (rad/s) | gyro_channel_y | float64 | rad/s | rad/s | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Z (rad/s) | gyro_channel_z | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD X (Î¼T) | magnetic_x | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD Y (Î¼T) | magnetic_y | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD Z (Î¼T) | magnetic_z | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  ORIENTATION (Azimuth) (Â°) | orientation_azimuth | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Pitch) (Â°) | orientation_pitch | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Roll ) (Â°) | orientation_roll | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |

## schema-1f9d435b70 (smartphone; 1 files)

**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.

| Original header | Internal name | Dtypes | Documented unit | Inferred unit | Normalized unit | Type | Missing | Training policy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GPS LATITUDE (degrees) | gps_latitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS LONGITUDE (degrees) | gps_longitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS ALTITUDE (m) | gps_altitude | int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS SPEED (Kmh) | gps_speed_raw | float64 | km/h | unresolved | unresolved | continuous | 0 | excluded / metadata |
|  GPS ACCURACY (m) | gps_accuracy | float64, int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS ORIENTATION (Â°) | gps_course | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
| GPS SATELLITES IN RANGE | unexpected_empty_column | float64 | n/a | unresolved | n/a | empty | 32829 | excluded / metadata |
|  TIME SINCE START (ms) | gps_satellites_raw | str | ms | unresolved | n/a | categorical | 0 | excluded / metadata |
|  DATE (YYYY-MO-DD HH-MI-SS_SSS) | elapsed_counter | int64 | n/a | unresolved | s | continuous | 0 | excluded / metadata |
|  ACCELEROMETER X (m/s²)  | datetime_raw | str | m/s2 | unresolved | n/a | timestamp | 0 | excluded / metadata |
|  ACCELEROMETER Y (m/s²) | accel_x | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | excluded / metadata |
|  ACCELEROMETER Z (m/s²) | accel_y | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | excluded / metadata |
|  GRAVITY X (m/s²) | accel_z | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | excluded / metadata |
|  GRAVITY Y (m/s²) | gravity_x | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | excluded / metadata |
|  GRAVITY Z (m/s²) | gravity_y | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | excluded / metadata |
|  GYROSCOPE Yaw (rad/s) | gravity_z | float64 | rad/s | unresolved | m/s2 | continuous | 0 | excluded / metadata |
|  GYROSCOPE Pitch (rad/s) | gyro_channel_yaw | float64 | rad/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  GYROSCOPE Roll (rad/s) | gyro_channel_pitch | float64 | rad/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  MAGNETIC FIELD X (Î¼T) | gyro_channel_roll | float64 | uT | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  MAGNETIC FIELD Y (Î¼T) | magnetic_x | float64 | uT | unresolved | uT | continuous | 0 | excluded / metadata |
|  MAGNETIC FIELD Z (Î¼T) | magnetic_y | float64 | uT | unresolved | uT | continuous | 0 | excluded / metadata |
|  ORIENTATION (Yaw) (Â°) | magnetic_z | float64 | deg | unresolved | uT | continuous | 0 | excluded / metadata |
|  ORIENTATION (Pitch) (Â°) | orientation_yaw | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Roll ) (Â°) | orientation_pitch | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
| (blank trailing column) | orientation_roll | float64 | n/a | unresolved | deg | continuous | 0 | excluded / metadata |

## schema-7e8ac482b0 (smartphone; 158 files)

**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.

| Original header | Internal name | Dtypes | Documented unit | Inferred unit | Normalized unit | Type | Missing | Training policy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GPS LATITUDE (degrees) | gps_latitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS LONGITUDE (degrees) | gps_longitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS ALTITUDE (m) | gps_altitude | float64, int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS SPEED (Kmh) | gps_speed_raw | float64, int64 | km/h | m/s | m/s | continuous | 8 | excluded / metadata |
|  GPS ACCURACY (m) | gps_accuracy | float64, int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS ORIENTATION (Â°) | gps_course | float64, int64 | deg | unresolved | deg | continuous | 2708 | excluded / metadata |
| GPS SATELLITES IN RANGE | gps_satellites_raw | str | n/a | unresolved | n/a | categorical | 0 | excluded / metadata |
|  TIME SINCE START (ms) | elapsed_counter | int64 | ms | unresolved | s | continuous | 0 | excluded / metadata |
|  DATE (YYYY-MO-DD HH-MI-SS_SSS) | datetime_raw | str | n/a | unresolved | n/a | timestamp | 0 | excluded / metadata |
|  ACCELEROMETER X (m/s²)  | accel_x | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Y (m/s²) | accel_y | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Z (m/s²) | accel_z | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY X (m/s²) | gravity_x | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Y (m/s²) | gravity_y | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Z (m/s²) | gravity_z | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GYROSCOPE Yaw (rad/s) | gyro_channel_yaw | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Pitch (rad/s) | gyro_channel_pitch | float64 | rad/s | rad/s | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Roll (rad/s) | gyro_channel_roll | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD X (Î¼T) | magnetic_x | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD Y (Î¼T) | magnetic_y | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD Z (Î¼T) | magnetic_z | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  ORIENTATION (Yaw) (Â°) | orientation_yaw | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Pitch) (Â°) | orientation_pitch | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Roll ) (Â°) | orientation_roll | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |

## schema-94eaa4638e (smartphone; 9 files)

**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.

| Original header | Internal name | Dtypes | Documented unit | Inferred unit | Normalized unit | Type | Missing | Training policy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GPS LATITUDE (degrees) | gps_latitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
| GPS LONGITUDE (degrees) | gps_longitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS ALTITUDE (m) | gps_altitude | float64 | m | unresolved | m | continuous | 0 | excluded / metadata |
| GPS SPEED (Kmh) | gps_speed_raw | float64 | km/h | unresolved | unresolved | continuous | 0 | excluded / metadata |
| GPS ACCURACY (m) | gps_accuracy | float64 | m | unresolved | m | continuous | 0 | excluded / metadata |
| GPS ORIENTATION (Â°) | gps_course | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  SATELLITES IN RANGE | gps_satellites_raw | str | n/a | unresolved | n/a | categorical | 0 | excluded / metadata |
|  TIME SINCE START (ms) | elapsed_counter | int64 | ms | unresolved | s | continuous | 0 | excluded / metadata |
|  DATE (YYYY-MO-DD HH-MI-SS_SSS) | datetime_raw | str | n/a | unresolved | n/a | timestamp | 0 | excluded / metadata |
| ACCELEROMETER X (m/s²)  | accel_x | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Y (m/s²) | accel_y | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Z (m/s²) | accel_z | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY X (m/s²) | gravity_x | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Y (m/s²) | gravity_y | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Z (m/s²) | gravity_z | float64 | m/s2 | unresolved | m/s2 | continuous | 0 | candidate after validation |
|  GYROSCOPE Yaw (rad/s) | gyro_channel_yaw | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Pitch (rad/s) | gyro_channel_pitch | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Roll (rad/s) | gyro_channel_roll | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |

## schema-bddf605e47 (vbox; 323 files)

**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.

| Original header | Internal name | Dtypes | Documented unit | Inferred unit | Normalized unit | Type | Missing | Training policy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| No of GPS Satellites Available | reference_satellites_raw | float64 | n/a | unresolved | n/a | discrete | 0 | excluded / metadata |
|  Time Since Start of Day (seconds) | reference_time_of_day | float64 | s | unresolved | s | timestamp | 0 | excluded / metadata |
|  Latitude (degrees) | reference_latitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  Longitude (degrees) | reference_longitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  Velocity (km/hr) | reference_speed | float64 | km/h | unresolved | m/s | continuous | 0 | excluded / metadata |
|  Heading (degrees) | reference_heading | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  Height (km) | reference_height_raw | float64 | km | m (magnitude hypothesis only) | unresolved | continuous | 0 | excluded / metadata |
|  Vertical velocity (km/hr) | reference_vertical_speed | float64 | km/h | unresolved | m/s | continuous | 0 | excluded / metadata |
|  Sample period (seconds) | reference_sample_period | float64 | s | unresolved | s | continuous | 0 | excluded / metadata |
|  Steering Angle (degrees) | vehicle_steering_angle | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  Wheel Speed Front Left (rad/sec) | vehicle_wheel_speed_front_left | float64 | rad/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  Wheel Speed Front Right (rad/sec) | vehicle_wheel_speed_front_right | float64 | rad/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  Wheel Speed Rear Left (rad/sec) | vehicle_wheel_speed_rear_left | float64 | rad/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  Wheel Speed Rear Right (rad/sec) | vehicle_wheel_speed_rear_right | float64 | rad/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  Yaw Rate (deg/sec) | vehicle_yaw_rate | float64 | deg/s | unresolved | rad/s | continuous | 0 | excluded / metadata |
|  Indicated Vehicle Speed (km/hr) | vehicle_indicated_speed | float64 | km/h | unresolved | m/s | continuous | 0 | excluded / metadata |
|  Indicated Longitudinal Acceleration (g) | vehicle_accel_long | float64 | g | g (hypothesis tested against motion) | m/s2 | continuous | 0 | excluded / metadata |
|  Indicated Lateral Acceleration (g) | vehicle_accel_lat | float64 | g | g (hypothesis tested against motion) | m/s2 | continuous | 0 | excluded / metadata |
|  Handbrake (0 or 1) | vehicle_handbrake | float64 | flag | unresolved | flag | discrete | 0 | excluded / metadata |
|  Gear Requested (Number fof gear employed 1-5) | vehicle_requested_gear | float64 | gear | unresolved | gear | discrete | 0 | excluded / metadata |
|  Gear (Number fof gear employed 1-5) | vehicle_gear | float64 | gear | unresolved | gear | discrete | 0 | excluded / metadata |
|  Engine Speed (rev/min) | vehicle_engine_speed | float64 | rpm | unresolved | rpm | continuous | 0 | excluded / metadata |
|  Coolant Temperature (degrees) | vehicle_coolant_temperature | float64 | degC | unresolved | degC | continuous | 0 | excluded / metadata |
|  Clutch Position (0 or 1) | vehicle_clutch | float64 | flag | unresolved | flag | discrete | 0 | excluded / metadata |
|  Brake Pressure (psi) | vehicle_brake_pressure | float64 | psi | unresolved | psi | continuous | 0 | excluded / metadata |
|  Brake Position (0 or 1) | vehicle_brake | float64 | flag | unresolved | flag | discrete | 0 | excluded / metadata |
|  Battery Voltage (volts) | vehicle_battery_voltage | float64 | V | unresolved | V | continuous | 0 | excluded / metadata |
|  Air Temperature (degrees) | vehicle_air_temperature | float64 | degC | unresolved | degC | continuous | 0 | excluded / metadata |
|  Accelerator Pedal Position (0 or 1) | vehicle_accelerator_pedal_raw | float64 | flag | percent-like scale | unresolved | continuous | 0 | excluded / metadata |

## schema-cbfccaebcf (smartphone; 2 files)

**Confirmed from data** for headers, types and missing counts. Units retain the separate evidence/confidence entries in the registry.

| Original header | Internal name | Dtypes | Documented unit | Inferred unit | Normalized unit | Type | Missing | Training policy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GPS LATITUDE (degrees) | gps_latitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS LONGITUDE (degrees) | gps_longitude | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  GPS ALTITUDE (m) | gps_altitude | float64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS SPEED (Kmh) | gps_speed_raw | float64 | km/h | m/s | m/s | continuous | 0 | excluded / metadata |
|  GPS ACCURACY (m) | gps_accuracy | int64 | m | unresolved | m | continuous | 0 | excluded / metadata |
|  GPS ORIENTATION (Â°) | gps_course | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
| GPS SATELLITES IN RANGE | gps_satellites_raw | str | n/a | unresolved | n/a | categorical | 0 | excluded / metadata |
|  TIME SINCE START (ms) | elapsed_counter | int64 | ms | unresolved | s | continuous | 0 | excluded / metadata |
|  DATE (YYYY-MO-DD HH-MI-SS_SSS | datetime_raw | str | n/a | unresolved | n/a | timestamp | 0 | excluded / metadata |
|  ACCELEROMETER X (m/s²)  | accel_x | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Y (m/s²) | accel_y | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  ACCELEROMETER Z (m/s²) | accel_z | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY X (m/s²) | gravity_x | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Y (m/s²) | gravity_y | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GRAVITY Z (m/s²) | gravity_z | float64 | m/s2 | m/s2 | m/s2 | continuous | 0 | candidate after validation |
|  GYROSCOPE X (rad/s) | gyro_channel_x | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Y (rad/s) | gyro_channel_y | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  GYROSCOPE Z (rad/s) | gyro_channel_z | float64 | rad/s | unresolved | rad/s | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD X (Î¼T) | magnetic_x | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD Y (Î¼T) | magnetic_y | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  MAGNETIC FIELD Z (Î¼T) | magnetic_z | float64 | uT | unresolved | uT | continuous | 0 | candidate after validation |
|  ORIENTATION (Azimuth) (Â°) | orientation_azimuth | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Pitch) (Â°) | orientation_pitch | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |
|  ORIENTATION (Roll ) (Â°) | orientation_roll | float64 | deg | unresolved | deg | continuous | 0 | excluded / metadata |

## Smartphone speed hypotheses

**Inferred with supporting evidence.** Compare the same numeric smartphone speed as km/h versus multiplying it by 3.6, both against VBOX km/h, before fitted time shifts. The latter tests the m/s hypothesis. Positive scaling leaves correlation unchanged, so correlation alone cannot distinguish the units. Unit decisions require moving data (reference standard deviation >5 km/h and mean >5 km/h), and one hypothesis must at least halve MAE. Stationary/ambiguous files remain unresolved.

**Inferred with supporting evidence.** Per-pair unit decisions: {'m/s': 116, 'unresolved': 28}. These are physical pair copies, not independent experiments. Choose m/s only for the sequences supported by the evidence. Unresolved files retain raw speed; any m/s comparison shown for them is explicitly a diagnostic hypothesis, not a conversion decision.

| Pair | Raw MAE / RMSE (km/h) | ×3.6 MAE / RMSE (km/h) | Correlation | Means raw / ×3.6 / VBOX | Inference |
| --- | --- | --- | --- | --- | --- |
| categorized/m | 25.980 / 29.970 | 4.223 / 7.438 | 0.932 | 9.966 / 35.878 / 35.706 | m/s |
| categorized/s1 | 19.455 / 22.893 | 6.183 / 9.134 | 0.837 | 7.431 / 26.751 / 26.401 | m/s |
| categorized/s2 | 21.227 / 26.318 | 5.790 / 8.999 | 0.908 | 8.087 / 29.114 / 28.941 | m/s |
| categorized/s3a | 28.221 / 32.551 | 10.128 / 14.765 | 0.762 | 10.613 / 38.208 / 37.937 | m/s |
| categorized/s3b | 14.364 / 16.661 | 5.143 / 7.698 | 0.753 | 5.799 / 20.878 / 19.905 | m/s |
| categorized/s3c | 30.759 / 37.611 | 5.358 / 8.732 | 0.957 | 11.809 / 42.512 / 42.308 | m/s |
| categorized/s4 | 25.797 / 32.654 | 16.129 / 24.478 | 0.516 | 9.874 / 35.547 / 33.650 | unresolved |
| categorized/vfa01 | 42.519 / 46.363 | 3.430 / 5.589 | 0.976 | 16.419 / 59.109 / 58.818 | m/s |
| categorized/vfa02 | 62.860 / 65.188 | 2.789 / 4.996 | 0.978 | 24.170 / 87.011 / 86.954 | m/s |
| categorized/vta10 | 64.766 / 66.210 | 5.851 / 9.295 | 0.875 | 25.225 / 90.810 / 89.991 | m/s |
| categorized/vta11 | 35.355 / 39.452 | 10.516 / 15.551 | 0.768 | 15.219 / 54.790 / 50.574 | m/s |
| categorized/vta12 | 47.543 / 48.660 | 8.579 / 9.849 | 0.569 | 18.773 / 67.583 / 66.316 | m/s |
| categorized/vta13 | 63.971 / 64.315 | 5.875 / 7.694 | 0.463 | 23.375 / 84.148 / 87.346 | m/s |
| categorized/vta14 | 49.018 / 49.563 | 3.223 / 4.558 | 0.885 | 18.784 / 67.621 / 67.802 | m/s |
| categorized/vta15 | 51.686 / 51.841 | 1.728 / 2.397 | 0.902 | 19.884 / 71.583 / 71.570 | m/s |
| categorized/vta16 | 31.477 / 35.309 | 3.909 / 6.643 | 0.954 | 12.132 / 43.676 / 43.361 | m/s |
| categorized/vta17 | 24.261 / 26.680 | 6.140 / 9.473 | 0.801 | 8.777 / 31.597 / 32.686 | m/s |
| categorized/vta19 | 21.387 / 25.035 | 10.296 / 14.592 | 0.720 | 9.900 / 35.640 / 29.078 | m/s |
| categorized/vta1a | 41.614 / 44.420 | 12.001 / 16.105 | 0.659 | 15.684 / 56.463 / 57.096 | m/s |
| categorized/vta1b | 34.347 / 36.523 | 1.478 / 2.093 | 0.993 | 13.203 / 47.530 / 47.549 | m/s |
| categorized/vta2 | 26.217 / 29.386 | 1.017 / 1.645 | 0.996 | 10.010 / 36.037 / 36.191 | m/s |
| categorized/vta20 | 4.367 / 10.603 | 6.822 / 16.274 | 0.292 | 1.957 / 7.044 / 4.223 | unresolved |
| categorized/vta21 | 34.661 / 37.395 | 9.198 / 13.717 | 0.683 | 13.299 / 47.875 / 47.787 | m/s |
| categorized/vta22 | 27.415 / 28.375 | 4.054 / 5.434 | 0.831 | 10.787 / 38.834 / 38.202 | m/s |
| categorized/vta23 | 25.381 / 26.627 | 4.009 / 5.943 | 0.876 | 9.946 / 35.804 / 35.327 | m/s |
| categorized/vta24 | 15.956 / 22.555 | 6.343 / 10.219 | 0.878 | 6.130 / 22.068 / 21.350 | m/s |
| categorized/vta25 | 8.733 / 14.846 | 11.368 / 18.116 | 0.110 | 2.482 / 8.935 / 8.853 | unresolved |
| categorized/vta26 | 13.664 / 19.512 | 3.037 / 5.342 | 0.960 | 5.253 / 18.911 / 18.620 | m/s |
| categorized/vta27 | 33.625 / 36.015 | 11.721 / 16.758 | 0.605 | 11.067 / 39.843 / 44.656 | m/s |
| categorized/vta28 | 24.906 / 28.934 | 9.066 / 13.081 | 0.745 | 9.119 / 32.829 / 33.577 | m/s |
| categorized/vta29 | 29.379 / 33.902 | 10.705 / 14.545 | 0.790 | 10.826 / 38.972 / 39.579 | m/s |
| categorized/vta3 | 21.589 / 26.002 | 26.978 / 29.180 | -0.704 | 2.665 / 9.594 / 20.992 | unresolved |
| categorized/vta30 | 18.070 / 24.452 | 3.454 / 5.711 | 0.968 | 6.889 / 24.799 / 24.677 | m/s |
| categorized/vta4 | 29.017 / 29.900 | 3.986 / 7.551 | 0.488 | 11.178 / 40.243 / 40.103 | m/s |
| categorized/vta5 | 31.009 / 31.620 | 5.551 / 8.094 | 0.069 | 11.460 / 41.256 / 42.469 | m/s |
| categorized/vta6 | 49.242 / 50.360 | 3.676 / 5.064 | 0.943 | 18.627 / 67.056 / 67.869 | m/s |
| categorized/vta7 | 46.800 / 51.379 | 8.597 / 13.348 | 0.886 | 17.791 / 64.047 / 64.591 | m/s |
| categorized/vta8 | 24.482 / 30.273 | 4.883 / 7.594 | 0.952 | 9.424 / 33.927 / 33.326 | m/s |
| categorized/vta9 | 54.916 / 55.441 | 11.110 / 13.111 | 0.817 | 16.848 / 60.654 / 71.764 | m/s |
| categorized/vtb1 | 35.077 / 39.278 | 10.070 / 17.821 | 0.741 | 11.456 / 41.242 / 46.093 | m/s |
| categorized/vtb10 | 30.438 / 31.396 | 6.473 / 9.262 | 0.711 | 11.279 / 40.604 / 41.717 | m/s |
| categorized/vtb11 | 50.522 / 50.562 | 2.495 / 3.290 | 0.323 | 19.293 / 69.456 / 69.815 | unresolved |
| categorized/vtb12 | 30.302 / 32.014 | 6.099 / 7.957 | 0.831 | 12.472 / 44.900 / 42.775 | m/s |
| categorized/vtb2 | 20.053 / 24.364 | 4.948 / 7.497 | 0.921 | 7.472 / 26.900 / 27.189 | m/s |
| categorized/vtb3 | 2.448 / 5.303 | 1.526 / 2.711 | 0.929 | 0.568 / 2.043 / 2.963 | unresolved |
| categorized/vtb4 | 10.915 / 13.129 | 3.690 / 5.016 | 0.872 | 4.878 / 17.562 / 15.629 | m/s |
| categorized/vtb5 | 45.922 / 50.138 | 12.916 / 18.521 | 0.768 | 17.210 / 61.955 / 62.355 | m/s |
| categorized/vtb6 | 45.535 / 45.725 | 2.765 / 3.596 | 0.702 | 17.780 / 64.009 / 63.316 | unresolved |
| categorized/vtb7 | 40.796 / 41.931 | 5.834 / 7.628 | 0.743 | 15.608 / 56.190 / 56.405 | m/s |
| categorized/vtb8 | 50.612 / 50.700 | 1.872 / 2.669 | 0.655 | 19.194 / 69.097 / 69.805 | unresolved |
| categorized/vtb9 | 55.716 / 55.946 | 4.239 / 6.084 | 0.367 | 21.370 / 76.934 / 77.086 | m/s |
| categorized/vw1 | 0.041 / 0.047 | 0.041 / 0.047 | unavailable | 0.000 / 0.000 / 0.041 | unresolved |
| categorized/vw10 | 30.324 / 31.647 | 6.526 / 7.889 | 0.830 | 10.167 / 36.600 / 40.490 | m/s |
| categorized/vw11 | 31.263 / 38.181 | 5.236 / 8.844 | 0.957 | 11.832 / 42.595 / 42.810 | m/s |
| categorized/vw12 | 65.282 / 65.367 | 1.296 / 1.697 | 0.919 | 25.126 / 90.453 / 90.408 | unresolved |
| categorized/vw13 | 70.274 / 70.354 | 3.813 / 5.932 | 0.736 | 28.299 / 101.875 / 98.572 | unresolved |
| categorized/vw14a | 65.709 / 65.983 | 2.422 / 3.961 | 0.873 | 25.141 / 90.509 / 90.850 | m/s |
| categorized/vw14b | 54.650 / 56.927 | 1.990 / 3.099 | 0.990 | 21.005 / 75.617 / 75.655 | m/s |
| categorized/vw14c | 28.247 / 36.608 | 3.979 / 6.322 | 0.981 | 10.903 / 39.251 / 38.863 | m/s |
| categorized/vw15 | 0.081 / 0.102 | 0.081 / 0.102 | unavailable | 0.000 / 0.000 / 0.081 | unresolved |
| categorized/vw16a | 38.197 / 42.537 | 11.373 / 16.825 | 0.773 | 13.880 / 49.969 / 51.908 | m/s |
| categorized/vw16b | 44.101 / 46.306 | 8.145 / 12.714 | 0.827 | 16.544 / 59.560 / 60.452 | m/s |
| categorized/vw17 | 41.023 / 41.571 | 6.631 / 8.101 | 0.691 | 18.029 / 64.903 / 59.052 | m/s |
| categorized/vw2 | 48.781 / 52.681 | 3.098 / 6.063 | 0.976 | 18.741 / 67.467 / 67.323 | m/s |
| categorized/vw3 | 33.558 / 37.212 | 7.058 / 11.876 | 0.839 | 13.001 / 46.805 / 46.064 | m/s |
| categorized/vw4 | 44.299 / 50.899 | 5.166 / 8.825 | 0.967 | 16.977 / 61.117 / 60.994 | m/s |
| categorized/vw5 | 17.750 / 19.041 | 5.335 / 8.035 | 0.586 | 6.276 / 22.592 / 23.695 | m/s |
| categorized/vw6 | 22.608 / 23.463 | 6.098 / 8.868 | 0.487 | 7.744 / 27.880 / 30.336 | m/s |
| categorized/vw7 | 19.418 / 21.258 | 12.036 / 14.029 | 0.217 | 7.016 / 25.256 / 26.050 | unresolved |
| categorized/vw8 | 18.227 / 20.506 | 8.866 / 11.891 | 0.432 | 7.159 / 25.773 / 24.977 | m/s |
| categorized/vw9 | 20.035 / 21.122 | 7.747 / 14.375 | -0.208 | 8.462 / 30.462 / 27.485 | m/s |
| categorized/y1 | 24.079 / 28.936 | 19.558 / 25.526 | 0.080 | 8.484 / 30.542 / 29.982 | unresolved |
| uncategorized/m | 25.980 / 29.970 | 4.223 / 7.438 | 0.932 | 9.966 / 35.878 / 35.706 | m/s |
| uncategorized/s1 | 19.455 / 22.893 | 6.183 / 9.134 | 0.837 | 7.431 / 26.751 / 26.401 | m/s |
| uncategorized/s2 | 21.227 / 26.318 | 5.790 / 8.999 | 0.908 | 8.087 / 29.114 / 28.941 | m/s |
| uncategorized/s3a | 28.221 / 32.551 | 10.128 / 14.765 | 0.762 | 10.613 / 38.208 / 37.937 | m/s |
| uncategorized/s3b | 14.364 / 16.661 | 5.143 / 7.698 | 0.753 | 5.799 / 20.878 / 19.905 | m/s |
| uncategorized/s3c | 30.759 / 37.611 | 5.358 / 8.732 | 0.957 | 11.809 / 42.512 / 42.308 | m/s |
| uncategorized/s4 | 25.797 / 32.654 | 16.129 / 24.478 | 0.516 | 9.874 / 35.547 / 33.650 | unresolved |
| uncategorized/vfa01 | 42.519 / 46.363 | 3.430 / 5.589 | 0.976 | 16.419 / 59.109 / 58.818 | m/s |
| uncategorized/vfa02 | 62.860 / 65.188 | 2.789 / 4.996 | 0.978 | 24.170 / 87.011 / 86.954 | m/s |
| uncategorized/vta10 | 64.766 / 66.210 | 5.851 / 9.295 | 0.875 | 25.225 / 90.810 / 89.991 | m/s |
| uncategorized/vta11 | 35.355 / 39.452 | 10.516 / 15.551 | 0.768 | 15.219 / 54.790 / 50.574 | m/s |
| uncategorized/vta12 | 47.543 / 48.660 | 8.579 / 9.849 | 0.569 | 18.773 / 67.583 / 66.316 | m/s |
| uncategorized/vta13 | 63.971 / 64.315 | 5.875 / 7.694 | 0.463 | 23.375 / 84.148 / 87.346 | m/s |
| uncategorized/vta14 | 49.018 / 49.563 | 3.223 / 4.558 | 0.885 | 18.784 / 67.621 / 67.802 | m/s |
| uncategorized/vta15 | 51.686 / 51.841 | 1.728 / 2.397 | 0.902 | 19.884 / 71.583 / 71.570 | m/s |
| uncategorized/vta16 | 31.477 / 35.309 | 3.909 / 6.643 | 0.954 | 12.132 / 43.676 / 43.361 | m/s |
| uncategorized/vta17 | 24.261 / 26.680 | 6.140 / 9.473 | 0.801 | 8.777 / 31.597 / 32.686 | m/s |
| uncategorized/vta19 | 21.387 / 25.035 | 10.296 / 14.592 | 0.720 | 9.900 / 35.640 / 29.078 | m/s |
| uncategorized/vta1a | 41.614 / 44.420 | 12.001 / 16.105 | 0.659 | 15.684 / 56.463 / 57.096 | m/s |
| uncategorized/vta1b | 34.347 / 36.523 | 1.478 / 2.093 | 0.993 | 13.203 / 47.530 / 47.549 | m/s |
| uncategorized/vta2 | 26.217 / 29.386 | 1.017 / 1.645 | 0.996 | 10.010 / 36.037 / 36.191 | m/s |
| uncategorized/vta20 | 4.367 / 10.603 | 6.822 / 16.274 | 0.292 | 1.957 / 7.044 / 4.223 | unresolved |
| uncategorized/vta21 | 34.661 / 37.395 | 9.198 / 13.717 | 0.683 | 13.299 / 47.875 / 47.787 | m/s |
| uncategorized/vta22 | 27.415 / 28.375 | 4.054 / 5.434 | 0.831 | 10.787 / 38.834 / 38.202 | m/s |
| uncategorized/vta23 | 25.381 / 26.627 | 4.009 / 5.943 | 0.876 | 9.946 / 35.804 / 35.327 | m/s |
| uncategorized/vta24 | 15.956 / 22.555 | 6.343 / 10.219 | 0.878 | 6.130 / 22.068 / 21.350 | m/s |
| uncategorized/vta25 | 8.733 / 14.846 | 11.368 / 18.116 | 0.110 | 2.482 / 8.935 / 8.853 | unresolved |
| uncategorized/vta26 | 13.664 / 19.512 | 3.037 / 5.342 | 0.960 | 5.253 / 18.911 / 18.620 | m/s |
| uncategorized/vta27 | 33.625 / 36.015 | 11.721 / 16.758 | 0.605 | 11.067 / 39.843 / 44.656 | m/s |
| uncategorized/vta28 | 24.906 / 28.934 | 9.066 / 13.081 | 0.745 | 9.119 / 32.829 / 33.577 | m/s |
| uncategorized/vta29 | 29.379 / 33.902 | 10.705 / 14.545 | 0.790 | 10.826 / 38.972 / 39.579 | m/s |
| uncategorized/vta3 | 21.589 / 26.002 | 26.978 / 29.180 | -0.704 | 2.665 / 9.594 / 20.992 | unresolved |
| uncategorized/vta30 | 18.070 / 24.452 | 3.454 / 5.711 | 0.968 | 6.889 / 24.799 / 24.677 | m/s |
| uncategorized/vta4 | 29.017 / 29.900 | 3.986 / 7.551 | 0.488 | 11.178 / 40.243 / 40.103 | m/s |
| uncategorized/vta5 | 31.009 / 31.620 | 5.551 / 8.094 | 0.069 | 11.460 / 41.256 / 42.469 | m/s |
| uncategorized/vta6 | 49.242 / 50.360 | 3.676 / 5.064 | 0.943 | 18.627 / 67.056 / 67.869 | m/s |
| uncategorized/vta7 | 46.800 / 51.379 | 8.597 / 13.348 | 0.886 | 17.791 / 64.047 / 64.591 | m/s |
| uncategorized/vta8 | 24.482 / 30.273 | 4.883 / 7.594 | 0.952 | 9.424 / 33.927 / 33.326 | m/s |
| uncategorized/vta9 | 54.916 / 55.441 | 11.110 / 13.111 | 0.817 | 16.848 / 60.654 / 71.764 | m/s |
| uncategorized/vtb1 | 35.077 / 39.278 | 10.070 / 17.821 | 0.741 | 11.456 / 41.242 / 46.093 | m/s |
| uncategorized/vtb10 | 30.438 / 31.396 | 6.473 / 9.262 | 0.711 | 11.279 / 40.604 / 41.717 | m/s |
| uncategorized/vtb11 | 50.522 / 50.562 | 2.495 / 3.290 | 0.323 | 19.293 / 69.456 / 69.815 | unresolved |
| uncategorized/vtb12 | 30.302 / 32.014 | 6.099 / 7.957 | 0.831 | 12.472 / 44.900 / 42.775 | m/s |
| uncategorized/vtb2 | 20.053 / 24.364 | 4.948 / 7.497 | 0.921 | 7.472 / 26.900 / 27.189 | m/s |
| uncategorized/vtb3 | 2.448 / 5.303 | 1.526 / 2.711 | 0.929 | 0.568 / 2.043 / 2.963 | unresolved |
| uncategorized/vtb4 | 10.915 / 13.129 | 3.690 / 5.016 | 0.872 | 4.878 / 17.562 / 15.629 | m/s |
| uncategorized/vtb5 | 45.922 / 50.138 | 12.916 / 18.521 | 0.768 | 17.210 / 61.955 / 62.355 | m/s |
| uncategorized/vtb6 | 45.535 / 45.725 | 2.765 / 3.596 | 0.702 | 17.780 / 64.009 / 63.316 | unresolved |
| uncategorized/vtb7 | 40.796 / 41.931 | 5.834 / 7.628 | 0.743 | 15.608 / 56.190 / 56.405 | m/s |
| uncategorized/vtb8 | 50.612 / 50.700 | 1.872 / 2.669 | 0.655 | 19.194 / 69.097 / 69.805 | unresolved |
| uncategorized/vtb9 | 55.716 / 55.946 | 4.239 / 6.084 | 0.367 | 21.370 / 76.934 / 77.086 | m/s |
| uncategorized/vw1 | 0.041 / 0.047 | 0.041 / 0.047 | unavailable | 0.000 / 0.000 / 0.041 | unresolved |
| uncategorized/vw10 | 30.324 / 31.647 | 6.526 / 7.889 | 0.830 | 10.167 / 36.600 / 40.490 | m/s |
| uncategorized/vw11 | 31.263 / 38.181 | 5.236 / 8.844 | 0.957 | 11.832 / 42.595 / 42.810 | m/s |
| uncategorized/vw12 | 65.282 / 65.367 | 1.296 / 1.697 | 0.919 | 25.126 / 90.453 / 90.408 | unresolved |
| uncategorized/vw13 | 70.274 / 70.354 | 3.813 / 5.932 | 0.736 | 28.299 / 101.875 / 98.572 | unresolved |
| uncategorized/vw14a | 65.709 / 65.983 | 2.422 / 3.961 | 0.873 | 25.141 / 90.509 / 90.850 | m/s |
| uncategorized/vw14b | 54.650 / 56.927 | 1.990 / 3.099 | 0.990 | 21.005 / 75.617 / 75.655 | m/s |
| uncategorized/vw14c | 28.247 / 36.608 | 3.979 / 6.322 | 0.981 | 10.903 / 39.251 / 38.863 | m/s |
| uncategorized/vw15 | 0.081 / 0.102 | 0.081 / 0.102 | unavailable | 0.000 / 0.000 / 0.081 | unresolved |
| uncategorized/vw16a | 38.197 / 42.537 | 11.373 / 16.825 | 0.773 | 13.880 / 49.969 / 51.908 | m/s |
| uncategorized/vw16b | 44.101 / 46.306 | 8.145 / 12.714 | 0.827 | 16.544 / 59.560 / 60.452 | m/s |
| uncategorized/vw17 | 41.023 / 41.571 | 6.631 / 8.101 | 0.691 | 18.029 / 64.903 / 59.052 | m/s |
| uncategorized/vw2 | 48.781 / 52.681 | 3.098 / 6.063 | 0.976 | 18.741 / 67.467 / 67.323 | m/s |
| uncategorized/vw3 | 33.558 / 37.212 | 7.058 / 11.876 | 0.839 | 13.001 / 46.805 / 46.064 | m/s |
| uncategorized/vw4 | 44.299 / 50.899 | 5.166 / 8.825 | 0.967 | 16.977 / 61.117 / 60.994 | m/s |
| uncategorized/vw5 | 17.750 / 19.041 | 5.335 / 8.035 | 0.586 | 6.276 / 22.592 / 23.695 | m/s |
| uncategorized/vw6 | 22.608 / 23.463 | 6.098 / 8.868 | 0.487 | 7.744 / 27.880 / 30.336 | m/s |
| uncategorized/vw7 | 19.418 / 21.258 | 12.036 / 14.029 | 0.217 | 7.016 / 25.256 / 26.050 | unresolved |
| uncategorized/vw8 | 18.227 / 20.506 | 8.866 / 11.891 | 0.432 | 7.159 / 25.773 / 24.977 | m/s |
| uncategorized/vw9 | 20.035 / 21.122 | 7.747 / 14.375 | -0.208 | 8.462 / 30.462 / 27.485 | m/s |
| uncategorized/y1 | 24.079 / 28.936 | 19.558 / 25.526 | 0.080 | 8.484 / 30.542 / 29.982 | unresolved |

### M speed distributions

**Confirmed from data.** Same-row comparisons before any fitted lag:

| Series / hypothesis | Mean | Median | Maximum | MAE vs VBOX (km/h) | RMSE vs VBOX (km/h) | Correlation |
| --- | --- | --- | --- | --- | --- | --- |
| Smartphone raw, falsely treated as km/h | 9.966 | 10.190 | 27.020 | 25.980 | 29.970 | 0.93243 |
| Smartphone raw ×3.6 as km/h | 35.878 | 36.684 | 97.272 | 4.223 | 7.438 | 0.93243 |
| VBOX km/h | 35.706 | 36.443 | 100.688 | reference | reference | reference |

For every pair and hypothesis, `phase0_evidence.json` additionally records input/reference mean, median, minimum and maximum; signed and absolute error mean, median, p95 and maximum; sample count; regression slope/intercept. These figures include all finite paired rows and therefore quantify forward-fill/lag effects as well as units.

## Stationary gravity, acceleration, gyro and height

**Inferred with supporting evidence.** Stationary candidates use reference speed <0.5 km/h for a contiguous nominal 3 s window; this is an audit selection, not an on-device stationary detector. Reference faults and sensor latency may contaminate these windows. Acceleration tests compare raw and ×9.80665 vehicle longitudinal acceleration with the reference speed derivative (11-row smoothing). Turn tests compare ±vehicle lateral acceleration ×9.80665 against v×yaw-rate. Gyro scale tests compare each raw channel, both signs, under rad/s and deg/s hypotheses against vehicle yaw rate. No frame is selected or changed.

| Pair | Stationary rows | Accel / gravity norm means (m/s²) | Residual minus / plus gravity means | Long accel MAE raw / ×g0 (m/s²) | Best rad/s channel (diagnostic) | Gyro MAE rad/s hypothesis / deg/s hypothesis (deg/s) | Height MAE as m / as km (m) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| categorized/m | 7863 | 9.876 / 9.807 | 0.411 / 19.673 | 0.421 / 0.240 | +1 gyro_channel_pitch | 3.969 / 3.957 | 51.253 / 106,989.076 |
| categorized/s1 | 3475 | 9.870 / 9.807 | 0.336 / 19.673 | 0.460 / 0.250 | +1 gyro_channel_pitch | 1.992 / 3.895 | 50.638 / 123,080.075 |
| categorized/s2 | 8649 | 9.903 / 9.807 | 0.530 / 19.693 | 0.443 / 0.194 | -1 gyro_channel_roll | 5.161 / 3.778 | 51.536 / 104,967.678 |
| categorized/s3a | 1650 | 9.879 / 9.807 | 0.527 / 19.669 | 0.395 / 0.196 | +1 gyro_channel_yaw | 3.889 / 3.330 | 47.278 / 107,073.985 |
| categorized/s3c | 1975 | 9.857 / 9.807 | 0.337 / 19.657 | 0.448 / 0.244 | +1 gyro_channel_pitch | 1.183 / 3.387 | 45.842 / 107,177.368 |
| categorized/vfa01 | 442 | 9.942 / 9.807 | 1.014 / 19.696 | 0.403 / 0.183 | -1 gyro_channel_yaw | 4.891 / 2.127 | 48.396 / 101,275.461 |
| categorized/vta1a | 43 | 10.145 / 9.807 | 1.929 / 19.845 | 0.433 / 0.190 | -1 gyro_channel_yaw | 5.377 / 2.536 | 50.123 / 96,893.526 |
| categorized/vta25 | 115 | 9.863 / 9.807 | 0.436 / 19.664 | 0.560 / 0.263 | +1 gyro_channel_pitch | 4.679 / 4.981 | 50.761 / 212,930.209 |
| categorized/vtb1 | 1493 | 9.954 / 9.807 | 1.628 / 19.661 | 0.509 / 0.455 | +1 gyro_channel_yaw | 5.137 / 2.484 | 67.196 / 237,617.041 |
| categorized/vw1 | 20446 | 9.854 / 9.807 | 0.371 / 19.657 | 0.041 / 0.403 | -1 gyro_channel_yaw | 0.630 / 0.069 | 95.844 / 95,843.746 |
| categorized/vw15 | 1288 | 9.852 / 9.807 | 0.260 / 19.656 | 0.016 / 0.091 | -1 gyro_channel_yaw | 0.373 / 0.039 | 77.951 / 69,621.128 |
| categorized/y1 | 6393 | 9.915 / 9.807 | 0.793 / 19.702 | 0.462 / 0.246 | -1 gyro_channel_roll | 4.545 / 3.426 | 41.702 / 104,794.388 |

**Inferred with supporting evidence.** The highest-correlation gyro channel below is a scale diagnostic, not a proposed frame mapping. Near-zero yaw channels can win an RMSE ranking without representing yaw. In S1 and S3c, the Pitch-labelled channel's rad/s-to-deg/s slope is near one, whereas treating its raw values as deg/s yields a slope near 0.017. This supports rad/s there. The registry lists qualifying evidence per channel (correlation >=0.6, slope 0.4-1.6, reference yaw standard deviation >=2 deg/s, and rad/s RMSE <95% of the deg/s-hypothesis RMSE). Other channels remain empirically unresolved.

| Pair | Highest-correlation channel | Correlation | Slope rad/s hypothesis | Slope deg/s hypothesis | RMSE rad/s hypothesis (deg/s) | RMSE deg/s hypothesis (deg/s) |
| --- | --- | --- | --- | --- | --- | --- |
| categorized/m | +1 gyro_channel_pitch | 0.63635 | 0.710 | 0.012 | 8.139 | 8.936 |
| categorized/s1 | +1 gyro_channel_pitch | 0.93476 | 0.984 | 0.017 | 2.903 | 7.580 |
| categorized/s2 | -1 gyro_channel_roll | 0.00524 | 0.003 | 0.000 | 8.921 | 7.791 |
| categorized/s3a | +1 gyro_channel_pitch | 0.19993 | 0.207 | 0.004 | 9.296 | 7.223 |
| categorized/s3c | +1 gyro_channel_pitch | 0.94901 | 0.961 | 0.017 | 2.131 | 6.514 |
| categorized/vfa01 | -1 gyro_channel_pitch | 0.02230 | 0.087 | 0.002 | 15.937 | 4.006 |
| categorized/vta1a | -1 gyro_channel_pitch | 0.01538 | 0.067 | 0.001 | 20.100 | 4.552 |
| categorized/vta25 | +1 gyro_channel_pitch | 0.77321 | 1.030 | 0.018 | 7.212 | 8.994 |
| categorized/vtb1 | +1 gyro_channel_pitch | 0.16556 | 0.623 | 0.011 | 17.532 | 4.666 |
| categorized/vw1 | +1 gyro_channel_pitch | 0.06867 | 1.792 | 0.031 | 1.685 | 0.088 |
| categorized/vw15 | -1 gyro_channel_roll | 0.02236 | 0.437 | 0.008 | 1.112 | 0.068 |
| categorized/y1 | +1 gyro_channel_pitch | 0.05342 | 0.067 | 0.001 | 11.376 | 7.301 |

**Inferred with supporting evidence.** Multiplying longitudinal acceleration by g0 reduces speed-derivative MAE in 59/72 categorized pairs. This supports g in many sequences, but the failures rule out claiming universal calibrated consistency. Smoothing excludes invalid-timestamp windows and boundary windows. Lateral results below test the g hypothesis against v×yaw rate using both possible signs; turn geometry, frame and latency remain relevant.

| Pair | Lateral +g / -g MAE (m/s²) | +g correlation | Mean height difference VBOX minus phone (m), metre hypothesis |
| --- | --- | --- | --- |
| categorized/m | 0.224 / 1.198 | 0.968 | -51.253 |
| categorized/s1 | 0.241 / 0.846 | 0.907 | -50.638 |
| categorized/s2 | 0.236 / 0.899 | 0.921 | -50.944 |
| categorized/s3a | 0.227 / 0.893 | 0.924 | -47.278 |
| categorized/s3c | 0.244 / 1.025 | 0.929 | -45.327 |
| categorized/vfa01 | 0.253 / 0.968 | 0.929 | -48.396 |
| categorized/vta1a | 0.253 / 1.099 | 0.930 | -50.123 |
| categorized/vta25 | 0.333 / 0.605 | 0.709 | -50.761 |
| categorized/vtb1 | 0.262 / 0.930 | 0.900 | -67.196 |
| categorized/vw1 | 0.314 / 0.314 | -0.017 | 95.844 |
| categorized/vw15 | 0.185 / 0.185 | -0.004 | -77.951 |
| categorized/y1 | 0.227 / 0.918 | 0.925 | -41.558 |

**Confirmed from data.** Additional unpaired schema representatives have these raw acceleration/gravity magnitudes. **Still unresolved.** Their low-speed selection relies on the phone itself, not an independent stationary reference.

| Unpaired schema representative | All-row accel norm mean | All-row gravity norm mean | Phone-low-speed accel norm mean |
| --- | --- | --- | --- |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-A4.csv | 9.827 | 9.807 | 9.815 |
| Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/S-T1.csv | 9.731 | 9.807 | unavailable |

**Still unresolved.** Rad/s consistency is assessed per channel and sequence; it cannot establish all gyro axes. Height magnitudes can reject an implausible kilometre interpretation in representative files but cannot resolve vertical datum, antenna offset, or validate 3-D ground truth. Vehicle satellite values and accelerator-pedal values must not be treated as literal satellite counts or binary flags without decoding. The paper's gyro table itself repeats Pitch; source labels do not settle conventions.

**Stated by dataset documentation.** README_1.pdf pages 2-5, Tables 3-4: smartphone sensors 10 Hz, smartphone GPS 1 Hz, VBOX/vehicle velocity km/h, accelerations g, gyros rad/s, height labelled km. The paper's Table 3 identifies accelerator pedal as percent, while the CSV header labels it 0/1. Original source labels are retained even where contradicted.
