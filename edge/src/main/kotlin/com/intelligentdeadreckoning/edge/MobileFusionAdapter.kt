package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Codec
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source

/** Edge runtime owns no fork of the INS/EKF; this adapter creates the mobile engine core. */
internal class MobileFusionAdapter(
    publicationIntervalNs: Long,
    config: FusionConfig = FusionConfig(),
) : NavigationEngine {
    private val delegate = FusionNavigationEngine(
        config = config,
        publicationIntervalNs = publicationIntervalNs,
    )

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) =
        delegate.initialize(session, calibration, mode)
    override fun acceptImu(measurement: Record) = delegate.acceptImu(measurement)
    override fun acceptGnss(measurement: Record) = delegate.acceptGnss(measurement)
    override fun drain() = delegate.drain()
    override fun stop() = delegate.stop()
    override fun reset() = delegate.reset()
}

/** Typed form of the frozen 1.1 edge output envelope; pass through Codec before publishing. */
data class EdgeNavigationOutput(
    val header: Header,
    val measurementTimeNs: Long,
    val receivedTimeNs: Long,
    val payloadType: String,
    val payload: Payload,
) {
    fun asRecord(): Record = Record(
        header,
        Event("edge-${measurementTimeNs}-${payloadType}", measurementTimeNs, receivedTimeNs, payload),
    ).also { Codec.encodeJson(it) }
}

/** Construct valid, versioned edge input session and an honest absent calibration record. */
internal fun edgeSession(sessionId: String, originNs: Long): Pair<EngineSession, Record> {
    val header = Header(sessionId, Source.REAL, ENGINE_OUTPUT_CONTRACT_VERSION)
    val calibration = CalibrationResult(
        id = "edge-no-calibration",
        status = CalibrationStatus.PENDING,
        q_vehicle_from_device_wxyz = null,
        gyro_bias_rad_s = null,
        accelerometer_bias_m_s2 = null,
        confidence = null,
    )
    return EngineSession(header, "edge-process", originNs) to
        Record(header, Event("0", originNs, originNs, calibration))
}
