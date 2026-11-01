package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.app.navigation.AppFusion
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class IovnbdEvalTest {
    private val header = Header("iovnbd-test", Source.REPLAY_REAL, ENGINE_OUTPUT_CONTRACT_VERSION)

    private fun imuAt(tNs: Long) = Record(header, Event(tNs.toString(), tNs, tNs, ImuMeasurement(
        Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE, ImuUnit.METRES_PER_SECOND_SQUARED,
        Vector3(0.0, 0.0, 9.8), SensorAccuracy.UNKNOWN,
    )))

    @Test
    fun outagesAreHalfOpenOnMeasurementTime() {
        val outages = listOf(IovnbdEval.Outage(1_000L, 2_000L))
        assertFalse(IovnbdEval.withheld(999L, outages))
        assertTrue(IovnbdEval.withheld(1_000L, outages))
        assertTrue(IovnbdEval.withheld(1_999L, outages))
        assertFalse(IovnbdEval.withheld(2_000L, outages))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyOutageIsRejected() {
        IovnbdEval.Outage(5L, 5L)
    }

    @Test
    fun batchCalibrationIsReleasedOnceAndNotBeforeItsTime() {
        val q = Quaternion(Math.cos(0.3), 0.0, 0.0, Math.sin(0.3))
        val engine = IovnbdEval.ReleasedCalibration(IovnbdEval.BatchCalibration(
            "batch-test", q, Vector3(0.0, 0.0, 0.002), availableNs = 5_000L,
        ))
        val pending = Record(header, Event("0", 0L, 0L, CalibrationResult(
            "none", CalibrationStatus.PENDING, null, null, null, null,
        )))
        engine.initialize(EngineSession(header, "test", 0L), pending, InitializationMode.DEPLOYABLE)
        engine.acceptImu(imuAt(4_999L))
        assertEquals(0, engine.drain().count())
        engine.acceptImu(imuAt(5_000L))
        val released = engine.drain().toList()
        assertEquals(1, released.size)
        val result = released.single().event.data as CalibrationResult
        assertEquals(CalibrationStatus.VALID, result.status)
        assertEquals(q, result.q_vehicle_from_device_wxyz)
        assertEquals(5_000L, released.single().event.t_ns)
        engine.acceptImu(imuAt(6_000L))
        assertEquals(0, engine.drain().count())
    }

    @Test
    fun jobsFileParsesArmsOutagesAndOptionalCalibration() {
        val file = Files.createTempFile("iovnbd-jobs", ".json")
        try {
            Files.writeString(file, """
                {"jobs":[
                  {"session":"a","output":"a.csv","arm":"ekf_nhc","outages_ns":[["10","20"]],
                   "calibration":{"id":"c","q_vehicle_from_device_wxyz":[1,0,0,0],
                                  "gyro_bias_rad_s":[0,0,0.001],"available_ns":"300"}},
                  {"session":"b","output":"b.csv","arm":"app","outages_ns":[],"calibration":null}
                ]}
            """.trimIndent())
            val jobs = IovnbdEval.readJobs(file)
            assertEquals(IovnbdEval.Arm.EKF_NHC, jobs[0].arm)
            assertEquals(listOf(IovnbdEval.Outage(10L, 20L)), jobs[0].outagesNs)
            assertEquals(300L, jobs[0].calibration!!.availableNs)
            assertEquals(IovnbdEval.Arm.APP, jobs[1].arm)
            assertNull(jobs[1].calibration)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun heldOutArmUsesTheV3CorrectionAndReadsItsOwnWeightsFileFromTheJob() {
        assertTrue(IovnbdEval.Arm.EKF_NHC_V3_HELDOUT.config().speedCorrectionEnabled)
        val file = Files.createTempFile("iovnbd-jobs", ".json")
        try {
            Files.writeString(file, """
                {"jobs":[{"session":"a","output":"a.csv","arm":"ekf_nhc_v3_heldout","outages_ns":[],
                          "speed_model":"weights/speed_v3_without_a.json","calibration":null},
                         {"session":"b","output":"b.csv","arm":"ekf_nhc_v3","outages_ns":[],"calibration":null}]}
            """.trimIndent())
            val jobs = IovnbdEval.readJobs(file)
            assertEquals("speed_v3_without_a.json", jobs[0].speedModel!!.fileName.toString())
            assertNull(jobs[1].speedModel)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun tightVelocityArmsChangeOnlyTheVelocityWeighting() {
        assertEquals(null, IovnbdEval.Arm.EKF_NHC.config().gnssVelocitySigmaM_S)
        assertEquals(0.2, IovnbdEval.Arm.EKF_NHC_VEL.config().gnssVelocitySigmaM_S!!, 0.0)
        assertFalse(IovnbdEval.Arm.EKF_NHC_VEL.config().speedCorrectionEnabled)
        val both = IovnbdEval.Arm.EKF_NHC_V3_HELDOUT_VEL.config()
        assertTrue(both.speedCorrectionEnabled)
        assertEquals(0.2, both.gnssVelocitySigmaM_S!!, 0.0)
    }

    @Test
    fun armsDifferOnlyWhereIntended() {
        assertFalse(IovnbdEval.Arm.INS.config().motionConstraintsEnabled)
        assertTrue(IovnbdEval.Arm.EKF_NHC.config().motionConstraintsEnabled)
        assertFalse(IovnbdEval.Arm.EKF_NHC.config().aiSpeedEstimationEnabled)
        assertTrue(IovnbdEval.Arm.EKF_NHC_AI.config().aiSpeedEstimationEnabled)
        assertTrue(IovnbdEval.Arm.APP.config().mountSlipDetectionEnabled)
        assertTrue(IovnbdEval.Arm.EKF_NHC_V3.config().speedCorrectionEnabled)
        assertFalse(IovnbdEval.Arm.EKF_NHC_V3.config().aiSpeedEstimationEnabled)
    }

    @Test
    fun mapArmsDifferFromTheNoMapArmOnlyInTheMapSettings() {
        val base = IovnbdEval.Arm.EKF_NHC_V3_HELDOUT_VEL_NHCOUT.config()
        val heading = IovnbdEval.Arm.EKF_NHC_V3_HELDOUT_VEL_NHCOUT_MAP.config()
        val cross = IovnbdEval.Arm.EKF_NHC_V3_HELDOUT_VEL_NHCOUT_MAPX.config()
        assertFalse(base.hmmMapMatchingEnabled)
        assertFalse(base.mapCrossTrackEnabled)
        assertEquals(base.copy(hmmMapMatchingEnabled = true, mapHeadingFeedbackEnabled = true), heading)
        assertEquals(base.copy(hmmMapMatchingEnabled = true, mapHeadingFeedbackEnabled = true, mapCrossTrackEnabled = true), cross)
        assertEquals(9.0, IovnbdEval.Arm.MAPX_VAR9.config().mapCrossTrackVarianceM2, 0.0)
        assertEquals(cross.copy(mapCrossTrackVarianceM2 = 4.0), IovnbdEval.Arm.MAPX_VAR4.config())
        assertEquals(cross.copy(mapCrossTrackMinConfidence = 0.6), IovnbdEval.Arm.MAPX_CONF06.config())
    }

    @Test
    fun theJobsFileCarriesTheRoadGraphDirectoryForMapArms() {
        val file = Files.createTempFile("jobs", ".json")
        try {
            Files.writeString(file, """{"jobs":[{"session":"s","output":"o.csv","arm":"mapx_var9","outages_ns":[["1","2"]],"road_graph":"g"}]}""")
            val job = IovnbdEval.readJobs(file).single()
            assertEquals(IovnbdEval.Arm.MAPX_VAR9, job.arm)
            assertEquals("g", job.roadGraph.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun theJobProfileDefaultsToIovnbdAndAPhoneDriveCanAskForTheAppsOwnSettings() {
        val file = Files.createTempFile("jobs", ".json")
        try {
            Files.writeString(file, """{"jobs":[{"session":"s","output":"a.csv","arm":"app","outages_ns":[]},
                {"session":"s","output":"b.csv","arm":"app","outages_ns":[],"profile":"phone"}]}""")
            val jobs = IovnbdEval.readJobs(file)
            assertEquals(IovnbdEval.PROFILE_IOVNBD, jobs[0].profile)
            assertEquals(IovnbdEval.PROFILE_PHONE, jobs[1].profile)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun theCalibratorThresholdsComeFromTheProfileAndNamedOverridesApplyOnTop() {
        val (phoneMount, phoneStationary) = IovnbdEval.calibratorThresholds(IovnbdEval.PROFILE_PHONE, emptyMap())
        assertEquals(com.intelligentdeadreckoning.app.calibration.MountThresholds(), phoneMount)
        assertEquals(com.intelligentdeadreckoning.app.calibration.StationaryThresholds(), phoneStationary)
        val (iovMount, iovStationary) = IovnbdEval.calibratorThresholds(IovnbdEval.PROFILE_IOVNBD, emptyMap())
        assertEquals(IovnbdEval.IOVNBD_MOUNT, iovMount)
        assertEquals(IovnbdEval.IOVNBD_STATIONARY, iovStationary)
        val (mount, stationary) = IovnbdEval.calibratorThresholds(IovnbdEval.PROFILE_PHONE,
            mapOf("stationary_accel_std" to 0.3, "gravity_spread" to 0.1, "min_gnss_fixes" to 2.0))
        assertEquals(0.3, stationary.maxAccelStdM_S2, 0.0)
        assertEquals(phoneStationary.maxGyroStdRad_S, stationary.maxGyroStdRad_S, 0.0)
        assertEquals(0.1, mount.maxGravitySpreadM_S2, 0.0)
        assertEquals(2, mount.minGnssFixes)
        assertEquals(phoneMount.minExcitationM_S2, mount.minExcitationM_S2, 0.0)
    }

    @Test
    fun anUnknownCalibratorThresholdIsRejectedAndTheJobsFileCarriesThem() {
        val error = runCatching { IovnbdEval.calibratorThresholds(IovnbdEval.PROFILE_PHONE, mapOf("gravity_sprad" to 0.1)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("gravity_sprad"))
        val file = Files.createTempFile("jobs", ".json")
        try {
            Files.writeString(file, """{"jobs":[{"session":"s","output":"o.csv","arm":"app","outages_ns":[],"profile":"phone",
                "calibrator":{"excitation":0.15,"stationary_accel_std":0.3}}]}""")
            assertEquals(mapOf("excitation" to 0.15, "stationary_accel_std" to 0.3), IovnbdEval.readJobs(file).single().calibrator)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun anUnknownJobProfileIsRejectedBeforeAnythingRuns() {
        val dir = Files.createTempDirectory("profile")
        try {
            Files.writeString(dir.resolve("measurements.jsonl"), "")
            val job = IovnbdEval.Job(dir, dir.resolve("o.csv"), IovnbdEval.Arm.APP, emptyList(), null, profile = "tablet")
            val error = runCatching { IovnbdEval.run(job) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException && error.message!!.contains("unknown profile"))
        } finally {
            Files.deleteIfExists(dir.resolve("measurements.jsonl"))
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun theGateSmoothingArmsChangeOnlyTheGateAndOrTheOutageSwitch() {
        val plain = IovnbdEval.Arm.EKF_NHC.config()
        assertEquals(0.0, plain.nhcGateSmoothingS, 0.0)
        assertEquals(plain.copy(nhcActiveDuringOutage = true), IovnbdEval.Arm.EKF_NHC_NHCOUT.config())
        assertEquals(plain.copy(nhcGateSmoothingS = 1.0), IovnbdEval.Arm.EKF_NHC_SMOOTH1.config())
        assertEquals(plain.copy(nhcGateSmoothingS = 1.0, nhcActiveDuringOutage = true), IovnbdEval.Arm.EKF_NHC_SMOOTH1_NHCOUT.config())
    }

    @Test
    fun eachAblationArmDiffersFromTheShippedConfigurationInExactlyOneSwitch() {
        val shipped = IovnbdEval.Arm.APP.config()
        assertEquals(shipped.copy(aiSpeedEstimationEnabled = false), IovnbdEval.Arm.APP_NO_AI.config())
        assertEquals(shipped.copy(adaptiveNoiseScalingEnabled = false), IovnbdEval.Arm.APP_NO_ADAPTIVE_NOISE.config())
        assertEquals(shipped.copy(mountSlipDetectionEnabled = false), IovnbdEval.Arm.APP_NO_MOUNT_SLIP.config())
        assertEquals(shipped.copy(adaptiveDecimationEnabled = false), IovnbdEval.Arm.APP_NO_DECIMATION.config())
        listOf(IovnbdEval.Arm.APP_NO_AI, IovnbdEval.Arm.APP_NO_ADAPTIVE_NOISE, IovnbdEval.Arm.APP_NO_MOUNT_SLIP, IovnbdEval.Arm.APP_NO_DECIMATION)
            .forEach { assertTrue(it.config() != shipped) }
    }

    @Test
    fun theAppArmsAreExactlyTheAppFusionConfigurationsWithAndWithoutTheMapSwitch() {
        // `app` and `app_map` are frozen as the previous shipped configuration; `app_current` follows AppFusion.
        assertEquals(AppFusion.config(experimentalMapMatching = false), IovnbdEval.Arm.APP_CURRENT.config())
        assertEquals(IovnbdEval.Arm.APP_CURRENT.config(), IovnbdEval.Arm.APP_NO_SLIP_NO_AI.config().copy(
            adaptiveNoiseScalingEnabled = true, mapHeadingFeedbackEnabled = true, adaptiveDecimationEnabled = true))
        assertEquals(AppFusion.config(experimentalMapMatching = true), IovnbdEval.Arm.APP_CURRENT.config().copy(
            hmmMapMatchingEnabled = true, mapCrossTrackEnabled = true, mapCrossTrackMinConfidence = AppFusion.MAP_CROSS_TRACK_MIN_CONFIDENCE))
        assertFalse(AppFusion.config(experimentalMapMatching = false).aiSpeedEstimationEnabled)
        assertFalse(AppFusion.config(experimentalMapMatching = false).mountSlipDetectionEnabled)
        assertTrue(IovnbdEval.Arm.APP.config().aiSpeedEstimationEnabled && IovnbdEval.Arm.APP.config().mountSlipDetectionEnabled)
        assertTrue(IovnbdEval.Arm.APP_MAP.config().mapCrossTrackEnabled)
        assertEquals(AppFusion.MAP_CROSS_TRACK_MIN_CONFIDENCE, IovnbdEval.Arm.APP_MAP.config().mapCrossTrackMinConfidence, 0.0)
    }
}
