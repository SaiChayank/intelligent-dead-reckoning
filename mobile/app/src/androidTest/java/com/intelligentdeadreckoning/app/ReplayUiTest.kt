package com.intelligentdeadreckoning.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.acquisition.InputSource
import com.intelligentdeadreckoning.app.replay.ReplayPhase
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ReplayUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var dir: File
    private lateinit var id: String
    @Before fun fixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        id = "000-demo-replay-test-${UUID.randomUUID()}"
        dir = File(context.noBackupFilesDir,"recordings/$id").apply { check(mkdirs()) }
        for (name in listOf("metadata.json", "measurements.jsonl")) {
            context.assets.open("demo-replay-v1/$name").use { input ->
                File(dir, name).outputStream().use { output ->
                    if (name == "metadata.json") {
                        val template = input.readBytes().toString(Charsets.UTF_8)
                        output.write(template.replace(
                            "\"recording_id\": \"demo-replay-v1\"",
                            "\"recording_id\": \"$id\"",
                        ).toByteArray(Charsets.UTF_8))
                    } else {
                        input.copyTo(output)
                    }
                }
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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("replay_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("replay_$id").performClick()
        phase(ReplayPhase.PLAYING)
        compose.onNodeWithText("replay_simulation").assertExists()
        compose.onNodeWithTag("recording_start").assertIsNotEnabled()
        compose.onNodeWithTag("replay_pause").performScrollTo().performClick(); phase(ReplayPhase.PAUSED)
        assertEquals(9007199254740993L, model().replay.value.latest!!.event.t_ns)
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
