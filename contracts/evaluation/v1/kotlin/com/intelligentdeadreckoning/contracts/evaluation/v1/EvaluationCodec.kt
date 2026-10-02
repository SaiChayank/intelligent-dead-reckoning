package com.intelligentdeadreckoning.contracts.evaluation.v1

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Locale

/**
 * Strict codec for the evaluation report document, the Kotlin twin of `contracts/evaluation/v1`.
 *
 * Every defined key is required; an unavailable value is an explicit JSON `null`. Unknown or
 * missing keys are rejected, the version must match exactly, numbers must be finite and
 * non-negative, and the honesty rules the document exists for are invariants:
 *
 * - an accuracy group may only exist when the reference is declared independent and the arm did
 *   not consume it (`INVARIANT`), and it must contain at least one measured value;
 * - a `reference_consumed` flag of `true` is refused outright: an arm is never scored against its
 *   own input;
 * - a non-`evaluated` arm must carry a reason and no metrics, because "not run" and "not
 *   implemented" are answers rather than blanks;
 * - a host platform cannot claim a device model or an Android release.
 */
class EvaluationContractException(val code: String, val path: String = "$") :
    IllegalArgumentException("$code at $path")

private val versionToken = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val decimalToken = Regex("0|[1-9][0-9]*")
private val tokenPattern = Regex("[a-z][a-z0-9_]{0,63}")
private const val MAX_TEXT = 2048

/**
 * Tokens no JSON number can be, but which a lenient reader hands over as one — Gson turns a bare
 * `NaN` into a string primitive, while Python's `json` parses the same token into a float. The two
 * codecs must name the same refusal for the same document, so a non-finite token is `NONFINITE`
 * here whether it arrives bare or quoted.
 */
private val NON_FINITE_TOKENS = setOf("NaN", "Infinity", "-Infinity", "+Infinity")

private fun fail(code: String, path: String = "$"): Nothing = throw EvaluationContractException(code, path)

private class Fields(value: JsonElement, val path: String) {
    val obj: JsonObject = if (value.isJsonObject) value.asJsonObject else fail("INVALID_TYPE", path)
    fun keys(vararg names: String) { if (obj.keySet() != names.toSet()) fail("INVALID_KEYS", path) }
    private fun at(key: String) = "$path.$key"
    operator fun get(key: String): JsonElement = obj[key] ?: fail("INVALID_KEYS", path)
    fun text(key: String): String {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        val s = v.asString
        if (s.isBlank() || s.length > MAX_TEXT) fail("OUT_OF_RANGE", at(key))
        return s
    }
    fun nullableText(key: String): String? = if (get(key).isJsonNull) null else text(key)
    fun token(key: String): String = text(key).also { if (!tokenPattern.matches(it)) fail("OUT_OF_RANGE", at(key)) }
    fun version(key: String): String = text(key).also { if (!versionToken.matches(it)) fail("OUT_OF_RANGE", at(key)) }
    fun decimal(key: String): Long {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        val s = v.asString
        if (s.length > 19 || !decimalToken.matches(s)) fail("OUT_OF_RANGE", at(key))
        return s.toLongOrNull() ?: fail("OUT_OF_RANGE", at(key))
    }
    fun number(key: String): Double {
        val v = get(key)
        if (!v.isJsonPrimitive) fail("INVALID_TYPE", at(key))
        val primitive = v.asJsonPrimitive
        if (!primitive.isNumber) {
            if (primitive.isString && primitive.asString in NON_FINITE_TOKENS) fail("NONFINITE", at(key))
            fail("INVALID_TYPE", at(key))
        }
        val n = v.asDouble
        if (!n.isFinite()) fail("NONFINITE", at(key))
        if (n < 0.0) fail("OUT_OF_RANGE", at(key))
        return n
    }
    fun nullableNumber(key: String): Double? = if (get(key).isJsonNull) null else number(key)
    fun integer(key: String): Long {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) fail("INVALID_TYPE", at(key))
        val n = v.asLong
        if (n < 0L) fail("OUT_OF_RANGE", at(key))
        return n
    }
    fun nullableInteger(key: String): Long? = if (get(key).isJsonNull) null else integer(key)
    fun boolean(key: String): Boolean {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isBoolean) fail("INVALID_TYPE", at(key))
        return v.asBoolean
    }
    fun array(key: String): JsonArray {
        val v = get(key)
        if (!v.isJsonArray) fail("INVALID_TYPE", at(key))
        return v.asJsonArray
    }
}

object EvaluationCodec {

    private val accuracyKeys = arrayOf(
        "reference_consumed", "outage_duration_s", "outage_distance_m", "final_position_error_m",
        "drift_percent", "position_rmse_m", "speed_mae_m_s", "speed_rmse_m_s", "heading_error_deg",
        "recovery_convergence_s", "recovery_threshold_m", "samples",
    )
    private val timingKeys = arrayOf(
        "output_hz", "inference_latency_p50_ms", "inference_latency_p95_ms", "end_to_end_p50_ms",
        "end_to_end_p95_ms", "queue_high_water", "drops", "errors", "memory_peak_mb", "samples",
    )

    fun decode(text: String): EvaluationReport {
        if (text.encodeToByteArray().size > 262_144) fail("OUT_OF_RANGE")
        val root = try { JsonParser.parseString(text) } catch (_: Exception) { fail("INVALID_JSON") }
        return decode(root)
    }

    fun decode(document: JsonElement): EvaluationReport {
        val root = Fields(document, "$")
        root.keys(
            "evaluation_contract_version", "evaluation_id", "created_utc_ms", "session", "platform",
            "reference", "segments", "arms",
        )
        if (root.text("evaluation_contract_version") != EvaluationReport.VERSION)
            fail("INVALID_VERSION", "$.evaluation_contract_version")

        val sessionFields = Fields(root["session"], "$.session")
        sessionFields.keys("session_id", "source", "contract_version", "duration_s", "records", "description")
        val duration = sessionFields.number("duration_s")
        if (duration <= 0.0) fail("OUT_OF_RANGE", "$.session.duration_s")
        val session = SessionDescriptor(
            sessionId = sessionFields.text("session_id"),
            source = sessionFields.token("source"),
            contractVersion = sessionFields.version("contract_version"),
            durationS = duration,
            records = sessionFields.integer("records"),
            description = sessionFields.text("description"),
        )

        val platformFields = Fields(root["platform"], "$.platform")
        platformFields.keys("host", "device_model", "android_release", "note")
        val platform = PlatformDescriptor(
            host = platformFields.boolean("host"),
            deviceModel = platformFields.nullableText("device_model"),
            androidRelease = platformFields.nullableText("android_release"),
            note = platformFields.text("note"),
        )
        if (platform.host && (platform.deviceModel != null || platform.androidRelease != null))
            fail("INVARIANT", "$.platform")

        val referenceFields = Fields(root["reference"], "$.reference")
        referenceFields.keys("kind", "independent", "description")
        val reference = ReferenceDescriptor(
            kind = enumOf(referenceFields.text("kind"), ReferenceKind.entries, "$.reference.kind"),
            independent = referenceFields.boolean("independent"),
            description = referenceFields.text("description"),
        )
        if (reference.kind == ReferenceKind.NONE && reference.independent)
            fail("INVARIANT", "$.reference.independent")

        val segmentArray = root.array("segments")
        if (segmentArray.size() > 64) fail("INVALID_TYPE", "$.segments")
        val segments = segmentArray.mapIndexed { index, element ->
            val fields = Fields(element, "$.segments[$index]")
            fields.keys("kind", "start_ns", "end_ns")
            val segment = Segment(
                kind = enumOf(fields.text("kind"), SegmentKind.entries, "$.segments[$index].kind"),
                startNs = fields.decimal("start_ns"),
                endNs = fields.decimal("end_ns"),
            )
            if (segment.endNs <= segment.startNs) fail("OUT_OF_RANGE", "$.segments[$index].end_ns")
            segment
        }
        segments.zipWithNext().forEachIndexed { index, (previous, current) ->
            if (current.startNs < previous.endNs) fail("INVARIANT", "$.segments[$index]")
        }

        val armArray = root.array("arms")
        if (armArray.isEmpty || armArray.size() > ArmId.entries.size) fail("INVALID_TYPE", "$.arms")
        val arms = armArray.mapIndexed { index, element -> decodeArm(element, "$.arms[$index]") }
        if (arms.map { it.armId }.toSet().size != arms.size) fail("DUPLICATE_ARM", "$.arms")

        arms.forEachIndexed { index, arm ->
            // Accuracy is a claim about error against a reference. Without a declared independent
            // reference the claim cannot exist, whatever the run measured.
            if (arm.accuracy != null && (reference.kind == ReferenceKind.NONE || !reference.independent))
                fail("INVARIANT", "$.arms[$index].accuracy")
        }

        return EvaluationReport(
            evaluationContractVersion = EvaluationReport.VERSION,
            evaluationId = root.text("evaluation_id"),
            createdUtcMs = root.decimal("created_utc_ms"),
            session = session,
            platform = platform,
            reference = reference,
            segments = segments,
            arms = arms,
        )
    }

    private fun decodeArm(element: JsonElement, path: String): Arm {
        val fields = Fields(element, path)
        fields.keys("arm_id", "label", "implementation", "status", "reason", "accuracy", "timing")
        val implementationFields = Fields(fields["implementation"], "$path.implementation")
        implementationFields.keys("name", "version")
        val arm = Arm(
            armId = enumOf(fields.text("arm_id"), ArmId.entries, "$path.arm_id"),
            label = fields.text("label"),
            implementation = ArmImplementation(
                implementationFields.text("name"),
                implementationFields.text("version"),
            ),
            status = enumOf(fields.text("status"), ArmStatus.entries, "$path.status"),
            reason = fields.nullableText("reason"),
            accuracy = if (fields["accuracy"].isJsonNull) null else decodeAccuracy(fields["accuracy"], "$path.accuracy"),
            timing = if (fields["timing"].isJsonNull) null else decodeTiming(fields["timing"], "$path.timing"),
        )
        if (arm.status == ArmStatus.EVALUATED) {
            if (arm.accuracy == null && arm.timing == null) fail("INVARIANT", path)
        } else {
            if (arm.reason == null) fail("INVARIANT", "$path.reason")
            if (arm.accuracy != null || arm.timing != null) fail("INVARIANT", path)
        }
        return arm
    }

    private fun decodeAccuracy(element: JsonElement, path: String): AccuracyMetrics {
        val fields = Fields(element, path)
        fields.keys(*accuracyKeys)
        val metrics = AccuracyMetrics(
            referenceConsumed = fields.boolean("reference_consumed"),
            outageDurationS = fields.nullableNumber("outage_duration_s"),
            outageDistanceM = fields.nullableNumber("outage_distance_m"),
            finalPositionErrorM = fields.nullableNumber("final_position_error_m"),
            driftPercent = fields.nullableNumber("drift_percent"),
            positionRmseM = fields.nullableNumber("position_rmse_m"),
            speedMaeMS = fields.nullableNumber("speed_mae_m_s"),
            speedRmseMS = fields.nullableNumber("speed_rmse_m_s"),
            headingErrorDeg = fields.nullableNumber("heading_error_deg"),
            recoveryConvergenceS = fields.nullableNumber("recovery_convergence_s"),
            recoveryThresholdM = fields.nullableNumber("recovery_threshold_m"),
            samples = fields.nullableInteger("samples"),
        )
        if (metrics.referenceConsumed) fail("INVARIANT", "$path.reference_consumed")
        if (metrics.recoveryConvergenceS != null && metrics.recoveryThresholdM == null)
            fail("INVARIANT", "$path.recovery_threshold_m")
        if (listOf(
                metrics.outageDurationS, metrics.outageDistanceM, metrics.finalPositionErrorM,
                metrics.driftPercent, metrics.positionRmseM, metrics.speedMaeMS, metrics.speedRmseMS,
                metrics.headingErrorDeg, metrics.recoveryConvergenceS, metrics.recoveryThresholdM,
                metrics.samples,
            ).all { it == null }
        ) fail("INVARIANT", path)
        return metrics
    }

    private fun decodeTiming(element: JsonElement, path: String): TimingMetrics {
        val fields = Fields(element, path)
        fields.keys(*timingKeys)
        val metrics = TimingMetrics(
            outputHz = fields.nullableNumber("output_hz"),
            inferenceLatencyP50Ms = fields.nullableNumber("inference_latency_p50_ms"),
            inferenceLatencyP95Ms = fields.nullableNumber("inference_latency_p95_ms"),
            endToEndP50Ms = fields.nullableNumber("end_to_end_p50_ms"),
            endToEndP95Ms = fields.nullableNumber("end_to_end_p95_ms"),
            queueHighWater = fields.nullableInteger("queue_high_water"),
            drops = fields.nullableInteger("drops"),
            errors = fields.nullableInteger("errors"),
            memoryPeakMb = fields.nullableNumber("memory_peak_mb"),
            samples = fields.nullableInteger("samples"),
        )
        if (metrics.outputHz != null && metrics.outputHz <= 0.0) fail("OUT_OF_RANGE", "$path.output_hz")
        listOf(
            metrics.inferenceLatencyP50Ms to metrics.inferenceLatencyP95Ms,
            metrics.endToEndP50Ms to metrics.endToEndP95Ms,
        ).forEach { (low, high) -> if (low != null && high != null && high < low) fail("INVARIANT", path) }
        if (listOf(
                metrics.outputHz, metrics.inferenceLatencyP50Ms, metrics.inferenceLatencyP95Ms,
                metrics.endToEndP50Ms, metrics.endToEndP95Ms, metrics.queueHighWater, metrics.drops,
                metrics.errors, metrics.memoryPeakMb, metrics.samples,
            ).all { it == null }
        ) fail("INVARIANT", path)
        return metrics
    }

    /** The canonical document. Decoding it reproduces the same typed report. */
    fun encode(report: EvaluationReport): String {
        val root = JsonObject()
        root.addProperty("evaluation_contract_version", EvaluationReport.VERSION)
        root.addProperty("evaluation_id", report.evaluationId)
        root.addProperty("created_utc_ms", report.createdUtcMs.toString())
        root.add("session", JsonObject().apply {
            addProperty("session_id", report.session.sessionId)
            addProperty("source", report.session.source)
            addProperty("contract_version", report.session.contractVersion)
            addProperty("duration_s", report.session.durationS)
            addProperty("records", report.session.records)
            addProperty("description", report.session.description)
        })
        root.add("platform", JsonObject().apply {
            addProperty("host", report.platform.host)
            add("device_model", report.platform.deviceModel?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("android_release", report.platform.androidRelease?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            addProperty("note", report.platform.note)
        })
        root.add("reference", JsonObject().apply {
            addProperty("kind", report.reference.kind.wire)
            addProperty("independent", report.reference.independent)
            addProperty("description", report.reference.description)
        })
        root.add("segments", JsonArray().apply {
            report.segments.forEach { segment ->
                add(JsonObject().apply {
                    addProperty("kind", segment.kind.wire)
                    addProperty("start_ns", segment.startNs.toString())
                    addProperty("end_ns", segment.endNs.toString())
                })
            }
        })
        root.add("arms", JsonArray().apply {
            report.arms.forEach { arm ->
                add(JsonObject().apply {
                    addProperty("arm_id", arm.armId.wire)
                    addProperty("label", arm.label)
                    add("implementation", JsonObject().apply {
                        addProperty("name", arm.implementation.name)
                        addProperty("version", arm.implementation.version)
                    })
                    addProperty("status", arm.status.wire)
                    add("reason", arm.reason?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                    add("accuracy", arm.accuracy?.let { metrics(encodeAccuracy(it)) } ?: JsonNull.INSTANCE)
                    add("timing", arm.timing?.let { metrics(encodeTiming(it)) } ?: JsonNull.INSTANCE)
                })
            }
        })
        // Newline-terminated with `\n` on every platform so the canonical document is identical
        // wherever it is produced.
        return PRETTY.toJson(root) + "\n"
    }

    private fun encodeAccuracy(metrics: AccuracyMetrics) = listOf(
        "reference_consumed" to metrics.referenceConsumed,
        "outage_duration_s" to metrics.outageDurationS,
        "outage_distance_m" to metrics.outageDistanceM,
        "final_position_error_m" to metrics.finalPositionErrorM,
        "drift_percent" to metrics.driftPercent,
        "position_rmse_m" to metrics.positionRmseM,
        "speed_mae_m_s" to metrics.speedMaeMS,
        "speed_rmse_m_s" to metrics.speedRmseMS,
        "heading_error_deg" to metrics.headingErrorDeg,
        "recovery_convergence_s" to metrics.recoveryConvergenceS,
        "recovery_threshold_m" to metrics.recoveryThresholdM,
        "samples" to metrics.samples,
    )

    private fun encodeTiming(metrics: TimingMetrics) = listOf(
        "output_hz" to metrics.outputHz,
        "inference_latency_p50_ms" to metrics.inferenceLatencyP50Ms,
        "inference_latency_p95_ms" to metrics.inferenceLatencyP95Ms,
        "end_to_end_p50_ms" to metrics.endToEndP50Ms,
        "end_to_end_p95_ms" to metrics.endToEndP95Ms,
        "queue_high_water" to metrics.queueHighWater,
        "drops" to metrics.drops,
        "errors" to metrics.errors,
        "memory_peak_mb" to metrics.memoryPeakMb,
        "samples" to metrics.samples,
    )

    private fun metrics(pairs: List<Pair<String, Any?>>): JsonObject = JsonObject().apply {
        pairs.forEach { (name, value) ->
            when (value) {
                null -> add(name, JsonNull.INSTANCE)
                is Boolean -> addProperty(name, value)
                is Long -> addProperty(name, value)
                is Double -> {
                    // A fixed precision keeps the document byte-stable across JVMs whose `Math`
                    // results may differ in the last bits; no metric here needs more.
                    addProperty(name, String.format(Locale.US, "%.6f", value).toDouble())
                }
                else -> fail("INVALID_TYPE", name)
            }
        }
    }

    /** Every wire value of these enums is its declaration name lowercased. */
    private fun <T : Enum<T>> enumOf(value: String, values: List<T>, path: String): T =
        values.firstOrNull { it.name.lowercase(Locale.US) == value } ?: fail("INVALID_ENUM", path)

    private val PRETTY = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
}
