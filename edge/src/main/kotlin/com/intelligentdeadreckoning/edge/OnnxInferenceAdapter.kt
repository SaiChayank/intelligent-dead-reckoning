package com.intelligentdeadreckoning.edge

/**
 * Optional edge inference stage. It is deliberately unavailable until a versioned ONNX model,
 * feature schema and accuracy evaluation are admitted to the repository; this runtime does not
 * synthesize features or pretend that an absent model has zero latency.
 */
interface OnnxInferenceAdapter {
    val available: Boolean
    val unavailableReason: String?
}

object NoOnnxModel : OnnxInferenceAdapter {
    override val available: Boolean = false
    override val unavailableReason: String =
        "No admitted ONNX model, inference runtime, or feature schema is present."
}
