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

class SessionViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val simulation = SimulationController(viewModelScope, SystemClock::elapsedRealtime)
    private val real = AndroidAcquisition(application)
    private val coordinator = SourceCoordinator(object : SourceControl {
        override fun start() = simulation.start()
        override fun stop() = simulation.stop()
    }, real)
    val state = simulation.state
    val capture = real.state
    val measurements = real.events
    val source = coordinator.source
    fun select(source: InputSource) = coordinator.select(source)
    fun start() = coordinator.start()
    fun stop() = coordinator.stop()
    fun onForeground() { coordinator.foreground(); refreshPermissions() }
    fun markPermissionRequested() { saved["location_requested"] = true }
    fun refreshPermissions() {
        val app = getApplication<Application>()
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) saved["location_granted"] = true
        real.permissionHistory(saved["location_requested"] ?: false, saved["location_granted"] ?: false)
    }
    fun onBackground() {
        simulation.stop(StopReason.BACKGROUND)
        coordinator.background()
    }
    override fun onCleared() { real.close(); super.onCleared() }
}
