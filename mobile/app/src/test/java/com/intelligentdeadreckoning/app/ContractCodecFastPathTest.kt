package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The per-sample validation avoids a charset encoder and regexes; these pin that it accepts and rejects exactly what the slow forms did. */
class ContractCodecFastPathTest {
    private fun diagnostic(message: String, eventId: String = "1", tNs: Long = 5L) =
        Record(Header("fast-path", Source.SIMULATION, "1.1.0"),
            Event(eventId, tNs, tNs, DiagnosticEvent(Severity.INFO, "CODE", message, 0)))

    private fun rejection(record: Record): String? = try { Codec.encodeJson(record); null } catch (e: IllegalArgumentException) { e.message }

    @Test fun wellFormedTextIncludingSurrogatePairsPassesRoundTrip() {
        for (text in listOf("plain", "caf\u00e9", "emoji \uD83D\uDE97 car", "\u4e2d\u6587")) {
            val r = diagnostic(text)
            assertEquals(r, Codec.decodeJson(Codec.encodeJson(r)))
        }
    }

    @Test fun unpairedSurrogatesAreRejectedAsInvalidUnicode() {
        for (text in listOf("lone high \uD83D", "lone low \uDE97", "\uDE97\uD83D swapped", "ends high \uD83D")) {
            val message = rejection(diagnostic(text))
            assertTrue("$text -> $message", message != null && message.contains("INVALID_UNICODE"))
        }
    }

    @Test fun blankAndOverlongStringsStillFail() {
        assertTrue(rejection(diagnostic("   "))!!.contains("OUT_OF_RANGE"))
        assertTrue(rejection(diagnostic("x".repeat(2049)))!!.contains("OUT_OF_RANGE"))
    }

    @Test fun decimalStringsRejectLeadingZerosSignsAndNonDigitsOnDecode() {
        val good = Codec.encodeJson(diagnostic("ok", eventId = "7", tNs = 123456789012345L)).toString(Charsets.UTF_8)
        assertEquals(123456789012345L, Codec.decodeJson(good.toByteArray()).event.t_ns)
        for (bad in listOf("0123456789012345", "-1", "12a", "", "1.5")) {
            val broken = good.replace("\"123456789012345\"", "\"$bad\"")
            try { Codec.decodeJson(broken.toByteArray()); fail("accepted t_ns=$bad") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0L, Codec.decodeJson(good.replace("\"123456789012345\"", "\"0\"").toByteArray()).event.t_ns)
    }
}
