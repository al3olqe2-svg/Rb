package com.rb.app

import com.rb.app.scanner.*
import org.junit.Assert.*
import org.junit.Test

class SerialRecognitionTest {
    private val printed = "8996603430263612277" // user-provided representative SIM; never rewrite a check digit
    private fun withCheckDigit(prefix: String) = (0..9).map { prefix + it }.first(SerialRecognition::luhn)
    private val valid = withCheckDigit("899660343026361227")
    @Test fun preservesFullBarcodeAndPrintedOcrWithoutNumericConversion() {
        assertEquals(listOf(printed), SerialRecognition.read(listOf(printed), emptyList()).candidates)
        assertEquals(listOf(printed), SerialRecognition.read(emptyList(), listOf("ICCID: 8996 6034 3026 3612 277")).candidates)
        assertEquals("00012345678901234567890123456789", SerialRecognition.manual("00012345678901234567890123456789"))
        assertEquals("8996603430263612277", SerialRecognition.manual("٨٩٩٦٦٠٣٤٣٠٢٦٣٦١٢٢٧٧"))
    }
    @Test fun unrelatedPayloadAndShortNumbersAreNotAutoAccepted() {
        for (raw in listOf("https://example.com/$valid", "TEL: $valid", "1234", "89966O3430263612277", "")) {
            assertTrue(SerialRecognition.read(listOf(raw), emptyList()).candidates.isEmpty())
        }
        assertFalse(SerialRecognition.read(emptyList(), listOf("0501234567", "2026", "2277")).automatic)
        assertNull(SerialRecognition.manual("89966O3430263612277"))
        assertNull(SerialRecognition.manual("1234.56"))
        assertNull(SerialRecognition.manual(""))
    }
    @Test fun conflictsAndFailedChecksRequireReviewWithoutInventingDigits() {
        val other = withCheckDigit("899660343026361228")
        val conflict = SerialRecognition.read(listOf(valid), listOf(other))
        assertEquals(listOf(valid, other), conflict.candidates)
        assertFalse(conflict.automatic)
        assertFalse(SerialRecognition.read(listOf(printed), emptyList()).automatic)
        assertEquals(listOf("00012345678901234567890123"), SerialRecognition.read(listOf("00012345678901234567890123"), emptyList()).candidates)
        assertFalse(SerialRecognition.read(listOf("00012345678901234567890123"), emptyList()).automatic)
    }
    @Test fun neverJoinsDifferentOcrLinesAndRequiresMultipleFrames() {
        val split = SerialRecognition.read(emptyList(), listOf("899660343", "0263612277"))
        assertFalse(printed in split.candidates)
        assertFalse(split.automatic)
        val barcode = SerialRecognition.read(listOf(valid), listOf(valid))
        assertTrue(barcode.automatic)
        val gate = TemporalCapture()
        assertTrue(gate.accept(barcode).isEmpty()); assertEquals(listOf(valid), gate.accept(barcode))
        gate.reset()
        assertTrue(gate.accept(barcode).isEmpty())
        val ocr = SerialRecognition.read(emptyList(), listOf(valid))
        val textGate = TemporalCapture()
        assertTrue(textGate.accept(ocr).isEmpty()); assertTrue(textGate.accept(ocr).isEmpty())
        assertEquals(listOf(valid), textGate.accept(ocr))
    }
    @Test fun fullSerialControlsSessionIdentityAndEditsAreRevalidated() {
        val first = "00012345678901234567895555"
        val second = "00022345678901234567895555"
        val session = ScanSession()
        assertTrue(session.add(first)); assertFalse(session.add(first)); assertTrue(session.add(second))
        assertEquals(2, session.serials.size)
        assertFalse(session.edit(first, second)); assertFalse(session.edit(first, "123O"))
        assertEquals(listOf(first, second), session.serials)
        assertTrue(session.edit(first, "٠٠٠٩٩٩"))
        assertEquals(listOf("000999", second), ScanSession(session.serials).serials)
    }
}
