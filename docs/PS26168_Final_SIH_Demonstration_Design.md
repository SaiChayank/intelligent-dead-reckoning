# PS26168 — Final SIH Demonstration Design

**Scope:** The final live demonstration sequence only. No architecture changes are introduced here.

**Honesty constraint:** the current repository has verified acquisition, recording/export/replay, offline MapLibre rendering, and a synthetic GNSS/DR/recovery map demo. The real NavigationEngine, corrected INS, AI model, EKF/UKF, map matching, routing, and edge engine are not yet implemented. Therefore the sequence below is the **target acceptance script**; any step depending on an unimplemented module must not be presented as already working until that module passes its gate.

**Fallback principle:** prefer validated recordings/replay of real system runs. A synthetic UI fixture may demonstrate interface behavior, but it cannot substitute for navigation-accuracy evidence.

---

## 1. Offline Startup
- Disable network connectivity as appropriate for the demo.
- Launch the preinstalled app.
- Show the Hyderabad MapLibre map loading locally.
- Show attribution and offline coverage.
- Confirm no tile/API-key/network dependency.

**Evidence:** real local vector map renders offline.

**Fallback:** rehearsal screen recording, explicitly labelled as such.

---

## 2. Source & Contract Proof
- Open Diagnostics.
- Select REAL PHONE.
- Start acquisition explicitly.
- Show accelerometer/gyro/gravity/magnetometer device names and measured rates.
- Show event/receipt timestamps.
- Show GNSS provider/quality fields when available.

**Evidence:** actual phone values changing, bounded queue counters, source identity.

---

## 3. Local Recording
- Start a new recording.
- Show recording ID and acquisition session ID.
- Show written/drop/error counters.
- Continue acquisition while recording.

**Expected:** recorder remains downstream; acquisition is independent of disk success.

---

## 4. Calibration
**Run only after real calibration exists and has passed acceptance.**

- Mount phone in defined vehicle position.
- Start guided calibration.
- Show stationary pitch/roll phase.
- Show dynamic yaw/forward-axis phase if required.
- Show calibration ID/status/confidence.

**Evidence:** calibration event and vehicle-frame sanity check.

**Fallback:** replay a previously validated calibration session through the same engine path.

---

## 5. GNSS-Available Navigation
**Run only after the real navigation engine exists.**

- Begin drive in open-sky area.
- Show GNSS quality usable.
- Show fused position on map.
- Show speed, heading, confidence radius.
- Show travelled trail.

**Evidence:** map source explicitly indicates real engine output.

---

## 6. Enter GNSS-Denied Segment

Preferred physical scenario:
- tunnel,
- covered parking,
- safe controlled GNSS-denied environment.

Alternative:
- software-masked GNSS during replay/evaluation on a ground-truthed drive.

Internally:
- GNSS quality becomes stale/denied,
- stale fixes are not repeated as fresh,
- fusion stops accepting invalid GNSS,
- DR continues.

Map:
- marker continues moving,
- heading/speed continue from engine,
- confidence expands as justified,
- localization mode is shown only if the contract/state machine explicitly supports it.

---

## 7. AI Contribution / Ablation

Use the exact same held-out outage interval to compare:

1. classical INS baseline,
2. classical fusion/constraints,
3. AI-enhanced system.

Display:
- ground truth,
- baseline trajectory,
- AI-enhanced trajectory,
- final outage error,
- drift percentage,
- velocity MAE/RMSE.

If AI does not materially improve the chosen metric, state that result.

---

## 8. Non-Holonomic Constraint Effect
On a prepared segment:
- show lateral/vertical velocity before/after constraint where appropriate,
- show physically plausible road-vehicle motion.

Do not generalize to motorcycles/skids/parking if not tested.

---

## 9. Map Matching
**Only after implemented.**

- show raw fused trail,
- show matched trail as a distinct overlay,
- show that matching does not rewrite raw history,
- show match confidence / candidate-road evidence if available.

Narrative: map matching constrains a credible estimate; it is not cosmetic hiding of bad INS.

---

## 10. GNSS Recovery
- emerge from denied zone,
- show returning GNSS fixes,
- show quality gate,
- show fusion reconvergence,
- show map transition without a presentation-only teleport.

Display:
- recovery start,
- innovation/recovery diagnostic if available,
- confidence contraction,
- convergence time.

---

## 11. Drift Target

Show:

```text
reference distance travelled
final position error
drift percentage = final error / reference distance × 100
outage duration
mean/RMSE position error
```

The `<10%` claim passes only if measured on the defined acceptance scenario.

---

## 12. ~10 Hz Mobile Runtime

Display:
- navigation output Hz,
- input sensor Hz,
- p50/p95 processing latency,
- queue high water,
- drops/errors,
- PSS/RSS,
- device model.

**Evidence:** measured on the physical Android device.

---

## 13. Stop and Finalize Recording
- Stop recording.
- Show completion state and final count.
- Stop acquisition.
- Show sensor/location cleanup if asked.

---

## 14. Export
- Open Saved Sessions.
- Choose the recorded run.
- Export explicitly to local storage.
- Show original private session remains intact.

Optional:
- verify ZIP contains exactly `metadata.json` and `measurements.jsonl`.

---

## 15. Replay
- replay the exact saved real session,
- show `replay_real`,
- show original timestamps/order retained,
- show no live sensor ownership.

If real navigation replay is implemented:
- feed replay through the same NavigationEngine,
- reproduce navigation outputs within defined tolerances.

---

## 16. Offline Python Evaluation
On laptop:
- read the same export,
- validate counts/timestamps,
- evaluate outage against ground truth,
- generate trajectory plot and metrics.

This demonstrates reproducibility, not a separate navigation implementation.

---

## 17. Optional Offline Route / Route Progress
Only if routing is implemented:
- choose local destination,
- compute route with no network,
- show planned polyline,
- show progress using real navigation position,
- show reroute only if tested.

If absent, omit. Routing is not needed to prove DR.

---

## 18. Edge Engine
Only when implemented:
- connect FOG IMU input or validated replay,
- show same navigation semantics,
- show ONNX runtime if used,
- demonstrate sustained processing toward ~200 Hz,
- show latency and accuracy.

Do not claim 200 Hz merely by replaying 10 Hz phone data faster unless explicitly labelled throughput-only.

---

# Demo Preparation Checklist

- [ ] Final APK/test APK installed in advance.
- [ ] No dependence on venue Wi-Fi.
- [ ] Offline map verified on exact demo phone.
- [ ] Demo drive/replay checksum recorded.
- [ ] Real and replay labels visible.
- [ ] GNSS-denial interval rehearsed.
- [ ] Ground truth available for every drift claim.
- [ ] Raw baseline and AI result computed for identical interval.
- [ ] Metric formulas reproducible.
- [ ] Fallback replay exists for every live-driving step.
- [ ] Screen recording exists as second-tier display fallback.
- [ ] Battery/storage/thermal condition checked.
- [ ] App data not cleared if retained sessions matter.
- [ ] Synthetic map remains labelled synthetic.
- [ ] No unimplemented module is presented as complete.

---

# Fallback Ladder

1. **Live real drive**
2. **Replay of a real, ground-truthed drive through the same pipeline**
3. **Screen capture of that successful run**
4. **Synthetic map demo — UI only**

---

# Metrics to Display

Core:
- outage duration,
- outage distance,
- final position error,
- drift percentage,
- position RMSE,
- velocity MAE/RMSE,
- heading error where reference exists,
- recovery convergence time,
- mobile output rate,
- processing latency,
- queue drops/errors.

Optional:
- calibrated 95% coverage,
- map-match error,
- route progress error,
- edge throughput/latency.

---

# Questions Judges Are Likely to Ask

### How do you know the phone still moves correctly with no GPS?
Show GNSS denied/stale, continuing engine states, ground-truth comparison, and drift metric.

### Is the synthetic map demo your real AI?
No. It validates offline rendering/UI before the navigation engine. Real performance is shown separately.

### Why not just Google Maps?
The project avoids mandatory live-network dependence and uses offline MapLibre/OSM-derived data.

### Is map matching doing all the work?
Show raw and matched trajectories separately.

### Why AI if EKF already exists?
Show classical-vs-AI ablation on the identical held-out interval.

### How do you avoid using VBOX at inference?
Show the canonical mobile inputs; VBOX appears only as label/evaluation.

### What if the phone moves in the mount?
Show remount invalidation once implemented, or state it remains an open gate.

### Can it route offline?
If built, show local graph routing. Otherwise explain that rendering, positioning, map matching, routing and turn-by-turn are separate functions.

---

**This document defines the target demonstration and fallback strategy only.**
