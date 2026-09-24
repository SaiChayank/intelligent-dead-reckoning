package com.intelligentdeadreckoning.app

import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.sessions.*
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled slow destination: tests cancellation on real Android, not storage speed. */
class ExportLifecycleDeviceTest {
    @Test fun backgroundDuringWriteClosesDestinationAndPreservesOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "export-test-${UUID.randomUUID()}")
        val dir = File(root,"fixture").apply { check(mkdirs()) }
        val metadata = RecordingMetadata("fixture", "acq", Source.REAL, RecordingStartState.RECORDING,
            RecordingEndState.STOPPED, CompletionState.COMPLETED, RecoveryState.NONE, null,null,null,
            ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS,null,"clock",10,10,20),
            DeviceInfo(null,null,null,null,null),ApplicationInfo(null,null,null,null),emptyList(),
            SourceConfiguration(LocationPermissionState.DENIED,null,null,null,null,true),
            CalibrationInfo(CalibrationApplication.NOT_APPLIED,null),0,emptyList())
        val original = RecordingCodec.encodeMetadata(metadata)
        val metaFile = File(dir,"metadata.json").apply { writeBytes(original) }
        val measurements = File(dir,"measurements.jsonl").apply { writeBytes(byteArrayOf()) }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = ExportController(SessionFiles { root },scope)
        try {
            withContext(Dispatchers.Main) {
                assertTrue(controller.choose("fixture"))
                controller.result { object : OutputStream() {
                    override fun write(b: Int) {
                        entered.countDown()
                        check(release.await(10,TimeUnit.SECONDS)) { "test destination timed out" }
                    }
                    override fun close() { closed.countDown() }
                } }
            }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { controller.onBackground() }
            release.countDown()
            withTimeout(10_000) { controller.state.first { it.phase == ExportPhase.CANCELLED } }
            assertTrue(closed.await(1,TimeUnit.SECONDS))
            assertArrayEquals(original,metaFile.readBytes())
            assertEquals(0L,measurements.length())
        } finally {
            release.countDown(); scope.cancel()
            // Only this test's explicit private fixture files, never user sessions.
            metaFile.delete(); measurements.delete(); dir.delete(); root.delete()
        }
    }
}
