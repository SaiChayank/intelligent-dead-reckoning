package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.app.calibration.isProperRotation
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.contracts.v1.*
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Single-process runtime around the exact production navigation engine. */
class EdgeRuntime(
    private val ingressCapacity: Int = 1024,
    private val outputCapacity: Int = 1024,
    private val maximumPairSkewNs: Long = 30_000_000L,
    private val maximumReceiptLagNs: Long = 100_000_000L,
    private val receiptClockComparable: Boolean = false,
    publicationIntervalNs: Long = 100_000_000L,
    private val monotonicClockNs: () -> Long = System::nanoTime,
    private val config: FusionConfig = FusionConfig(),
    private val latencyWindow: Int = 20_000,
    private val profile: Boolean = true,
    private val inference: OnnxInferenceAdapter = NoOnnxModel,
) : AutoCloseable {
    init {
        require(ingressCapacity >= 2 && outputCapacity >= 2)
        require(maximumPairSkewNs >= 0L && maximumReceiptLagNs >= 0L)
        require(publicationIntervalNs >= 0L && latencyWindow >= 2)
    }

    private val engine = MobileFusionAdapter(publicationIntervalNs, config)
    private val ingress = ArrayBlockingQueue<QueuedRecord>(ingressCapacity)
    private val output = ArrayBlockingQueue<Record>(outputCapacity)
    private val acceptedImu = AtomicLong()
    private val acceptedOptionalImu = AtomicLong()
    private val acceptedGnss = AtomicLong()
    private val navigationStateCount = AtomicLong()
    private val rejected = AtomicLong()
    private val invalid = AtomicLong()
    private val late = AtomicLong()
    private val duplicate = AtomicLong()
    private val dropped = AtomicLong()
    private val outputDropped = AtomicLong()
    private val ingressHighWater = AtomicInteger()
    private val outputHighWater = AtomicInteger()
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val admissionLock = Any()
    private var sessionStarted = false
    private val seenIds = HashSet<String>()
    private val lastChannelNs = HashMap<String, Long>()

    private val accelSourceRate = RateAccumulator()
    private val gyroSourceRate = RateAccumulator()
    private val gnssSourceRate = RateAccumulator()
    private val accelAdmissionRate = RateAccumulator()
    private val gyroAdmissionRate = RateAccumulator()
    private val gnssAdmissionRate = RateAccumulator()
    private val accelProcessingRate = RateAccumulator()
    private val gyroProcessingRate = RateAccumulator()
    private val gnssProcessingRate = RateAccumulator()
    private val pairedSourceRate = RateAccumulator()
    private val pairedProcessingRate = RateAccumulator()
    private val outputSourceRate = RateAccumulator()
    private val outputProcessingRate = RateAccumulator()
    private val cycleLatency = BoundedLatencies(latencyWindow)
    private val stageLatency = HashMap<String, BoundedLatencies>()
    private val stageLock = Any()

    private val memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val peakHeapBytes = AtomicLong()
    private val workerStartCpuNs = AtomicLong(-1L)
    private val workerEndCpuNs = AtomicLong(-1L)
    private val workerStartWallNs = AtomicLong(-1L)
    private val workerEndWallNs = AtomicLong(-1L)

    @Volatile private var header: Header? = null
    @Volatile private var originNs = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var calibrationRecord: Record? = null
    private var pendingAccel: Record? = null
    private var pendingGyro: Record? = null
    private var lastPublishedMeasurementNs: Long? = null

    /** Start requires valid calibrated external IMU semantics; no frame is guessed. */
    @Synchronized
    fun start(sessionHeader: Header, sessionOriginNs: Long, calibration: Record): Boolean {
        if (sessionStarted || running.get() || stopping.get() ||
            sessionHeader.contract_version != ENGINE_OUTPUT_CONTRACT_VERSION ||
            sessionHeader.source != Source.REAL || sessionOriginNs < 0L ||
            !validCalibration(calibration, sessionHeader, sessionOriginNs)
        ) {
            rejected.incrementAndGet()
            invalid.incrementAndGet()
            return false
        }
        header = sessionHeader
        originNs = sessionOriginNs
        calibrationRecord = calibration
        lastPublishedMeasurementNs = null
        val session = EngineSession(sessionHeader, "edge-process", sessionOriginNs)
        timed("initialize") { engine.initialize(session, calibration, InitializationMode.DEPLOYABLE) }
        drainEngine()
        synchronized(admissionLock) {
            sessionStarted = true
            stopping.set(false)
            running.set(true)
            worker = Thread(::consume, "edge-navigation-engine").apply { isDaemon = true; start() }
        }
        return true
    }

    /** Strictly validate and enqueue a contract record without resampling or blocking. */
    fun offer(record: Record): Boolean {
        if (!running.get()) return false
        val expectedHeader = header ?: return false
        val calibrationTimeNs = calibrationRecord?.event?.t_ns ?: originNs
        val channel = try {
            timed("input_validation") {
                EdgeInputValidator.validate(record, expectedHeader, originNs, calibrationTimeNs)
            }
        } catch (e: EdgeInputValidationException) {
            rejected.incrementAndGet()
            when (e.rejection) {
                InputRejection.INVALID -> invalid.incrementAndGet()
                InputRejection.LATE -> late.incrementAndGet()
                InputRejection.DUPLICATE -> duplicate.incrementAndGet()
            }
            return false
        } catch (_: RuntimeException) {
            rejected.incrementAndGet(); invalid.incrementAndGet(); return false
        }

        synchronized(seenIds) {
            val previous = lastChannelNs[channel]
            if (previous != null && record.event.t_ns <= previous) {
                rejected.incrementAndGet(); duplicate.incrementAndGet(); return false
            }
            if (seenIds.size >= MAX_SESSION_EVENTS) {
                rejected.incrementAndGet(); invalid.incrementAndGet(); return false
            }
            if (!seenIds.add(record.event.event_id)) {
                rejected.incrementAndGet(); duplicate.incrementAndGet(); return false
            }
            lastChannelNs[channel] = record.event.t_ns
        }

        val admittedAtNs = monotonicClockNs()
        if (receiptClockComparable) {
            val receiptAgeNs = admittedAtNs - record.event.received_ns
            if (record.event.received_ns > admittedAtNs || receiptAgeNs > maximumReceiptLagNs) {
                rejected.incrementAndGet(); late.incrementAndGet(); return false
            }
        }
        synchronized(admissionLock) {
            if (!running.get()) return false
            if (!ingress.offer(QueuedRecord(record, admittedAtNs))) { dropped.incrementAndGet(); return false }
            when (val payload = record.event.data) {
                is ImuMeasurement -> when (payload.sensor) {
                    Sensor.ACCELEROMETER -> { accelSourceRate.add(record.event.t_ns); accelAdmissionRate.add(admittedAtNs) }
                    Sensor.GYROSCOPE -> { gyroSourceRate.add(record.event.t_ns); gyroAdmissionRate.add(admittedAtNs) }
                    else -> acceptedOptionalImu.incrementAndGet()
                }
                is GnssMeasurement -> { gnssSourceRate.add(record.event.t_ns); gnssAdmissionRate.add(admittedAtNs) }
                else -> error("validator admitted non-input record")
            }
            ingressHighWater.accumulateAndGet(ingress.size) { old, now -> maxOf(old, now) }
        }
        return true
    }

    fun drain(maximum: Int = Int.MAX_VALUE): List<Record> {
        require(maximum >= 0)
        val result = ArrayList<Record>(minOf(maximum, output.size))
        repeat(minOf(maximum, output.size)) { output.poll()?.let(result::add) }
        return result
    }

    fun metrics(): EdgeRuntimeMetrics {
        val cpuNs = if (workerStartCpuNs.get() >= 0L && workerEndCpuNs.get() >= 0L) {
            (workerEndCpuNs.get() - workerStartCpuNs.get()).coerceAtLeast(0L)
        } else null
        val wallNs = if (workerStartWallNs.get() >= 0L && workerEndWallNs.get() >= 0L) {
            (workerEndWallNs.get() - workerStartWallNs.get()).coerceAtLeast(0L)
        } else null
        val stages = synchronized(stageLock) { stageLatency.mapValues { it.value.snapshot() } }
        return EdgeRuntimeMetrics(
            phase = when { running.get() -> "running"; stopping.get() -> "stopping"; worker != null -> "stopped"; else -> "idle" },
            accelerometerInput = accelSourceRate.snapshot(), accelerometerInputWall = accelAdmissionRate.snapshot(),
            gyroscopeInput = gyroSourceRate.snapshot(), gyroscopeInputWall = gyroAdmissionRate.snapshot(),
            gnssInput = gnssSourceRate.snapshot(), gnssInputWall = gnssAdmissionRate.snapshot(),
            navigationOutput = outputSourceRate.snapshot(), pairedImuSource = pairedSourceRate.snapshot(),
            imuProcessing = merge(accelProcessingRate.snapshot(), gyroProcessingRate.snapshot()),
            gnssProcessing = gnssProcessingRate.snapshot(), pairedImuProcessing = pairedProcessingRate.snapshot(),
            navigationOutputProcessing = outputProcessingRate.snapshot(),
            ingressDepth = ingress.size, ingressCapacity = ingressCapacity, ingressHighWater = ingressHighWater.get(),
            outputDepth = output.size, outputCapacity = outputCapacity, outputHighWater = outputHighWater.get(),
            acceptedImuRecords = acceptedImu.get(), acceptedOptionalImuRecords = acceptedOptionalImu.get(),
            acceptedGnssRecords = acceptedGnss.get(), navigationStateCount = navigationStateCount.get(),
            dropped = dropped.get(), rejected = rejected.get(), late = late.get(), invalid = invalid.get(),
            duplicate = duplicate.get(), outputDropped = outputDropped.get(), aiAvailable = inference.available,
            inferenceUnavailableReason = inference.unavailableReason, stageLatency = stages,
            totalCycleLatency = cycleLatency.snapshot(), workerWallTimeNs = wallNs, workerCpuTimeNs = cpuNs,
            peakHeapBytesSampled = peakHeapBytes.get().takeIf { it > 0L }, peakRssBytesSampled = null,
        )
    }

    /** Stop admission atomically, drain every accepted record, then close the engine. */
    fun stop(timeout: Long = 30, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val thread = synchronized(admissionLock) {
            if (!running.getAndSet(false)) return false
            stopping.set(true)
            worker
        }
        thread?.join(unit.toMillis(timeout))
        if (thread?.isAlive == true) return false
        timed("stop") { drainEngine(); engine.stop(); drainEngine() }
        stopping.set(false)
        return true
    }

    override fun close() { stop() }

    private fun consume() {
        val threadBean = ManagementFactory.getThreadMXBean()
        if (threadBean.isCurrentThreadCpuTimeSupported) {
            if (!threadBean.isThreadCpuTimeEnabled) runCatching { threadBean.isThreadCpuTimeEnabled = true }
            workerStartCpuNs.set(threadBean.currentThreadCpuTime)
        }
        workerStartWallNs.set(monotonicClockNs())
        while (running.get() || ingress.isNotEmpty()) {
            val queued = ingress.poll(50, TimeUnit.MILLISECONDS) ?: continue
            try {
                process(queued.record)
                drainEngine()
            } catch (_: RuntimeException) {
                rejected.incrementAndGet()
            } finally {
                val finishedNs = monotonicClockNs()
                cycleLatency.add(finishedNs - queued.admittedAtNs)
                when (val payload = queued.record.event.data) {
                    is ImuMeasurement -> when (payload.sensor) {
                        Sensor.ACCELEROMETER -> accelProcessingRate.add(queued.record.event.t_ns)
                        Sensor.GYROSCOPE -> gyroProcessingRate.add(queued.record.event.t_ns)
                        else -> Unit
                    }
                    is GnssMeasurement -> gnssProcessingRate.add(queued.record.event.t_ns)
                    else -> Unit
                }
                val used = memoryBean.heapMemoryUsage.used
                peakHeapBytes.accumulateAndGet(used) { old, now -> maxOf(old, now) }
            }
        }
        if (pendingAccel != null) rejected.incrementAndGet()
        if (pendingGyro != null) rejected.incrementAndGet()
        pendingAccel = null
        pendingGyro = null
        workerEndWallNs.set(monotonicClockNs())
        if (threadBean.isCurrentThreadCpuTimeSupported && threadBean.isThreadCpuTimeEnabled) {
            workerEndCpuNs.set(threadBean.currentThreadCpuTime)
        }
    }

    private fun process(record: Record) {
        when (val payload = record.event.data) {
            is GnssMeasurement -> timed("gnss_gate_ins_ekf_confidence") {
                acceptedGnss.incrementAndGet()
                engine.acceptGnss(record)
            }
            is ImuMeasurement -> when (payload.sensor) {
                Sensor.ACCELEROMETER -> { if (pendingAccel != null) rejected.incrementAndGet(); pendingAccel = record; pairIfReady() }
                Sensor.GYROSCOPE -> { if (pendingGyro != null) rejected.incrementAndGet(); pendingGyro = record; pairIfReady() }
                else -> rejected.incrementAndGet()
            }
            else -> rejected.incrementAndGet()
        }
    }

    private fun pairIfReady() {
        val accel = pendingAccel ?: return
        val gyro = pendingGyro ?: return
        if (absDifference(accel.event.t_ns, gyro.event.t_ns) > maximumPairSkewNs) {
            if (accel.event.t_ns < gyro.event.t_ns) pendingAccel = null else pendingGyro = null
            rejected.incrementAndGet()
            return
        }
        pendingAccel = null
        pendingGyro = null
        timed("shared_preprocessing_pairing") { Unit }
        timed("ins_ekf_constraints_confidence") { engine.acceptImu(accel); engine.acceptImu(gyro) }
        acceptedImu.addAndGet(2L)
        pairedSourceRate.add(maxOf(accel.event.t_ns, gyro.event.t_ns))
        pairedProcessingRate.add(monotonicClockNs())
    }

    private fun drainEngine() {
        for (record in engine.drain()) {
            when (val payload = record.event.data) {
                is NavigationState -> {
                    if (record.header != header) { rejected.incrementAndGet(); continue }
                    val previous = lastPublishedMeasurementNs
                    if (previous != null && record.event.t_ns < previous) { rejected.incrementAndGet(); continue }
                    if (payload.initialization_mode != InitializationMode.DEPLOYABLE) { rejected.incrementAndGet(); continue }
                    if (previous != null && record.event.t_ns == previous) continue
                    timed("output_contract_validation") { Codec.encodeJson(record) }
                    lastPublishedMeasurementNs = record.event.t_ns
                    outputSourceRate.add(record.event.t_ns)
                    navigationStateCount.incrementAndGet()
                    outputProcessingRate.add(monotonicClockNs())
                    publish(record)
                }
                is Confidence, is DiagnosticEvent, is GnssQualityState, is CalibrationResult -> {
                    if (record.header == header) publish(record) else rejected.incrementAndGet()
                }
                else -> rejected.incrementAndGet()
            }
        }
    }

    private fun publish(record: Record) {
        if (record.event.data !is NavigationState) timed("output_contract_validation") { Codec.encodeJson(record) }
        if (!output.offer(record)) outputDropped.incrementAndGet()
        outputHighWater.accumulateAndGet(output.size) { old, now -> maxOf(old, now) }
    }

    private fun validCalibration(record: Record, expectedHeader: Header, sessionOriginNs: Long): Boolean = try {
        require(record.header == expectedHeader && record.event.t_ns >= sessionOriginNs)
        Codec.encodeJson(record)
        val calibration = record.event.data as? CalibrationResult ?: error("calibration payload required")
        require(calibration.status == CalibrationStatus.VALID)
        require(isProperRotation(calibration.q_vehicle_from_device_wxyz ?: error("missing mounting rotation"), 1e-6))
        require(calibration.gyro_bias_rad_s != null)
        true
    } catch (_: RuntimeException) { false }

    private fun merge(a: RateSummary, b: RateSummary): RateSummary {
        val count = a.count + b.count
        val first = listOfNotNull(a.firstTimestampNs, b.firstTimestampNs).minOrNull()
        val last = listOfNotNull(a.lastTimestampNs, b.lastTimestampNs).maxOrNull()
        val hz = if (count > 1 && first != null && last != null && last > first) (count - 1) * 1e9 / (last - first) else null
        val minInterval = listOfNotNull(a.minimumIntervalNs, b.minimumIntervalNs).minOrNull()
        val maxInterval = listOfNotNull(a.maximumIntervalNs, b.maximumIntervalNs).maxOrNull()
        return RateSummary(count, first, last, hz, minInterval, maxInterval)
    }

    private inline fun <T> timed(stage: String, action: () -> T): T {
        if (!profile) return action()
        val started = monotonicClockNs()
        try { return action() } finally {
            synchronized(stageLock) { stageLatency.getOrPut(stage) { BoundedLatencies(latencyWindow) }.add(monotonicClockNs() - started) }
        }
    }

    private fun absDifference(a: Long, b: Long): Long = if (a >= b) a - b else b - a
    private data class QueuedRecord(val record: Record, val admittedAtNs: Long)
    private companion object { const val MAX_SESSION_EVENTS = 1_000_000 }
}
