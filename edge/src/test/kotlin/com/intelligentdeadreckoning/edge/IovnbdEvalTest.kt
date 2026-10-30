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
    fun theAppArmsAreExactlyTheAppFusionConfigurationsWithAndWithoutTheMapSwitch() {
        assertEquals(AppFusion.config(experimentalMapMatching = false), IovnbdEval.Arm.APP.config())
        assertEquals(AppFusion.config(experimentalMapMatching = true), IovnbdEval.Arm.APP_MAP.config())
        assertTrue(IovnbdEval.Arm.APP_MAP.config().mapCrossTrackEnabled)
        assertEquals(AppFusion.MAP_CROSS_TRACK_MIN_CONFIDENCE, IovnbdEval.Arm.APP_MAP.config().mapCrossTrackMinConfidence, 0.0)
    }
}
