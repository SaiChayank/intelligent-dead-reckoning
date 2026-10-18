# models — trained/exported model artifacts (no weights)

Future home of trained and exported model artifacts (TFLite/ONNX/etc.). The only current
tracked file is [model_manifest.json](model_manifest.json), which records an empty artifact
list and no hashes because there are no weights to hash.

**No model has been trained or approved.** Training is gated: 0 sequences are approved in
[reports/training_admission.json](../reports/training_admission.json).
No online learning is permitted on device; all model training is strictly offline with
model artifact hash/version traceability. Do not add weights here before that gate opens.

Weight files (`*.tflite`, `*.onnx`, `*.pt`, `*.h5`, `*.keras`, `*.ckpt`) are
gitignored by policy; model *code* lives in `training/`, evaluation evidence in
`reports/`. See [ARCHITECTURE.md](../ARCHITECTURE.md).
