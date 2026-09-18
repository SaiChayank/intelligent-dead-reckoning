package com.intelligentdeadreckoning.app.acquisition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Foreground-only adapter. Callback copies are bounded; validation runs on Default.
 * No Activity, UI, location history, file writer, navigation or service is owned here.
 */
class AndroidAcquisition(context: Context) : SourceControl {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()
    private val stream = MutableSharedFlow<Record>(replay = 0, extraBufferCapacity = 256)
    val events = stream.asSharedFlow()
    private var current: Run? = null
    @Volatile private var requested = false
    @Volatile private var hadGrant = false

    fun permissionHistory(requested: Boolean, previouslyGranted: Boolean) {
        this.requested = requested; hadGrant = previouslyGranted || hadGrant
        synchronized(lock) {
            if (current == null) mutableState.value = mutableState.value.copy(permission = readAccess())
        }
    }

    private fun readAccess(): LocationAccess {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val access = locationAccess(fine, coarse, requested, hadGrant)
        if (fine || coarse) hadGrant = true
        return access
    }

    override fun start() = synchronized(lock) {
        if (current != null) return@synchronized
        val run = Run()
        current = run
        mutableState.value = CaptureState(running = true, sessionId = run.id, originNs = run.origin,
            permission = readAccess(), message = "Starting phone sensors…")
        run.start()
    }

    override fun stop() = synchronized(lock) {
        val run = current ?: return@synchronized
        current = null
        run.active = false // Immediate gate: no stale callback can publish after stop/source switch.
        run.shutdownDrops.set(run.inbox.size.toLong())
        run.inbox.clear()
        run.handler.post { try { run.cleanup() } finally { run.thread.quitSafely() } }
        mutableState.value = mutableState.value.copy(running = false, queueDepth = 0,
            message = "Stopped. Last samples retained; Start creates a new session.")
    }

    fun close() { stop(); scope.cancel() }

    private inner class Run : SensorEventListener, LocationListener {
        val id = UUID.randomUUID().toString()
        val origin = SystemClock.elapsedRealtimeNanos()
        val thread = HandlerThread("IDR-acquisition").apply { start() }
        val handler = Handler(thread.looper)
        val inbox = BoundedInbox<Sample>()
        val shutdownDrops = AtomicLong()
        @Volatile var active = true
        var job: Job? = null
        private val sensors = app.getSystemService(SensorManager::class.java)
        private val locations = app.getSystemService(LocationManager::class.java)
        @Volatile private var hardware: Map<Sensor, SensorInfo> = emptyMap()
        @Volatile private var access = LocationAccess.NOT_REQUESTED
        @Volatile private var locationEpoch = 0L
        @Volatile private var providerEnabled = false
        @Volatile private var satellite: SatelliteSnapshot? = null
        private val outputDrops = AtomicLong()
        private val mappings = linkedMapOf(AndroidSensor.TYPE_ACCELEROMETER to Sensor.ACCELEROMETER,
            AndroidSensor.TYPE_GYROSCOPE to Sensor.GYROSCOPE,
            AndroidSensor.TYPE_MAGNETIC_FIELD to Sensor.MAGNETOMETER,
            AndroidSensor.TYPE_GRAVITY to Sensor.GRAVITY)
        private val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (!active) return
                satellite = SatelliteSnapshot(SystemClock.elapsedRealtimeNanos(), status.satelliteCount,
                    (0 until status.satelliteCount).count { status.usedInFix(it) }.toLong())
            }
            override fun onStopped() { satellite = null }
        }
        private var locationListener: LocationListener? = null
        private val poll = object : Runnable {
            override fun run() {
                if (!active) return
                val fresh = readAccess()
                if (fresh != access) configureLocation(fresh)
                // Provider enable/disable callbacks also update this; polling covers OEM omissions.
                providerEnabled = allowedProviders().any { locations?.isProviderEnabled(it) == true }
                handler.postDelayed(this, 500)
            }
        }

        fun start() {
            handler.post {
                if (!active) return@post
                for ((type, kind) in mappings) {
                    val sensor = sensors?.getDefaultSensor(type)
                    val info = registerSensor(kind, sensor?.name, sensor?.vendor) {
                        try { sensors?.registerListener(this, sensor, sensorPeriodUs(kind), 0, handler) == true }
                        catch (_: SecurityException) { false }
                        catch (_: IllegalArgumentException) { false }
                    }
                    hardware = hardware + (kind to info)
                    if (!info.available) notice("SENSOR_MISSING", "${kind.wire} unavailable or registration refused.")
                }
                configureLocation(readAccess())
                handler.post(poll)
            }
            job = scope.launch {
                val processor = AcquisitionProcessor(Header(id, Source.REAL), origin) { record ->
                    synchronized(lock) {
                        if (active && current === this@Run && !stream.tryEmit(record) && record.event.data !is DiagnosticEvent)
                            outputDrops.incrementAndGet()
                    }
                }
                var lastPublish = 0L
                var epoch = -1L
                try { while (isActive && active) {
                    if (epoch != locationEpoch) { epoch = locationEpoch; processor.clearLocation() }
                    repeat(64) {
                        val sample = inbox.poll() ?: return@repeat
                        if (sample.payload !is GnssMeasurement || (sample.locationEpoch == locationEpoch && access in listOf(LocationAccess.PRECISE, LocationAccess.APPROXIMATE)))
                            processor.accept(sample)
                        else processor.discardForPermission(SystemClock.elapsedRealtimeNanos())
                    }
                    val now = SystemClock.elapsedRealtimeNanos()
                    processor.overflow(inbox.takeDrops() + outputDrops.getAndSet(0), now)
                    if (epoch != locationEpoch) { epoch = locationEpoch; processor.clearLocation() }
                    if (now - lastPublish >= 200_000_000) {
                        lastPublish = now
                        val publishEpoch = locationEpoch
                        val publishAccess = access
                        val sat = satellite?.takeIf { now - it.time <= 5_000_000_000L && access == LocationAccess.PRECISE }
                        val snapshot = processor.snapshot(now, publishAccess, providerEnabled, sat?.used).copy(
                            sensors = hardware, satellitesUsed = sat?.used, satellitesVisible = sat?.visible,
                            queueDepth = inbox.size, queueHighWater = inbox.highWater,
                            message = "Live phone measurements · navigation not running")
                        synchronized(lock) {
                            if (active && current === this@Run && publishEpoch == locationEpoch && publishAccess == access)
                                mutableState.value = snapshot
                        }
                    }
                    delay(10)
                } } finally {
                    val now = SystemClock.elapsedRealtimeNanos()
                    processor.overflow(inbox.takeDrops() + outputDrops.getAndSet(0), now)
                    processor.stopped(shutdownDrops.get(), now)
                    val last = processor.snapshot(now, access, providerEnabled, null).copy(running = false,
                        sensors = hardware, queueDepth = 0, queueHighWater = inbox.highWater,
                        message = "Stopped. Last samples retained; Start creates a new session.")
                    synchronized(lock) {
                        if (current == null && mutableState.value.sessionId == id) mutableState.value = last
                    }
                }
            }
        }

        private fun notice(code: String, message: String) {
            val now = SystemClock.elapsedRealtimeNanos()
            offer(Sample(now, now, DiagnosticEvent(Severity.WARNING, code, message, 0)))
        }

        private fun offer(sample: Sample) = synchronized(lock) {
            if (active && current === this@Run) inbox.offer(sample)
        }

        private fun allowedProviders(): List<String> = when (access) {
            LocationAccess.PRECISE -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            LocationAccess.APPROXIMATE -> listOf(LocationManager.NETWORK_PROVIDER)
            else -> emptyList()
        }.filter { locations?.allProviders?.contains(it) == true }

        @SuppressLint("MissingPermission") // Runtime recheck plus SecurityException boundary below.
        private fun configureLocation(fresh: LocationAccess) {
            removeLocation()
            locationEpoch++
            access = fresh
            if (fresh !in listOf(LocationAccess.PRECISE, LocationAccess.APPROXIMATE)) {
                notice(if (fresh == LocationAccess.REVOKED) "PERMISSION_REVOKED" else "PERMISSION_DENIED", "Location unavailable; IMU acquisition can continue.")
                return
            }
            try {
                val epoch = locationEpoch
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (active && epoch == locationEpoch) this@Run.onLocationChanged(location)
                    }
                    override fun onProviderEnabled(provider: String) {
                        if (active && epoch == locationEpoch) this@Run.onProviderEnabled(provider)
                    }
                    override fun onProviderDisabled(provider: String) {
                        if (active && epoch == locationEpoch) this@Run.onProviderDisabled(provider)
                    }
                    @Deprecated("Compatibility callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                locationListener = listener
                for (provider in allowedProviders())
                    locations?.requestLocationUpdates(provider, 1000L, 0f, listener, handler.looper)
                providerEnabled = allowedProviders().any { locations?.isProviderEnabled(it) == true }
                if (fresh == LocationAccess.PRECISE && locations?.registerGnssStatusCallback(callback, handler) != true)
                    notice("GNSS_STATUS_UNAVAILABLE", "Satellite-status callback unavailable.")
                notice("PERMISSION_CHANGED", "Location access: ${fresh.name.lowercase()}.")
                if (!providerEnabled) notice("PROVIDER_DISABLED", "Enable device location or an available provider.")
            } catch (_: SecurityException) {
                removeLocation(); access = LocationAccess.REVOKED; locationEpoch++
                notice("PERMISSION_REVOKED", "Location permission changed during registration.")
            } catch (_: IllegalArgumentException) {
                removeLocation(); notice("PROVIDER_UNAVAILABLE", "Requested location provider unavailable.")
            }
        }

        private fun removeLocation() {
            try { locationListener?.let { locations?.removeUpdates(it) } }
            catch (_: SecurityException) { /* No retained subscription after OS permission revocation. */ }
            try { locations?.unregisterGnssStatusCallback(callback) } catch (_: SecurityException) { }
            locationListener = null
            satellite = null; providerEnabled = false
        }

        fun cleanup() {
            handler.removeCallbacks(poll)
            try { sensors?.unregisterListener(this) } finally { removeLocation() }
        }

        override fun onSensorChanged(event: SensorEvent) {
            val receipt = SystemClock.elapsedRealtimeNanos()
            if (!active) return
            val kind = mappings[event.sensor.type] ?: return
            if (event.values.size < 3) { notice("INVALID_MEASUREMENT", "Sensor emitted fewer than three components."); return }
            val unit = when (kind) {
                Sensor.GYROSCOPE -> ImuUnit.RADIANS_PER_SECOND
                Sensor.MAGNETOMETER -> ImuUnit.MICROTESLA
                else -> ImuUnit.METRES_PER_SECOND_SQUARED
            }
            val accuracy = when (event.accuracy) {
                SensorManager.SENSOR_STATUS_UNRELIABLE -> SensorAccuracy.UNRELIABLE
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> SensorAccuracy.LOW
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> SensorAccuracy.MEDIUM
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> SensorAccuracy.HIGH
                else -> SensorAccuracy.UNKNOWN
            }
            offer(Sample(event.timestamp, receipt, ImuMeasurement(kind, DeviceFrame.ANDROID_DEVICE, unit,
                Vector3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()), accuracy)))
        }
        override fun onAccuracyChanged(sensor: AndroidSensor?, accuracy: Int) = Unit // Each measurement carries its own accuracy.

        override fun onLocationChanged(location: Location) {
            val receipt = SystemClock.elapsedRealtimeNanos()
            if (!active) return
            if (readAccess() != access) { configureLocation(readAccess()); return }
            if (location.provider !in allowedProviders()) return
            val sat = satellite?.takeIf { receipt - it.time <= 5_000_000_000L && location.provider == LocationManager.GPS_PROVIDER }
            val payload = mapLocation(location, sat?.used)
            offer(Sample(location.elapsedRealtimeNanos, receipt, payload, locationEpoch))
        }
        override fun onProviderEnabled(provider: String) { providerEnabled = true; notice("PROVIDER_ENABLED", "Location provider enabled.") }
        override fun onProviderDisabled(provider: String) {
            providerEnabled = allowedProviders().any { locations?.isProviderEnabled(it) == true }
            satellite = null; notice("PROVIDER_DISABLED", "Location provider disabled.")
        }
        @Deprecated("Required compatibility callback for older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private data class SatelliteSnapshot(val time: Long, val visible: Int, val used: Long)
}

/** Android has* presence flags must be inspected before reading numeric defaults. */
internal fun mapLocation(location: Location, satellites: Long?) = gnssPayload(location.latitude, location.longitude,
    if (location.hasAltitude()) location.altitude else null,
    if (location.hasSpeed()) location.speed.toDouble() else null,
    if (location.hasBearing()) location.bearing.toDouble() else null,
    if (location.hasAccuracy()) location.accuracy.toDouble() else null,
    if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null,
    satellites, location.provider ?: "unknown", location.time.takeIf { it > 0 })
