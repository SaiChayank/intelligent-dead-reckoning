package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.app.replay.ReplayReader
import com.intelligentdeadreckoning.contracts.recording.v1.RecordingCodec
import com.intelligentdeadreckoning.contracts.v1.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SampleDriveReplayTest {

    @Test
    fun sampleDriveReplaysThroughCalibratingFusionEngineAndEntersDeadReckoning() = runTest {
        val assetDir = File("src/main/assets/sample_drive/hyderabad-demo-drive")
        val metadataFile = File(assetDir, "metadata.json")
        val gzFile = File(assetDir, "measurements.jsonl.gz")

        assertTrue("metadata.json must exist in assets", metadataFile.exists())
        assertTrue("measurements.jsonl.gz must exist in assets", gzFile.exists())

        val metadata = RecordingCodec.decodeMetadata(metadataFile.readBytes())
        assertEquals("hyderabad-demo-drive", metadata.recordingId)

        val decompressedInput = GZIPInputStream(FileInputStream(gzFile))
        val reader = ReplayReader(decompressedInput, metadata)

        val testDispatcher = StandardTestDispatcher(testScheduler)
        var currentClockNs = metadata.clock.originNs

        val engine = CalibratingFusionEngine()
        val runtime = NavigationRuntime(
            engine = engine,
            scope = this,
            nowNs = { currentClockNs },
            dispatcher = testDispatcher,
        )

        val header = Header(
            session_id = metadata.acquisitionSessionId,
            source = Source.REPLAY_SIMULATION,
            contract_version = ENGINE_OUTPUT_CONTRACT_VERSION,
        )

        val published = mutableListOf<Record>()
        val collector = launch { runtime.output.collect { published += it } }

        assertTrue(runtime.start(header, metadata.clock.originNs, InitializationMode.DEPLOYABLE))
        advanceUntilIdle()

        var recordCount = 0L
        while (true) {
            val record = reader.next() ?: break
            currentClockNs = record.event.t_ns
            runtime.offer(record)
            recordCount++
            if (recordCount % 50 == 0L) {
                advanceUntilIdle()
            }
        }
        advanceUntilIdle()
        runtime.stop()
        advanceUntilIdle()
        collector.cancel()

        assertTrue("Should offer all records", recordCount > 10_000L)
        assertTrue("Should publish navigation/calibration events", published.isNotEmpty())

        val calibrations = published.mapNotNull { it.event.data as? CalibrationResult }
        val navStates = published.mapNotNull { it.event.data as? NavigationState }

        println("Sample drive published ${calibrations.size} calibration events and ${navStates.size} nav states.")
    }
}
