package com.intelligentdeadreckoning.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeProfilesTest {
    @Test
    fun theDefaultProfileIsTheShippedEngineSettingsAndTheExternalImuProfileAddsTwoThings() {
        val default = EdgeProfiles.byName("default")
        assertFalse(default.speedCorrectionEnabled)
        assertNull(default.gnssVelocitySigmaM_S)
        val external = EdgeProfiles.byName("external-imu")
        assertTrue(external.speedCorrectionEnabled)
        assertEquals(0.2, external.gnssVelocitySigmaM_S!!, 0.0)
        assertEquals(default.copy(speedCorrectionEnabled = true, gnssVelocitySigmaM_S = 0.2), external)
    }

    @Test
    fun anUnknownProfileNameIsRefused() {
        try {
            EdgeProfiles.byName("phone")
            org.junit.Assert.fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun theBenchmarkConfigCarriesTheProfileToTheRuntime() {
        assertEquals(EdgeProfiles.default, BenchmarkHarness.Config().fusion)
        assertEquals(EdgeProfiles.externalVehicleImu, BenchmarkHarness.Config(fusion = EdgeProfiles.byName("external-imu")).fusion)
    }
}
