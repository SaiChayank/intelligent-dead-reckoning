package com.intelligentdeadreckoning.app

import android.os.SystemClock
import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.intelligentdeadreckoning.app.simulation.SimulationController
import com.intelligentdeadreckoning.app.simulation.StopReason
import com.intelligentdeadreckoning.app.acquisition.*
import com.intelligentdeadreckoning.app.recording.*
import java.io.File
import com.intelligentdeadreckoning.app.sessions.*
import android.net.Uri
import com.intelligentdeadreckoning.app.replay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

class SessionViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val simulation = SimulationController(viewModelScope, SystemClock::elapsedRealtime)
    private val real = AndroidAcquisition(application)
    private val recorder = LocalRecorder(
        FileRecordingStorage { File(application.noBackupFilesDir, "recordings") },
        SystemClock::elapsedRealtimeNanos, System::currentTimeMillis,
    )
    // Presentation snapshots only: the recorder retains exact full-rate counters internally.
    @OptIn(FlowPreview::class)
    val recording = recorder.state.sample(200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecorderState())
    private var foreground = false
    private val files = SessionFiles { File(application.noBackupFilesDir, "recordings") }
    private val player = ReplayController(files, viewModelScope, SystemClock::elapsedRealtimeNanos)
    @OptIn(FlowPreview::class)
    val replay = player.state.sample(100).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReplayState())
    val replayEvents = player.events
    val replayVisible = MutableStateFlow(false)
    fun startReplay(id: String) {
        if (!foreground || recorder.state.value.busy || export.value.busy || player.state.value.busy) return
        coordinator.stop()
        replayVisible.value = true
        player.start(id)
    }
    fun pauseReplay() = player.pause()
    fun resumeReplay() { if (foreground) player.resume() }
    fun stopReplay() = player.stop()
    private val exporter = ExportController(files, viewModelScope)
    val export = exporter.state
    private val libraryMutable = MutableStateFlow(SessionPage())
    val library = libraryMutable.asStateFlow()
    val libraryError = MutableStateFlow<String?>(null)
    val currentSession = MutableStateFlow<SavedSession?>(null)
    val elapsedNs = MutableStateFlow<Long?>(null)
    private var detailJob: Job? = null
    private var listJob: Job? = null
    fun refreshSessions(after: String? = null) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                libraryMutable.value = withContext(Dispatchers.IO) { files.list(after) }
                libraryError.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { libraryError.value = e.message ?: "Cannot read sessions" }
        }
    }
    fun chooseExport(id: String): Boolean {
        if (recorder.state.value.busy || player.state.value.busy || !exporter.choose(id)) return false
        saved["export_pending"] = id
        return true
    }
    fun exportResult(uri: Uri?) {
        val pending = saved.get<String>("export_pending")
        saved.remove<String>("export_pending")
        if (pending != null && export.value.phase != ExportPhase.CHOOSING) exporter.choose(pending)
        exporter.result(if (uri == null) null else ({
            require(isLocalExportUri(uri)) { "Choose local device storage, not a cloud provider" }
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("Destination unavailable")
        }))
    }
    init {
        if (saved.get<Boolean>("export_writing") == true) exporter.restoreInterrupted()
        viewModelScope.launch {
            export.collect { saved["export_writing"] = it.phase == ExportPhase.WRITING }
        }
        recorder.loadInterrupted()
        viewModelScope.launch {
            real.state.collect { if (!it.running) recorder.stop("Acquisition stopped.") }
        }
    }
    private val coordinator = SourceCoordinator(object : SourceControl {
        override fun start() = simulation.start()
        override fun stop() = simulation.stop()
    }, real)
    val state = simulation.state
    val capture = real.state
    val measurements = real.events
    val source = coordinator.source
    fun select(source: InputSource) {
        player.stop(); replayVisible.value = false
        if (source != coordinator.source.value) recorder.stop("Source switched.")
        coordinator.select(source)
    }
    fun start() { if (!player.state.value.busy) { replayVisible.value = false; coordinator.start() } }
    fun stop() { recorder.stop("Acquisition stopped."); coordinator.stop() }
    fun startRecording() {
        val snapshot = capture.value
        if (foreground && !player.state.value.busy && !replayVisible.value && !export.value.busy && source.value == InputSource.REAL && snapshot.running && snapshot.sensors.isNotEmpty()) {
            recorder.start(recordingMetadata(getApplication(), snapshot), measurements)
        }
    }
    fun stopRecording() = recorder.stop()
    fun onForeground() {
        foreground = true; coordinator.foreground(); refreshPermissions()
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = recorder.state.value
                currentSession.value = snapshot.recordingId?.let { id -> withContext(Dispatchers.IO) { files.inspect(id) } }
                elapsedNs.value = currentSession.value?.let { session -> session.durationNs ?: if (snapshot.busy)
                    session.metadata?.clock?.startedNs?.let { (SystemClock.elapsedRealtimeNanos() - it).coerceAtLeast(0) } else null }
                delay(1000)
            }
        }
    }
    fun markPermissionRequested() { saved["location_requested"] = true }
    fun refreshPermissions() {
        val app = getApplication<Application>()
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) saved["location_granted"] = true
        real.permissionHistory(saved["location_requested"] ?: false, saved["location_granted"] ?: false)
    }
    fun onBackground() {
        foreground = false
        player.stop()
        detailJob?.cancel()
        exporter.onBackground()
        recorder.stop("Foreground owner stopped.")
        simulation.stop(StopReason.BACKGROUND)
        coordinator.background()
    }
    override fun onCleared() { player.stop(); recorder.close(); real.close(); super.onCleared() }
}
