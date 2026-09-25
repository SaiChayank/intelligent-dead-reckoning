package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.map.*
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.*
import org.junit.Test

class NavigationPresentationTest {
    private val epoch = 9_007_199_254_740_993L
    private fun adapter(capacity: Int = 512) = NavigationPresentation(SyntheticMapDemo.header,InitializationMode.DEPLOYABLE,capacity)
    private fun record(ms: Long = 0) = SyntheticMapDemo.record(ms,epoch)
    private fun nav(record: Record, change: (NavigationState) -> NavigationState) =
        record.copy(event = record.event.copy(data = change(record.event.data as NavigationState)))
    @Test fun originAndKnownEquatorialEastNorthDisplacements() {
        val origin = GeoOrigin(0.0,0.0,0.0)
        assertEquals(MapPoint(0.0,0.0),MapCoordinates.fromEnu(origin,Vector3(0.0,0.0,0.0)))
        val east = MapCoordinates.fromEnu(origin,Vector3(1000.0,0.0,0.0))
        assertEquals(0.00898315277,east.longitude,1e-10); assertEquals(0.0,east.latitude,1e-12)
        val north = MapCoordinates.fromEnu(origin,Vector3(0.0,1000.0,0.0))
        assertEquals(0.0090436947,north.latitude,1e-9); assertEquals(0.0,north.longitude,1e-12)
    }
    @Test fun hyderabadOriginAndHeadingSpeedPreserved() {
        val state = adapter().accept(record(),epoch)
        assertEquals(17.435,state.point!!.latitude,1e-10); assertEquals(78.445,state.point.longitude,1e-10)
        assertEquals(90.0,state.headingDegrees!!,0.0); assertEquals(10.0,state.speedMetresPerSecond!!,1e-12)
        assertEquals(Source.SIMULATION,state.source); assertNull(state.accuracy95Metres)
    }
    @Test fun exactLargeTimestampsAndStaleBoundary() {
        val a = adapter(); a.accept(record(),epoch)
        assertNotNull(a.snapshot(epoch+NavigationPresentation.STALE_NS).point)
        assertNull(a.snapshot(epoch+NavigationPresentation.STALE_NS+1).point)
    }
    @Test fun futureAndLateReceiptAreHidden() {
        assertNull(adapter().accept(record(),epoch-1).point)
        val r = record().let { it.copy(event = it.event.copy(received_ns = epoch+1)) }
        assertNull(adapter().accept(r,epoch).point)
    }
    @Test fun duplicateAndOutOfOrderBreakTrail() {
        val a = adapter(); val r = record(100)
        assertNotNull(a.accept(r,r.event.t_ns).point)
        assertNull(a.accept(r,r.event.t_ns).point)
        assertNull(a.accept(record(),r.event.t_ns).point)
        assertEquals(1,a.accept(record(200),epoch+200_000_000).trail.size)
    }
    @Test fun sourceSessionAndModeCannotMix() {
        for(header in listOf(SyntheticMapDemo.header.copy(source = Source.REAL),SyntheticMapDemo.header.copy(session_id = "other")))
            assertNull(adapter().accept(record().copy(header = header),epoch).point)
        assertNull(adapter().accept(nav(record()) { it.copy(initialization_mode = InitializationMode.EVALUATION) },epoch).point)
    }
    @Test fun invalidNaNAndQuaternionAreRejected() {
        assertNull(adapter().accept(nav(record()) { it.copy(position_enu_m = Vector3(Double.NaN,0.0,0.0)) },epoch).point)
        assertNull(adapter().accept(nav(record()) { it.copy(q_enu_from_vehicle_wxyz = Quaternion(2.0,0.0,0.0,0.0)) },epoch).point)
    }
    @Test fun unavailableAndOutsideCoverageHidePosition() {
        val unavailable = NavigationState(NavigationStatus.UNINITIALIZED,InitializationMode.DEPLOYABLE,null,null,null,null,null,null,false)
        assertNull(adapter().accept(record().let { it.copy(event = it.event.copy(data = unavailable)) },epoch).point)
        assertNull(adapter().accept(nav(record()) { it.copy(origin_wgs84_deg_m = GeoOrigin(0.0,0.0,0.0)) },epoch).point)
    }
    @Test fun boundedTrailDoesNotAccumulateAnEntireTrip() {
        val a = adapter(3)
        repeat(100) { val r = record(it*100L); a.accept(r,r.event.t_ns) }
        assertEquals(3,a.snapshot(epoch+9_900_000_000L).trail.size)
    }
    @Test fun missingHeadingAndVelocityStayNull() {
        val r = nav(record()) { it.copy(status = NavigationStatus.DEGRADED,heading_deg = null,velocity_enu_m_s = null) }
        val state = adapter().accept(r,epoch)
        assertNotNull(state.point); assertNull(state.headingDegrees); assertNull(state.speedMetresPerSecond)
    }
    @Test fun confidenceRequiresCalibratedSameSessionSameTime() {
        val r = record()
        val c = r.copy(event = r.event.copy(event_id = "100",data = Confidence(ConfidenceState.CALIBRATED,null,12.0,null)))
        assertEquals(12.0,adapter().accept(r,epoch,c).accuracy95Metres!!,0.0)
        assertNull(adapter().accept(r,epoch,c.copy(header = c.header.copy(source = Source.REAL))).accuracy95Metres)
        assertNull(adapter().accept(r,epoch,c.copy(event = c.event.copy(t_ns = epoch-1))).accuracy95Metres)
        assertNull(adapter().accept(r,epoch,c.copy(event = c.event.copy(data = Confidence(ConfidenceState.UNVALIDATED,null,12.0,null)))).accuracy95Metres)
    }
    @Test fun fixtureTransitionsAreContinuousAndRemainSynthetic() {
        for(boundary in listOf(10_000L,20_000L)) {
            val a = adapter(); val before = record(boundary-1); val after = record(boundary)
            val p = a.accept(before,before.event.t_ns).point!!; val q = a.accept(after,after.event.t_ns).point!!
            assertTrue(kotlin.math.abs(p.latitude-q.latitude)<1e-6)
            assertTrue(kotlin.math.abs(p.longitude-q.longitude)<1e-6)
            assertEquals(Source.SIMULATION,after.header.source)
            assertNotEquals(SyntheticMapDemo.scenario(boundary-1),SyntheticMapDemo.scenario(boundary))
        }
    }
    @Test fun fixtureIsDeterministicAndContractValidAtEveryTick() {
        for(ms in 0L..30_000L step 50) {
            val r = record(ms)
            assertEquals(r,Codec.decodeJson(Codec.encodeJson(r)))
            assertEquals(r,record(ms))
        }
    }
    @Test fun geoJsonUsesLongitudeFirstAndClosesPolygons() {
        val state = adapter().accept(record(),epoch).copy(accuracy95Metres = 8.0)
        val features = JsonParser.parseString(MapOverlay.json(state)).asJsonObject["features"].asJsonArray
        val point = features.first { it.asJsonObject["properties"].asJsonObject["kind"].asString == "position" }.asJsonObject
        assertEquals(78.445,point["geometry"].asJsonObject["coordinates"].asJsonArray[0].asDouble,1e-10)
        features.filter { it.asJsonObject["geometry"].asJsonObject["type"].asString == "Polygon" }.forEach {
            val ring = it.asJsonObject["geometry"].asJsonObject["coordinates"].asJsonArray[0].asJsonArray
            // Last circle point is explicitly closed, not approximately closed.
            assertEquals(ring[0],ring[ring.size()-1])
        }
    }
    @Test fun hiddenPositionProducesNoMapFeatures() {
        val raw = MapOverlay.json(MapPresentation(Source.REAL))
        assertEquals(0,JsonParser.parseString(raw).asJsonObject["features"].asJsonArray.size())
    }
    @Test fun gapsDoNotConnectUnobservedTravel() {
        val a = adapter(); a.accept(record(),epoch)
        val r = record(5000)
        assertEquals(1,a.accept(r,r.event.t_ns).trail.size)
    }
}
