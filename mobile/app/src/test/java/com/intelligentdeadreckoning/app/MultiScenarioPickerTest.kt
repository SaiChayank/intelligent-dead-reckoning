package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.app.replay.ReplayReader
import com.intelligentdeadreckoning.app.sessions.SampleDriveInstaller
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
class MultiScenarioPickerTest {

    @Test
    fun allDemoScenariosHaveValidCatalogueEntries() {
        assertEquals(3, SampleDriveInstaller.ALL_DEMO_SESSIONS.size)
        assertEquals(3, SampleDriveInstaller.DEMO_SCENARIOS.size)

        for (sessionId in SampleDriveInstaller.ALL_DEMO_SESSIONS) {
            val scenario = SampleDriveInstaller.getScenario(sessionId)
            assertNotNull("Scenario must exist for $sessionId", scenario)
            assertEquals(sessionId, scenario!!.id)
            assertTrue("Title must not be empty", scenario.title.isNotBlank())
            assertTrue("Subtitle must not be empty", scenario.subtitle.isNotBlank())
            assertTrue("Duration text must not be empty", scenario.durationText.isNotBlank())
            assertTrue("Badge must not be empty", scenario.badge.isNotBlank())
            assertTrue("Description must not be empty", scenario.description.isNotBlank())
            assertTrue("Key features must contain multiple points", scenario.keyFeatures.size >= 3)
        }

        assertNull("Unknown scenario must return null", SampleDriveInstaller.getScenario("unknown-drive"))
    }

    @Test
    fun allAssetFixturesExistAndAreValid() {
        val baseDir = File("src/main/assets/sample_drive")
        assertTrue("Base asset directory must exist", baseDir.exists())

        for (sessionId in SampleDriveInstaller.ALL_DEMO_SESSIONS) {
            val sessionDir = File(baseDir, sessionId)
            assertTrue("Scenario dir must exist: $sessionDir", sessionDir.exists() && sessionDir.isDirectory)

            val metaFile = File(sessionDir, "metadata.json")
            assertTrue("metadata.json must exist in $sessionId", metaFile.exists())
            val metadata = RecordingCodec.decodeMetadata(metaFile.readBytes())
            assertEquals(sessionId, metadata.recordingId)
            assertTrue("Origin timestamp must be positive", metadata.clock.originNs > 0)

            val gzFile = File(sessionDir, "measurements.jsonl.gz")
            assertTrue("measurements.jsonl.gz must exist in $sessionId", gzFile.exists())
            assertTrue("GZ file must have non-zero size", gzFile.length() > 10_000L)

            val evalFile = File(sessionDir, "evaluation_report.json")
            if (sessionId == SampleDriveInstaller.DEMO_SESSION_ID) {
                assertTrue("evaluation_report.json must exist in primary $sessionId", evalFile.exists())
            }
        }
    }

    @Test
    fun urbanCanyonMultipathReplaysCleanlyThroughNavigationRuntime() = runTest {
        val assetDir = File("src/main/assets/sample_drive/urban-canyon-multipath")
        val metadata = RecordingCodec.decodeMetadata(File(assetDir, "metadata.json").readBytes())
        val decompressedInput = GZIPInputStream(FileInputStream(File(assetDir, "measurements.jsonl.gz")))
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

        assertTrue("Should offer all records for urban canyon", recordCount > 10_000L)
        val navStates = published.mapNotNull { it.event.data as? NavigationState }
        assertTrue("Urban canyon must publish nav states", navStates.isNotEmpty())
    }

    @Test
    fun stopAndGoOutageReplaysCleanlyThroughNavigationRuntime() = runTest {
        val assetDir = File("src/main/assets/sample_drive/stop-and-go-outage")
        val metadata = RecordingCodec.decodeMetadata(File(assetDir, "metadata.json").readBytes())
        val decompressedInput = GZIPInputStream(FileInputStream(File(assetDir, "measurements.jsonl.gz")))
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

        assertTrue("Should offer all records for stop and go", recordCount > 10_000L)
        val navStates = published.mapNotNull { it.event.data as? NavigationState }
        assertTrue("Stop and go must publish nav states", navStates.isNotEmpty())
    }
}
