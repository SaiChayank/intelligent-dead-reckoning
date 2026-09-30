package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.acquisition.AcquisitionProcessor
import com.intelligentdeadreckoning.app.acquisition.GnssFixEvidence
import com.intelligentdeadreckoning.app.acquisition.GnssInnovationTrust
import com.intelligentdeadreckoning.app.acquisition.GnssProviderProfile
import com.intelligentdeadreckoning.app.acquisition.GnssQuality
import com.intelligentdeadreckoning.app.acquisition.GnssQualityInput
import com.intelligentdeadreckoning.app.acquisition.GnssQualityManager
import com.intelligentdeadreckoning.app.acquisition.GnssQualityPolicy
import com.intelligentdeadreckoning.app.acquisition.GnssReason
import com.intelligentdeadreckoning.app.acquisition.GnssTransitionCode
import com.intelligentdeadreckoning.app.acquisition.LocationAccess
import com.intelligentdeadreckoning.app.acquisition.Sample
import com.intelligentdeadreckoning.app.acquisition.gnssPayload
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.GnssState
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GNSS quality state machine. Cases required by the stage brief: no provider, first
 * fix, healthy sequence, stale sequence, poor accuracy, cached/pre-session fix, recovery
 * and null optional fields, plus the determinism and threshold-provenance guarantees the
 * policy claims.
 */
class GnssQualityTest {
    private val policy = GnssQualityPolicy()
    private val origin = 1_000_000_000L
    private val second = 1_000_000_000L

    private fun fix(
        tNs: Long = origin,
        receivedNs: Long = tNs,
        horizontalAccuracyM: Double? = 5.0,
        verticalAccuracyM: Double? = 3.0,
        satellitesUsed: Int? = 12,
        hasSpeed: Boolean = true,
        hasBearing: Boolean = false,
    ) = GnssFixEvidence(
        tNs, receivedNs, horizontalAccuracyM, verticalAccuracyM, satellitesUsed, hasSpeed, hasBearing,
    )

    private fun input(
        nowNs: Long = origin,
        permission: LocationAccess = LocationAccess.PRECISE,
        providerEnabled: Boolean = true,
        provider: String? = "gps",
        lastFix: GnssFixEvidence? = null,
        timestampsValid: Boolean = true,
        innovation: GnssInnovationTrust? = null,
        sessionOriginNs: Long = origin,
    ) = GnssQualityInput(
        nowNs, sessionOriginNs, permission, providerEnabled, provider, lastFix, timestampsValid, innovation,
    )

    // ---------------------------------------------------------------- no provider

    @Test fun noProviderIsUnavailableAndUnknownProviderIsNeverTrusted() {
        val disabled = GnssQuality.evaluate(input(providerEnabled = false), policy)
        assertEquals(GnssState.UNAVAILABLE, disabled.state)
        assertEquals(listOf("PROVIDER_DISABLED"), disabled.reasons)
        assertTrue(disabled.outage)

        val unknown = GnssQuality.evaluate(input(provider = "passive", lastFix = fix()), policy)
        assertEquals(GnssState.UNAVAILABLE, unknown.state)
        assertEquals("PROVIDER_UNKNOWN", unknown.reasons.first())
        assertNull(policy.staleAfterNs("passive"))
    }

    @Test fun grantedAccessWithNoChannelYetIsAcquiringNotUnknownProvider() {
        val decision = GnssQuality.evaluate(input(provider = null), policy)
        assertEquals(GnssState.ACQUIRING, decision.state)
        assertEquals(listOf("NO_FIX"), decision.reasons)
        assertNull(decision.fixAgeS)
        assertNull(decision.satellitesUsed)
        assertNull(decision.provider)
    }

    // ---------------------------------------------------------------- first fix

    @Test fun firstFixIsAcquiringUntilAFixArrivesAndThenIsEvaluated() {
        val before = GnssQuality.evaluate(input(), policy)
        assertEquals(GnssState.ACQUIRING, before.state)
        assertNull(before.fixAgeS)

        val after = GnssQuality.evaluate(input(lastFix = fix()), policy)
        assertEquals(GnssState.GOOD, after.state)
        assertEquals(0.0, after.fixAgeS!!, 0.0)
        assertEquals(12L, after.satellitesUsed)
        assertEquals(listOf("BEARING_UNAVAILABLE"), after.reasons)
    }

    // ---------------------------------------------------------------- healthy sequence

    @Test fun healthySequenceStaysGoodWithoutPublishingSpuriousTransitions() {
        val manager = GnssQualityManager(policy)
        val states = (0 until 5).map { step ->
            val t = origin + step * second
            val decision = manager.update(
                input(nowNs = t, lastFix = fix(tNs = t, receivedNs = t + 31_000_000L)),
            )
            assertEquals(GnssState.GOOD, decision.state)
            decision.state
        }
        assertEquals(List(5) { GnssState.GOOD }, states)
        assertEquals(0L, manager.transitions)
        assertNull(manager.lastTransition)
    }

    @Test fun goodIsNotAnAccuracyClaimAndReportsWhatIsMissing() {
        val decision = GnssQuality.evaluate(
            input(lastFix = fix(hasSpeed = false, hasBearing = false, verticalAccuracyM = null)),
            policy,
        )
        assertEquals(GnssState.GOOD, decision.state)
        assertEquals(
            listOf("SPEED_UNAVAILABLE", "BEARING_UNAVAILABLE", "VERTICAL_ACCURACY_UNKNOWN"),
            decision.reasons,
        )
    }

    // ---------------------------------------------------------------- stale sequence

    @Test fun staleSequenceUsesTheMeasuredCadenceOfTheChannel() {
        val fresh = GnssQuality.evaluate(input(nowNs = origin, lastFix = fix()), policy)
        assertEquals(GnssState.GOOD, fresh.state)

        val justInside = GnssQuality.evaluate(input(nowNs = origin + 2 * second, lastFix = fix()), policy)
        assertEquals(GnssState.GOOD, justInside.state)
        assertEquals(2.0, justInside.fixAgeS!!, 1e-9)

        val stale = GnssQuality.evaluate(input(nowNs = origin + 2 * second + 1, lastFix = fix()), policy)
        assertEquals(GnssState.STALE, stale.state)
        assertEquals("STALE_FIX", stale.reasons.first())
        assertTrue(stale.outage)

        val muchLater = GnssQuality.evaluate(input(nowNs = origin + 600 * second, lastFix = fix()), policy)
        assertEquals(GnssState.STALE, muchLater.state)
        assertEquals(600.0, muchLater.fixAgeS!!, 1e-9)
        assertEquals(12L, muchLater.satellitesUsed)
    }

    @Test fun oneSharedBoundWouldHaveMisreportedTheSlowChannel() {
        // The corpus's network channel is 0.05 Hz. One 5 s bound calls it stale while it is
        // healthy; the per-provider bound derived from its measured cadence does not.
        val slowButHealthy = GnssQuality.evaluate(
            input(nowNs = origin + 5 * second, provider = "network", lastFix = fix()),
            policy,
        )
        assertEquals(GnssState.GOOD, slowButHealthy.state)
        assertEquals(5.0, slowButHealthy.fixAgeS!!, 1e-9)

        val sameAgeOnGps = GnssQuality.evaluate(
            input(nowNs = origin + 5 * second, provider = "gps", lastFix = fix()),
            policy,
        )
        assertEquals(GnssState.STALE, sameAgeOnGps.state)

        val genuinelyStale = GnssQuality.evaluate(
            input(nowNs = origin + 41 * second, provider = "network", lastFix = fix()),
            policy,
        )
        assertEquals(GnssState.STALE, genuinelyStale.state)
    }

    // ---------------------------------------------------------------- poor accuracy

    @Test fun poorAccuracyIsDecisiveAndDegradedIsNotAnOutage() {
        val poor = GnssQuality.evaluate(input(lastFix = fix(horizontalAccuracyM = 100.0)), policy)
        assertEquals(GnssState.DEGRADED, poor.state)
        assertEquals("HORIZONTAL_ACCURACY_POOR", poor.reasons.first())
        assertFalse(poor.outage)
    }

    @Test fun accuracyBoundIsInclusiveAndUnknownAccuracyIsNotPoor() {
        assertEquals(
            GnssState.GOOD,
            GnssQuality.evaluate(input(lastFix = fix(horizontalAccuracyM = 15.0)), policy).state,
        )
        assertEquals(
            GnssState.DEGRADED,
            GnssQuality.evaluate(input(lastFix = fix(horizontalAccuracyM = 15.1)), policy).state,
        )
        val unknown = GnssQuality.evaluate(input(lastFix = fix(horizontalAccuracyM = null)), policy)
        assertEquals(GnssState.DEGRADED, unknown.state)
        assertEquals("HORIZONTAL_ACCURACY_UNKNOWN", unknown.reasons.first())
    }

    @Test fun weakGeometryIsDecisiveOnlyForProvidersThatReportIt() {
        val low = GnssQuality.evaluate(input(lastFix = fix(satellitesUsed = 3)), policy)
        assertEquals(GnssState.DEGRADED, low.state)
        assertEquals("SATELLITES_LOW", low.reasons.first())

        assertEquals(
            GnssState.GOOD,
            GnssQuality.evaluate(input(lastFix = fix(satellitesUsed = 4)), policy).state,
        )

        val unknown = GnssQuality.evaluate(input(lastFix = fix(satellitesUsed = null)), policy)
        assertEquals(GnssState.DEGRADED, unknown.state)
        assertEquals("SATELLITES_UNKNOWN", unknown.reasons.first())

        // The network provider structurally never reports satellites; requiring them would
        // reject every network fix for a field Android never supplies there.
        val network = GnssQuality.evaluate(
            input(provider = "network", lastFix = fix(satellitesUsed = null)),
            policy,
        )
        assertEquals(GnssState.GOOD, network.state)
        assertNull(network.satellitesUsed)
    }

    @Test fun approximateAccessCapsTrustWithoutHidingAFreshFix() {
        val decision = GnssQuality.evaluate(
            input(permission = LocationAccess.APPROXIMATE, lastFix = fix()),
            policy,
        )
        assertEquals(GnssState.DEGRADED, decision.state)
        assertEquals("APPROXIMATE_LOCATION", decision.reasons.first())
        assertEquals(0.0, decision.fixAgeS!!, 0.0)
    }

    @Test fun denialRevocationAndNonRequestAreDistinguishedNotLumpedTogether() {
        assertEquals(
            GnssState.DENIED,
            GnssQuality.evaluate(input(permission = LocationAccess.DENIED, lastFix = fix()), policy).state,
        )
        assertEquals(
            "PERMISSION_DENIED",
            GnssQuality.evaluate(input(permission = LocationAccess.DENIED), policy).reasons.first(),
        )
        assertEquals(
            "PERMISSION_REVOKED",
            GnssQuality.evaluate(input(permission = LocationAccess.REVOKED), policy).reasons.first(),
        )
        assertEquals(
            "LOCATION_NOT_REQUESTED",
            GnssQuality.evaluate(input(permission = LocationAccess.NOT_REQUESTED), policy).reasons.first(),
        )
        // Permission outranks everything else, including a perfect fix.
        val deniedWithGoodFix = GnssQuality.evaluate(
            input(permission = LocationAccess.DENIED, lastFix = fix()),
            policy,
        )
        assertEquals(GnssState.DENIED, deniedWithGoodFix.state)
        assertNull(deniedWithGoodFix.fixAgeS)
    }

    // ---------------------------------------------------------------- cached / pre-session

    @Test fun cachedPreSessionFixIsNamedRatherThanSampledAsNewEvidence() {
        val cached = GnssQuality.evaluate(
            input(lastFix = fix(tNs = origin - 30 * second, receivedNs = origin)),
            policy,
        )
        assertEquals(GnssState.DEGRADED, cached.state)
        assertEquals("PRE_SESSION_FIX", cached.reasons.first())
        assertEquals(30.0, cached.fixAgeS!!, 1e-9)
    }

    @Test fun timestampsThatDoNotHoldUpAreRejectedNotAged() {
        val future = GnssQuality.evaluate(input(nowNs = origin, lastFix = fix(tNs = origin + second)), policy)
        assertEquals(GnssState.DEGRADED, future.state)
        assertEquals("TIMESTAMP_INVALID", future.reasons.first())
        assertNull(future.fixAgeS)

        val receivedBeforeMeasured = GnssQuality.evaluate(
            input(lastFix = fix(tNs = origin, receivedNs = origin - 1)),
            policy,
        )
        assertEquals("TIMESTAMP_INVALID", receivedBeforeMeasured.reasons.first())

        val callerRejected = GnssQuality.evaluate(input(lastFix = fix(), timestampsValid = false), policy)
        assertEquals(GnssState.DEGRADED, callerRejected.state)
        assertEquals("TIMESTAMP_INVALID", callerRejected.reasons.first())
    }

    // ---------------------------------------------------------------- recovery

    @Test fun recoveryIsPublishedOnceWithTheRightCode() {
        val manager = GnssQualityManager(policy)
        assertEquals(GnssState.GOOD, manager.update(input(lastFix = fix())).state)
        assertEquals(0L, manager.transitions)

        val outage = manager.update(input(nowNs = origin + 10 * second, lastFix = fix()))
        assertEquals(GnssState.STALE, outage.state)
        assertEquals(GnssTransitionCode.GNSS_OUTAGE, manager.lastTransition!!.code)
        assertTrue(manager.lastTransition!!.outageStarted)
        assertFalse(manager.lastTransition!!.outageEnded)
        assertEquals(1L, manager.transitions)

        assertEquals(
            GnssState.STALE,
            manager.update(input(nowNs = origin + 11 * second, lastFix = fix())).state,
        )
        assertNull(manager.lastTransition)
        assertEquals(1L, manager.transitions)

        val recovered = manager.update(input(nowNs = origin + 12 * second, lastFix = fix(tNs = origin + 12 * second)))
        assertEquals(GnssState.GOOD, recovered.state)
        assertEquals(GnssTransitionCode.GNSS_RECOVERED, manager.lastTransition!!.code)
        assertTrue(manager.lastTransition!!.outageEnded)
        assertEquals(2L, manager.transitions)

        val degraded = manager.update(
            input(nowNs = origin + 13 * second, lastFix = fix(tNs = origin + 13 * second, horizontalAccuracyM = 90.0)),
        )
        assertEquals(GnssState.DEGRADED, degraded.state)
        assertEquals(GnssTransitionCode.GNSS_QUALITY_CHANGED, manager.lastTransition!!.code)
        assertFalse(manager.lastTransition!!.outageStarted)
        assertFalse(manager.lastTransition!!.outageEnded)
    }

    @Test fun resetForgetsThePreviousStateWithoutChangingTheDecision() {
        val manager = GnssQualityManager(policy)
        manager.update(input(lastFix = fix(horizontalAccuracyM = 90.0)))
        manager.update(input(lastFix = fix()))
        assertNotNull(manager.lastTransition)
        manager.reset()
        assertNull(manager.decision)
        assertNull(manager.lastTransition)
        assertEquals(GnssState.GOOD, manager.update(input(lastFix = fix())).state)
        assertNull(manager.lastTransition)
    }

    // ---------------------------------------------------------------- null optional fields

    @Test fun nullOptionalFieldsStayNullAndAreNamedRatherThanFilled() {
        // Age comes from the measurement timestamp against `now`, never from receipt time,
        // so the two are set apart deliberately here.
        val decision = GnssQuality.evaluate(
            input(
                nowNs = origin + 69_000_000L,
                lastFix = GnssFixEvidence(
                    tNs = origin,
                    receivedNs = origin + 69_000_000L,
                    horizontalAccuracyM = null,
                    verticalAccuracyM = null,
                    satellitesUsed = null,
                    hasSpeed = false,
                    hasBearing = false,
                ),
            ),
            policy,
        )
        assertEquals(GnssState.DEGRADED, decision.state)
        assertEquals("HORIZONTAL_ACCURACY_UNKNOWN", decision.reasons.first())
        assertTrue("SATELLITES_UNKNOWN" in decision.reasons)
        assertTrue("SPEED_UNAVAILABLE" in decision.reasons)
        assertTrue("BEARING_UNAVAILABLE" in decision.reasons)
        assertTrue("VERTICAL_ACCURACY_UNKNOWN" in decision.reasons)
        assertNull(decision.satellitesUsed)
        assertEquals(0.069, decision.fixAgeS!!, 1e-9)

        val contract = decision.toContract()
        assertEquals(GnssState.DEGRADED, contract.state)
        assertNull(contract.satellites_used)
        assertEquals(decision.reasons, contract.reasons)
        assertEquals(decision.fixAgeS!!, contract.fix_age_s!!, 0.0)
    }

    // ---------------------------------------------------------------- provenance and determinism

    @Test fun policyThresholdsAreTheDocumentedValidatedValues() {
        assertEquals(
            mapOf(
                "gps" to GnssProviderProfile(1_000_000_000L, satellitesExpected = true),
                "network" to GnssProviderProfile(20_100_000_000L, satellitesExpected = false),
            ),
            policy.providers,
        )
        assertEquals(2, policy.staleMissedIntervals)
        assertEquals(2_000_000_000L, policy.staleAfterNs("gps"))
        assertEquals(40_200_000_000L, policy.staleAfterNs("network"))
        assertEquals(15.0, policy.maximumHorizontalAccuracyM, 0.0)
        assertEquals(15.0, policy.maximumVerticalAccuracyM, 0.0)
        assertEquals(4, policy.minimumSatellites)
    }

    @Test fun identicalInputsProduceIdenticalDecisionsAndStableReasonOrder() {
        val sample = input(
            nowNs = origin + 7 * second,
            lastFix = fix(tNs = origin, hasSpeed = false),
        )
        assertEquals(GnssQuality.evaluate(sample, policy), GnssQuality.evaluate(sample, policy))
        val decision = GnssQuality.evaluate(sample, policy)
        assertEquals(GnssState.STALE, decision.state)
        assertEquals(listOf("STALE_FIX", "SPEED_UNAVAILABLE", "BEARING_UNAVAILABLE"), decision.reasons)
    }

    @Test fun reasonAndTransitionCodesAreUniqueAndDecisivenessIsDeclared() {
        val codes = GnssReason.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it == it.uppercase() && '_' in it })
        assertEquals(GnssReason.entries.size, GnssReason.entries.count { it.decisive } + 4)
        val transitionCodes = GnssTransitionCode.entries.map { it.code }
        assertEquals(transitionCodes.size, transitionCodes.toSet().size)
    }

    @Test fun innovationCorroborationCanOnlyLowerTrustNeverRaiseIt() {
        val good = input(lastFix = fix())
        assertEquals(GnssState.GOOD, GnssQuality.evaluate(good, policy).state)

        val rejected = GnssQuality.evaluate(
            good.copy(innovation = GnssInnovationTrust(accepted = false, detail = "residual 42")),
            policy,
        )
        assertEquals(GnssState.DEGRADED, rejected.state)
        assertEquals("INNOVATION_REJECTED", rejected.reasons.first())

        val accepted = GnssQuality.evaluate(good.copy(innovation = GnssInnovationTrust(accepted = true)), policy)
        assertEquals(GnssState.GOOD, accepted.state)
        assertEquals(listOf("BEARING_UNAVAILABLE"), accepted.reasons)

        // Acceptance corroborates; it cannot promote a fix the recorded evidence rejects.
        val poorButCorroborated = GnssQuality.evaluate(
            input(lastFix = fix(horizontalAccuracyM = 100.0), innovation = GnssInnovationTrust(true)),
            policy,
        )
        assertEquals(GnssState.DEGRADED, poorButCorroborated.state)
        assertEquals("HORIZONTAL_ACCURACY_POOR", poorButCorroborated.reasons.first())

        val staleButCorroborated = GnssQuality.evaluate(
            input(nowNs = origin + 60 * second, lastFix = fix(), innovation = GnssInnovationTrust(true)),
            policy,
        )
        assertEquals(GnssState.STALE, staleButCorroborated.state)
        assertEquals("STALE_FIX", staleButCorroborated.reasons.first())
    }

    // ---------------------------------------------------------------- processor wiring

    @Test fun acquisitionPublishesOneDiagnosticPerRealQualityChange() {
        val events = mutableListOf<Record>()
        val processor = AcquisitionProcessor(Header("quality", Source.REAL), origin, events::add)
        fun codes() = events.mapNotNull { (it.event.data as? DiagnosticEvent)?.code }

        fun acceptFix(t: Long, horizontalAccuracy: Double?, satellites: Long? = 12L) {
            processor.accept(
                Sample(
                    t,
                    t,
                    gnssPayload(12.0, 77.0, null, null, null, horizontalAccuracy, 3.0, satellites, "gps", null),
                ),
            )
        }

        acceptFix(origin, 5.0)
        val first = processor.snapshot(origin, LocationAccess.PRECISE, true, null)
        assertEquals(GnssState.GOOD, first.quality.state)
        assertEquals(0, codes().count { it == "GNSS_OUTAGE" || it == "GNSS_RECOVERED" })

        val stale = processor.snapshot(origin + 3 * second, LocationAccess.PRECISE, true, null)
        assertEquals(GnssState.STALE, stale.quality.state)
        assertEquals(1, codes().count { it == "GNSS_OUTAGE" })
        // A repeated read of an unchanged state must not publish anything further.
        assertEquals(
            GnssState.STALE,
            processor.snapshot(origin + 4 * second, LocationAccess.PRECISE, true, null).quality.state,
        )
        assertEquals(1, codes().count { it == "GNSS_OUTAGE" })

        acceptFix(origin + 4 * second, 5.0)
        assertEquals(
            GnssState.GOOD,
            processor.snapshot(origin + 4 * second, LocationAccess.PRECISE, true, null).quality.state,
        )
        assertEquals(1, codes().count { it == "GNSS_RECOVERED" })

        // A poor fix is degraded, and that too is published exactly once.
        acceptFix(origin + 5 * second, 100.0)
        assertEquals(
            GnssState.DEGRADED,
            processor.snapshot(origin + 5 * second, LocationAccess.PRECISE, true, null).quality.state,
        )
        assertEquals(1, codes().count { it == "GNSS_QUALITY_CHANGED" })
        assertEquals(1, codes().count { it == "GNSS_OUTAGE" })
        assertEquals(1, codes().count { it == "GNSS_RECOVERED" })

        // The reported age tracks the clock against the newest fix, and the contract payload
        // carries the same reasons the decision reported.
        val aged = processor.snapshot(origin + 6 * second, LocationAccess.PRECISE, true, null)
        assertEquals(GnssState.DEGRADED, aged.quality.state)
        assertEquals(1.0, aged.quality.fix_age_s!!, 1e-9)
        assertTrue("HORIZONTAL_ACCURACY_POOR" in aged.quality.reasons)
    }

    @Test fun theFixOwnSatelliteCountWinsAndLiveStatusIsOnlyAFallback() {
        val events = mutableListOf<Record>()
        val processor = AcquisitionProcessor(Header("quality", Source.REAL), origin, events::add)
        processor.accept(
            Sample(
                origin,
                origin,
                gnssPayload(12.0, 77.0, null, null, null, 5.0, 3.0, 12L, "gps", null),
            ),
        )
        val fromFix = processor.snapshot(origin, LocationAccess.PRECISE, true, 9L)
        assertEquals(12L, fromFix.quality.satellites_used)
        assertEquals(GnssState.GOOD, fromFix.quality.state)

        // A second channel whose fix reports none: the live status count is the fallback, and
        // the network profile never demands satellites, so this stays usable.
        val network = AcquisitionProcessor(Header("quality", Source.REAL), origin, events::add)
        network.accept(
            Sample(
                origin,
                origin,
                gnssPayload(12.0, 77.0, null, null, null, 5.0, 3.0, null, "network", null),
            ),
        )
        val fellBack = network.snapshot(origin, LocationAccess.PRECISE, true, 9L)
        assertEquals(9L, fellBack.quality.satellites_used)
        assertEquals(GnssState.GOOD, fellBack.quality.state)

        // The recorded network channel in this corpus is 0.05 Hz, so five seconds of silence
        // is healthy there while the same silence on the 1 Hz GPS channel is not.
        assertEquals(
            GnssState.GOOD,
            network.snapshot(origin + 5 * second, LocationAccess.PRECISE, true, 9L).quality.state,
        )
        assertEquals(
            GnssState.STALE,
            network.snapshot(origin + 41 * second, LocationAccess.PRECISE, true, 9L).quality.state,
        )
        assertEquals(
            GnssState.STALE,
            processor.snapshot(origin + 5 * second, LocationAccess.PRECISE, true, 9L).quality.state,
        )
    }
}
