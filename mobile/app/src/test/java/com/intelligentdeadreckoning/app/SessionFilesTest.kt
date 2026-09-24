package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.sessions.*
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(id: String = "session", incomplete: Boolean = false): SessionFiles {
        val ns = 9007199254740993L
        val m = RecordingMetadata(id, "acq", Source.REAL, RecordingStartState.RECORDING,
            if (incomplete) RecordingEndState.INTERRUPTED else RecordingEndState.STOPPED,
            if (incomplete) CompletionState.INCOMPLETE else CompletionState.COMPLETED,
            if (incomplete) RecoveryState.RECOVERED else RecoveryState.NONE,
            100, 100, 200, ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS, null, "acq", ns, ns, ns + 100),
            DeviceInfo(null,null,null,null,null), ApplicationInfo(null,null,null,null), emptyList(),
            SourceConfiguration(LocationPermissionState.DENIED,null,null,null,null,true),
            CalibrationInfo(CalibrationApplication.NOT_APPLIED,null), 0, emptyList())
        val dir = File(temp.root,id).apply { mkdir() }
        File(dir,"metadata.json").writeBytes(RecordingCodec.encodeMetadata(m))
        File(dir,"measurements.jsonl").writeBytes(byteArrayOf())
        return SessionFiles { temp.root }
    }
    @Test fun deterministicExactCopyAndOriginalPreserved() {
        val files = fixture()
        val original = File(temp.root,"session/metadata.json")
        val bytes = original.readBytes(); val modified = original.lastModified()
        fun pack() = ByteArrayOutputStream().also { files.export("session", { it }) }.toByteArray()
        val a = pack(); assertArrayEquals(a, pack())
        ZipInputStream(ByteArrayInputStream(a)).use {
            assertEquals("metadata.json", it.nextEntry.name); assertArrayEquals(bytes,it.readBytes())
            assertEquals("measurements.jsonl",it.nextEntry.name); assertEquals(0,it.readBytes().size)
            assertNull(it.nextEntry)
        }
        assertArrayEquals(bytes,original.readBytes()); assertEquals(modified,original.lastModified())
    }
    @Test fun incompleteAndMissingAreExplicit() {
        val files = fixture(incomplete = true)
        val item = files.inspect("session")
        assertEquals(RecoveryState.RECOVERED,item.metadata!!.recoveryState)
        assertEquals(100L,item.durationNs); assertTrue(item.exportable)
        assertNotNull(files.inspect("missing").error)
        assertFalse(files.inspect("../session").exportable)
        assertEquals(1,files.list().sessions.size)
    }
    @Test fun missingSessionDoesNotOpenDestination() {
        var opened = false
        try { SessionFiles { temp.root }.export("missing", { opened = true; ByteArrayOutputStream() }); fail() }
        catch (_: IllegalArgumentException) { }
        assertFalse(opened)
    }
    @Test fun cancellationAndWriteFailurePreserveSource() {
        val files = fixture(); val before = File(temp.root,"session/metadata.json").readBytes()
        try { files.export("session", { ByteArrayOutputStream() }) { throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        try { files.export("session", { object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } } }); fail() }
        catch (_: IOException) { }
        assertArrayEquals(before,File(temp.root,"session/metadata.json").readBytes())
    }
    @Test fun pickerCancellationAndRepeatedRequest() = runTest {
        val controller = ExportController(fixture(),this,StandardTestDispatcher(testScheduler))
        assertTrue(controller.choose("session")); assertFalse(controller.choose("other"))
        controller.onBackground() // picker is allowed to own foreground
        assertEquals(ExportPhase.CHOOSING,controller.state.value.phase)
        controller.result(null)
        assertEquals(ExportPhase.CANCELLED,controller.state.value.phase)
    }
    @Test fun successfulAndFailedControllerResults() = runTest {
        val controller = ExportController(fixture(),this,StandardTestDispatcher(testScheduler))
        controller.choose("session"); controller.result { ByteArrayOutputStream() }; advanceUntilIdle()
        assertEquals(ExportPhase.EXPORTED,controller.state.value.phase)
        controller.choose("missing"); controller.result { error("must not open") }; advanceUntilIdle()
        assertEquals(ExportPhase.FAILED,controller.state.value.phase)
    }
    @Test fun pagesAreBounded() {
        val files = fixture()
        repeat(24) { File(temp.root,"z$it").mkdir() }
        val page = files.list(); assertEquals(20,page.sessions.size); assertNotNull(page.next)
        assertEquals(5,files.list(page.next).sessions.size)
    }
    @Test fun backgroundCancelsQueuedWriteWithoutOpeningDestination() = runTest {
        val controller = ExportController(fixture(),this,StandardTestDispatcher(testScheduler))
        var opened = false
        controller.choose("session"); controller.result { opened = true; ByteArrayOutputStream() }
        controller.onBackground(); advanceUntilIdle()
        assertFalse(opened); assertEquals(ExportPhase.CANCELLED,controller.state.value.phase)
    }
    @Test fun openAndOversizedMetadataCannotExport() {
        val files = fixture()
        val path = File(temp.root,"session/metadata.json")
        val m = RecordingCodec.decodeMetadata(path.readBytes())
        path.writeBytes(RecordingCodec.encodeMetadata(m.copy(endState = null, completionState = CompletionState.OPEN,
            endedUtcMs = null, clock = m.clock.copy(endedNs = null), recordCount = null, channelCounts = null)))
        assertFalse(files.inspect("session").exportable)
        path.writeBytes(ByteArray(262145))
        assertTrue(files.inspect("session").error!!.contains("limit"))
    }
    @Test fun bytesAreStableAcrossTimezonesAndCancellationClosesOutput() {
        val files = fixture()
        val measurements = File(temp.root,"session/measurements.jsonl")
        // Export is byte-preserving, not a validator or repairer, even for corrupt evidence.
        val payload = ByteArray(150000) { (it % 251).toByte() }; measurements.writeBytes(payload)
        val oldZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val first = ByteArrayOutputStream().also { files.export("session", { it }) }.toByteArray()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            val second = ByteArrayOutputStream().also { files.export("session", { it }) }.toByteArray()
            assertArrayEquals(first,second)
        } finally { java.util.TimeZone.setDefault(oldZone) }
        var opened = false; var closed = false
        try {
            files.export("session", { opened = true; object : ByteArrayOutputStream() {
                override fun close() { closed = true; super.close() }
            } }) { if (opened) throw CancellationException() }
            fail()
        } catch (_: CancellationException) { }
        assertTrue(closed); assertArrayEquals(payload,measurements.readBytes())
    }
}
