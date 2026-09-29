# models — trained/exported model artifacts (RESERVED, EMPTY)

Future home of trained and exported model artifacts (TFLite/ONNX/etc.).

**No model has been trained or approved.** Training is gated: 0 of 144
IO-VNBD pairs pass `reports/training_admission.json`, and the build order in
[CURRENT_STATE_AUDIT.md](../CURRENT_STATE_AUDIT.md) places AI work after the
classical baseline is credible. Do not add weights here before that gate opens.

Weight files (`*.tflite`, `*.onnx`, `*.pt`, `*.h5`, `*.keras`, `*.ckpt`) are
gitignored by policy; model *code* lives in `training/`, evaluation evidence in
`reports/`. See [ARCHITECTURE.md](../ARCHITECTURE.md).
