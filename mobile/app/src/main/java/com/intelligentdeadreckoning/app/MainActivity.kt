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
            IdrTheme { IdrApp(state, session::start, session::stop, source, capture, session::select,
                onPermission = {
                    session.markPermissionRequested()
                    permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, onSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                }, recording = recording, onStartRecording = session::startRecording,
                onStopRecording = session::stopRecording) }
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
