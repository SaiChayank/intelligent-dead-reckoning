package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.intelligentdeadreckoning.app.replay.*

@Composable
fun ReplayPanel(state: ReplayState, pause: () -> Unit, resume: () -> Unit, stop: () -> Unit) {
    Column {
        Text("Replay · ${state.phase.name.lowercase()}", Modifier.testTag("replay_status"))
        Text(state.message)
        Text("Session: ${state.id ?: "none"}\nSource: ${state.source?.wire ?: "pending"}\nRecords: ${state.emitted} / ${state.total ?: "unknown"}\nPlayback position: ${state.elapsedNs / 1_000_000_000L} s")
        if (state.incomplete) Text("INCOMPLETE / RECOVERED — only the recovered prefix is available.")
        Row {
            TextButton(pause, enabled = state.phase == ReplayPhase.PLAYING, modifier = Modifier.testTag("replay_pause")) { Text("Pause") }
            TextButton(resume, enabled = state.phase == ReplayPhase.PAUSED, modifier = Modifier.testTag("replay_resume")) { Text("Resume") }
            TextButton(stop, enabled = state.busy && state.phase != ReplayPhase.STOPPING, modifier = Modifier.testTag("replay_stop")) { Text("Stop replay") }
        }
        Text("Select a live source explicitly to leave replay. No automatic restart.")
        state.latest?.let {
            Text("Last event: ${it.event.data.type}\nID: ${it.event.event_id}\nOriginal event ns: ${it.event.t_ns}\nOriginal receipt ns: ${it.event.received_ns}")
            Text(it.event.data.toString(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
