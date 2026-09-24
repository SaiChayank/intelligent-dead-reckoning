package com.intelligentdeadreckoning.app.sessions

import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExportPhase { IDLE, CHOOSING, WRITING, EXPORTED, CANCELLED, FAILED }
data class ExportState(val phase: ExportPhase = ExportPhase.IDLE, val id: String? = null, val message: String = "No exported copy.") {
    val busy get() = phase == ExportPhase.CHOOSING || phase == ExportPhase.WRITING
}
class ExportController(private val files: SessionFiles, private val scope: CoroutineScope,
                       private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow(ExportState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun restoreInterrupted() {
        if (!mutable.value.busy) mutable.value = ExportState(ExportPhase.FAILED, message = "Previous export was interrupted; partial destination may remain. Original unchanged. Export again explicitly.")
    }
    fun choose(id: String): Boolean {
        if (mutable.value.busy) return false
        mutable.value = ExportState(ExportPhase.CHOOSING, id, "Choose a local destination; original stays private.")
        return true
    }
    fun result(open: (() -> OutputStream)?) {
        val current = mutable.value
        if (current.phase != ExportPhase.CHOOSING) return
        if (open == null) {
            mutable.value = current.copy(phase = ExportPhase.CANCELLED, message = "Export cancelled; original unchanged.")
            return
        }
        mutable.value = current.copy(phase = ExportPhase.WRITING, message = "Writing exported copy…")
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(io) { files.export(current.id!!, open) { ensureActive() } }
                mutable.value = current.copy(phase = ExportPhase.EXPORTED, message = "Copy exported; private original preserved. No navigation is running.")
            } catch (e: CancellationException) {
                mutable.value = current.copy(phase = ExportPhase.CANCELLED, message = "Export interrupted; partial destination may remain. Original unchanged.")
            } catch (e: Exception) {
                mutable.value = current.copy(phase = ExportPhase.FAILED, message = "Export failed: ${e.message}. Partial destination may remain; original unchanged.")
            }
        }
    }
    // The document picker itself backgrounds the activity; keep CHOOSING, cancel only active I/O.
    fun onBackground() { job?.cancel() }
}
