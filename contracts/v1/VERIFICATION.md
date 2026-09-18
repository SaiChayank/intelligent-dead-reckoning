# Contract boundary verification — 2026-09-16

## Verdict and scope

Prompt 1 is implemented: seven typed event payloads, strict single-record JSON
and streaming JSONL codecs in Python and Kotlin, matching negative fixtures,
proper-rotation rejection and actual bidirectional writer/reader verification.
All four IMU sensor variants are covered. The NavigationEngine is an interface
only, not a functioning navigation engine. No acquisition, INS propagation,
map rendering, recording UI, AI or EKF was added. Foundation readiness remains
NO-GO for AI; this result closes only the contract-boundary implementation task.

## Files changed in this continuation

- Added `contracts/__init__.py`, `contracts/v1/__init__.py`, `models.py`,
  `codec.py`, `interop.py`, `golden_records.jsonl`, `edge_records.jsonl`,
  `invalid_records.json` and this verification note.
- Added `contracts/v1/kotlin/com/intelligentdeadreckoning/contracts/v1/Models.kt`
  and `Codec.kt`.
- Updated `contracts/v1/README.md` to describe implemented framing, validation,
  bounds, ownership, error handling and reproducible interoperability commands.
- Added `tests/test_contract_codec.py` and
  `mobile/app/src/test/java/com/intelligentdeadreckoning/app/StrictContractTest.kt`.
- Updated `mobile/app/build.gradle.kts`: compile the external pure Kotlin source
  directory and make the already pinned Gson 2.11.0 available to the codec.
  AGP built-in Kotlin requires the Kotlin source set, not the Java source set.
- Preserved the original `golden.json`, its existing parity tests, the app shell,
  training source, raw data and historical reports. Existing Git changes were
  neither staged, reverted nor discarded.

## Verification results

Environment: repository CPython 3.12.14 virtual environment; Java 25.0.3 Android
Studio JBR; Gradle 9.3.1 / AGP 9.1.1 / Kotlin 2.2.10; SDK compile platform 37.

- `.venv\Scripts\python.exe -B -m unittest discover -s tests -v`
  and final `-q` run: **89 tests passed**, including 15 new codec tests.
- AST parsing of every Python source under `contracts/`, `training/`, `tests/`:
  **31 files passed**. Imports were also tested in a fresh process with
  application file opening/directory creation forbidden.
- From `mobile/`, with JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME configured:
  `./gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --console=plain -q`:
  **passed**. **31 JVM tests passed**: 15 strict contract, 4 original golden,
  12 existing simulation tests. Debug APK assembled successfully.
- Lint: **0 errors, 8 warnings**. Warnings concern target API and newer Gradle/
  dependency versions; these were not silenced or addressed by unrelated upgrades.
- Shared corpus: **47 malformed/invalid cases rejected in both languages** with
  matching expected error codes. Includes invalid versions/enums/keys/types,
  ranges, incorrect units/frames, null/invariant violations, duplicate keys,
  NaN/infinity, non-unit rotations, malformed/truncated JSON and invalid Unicode.
- Additional streaming tests cover duplicate event IDs, one-based failure lines,
  preserved valid prefixes, immutable session/source/mode, CRLF, exact equal-time
  sensor events, arrival order, bounded/lazy reads and I/O failures. Reflection
  matrices are rejected by both math validators; quaternion sign is not changed.
- Actual bridge executed from repository root:
  `.venv\Scripts\python.exe -B -m contracts.v1.interop export mobile/app/build/contract_interop/python.jsonl`.
  Gradle tests ran with IDR_CONTRACT_PYTHON_JSONL set to that absolute path, read
  Python's emitted bytes, checked canonical typed values and emitted Kotlin bytes.
  `.venv\Scripts\python.exe -B -m contracts.v1.interop verify mobile/app/build/contract_interop/kotlin.jsonl`
  then confirmed **17 Kotlin records, all typed values identical**. The bridge
  covers explicit nulls, all event types, >2^53 and maximum signed Int64 timestamps,
  and maximum signed Int64 integer metadata. All four source labels are separately
  round-trip tested in both languages. This is value equality, not byte equality.
- Import inspection: Kotlin contracts depend only on Kotlin/JVM and Gson, not
  Android, Compose, map SDKs or navigation implementation classes.
- `git diff --check`: passed (Git emitted existing LF/CRLF conversion notices).

## Preservation evidence

A read-only, file-by-file comparison with `reports/phase0_raw_manifest.json`
checked **1,186 files / 3,726,142,216 bytes** under the explicit root
`data/raw/iovnbd`. SHA-256, size and nanosecond modification timestamps all match;
there are no missing or extra raw files. No audit/report generation was run.

All 21 existing report files remain present. The 18 historical evidence files
with a retained pre-readiness snapshot match SHA-256, size and modification time.
The three later readiness/admission documents were not edited. Contract evidence
is written here, separate from those historical reports; build outputs are under
`mobile/app/build/` only. IO-VNBD exports were not relabelled or converted into
raw Android device-frame records.

## Remaining limitations and next bounded action

No contract implementation test is blocked. Device acquisition and a concrete
engine remain absent by design; no new phone validation or installation was
performed. The engine interface's future causal calibration/ordering/ownership
requirements are not an algorithm implementation. Streams are synchronous,
caller-owned and fail fast; a partially written final line is not an atomic trip
recording transaction. See README for limits and full behavior.

Next authorized task, when requested: **Prompt 2 — real sensor and GNSS
acquisition without recording**, preserving simulation and using this typed
boundary. Do not proceed to AI/EKF or assume unresolved IO-VNBD frame conventions
have been fixed by serialization work.
