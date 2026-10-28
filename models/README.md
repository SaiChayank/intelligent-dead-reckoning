# models: exported model artifacts

> **Documentation note:** the detailed companion documents this file used to link (acquisition, recording, export, replay, calibration, fusion, map, UI design, security and release notes) were removed in the repository cleanup. Their content is not reproduced here; the current verification record is [reports/gap_closure_log.md](../reports/gap_closure_log.md).

## Which manifest to trust

| File | Role |
|---|---|
| [ai_model_manifest.json](ai_model_manifest.json) | **Authoritative.** Lists the exported weight files, SHA-256, training data and headline metrics, and limitations. |
| [model_manifest.json](model_manifest.json) | Legacy record of the original training gate (`no_model_artifacts`, 0 approved sequences). It is kept because the release test asserts it; it does not describe the weights below. |

## What is here

| Artifact | Trained on | Status |
|---|---|---|
| `speed_estimator_weights.json` (v1, 6 channels) | Synthetic data only | Bundled in the Android app. On real drives it fails badly (leave-one-drive-out RMSE 62 to 97 m/s, see `reports/model_a_real_data_eval.md`). |
| `speed_estimator_v2_invariant_weights.json` (4 channels) | 12 IO-VNBD candidate drives under a documented research exception | Not used by the app (its inputs differ from v1 and the Kotlin feature extraction is not updated). Leave-one-drive-out RMSE 6.11 m/s against 6.67 m/s for predicting the mean. Not field-validated. |
| `speed_estimator_v3_weights.json` (9 features) | 11 IO-VNBD drives with a vehicle-frame inertial recording, leave-one-drive-out | Learned correction of integrated acceleration, off by default (`speedCorrectionEnabled`). On held-out drives speed RMSE 2.6 m/s against 4.8 (hold) and 4.3 (integrated); median drift still above the 10 % gate. External-IMU input only; see [speed_v3_manifest.json](speed_v3_manifest.json). Not field-validated. |

The formal gate in [reports/training_admission.json](../reports/training_admission.json) still records 0 approved
sequences; v2 exists only under the research exception described in its manifest entry.

No online learning is permitted on the device: all training is offline, with model artifact hash/version traceability.

Weight files in other formats (`*.tflite`, `*.onnx`, `*.pt`, `*.h5`, `*.keras`, `*.ckpt`) are gitignored by
policy; the JSON weights above are tracked. Model code lives in `training/`, evaluation evidence in `reports/`.
