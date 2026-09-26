# PS26168 — Canonical Internal Data Model

**Scope:** Define the normalized, versioned representation used across Android acquisition, recording, replay, future navigation, map presentation, evaluation, and edge deployment. Field-level schema only. Derived ML features, model architecture, EKF equations, and map-matching algorithms are deliberately deferred.

---

## 0. Design Rationale

The project should not flatten every concept into one giant row. The current contract already separates:

1. **Record envelope**
2. **IMU measurement**
3. **GNSS measurement**
4. **Calibration result**
5. **Navigation state**
6. **GNSS quality**
7. **Confidence**
8. **Diagnostic event**
9. **Recording metadata**

This prevents missing GNSS data from contaminating IMU records, provider accuracy from becoming fused confidence, replay provenance from being lost, calibration from being hidden, and map logic from defining navigation truth.

---

## 1. Common Record Envelope

### Header

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `contract_version` | string | yes | Exact supported version, currently `1.0.0` |
| `session_id` | non-empty string | yes | Stream identity |
| `source` | enum | yes | `real`, `simulation`, `replay_real`, `replay_simulation` |

Source rules:
- `real`: live physical acquisition.
- `simulation`: canonical simulation.
- `replay_real`: replay of stored real data.
- `replay_simulation`: replay of stored simulation.

### Event

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `event_id` | canonical decimal string | yes | Session-unique identity |
| `t_ns` | nonnegative Int64 / decimal string on wire | yes | Measurement/state time |
| `received_ns` | nonnegative Int64 / decimal string on wire | yes | Receipt/publication time |
| `data` | typed payload | yes | One event payload |

Rules:
- exact Int64 preservation,
- no floating-point timestamp conversion,
- equal timestamps across different events allowed,
- duplicate `event_id` invalid,
- session/source/version immutable.

---

## 2. IMU Measurement

| Field | Type | Meaning |
|---|---|---|
| `sensor` | enum | accelerometer / gyroscope / gravity / magnetometer |
| `frame` | enum | currently `android_device` |
| `unit` | enum | `m/s^2`, `rad/s`, `uT` |
| `xyz` | finite Vector3 | sample components |
| `accuracy` | enum | unknown / unreliable / low / medium / high |

### Android device frame
- +X screen right
- +Y screen top
- +Z out of screen
- right-handed

Screen rotation does not remap recorded physical axes.

### Dataset boundary
IO-VNBD CSV exports are not automatically equivalent to this Android `android_device` contract. Source adapters must prove the mapping before representing dataset channels as canonical raw device-frame IMU.

---

## 3. GNSS Measurement

| Field | Type | Meaning |
|---|---|---|
| `latitude_deg` | [-90,90] float | WGS84 latitude |
| `longitude_deg` | [-180,180] float | WGS84 longitude |
| `altitude_m` | float/null | altitude |
| `altitude_reference` | enum/null | ellipsoid / MSL / unknown |
| `speed_m_s` | nonnegative float/null | provider speed |
| `bearing_deg` | [0,360) float/null | provider bearing/course |
| `horizontal_accuracy_m` | nonnegative float/null | provider uncertainty |
| `vertical_accuracy_m` | nonnegative float/null | provider uncertainty |
| `satellites_used` | integer/null | satellite count where valid |
| `provider` | string | GPS/network/etc |
| `utc_ms` | integer/null | wall-time metadata |

Missing speed/bearing is null, not zero. No fix means no fabricated GNSS event.

---

## 4. Calibration Result

| Field | Type | Meaning |
|---|---|---|
| `id` | string | calibration identity |
| `status` | enum | pending / valid / invalid / expired |
| `q_vehicle_from_device_wxyz` | unit quaternion/null | device→vehicle rotation |
| `gyro_bias_rad_s` | Vector3/null | gyro bias |
| `accelerometer_bias_m_s2` | Vector3/null | accelerometer bias |
| `confidence` | [0,1]/null | calibration-quality score |

Vehicle frame: FLU — X forward, Y left, Z up.

Calibration cannot be backdated. A remount invalidates the current calibration.

---

## 5. Navigation State

| Field | Type | Meaning |
|---|---|---|
| `status` | enum | uninitialized / calibrating / tracking / degraded / failed |
| `initialization_mode` | enum | evaluation / deployable |
| `origin_wgs84_deg_m` | GeoOrigin/null | geographic origin |
| `position_enu_m` | Vector3/null | local ENU position |
| `velocity_enu_m_s` | Vector3/null | ENU velocity |
| `q_enu_from_vehicle_wxyz` | quaternion/null | vehicle→ENU attitude |
| `heading_deg` | float/null | heading |
| `calibration_id` | string/null | calibration provenance |
| `gnss_used_after_initialization` | bool | GNSS-aiding provenance |

`evaluation` and `deployable` must not silently mix.

---

## 6. GNSS Quality State

| Field | Type | Meaning |
|---|---|---|
| `state` | enum | unavailable / acquiring / good / degraded / stale / denied |
| `fix_age_s` | float/null | age of relevant fix |
| `satellites_used` | integer/null | valid satellite count |
| `reasons` | list[str] | stable reason codes |

GNSS quality is input quality, not navigation quality.

---

## 7. Confidence

| Field | Type | Meaning |
|---|---|---|
| `state` | enum | unavailable / unvalidated / calibrated |
| `probability` | float/null | null unless genuinely calibrated |
| `horizontal_accuracy_95_m` | float/null | fused-navigation 95% horizontal uncertainty |
| `speed_std_m_s` | float/null | speed uncertainty |

Android provider accuracy must not be renamed as this fused confidence.

---

## 8. Diagnostic Event

| Field | Type | Meaning |
|---|---|---|
| `severity` | enum | info / warning / error |
| `code` | stable uppercase string | machine-readable condition |
| `message` | string | human-readable description |
| `dropped_count` | integer | loss count if relevant |

Diagnostics record problems; they do not silently repair them.

---

## 9. Engine Session

`EngineSession` is configuration, not an event.

| Field | Meaning |
|---|---|
| `header` | immutable stream identity |
| `boot_id` | monotonic clock identity |
| `origin_ns` | session origin |

---

## 10. Recording Metadata

Stored once per recording:
- recording ID,
- acquisition session ID,
- original source,
- start/end/completion/recovery state,
- UTC metadata,
- clock identity,
- device/OS/application metadata,
- sensor descriptors/rates,
- permission/source configuration,
- calibration application state,
- record count,
- channel counts.

Canonical files:

```text
metadata.json
measurements.jsonl
```

---

## 11. Relationships

```text
RecordingMetadata
   └── acquisitionSessionId
            │
            ▼
       Record stream
       ├── IMU
       ├── GNSS
       ├── Calibration
       ├── Navigation
       ├── GNSS Quality
       ├── Confidence
       └── Diagnostic

CalibrationResult.id
       └──── referenced by NavigationState.calibration_id

NavigationState.origin + position_enu
       ↓
NavigationPresentation
       ↓
WGS84 MapPoint
```

---

## 12. Future Structures — Not Yet Frozen

### Localization mode
Potential future enum:

```text
GNSS
DR
FUSED
RECOVERY
```

Current v1 has no explicit field. Do not overload `status` or `gnss_used_after_initialization`.

### Model provenance
When AI is deployed:
- model ID/version,
- SHA-256,
- preprocessing schema/version,
- normalization version,
- training-manifest ID.

### Map-matched state
Do not overwrite raw fused position without provenance. Future fields may include:
- raw position,
- matched position,
- matched edge ID,
- match confidence,
- matcher version.

### Route state
Routing is separate:
- route ID,
- polyline,
- distance remaining,
- maneuver,
- off-route,
- reroute state.

---

## 13. Training / Evaluation Schema Rules

Candidate deployable inputs, after validation:
- accelerometer,
- gyroscope,
- optional gravity/magnetometer,
- causal calibration state,
- causal GNSS quality/context where allowed.

Reference-only:
- VBOX lat/lon,
- VBOX speed,
- VBOX heading,
- VBOX vehicle dynamics.

Never leak reference fields into deployable inference.

---

## 14. Coordinate Systems

- Android device: X right, Y top, Z out.
- Vehicle: FLU.
- Navigation: ENU.
- Geography: WGS84.
- Quaternion: scalar-first `[w,x,y,z]`, proper rotation only.

---

## 15. Serialization Rules

- UTF-8 JSON/JSONL
- finite numeric values
- explicit nulls
- exact Int64 timestamps
- no unit guessing
- no implicit resampling/interpolation
- strict version support
- bounded streaming readers
- replay lineage preserved

---

## 16. Current Status

| Element | Status |
|---|---|
| v1 typed models | implemented Python + Kotlin |
| strict codecs | implemented |
| recording metadata | implemented |
| app recording/replay | implemented |
| NavigationEngine interface | implemented |
| engine implementation | not implemented |
| localization-mode field | not in v1 |
| model provenance | future |
| map-match output schema | future |
| routing schema | future |
| edge transport schema | future |

**No feature engineering or model design is performed here.**
