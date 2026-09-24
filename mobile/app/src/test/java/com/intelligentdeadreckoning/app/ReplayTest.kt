package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.replay.*
import com.intelligentdeadreckoning.app.sessions.*
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayTest {
    @get:Rule val temp = TemporaryFolder()
    private val ns = 9007199254740993L
    private fun metadata(source: Source = Source.REAL, count: Long = 2) = RecordingMetadata(
        "fixture","acq",source,RecordingStartState.RECORDING,RecordingEndState.STOPPED,
        CompletionState.COMPLETED,RecoveryState.NONE,null,null,null,
        ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS,null,"clock",ns,ns,ns + 2_000_000_000),
        DeviceInfo(null,null,null,null,null),ApplicationInfo(null,null,null,null),emptyList(),
        SourceConfiguration(LocationPermissionState.DENIED,null,null,null,null,true),
        CalibrationInfo(CalibrationApplication.NOT_APPLIED,null),count,listOf(ChannelCount("diagnostic",count)))
    private fun record(id: Long = 0, time: Long = ns, source: Source = Source.REAL) = Record(Header("acq",source),
        Event(id.toString(),time,time,DiagnosticEvent(Severity.INFO,"TEST","fixture",0)))
    private fun bytes(records: List<Record>, m: RecordingMetadata) = records.joinToString("\n", postfix = if (records.isEmpty()) "" else "\n") {
        String(RecordingCodec.encodeRecord(it,m), Charsets.UTF_8)
    }.toByteArray()
    private fun files(m: RecordingMetadata = metadata(), records: List<Record> = listOf(record(),record(1,ns + 1_000_000_000))): SessionFiles {
        val dir = File(temp.root,"fixture").apply { mkdir() }
        File(dir,"metadata.json").writeBytes(RecordingCodec.encodeMetadata(m))
        File(dir,"measurements.jsonl").writeBytes(bytes(records,m))
        return SessionFiles { temp.root }
    }
    @Test fun exactInt64SourceAndArrivalOrderWithoutRetiming() {
        val m = metadata(count = 4)
        val original = listOf(record(0),record(2,ns+2),record(3,ns+3),record(1,ns+1))
        val reader = ReplayReader(ByteArrayInputStream(bytes(original,m)),m)
        original.forEach { assertEquals(it.copy(header = it.header.copy(source = Source.REPLAY_REAL)),reader.next()) }
        assertNull(reader.next())
    }
    @Test fun simulationSourceAndNullGnssArePreserved() {
        val m = metadata(Source.SIMULATION,1).copy(channelCounts = listOf(ChannelCount("gnss",1)))
        val payload = GnssMeasurement(10.0,20.0,null,null,null,null,null,null,null,"fixture",null)
        val r = Record(Header("acq",Source.SIMULATION),Event("0",ns,ns,payload))
        val reader = ReplayReader(ByteArrayInputStream(bytes(listOf(r),m)),m)
        assertEquals(r.copy(header = r.header.copy(source = Source.REPLAY_SIMULATION)),reader.next())
        assertNull(reader.next())
    }
    @Test fun rejectsDuplicateInteriorCorruptionTruncatedTailAndCountMismatch() {
        val m = metadata()
        val duplicate = bytes(listOf(record(),record()),m)
        val good = bytes(listOf(record(),record(1)),m)
        val corrupt = bytes(listOf(record()),m) + "{bad}\n".toByteArray()
        val truncated = bytes(listOf(record()),m) + "{\"event\":".toByteArray()
        listOf(duplicate,corrupt,truncated,bytes(listOf(record()),m),good + "\n".toByteArray()).forEach { raw ->
            val reader = ReplayReader(ByteArrayInputStream(raw),m)
            try { while(reader.next() != null) {} ; fail("Must reject") }
            catch(e: IOException) { assertTrue(e.message!!.contains("Replay line")) }
        }
    }
    @Test fun finalLineWithoutLfAndEmptySession() {
        val m = metadata(count = 1)
        val reader = ReplayReader(ByteArrayInputStream(bytes(listOf(record()),m).dropLast(1).toByteArray()),m)
        assertNotNull(reader.next()); assertNull(reader.next())
        val empty = metadata(count = 0).copy(channelCounts = emptyList())
        assertNull(ReplayReader(ByteArrayInputStream(byteArrayOf()),empty).next())
    }
    @Test fun recoveredAllowedButOpenFailedUnrecoverableRejected() {
        val m = metadata().copy(completionState = CompletionState.INCOMPLETE,endState = RecordingEndState.INTERRUPTED,recoveryState = RecoveryState.RECOVERED)
        assertTrue(SavedSession("fixture",m,1).replayable)
        assertFalse(SavedSession("fixture",m.copy(recoveryState = RecoveryState.UNRECOVERABLE),1).replayable)
        assertFalse(SavedSession("fixture",m.copy(completionState = CompletionState.OPEN),1).replayable)
        assertFalse(SavedSession("fixture",m.copy(completionState = CompletionState.FAILED),1).replayable)
    }
    @Test fun playbackPauseResumeStopAndRestartUsesSeparateClock() = runTest {
        val player = ReplayController(files(),this,{testScheduler.currentTime * 1_000_000},StandardTestDispatcher(testScheduler))
        val received = mutableListOf<Record>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { received.add(it) } }
        assertTrue(player.start("fixture")); assertFalse(player.start("fixture")); runCurrent()
        assertEquals(1,received.size)
        advanceTimeBy(400); player.pause(); advanceTimeBy(5000); runCurrent()
        assertEquals(1,received.size); assertEquals(ReplayPhase.PAUSED,player.state.value.phase)
        player.resume(); advanceTimeBy(599); runCurrent(); assertEquals(1,received.size)
        advanceTimeBy(30); runCurrent(); assertEquals(2,received.size)
        assertEquals(ReplayPhase.COMPLETED,player.state.value.phase)
        assertEquals(ns,received.first().event.t_ns)
        assertTrue(player.start("fixture")); runCurrent(); player.stop(); runCurrent()
        assertEquals(ReplayPhase.STOPPED,player.state.value.phase)
        advanceTimeBy(5000); runCurrent(); assertEquals(ReplayPhase.STOPPED,player.state.value.phase)
    }
    @Test fun missingAndCorruptSessionFailWithoutChangingFiles() = runTest {
        val f = files(); val path = File(temp.root,"fixture/measurements.jsonl")
        path.appendText("broken\n"); val before = path.readBytes()
        val player = ReplayController(f,this,{testScheduler.currentTime * 1_000_000},StandardTestDispatcher(testScheduler))
        player.start("missing"); advanceUntilIdle(); assertEquals(ReplayPhase.FAILED,player.state.value.phase)
        player.start("fixture"); advanceUntilIdle(); assertEquals(ReplayPhase.FAILED,player.state.value.phase)
        assertArrayEquals(before,path.readBytes())
    }
    @Test fun slowConsumerBackpressuresWithoutDrops() = runTest {
        val player = ReplayController(files(),this,{testScheduler.currentTime * 1_000_000},StandardTestDispatcher(testScheduler))
        val received = mutableListOf<Record>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { received.add(it); delay(2000) } }
        player.start("fixture"); runCurrent(); advanceTimeBy(1100); runCurrent()
        assertEquals(1,received.size)
        advanceTimeBy(1000); runCurrent(); assertEquals(2,received.size)
        assertEquals(ReplayPhase.COMPLETED,player.state.value.phase)
    }
    @Test fun badVersionMembershipAndChannelCountsRejected() {
        val m = metadata(count = 1)
        val raw = bytes(listOf(record()),m).toString(Charsets.UTF_8)
        listOf(raw.replace("1.0.0","9.0.0"), raw.replace("\"acq\"","\"other\""), raw.replace("\"real\"","\"simulation\"")).forEach {
            try { ReplayReader(ByteArrayInputStream(it.toByteArray()),m).next(); fail() } catch (_: IOException) { }
        }
        val reader = ReplayReader(ByteArrayInputStream(raw.toByteArray()),m.copy(channelCounts = listOf(ChannelCount("imu",1))))
        reader.next()
        try { reader.next(); fail() } catch(e: IOException) { assertTrue(e.message!!.contains("CHANNEL_COUNT_MISMATCH")) }
    }
    @Test fun limitZeroCountsAndImmediateStopAreExplicit() = runTest {
        try { ReplayReader(ByteArrayInputStream(byteArrayOf()),metadata(count = 1_000_001)); fail() }
        catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("REPLAY_RECORD_LIMIT")) }
        assertNull(ReplayReader(ByteArrayInputStream(byteArrayOf()),metadata(count = 0)).next())
        val player = ReplayController(files(),this,{testScheduler.currentTime * 1_000_000},StandardTestDispatcher(testScheduler))
        player.start("fixture"); player.stop(); runCurrent()
        assertEquals(ReplayPhase.STOPPED,player.state.value.phase)
        assertEquals(0L,player.state.value.emitted)
    }
}
