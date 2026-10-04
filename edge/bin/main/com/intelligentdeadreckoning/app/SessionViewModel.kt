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
import com.intelligentdeadreckoning.app.security.SafeSecurityMessages
import android.net.Uri
import com.intelligentdeadreckoning.app.replay.*
import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.app.evaluation.EvaluationLibrary
import com.intelligentdeadreckoning.app.evaluation.EvaluationStore
import com.intelligentdeadreckoning.app.map.EngineSessionMap
import com.intelligentdeadreckoning.app.map.LiveGnssView
import com.intelligentdeadreckoning.app.map.MapPresentation
import com.intelligentdeadreckoning.app.map.NO_ENGINE_VIEW
import com.intelligentdeadreckoning.app.map.NO_LIVE_GNSS
import com.intelligentdeadreckoning.app.map.RecordedSessionMap
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.app.matching.RoadGraphPack
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

/** Pure ownership gate shared with regression tests: never delete a session in use. */
internal fun canDeleteSession(
    id: String,
    recording: RecorderState,
    replay: ReplayState,
    export: ExportState,
    libraryBusy: Boolean,
): Boolean = !libraryBusy &&
    !(recording.busy && recording.recordingId == id) &&
    !(replay.busy && replay.id == id) &&
    !(export.busy && export.id == id)

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

    /** The seam to the production navigation engine (the classical GNSS+INS error-state EKF).
     *  Fed by the same canonical record streams the recorder consumes; owns its own engine
     *  worker; touches no sensors, maps or files. Its output drives the map through the
     *  presentation pipeline and is never altered by it. Publications are capped at 10 Hz so
     *  the displayed state moves at a rate a person can follow; the filter itself runs at
     *  sensor rate regardless. Calibration is estimated causally in the current session;
     *  fusion waits for valid mounting and measured forward course. Field qualification is pending. */
    private val navigation = NavigationRuntime(
        CalibratingFusionEngine(),
        viewModelScope, SystemClock::elapsedRealtimeNanos,
    )
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
        // The engine output stream speaks the navigation exchange contract (1.1.0): its
        // NavigationState carries localization_mode. A refused start (e.g. after an unreset
        // failure) is final for this session.
        navigation.start(Header(sessionId, source, ENGINE_OUTPUT_CONTRACT_VERSION), originNs,
            com.intelligentdeadreckoning.contracts.v1.InitializationMode.DEPLOYABLE)
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
        if (!foreground || libraryOperationBusy.value || recorder.state.value.busy || export.value.busy || player.state.value.busy) return
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
    val libraryOperationBusy = MutableStateFlow(false)
    val currentSession = MutableStateFlow<SavedSession?>(null)
    val elapsedNs = MutableStateFlow<Long?>(null)
    private var detailJob: Job? = null
    private var listJob: Job? = null
    private var deleteJob: Job? = null
    fun refreshSessions(after: String? = null) {
        if (libraryOperationBusy.value) return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                libraryMutable.value = withContext(Dispatchers.IO) { files.list(after) }
                libraryError.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { libraryError.value = "Cannot read sessions (${SafeSecurityMessages.code(e, "SESSION_IO_ERROR")})." }
        }
    }
    fun chooseExport(id: String): Boolean {
        if (libraryOperationBusy.value || recorder.state.value.busy || player.state.value.busy || !exporter.choose(id)) return false
        saved["export_pending"] = id
        return true
    }
    /** Permanently removes one session after explicit confirmation in the session dialog. */
    fun deleteSession(id: String) {
        // Use the recorder's unsampled state: `recording` below is a 5 Hz presentation
        // snapshot and may lag a just-started/finalizing writer by up to 200 ms.
        val recording = recorder.state.value
        val replaying = player.state.value
        val exporting = export.value
        if (!foreground || !canDeleteSession(
                id, recording, replaying, exporting, libraryOperationBusy.value,
            )
        ) return
        libraryOperationBusy.value = true
        libraryError.value = null
        listJob?.cancel()
        deleteJob?.cancel()
        deleteJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { files.delete(id) }
                libraryError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libraryError.value = "Cannot delete session (${SafeSecurityMessages.code(e, "SESSION_DELETE_FAILED")})."
            } finally {
                libraryOperationBusy.value = false
            }
            refreshSessions()
        }
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
    /**
     * Live phone GNSS folded for display only, from this capture session's fixes. The fold is
     * session-owned, reset on stop/restart and permission loss, and ticked so stale fixes leave
     * the marker even when Android has simply stopped sending callbacks. Nothing is propagated or
     * fused; a stale trail may remain as history, but its current-position field is hidden.
     */
    val liveGnss = channelFlow {
        val lock = Any()
        val map = RecordedSessionMap(
            staleAfterForProvider = { provider -> GnssQualityPolicy().staleAfterNs(provider) ?: RecordedSessionMap.DEFAULT_GAP_NS },
            source = Source.REAL,
        )
        var capture = real.state.value
        var sessionId: String? = null
        fun view(nowNs: Long = SystemClock.elapsedRealtimeNanos()) = synchronized(lock) {
            LiveGnssView(map.snapshot(nowNs), map.stats())
        }
        send(view())
        launch {
            real.state.collect { next ->
                val current = synchronized(lock) {
                    val newSession = next.running && next.sessionId != sessionId
                    capture = next
                    sessionId = next.sessionId.takeIf { next.running }
                    val permitted = next.permission in listOf(
                        LocationAccess.PRECISE, LocationAccess.APPROXIMATE,
                    )
                    if (!next.running || newSession || !permitted) map.reset()
                    LiveGnssView(map.snapshot(SystemClock.elapsedRealtimeNanos()), map.stats())
                }
                send(current)
            }
        }
        launch {
            measurements.filter { it.event.data is GnssMeasurement }.collect { record ->
                val current = synchronized(lock) {
                    val permitted = capture.running &&
                        capture.permission in listOf(LocationAccess.PRECISE, LocationAccess.APPROXIMATE) &&
                        record.header.session_id == sessionId
                    if (permitted) map.accept(record)
                    else map.snapshot(SystemClock.elapsedRealtimeNanos())
                    LiveGnssView(map.snapshot(SystemClock.elapsedRealtimeNanos()), map.stats())
                }
                send(current)
            }
        }
        while (isActive) {
            delay(500)
            send(view())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NO_LIVE_GNSS)
    /**
     * The evaluation reports this app can show: the document staged from
     * `contracts/evaluation/v1/golden_report.json` at build time, plus anything installed under the
     * app's own evaluation directory. Reading them is I/O; nothing here computes a metric.
     */
    private val evaluationMutable = MutableStateFlow(EvaluationLibrary())
    val evaluationLibrary = evaluationMutable.asStateFlow()
    private var evaluationJob: Job? = null
    fun refreshEvaluation() {
        evaluationJob?.cancel()
        evaluationJob = viewModelScope.launch {
            evaluationMutable.value = withContext(Dispatchers.IO) { EvaluationStore.load(getApplication()) }
        }
    }

    /** The evaluation toggle: on, the map matcher runs beside the raw output; off, it does not. */
    val mapEvaluation = MutableStateFlow(false)
    private var evaluationGraph: RoadGraph? = null
    /**
     * The engine session's map display: NavigationEngine → NavigationState →
     * NavigationPresentation → MapOverlay → MapLibreRenderer. Rebuilt per subscriber like the
     * live view, so returning to the map starts a fresh trail. A 500 ms ticker expires stale
     * state on schedule: when the engine stops, its position leaves the screen within the
     * presentation's staleness bound instead of lingering, and nothing is extrapolated or
     * restarted in the meantime.
     */
    val engineMap = channelFlow {
        val fold = EngineSessionMap()
        fun now() = SystemClock.elapsedRealtimeNanos()
        send(fold.snapshot(now()))
        launch { navigationEvents.collect { send(fold.accept(it, now())) } }
        launch {
            mapEvaluation.collect { enabled ->
                if (!enabled) {
                    fold.evaluation = null
                    fold.matchingIssue = null
                } else {
                    try {
                        fold.matchingIssue = null
                        fold.evaluation = evaluationGraph ?: withContext(Dispatchers.IO) {
                            RoadGraphPack(getApplication()).install()
                        }.also { evaluationGraph = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Matching is an optional overlay. A missing/corrupt graph must not
                        // terminate engine-map collection or hide raw navigation output.
                        fold.evaluation = null
                        fold.matchingIssue = "Road graph unavailable (${SafeSecurityMessages.code(error, "ROAD_GRAPH_REJECTED")}); raw navigation is unchanged."
                    }
                }
                send(fold.snapshot(now()))
            }
        }
        while (isActive) {
            delay(500)
            send(fold.snapshot(now()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NO_ENGINE_VIEW)
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
        if (foreground && !libraryOperationBusy.value && !player.state.value.busy && !replayVisible.value && !export.value.busy && source.value == InputSource.REAL && snapshot.running && snapshot.sensors.isNotEmpty()) {
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

    // Declared last on purpose: property initializers and init blocks run in declaration order, so
    // the report read starts only after every field it touches exists.
    init { refreshEvaluation() }
}
