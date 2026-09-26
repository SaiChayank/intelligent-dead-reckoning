# PS26168 — Focused Security & Privacy Review

**Scope:** Security/privacy controls relevant to the architecture already designed: Android acquisition, location data, recording/export/replay, model artifacts, offline maps, future navigation engine, and optional edge deployment. This is a review/classification exercise, not a redesign.

---

## Classification Summary

| # | Control | Classification | Rationale |
|---|---|---|---|
| 1 | Least-privilege Android permissions | **MVP** | Location and sensors are sensitive; request only what the foreground feature needs |
| 2 | No mandatory Internet permission / offline runtime | **MVP** | Prevents hidden cloud/tile/API dependency and reduces location-data exposure |
| 3 | Foreground-only acquisition policy | **MVP** | Current design; avoids silent background location/sensor collection |
| 4 | App-private recording storage | **MVP** | Raw IMU/GNSS contains sensitive movement history |
| 5 | Android backup disabled for sensitive sessions | **MVP** | Prevents unintended cloud/device backup of recordings |
| 6 | Explicit user-triggered export | **MVP** | User controls when private recordings leave app storage |
| 7 | Local export-destination validation | **MVP** for current policy | Current design intentionally avoids cloud-provider export destinations |
| 8 | Strict contract/input validation | **MVP** | Prevent malformed records from poisoning navigation/filter state |
| 9 | Replay/session path validation | **MVP** | Prevent path traversal/invalid session access |
| 10 | ZIP/import validation | **NOT APPLICABLE currently** | App exports ZIP but does not currently import arbitrary ZIP; becomes MVP if import is added |
| 11 | Model integrity hashing | **MVP once models deploy** | Navigation must not load modified model artifacts silently |
| 12 | Dataset/experiment integrity hashing | **MVP for final evaluation** | Protects training/test reproducibility |
| 13 | Offline map-pack integrity | **MVP** | Corrupt/tampered map/style should be detected before use |
| 14 | Dependency vulnerability review | **MVP** | Android/Python/MapLibre/ML dependencies are third-party attack surface |
| 15 | Secret management | **MVP where secrets exist** | Signing/API credentials must never be committed; current runtime should need no API secret |
| 16 | Log/diagnostic privacy | **MVP** | Avoid leaking exact location/session data into general logs |
| 17 | Model provenance / anti-rollback | **RECOMMENDED** | Ensure known model version is active; full signed-update system is production scope |
| 18 | Tamper-evident recording manifest | **RECOMMENDED** | Cheap integrity evidence for judged/reference datasets |
| 19 | Authentication / RBAC inside app | **NOT APPLICABLE for current single-user offline prototype** | No remote API/multi-user account surface exists |
| 20 | Network TLS / API authorization | **NOT APPLICABLE currently** | No runtime network API exists |
| 21 | Background location service hardening | **NOT APPLICABLE currently** | No background navigation service |
| 22 | Edge transport authentication | **FUTURE/PRODUCTION** | Required only if phone and edge communicate over USB/Bluetooth/network |
| 23 | Encrypted local database/recording | **RECOMMENDED/PRODUCTION** | Useful for product privacy; current app-private storage is sufficient for prototype |
| 24 | Secure OTA model/map updates | **PRODUCTION** | Not part of current offline MVP |

---

# MVP Controls — Implementation Location and Test Method

## 1. Least-Privilege Android Permissions

### Implementation

Current manifest requires only:

- coarse location,
- fine location,

with optional hardware features.

It explicitly avoids:

- background location,
- foreground-service permission,
- broad storage permission.

### Test

- fresh install,
- deny location,
- grant approximate,
- grant precise,
- revoke while app is open/paused,
- confirm IMU still behaves correctly where permitted,
- confirm no crash/fabricated GNSS values.

---

## 2. Offline Runtime / No Hidden Network Dependency

### Implementation

Current manifest removes inherited:

- `INTERNET`,
- network state,
- Wi-Fi state.

MapLibre runs disconnected with local data.

Future model/routing code must not add network access implicitly.

### Test

Run full acquisition/recording/replay/map path with:

- airplane mode / network unavailable as appropriate,
- no Wi-Fi/mobile data.

Confirm:

- map loads,
- recording works,
- replay works,
- navigation inference works once implemented.

Network unavailability must not change estimator semantics.

---

## 3. Foreground-Only Acquisition

### Implementation

Current lifecycle stops acquisition/recording/replay when foreground ownership ends.

No service silently continues.

### Test

- start real acquisition,
- press Home,
- verify sensors/location unregister,
- return,
- confirm no silent restart,
- require explicit Start.

---

## 4. App-Private Recording Storage

### Implementation

Canonical recordings live under app-private `noBackupFilesDir`.

No external-storage permission.

### Test

- create recording,
- verify it is not directly visible as a normal shared-storage file,
- ensure app can enumerate it,
- verify uninstall/data-clear consequences are documented.

---

## 5. Backup Protection

### Implementation

Manifest has application backup disabled.

### Test

Review manifest and backup/data-extraction rules in the packaged app.

For production, add a dedicated automated manifest regression check.

---

## 6. Explicit Export

### Implementation

Export starts only from a user action and uses Android's document framework.

Original private files remain authoritative.

### Test

- cancel picker,
- failed destination,
- successful local export,
- background during export,
- verify original checksums unchanged.

---

## 7. Export Destination Validation

Current policy rejects known non-local/cloud-provider destinations.

### Test

- local Downloads/document provider succeeds,
- unsupported/cloud destination is rejected,
- failure message is explicit,
- no original mutation.

If product policy later allows cloud export, treat that as an explicit privacy feature, not a silent relaxation.

---

## 8. Strict Input Validation

### Implementation

Use the canonical codec before data mutates navigation state.

Reject:

- NaN/Infinity,
- invalid units/frame,
- malformed quaternion,
- invalid source/version,
- negative/broken timestamps,
- impossible ranges,
- corrupt JSONL.

### Test

Use invalid/golden fixtures and ensure rejected records never affect filter state.

---

## 9. Replay / Session Path Validation

### Risk

A session ID should never be treated as an arbitrary filesystem path.

### Implementation

- resolve only IDs discovered under the private recordings root,
- reject traversal tokens,
- do not follow unexpected symlinks,
- keep reads inside the configured root.

### Test

Try:

```text
../../...
absolute path
nonexistent ID
corrupt metadata
corrupt interior JSONL
```

Confirm safe rejection.

---

## 10. Arbitrary ZIP Import

**Not applicable currently.**

If import is added later:

- whitelist expected entries,
- reject absolute paths and `..`,
- enforce uncompressed size limits,
- reject duplicate entries,
- verify contract/metadata before materialization,
- protect against zip bombs.

---

## 11. Model Integrity

**Applicable once a model is deployed.**

### Implementation

At model load:

1. compute SHA-256,
2. compare with frozen manifest,
3. verify input schema/version,
4. refuse mismatch,
5. fall back to classical path where safe,
6. emit security/diagnostic event.

### Test

Flip one byte in a model copy and confirm:

- load fails,
- corrupted model is not executed,
- classical fallback remains visible if supported.

---

## 12. Dataset / Experiment Integrity

### Implementation

Ground-truthed experiment storage includes hash manifest for:

- phone metadata,
- measurements JSONL,
- reference file,
- labels/masks.

Training/evaluation checks hashes before use.

### Test

Modify one artifact and confirm the pipeline refuses to silently use it.

---

## 13. Offline Map-Pack Integrity

### Implementation

Current map pack already uses manifest/checksum concepts.

Verify:

- MBTiles checksum,
- manifest version,
- required glyph/style resources,
- bounded paths.

### Test

Corrupt the MBTiles or manifest in a test build and confirm:

- pack is rejected,
- no network fallback occurs,
- navigation can still expose numeric state independently.

---

## 14. Dependency Security

### Android

Review Gradle dependency reports / vulnerability scanning available in the build environment.

Keep MapLibre and AndroidX versions intentionally pinned/upgraded.

### Python

Use:

```text
pip-audit
```

or equivalent against pinned dependencies once training requirements are frozen.

### Test

Before final submission:

- no known critical direct dependency issue left unexplained,
- dependency lock/pin files committed where appropriate.

---

## 15. Secrets Management

Current runtime should not need tile/API/cloud keys.

Potential secrets:

- release signing material,
- future remote-service credentials,
- optional private artifact registry tokens.

Rules:

- never commit,
- environment/secure build storage,
- `.env`/key files gitignored,
- no secret copied into documentation/screenshots.

---

## 16. Logging Privacy

Avoid production/general logs containing:

- exact latitude/longitude,
- full trip path,
- user-selected export path,
- unique device identifiers beyond what is necessary,
- raw sensor payloads.

Debug builds may expose more, but verification notes should avoid unnecessarily publishing sensitive real trip locations.

---

# Recommended Controls

## 17. Model Provenance / Anti-Rollback

Record active:

```text
model_version
SHA-256
feature_schema
training_manifest
build SHA
```

A future signed-package system may prevent rollback to a vulnerable/invalid model.

For the hackathon, explicit version/hash display is sufficient.

---

## 18. Tamper-Evident Recording Manifest

Recommended lightweight approach:

```text
SHA256(metadata.json)
SHA256(measurements.jsonl)
SHA256(reference.csv)
SHA256(labels.json)
```

stored in a separate manifest.

This supports scientific integrity without inventing a blockchain/complex cryptographic service.

---

## 19. Authentication / RBAC

**Not applicable to the current single-user app.**

There is:

- no backend API,
- no account system,
- no multi-user shared dashboard.

Do not add login screens purely for appearance.

Becomes applicable if recordings synchronize to a remote service or multiple analysts/users share data.

---

# Protecting the Offline / On-Device Boundary

This is the project-specific equivalent of a foundational security invariant.

The IDR system should not silently become dependent on a return path to the Internet.

## Risks

### Map renderer accidentally fetches online tiles
Mitigation:
- disconnected MapLibre,
- local pack,
- manifest permissions removing Internet.

### Routing uses a web API
Mitigation:
- routing absent for MVP or fully local road graph.

### AI inference calls a cloud endpoint
Mitigation:
- TFLite/ONNX local artifact only.

### GNSS quality enrichment queries online assistance/reputation
Mitigation:
- use locally available Android/provider data only.

### Reverse geocoding leaks location
Mitigation:
- do not perform live remote geocoding.

### Crash analytics leak exact location
Mitigation:
- redact location/session payloads before any future telemetry integration.

---

## Verification Test

On the final app:

1. inspect manifest for Internet/background/service permissions,
2. run while general network connectivity is unavailable,
3. use acquisition,
4. navigate through offline map,
5. record,
6. replay,
7. run real navigation once implemented.

The full core flow must still work.

---

# Model / Data Poisoning Considerations

Training data and model artifacts are part of the navigation trust chain.

Controls:

- hash datasets,
- immutable raw captures,
- split manifests,
- training provenance,
- model hash verification,
- no automatic online learning from user feedback,
- no silently downloaded replacement models.

A poisoned navigation model can create physically plausible but wrong coordinates, so integrity is not optional once ML is deployed.

---

# Future Edge Transport

If Android communicates with a separate edge device:

Minimum security later:

- peer authentication,
- versioned framing,
- integrity/authentication of messages,
- replay protection,
- bounded input,
- explicit source identity,
- no arbitrary command execution,
- encrypted transport where appropriate.

This is not needed for the current phone-only prototype.

---

# Driver / Operational Safety

Security/privacy review does not replace road safety.

Recommended:

- passenger handles test controls,
- no complex interaction while driving,
- large glanceable status,
- no instruction to deliberately lose control/perform unsafe maneuvers,
- no GNSS jamming.

---

**No approved architecture is redesigned here. This document classifies the controls around the current local Android/replay/map/navigation design.**
