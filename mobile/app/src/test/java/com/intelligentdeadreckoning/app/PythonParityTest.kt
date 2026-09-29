/*
 * The Python session reader must reach the same verdict as the on-device replay reader.
 *
 * For every case, the metadata and the measurements JSONL are encoded once with the
 * canonical Kotlin codec — the exact byte form an export carries — and then judged twice:
 * by ReplayReader/SessionFiles on this JVM, and by tools/parity_probe.py, which runs the
 * Python reader that consumes exported recordings. The JSON verdicts must agree on
 * acceptance, refusal code, record order, timestamps and replay source.
 *
 * Payload values are never asserted: the parity contract is identity and verdict, not
 * content. The Python interpreter is located under the repository root (.venv first), so
 * no device or network is required and the check runs in ordinary testDebugUnitTest.
 */
package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.replay.ReplayReader
import com.intelligentdeadreckoning.app.sessions.SessionFiles
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Assert.fail returns void, so it cannot sit in an expression. This one is Nothing. */
private fun abort(message: String): Nothing = throw AssertionError(message)

class PythonParityTest {
    @get:Rule val temp = TemporaryFolder()
    private val ns = 9007199254740993L

    // started < every t; ended absorbs the 30 s receipt delays used by the IMU helper.
    private fun metadata(source: Source = Source.REAL, count: Long, channels: List<ChannelCount>? = null) =
        RecordingMetadata("fixture","acq",source,RecordingStartState.RECORDING,RecordingEndState.STOPPED,
            CompletionState.COMPLETED,RecoveryState.NONE,null,null,null,
            ClockIdentity(ClockDomain.ANDROID_ELAPSED_REALTIME_NS,null,"clock",ns,ns,ns + 31_000_000_000),
            DeviceInfo(null,null,null,null,null),ApplicationInfo(null,null,null,null),emptyList(),
            SourceConfiguration(LocationPermissionState.DENIED,null,null,null,null,true),
            CalibrationInfo(CalibrationApplication.NOT_APPLIED,null),count,channels)

    private fun imu(id: Long, t: Long, sensor: Sensor = Sensor.ACCELEROMETER) = Record(
        Header("acq", Source.REAL),
        Event(id.toString(), t, t + 30_000_000_000L, ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE,
            if (sensor == Sensor.GYROSCOPE) ImuUnit.RADIANS_PER_SECOND else ImuUnit.METRES_PER_SECOND_SQUARED,
            Vector3(0.0,0.0,9.80665), SensorAccuracy.HIGH)))

    private fun diagnostic(id: Long, t: Long) = Record(Header("acq",Source.REAL),
        Event(id.toString(),t,t,DiagnosticEvent(Severity.INFO,"TEST","fixture",0)))

    private fun navigation(id: Long, t: Long, mode: InitializationMode) = Record(Header("acq",Source.REAL),
        Event(id.toString(),t,t,NavigationState(NavigationStatus.UNINITIALIZED,mode,null,null,null,null,null,null,false)))

    private fun records(name: String, m: RecordingMetadata, body: List<Record>): File {
        // The stored recording ID must name its own directory, on both sides.
        val fixed = m.copy(recordingId = name)
        val dir = File(temp.root, name).apply { mkdirs() }
        File(dir,"metadata.json").writeBytes(RecordingCodec.encodeMetadata(fixed))
        File(dir,"measurements.jsonl").writeBytes(
            body.joinToString("\n", postfix = "\n") { String(RecordingCodec.encodeRecord(it,fixed), Charsets.UTF_8) }
                .toByteArray())
        return dir
    }

    private class Case(val name: String, val dir: File, val expected: String?, val accepted: Boolean,
                       val ids: List<String>, val source: String)

    private fun cases(): List<Case> {
        val mixed = metadata(count = 3)
        val sim = metadata(Source.SIMULATION, count = 1)
        val dup = metadata(count = 2)
        val pre = metadata(count = 1)
        val modes = metadata(count = 2)
        val shortFall = metadata(count = 2)
        val wrongChannel = metadata(count = 1, channels = listOf(ChannelCount("imu",1)))
        val corrupt = metadata(count = 2)
        val oversized = metadata(count = 1)
        return listOf(
            Case("mixed_arrival", records("mixed_arrival", mixed,
                listOf(imu(0,ns), diagnostic(2,ns+2), imu(1,ns+1,Sensor.GYROSCOPE))),
                null, true, listOf("0","2","1"), "replay_real"),
            Case("simulation_source", records("simulation_source", sim,
                listOf(Record(Header("acq",Source.SIMULATION), imu(0,ns).event))),
                null, true, listOf("0"), "replay_simulation"),
            Case("duplicate_event", records("duplicate_event", dup,
                listOf(imu(7,ns), imu(7,ns+1))), "DUPLICATE_EVENT", false, emptyList(), ""),
            Case("pre_session_record", records("pre_session_record", pre,
                listOf(imu(0,ns-2))), "PRE_SESSION_RECORD", false, emptyList(), ""),
            Case("mode_switch", records("mode_switch", modes,
                listOf(navigation(0,ns,InitializationMode.EVALUATION),
                       navigation(1,ns+1,InitializationMode.DEPLOYABLE))),
                "INITIALIZATION_MODE_CHANGED", false, emptyList(), ""),
            Case("record_count_mismatch", records("record_count_mismatch", shortFall,
                listOf(imu(0,ns))), "RECORD_COUNT_MISMATCH", false, emptyList(), ""),
            Case("channel_count_mismatch", records("channel_count_mismatch", wrongChannel,
                listOf(diagnostic(0,ns))), "CHANNEL_COUNT_MISMATCH", false, emptyList(), ""),
            Case("truncated_row", records("truncated_row", corrupt,
                listOf(imu(0,ns))) .let { dir ->
                    File(dir,"measurements.jsonl").appendBytes("{\"event\":\n".toByteArray()); dir },
                "MALFORMED_JSON", false, emptyList(), ""),
            Case("oversized_row", File(temp.root,"oversized_row").apply {
                    mkdirs()
                    File(this,"metadata.json").writeBytes(RecordingCodec.encodeMetadata(oversized.copy(recordingId = "oversized_row")))
                    // 70_000 bytes: both readers must refuse; Kotlin's guard counts the newline,
                    // Python's counts the stripped line, so max+1-byte lines are out of scope.
                    File(this,"measurements.jsonl").writeBytes("x".repeat(70_000).toByteArray() + '\n'.code.toByte())
                }, "RECORD_TOO_LARGE", false, emptyList(), ""),
        )
    }

    /** Kotlin-side verdict: drain the replay reader, keeping ids and the first refusal. */
    private fun kotlinVerdict(name: String): Triple<List<String>, String?, String> {
        val ids = mutableListOf<String>()
        var source = ""
        var failure: String? = null
        val (meta, stream) = SessionFiles { temp.root }.openReplay(name)
        try {
            val reader = ReplayReader(stream, meta)
            while (true) {
                try { val record = reader.next() ?: break
                    ids.add(record.event.event_id)
                    source = record.header.source.wire
                } catch (e: Exception) { failure = e.message ?: e.javaClass.simpleName; break }
            }
        } finally { stream.close() }
        return Triple(ids, failure, source)
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .firstOrNull { it.resolve("tools").resolve("parity_probe.py").isFile }
            ?: abort("tools/parity_probe.py not found above ${System.getProperty("user.dir")}")

    private fun pythonInterpreter(): String {
        val root = repoRoot()
        val candidates = listOf(
            root.resolve(".venv/Scripts/python.exe"), root.resolve(".venv/bin/python"),
            File("python"), File("python3"), File("py"))
        for (candidate in candidates) {
            val command = if (candidate.isAbsolute) listOf(candidate.absolutePath) else listOf(candidate.name)
            try {
                val probe = ProcessBuilder(command + "--version").start()
                val text = probe.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                probe.waitFor(30, TimeUnit.SECONDS)
                if (probe.exitValue() == 0 && text.startsWith("Python 3.")) return command.first()
            } catch (_: Exception) { }
        }
        abort("no Python 3 interpreter found for the parity probe")
    }

    private data class ProbeVerdict(val accepted: Boolean, val code: String?, val records: List<String>)

    private fun probe(dir: File): ProbeVerdict {
        val process = ProcessBuilder(listOf(pythonInterpreter(),
            repoRoot().resolve("tools/parity_probe.py").absolutePath, dir.absolutePath))
            .redirectErrorStream(false).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("probe failed: $err$out", process.waitFor(120, TimeUnit.SECONDS))
        val json = JsonParser.parseString(out).asJsonObject
        val accepted = json["accepted"].asBoolean
        val code = json["code"]?.takeIf { !it.isJsonNull }?.asString
        val records = json["records"].asJsonArray.map { entry ->
            val row = entry.asJsonObject
            "${row["event_id"].asString}:${row["t_ns"].asLong}:${row["received_ns"].asLong}:${row["source"].asString}"
        }
        return ProbeVerdict(accepted, code, records)
    }

    @Test fun bothReadersReachTheSameVerdictOnEveryFixture() {
        for (case in cases()) {
            val (kotlinIds, kotlinFailure, kotlinSource) = kotlinVerdict(case.name)
            val python = probe(case.dir)
            val context = "case ${case.name}: kotlin failure=${kotlinFailure ?: "none"} python=$python"
            assertEquals(context, case.accepted, kotlinFailure == null)
            assertEquals(context, case.accepted, python.accepted)
            if (!case.accepted) {
                assertNotNull(context, python.code)
                assertTrue(context, python.code!!.contains(case.expected!!))
                assertNotNull(context, kotlinFailure)
                assertTrue(context, kotlinFailure!!.contains(case.expected))
            } else {
                assertEquals(context, case.ids, kotlinIds)
                assertEquals(context, case.ids, python.records.map { it.substringBefore(':') })
                assertEquals(context, case.source, kotlinSource)
                assertTrue(context, python.records.all { it.substringAfterLast(':') == case.source })
                // Exact Int64 timestamps survive both readers (never binary64).
                assertTrue(context, python.records.any { it.startsWith("0:$ns:${ns + 30_000_000_000L}:") })
            }
        }
    }
}
