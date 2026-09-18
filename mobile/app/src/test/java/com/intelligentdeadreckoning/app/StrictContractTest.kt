package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.contracts.v1.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class StrictContractTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
    private fun records() = Codec.readJsonl(ByteArrayInputStream(fixture("golden_records.jsonl"))).toList()
    private fun failure(code: String? = null, block: () -> Unit): ContractException {
        try { block(); fail("Expected contract rejection") }
        catch (e: ContractException) { if (code != null) assertEquals(code, e.code); return e }
        error("unreachable")
    }

    @Test fun allSevenEventTypesRoundTripAsTypedModels() {
        val records = records()
        assertEquals(7, records.map { it.event.data.type }.toSet().size)
        for (r in records) {
            assertEquals(r, Codec.decodeJson(Codec.encodeJson(r)))
            val out = ByteArrayOutputStream()
            Codec.writeJson(r, out)
            assertEquals(r, Codec.readJson(ByteArrayInputStream(out.toByteArray())))
        }
        val out = ByteArrayOutputStream()
        Codec.writeJsonl(records.asSequence(), out)
        assertEquals(records, Codec.readJsonl(ByteArrayInputStream(out.toByteArray())).toList())
    }

    @Test fun sharedNegativeCorpusRejectsAll47CasesWithMatchingDiagnostics() {
        val cases = JsonParser.parseString(fixture("invalid_records.json").toString(Charsets.UTF_8)).asJsonArray
        assertEquals(47, cases.size())
        cases.forEach {
            val c = it.asJsonObject
            try { failure(c["code"].asString) { Codec.decodeJson(c["input"].asString.toByteArray(Charsets.UTF_8)) } }
            catch (e: AssertionError) { throw AssertionError("Case: ${c["name"].asString}", e) }
        }
    }

    @Test fun int64TimestampsRemainExactAtBothBoundariesAndAbove2pow53() {
        val r = records().first()
        listOf(0L, 9007199254740993L, Long.MAX_VALUE).forEach { time ->
            val changed = r.copy(event = r.event.copy(t_ns = time, received_ns = time))
            assertEquals(time, Codec.decodeJson(Codec.encodeJson(changed)).event.t_ns)
            val obj = JsonParser.parseString(Codec.encodeJson(changed).toString(Charsets.UTF_8))
            assertEquals(time.toString(), obj.asJsonObject.getAsJsonObject("event")["t_ns"].asString)
        }
        failure("OUT_OF_RANGE") { Codec.encodeJson(r.copy(event = r.event.copy(t_ns = -1))) }
    }

    @Test fun nullableValuesRemainExplicitNullNotInventedZero() {
        val r = records()[2]
        val fix = r.event.data as GnssMeasurement
        assertNull(fix.speed_m_s); assertNull(fix.bearing_deg); assertNull(fix.altitude_m)
        val obj = JsonParser.parseString(Codec.encodeJson(r).toString(Charsets.UTF_8))
        assertTrue(obj.asJsonObject.getAsJsonObject("event").getAsJsonObject("data")["speed_m_s"].isJsonNull)
    }

    @Test fun writerRejectsNonfiniteTypedModelsBeforeWritingAnything() {
        val r = records()[0]
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { n ->
            val bad = r.copy(event = r.event.copy(data = (r.event.data as ImuMeasurement).copy(xyz = Vector3(n,0.0,1.0))))
            val out = ByteArrayOutputStream()
            failure("NONFINITE") { Codec.writeJson(bad, out) }
            assertEquals(0, out.size())
        }
    }

    @Test fun reflectionsNonunitQuaternionsAndNonrotationsAreRejected() {
        validateRotation(listOf(listOf(0.0,-1.0,0.0),listOf(1.0,0.0,0.0),listOf(0.0,0.0,1.0)))
        failure("INVALID_ROTATION") { validateRotation(listOf(listOf(-1.0,0.0,0.0),listOf(0.0,1.0,0.0),listOf(0.0,0.0,1.0))) }
        failure("INVALID_ROTATION") { validateRotation(listOf(listOf(2.0,0.0,0.0),listOf(0.0,1.0,0.0),listOf(0.0,0.0,1.0))) }
        val r = records()[3]
        val cal = r.event.data as CalibrationResult
        val negative = r.copy(event = r.event.copy(data = cal.copy(q_vehicle_from_device_wxyz = Quaternion(-1.0,0.0,0.0,0.0))))
        assertEquals(negative, Codec.decodeJson(Codec.encodeJson(negative)))
        failure("INVALID_ROTATION") { Codec.encodeJson(r.copy(event = r.event.copy(data = cal.copy(q_vehicle_from_device_wxyz = Quaternion(2.0,0.0,0.0,0.0))))) }
    }

    @Test fun duplicateIdsFailAtLine2AndKeepOnlyValidPrefix() {
        val r = records()[0]
        val one = Codec.encodeJson(r) + byteArrayOf(10)
        val reader = Codec.readJsonl(ByteArrayInputStream(one+one)).iterator()
        assertEquals(r, reader.next())
        assertEquals(2L, failure("DUPLICATE_EVENT") { reader.next() }.line)
        val out = ByteArrayOutputStream()
        failure("DUPLICATE_EVENT") { Codec.writeJsonl(sequenceOf(r,r), out) }
        assertArrayEquals(one, out.toByteArray())
    }

    @Test fun sameTimestampDifferentSensorsAndArrivalOrderArePreserved() {
        val r = records()
        val a = r[0]; val b = r[1].copy(event = r[1].event.copy(t_ns = a.event.t_ns, received_ns = a.event.received_ns))
        val out = ByteArrayOutputStream()
        Codec.writeJsonl(sequenceOf(b,a), out)
        assertEquals(listOf(b,a), Codec.readJsonl(ByteArrayInputStream(out.toByteArray())).toList())
    }

    @Test fun headerAndInitializationModeCannotChangeSilently() {
        val r = records()
        for (header in listOf(r[0].header.copy(session_id = "other"), r[0].header.copy(source = Source.REAL))) {
            val b = r[1].copy(header = header)
            failure("SESSION_MISMATCH") { Codec.readJsonl(ByteArrayInputStream(Codec.encodeJson(r[0])+byteArrayOf(10)+Codec.encodeJson(b))).toList() }
        }
        val nav = r[4]; val p = nav.event.data as NavigationState
        val changed = nav.copy(event = nav.event.copy(event_id = "90", data = p.copy(initialization_mode = InitializationMode.EVALUATION)))
        failure("INVARIANT") { Codec.writeJsonl(sequenceOf(nav,changed), ByteArrayOutputStream()) }
    }

    @Test fun truncationBlankLinesInvalidUtf8AndCrLfAreHandled() {
        val r = records()[0]; val one = Codec.encodeJson(r)
        assertEquals(listOf(r), Codec.readJsonl(ByteArrayInputStream(one)).toList())
        assertEquals(listOf(r), Codec.readJsonl(ByteArrayInputStream(one+byteArrayOf(13,10))).toList())
        listOf(one.copyOf(one.size-1),byteArrayOf(10),byteArrayOf(0xff.toByte())).forEach { bad ->
            assertEquals(2L, failure { Codec.readJsonl(ByteArrayInputStream(one+byteArrayOf(10)+bad)).toList() }.line)
        }
        failure("INVALID_UTF8") { Codec.decodeJson(byteArrayOf(0xff.toByte())) }
    }

    @Test fun lazyReadersHaveSizeDepthAndEventLimits() {
        val raw = fixture("golden_records.jsonl")
        val input = ByteArrayInputStream(raw)
        val seq = Codec.readJsonl(input)
        assertEquals(raw.size, input.available())
        seq.iterator().next()
        assertTrue(input.available() > 0)
        failure("RESOURCE_LIMIT") { Codec.readJsonl(ByteArrayInputStream(raw), Limits(maxRecords = 1)).toList() }
        failure("RESOURCE_LIMIT") { Codec.decodeJson(ByteArray(1000) { 32 }, Limits(maxRecordBytes = 100)) }
        failure("RESOURCE_LIMIT") { Codec.decodeJson(("[".repeat(32)+"0"+"]".repeat(32)).toByteArray()) }
    }

    @Test fun shortReadsAndIoFailuresAreExplicitAndRedacted() {
        val r = records()[0]
        val short = object : ByteArrayInputStream(Codec.encodeJson(r)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b,off,minOf(len,3))
        }
        assertEquals(r, Codec.readJson(short))
        val broken = object : OutputStream() { override fun write(b: Int) { throw IOException("private path") } }
        val e = failure("IO_ERROR") { Codec.writeJson(r,broken) }
        assertFalse(e.message!!.contains("private"))
    }

    @Test fun kotlinConsumesPythonOutputAndEmitsTypedOutputForPython() {
        val canonical = records() + Codec.readJsonl(ByteArrayInputStream(fixture("edge_records.jsonl"))).toList()
        val external = System.getenv("IDR_CONTRACT_PYTHON_JSONL")
        val input = if (external == null) canonical else File(external).inputStream().use { Codec.readJsonl(it).toList() }
        assertEquals(canonical, input)
        val output = File("build/contract_interop/kotlin.jsonl")
        output.parentFile.mkdirs()
        output.outputStream().use { Codec.writeJsonl(input.asSequence(), it) }
        assertEquals(canonical, output.inputStream().use { Codec.readJsonl(it).toList() })
    }

    @Test fun engineRemainsAnInterfaceWithNoUiDependencies() {
        assertTrue(NavigationEngine::class.java.isInterface)
        assertEquals("com.intelligentdeadreckoning.contracts.v1", NavigationEngine::class.java.packageName)
    }

    @Test fun edgeFixturesAndEverySourceLabelRoundTripWithoutLosingNullsOrUnits() {
        val edge = Codec.readJsonl(ByteArrayInputStream(fixture("edge_records.jsonl"))).toList()
        assertEquals(9, edge.size)
        assertEquals(Long.MAX_VALUE, (edge[7].event.data as DiagnosticEvent).dropped_count)
        assertEquals(Long.MAX_VALUE, edge.last().event.t_ns)
        for (source in Source.entries) for (r in edge) {
            val changed = r.copy(header = r.header.copy(source = source))
            assertEquals(changed, Codec.decodeJson(Codec.encodeJson(changed)))
        }
    }
}
