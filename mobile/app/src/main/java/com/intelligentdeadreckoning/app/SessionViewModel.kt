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
    init {
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
        if (source != coordinator.source.value) recorder.stop("Source switched.")
        coordinator.select(source)
    }
    fun start() = coordinator.start()
    fun stop() { recorder.stop("Acquisition stopped."); coordinator.stop() }
    fun startRecording() {
        val snapshot = capture.value
        if (foreground && source.value == InputSource.REAL && snapshot.running && snapshot.sensors.isNotEmpty()) {
            recorder.start(recordingMetadata(getApplication(), snapshot), measurements)
        }
    }
    fun stopRecording() = recorder.stop()
    fun onForeground() { foreground = true; coordinator.foreground(); refreshPermissions() }
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
        recorder.stop("Foreground owner stopped.")
        simulation.stop(StopReason.BACKGROUND)
        coordinator.background()
    }
    override fun onCleared() { recorder.close(); real.close(); super.onCleared() }
}
