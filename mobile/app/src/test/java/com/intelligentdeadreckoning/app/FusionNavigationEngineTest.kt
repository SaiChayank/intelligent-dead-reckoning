package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.conjugate
import com.intelligentdeadreckoning.app.calibration.isProperRotation
import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.app.calibration.plus
import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.calibration.rotationAngleBetween
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionFailure
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.FusionStatus
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.AltitudeReference
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Codec
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Severity
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production engine against synthetic contract records.
 *
 * The filter's arithmetic is tested in [FusionFilterTest] and its geodesy in [GeodesyTest]. What
 * is tested here is the boundary the product actually publishes: that everything leaving the
 * engine is one of the canonical payloads and survives the frozen codec, that the contract's
 * field rules hold in every state it can reach, and that the semantics a driver or a reviewer
 * would care about — no attitude without a vehicle frame, no fix without provider evidence, an
 * outage that coasts, a recovery that converges — behave as documented.
 */
class FusionNavigationEngineTest {

    private val header = Header("synthetic-drive", Source.REAL)
    private val session = EngineSession(header, "boot", 1_000_000_000L)
    private val calibration = CalibrationResult(
        id = "cal-1",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = MOUNTING_VEHICLE_FROM_DEVICE,
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.9,
    )

    private fun engine(config: FusionConfig = FusionConfig()) =
        FusionNavigationEngine(config = config, publicationIntervalNs = 0L)

    private fun calRecord(result: CalibrationResult) =
        Record(header, Event("cal", session.origin_ns, session.origin_ns, result))

    private fun imu(tNs: Long, accel: Vector3, gyro: Vector3): List<Record> = listOf(
        Record(
            header,
            Event(
                "a$tNs", tNs, tNs,
                ImuMeasurement(
                    Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                    ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH,
                ),
            ),
        ),
        Record(
            header,
            Event(
                "g$tNs", tNs, tNs,
                ImuMeasurement(
                    Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                    ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH,
                ),
            ),
        ),
    )

    private fun fix(
        tNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double? = 500.0,
        horizontalAccuracyM: Double? = 3.0,
        verticalAccuracyM: Double? = 5.0,
        speedM_S: Double? = null,
        bearingDeg: Double? = null,
        satellitesUsed: Long? = 20L,
        provider: String = "gps",
    ) = Record(
        header,
        Event(
            "n$tNs", tNs, tNs,
            GnssMeasurement(
                latitude_deg = latitudeDeg,
                longitude_deg = longitudeDeg,
                altitude_m = altitudeM,
                altitude_reference = if (altitudeM != null) AltitudeReference.ELLIPSOID else null,
                speed_m_s = speedM_S,
                bearing_deg = bearingDeg,
                horizontal_accuracy_m = horizontalAccuracyM,
                vertical_accuracy_m = verticalAccuracyM,
                satellites_used = satellitesUsed,
                provider = provider,
                utc_ms = null,
            ),
        ),
    )

    private inner class Drive(
        val engine: FusionNavigationEngine,
        val t0Ns: Long,
    ) {
        var tNs = t0Ns

        /** One second of straight 10 m/s driving north, feeding the samples the physics produces. */
        fun second() {
            for (i in 1..100) {
                tNs += 10_000_000L
                // Specific force at level, constant velocity is purely the reaction to gravity.
                val (accel, gyro) = imu(tNs, Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0))
                engine.acceptImu(accel)
                engine.acceptImu(gyro)
            }
        }

        /** The truth's geodetic position after the seconds driven so far. */
        var lastFixLatitudeDeg = ANCHOR_LAT

        fun fixHere(accuracyM: Double = 3.0): Record {
            val metres = 10.0 * (tNs - t0Ns) / 1e9
            val rN = Geodesy.radii(Math.toRadians(ANCHOR_LAT)).second
            lastFixLatitudeDeg = ANCHOR_LAT + Math.toDegrees(metres / rN)
            return fix(
                tNs,
                lastFixLatitudeDeg,
                ANCHOR_LON,
                horizontalAccuracyM = accuracyM,
                speedM_S = 10.0,
                bearingDeg = 0.0,
            )
        }
    }

    private fun take(engine: FusionNavigationEngine): List<Record> = engine.drain().toList()

    /** Everything the engine has emitted since this test began, not merely what is queued now. */
    private fun drain(engine: FusionNavigationEngine): List<Record> {
        val fresh = take(engine)
        history.addAll(fresh)
        return fresh
    }

    private val history = mutableListOf<Record>()

    private fun states(records: List<Record>) = records.mapNotNull { it.event.data as? NavigationState }

    private fun confidences(records: List<Record>) = records.mapNotNull { it.event.data as? Confidence }

    private fun diagnostics(records: List<Record>) = records.mapNotNull { it.event.data as? DiagnosticEvent }

    private fun codes(records: List<Record>) = diagnostics(records).map { it.code }

    // ------------------------------------------------------------- canonical output only

    @Test
    fun everythingPublishedIsCanonicalAndSurvivesTheFrozenCodec() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        fusion.stop()
        val records = drain(fusion)
        assertTrue("the engine published nothing", records.isNotEmpty())
        for (record in records) {
            assertEquals(record, Codec.decodeJson(Codec.encodeJson(record)))
        }
        val kinds = records.map { it.event.data.type }.toSet()
        assertTrue(
            "non-canonical payload kinds published: $kinds",
            kinds.all {
                it == "navigation" || it == "confidence" || it == "diagnostic" || it == "calibration"
            },
        )
    }

    // ------------------------------------------------------------- the contract's field rules

    @Test
    fun uninitializedStatesCarryNoSpatialFields() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        // IMU arrives before any fix: there is an anchor to propagate from, so the filter stays
        // uninitialized and nothing spatial may be claimed.
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        val records = drain(fusion)
        for (state in states(records)) {
            assertEquals(NavigationStatus.UNINITIALIZED, state.status)
            assertNull(state.origin_wgs84_deg_m)
            assertNull(state.position_enu_m)
            assertNull(state.velocity_enu_m_s)
            assertNull(state.q_enu_from_vehicle_wxyz)
            assertNull(state.heading_deg)
            assertNull(state.calibration_id)
            assertFalse(state.gnss_used_after_initialization)
        }
        for (confidence in confidences(records)) {
            assertEquals(ConfidenceState.UNAVAILABLE, confidence.state)
            assertNull(confidence.probability)
            assertNull(confidence.horizontal_accuracy_95_m)
            assertNull(confidence.speed_std_m_s)
        }
    }

    @Test
    fun aFailedRunPublishesNoSpatialFieldsEvenThoughItHasThem() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        assertTrue(fusion.solution.aligned)
        drain(fusion)
        // A NaN coordinate must be refused as invalid evidence before it can poison anything.
        fusion.acceptGnss(fix(drive.tNs, Double.NaN, ANCHOR_LON, speedM_S = 10.0, bearingDeg = 0.0))
        drain(fusion)
        assertTrue(
            "the NaN fix was not refused: ${codes(history)}",
            codes(history).contains("GNSS_MEASUREMENT_REFUSED"),
        )
        assertTrue(fusion.solution.aligned)
        // Two updates so far, both from the second fix: its position row and its course-velocity
        // row. The NaN fix added nothing, which is the point.
        assertEquals("the NaN fix reached the filter", 2L, fusion.solution.acceptedUpdates)
        // One non-finite inertial sample is not smoothed over: the run is declared lost, because
        // there is no honest way to integrate a NaN and no way to know how long it has been wrong.
        // The sample carries a fresh timestamp, so it is really propagated and not merely paired
        // against the previous sample's accelerometer reading at a duplicate instant.
        drive.second()
        val nanAt = drive.tNs + 10_000_000L
        fusion.acceptImu(
            Record(
                header,
                Event(
                    "nanaccel", nanAt, nanAt,
                    ImuMeasurement(
                        Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                        ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(0.0, 0.0, 9.80665),
                        SensorAccuracy.HIGH,
                    ),
                ),
            ),
        )
        fusion.acceptImu(
            Record(
                header,
                Event(
                    "nandrive", nanAt, nanAt,
                    ImuMeasurement(
                        Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE, ImuUnit.RADIANS_PER_SECOND,
                        Vector3(Double.NaN, 0.0, 0.0), SensorAccuracy.HIGH,
                    ),
                ),
            ),
        )
        drain(fusion)
        assertEquals(FusionStatus.FAILED, fusion.solution.status)
        assertEquals(FusionFailure.NON_FINITE_SAMPLE, fusion.solution.failure)
        assertTrue(
            "no failure diagnostic: ${codes(history)}",
            codes(history).contains("ENGINE_FAILURE"),
        )
        // Nothing past the failure moves: more samples and more fixes are refused whole.
        val frozen = fusion.solution.positionEnuM
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        assertEquals(frozen, fusion.solution.positionEnuM)
        fusion.stop()
        for (state in states(drain(fusion))) {
            assertNull("a failed state published a position", state.position_enu_m)
            assertNull("a failed state published an origin", state.origin_wgs84_deg_m)
            assertNull("a failed state published a heading", state.heading_deg)
            assertNull("a failed state published a calibration id", state.calibration_id)
        }
    }

    @Test
    fun trackingCarriesTheWholeSolutionAndAProperAttitude() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        val last = states(drain(fusion)).last()
        assertEquals(NavigationStatus.TRACKING, last.status)
        val origin = requireNotNull(last.origin_wgs84_deg_m)
        val anchor = GeodeticAnchor(origin.latitude_deg, origin.longitude_deg, origin.altitude_m, true)
        // The published position is relative to the published origin, and the two agree: putting
        // the position back through the map reproduces the fix the state was built from.
        val point = Geodesy.geodeticFromEnu(anchor, last.position_enu_m!!)
        assertTrue(abs(point.latitudeDeg - latitudeOf(drive)) < 1e-6)
        val attitude = last.q_enu_from_vehicle_wxyz
        assertNotNull(attitude)
        assertTrue(isProperRotation(attitude!!, 1e-9))
        assertEquals(last.calibration_id, "cal-1")
        assertTrue(last.heading_deg!! in 0.0..360.0)
        assertTrue(last.gnss_used_after_initialization)
    }

    // ------------------------------------------------------------- calibration

    @Test
    fun withoutAValidCalibrationTheEngineNeverReachesTracking() {
        val fusion = engine()
        val invalid = calibration.copy(status = CalibrationStatus.INVALID, q_vehicle_from_device_wxyz = null)
        fusion.initialize(session, calRecord(invalid), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        fusion.stop()
        val published = states(drain(fusion))
        // The fixes were used, the filter may even be aligned — but the vehicle heading is not
        // known, so the contract's tracking state is never claimed.
        assertTrue(published.none { it.status == NavigationStatus.TRACKING })
        // The calibration diagnostic was emitted at initialize; the queue has since been
        // drained, so it is read from the accumulated history.
        assertTrue(
            "no CALIBRATION_REQUIRED diagnostic: ${codes(history)}",
            codes(history).contains("CALIBRATION_REQUIRED"),
        )
        val last = published.last()
        assertNull(last.q_enu_from_vehicle_wxyz)
        assertNull(last.heading_deg)
        assertNull(last.calibration_id)
    }

    @Test
    fun aNonUnitTransformIsRefusedLikeAnInvalidOne() {
        val fusion = engine()
        // A unit quaternion is always a proper rotation, so the only transform a quaternion can
        // carry that is *not* one is a non-unit (or non-finite) one. Scaling by two is exactly
        // that: trusting it would scale every reported direction and position error.
        val mount = MOUNTING_VEHICLE_FROM_DEVICE
        val inflated = calibration.copy(
            q_vehicle_from_device_wxyz = Quaternion(mount.w * 2.0, mount.x, mount.y, mount.z),
        )
        assertTrue(isProperRotation(calibration.q_vehicle_from_device_wxyz!!, 1e-9))
        assertFalse(isProperRotation(inflated.q_vehicle_from_device_wxyz!!, 1e-9))
        fusion.initialize(session, calRecord(inflated), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drain(fusion)
        // A transform that is not a rotation cannot be trusted to carry the vehicle frame; the
        // engine refuses it exactly as the calibration engine does and stays uninitialized.
        assertFalse(fusion.solution.aligned)
        assertTrue(
            "an invalid transform was adopted without a CALIBRATION_REQUIRED diagnostic: ${codes(history)}",
            codes(history).contains("CALIBRATION_REQUIRED"),
        )
    }

    // ------------------------------------------------------------- refusal before filtering

    @Test
    fun aFixWithoutProviderEvidenceIsRefusedBeforeTheFilter() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        // No horizontal accuracy at all: there is no honest way to form its covariance.
        fusion.acceptGnss(
            fix(session.origin_ns + 1_000_000_000L, ANCHOR_LAT, ANCHOR_LON, horizontalAccuracyM = null),
        )
        val records = drain(fusion)
        assertFalse("the fix was not refused", fusion.solution.aligned)
        assertTrue(codes(history).contains("GNSS_MEASUREMENT_REFUSED"))
        // A provider nobody has a profile for cannot be judged against any bound.
        fusion.acceptGnss(
            fix(
                session.origin_ns + 2_000_000_000L, ANCHOR_LAT, ANCHOR_LON,
                provider = "mystery", satellitesUsed = null,
            ),
        )
        assertTrue(codes(drain(fusion)).isNotEmpty())
    }

    @Test
    fun aPoorAccuracyFixIsRefusedAndAConfidentFixIsNot() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere(accuracyM = 40.0))
        val refused = !fusion.solution.aligned
        fusion.acceptGnss(drive.fixHere(accuracyM = 3.0))
        assertTrue("a 40 m fix was not refused", refused)
        assertTrue("a 3 m fix was refused", fusion.solution.aligned)
    }

    // ------------------------------------------------------------- outage and recovery

    @Test
    fun anOutageCoastsAndTheStatusFallsToDegradedWithTheCovarianceGrowing() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        val fresh = states(drain(fusion)).last()
        assertEquals(NavigationStatus.TRACKING, fresh.status)
        val sigmaBefore = fusion.solution.positionSigmaM.x
        // Ten seconds with no GNSS at all, while the samples keep arriving.
        repeat(10) { drive.second() }
        val coasted = states(drain(fusion)).last()
        assertEquals(NavigationStatus.DEGRADED, coasted.status)
        // Coasting, not a failure: the position and the attitude are still published.
        assertNotNull(coasted.position_enu_m)
        assertNotNull(coasted.q_enu_from_vehicle_wxyz)
        assertTrue(fusion.solution.positionSigmaM.x > sigmaBefore)
        val codesSoFar = codes(history)
        assertTrue(
            "no outage diagnostic: $codesSoFar",
            codesSoFar.contains("FUSION_GNSS_OUTAGE"),
        )
        // And the fix that ends the outage is a measurement update, not a snap.
        val before = fusion.solution.positionEnuM
        fusion.acceptGnss(drive.fixHere())
        val after = fusion.solution.positionEnuM
        val moved = Math.hypot(after.x - before.x, after.y - before.y)
        assertTrue("the state jumped $moved m to the returning fix", moved < 5.0)
        val recovered = states(drain(fusion)).last()
        assertEquals(NavigationStatus.TRACKING, recovered.status)
        assertTrue(
            "no recovery diagnostic: ${codes(history)}",
            codes(history).contains("FUSION_GNSS_RECOVERED"),
        )
    }

    @Test
    fun aPersistentlyLyingStreamIsRefusedUpdateAfterUpdateWithoutBeingFollowed() {
        // A cross-track lie three kilometres out. The covariance never grows fast enough to admit
        // it, so the gate refuses every one — and that is the system working: the solution is
        // *not* dragged three kilometres, and the diagnostics say why. The deeper limitation is
        // tested at the filter level: against a lie the covariance *can* grow into, the gate
        // eventually opens, because an innovation gate is a statistical test against the filter's
        // own reported uncertainty and nothing more.
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drain(fusion)
        val anchorAt = GeodeticAnchor(ANCHOR_LAT, ANCHOR_LON, 500.0, true)
        repeat(10) {
            drive.second()
            val biased = Geodesy.geodeticFromEnu(
                anchorAt,
                Geodesy.enuFromGeodetic(anchorAt, drive.lastFixLatitudeDeg, ANCHOR_LON, 500.0) +
                    Vector3(3000.0, 0.0, 0.0),
            )
            fusion.acceptGnss(
                fix(drive.tNs, biased.latitudeDeg, biased.longitudeDeg, speedM_S = 10.0, bearingDeg = 0.0),
            )
        }
        drain(fusion)
        val solution = fusion.solution
        // Every position row was refused, so the state never moved. The course-velocity rows of
        // the same fixes were still accepted — they say how the vehicle moves, not where it is,
        // and there is nothing dishonest about a liar's speedometer being right.
        assertEquals(10L, solution.rejectedUpdates)
        assertEquals(10L, solution.acceptedUpdates)
        assertTrue(solution.lastRejectionNis!! > 16.2662)
        assertTrue(
            "no rejection diagnostic: ${codes(history)}",
            codes(history).contains("GNSS_UPDATE_REJECTED"),
        )
        // The refusal kept the solution where the physics says the vehicle is, not where the
        // stream insisted it was. Both are taken in the solution's own anchor frame, which is
        // where its ENU position lives.
        val truth = Geodesy.enuFromGeodetic(
            solution.anchor!!, latitudeOf(drive), ANCHOR_LON, 500.0,
        )
        val position = solution.positionEnuM
        val honestDistance = Math.hypot(position.x - truth.x, position.y - truth.y)
        assertTrue("the state strayed $honestDistance m from the truth", honestDistance < 1.0)
        // And the lie is far away, exactly as refused: the solution never moved three kilometres.
        val lieDistance = Math.hypot(position.x - (truth.x + 3000.0), position.y - truth.y)
        assertTrue("the state is only $lieDistance m from the lie", lieDistance > 2900.0)
    }

    // ------------------------------------------------------------- alignment is not a snap

    @Test
    fun theFirstUsableFixAlignsAndThePositionStartsAtTheAnchor() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        val records = drain(fusion)
        // The first fix *sets* the anchor — that is alignment, the one moment the state is taken
        // from outside evidence. Every later fix is a gated update instead.
        val aligned = states(records).last()
        assertEquals(NavigationStatus.TRACKING, aligned.status)
        val origin = aligned.origin_wgs84_deg_m!!
        // The anchor is the fix itself, to the last bit: alignment sets it, it does not average it.
        assertEquals(drive.lastFixLatitudeDeg, origin.latitude_deg, 0.0)
        assertEquals(ANCHOR_LON, origin.longitude_deg, 0.0)
        assertEquals(Vector3(0.0, 0.0, 0.0).x, aligned.position_enu_m!!.x, 1.0)
        assertEquals(0.0, aligned.position_enu_m!!.y, 1.0)
        assertTrue(
            "FUSION_ALIGNED was not published: ${codes(records)}",
            codes(records).contains("FUSION_ALIGNED"),
        )
    }

    // ------------------------------------------------------------- confidence honesty

    @Test
    fun confidenceIsTheFilterOwnCovarianceWithAStatedConversion() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        val confidence = confidences(drain(fusion)).last()
        assertEquals(ConfidenceState.UNVALIDATED, confidence.state)
        // probability must be null unless the state is CALIBRATED, which this covariance has not
        // earned: it is a model output, not a validated error bound.
        assertNull(confidence.probability)
        assertNotNull(confidence.horizontal_accuracy_95_m)
        assertNotNull(confidence.speed_std_m_s)
        // The 95% radius is the stated 1-sigma-to-95% conversion of the filter's own sigmas.
        val solution = fusion.solution
        val sigma = Math.sqrt(
            (solution.positionSigmaM.x * solution.positionSigmaM.x +
                solution.positionSigmaM.y * solution.positionSigmaM.y) / 2.0,
        )
        assertEquals(Math.sqrt(5.991) * sigma, confidence.horizontal_accuracy_95_m!!, 1e-9)
    }

    // ------------------------------------------------------------- mode stability

    @Test
    fun theInitializationModeNeverChangesSilently() {
        val fusion = engine()
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        val drive = Drive(fusion, session.origin_ns)
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        drive.second()
        fusion.acceptGnss(drive.fixHere())
        fusion.stop()
        val modes = states(drain(fusion)).map { it.initialization_mode }.toSet()
        assertEquals(setOf(InitializationMode.EVALUATION), modes)
    }

    // ------------------------------------------------------------- helpers

    private fun latitudeOf(drive: Drive): Double {
        val metres = 10.0 * (drive.tNs - drive.t0Ns) / 1e9
        val rN = Geodesy.radii(Math.toRadians(ANCHOR_LAT)).second
        return ANCHOR_LAT + Math.toDegrees(metres / rN)
    }

    companion object {
        const val ANCHOR_LAT = 17.5
        const val ANCHOR_LON = 78.4

        /** Device yawed 90 degrees in the vehicle, so the mounting is actually exercised. */
        val MOUNTING_VEHICLE_FROM_DEVICE: Quaternion = quaternionAboutZ(Math.PI / 2.0)
    }
}
