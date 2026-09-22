package com.intelligentdeadreckoning.app

import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.recording.*
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Disposable, app-private fixtures only. Does not fill the phone or touch real recordings. */
class RecordingStorageDeviceTest {
    private fun metadata() = RecordingMetadata(
        "fixture", "fixture-acquisition", Source.REAL, RecordingStartState.RECORDING, null,
        CompletionState.OPEN, RecoveryState.NONE, null, null, null,
        ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS, null, "fixture-clock", 10, 10, null),
        DeviceInfo(null, null, null, null, null), ApplicationInfo(null, null, null, null), emptyList(),
        SourceConfiguration(LocationPermissionState.DENIED, null, null, null, null, true),
        CalibrationInfo(CalibrationApplication.NOT_APPLIED, null), null, null,
    )
    @Test fun unavailablePrivateDirectoryFailsExplicitlyWithoutBlockingPublisher() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "recorder-device-test-${UUID.randomUUID()}")
        check(root.mkdir())
        val blocked = File(root, "blocked").apply { writeText("file, not a directory") }
        val recorder = LocalRecorder(FileRecordingStorage(blocked), { 20 }, { 100 })
        try {
            val stream = MutableSharedFlow<Record>(extraBufferCapacity = 8)
            assertTrue(recorder.start(metadata(), stream))
            val failed = withTimeout(10_000) { recorder.state.first { it.phase == RecorderPhase.FAILED } }
            assertTrue(failed.writeErrors > 0)
            assertTrue(failed.message.contains("WRITE_FAILURE"))
            withTimeout(1000) { stream.emit(Record(Header("fixture-acquisition", Source.REAL),
                Event("0", 10, 10, DiagnosticEvent(Severity.INFO, "TEST", "fixture", 0)))) }
            assertEquals("file, not a directory", blocked.readText())
        } finally { recorder.close(); root.deleteRecursively() }
    }

    @Test fun injectedWriteFailurePersistsFailedMetadataOnAndroidFilesystem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "recorder-device-test-${UUID.randomUUID()}")
        val files = FileRecordingStorage(root)
        val faulty = object : RecordingStorage by files {
            override fun create(metadata: RecordingMetadata): RecordingOutput {
                val output = files.create(metadata)
                return object : RecordingOutput by output {
                    override fun append(bytes: ByteArray) { throw IOException("ENOSPC injected test failure") }
                }
            }
        }
        val recorder = LocalRecorder(faulty, { 20 }, { 100 })
        try {
            val record = Record(Header("fixture-acquisition", Source.REAL),
                Event("0", 10, 10, DiagnosticEvent(Severity.INFO, "TEST", "fixture", 0)))
            recorder.start(metadata(), flowOf(record))
            val failed = withTimeout(10_000) { recorder.state.first { it.phase == RecorderPhase.FAILED } }
            assertEquals(1L, failed.dropped)
            assertEquals(1L, failed.writeErrors)
            val persisted = RecordingCodec.decodeMetadata(File(root, "fixture/metadata.json").readBytes())
            assertEquals(CompletionState.FAILED, persisted.completionState)
            assertEquals(RecoveryState.REQUIRED, persisted.recoveryState)
            assertEquals(0L, persisted.recordCount)
        } finally { recorder.close(); root.deleteRecursively() }
    }
}
