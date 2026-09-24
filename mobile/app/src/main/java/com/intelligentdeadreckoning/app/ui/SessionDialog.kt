package com.intelligentdeadreckoning.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intelligentdeadreckoning.app.sessions.*
import java.time.Instant

@Composable
fun SessionDialog(page: SessionPage, error: String?, busy: Boolean, exportMessage: String,
                  refresh: (String?) -> Unit, export: (String) -> Unit, close: () -> Unit,
                  replay: (String) -> Unit = {}) {
    AlertDialog(onDismissRequest = close, title = { Text("Local saved sessions") }, text = {
        Column {
            Text("Saved recordings · replay is not live acquisition or navigation. Export creates a copy.")
            Text(exportMessage)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page.sessions.isEmpty()) item { Text("No sessions on this page. Refresh after finalization.") }
                items(page.sessions, key = { it.id }) { session ->
                    Column {
                        Text(session.id, style = MaterialTheme.typography.titleSmall)
                        val m = session.metadata
                        Text("Time: ${m?.startedUtcMs?.let { Instant.ofEpochMilli(it) } ?: "unknown"}\nSource: ${m?.source ?: "unknown"}\nDuration: ${session.durationNs?.let { "${it / 1_000_000_000L} s" } ?: "unknown / open"}\nRecords: ${m?.recordCount ?: "unknown / open"}\nState: ${m?.completionState ?: "invalid"} / ${m?.recoveryState ?: "unknown"}\nSize: ${session.bytes?.let { "$it bytes" } ?: "unknown"}")
                        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (m != null && m.completionState.name != "COMPLETED") Text("Not a clean completed session; export preserves its status and bytes.")
                        OutlinedButton(onClick = { export(session.id) }, enabled = !busy && session.exportable) { Text("Export copy (.zip)") }
                        OutlinedButton(onClick = { replay(session.id); close() }, enabled = !busy && session.replayable) { Text("Replay session") }
                        HorizontalDivider()
                    }
                }
            }
            Row {
                TextButton(onClick = { refresh(null) }) { Text("Refresh / first") }
                TextButton(onClick = { refresh(page.next) }, enabled = page.next != null) { Text("Next page") }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
