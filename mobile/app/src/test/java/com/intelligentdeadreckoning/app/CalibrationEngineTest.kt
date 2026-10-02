package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.CalibrationDiagnostic
import com.intelligentdeadreckoning.app.calibration.CalibrationNavigationEngine
import com.intelligentdeadreckoning.app.calibration.CalibrationOutcome
import com.intelligentdeadreckoning.app.calibration.canonical
import com.intelligentdeadreckoning.app.calibration.GRAVITY_STANDARD_M_S2
import com.intelligentdeadreckoning.app.calibration.GnssFix
import com.intelligentdeadreckoning.app.calibration.ImuPair
import com.intelligentdeadreckoning.app.calibration.MountEstimator
import com.intelligentdeadreckoning.app.calibration.MountThresholds
import com.intelligentdeadreckoning.app.calibration.StationaryAccumulator
import com.intelligentdeadreckoning.app.calibration.StationaryWindow
import com.intelligentdeadreckoning.app.calibration.angleBetweenVectors
import com.intelligentdeadreckoning.app.calibration.conjugate
import com.intelligentdeadreckoning.app.calibration.cross
import com.intelligentdeadreckoning.app.calibration.isProperRotation
import com.intelligentdeadreckoning.app.calibration.minus
import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.app.calibration.normalizedOrNull
import com.intelligentdeadreckoning.app.calibration.plus
import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.calibration.quaternionFromAxisAngle
import com.intelligentdeadreckoning.app.calibration.removeComponentAlong
import com.intelligentdeadreckoning.app.calibration.rotate
import com.intelligentdeadreckoning.app.calibration.rotationAngleBetween
import com.intelligentdeadreckoning.app.calibration.rotationFromTo
import com.intelligentdeadreckoning.app.calibration.times
import com.intelligentdeadreckoning.app.calibration.toRotationMatrix
import com.intelligentdeadreckoning.app.navigation.NavigationPhase
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Codec
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
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
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analytic tests for the phone-to-vehicle calibration engine.
 *
 * Every case is built from ground truth: a true mount is chosen, contract-level IMU and GNSS
 * evidence is generated from it, and the estimate is compared against the truth rather than
 * against another estimate. The synthetic vehicle therefore models the things that make a real
 * collection hard — sensor noise, road vibration, a receiver that lies about its course — because
 * a calibration that only works on noise-free samples has not been tested at all.
 *
 * The target frame is X forward, Y left, Z up, right-handed, and the quantity under test is
 * `q_vehicle_from_device`: the rotation taking a measurement from the Android device frame into
 * that vehicle frame.
 */
class CalibrationEngineTest {

    // -------------------------------------------------------------------------------------------
    // Mount geometry
    // -------------------------------------------------------------------------------------------

    @Test
    fun declaredMountsMapTheirDeviceAxesWhereTheVehicleFrameExpects() {
        // A transposed mount literal would still be self-consistent with evidence generated from
        // it, so the physical claim is pinned directly instead of only through recovery.
        assertDeviceAxes(IDENTITY_MOUNT, DEVICE_FORWARD, DEVICE_LEFT, DEVICE_UP)
        assertDeviceAxes(PORTRAIT_MOUNT, DEVICE_RIGHT, DEVICE_UP, Vector3(-1.0, 0.0, 0.0))
        assertDeviceAxes(LANDSCAPE_MOUNT, DEVICE_UP, DEVICE_LEFT, Vector3(-1.0, 0.0, 0.0))
        for ((name, mount) in MOUNTS) {
            assertTrue("$name is not a proper rotation", isProperRotation(mount, 1e-12))
        }
    }

    @Test
    fun identityMountIsRecoveredExactly() {
        val valid = assertValid(calibrate(SyntheticVehicle(IDENTITY_MOUNT)))

        assertTrue("yaw was not claimed as measured", valid.yawMeasured)
        assertTransform(valid, IDENTITY_MOUNT, EXACT_DEGREES)
    }

    @Test
    fun portraitMountIsRecovered() {
        val valid = assertValid(calibrate(SyntheticVehicle(PORTRAIT_MOUNT)))

        assertTransform(valid, PORTRAIT_MOUNT, EXACT_DEGREES)
    }

    @Test
    fun landscapeMountIsRecovered() {
        val valid = assertValid(calibrate(SyntheticVehicle(LANDSCAPE_MOUNT)))

        assertTransform(valid, LANDSCAPE_MOUNT, EXACT_DEGREES)
    }

    @Test
    fun tiltedPhoneIsRecovered() {
        // Yawed 100 degrees, pitched back 8 and rolled -6: a plausible windscreen cradle that no
        // alignment shortcut would guess, so recovery has to come from gravity plus the drive.
        val valid = assertValid(calibrate(SyntheticVehicle(TILTED_MOUNT)))

        assertTransform(valid, TILTED_MOUNT, EXACT_DEGREES)
    }

    @Test
    fun theSameMountIsRecoveredOnDifferentCourses() {
        // The mount is a property of the phone and the course is not, so the answer must not move
        // when the drive does. A yaw taken from the world-frame course instead of from the
        // measured force would differ between these two.
        val east = assertValid(calibrate(SyntheticVehicle(TILTED_MOUNT), headingDeg = 90.0))
        val northEast = assertValid(calibrate(SyntheticVehicle(TILTED_MOUNT), headingDeg = 30.0))

        assertTransform(east, TILTED_MOUNT, EXACT_DEGREES)
        assertTransform(northEast, TILTED_MOUNT, EXACT_DEGREES)
    }

    // -------------------------------------------------------------------------------------------
    // Stage A: noise, stability, bias
    // -------------------------------------------------------------------------------------------

    @Test
    fun noisyStationaryWindowMeasuresTiltAndCapsConfidence() {
        val vehicle = SyntheticVehicle(
            TILTED_MOUNT,
            gyroBiasRad_S = Vector3(0.004, -0.006, 0.003),
            accelNoiseM_S2 = 0.02,
            gyroNoiseRad_S = 0.004,
        )
        val run = CalibrationRun()
        run.feed(vehicle.parked(6.0))
        run.finish()

        val pending = assertPresent("no resting window was accepted", run.last)
        assertEquals(CalibrationStatus.PENDING, pending.status)
        assertFalse("yaw cannot be measured at rest", pending.yawMeasured)
        val transform = assertPresent("tilt was not published", pending.qVehicleFromDevice)
        val confidence = assertPresent("pending confidence", pending.confidence)
        assertTrue("pending confidence must be capped, was $confidence", confidence <= 0.5)
        assertTrue("pending confidence must be positive, was $confidence", confidence > 0.0)
        assertNull("no calibration should be valid yet", run.valid)

        // The tilt is the part that was measured, so the transform must map the true device up
        // direction onto vehicle up even though its yaw is still a placeholder.
        val measuredUp = vehicle.qVehicleFromDevice.conjugate().rotate(VEHICLE_UP)
        val tiltErrorDeg = Math.toDegrees(angleBetweenVectors(transform.rotate(measuredUp), VEHICLE_UP))
        assertTrue("tilt is off by $tiltErrorDeg deg", tiltErrorDeg < MEASURED_DEGREES)
    }

    @Test
    fun aVibratingParkedPeriodIsNotARestingMeasurement() {
        // Engine idle and a passenger touching the phone: perfectly plausible magnitudes, but
        // nothing that can measure gravity. No transform may be published at all.
        val vehicle = SyntheticVehicle(IDENTITY_MOUNT, accelNoiseM_S2 = 0.3)
        val run = CalibrationRun()
        run.feed(vehicle.parked(6.0))
        run.finish()

        assertTrue("windows were accepted from a vibrating phone", run.windows.isEmpty())
        assertTrue(
            "a gravity estimate was invented from noise",
            run.outcomes.none { it.qVehicleFromDevice != null },
        )
        assertNull(run.estimator.current.qVehicleFromDevice)
    }

    @Test
    fun drivenSamplesAreNeverBlendedIntoTheRestingWindow() {
        // The physically dangerous case: a parked period followed by a hard pull-away, with no
        // vibration to give the drive away. Judging the whole period at once would produce a
        // window whose gravity direction is degrees off while still looking perfectly stable.
        val vehicle = SyntheticVehicle(TILTED_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        run.feed(vehicle.driving(10.0, 3.0, 6.0, 45.0))
        run.finish()

        val parked = run.windows.first()
        val trueUp = vehicle.qVehicleFromDevice.conjugate().rotate(VEHICLE_UP)
        val errorDeg = Math.toDegrees(angleBetweenVectors(trueUp, parked.upDevice!!))
        assertTrue("the resting window drifted $errorDeg deg from the true up", errorDeg < 0.05)

        // This drive is quiet in the synthetic model, so only the GNSS motion guard stops the
        // driven period from becoming a second "resting" window.
        assertEquals("the driven period did not produce a window", 2, run.windows.size)
        assertTrue(
            "the driven window was not discarded: ${run.codes}",
            run.codes.contains("CALIBRATION_WINDOW_MOVING"),
        )
    }

    @Test
    fun gyroBiasIsMeasuredFromRest() {
        val bias = Vector3(0.012, -0.021, 0.006)
        val run = calibrate(SyntheticVehicle(PORTRAIT_MOUNT, gyroBiasRad_S = bias))

        val measured = assertPresent("no gyro bias was published", assertValid(run).gyroBiasRad_S)
        assertEquals(bias.x, measured.x, 1e-9)
        assertEquals(bias.y, measured.y, 1e-9)
        assertEquals(bias.z, measured.z, 1e-9)
    }

    @Test
    fun accelerometerBiasIsReportedOnlyWhenTheAttitudesJustifyIt() {
        val bias = Vector3(0.04, -0.03, 0.05)

        // One attitude cannot separate a horizontal bias from a tilt, nor a vertical bias from a
        // gravity-scale error, so the honest answer is that the bias is unknown.
        val single = CalibrationRun()
        single.window(windowAt(DEVICE_UP, 0L, biasM_S2 = bias))
        assertNull(single.estimator.current.accelBiasM_S2)

        // A tetrahedron of attitudes is well conditioned, and the bias is then exactly soluble.
        val spread = CalibrationRun()
        TETRAHEDRON.forEachIndexed { index, up ->
            spread.window(windowAt(up, index * 5_000_000_000L, biasM_S2 = bias))
        }
        val solved = assertPresent("bias was not solved", spread.estimator.current.accelBiasM_S2)
        assertEquals(bias.x, solved.x, 1e-6)
        assertEquals(bias.y, solved.y, 1e-6)
        assertEquals(bias.z, solved.z, 1e-6)

        // A solve larger than any credible accelerometer bias is refused rather than published.
        val absurd = CalibrationRun()
        TETRAHEDRON.forEachIndexed { index, up ->
            absurd.window(windowAt(up, index * 5_000_000_000L, biasM_S2 = Vector3(1.4, 1.4, 1.4)))
        }
        assertNull(absurd.estimator.current.accelBiasM_S2)
        assertTrue(absurd.codes.contains("CALIBRATION_ACCEL_BIAS_NOT_JUSTIFIED"))
    }

    @Test
    fun attitudesConfinedToAPlaneDoNotProduceABias() {
        // A phone that only ever rests on a flat dash and is re-placed within that same plane:
        // the attitudes span two dimensions, so the out-of-plane bias is unobservable no matter
        // how many windows there are. The honest answer is that the bias is unknown.
        val run = CalibrationRun()
        listOf(
            Vector3(0.0, 0.0, 1.0),
            Vector3(0.20, 0.0, 0.980),
            Vector3(-0.20, 0.0, 0.980),
            Vector3(0.0, 0.0, 1.0),
        ).forEachIndexed { index, up ->
            run.window(windowAt(up, index * 5_000_000_000L, biasM_S2 = Vector3(0.05, 0.0, 0.0)))
        }

        assertNull(run.estimator.current.accelBiasM_S2)
    }

    // -------------------------------------------------------------------------------------------
    // Stage B: rejections, contradictions, and how many segments are needed
    // -------------------------------------------------------------------------------------------

    @Test
    fun constantSpeedDrivingCarriesNoDirectionAndIsRejected() {
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        run.feed(vehicle.driving(15.0, 0.0, 12.0, 0.0))
        run.finish()

        assertNull("a yaw was invented from constant-speed cruise", run.valid)
        assertTrue(run.codes.contains("CALIBRATION_SEGMENT_INSUFFICIENT_EXCITATION"))
        val pending = assertPresent("nothing was published", run.last)
        assertEquals(CalibrationStatus.PENDING, pending.status)
        assertFalse(pending.yawMeasured)
    }

    @Test
    fun weakExcitationBelowThresholdIsRejected() {
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        // 0.1 m/s^2 of specific force: real motion, but far too little to locate an axis.
        run.feed(vehicle.driving(15.0, 0.1, 12.0, 0.0))
        run.finish()

        assertNull("a yaw was measured from almost no excitation", run.valid)
        assertTrue(run.codes.contains("CALIBRATION_SEGMENT_INSUFFICIENT_EXCITATION"))
    }

    @Test
    fun aCourseThatContradictsMeasuredDisplacementIsRejected() {
        // The receiver reports a course 90 degrees from the displacement measured between its own
        // fixes. Nothing about that course can be trusted, so no yaw may be published from it.
        val lying = calibrate(
            SyntheticVehicle(PORTRAIT_MOUNT),
            headingDeg = 20.0,
            reportedHeadingDeg = 110.0,
        )
        assertNull("a yaw was measured from a course the fixes contradict", lying.valid)
        assertTrue(
            "the contradiction was not reported: ${lying.codes}",
            lying.codes.contains("CALIBRATION_SEGMENT_GNSS_COURSE_INCONSISTENT"),
        )

        // The control: identical motion, honest course, same mount recovered. The rejection above
        // is about the lie, not about the drive.
        val honest = assertValid(calibrate(SyntheticVehicle(PORTRAIT_MOUNT), headingDeg = 20.0))
        assertTransform(honest, PORTRAIT_MOUNT, EXACT_DEGREES)
    }

    @Test
    fun turningDrivingIsRejected() {
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        // 0.4 rad/s about vehicle up: the lateral force of a corner would leak into the direction
        // the longitudinal axis is measured from, so the segment cannot be used.
        run.feed(vehicle.driving(12.0, 1.5, 12.0, 90.0, yawRateRad_S = 0.4))
        run.finish()

        assertNull("a yaw was measured while turning", run.valid)
        assertTrue(run.codes.contains("CALIBRATION_SEGMENT_NOT_STRAIGHT"))
    }

    @Test
    fun inaccurateFixesAreRejected() {
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        run.feed(vehicle.driving(12.0, 1.5, 12.0, 90.0, accuracyM = 40.0))
        run.finish()

        assertNull("a yaw was measured from 40 m fixes", run.valid)
        assertTrue(run.codes.contains("CALIBRATION_SEGMENT_GNSS_INACCURATE"))
    }

    @Test
    fun evaluationNeedsOneSegmentAndDeployableNeedsTwo() {
        // One straight, well-excited segment is enough to evaluate a mount; trusting a phone in
        // service needs a second independent determination of the same yaw.
        val evaluation = CalibrationRun(InitializationMode.EVALUATION)
        evaluation.feed(oneDrive())
        evaluation.finish()
        assertValid(evaluation)

        val deployable = CalibrationRun(InitializationMode.DEPLOYABLE)
        deployable.feed(oneDrive())
        deployable.finish()
        assertNull("a single segment was accepted as deployable", deployable.valid)
        val pending = assertPresent("nothing was published", deployable.last)
        assertFalse(pending.yawMeasured)
    }

    @Test
    fun contradictorySegmentsRequireRecalibrationInsteadOfBeingAveraged() {
        // The phone is turned 30 degrees within its cradle between two drives. Rotation about
        // vehicle up is invisible to gravity, so nothing expires the calibration; the two
        // segments simply disagree about vehicle forward, and that is contradictory evidence
        // rather than noise to be averaged away.
        val first = SyntheticVehicle(PORTRAIT_MOUNT, seed = 11L)
        val run = CalibrationRun(InitializationMode.DEPLOYABLE)
        run.feed(first.parked(3.0))
        run.feed(first.driving(10.0, 1.5, 12.0, 90.0))
        run.feed(listOf(first.fix(0.0)))

        val second = SyntheticVehicle(
            quaternionAboutZ(30.0 * DEGREES) * PORTRAIT_MOUNT,
            seed = 12L,
            startNs = first.nowNs + 1_000_000_000L,
        )
        run.feed(second.driving(10.0, 1.5, 12.0, 90.0))
        run.finish()

        assertNull("contradictory segments produced a calibration", run.valid)
        assertTrue(
            "the contradiction was not reported: ${run.codes}",
            run.codes.contains("CALIBRATION_YAW_INCONSISTENT"),
        )
        assertEquals(CalibrationStatus.INVALID, run.estimator.current.status)
    }

    // -------------------------------------------------------------------------------------------
    // Adopting a prior calibration
    // -------------------------------------------------------------------------------------------

    @Test
    fun aPriorIsAdoptedUnderThisSessionsOwnId() {
        val estimator = MountEstimator(MountThresholds(), InitializationMode.DEPLOYABLE, "session-b")
        assertTrue(estimator.adoptPrior(PORTRAIT_MOUNT, Vector3(0.01, 0.0, 0.0), null))

        // Adoption is announced immediately: a consumer told about an in-force calibration through
        // a navigation state must also receive the calibration itself.
        val adopted = assertPresent("adoption published nothing", estimator.drainOutcomes().lastOrNull())
        assertEquals(CalibrationStatus.VALID, adopted.status)
        assertTrue(
            "the prior's id was taken over instead of a new one being minted: ${adopted.id}",
            adopted.id.startsWith("session-b"),
        )
        assertTrue(isProperRotation(adopted.qVehicleFromDevice!!, 1e-9))
        assertEquals(0.01, adopted.gyroBiasRad_S!!.x, 1e-9)

        // A transform that is not a rigid rotation is refused outright, never renormalized.
        val strict = MountEstimator(MountThresholds(), InitializationMode.EVALUATION, "session-c")
        assertFalse(strict.adoptPrior(Quaternion(2.0, 0.0, 0.0, 0.0), null, null))
        assertFalse(strict.adoptPrior(Quaternion(1.0, 1.0, 0.0, 0.0), null, null))
        assertNull(strict.drainOutcomes().lastOrNull())
    }

    @Test
    fun anAdoptedPriorCountsAsOneYawDetermination() {
        // A deployment adopting a prior still has to earn its second segment, so a session that
        // adopts and then drives once is valid, while one that adopts and never drives is not
        // promoted by the adoption alone.
        val run = CalibrationRun(InitializationMode.DEPLOYABLE)
        assertTrue(run.estimator.adoptPrior(PORTRAIT_MOUNT, null, null))
        run.feed(emptyList())
        assertNotNull("the adopted calibration was not published", run.valid)

        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        run.feed(vehicle.driving(10.0, 1.5, 12.0, 90.0))
        run.finish()

        val valid = assertValid(run)
        assertTrue(valid.yawMeasured)
        assertTrue(isProperRotation(valid.qVehicleFromDevice!!, 1e-9))
    }

    // -------------------------------------------------------------------------------------------
    // Remount detection
    // -------------------------------------------------------------------------------------------

    @Test
    fun remountAtRestIsDetectedFromGravityAndRequiresRecalibration() {
        val vehicle = SyntheticVehicle(IDENTITY_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        run.feed(vehicle.driving(10.0, 1.5, 12.0, 90.0))
        run.finish()
        val original = assertValid(run)

        // The phone is re-placed while the car is parked. Delivering the window directly is the
        // only way to isolate the gravity gate: a real re-placement also rotates the phone, which
        // the handling gate would catch first (tested separately below).
        val alternativeUp = requireNotNull(
            LANDSCAPE_MOUNT.conjugate().rotate(VEHICLE_UP).normalizedOrNull(),
        )
        run.window(windowAt(alternativeUp, vehicle.nowNs + 60_000_000_000L))

        val expired = assertPresent(
            "no calibration was expired",
            run.outcomes.lastOrNull { it.status == CalibrationStatus.EXPIRED },
        )
        assertEquals(original.id, expired.id)
        assertEquals(0.0, expired.confidence!!, 1e-12)
        assertTrue(run.codes.contains("CALIBRATION_REMOUNT_DETECTED"))

        // A new determination starts at once, under a new id, with the gyro bias retained because
        // the sensor has not changed even though the mounting has.
        val pending = assertPresent("nothing replaced the expired calibration", run.last)
        assertEquals(CalibrationStatus.PENDING, pending.status)
        assertNotEquals(original.id, pending.id)
        assertNotNull("the new calibration has no tilt at all", pending.qVehicleFromDevice)
        assertNotNull("the gyro bias was needlessly discarded", pending.gyroBiasRad_S)
        assertFalse(pending.yawMeasured)

        // The expired calibration stays in the history, as an audit trail should, but it is no
        // longer what is in force, and nothing after it may be valid without recalibrating.
        assertNotEquals(CalibrationStatus.VALID, run.estimator.current.status)
        assertEquals(CalibrationStatus.PENDING, run.outcomes.last().status)
        val expiryIndex = run.outcomes.indexOfFirst { it.status == CalibrationStatus.EXPIRED }
        assertTrue("the calibration was never marked expired", expiryIndex >= 0)
        assertTrue(
            "a calibration became valid again without recalibration",
            run.outcomes.drop(expiryIndex + 1).none { it.status == CalibrationStatus.VALID },
        )
    }

    @Test
    fun handlingWhileParkedIsDetectedFromRotation() {
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        run.feed(vehicle.driving(10.0, 1.5, 12.0, 90.0))
        // The car stops, and then the phone is turned by hand.
        run.feed(listOf(vehicle.fix(0.0)))
        val calibrated = assertValid(run)

        // Rotation about vehicle up: invisible to gravity by construction, which is exactly why
        // the handling gate exists. 1 rad/s for 0.4 s is about 23 degrees.
        run.feed(vehicle.handling(0.4, rateRad_S = 1.0))

        val expired = assertPresent("handling was not detected", run.outcomes.lastOrNull {
            it.status == CalibrationStatus.EXPIRED
        })
        assertEquals(calibrated.id, expired.id)
        assertTrue(run.codes.contains("CALIBRATION_REMOUNT_DETECTED"))
        assertEquals(CalibrationStatus.PENDING, run.last!!.status)
    }

    @Test
    fun handlingBeforeAnyCalibrationExistsDoesNotExpireAnything() {
        // Parked, then handled before a calibration exists: there is nothing to invalidate, so the
        // accumulator must simply start a new window, and the session must still calibrate.
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val run = CalibrationRun()
        run.feed(vehicle.handling(1.0, rateRad_S = 1.0))
        run.feed(vehicle.parked(4.0))
        run.feed(vehicle.driving(12.0, 1.5, 12.0, 90.0))
        run.finish()

        assertTransform(assertValid(run), PORTRAIT_MOUNT, EXACT_DEGREES)
        assertFalse(run.codes.contains("CALIBRATION_REMOUNT_DETECTED"))
    }

    // -------------------------------------------------------------------------------------------
    // Quaternion invariants
    // -------------------------------------------------------------------------------------------

    @Test
    fun everyPublishedTransformIsAProperRotation() {
        for ((name, mount) in MOUNTS) {
            val run = calibrate(SyntheticVehicle(mount))
            val published = run.outcomes.filter { it.qVehicleFromDevice != null }
            assertTrue("$name published no transform", published.isNotEmpty())
            val deviceUp = run.windows.first().upDevice

            for (outcome in published) {
                val transform = outcome.qVehicleFromDevice!!
                assertTrue("$name published a non-rotation", isProperRotation(transform, 1e-9))
                assertEquals(
                    "$name published an unnormalized quaternion",
                    1.0, transform.norm(), 1e-12,
                )
                val matrix = transform.toRotationMatrix()
                val determinant = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
                    matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
                    matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
                assertEquals("$name published a reflection", 1.0, determinant, 1e-12)

                // The quaternion and the matrix must be two spellings of one rotation.
                for (axis in AXES) {
                    val byQuaternion = transform.rotate(axis)
                    val byMatrix = Vector3(
                        matrix[0] * axis.x + matrix[1] * axis.y + matrix[2] * axis.z,
                        matrix[3] * axis.x + matrix[4] * axis.y + matrix[5] * axis.z,
                        matrix[6] * axis.x + matrix[7] * axis.y + matrix[8] * axis.z,
                    )
                    assertEquals(0.0, (byQuaternion - byMatrix).norm(), 1e-12)
                }

                // Rotating the measured device up must land on vehicle up: this is the property
                // remount detection and every later tilt comparison depend on.
                if (deviceUp != null) {
                    val mapped = transform.rotate(deviceUp)
                    val tiltErrorDeg = Math.toDegrees(angleBetweenVectors(mapped, VEHICLE_UP))
                    assertTrue("$name tilted $tiltErrorDeg deg", tiltErrorDeg < EXACT_DEGREES)
                }
            }
        }
    }

    @Test
    fun rotationPrimitivesObeyTheirInvariants() {
        val rotation = yawPitchRoll(37.0, -21.0, 14.0)

        // A rotation and its negation are the same orientation, and the geodesic angle knows it.
        assertEquals(
            0.0,
            rotationAngleBetween(rotation, Quaternion(-rotation.w, -rotation.x, -rotation.y, -rotation.z)),
            1e-12,
        )
        // The product of a rotation with its inverse is the identity, not merely close to it.
        assertEquals(
            0.0,
            rotationAngleBetween(Quaternion(1.0, 0.0, 0.0, 0.0), rotation.conjugate() * rotation),
            1e-9,
        )
        assertEquals(rotation.w, rotation.canonical().w, 1e-12)

        // Composition applies the right-hand rotation first, and the inverse undoes it.
        val yaw = quaternionAboutZ(0.7)
        val composed = yaw * rotation
        val vector = Vector3(0.3, -0.7, 0.4)
        assertEquals(0.0, (composed.rotate(vector) - yaw.rotate(rotation.rotate(vector))).norm(), 1e-12)
        assertEquals(0.0, (rotation.conjugate().rotate(rotation.rotate(vector)) - vector).norm(), 1e-12)

        // Norm preservation is what makes a rotation usable on measured vectors.
        assertEquals(vector.norm(), rotation.rotate(vector).norm(), 1e-12)

        // `rotationFromTo` must do what the gravity alignment needs, including the case where the
        // two directions already agree and the rotation is the identity.
        val from = requireNotNull(TILTED_MOUNT.conjugate().rotate(VEHICLE_UP).normalizedOrNull())
        val toUp = rotationFromTo(from, VEHICLE_UP)
        assertEquals(0.0, Math.toDegrees(angleBetweenVectors(toUp.rotate(from), VEHICLE_UP)), 1e-9)
        assertTrue(isProperRotation(toUp, 1e-12))
        val aligned = rotationFromTo(VEHICLE_UP, VEHICLE_UP)
        assertEquals(0.0, (aligned.rotate(DEVICE_FORWARD) - DEVICE_FORWARD).norm(), 1e-12)

        // A quaternion cannot encode a reflection, so the `det = +1` check guards the conversion
        // to a matrix rather than the input: every rotation, including the 180-degree turns whose
        // `w` is zero, must come out proper.
        assertTrue(isProperRotation(Quaternion(0.0, 1.0, 0.0, 0.0), 1e-9))
        assertTrue(isProperRotation(Quaternion(0.0, 0.0, 0.0, 1.0), 1e-9))
        assertTrue(isProperRotation(Quaternion(1.0, 0.0, 0.0, 0.0), 1e-12))

        // What the gate does refuse is a quaternion that is not a unit rotation at all, rather
        // than silently normalizing it into one.
        assertFalse(isProperRotation(Quaternion(1.0, 1.0, 0.0, 0.0), 1e-9))
        assertFalse(isProperRotation(Quaternion(0.5, 0.0, 0.0, 0.0), 1e-9))
        assertFalse(isProperRotation(Quaternion(Double.NaN, 0.0, 0.0, 0.0), 1e-9))
    }

    @Test
    fun removingTheUpComponentLeavesExactlyTheHorizontalForce() {
        // The stage-B projection is the entire basis of the yaw estimate, so it is pinned on a
        // known case rather than only observed through a recovered mount.
        val transform = yawPitchRoll(-140.0, 5.0, 3.0)
        val deviceUp = requireNotNull(transform.conjugate().rotate(VEHICLE_UP).normalizedOrNull())
        val deviceForce = transform.conjugate().rotate(Vector3(1.8, 0.0, G))

        val horizontal = deviceForce.removeComponentAlong(deviceUp)
        val recovered = transform.rotate(horizontal)
        assertEquals("the up component was not removed", 0.0, recovered.z, 1e-9)
        assertEquals("the forward component was lost", 1.8, recovered.x, 1e-9)
        assertEquals(1.8, horizontal.norm(), 1e-9)
    }

    // -------------------------------------------------------------------------------------------
    // Integration: the runtime is the only way in
    // -------------------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun calibrationReachesTheRuntimeAsCanonicalContractRecords() = runTest {
        val header = Header("synthetic-drive", Source.REAL, "1.1.0")
        val runtime = NavigationRuntime(
            CalibrationNavigationEngine(), this, { 0L }, StandardTestDispatcher(testScheduler),
        )
        val outputs = mutableListOf<Record>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.output.collect { outputs += it }
        }

        assertTrue(runtime.start(header, 0L, InitializationMode.EVALUATION))
        advanceUntilIdle()
        assertEquals(NavigationPhase.RUNNING, runtime.state.value.phase)

        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT)
        val evidence = ArrayList<Evidence>()
        evidence += vehicle.parked(3.0)
        evidence += vehicle.driving(12.0, 1.5, 12.0, 90.0)
        evidence += vehicle.fix(0.0)
        val records = header.records(evidence)

        // Ingress is bounded by design, so a test that wants every record delivered has to pace
        // itself the way a producer must.
        var index = 0
        while (index < records.size) {
            val end = minOf(index + 128, records.size)
            for (position in index until end) {
                assertTrue("record $position was refused", runtime.offer(records[position]))
            }
            advanceUntilIdle()
            index = end
        }

        assertTrue(runtime.stop())
        advanceUntilIdle()
        assertEquals(NavigationPhase.IDLE, runtime.state.value.phase)
        runtime.close()
        advanceUntilIdle()

        // Everything the engine produced is canonical: the frozen codec parses it, and decoding
        // what it encoded reproduces the same record.
        assertTrue("the engine published nothing", outputs.isNotEmpty())
        for (record in outputs) {
            assertEquals(record, Codec.decodeJson(Codec.encodeJson(record)))
        }

        val calibrations = outputs.mapNotNull { it.event.data as? CalibrationResult }
        val diagnosticCodes = outputs.mapNotNull { it.event.data as? DiagnosticEvent }.map { it.code }
        val valid = assertPresent(
            "no VALID calibration reached the runtime; diagnostics: $diagnosticCodes",
            calibrations.lastOrNull { it.status == CalibrationStatus.VALID },
        )
        val transform = assertPresent("the valid calibration carried no transform",
            valid.q_vehicle_from_device_wxyz)
        assertTrue(isProperRotation(transform, 1e-9))
        assertNotNull("the valid calibration carried no gyro bias", valid.gyro_bias_rad_s)
        assertTransformRecord(transform, PORTRAIT_MOUNT, EXACT_DEGREES)

        // The navigation state must name the calibration in force, and must not claim a position
        // or heading solution that does not exist.
        val navigation = outputs.mapNotNull { it.event.data as? NavigationState }.last()
        assertEquals(NavigationStatus.DEGRADED, navigation.status)
        assertEquals(valid.id, navigation.calibration_id)
        assertNull(navigation.position_enu_m)
        assertNull(navigation.heading_deg)
        assertFalse(navigation.gnss_used_after_initialization)
        assertTrue("the calibration was not announced as unimplemented propagation",
            diagnosticCodes.contains("NAVIGATION_NOT_IMPLEMENTED"))

        assertEquals("a measurement was echoed as output", 0, runtime.state.value.outputRejected)
        assertEquals("ingress overflowed in a paced test", 0, runtime.state.value.ingressDropped)
        assertTrue("no samples were accepted", runtime.state.value.acceptedImu > 0)
    }

    // -------------------------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------------------------

    private class CalibrationRun(
        mode: InitializationMode = InitializationMode.EVALUATION,
        thresholds: MountThresholds = MountThresholds(),
    ) {
        val estimator = MountEstimator(thresholds, mode, "synthetic")
        private val accumulator = StationaryAccumulator()
        val outcomes = mutableListOf<CalibrationOutcome>()
        val diagnostics = mutableListOf<CalibrationDiagnostic>()
        val windows = mutableListOf<StationaryWindow>()

        /**
         * Feed evidence the way the engine does: a window is offered to the estimator before the
         * sample that closed it, because the tilt has to exist before that same sample can join a
         * straight segment.
         */
        fun feed(evidence: List<Evidence>) {
            for (item in evidence) {
                when (item) {
                    is Evidence.Imu -> {
                        val window = accumulator.offer(item.pair)
                        if (window != null) {
                            windows += window
                            estimator.onStationaryWindow(window)
                        }
                        estimator.onImu(item.pair)
                    }

                    is Evidence.Gnss -> estimator.onGnss(item.fix)
                }
            }
            drain()
        }

        /** Deliver a window directly; only for mechanisms a sample stream cannot isolate. */
        fun window(window: StationaryWindow) {
            windows += window
            estimator.onStationaryWindow(window)
            drain()
        }

        /** Mirrors `CalibrationNavigationEngine.stop`, including the accumulator flush. */
        fun finish() {
            accumulator.flush()?.let {
                windows += it
                estimator.onStationaryWindow(it)
            }
            estimator.flush()
            drain()
        }

        private fun drain() {
            outcomes += estimator.drainOutcomes()
            diagnostics += estimator.drainDiagnostics()
        }

        val codes: List<String> get() = diagnostics.map { it.code }
        val valid: CalibrationOutcome? get() = outcomes.lastOrNull { it.status == CalibrationStatus.VALID }
        val last: CalibrationOutcome? get() = outcomes.lastOrNull()
    }

    private sealed interface Evidence {
        data class Imu(val pair: ImuPair) : Evidence
        data class Gnss(val fix: GnssFix) : Evidence
    }

    /**
     * A synthetic vehicle carrying a phone at a known mount.
     *
     * The accelerometer is modelled as a *specific force* sensor: it measures `a - g`, which at
     * rest is the gravity reaction pointing up, and under a forward specific force `a` reads
     * `(a, 0, g)` in vehicle coordinates. Getting that sign wrong is the classic gravity-alignment
     * bug, so it is stated where the evidence is generated rather than assumed.
     */
    private class SyntheticVehicle(
        val qVehicleFromDevice: Quaternion,
        private val gyroBiasRad_S: Vector3 = VECTOR_ZERO,
        private val accelBiasM_S2: Vector3 = VECTOR_ZERO,
        private val accelNoiseM_S2: Double = 0.0,
        private val gyroNoiseRad_S: Double = 0.0,
        seed: Long = 20260930L,
        startNs: Long = 0L,
    ) {
        private val random = kotlin.random.Random(seed)
        private var tNs = startNs
        private var latDeg = 12.9716
        private var lonDeg = 77.5946
        private var speedM_S = 0.0

        val nowNs: Long get() = tNs

        private fun specificForce(fVehicle: Vector3): Vector3 =
            qVehicleFromDevice.conjugate().rotate(fVehicle) + accelBiasM_S2 + noise(accelNoiseM_S2)

        private fun angularRate(wVehicle: Vector3): Vector3 =
            qVehicleFromDevice.conjugate().rotate(wVehicle) + gyroBiasRad_S + noise(gyroNoiseRad_S)

        private fun noise(amplitude: Double): Vector3 = if (amplitude <= 0.0) {
            VECTOR_ZERO
        } else {
            Vector3(
                random.nextDouble(-amplitude, amplitude),
                random.nextDouble(-amplitude, amplitude),
                random.nextDouble(-amplitude, amplitude),
            )
        }

        /** Parked and still: no GNSS at all, which is what a phone placed before a drive sees. */
        fun parked(durationS: Double): List<Evidence> {
            val out = ArrayList<Evidence>()
            repeat((durationS / IMU_DT_S).roundToInt()) {
                tNs += IMU_STEP_NS
                speedM_S = 0.0
                out += Evidence.Imu(
                    ImuPair(tNs, specificForce(Vector3(0.0, 0.0, G)), angularRate(VECTOR_ZERO)),
                )
            }
            return out
        }

        /**
         * Straight driving.
         *
         * Every drive opens with a short pull-away transient, because a car cannot go from parked
         * to a steady specific force instantaneously. That transient is not decoration: it is what
         * tells the accelerometer the drive has begun, and without it a parked period followed by
         * a constant-speed cruise looks like one continuous rest.
         *
         * @param accelerationM_S2 longitudinal specific force; zero is a constant-speed cruise
         * @param reportedHeadingDeg the course the receiver reports, defaulting to the true one
         * @param vibrationM_S2 per-axis road and engine vibration, which a real car always has
         * @param yawRateRad_S rate about vehicle up, to model a drive that is not straight
         * @param accuracyM reported horizontal accuracy
         */
        fun driving(
            durationS: Double,
            accelerationM_S2: Double,
            startSpeedM_S: Double,
            headingDeg: Double,
            reportedHeadingDeg: Double = headingDeg,
            vibrationM_S2: Double = 0.0,
            yawRateRad_S: Double = 0.0,
            accuracyM: Double? = 4.0,
        ): List<Evidence> {
            val out = ArrayList<Evidence>()
            speedM_S = startSpeedM_S
            var elapsedS = 0.0
            var nextFixS = 0.0
            val transientSteps = (PULL_AWAY_S / IMU_DT_S).roundToInt()
            repeat((durationS / IMU_DT_S).roundToInt()) { index ->
                val applied = when {
                    index >= transientSteps -> accelerationM_S2
                    accelerationM_S2 == 0.0 -> PULL_AWAY_M_S2
                    accelerationM_S2 < 0.0 -> -max(abs(accelerationM_S2), PULL_AWAY_M_S2)
                    else -> max(abs(accelerationM_S2), PULL_AWAY_M_S2)
                }
                elapsedS += IMU_DT_S
                tNs += IMU_STEP_NS
                speedM_S += applied * IMU_DT_S
                advance(headingDeg, speedM_S * IMU_DT_S)
                out += Evidence.Imu(
                    ImuPair(
                        tNs,
                        specificForce(Vector3(applied, 0.0, G)) + noise(vibrationM_S2),
                        angularRate(Vector3(0.0, 0.0, yawRateRad_S)),
                    ),
                )
                if (elapsedS >= nextFixS) {
                    nextFixS += FIX_PERIOD_S
                    out += Evidence.Gnss(
                        GnssFix(tNs, latDeg, lonDeg, speedM_S, reportedHeadingDeg, accuracyM),
                    )
                }
            }
            return out
        }

        /** A plausible fix at the current ground position, at a chosen ground speed. */
        fun fix(speedM_S: Double): Evidence.Gnss =
            Evidence.Gnss(GnssFix(tNs, latDeg, lonDeg, speedM_S, 0.0, 4.0))

        /** The phone being turned by hand: a steady rotation about vehicle up while parked. */
        fun handling(durationS: Double, rateRad_S: Double): List<Evidence> {
            val out = ArrayList<Evidence>()
            repeat((durationS / IMU_DT_S).roundToInt()) {
                tNs += IMU_STEP_NS
                out += Evidence.Imu(
                    ImuPair(
                        tNs,
                        specificForce(Vector3(0.0, 0.0, G)),
                        angularRate(Vector3(0.0, 0.0, rateRad_S)),
                    ),
                )
            }
            return out
        }

        /** Flat-earth position update at road scale, the scale a GNSS course is valid at. */
        private fun advance(headingDeg: Double, metres: Double) {
            val headingRad = headingDeg * DEGREES
            latDeg += Math.toDegrees(metres * cos(headingRad) / EARTH_RADIUS_M)
            lonDeg += Math.toDegrees(
                metres * sin(headingRad) / (EARTH_RADIUS_M * cos(Math.toRadians(latDeg))),
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /** Parked, then a straight accelerating drive: the minimum evidence for a full calibration. */
    private fun calibrate(
        vehicle: SyntheticVehicle,
        headingDeg: Double = 90.0,
        reportedHeadingDeg: Double = headingDeg,
    ): CalibrationRun {
        val run = CalibrationRun()
        run.feed(vehicle.parked(3.0))
        run.feed(
            vehicle.driving(12.0, 1.5, 12.0, headingDeg, reportedHeadingDeg = reportedHeadingDeg),
        )
        run.finish()
        return run
    }

    /** One drive's evidence, identical between runs so two modes can be compared on it. */
    private fun oneDrive(): List<Evidence> {
        val vehicle = SyntheticVehicle(PORTRAIT_MOUNT, seed = 4242L)
        return ArrayList<Evidence>().apply {
            addAll(vehicle.parked(3.0))
            addAll(vehicle.driving(12.0, 1.5, 12.0, 90.0))
        }
    }

    private fun assertValid(run: CalibrationRun): CalibrationOutcome {
        val valid = assertPresent(
            "no VALID calibration was published; diagnostics: ${run.codes}", run.valid,
        )
        assertEquals(CalibrationStatus.VALID, valid.status)
        assertNotNull("a VALID calibration carried no transform", valid.qVehicleFromDevice)
        return valid
    }

    /** The transform must be the true mount, and must map the device axes where they belong. */
    private fun assertTransform(
        outcome: CalibrationOutcome,
        truth: Quaternion,
        toleranceDeg: Double,
    ) {
        assertTransformRecord(
            assertPresent("the calibration carried no transform", outcome.qVehicleFromDevice),
            truth,
            toleranceDeg,
        )
    }

    private fun assertTransformRecord(
        transform: Quaternion,
        truth: Quaternion,
        toleranceDeg: Double,
    ) {
        assertTrue("the transform is not a proper rotation", isProperRotation(transform, 1e-9))
        val errorDeg = Math.toDegrees(rotationAngleBetween(truth, transform))
        assertTrue("mount recovered to $errorDeg deg, tolerance $toleranceDeg", errorDeg <= toleranceDeg)
        for (axis in AXES) {
            val expected = truth.rotate(axis)
            val actual = transform.rotate(axis)
            val axisErrorDeg = Math.toDegrees(angleBetweenVectors(expected, actual))
            assertTrue(
                "device axis $axis maps $axisErrorDeg deg away from the vehicle axis",
                axisErrorDeg <= toleranceDeg,
            )
        }
    }

    /** The declared axes must mean what the mount literals claim, so a transpose cannot hide. */
    private fun assertDeviceAxes(
        mount: Quaternion,
        xVehicle: Vector3,
        yVehicle: Vector3,
        zVehicle: Vector3,
    ) {
        assertEquals(0.0, (mount.rotate(DEVICE_FORWARD) - xVehicle).norm(), 1e-12)
        assertEquals(0.0, (mount.rotate(DEVICE_LEFT) - yVehicle).norm(), 1e-12)
        assertEquals(0.0, (mount.rotate(DEVICE_UP) - zVehicle).norm(), 1e-12)
        assertEquals("the declared axes are not right-handed", 0.0,
            ((yVehicle cross zVehicle) - xVehicle).norm(), 1e-12)
    }

    /**
     * A resting window at a given device-frame up direction, as the accumulator would report it.
     *
     * Used to exercise the mechanisms a raw sample stream cannot isolate: a *completed*
     * re-placement is exactly the case the accumulator's rotation gates never see, and the bias
     * solve needs attitudes a single parked session would never produce.
     */
    private fun windowAt(
        upDevice: Vector3,
        startNs: Long,
        durationS: Double = 3.0,
        biasM_S2: Vector3 = VECTOR_ZERO,
        gyroRad_S: Vector3 = VECTOR_ZERO,
    ): StationaryWindow {
        val unit = requireNotNull(upDevice.normalizedOrNull()) { "up direction must be non-zero" }
        val mean = unit * G + biasM_S2
        return StationaryWindow(
            startNs = startNs,
            endNs = startNs + (durationS * 1e9).toLong(),
            samples = (durationS / IMU_DT_S).roundToInt(),
            meanAccelM_S2 = mean,
            meanGyroRad_S = gyroRad_S,
            accelStdM_S2 = 0.01,
            gyroStdRad_S = 0.002,
            gravityMagnitudeM_S2 = mean.norm(),
        )
    }

    /** The same evidence as canonical contract records, the only form the runtime accepts. */
    private fun Header.records(evidence: List<Evidence>): List<Record> {
        var id = 1L
        val out = ArrayList<Record>(evidence.size * 2)
        for (item in evidence) {
            when (item) {
                is Evidence.Imu -> {
                    out += imuRecord(
                        id++, item.pair.tNs, Sensor.ACCELEROMETER,
                        ImuUnit.METRES_PER_SECOND_SQUARED, item.pair.accelM_S2,
                    )
                    out += imuRecord(
                        id++, item.pair.tNs, Sensor.GYROSCOPE,
                        ImuUnit.RADIANS_PER_SECOND, item.pair.gyroRad_S,
                    )
                }

                is Evidence.Gnss -> out += Record(
                    this,
                    Event(
                        (id++).toString(), item.fix.tNs, item.fix.tNs,
                        GnssMeasurement(
                            item.fix.latitudeDeg, item.fix.longitudeDeg, null, null,
                            item.fix.speedM_S, item.fix.bearingDeg, item.fix.horizontalAccuracyM,
                            null, null, "gps", null,
                        ),
                    ),
                )
            }
        }
        return out
    }

    private fun Header.imuRecord(
        id: Long,
        tNs: Long,
        sensor: Sensor,
        unit: ImuUnit,
        xyz: Vector3,
    ): Record = Record(
        this,
        Event(
            id.toString(), tNs, tNs,
            ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE, unit, xyz, SensorAccuracy.HIGH),
        ),
    )

    private companion object {
        const val IMU_DT_S = 0.01
        const val IMU_STEP_NS = 10_000_000L
        const val FIX_PERIOD_S = 1.0

        /** Length and size of the pull-away transient every drive opens with. */
        const val PULL_AWAY_S = 0.5
        const val PULL_AWAY_M_S2 = 2.5
        const val EARTH_RADIUS_M = 6_371_000.0
        const val DEGREES = PI / 180.0

        /** Noiseless geometry must be recovered to numerical precision, not merely closely. */
        const val EXACT_DEGREES = 1e-4
        const val MEASURED_DEGREES = 0.5

        val G = GRAVITY_STANDARD_M_S2
        val VECTOR_ZERO = Vector3(0.0, 0.0, 0.0)
        val DEVICE_FORWARD = Vector3(1.0, 0.0, 0.0)
        val DEVICE_LEFT = Vector3(0.0, 1.0, 0.0)
        val DEVICE_UP = Vector3(0.0, 0.0, 1.0)
        val DEVICE_RIGHT = Vector3(0.0, -1.0, 0.0)
        val VEHICLE_UP = Vector3(0.0, 0.0, 1.0)

        val AXES = listOf(DEVICE_FORWARD, DEVICE_LEFT, DEVICE_UP)

        /** Vertices of a regular tetrahedron: four attitudes that can separate bias from gravity. */
        val TETRAHEDRON = listOf(
            Vector3(0.0, 0.0, 1.0),
            Vector3(0.0, 0.9428, -0.3333),
            Vector3(0.8165, -0.4714, -0.3333),
            Vector3(-0.8165, -0.4714, -0.3333),
        )

        /** Flat on the dash: device X forward, device Y left, device Z up. */
        val IDENTITY_MOUNT = mount(DEVICE_FORWARD, DEVICE_LEFT, DEVICE_UP)

        /**
         * Standing upright with the screen facing the driver: the usual windscreen cradle.
         *
         * Device Z (out of the screen) points backwards, device Y (screen up) points at the roof,
         * and device X (screen right) therefore points at the vehicle's right, which is -Y.
         */
        val PORTRAIT_MOUNT = mount(DEVICE_RIGHT, DEVICE_UP, Vector3(-1.0, 0.0, 0.0))

        /** The same cradle rotated a quarter turn in its own plane. */
        val LANDSCAPE_MOUNT = mount(DEVICE_UP, DEVICE_LEFT, Vector3(-1.0, 0.0, 0.0))

        /** Yawed, pitched back and rolled: a plausible mounting no shortcut would guess. */
        val TILTED_MOUNT = yawPitchRoll(100.0, 8.0, -6.0)

        val MOUNTS = listOf(
            "identity" to IDENTITY_MOUNT,
            "portrait" to PORTRAIT_MOUNT,
            "landscape" to LANDSCAPE_MOUNT,
            "tilted" to TILTED_MOUNT,
        )

        /**
         * `Rz(yaw) * Ry(pitch) * Rx(roll)`, the composition `toYawPitchRollDeg` inverts. Positive
         * yaw is counter-clockwise seen from above, positive pitch lowers vehicle forward, and
         * positive roll lowers vehicle left.
         */
        fun yawPitchRoll(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Quaternion =
            quaternionAboutZ(yawDeg * DEGREES) *
                quaternionFromAxisAngle(DEVICE_LEFT, pitchDeg * DEGREES) *
                quaternionFromAxisAngle(DEVICE_FORWARD, rollDeg * DEGREES)

        /**
         * Build the true mount from the three device axes expressed in vehicle coordinates.
         *
         * The rotation is `q_vehicle_from_device`, so its columns are where the device axes go;
         * transposing would mirror the yaw, which is why [assertDeviceAxes] pins this separately.
         */
        fun mount(xVehicle: Vector3, yVehicle: Vector3, zVehicle: Vector3): Quaternion =
            quaternionFromMatrix(
                doubleArrayOf(
                    xVehicle.x, yVehicle.x, zVehicle.x,
                    xVehicle.y, yVehicle.y, zVehicle.y,
                    xVehicle.z, yVehicle.z, zVehicle.z,
                ),
            )

        /**
         * Quaternion of a row-major proper rotation matrix (Shepperd's method).
         *
         * Written independently of the production `toRotationMatrix`, so the round trip through
         * these helpers tests that code instead of agreeing with it.
         */
        fun quaternionFromMatrix(m: DoubleArray): Quaternion {
            val trace = m[0] + m[4] + m[8]
            val raw = when {
                trace > 0.0 -> {
                    val s = sqrt(trace + 1.0) * 2.0
                    Quaternion(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
                }

                m[0] > m[4] && m[0] > m[8] -> {
                    val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2.0
                    Quaternion((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
                }

                m[4] > m[8] -> {
                    val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2.0
                    Quaternion((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
                }

                else -> {
                    val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2.0
                    Quaternion((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
                }
            }
            return requireNotNull(raw.normalizedOrNull()) { "degenerate mount matrix" }
        }

        /** Unit quaternion, or null when the input is degenerate; an assertion input check. */
        fun Quaternion.normalizedOrNull(): Quaternion? {
            val n = sqrt(w * w + x * x + y * y + z * z)
            if (!n.isFinite() || n < 1e-12) return null
            return Quaternion(w / n, x / n, y / n, z / n)
        }
    }
}

/** JUnit's `assertNotNull` returns void, so tests that need the value go through this. */
private fun <T> assertPresent(message: String, value: T?): T {
    assertNotNull(message, value)
    return value!!
}
