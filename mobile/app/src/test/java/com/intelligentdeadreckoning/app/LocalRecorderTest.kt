package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.recording.*
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRecorderTest {
    @Test fun acquisitionDiagnosticsBeforeTriggeringMeasurementPreserveArrivalOrder() = runTest {
        val emitted = mutableListOf<Record>()
        val processor = com.intelligentdeadreckoning.app.acquisition.AcquisitionProcessor(Header("acq-1", Source.REAL), ns, emitted::add)
        val payload = ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
            ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(0.0, 0.0, 9.81), SensorAccuracy.HIGH)
        processor.accept(com.intelligentdeadreckoning.app.acquisition.Sample(ns, ns, payload))
        processor.accept(com.intelligentdeadreckoning.app.acquisition.Sample(ns + 200_000_000, ns + 400_000_000, payload))
        assertEquals(listOf("0", "2", "3", "1"), emitted.map { it.event.event_id })
        val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 500_000_000 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.start(metadata(), emitted.asFlow()); runCurrent()
        assertEquals(RecorderPhase.COMPLETED, recorder.state.value.phase)
        assertEquals(0L, recorder.state.value.eventIdGaps)
        assertEquals(emitted, storage.records.map { RecordingCodec.decodeRecord(it, storage.finalized!!) })
        recorder.close()
    }
    @get:Rule val temporary = TemporaryFolder()
    private val ns = 9_007_199_254_740_993L
    private fun metadata(id: String = "recording-1") = RecordingMetadata(
        id, "acq-1", Source.REAL, RecordingStartState.RECORDING, null, CompletionState.OPEN, RecoveryState.NONE,
        100, 100, null, ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS, null, "acq-1", ns, ns, null),
        DeviceInfo(null, null, null, null, null), ApplicationInfo(null, null, null, null), emptyList(),
        SourceConfiguration(LocationPermissionState.DENIED, null, null, null, null, true),
        CalibrationInfo(CalibrationApplication.NOT_APPLIED, null), null, null,
    )
    private fun record(id: Long = 0) = Record(Header("acq-1", Source.REAL),
        Event(id.toString(), ns + id, ns + id + 1, DiagnosticEvent(Severity.INFO, "TEST", "fixture", 0)))
    private class MemoryStorage : RecordingStorage {
        var initial: RecordingMetadata? = null
        var finalized: RecordingMetadata? = null
        val records = mutableListOf<ByteArray>()
        var appendError = false
        var createError = false
        var permissionError = false
        var finalizeError = false
        var closeError = false
        var syncError = false
        var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)
        override fun create(metadata: RecordingMetadata): RecordingOutput {
            if (permissionError) throw SecurityException("permission denied synthetic")
            if (createError) throw IOException("storage unavailable")
            initial = metadata
            return object : RecordingOutput {
                override fun append(bytes: ByteArray) {
                    entered.countDown()
                    check(gate?.await(5, TimeUnit.SECONDS) != false) { "test writer gate timeout" }
                    if (appendError) throw IOException("ENOSPC synthetic")
                    records.add(bytes)
                }
                override fun sync() { if (syncError) throw IOException("sync failure") }
                override fun close() { if (closeError) throw IOException("close failure") }
            }
        }
        override fun finalize(metadata: RecordingMetadata) {
            if (finalizeError) throw IOException("rename failure")
            finalized = metadata
        }
        override fun recover() = RecoverySummary()
    }

    @Test fun startStopExactTimestampsAndMetadataCounts() = runTest {
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = dispatcher, collectorDispatcher = dispatcher)
        val stream = MutableSharedFlow<Record>(extraBufferCapacity = 16)
        assertTrue(recorder.start(metadata(), stream))
        assertFalse(recorder.start(metadata("other"), stream))
        runCurrent()
        assertEquals(RecorderPhase.RECORDING, recorder.state.value.phase)
        stream.emit(record()); stream.emit(record(1)); runCurrent()
        recorder.stop(); runCurrent()
        assertEquals(RecorderPhase.COMPLETED, recorder.state.value.phase)
        assertEquals(2L, storage.finalized!!.recordCount)
        assertEquals(2L, storage.finalized!!.channelCounts!!.sumOf { it.count })
        assertEquals(ns + 50, storage.finalized!!.clock.endedNs)
        assertEquals(record(), RecordingCodec.decodeRecord(storage.records.first(), storage.finalized!!))
        stream.emit(record(2)); runCurrent()
        assertEquals(2, storage.records.size)
        recorder.close()
    }
    @Test fun emptySessionAndStopDuringStartup() = runTest {
        val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns }, { 100 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.start(metadata(), MutableSharedFlow()); recorder.stop(); runCurrent()
        assertEquals(CompletionState.COMPLETED, storage.finalized!!.completionState)
        assertEquals(0L, storage.finalized!!.recordCount)
        recorder.close()
    }
    @Test fun slowWriterOverflowIsExplicitAndProducerContinues() = runBlocking {
        val storage = MemoryStorage().apply { gate = CountDownLatch(1) }
        val recorder = LocalRecorder(storage, { ns + 100 }, { 200 }, capacity = 2,
            collectorDispatcher = Dispatchers.Unconfined)
        val stream = MutableSharedFlow<Record>()
        try {
            recorder.start(metadata(), stream)
            stream.emit(record())
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            stream.emit(record(1)); stream.emit(record(2)); stream.emit(record(3))
            assertEquals(1L, recorder.state.value.dropped)
            assertEquals(RecorderPhase.FINALIZING, recorder.state.value.phase)
            // No active recorder subscriber now; acquisition publisher can keep emitting.
            withTimeout(1000) { stream.emit(record(4)) }
            storage.gate!!.countDown()
            withTimeout(5000) { recorder.state.first { it.phase == RecorderPhase.FAILED } }
            assertEquals(3L, recorder.state.value.written)
            assertEquals(CompletionState.FAILED, storage.finalized!!.completionState)
            assertTrue(recorder.state.value.message.contains("OVERFLOW"))
        } finally { storage.gate!!.countDown(); recorder.close() }
    }
    @Test fun writeCreateSyncCloseAndFinalizationFailuresAreVisible() = runTest {
        for (failure in listOf("append", "create", "permission", "sync", "close", "finalize")) {
            val storage = MemoryStorage().apply {
                appendError = failure == "append"; createError = failure == "create"
                permissionError = failure == "permission"
                syncError = failure == "sync"; closeError = failure == "close"; finalizeError = failure == "finalize"
            }
            val d = StandardTestDispatcher(testScheduler)
            val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
            val stream = MutableSharedFlow<Record>(extraBufferCapacity = 4)
            recorder.start(metadata(), stream); runCurrent()
            stream.emit(record()); runCurrent(); recorder.stop(); runCurrent()
            assertEquals(failure, RecorderPhase.FAILED, recorder.state.value.phase)
            assertTrue(failure, recorder.state.value.writeErrors > 0)
            if (failure == "create") assertNull(storage.finalized)
            recorder.close()
        }
    }
    @Test fun lifecycleAcquisitionStopAndSourceSwitchFinalizeWithoutRestart() = runTest {
        for (reason in listOf("Foreground owner stopped.", "Acquisition stopped.", "Source switched.")) {
            val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
            val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
            val stream = MutableSharedFlow<Record>(extraBufferCapacity = 4)
            recorder.start(metadata(), stream); runCurrent()
            stream.emit(record()); runCurrent(); recorder.stop(reason); runCurrent()
            stream.emit(record(1)); runCurrent()
            assertEquals(1L, storage.finalized!!.recordCount)
            assertEquals(RecorderPhase.COMPLETED, recorder.state.value.phase)
            recorder.close()
        }
    }
    @Test fun ownerCloseDrainsAndDisallowsRestart() = runTest {
        val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.start(metadata(), flowOf(record())); recorder.close(); runCurrent()
        assertEquals(CompletionState.COMPLETED, storage.finalized!!.completionState)
        assertFalse(recorder.start(metadata(), emptyFlow()))
    }
    @Test fun mismatchedSessionFailsAndNeverWritesTheNewSource() = runTest {
        val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.start(metadata(), flowOf(record().copy(header = Header("new", Source.SIMULATION))))
        runCurrent()
        assertEquals(RecorderPhase.FAILED, recorder.state.value.phase)
        assertEquals(1L, recorder.state.value.dropped)
        assertTrue(storage.records.isEmpty()); recorder.close()
    }
    @Test fun idGapsAndDuplicateIdsAreDetectedWithoutRewritingRecords() = runTest {
        val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.start(metadata(), flowOf(record(), record(3), record(3))); runCurrent()
        assertEquals(2L, recorder.state.value.eventIdGaps)
        assertEquals(RecorderPhase.FAILED, recorder.state.value.phase)
        assertEquals(2L, recorder.state.value.written); recorder.close()
    }

    private fun interrupted(tail: ByteArray): Pair<FileRecordingStorage, File> {
        val root = temporary.newFolder()
        val storage = FileRecordingStorage(root)
        storage.create(metadata()).use { it.append(RecordingCodec.encodeRecord(record(), metadata())); it.sync() }
        // Emulate a previous process's interrupted metadata while releasing this process's lease.
        storage.finalize(metadata())
        val file = File(root, "recording-1/measurements.jsonl")
        file.appendBytes(tail)
        return storage to file
    }
    private fun readMetadata(file: File) = RecordingCodec.decodeMetadata(File(file.parentFile, "metadata.json").readBytes())

    @Test fun trailingPartialRecoveryRetainsOnlyValidPrefixAndMarksIncomplete() {
        val raw = RecordingCodec.encodeRecord(record(1), metadata())
        val (storage, file) = interrupted(raw.copyOf(raw.size - 12))
        val recovery = storage.recover()
        assertEquals(1, recovery.recovered)
        assertTrue(recovery.message.contains("trimmed trailing bytes: ${raw.size - 12}"))
        val meta = readMetadata(file)
        assertEquals(CompletionState.INCOMPLETE, meta.completionState)
        assertEquals(RecoveryState.RECOVERED, meta.recoveryState)
        assertEquals(1L, meta.recordCount)
        assertEquals(listOf(record()), file.inputStream().use { Codec.readJsonl(it).toList() })
        assertEquals(0, storage.recover().recovered)
    }
    @Test fun completeLastRecordWithoutNewlineIsKept() {
        val (storage, file) = interrupted(RecordingCodec.encodeRecord(record(1), metadata()))
        val bytes = file.readBytes()
        assertEquals(1, storage.recover().recovered)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(2L, readMetadata(file).recordCount)
    }
    @Test fun recoveryAcceptsDiagnosticIdReorderingWithoutSorting() {
        val root = temporary.newFolder(); val storage = FileRecordingStorage(root)
        val records = listOf(record(0), record(2), record(3), record(1))
        storage.create(metadata()).use { output ->
            records.forEach { output.append(RecordingCodec.encodeRecord(it, metadata())) }
            output.sync()
        }
        storage.finalize(metadata()) // Fixture for an interrupted previous process.
        assertEquals(1, storage.recover().recovered)
        val file = File(root, "recording-1/measurements.jsonl")
        assertEquals(records, file.inputStream().use { Codec.readJsonl(it).toList() })
        assertEquals(4L, readMetadata(file).recordCount)
    }
    @Test fun corruptInteriorCompleteTailDuplicateAndInvalidUtf8AreNeverTrimmed() {
        for (tail in listOf("{broken}\n{}\n".toByteArray(), "{}".toByteArray(),
            "{\"a\":1 x".toByteArray(), "{\"a\":1,}".toByteArray(),
            "{\"a\":1,\"a\":".toByteArray(), byteArrayOf(0xc3.toByte()),
            RecordingCodec.encodeRecord(record(), metadata()) + byteArrayOf(10))) {
            val (storage, file) = interrupted(tail); val before = file.readBytes()
            assertEquals(1, storage.recover().failed)
            assertArrayEquals(before, file.readBytes())
            assertEquals(RecoveryState.UNRECOVERABLE, readMetadata(file).recoveryState)
        }
    }
    @Test fun realFilesFinalizeRoundTripAndDoNotOverwriteExistingSession() = runTest {
        val root = temporary.newFolder(); val storage = FileRecordingStorage(root)
        val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.start(metadata(), flowOf(record())); runCurrent()
        val file = File(root, "recording-1/measurements.jsonl")
        val before = File(file.parentFile, "metadata.json").readBytes()
        assertEquals(1L, readMetadata(file).recordCount)
        recorder.start(metadata(), flowOf(record(1))); runCurrent()
        assertEquals(RecorderPhase.FAILED, recorder.state.value.phase)
        assertArrayEquals(before, File(file.parentFile, "metadata.json").readBytes())
        recorder.close()
    }
    @Test fun startupRecoveryBlocksStartAndExposesRecoveredState() = runTest {
        val (storage, _) = interrupted(byteArrayOf())
        val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns }, { 100 }, ioDispatcher = d, collectorDispatcher = d)
        recorder.loadInterrupted()
        assertFalse(recorder.start(metadata("new"), emptyFlow()))
        runCurrent()
        assertEquals(RecorderPhase.RECOVERED, recorder.state.value.phase)
        recorder.close()
    }
    @Test fun recoveryDoesNotTouchAnotherOwnersActiveOrFinalizingSession() {
        val root = temporary.newFolder(); val storage = FileRecordingStorage(root)
        val output = storage.create(metadata())
        output.append(RecordingCodec.encodeRecord(record(), metadata()))
        assertEquals(0, FileRecordingStorage(root).recover().recovered)
        output.close()
        assertEquals(0, FileRecordingStorage(root).recover().recovered)
        storage.finalize(metadata()) // Release process lease, leave interrupted metadata for fixture.
        assertEquals(1, FileRecordingStorage(root).recover().recovered)
    }
    @Test fun missingMetadataAndUnsupportedVersionRemainUntouched() {
        val root = temporary.newFolder()
        val dir = File(root, "unknown").apply { mkdir() }
        assertEquals(1, FileRecordingStorage(root).recover().failed)
        val file = File(dir, "metadata.json")
        val raw = RecordingCodec.encodeMetadata(metadata("unknown")).toString(Charsets.UTF_8)
            .replace("\"recording_contract_version\":\"1.0.0\"", "\"recording_contract_version\":\"9.0.0\"").toByteArray()
        file.writeBytes(raw)
        assertEquals(1, FileRecordingStorage(root).recover().failed)
        assertArrayEquals(raw, file.readBytes())
    }
    @Test fun nullableGnssAndOriginalSimulationSourceRoundTrip() = runTest {
        val storage = MemoryStorage(); val d = StandardTestDispatcher(testScheduler)
        val recorder = LocalRecorder(storage, { ns + 50 }, { 200 }, ioDispatcher = d, collectorDispatcher = d)
        val meta = metadata().copy(source = Source.SIMULATION)
        val gnss = Record(Header("acq-1", Source.SIMULATION), Event("0", ns, ns + 1,
            GnssMeasurement(12.0, 77.0, null, null, null, null, null, null, null, "gps", null)))
        recorder.start(meta, flowOf(gnss)); runCurrent()
        assertEquals(gnss, RecordingCodec.decodeRecord(storage.records.single(), storage.finalized!!))
        assertEquals(Source.SIMULATION, storage.finalized!!.source)
        recorder.close()
    }
}
