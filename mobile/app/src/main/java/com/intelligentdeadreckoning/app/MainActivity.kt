package com.intelligentdeadreckoning.app

import android.os.Bundle
import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.intelligentdeadreckoning.app.ui.IdrApp
import com.intelligentdeadreckoning.app.ui.IdrTheme

class MainActivity : ComponentActivity() {
    private val session: SessionViewModel by viewModels()
    private val exportDocument = registerForActivityResult(com.intelligentdeadreckoning.app.sessions.CreateSessionDocument()) {
        session.exportResult(it)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        session.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val source by session.source.collectAsStateWithLifecycle()
            val capture by session.capture.collectAsStateWithLifecycle()
            val recording by session.recording.collectAsStateWithLifecycle()
            val library by session.library.collectAsStateWithLifecycle()
            val libraryError by session.libraryError.collectAsStateWithLifecycle()
            val current by session.currentSession.collectAsStateWithLifecycle()
            val elapsed by session.elapsedNs.collectAsStateWithLifecycle()
            val export by session.export.collectAsStateWithLifecycle()
            val replay by session.replay.collectAsStateWithLifecycle()
            val replayVisible by session.replayVisible.collectAsStateWithLifecycle()
            IdrTheme { IdrApp(state, session::start, session::stop, source, capture, session::select,
                onPermission = {
                    session.markPermissionRequested()
                    permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, onSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                }, recording = recording, onStartRecording = session::startRecording,
                onStopRecording = session::stopRecording, library = library, libraryError = libraryError,
                currentSession = current, elapsedNs = elapsed, export = export, onRefreshSessions = session::refreshSessions,
                replay = replay, replayVisible = replayVisible, onReplay = session::startReplay,
                onPauseReplay = session::pauseReplay, onResumeReplay = session::resumeReplay, onStopReplay = session::stopReplay,
                onExport = { id -> if (session.chooseExport(id)) {
                    try { exportDocument.launch(id) } catch (_: Exception) { session.exportResult(null) }
                } }) }
        }
    }

    override fun onStart() { super.onStart(); session.onForeground() }
    override fun onResume() { super.onResume(); session.refreshPermissions() }

    override fun onStop() {
        // No background service. Returning to this screen never silently resumes a session.
        session.onBackground()
        super.onStop()
    }
}
