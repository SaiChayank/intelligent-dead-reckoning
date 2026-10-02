# Edge runtime (single JVM process)

The first secondary deployment target adapts the **same** contract-1.1.0 Kotlin
`FusionNavigationEngine` used by mobile. This avoids a fork in INS/EKF semantics.
Input must be a canonical `Source.REAL` JSONL session with one valid calibration
record (`VALID`, proper vehicle-from-device quaternion, gyroscope bias) causally
before every measurement. IMU units and Android device frame are validated, never
guessed or converted. The runtime uses a bounded nonblocking queue and one worker;
queue overflow and invalid/late/duplicate rows are explicit counters.

The target depends on the mobile navigation semantics being stable. They are covered
by the mobile fusion, confidence, contract and lifecycle tests; validate those before
qualifying a separate deployment.

## Run

From the repository root, using the mobile Gradle wrapper and its configured local
Gradle cache:

```bash
cd mobile
bash gradlew -p ../edge test --offline --console=plain
bash gradlew -p ../edge runEdgeBenchmark \
  -Pinput=/absolute/path/to/calibrated-real-1.1.jsonl \
  -Poutput=/absolute/path/to/edge-profile.json \
  -Preference=/absolute/path/to/independent-reference-1.1.jsonl \
  --offline --console=plain
```

The benchmark streams the input at **1x measurement-time pace** by default. It does
not multiply or infer an input rate. A 10 Hz source therefore cannot produce a 200 Hz
claim. Sustained 200 Hz eligibility requires both accelerometer and gyroscope measured
source rates and actual paired-worker throughput >=200 Hz, no drops, and unused bounded
queue headroom. Set the report path outside the repository or to a dedicated build
folder to avoid checking in measurements containing sensor/location data.

`NavigationState` remains at the mobile engine's publication cadence (5 Hz today); it
is not emitted at the sensor rate. The report separates source cadence, wall admission,
worker throughput, source-clock output cadence, cycle latency percentiles, queue
high-water/drop counts, sampled JVM heap, and worker-thread CPU. RSS and board thermal,
battery and power are explicitly unavailable without target-specific measurement.

## Not implemented / qualification requirements

- Feature generation has no admitted schema or implementation.
- There is no ONNX model or ONNX Runtime dependency, so inference is `N/A`, not zero.
  Add it only with a versioned feature/model contract, independent accuracy comparison,
  and a measured inference benchmark.
- Map matching is an optional mobile presentation overlay, not part of `FusionNavigationEngine`.
- Accuracy is scored only when an independent reference has an exact measurement-time
  join and identical ENU origin. No interpolation is used. Without joined truth the
  report says zero matches and null errors; it does not invent an accuracy value.
- This checkout has no qualified external 200 Hz edge sensor dataset or edge-board
  benchmark. Run the same harness against timestamped calibrated 200 Hz acquisition
  from the intended board before claiming that target. No Numba/C++ rewrite is justified
  until a target run locates a measured hot spot.
