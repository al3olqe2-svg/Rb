package com.rb.app

import com.rb.app.scanner.*
import com.rb.app.scanner.GroupLabelScanner.Observation
import com.rb.app.scanner.GroupLabelScanner.Point
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GroupLabelScannerTest {
    private val upper = "8996603430263612700"
    private val lower = "8996603430263612799"
    private fun box(value: String, x: Double, y: Double, w: Double = 300.0, h: Double = 35.0) =
        Observation(value, listOf(Point(x,y), Point(x+w,y), Point(x+w,y+h), Point(x,y+h)), 0.0)
    private fun frame(top: String = upper, bottom: String = lower, angle: Double = 0.0): SerialRecognition.Reading {
        fun rotate(o: Observation) = o.copy(orientationDegrees = angle, corners = o.corners.map {
            val a = angle * PI / 180
            // Include mild shear to represent an oblique camera view.
            val x = it.x + it.y * .12
            Point(x * cos(a) - it.y * sin(a) + 1000, x * sin(a) + it.y * cos(a) + 1000)
        })
        return GroupLabelScanner.read(listOf(box(bottom, 100.0, 300.0), box(top, 100.0, 100.0)).map(::rotate),
            listOf(box(top,100.0,145.0,300.0,20.0), box(bottom,100.0,345.0,300.0,20.0), box("1234",0.0,0.0)).map(::rotate))
    }
    @Test fun topIsGeometricNotSmallestAndSurvivesRotationAndObliqueView() {
        for (angle in listOf(0.0, 15.0, 90.0, 180.0, 270.0, -25.0)) {
            assertEquals(upper, frame(angle = angle).groupUpperCandidate)
            assertEquals(lower, frame(top = lower, bottom = upper, angle = angle).groupUpperCandidate)
            assertEquals(setOf(upper, lower), frame(angle = angle).candidates.toSet())
        }
    }
    @Test fun missingOrientationSingleBarcodeDisagreementAndUnrelatedNumbersRequireReview() {
        val bars = listOf(box(upper,100.0,100.0),box(lower,100.0,300.0))
        assertNull(GroupLabelScanner.read(bars, emptyList()).groupUpperCandidate)
        assertNull(GroupLabelScanner.read(bars, listOf(box(upper,100.0,145.0),box(lower,100.0,345.0)).map { it.copy(orientationDegrees = null) }).groupUpperCandidate)
        assertNull(GroupLabelScanner.read(bars.take(1), listOf(box(upper,100.0,145.0))).groupUpperCandidate)
        assertNull(GroupLabelScanner.read(bars, listOf(box(lower,100.0,145.0),box(upper,100.0,345.0))).groupUpperCandidate)
        assertNull(GroupLabelScanner.read(bars, listOf(box(upper,100.0,145.0),box("8996603430263612888",100.0,345.0))).groupUpperCandidate)
        assertTrue(GroupLabelScanner.read(listOf(box("12345",0.0,0.0)), listOf(box("2026",0.0,0.0))).candidates.isEmpty())
    }
    @Test fun exactCandidatesFreezeAndPreferenceRequiresRepeatedEvidence() {
        val gate = GroupLabelCapture()
        repeat(2) { assertNull(gate.accept(frame())) }
        val selected = gate.accept(frame())!!
        assertEquals(upper, selected.upper); assertEquals(listOf(upper,lower), selected.candidates)
        repeat(10) { assertEquals(selected, gate.accept(frame(top = lower, bottom = upper))) }
        gate.reset()
        repeat(2) { assertNull(gate.accept(frame(top = lower, bottom = upper))) }
        assertEquals(lower, gate.accept(frame(top = lower, bottom = upper))!!.upper)
    }
    @Test fun ambiguousPairAndSingleNumberFreezeForManualChoiceAndEvidenceExpires() {
        val gate = GroupLabelCapture()
        val ambiguous = GroupLabelScanner.read(listOf(Observation(upper, emptyList()), Observation(lower, emptyList())), emptyList())
        repeat(2) { assertNull(gate.accept(ambiguous)) }
        val selection = gate.accept(ambiguous)!!
        assertNull(selection.upper); assertEquals(listOf(upper,lower), selection.candidates)
        assertEquals(selection, gate.accept(frame()))
        var now = 0L
        val single = GroupLabelCapture { now }
        val reading = ambiguous.copy(candidates = listOf(upper))
        repeat(4) { assertNull(single.accept(reading)) }
        now = 3000
        repeat(4) { assertNull(single.accept(reading)) }
        assertNull(single.accept(reading)!!.upper)
    }
    @Test fun conflictingSpatialEvidenceNeverAutoSelects() {
        val gate = GroupLabelCapture()
        assertNull(gate.accept(frame()))
        assertNull(gate.accept(frame(top = lower, bottom = upper)))
        assertNull(gate.accept(frame())!!.upper)
    }
}
