package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.acquisition.InputSource
import com.intelligentdeadreckoning.app.replay.ReplayPhase
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ReplayUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var dir: File
    private lateinit var id: String
    private val ns = 9007199254740993L
    @Before fun fixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        id = "000-replay-test-${UUID.randomUUID()}"
        dir = File(context.noBackupFilesDir,"recordings/$id").apply { check(mkdirs()) }
        val m = RecordingMetadata(id,"fixture",Source.SIMULATION,RecordingStartState.RECORDING,
            RecordingEndState.STOPPED,CompletionState.COMPLETED,RecoveryState.NONE,null,null,null,
            ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS,null,"fixture",ns,ns,ns+30_000_000_000),
            DeviceInfo(null,null,null,null,null),ApplicationInfo(null,null,null,null),emptyList(),
            SourceConfiguration(LocationPermissionState.DENIED,null,null,null,null,true),
            CalibrationInfo(CalibrationApplication.NOT_APPLIED,null),2,listOf(ChannelCount("diagnostic",2)))
        File(dir,"metadata.json").writeBytes(RecordingCodec.encodeMetadata(m))
        File(dir,"measurements.jsonl").outputStream().use { out ->
            repeat(2) { i ->
                val time = ns + i * 30_000_000_000L
                out.write(RecordingCodec.encodeRecord(Record(Header("fixture",Source.SIMULATION),
                    Event(i.toString(),time,time,DiagnosticEvent(Severity.INFO,"FIXTURE","Synthetic replay fixture",0))),m))
                out.write(10)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("saved_sessions").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
    private fun model() = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
    private fun phase(p: ReplayPhase) = compose.waitUntil(10_000) { model().replay.value.phase == p }
    @After fun cleanup() {
        compose.runOnIdle { model().stopReplay() }
        compose.waitUntil(10_000) { !model().replay.value.busy }
        File(dir,"metadata.json").delete(); File(dir,"measurements.jsonl").delete(); dir.delete()
    }
    @Test fun selectPauseResumeStopAndExactSourceLabel() {
        compose.onNodeWithTag("saved_sessions").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Replay session").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Replay session")[0].performClick()
        phase(ReplayPhase.PLAYING)
        compose.onNodeWithText("replay_simulation").assertExists()
        compose.onNodeWithTag("recording_start").assertIsNotEnabled()
        compose.onNodeWithTag("replay_pause").performScrollTo().performClick(); phase(ReplayPhase.PAUSED)
        assertEquals(ns, model().replay.value.latest!!.event.t_ns)
        compose.onNodeWithTag("replay_resume").performClick(); phase(ReplayPhase.PLAYING)
        compose.onNodeWithTag("replay_stop").performClick(); phase(ReplayPhase.STOPPED)
        assertEquals(1L,model().replay.value.emitted)
    }
    @Test fun liveSourceExclusionBackgroundAndExplicitSourceSwitch() {
        compose.runOnIdle { model().select(InputSource.REAL); model().start() }
        compose.waitUntil(10_000) { model().capture.value.running }
        compose.runOnIdle { model().startReplay(id) }; phase(ReplayPhase.PLAYING)
        assertFalse(model().capture.value.running)
        compose.runOnIdle { model().start(); model().startRecording() }
        assertFalse(model().capture.value.running)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        phase(ReplayPhase.STOPPED)
        assertFalse(model().capture.value.running)
        compose.runOnIdle { model().startReplay(id) }; phase(ReplayPhase.PLAYING)
        compose.runOnIdle { model().select(InputSource.SIMULATION) }; phase(ReplayPhase.STOPPED)
        assertFalse(model().replayVisible.value)
    }
}
