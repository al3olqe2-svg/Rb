package com.rb.app

import com.rb.app.scanner.*
import org.junit.Assert.*
import org.junit.Test

class Stage5LogicTest {
    private val full = "8996603421269968985"
    private fun ocr(vararg s: String) = SerialRecognition.read(emptyList(), s.toList())
    @Test fun noisyFramesFreezeExactFullNumberUntilExplicitReset() {
        val gate = TemporalCapture()
        assertTrue(gate.accept(ocr(full)).isEmpty())
        assertTrue(gate.accept(ocr(full.dropLast(2))).isEmpty())
        assertTrue(gate.accept(ocr(full)).isEmpty())
        assertEquals(listOf(full), gate.accept(ocr(full)))
        repeat(10) { assertEquals(listOf(full), gate.accept(ocr("8996603421269968999"))) }
        gate.reset()
        assertTrue(gate.accept(ocr(full)).isEmpty())
    }
    @Test fun oldEvidenceExpiresAndTruncatedSubstringsWaitForFullReading() {
        var now = 0L
        val gate = TemporalCapture(clock = { now })
        repeat(2) { assertTrue(gate.accept(ocr(full)).isEmpty()) }
        now = 3000
        assertTrue(gate.accept(ocr(full)).isEmpty())
        assertTrue(gate.accept(ocr(full)).isEmpty())
        assertEquals(listOf(full), gate.accept(ocr(full)))
        val long = full + "0"
        val partial = TemporalCapture()
        assertTrue(partial.accept(ocr(long)).isEmpty())
        repeat(3) { assertTrue(partial.accept(ocr(full)).isEmpty()) }
        assertTrue(partial.accept(ocr(long)).isEmpty())
        assertEquals(listOf(long), partial.accept(ocr(long)))
    }
    @Test fun transientConflictsNeverInventDigitsAndStableConflictsRemainSelectable() {
        val other = "8996603421269968999"
        val gate = TemporalCapture()
        assertTrue(gate.accept(ocr(full)).isEmpty())
        assertTrue(gate.accept(ocr(other)).isEmpty())
        assertTrue(gate.accept(ocr(full)).isEmpty())
        assertTrue(gate.accept(ocr(other)).isEmpty())
        assertEquals(setOf(full, other), gate.accept(ocr(full)).toSet())
        val bars = TemporalCapture()
        val conflict = SerialRecognition.read(listOf(full), listOf(other))
        assertTrue(bars.accept(conflict).isEmpty())
        assertEquals(setOf(full, other), bars.accept(conflict).toSet())
    }
    @Test fun sessionExclusionsAndShortOcrCannotFreeze() {
        val gate = TemporalCapture()
        repeat(12) { assertTrue(gate.accept(ocr(full, full.dropLast(2), "8985"), setOf(full)).isEmpty()) }
    }
    @Test fun nonstandardOcrLengthsRemainAvailableAfterStrongerConsensus() {
        val gate = TemporalCapture()
        val unusual = "00012345678901234567890123"
        repeat(4) { assertTrue(gate.accept(ocr(unusual)).isEmpty()) }
        assertEquals(listOf(unusual), gate.accept(ocr(unusual)))
        val confirmed = TemporalCapture()
        repeat(10) {
            assertTrue(confirmed.accept(ocr(full, full.dropLast(1)), setOf(full)).isEmpty())
        }
    }
    @Test fun groupModeSupportsShortAndLeadingZeroCodesWithoutSimRules() {
        val gate = TemporalCapture(group = true)
        val reading = SerialRecognition.readGroup(listOf("00055"), listOf("00055"))
        assertTrue(gate.accept(reading).isEmpty())
        assertEquals(listOf("00055"), gate.accept(reading))
        assertEquals(listOf("00055"), SerialRecognition.readGroup(emptyList(), listOf("00055")).candidates)
    }
    @Test fun seriesIsInclusiveExactAndPreservesZeros() {
        val rows = SerialSeries.generate(full, "8999")
        assertEquals(15, rows.size); assertEquals(full, rows.first())
        assertEquals("8996603421269968999", rows.last())
        assertEquals(listOf("00000998", "00000999", "00001000"), SerialSeries.generate("00000998", "1000"))
        val session = ScanSession(listOf(full))
        assertEquals(14, rows.count { session.add(it) })
        assertEquals(15, session.serials.size)
        assertEquals(listOf(full), SerialSeries.generate(full, "8985"))
    }
    @Test fun invalidOrOversizedRangesAreRejected() {
        for ((start, end) in listOf(full to "8984", full to "12", full to "abcd",
            "00000000" to "0500", "abc" to "8999", "9999" to "0000")) {
            assertTrue(runCatching { SerialSeries.generate(start, end) }.isFailure)
        }
        assertEquals(500, SerialSeries.generate("00000000", "0499").size)
    }
}
