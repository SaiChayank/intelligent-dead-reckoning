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
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.app.navigation.UninitializedNavigationEngine
import com.intelligentdeadreckoning.app.map.LiveGnssView
import com.intelligentdeadreckoning.app.map.NO_LIVE_GNSS
import com.intelligentdeadreckoning.app.map.RecordedSessionMap
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
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

    /** The seam to the future navigation engine. Fed by the same canonical record streams the
     *  recorder consumes; owns its own engine worker; touches no sensors, maps or files.
     *  Its output is exposed but nothing consumes it yet — there is no engine to show. */
    private val navigation = NavigationRuntime(UninitializedNavigationEngine(), viewModelScope, SystemClock::elapsedRealtimeNanos)
    val navigationState = navigation.state
    val navigationEvents = navigation.output
    /** The producer session the engine was bound to. One bind per session: a failed engine is
     *  never rebound or restarted — a new session must be started explicitly. */
    private var navigationSession: String? = null
    private var navigationSource: Source? = null

    private fun bindNavigation(sessionId: String, originNs: Long?, source: Source) {
        if (originNs == null) return
        navigationSession = sessionId
        navigationSource = source
        // A refused start (e.g. after an unreset failure) is final for this session.
        navigation.start(Header(sessionId, source), originNs)
    }

    private fun releaseNavigation() {
        navigationSession = null
        navigationSource = null
        navigation.stop()
    }

    @OptIn(FlowPreview::class)
    val replay = player.state.sample(100).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReplayState())
    val replayEvents = player.events
    val replayVisible = MutableStateFlow(false)
    fun startReplay(id: String) {
        if (!foreground || recorder.state.value.busy || export.value.busy || player.state.value.busy) return
        coordinator.stop()
        // A replay is a different producer session: the live engine session ends first.
        releaseNavigation()
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
        // Both canonical producers feed the navigation seam. Records are offered, never
        // awaited: a stopped or failed engine session simply refuses them.
        viewModelScope.launch {
            real.events.collect { navigation.offer(it) }
        }
        viewModelScope.launch {
            player.events.collect { navigation.offer(it) }
        }
        viewModelScope.launch {
            real.state.collect { snapshot ->
                if (!snapshot.running) recorder.stop("Acquisition stopped.")
                val id = snapshot.sessionId
                when {
                    snapshot.running && id != null && id != navigationSession ->
                        bindNavigation(id, snapshot.originNs, Source.REAL)
                    !snapshot.running && navigationSource == Source.REAL ->
                        releaseNavigation()
                }
            }
        }
        // Replay rows carry their recorded acquisition identity plus the replay source; the
        // engine session binds to exactly that, so replayed rows can never mix with live ones.
        viewModelScope.launch {
            player.state.collect { r ->
                val id = r.acquisitionSessionId
                when {
                    r.phase == ReplayPhase.PLAYING && id != null && r.source != null && id != navigationSession ->
                        bindNavigation(id, r.originNs, r.source)
                    id != null && id == navigationSession &&
                        r.phase in listOf(ReplayPhase.STOPPED, ReplayPhase.COMPLETED, ReplayPhase.FAILED) ->
                        releaseNavigation()
                }
            }
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
    /** Live phone GNSS folded for display only: the same fold a replayed session gets, claimed as
     * live rather than replayed. Rebuilt per subscriber, so leaving the map and returning starts a
     * fresh live trail instead of resuming a stale one. Nothing is propagated or fused. */
    val liveGnss = flow {
        val map = RecordedSessionMap(source = Source.REAL)
        emit(LiveGnssView(map.snapshot(), map.stats()))
        measurements.filter { it.event.data is GnssMeasurement }.collect { record ->
            emit(LiveGnssView(map.accept(record), map.stats()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NO_LIVE_GNSS)
    fun select(source: InputSource) {
        player.stop(); replayVisible.value = false
        // A source change ends the engine session; the new one binds only with a new session.
        releaseNavigation()
        if (source != coordinator.source.value) recorder.stop("Source switched.")
        coordinator.select(source)
    }
    fun start() { if (!player.state.value.busy) { replayVisible.value = false; coordinator.start() } }
    fun stop() {
        recorder.stop("Acquisition stopped.")
        if (navigationSource == Source.REAL) releaseNavigation()
        coordinator.stop()
    }
    /** Clears a stopped or failed engine session so a new one may start. Never automatic. */
    fun resetNavigation() = navigation.reset()
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
        // Lifecycle stop: the engine session ends with the foreground owner and nothing
        // restarts it on return.
        releaseNavigation()
        detailJob?.cancel()
        exporter.onBackground()
        recorder.stop("Foreground owner stopped.")
        simulation.stop(StopReason.BACKGROUND)
        coordinator.background()
    }
    override fun onCleared() { player.stop(); releaseNavigation(); navigation.close(); recorder.close(); real.close(); super.onCleared() }
}
