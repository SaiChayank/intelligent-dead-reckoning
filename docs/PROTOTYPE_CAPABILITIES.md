# SIH prototype capability matrix

Status is for the checked-out prototype. **Implemented** means source exists; it does not
mean field qualification or product acceptance.

| Capability | Status | Evidence / boundary |
|---|---|---|
| Simulation UI | Implemented | Explicitly scripted and labelled; not a physical sensor model or navigation result. |
| Foreground IMU and optional GNSS acquisition | Implemented; historical device-tested | Dated OnePlus CPH2585 / Android 16 reports exist; not fresh acceptance of this checkout. Rates/device behavior vary. |
| Private local recording and interruption recovery | Implemented; historical device-tested | App-private, no-backup sessions. Uninstall/data clear removes them. |
| Local ZIP export and strict replay | Implemented; historical device-tested | Explicit local export, read-only playback, exact timestamps/source identity. Tracked synthetic replay fixture is diagnostic-only, no GNSS/sensors. |
| User-confirmed per-session deletion | Implemented; host regression-tested | Guards active recording/replay/export; symlink-safe removal; current device instrumentation unavailable. |
| Offline Hyderabad map | Implemented; previous device acceptance | Bundled, hash-checked tiles; central Hyderabad only. Current airplane-mode device check pending. |
| Synthetic map demo | Implemented; historical host/device tests | UI rendering, camera and scripted outage display only; no real movement/truth. |
| Live/recorded GNSS map display | Implemented | Raw provider fixes only, coverage-limited; no dead reckoning. |
| Calibration engine | Implemented in source; host-tested | No user-accessible calibration workflow or valid normal-app hand-off to fusion. |
| Fusion EKF / motion constraints | Implemented in source; host-tested | Experimental; no accepted moving-drive/reference qualification. Normal app has no aligned position. |
| Offline road matching | Implemented; host-tested | Optional parallel overlay, not routing or navigation. No ground-truth field accuracy. |
| Evaluation screen/report | Implemented | Deterministic host scripted-truth report; not field ground truth/device performance. |
| Independent ground-truth field evaluation | **Unavailable / blocked** | `experiments/` corpus empty; no approved sealed moving-drive reference. See [manifest](../evaluation/ground_truth_manifest.json). |
| AI model and on-device inference | **Not implemented** | Admission no-go, zero approved sequences, no weights/runtime/hashes. See [model manifest](../models/model_manifest.json). |
| Physical airplane-mode verification of complete demo | **Pending** | ADB/device unavailable in this workspace. Host asset checks are not physical verification. |
| Offline route planning / turn-by-turn | Future | Road graph is undirected and has no routing costs/turn restrictions. |
| Production release signing, license decision, Android advisory review | Future release gates | No signing key/config or legal license selected; Android advisory scan not available here. |
| Cloud/backend/accounts/analytics/public hosting | Intentionally excluded | Local prototype needs none; no deploy action is part of this task. |
