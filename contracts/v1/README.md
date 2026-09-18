# Navigation exchange contract 1.0.0 — codecs implemented, engine not implemented

This defines the **new Android/core boundary**, not the unresolved IO-VNBD export
semantics. `golden.json` is a synthetic interoperability fixture. It is not a
recorded trip, calibration approval, physically consistent time series or training
example. Python and Kotlin typed models and strict codecs are implemented here.
The contract is not yet integrated into acquisition or a navigation algorithm.
`golden_records.jsonl` expresses the original fixture as single-event envelopes;
`edge_records.jsonl` adds missing optional data, all IMU sensor types and Int64
boundaries. Both languages also consume the same invalid-record corpus.

## Envelope and wire rules

UTF-8 JSON, finite IEEE-754 binary64 numeric values, no NaN/Infinity. Objects have
unique keys. All defined fields are required; optional values use explicit JSON
null rather than omission, zero, empty strings or sentinel coordinates.
Arrays have fixed lengths as specified below. Booleans are JSON booleans.

The production envelope has exactly `contract_version` (string `1.0.0`), nonempty
`session_id`, `source` (`real`, `simulation`, `replay_real`, `replay_simulation`)
and `event` (one event object).
Every event has nonempty session-unique decimal-string `event_id`, `type`, `t_ns`,
`received_ns` and `data`. IDs distinguish different sensors at the same instant.
The legacy `golden.json` envelope uses an `events` array for tests only; production
readers intentionally reject that shape. JSON Lines contains one complete
version/session/source/event envelope per line. There is no production batch
array reader. Single-document JSON reads/writes exactly one bounded envelope.

Event IDs and both timestamps use canonical decimal strings (`0` or a nonzero
digit followed by digits): no sign, whitespace, exponent or leading zeroes.
Both timestamps are nonnegative base-10 integer strings within signed Int64 range;
decode to Python int / Kotlin Long, never via Double. The fixture intentionally
uses values above 2^53. Preserve timestamps exactly. `t_ns` is measurement time,
`received_ns` is callback receipt time, and received time must not precede event
time. Both use Android elapsed-realtime nanoseconds since boot, not wall time.
Session metadata must record the boot/session clock identity; never compare
monotonic times across devices or reboots. Preserve source timestamps on replay
and use a separate playback clock. GNSS wall time is nullable `utc_ms` in integer
milliseconds since Unix epoch; it is metadata, never the integration clock.

GNSS must use the fix's elapsed-realtime timestamp, not callback wall time.
Subtract an explicit integer origin before converting durations to floating-point
seconds. Every sensor keeps its own timestamp. Equal times across sensor types
are allowed; duplicate event IDs are rejected. Acquisition arrival order must
be preserved in recording; the future engine sorts a bounded 100 ms buffer by
measurement time then event ID and emits `LATE_MEASUREMENT` instead of retroactively
changing emitted states. This 100 ms policy is proposed and needs device validation.

The future acquisition target is 100 Hz accelerometer/gyro and 1 Hz GNSS where
supported, not a guarantee. Record requested and measured rates separately.
Gravity/magnetic events are optional, asynchronous and not resampled implicitly.
The current UI demo runs at a 10 Hz target and does NOT implement this pipeline.
Report IMU gaps >100 ms and do not propagate across >500 ms without reinitializing;
these are proposed safety thresholds, not dataset synchronization corrections.

Unknown major/minor versions, event types, enum values, missing keys and unknown
keys are rejected by a strict 1.0.0 consumer. Any field/meaning change requires
a version change; readers explicitly opt into versions. No silent unit guessing.
JSON object order/whitespace is immaterial. Ordered arrays and events are not.
All version differences, including patch changes, are rejected. Integer-valued
JSON fields (`satellites_used`, `utc_ms`, `dropped_count`) must use integer tokens
in [0, 9223372036854775807], not `1.0`, `1e0`, booleans or quoted numbers.

## Physical frames

- `android_device`: right-handed; X toward the screen's right, Y toward its top,
  Z out of the screen in the device's natural orientation. Screen rotation never
  remaps recorded physical axes. Positive rotation follows the right-hand rule.
- `vehicle`: right-handed FLU: X forward, Y left, Z up. Positive yaw is left turn.
- `enu`: right-handed East, North, Up. Yaw is CCW from East; course/heading is
  clockwise from North. ENU yaw = wrap(pi/2 - heading_deg*pi/180).
- Accelerometer carries specific force: a resting, screen-up device reports
  approximately +g on Z. Gravity-sensor vectors follow Android's supplied-gravity
  convention (+g on Z in that pose). Gyro uses rad/s; magnetometer uses microtesla.
  Gravitational acceleration in ENU is [0,0,-9.80665] m/s² as an explicit nominal
  constant, not a measured local gravity estimate. Do not double-subtract gravity.
- Quaternions are scalar-first `[w,x,y,z]`, active source-to-destination rotations
  on column vectors. Only unit quaternions/proper rotations are permitted. q and
  -q represent the same rotation. No arbitrary reflection/sign permutation.
- Geographic inputs are WGS84 degrees; position and velocity outputs are ENU
  metres and m/s relative to an explicit WGS84 origin with ellipsoidal altitude.
  IO-VNBD world-like export channels must not be relabelled as Android device data.

## Event data fields (all required, null only where explicitly stated)

`imu`:

- `sensor`: accelerometer, gyroscope, gravity or magnetometer.
- `frame`: exactly android_device. `xyz`: three finite numbers, never null.
- `unit`: m/s^2 for accelerometer/gravity, rad/s for gyro, uT for magnetometer.
- `accuracy`: unknown, unreliable, low, medium or high; unknown is not high.
- Missing hardware emits a diagnostic, not invented zero measurements. Rotation
  vectors are outside 1.0.0 and require a later explicit event definition.

`gnss`:

- `latitude_deg` [-90,90], `longitude_deg` [-180,180]; (0,0) is not a generic
  invalid sentinel in this new contract. No valid fix means no GNSS measurement.
- `altitude_m`: finite or null; `altitude_reference`: ellipsoid, msl, unknown or
  null. Both are null together when altitude is absent. Never merge MSL and
  ellipsoidal altitude without an explicit geoid conversion.
- `speed_m_s`: nonnegative or null; `bearing_deg`: [0,360) or null. Missing speed
  is not zero; missing course is not north. Bearing is course, not phone yaw.
- `horizontal_accuracy_m`, `vertical_accuracy_m`: nonnegative or null, provider
  reported 68% uncertainty estimates, not guaranteed errors.
- `satellites_used`: nonnegative integer or null; `provider`: nonempty string;
  `utc_ms`: nonnegative integer or null. No coordinate/identity data in diagnostics.

`calibration`:

- `id`: nonempty string; `status`: pending, valid, invalid or expired.
- `q_vehicle_from_device_wxyz`: 4-element unit quaternion or null.
- `gyro_bias_rad_s`: three device-frame values or null;
  `accelerometer_bias_m_s2`: three device-frame values or null.
- `confidence`: [0,1] or null; this is a calibration-quality score, NOT a calibrated
  probability. A valid result requires a quaternion and gyro bias; unavailable
  accelerometer bias stays null. Event `t_ns` is when calibration became available;
  it cannot initialize earlier states. Reset on remount/session change.

`navigation`:

- `status`: uninitialized, calibrating, tracking, degraded or failed.
- `initialization_mode`: evaluation or deployable; must never change silently.
- `origin_wgs84_deg_m`: [latitude,longitude,ellipsoidal altitude] or null.
- `position_enu_m`, `velocity_enu_m_s`: three finite values or null.
- `q_enu_from_vehicle_wxyz`: four-element unit quaternion or null.
- `heading_deg`: [0,360) or null; `calibration_id`: nonempty string or null.
- `gnss_used_after_initialization`: boolean, including for evaluation runs.
- Tracking requires origin, position, velocity, quaternion and calibration ID;
  unavailable states use nulls. A numeric position alone must not imply accuracy.
- Uninitialized, calibrating and failed states require all spatial fields,
  heading and calibration ID to be null. Degraded states may carry partial data;
  any position or velocity still requires an origin. The stream must not switch
  initialization mode; start a new stream/session for another mode.

`gnss_quality`:

- `state`: unavailable, acquiring, good, degraded, stale or denied.
- `fix_age_s`: nonnegative or null; `satellites_used`: nonnegative integer or null.
- `reasons`: array of nonempty stable reason-code strings (empty allowed).
- Quality thresholds belong to a separately versioned, measured policy. Until
  validated, a synthetic example does not qualify a real fix as good.

`confidence`:

- `state`: unavailable, unvalidated or calibrated.
- `probability`: [0,1] or null; must be null unless state is calibrated.
- `horizontal_accuracy_95_m`: nonnegative or null; `speed_std_m_s`: nonnegative
  one-standard-deviation estimate or null. These are distinct from provider GNSS
  68% accuracy. Do not convert one into another without a stated error model.

`diagnostic`:

- `severity`: info, warning or error; `code`: nonempty stable uppercase code;
  `message`: nonempty human-readable text; `dropped_count`: nonnegative integer.
- Initial codes: SIMULATED_INPUT, SENSOR_MISSING, PERMISSION_DENIED,
  INVALID_MEASUREMENT, DUPLICATE_EVENT, LATE_MEASUREMENT, TIME_GAP,
  CALIBRATION_REQUIRED, QUEUE_OVERFLOW, RECORDING_IO_ERROR, ENGINE_FAILURE.
- Diagnostics never replace required error-state transitions or secretly fill gaps.
- Diagnostic and GNSS reason codes are extensible identifiers matching
  `[A-Z][A-Z0-9_]*`, not a closed enum. Other enums above are closed.

## Numerical acceptance and integration boundary

Integers, IDs, strings, enums, nulls and booleans compare exactly. For fixture
binary64 values use absolute error <=1e-12, relative tolerance zero. Future Android
Float-to-Double acquisition comparisons may use 1e-6 in SI components, documented
per field; do not apply that tolerance to latitude/longitude. Quaternion norm and
R-transpose-R identity / determinant +1 errors must be <=1e-6. Rotations compare
up to quaternion sign. These are serialization/math tolerances, not INS accuracy
or timestamp-alignment approval thresholds.

UI-independent `NavigationEngine` interface: initialize(session, calibration,
initializationMode), acceptImu(measurement), acceptGnss(measurement), drain ordered
navigation/quality/confidence/diagnostic outputs, stop/reset. It must import no
Compose, Android UI or map SDK types. The Python Protocol and Kotlin interface
are present; the engine is **not implemented**. `EngineSession` carries the header,
boot identity and integer clock origin; it is configuration, not an eighth event.
Calls carry typed event records so timestamps/source/session labels are retained.
Future implementations must enforce input kind, causal calibration availability,
calibration-reference ownership and queue/ordering rules. Codec validation does
not certify these algorithm-level rules or implement them on the engine's behalf.
GNSS input acceptance is not permission to implement a GNSS correction/EKF now.

Proposed replaceable map boundary: accept WGS84 positions and styled trails only;
no filtering/calibration logic in the renderer. Offline mode must work without a
map (numeric state/trail), with separately licensed downloadable regions possible
later. No map provider, caching or offline tiles are implemented or certified.

## Implemented codec APIs and limits

Python: `contracts.v1.models` contains frozen dataclasses and enums;
`contracts.v1.codec` exposes `decode_json`, `encode_json`, `read_json`, `write_json`,
`read_jsonl`, `write_jsonl`, `Limits`, `ContractError` and `validate_rotation`.
Kotlin: `com.intelligentdeadreckoning.contracts.v1` provides equivalent data
classes/enums, `Codec.decodeJson/encodeJson/readJson/writeJson/readJsonl/writeJsonl`,
`Limits`, `ContractException` and `validateRotation`.

Validation happens at both codec boundaries, not in DTO constructors. Invalid
typed values are rejected before that record is emitted. Kotlin uses Gson 2.11.0
in strict token mode, not reflective DTO coercion. Python uses only the standard
library. The sources have no Android/Compose/map dependency; the mobile Gradle
Kotlin source set includes this directory solely to compile and test them.

- Defaults: 65,536 UTF-8 bytes per record excluding LF/CRLF, 1,000,000 records per
  stream, nesting depth at most 16, text at most 2,048 UTF-16 code units, and at
  most 64 GNSS reason codes. Text must be nonblank and valid Unicode.
- `Limits` can explicitly change byte/count caps. Duplicate detection retains
  IDs up to the count cap, not full measurements. Rotate to a new session before
  exceeding the cap; never silently disable duplicate detection for long trips.
- Each JSONL stream has one immutable header and unique event IDs. Equal sensor
  timestamps are allowed. Arrival order is preserved; these codecs never reorder,
  interpolate, fuse, convert units or change real/simulation/replay labels.
- Readers are lazy and fail fast; Python returns an iterator, Kotlin a single-use
  Sequence. Single JSON input is a bounded document. Streams are synchronous,
  caller-owned binary streams: callers close/flush them, use buffered file streams
  (especially Kotlin JSONL), and run I/O off the UI thread. No hidden worker or
  file access occurs on import. Nonblocking streams are not supported.
- JSONL accepts LF and CRLF. A complete final record without LF is accepted;
  a truncated final object, blank line, BOM, comment, trailing JSON value,
  duplicate object key, invalid UTF-8 or nonfinite number is rejected.
- A later failure does not undo already yielded/written valid records. An I/O
  failure can leave a partial final line, which readers reject. These are streaming
  codecs, not atomic session writers, trip recorders or app replay controllers.
- Errors expose `code`, schema `path` and one-based JSONL `line`; payload values
  and underlying I/O exception messages are omitted. Codes include INVALID_VERSION,
  INVALID_KEYS, INVALID_TYPE, INVALID_ENUM, INVALID_SHAPE, OUT_OF_RANGE, NONFINITE,
  INVALID_ROTATION, INVARIANT, DUPLICATE_KEY, DUPLICATE_EVENT, SESSION_MISMATCH,
  MALFORMED_JSON, INVALID_UTF8, INVALID_UNICODE, RESOURCE_LIMIT and IO_ERROR.
  Python additionally reports INVALID_MODEL for incorrect runtime DTO types.
  Syntax/duplicate-key errors may identify `$` rather than a field path.
- Quaternion components/sign are preserved, not normalized or canonicalized.
  The separate matrix validator rejects determinant -1, nonorthogonality and
  nonfinite values. Matrices are not a wire alternative to four quaternion values.

Python streaming usage (the caller chooses paths and owns the files):

```python
from contracts.v1.codec import read_jsonl, write_jsonl

with open("input.jsonl", "rb") as source, open("copy.jsonl", "wb") as target:
    write_jsonl(read_jsonl(source), target)
```

Equivalent Kotlin usage:

```kotlin
inputFile.inputStream().buffered().use { source ->
    outputFile.outputStream().buffered().use { target ->
        Codec.writeJsonl(Codec.readJsonl(source), target)
    }
}
```

Never pass the same file as input and output. Neither example remaps IO-VNBD
exports: those remain outside the raw Android device-frame contract.

## Reproduce tests and actual bidirectional interoperability

From the repository root in PowerShell, using the existing Python environment
and Android SDK/JDK configuration:

```powershell
.venv\Scripts\python.exe -B -m unittest discover -s tests -v
.venv\Scripts\python.exe -B -m contracts.v1.interop export mobile/app/build/contract_interop/python.jsonl
$env:IDR_CONTRACT_PYTHON_JSONL = Join-Path $PWD 'mobile/app/build/contract_interop/python.jsonl'
Push-Location mobile
# Use the repository's configured SDK/JDK; this workstation uses Android Studio's JBR.
$env:GRADLE_USER_HOME = Join-Path $PWD '.gradle-user-home'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --console=plain --rerun-tasks
Pop-Location
.venv\Scripts\python.exe -B -m contracts.v1.interop verify mobile/app/build/contract_interop/kotlin.jsonl
```

Offline Gradle requires the existing cached dependencies; on first setup install
the SDK/dependencies using the mobile setup instructions. `--rerun-tasks` ensures
the external Python file is consumed even if Gradle considers tests up to date.
The bridge exports only synthetic fixtures to an explicitly supplied build path.
The Kotlin test compares Python-produced data with 17 canonical typed records,
writes them back, then Python checks all values. Without the environment variable,
Kotlin still runs standalone fixture tests but does not claim a Python-writer run.
Cross-language numeric values currently match exactly, stricter than the stated
1e-12 tolerance; key order/number spelling are not required to be byte-identical.

These tests validate the exchange layer, not recorder/replay UI, acquisition,
INS physics, Python/Android preprocessing equivalence or readiness for AI/EKF.
