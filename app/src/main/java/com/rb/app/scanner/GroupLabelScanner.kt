package com.rb.app.scanner

import kotlin.math.*

/** Geometry is in ML Kit's rotation-corrected image space, never raw sensor coordinates.
 * The directed OCR line angle defines the label's reading axes, including sideways/upside-down labels.
 * Missing orientation, disagreement or partial labels always require manual review. */
object GroupLabelScanner {
    data class Point(val x: Double, val y: Double)
    data class Observation(val raw: String, val corners: List<Point>, val orientationDegrees: Double? = null)
    private data class Located(val value: String, val x: Double, val y: Double, val width: Double, val height: Double)
    private fun number(raw: String): String? = SerialRecognition.normalize(raw)
        .takeIf { it.length in 18..22 && it.all { c -> c in '0'..'9' } }

    fun read(barcodes: List<Observation>, lines: List<Observation>): SerialRecognition.Reading {
        val bars = barcodes.mapNotNull { number(it.raw) }.distinct()
        val texts = lines.mapNotNull { number(it.raw) }.distinct()
        val candidates = (bars + texts).distinct()
        val numericLines = lines.filter { number(it.raw) != null && it.corners.size == 4 }
        val axes = numericLines.mapNotNull { line ->
            val angle = line.orientationDegrees?.takeIf { it.isFinite() } ?: return@mapNotNull null
            val radians = angle * PI / 180
            Point(cos(radians), sin(radians))
        }
        val axis = axes.firstOrNull()
        var preferred: String? = null
        if (axis != null && axes.size == numericLines.size && axes.all { it.x * axis.x + it.y * axis.y > .94 }) {
            fun locate(observation: Observation): Located? {
                val value = number(observation.raw) ?: return null
                if (observation.corners.size != 4) return null
                val xs = observation.corners.map { it.x * axis.x + it.y * axis.y }
                val ys = observation.corners.map { -it.x * axis.y + it.y * axis.x }
                return Located(value, xs.average(), ys.average(), xs.max() - xs.min(), ys.max() - ys.min())
            }
            val locatedBars = barcodes.mapNotNull(::locate)
            val locatedText = numericLines.mapNotNull(::locate)
            // Require one spatially adjacent exact printed match per barcode. No numeric sort.
            val matched = locatedBars.filter { bar -> locatedText.any { text ->
                text.value == bar.value && abs(text.x - bar.x) < max(bar.width, text.width) * .6 &&
                    abs(text.y - bar.y) < max(bar.height, text.height) * 3 + bar.width * .2
            } }
            val ordered = matched.sortedBy { it.y }
            if (ordered.size == 2 && ordered.map { it.value }.distinct().size == 2 &&
                candidates.toSet() == ordered.map { it.value }.toSet()) {
                val top = ordered[0]; val bottom = ordered[1]
                val topText = locatedText.singleOrNull { it.value == top.value }
                val bottomText = locatedText.singleOrNull { it.value == bottom.value }
                if (topText != null && bottomText != null &&
                    bottom.y - top.y > max(top.height, bottom.height) * 1.2 &&
                    bottomText.y - topText.y > max(topText.height, bottomText.height) * 1.2 &&
                    abs(top.x - bottom.x) < min(top.width, bottom.width) * .6 &&
                    abs((topText.y - top.y) - (bottomText.y - bottom.y)) < max(top.height, bottom.height)) {
                    preferred = top.value
                }
            }
        }
        return SerialRecognition.Reading(candidates, preferred != null, bars.isNotEmpty(), bars, preferred)
    }
}

/** Freeze the whole candidate set; preference needs repeated geometric agreement, not just hits. */
class GroupLabelCapture(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Selection(val candidates: List<String>, val upper: String?)
    private val frames = ArrayDeque<SerialRecognition.Reading>()
    private var last: Long? = null
    private var frozen: Selection? = null
    fun reset() { frames.clear(); last = null; frozen = null }
    fun accept(reading: SerialRecognition.Reading): Selection? {
        frozen?.let { return it }
        val now = clock()
        if (last?.let { now - it > 2500 } == true) frames.clear()
        last = now
        frames.addLast(reading)
        while (frames.size > 7) frames.removeFirst()
        val observed = frames.flatMap { it.candidates }.distinct()
        val stable = observed.filter { v -> frames.count { v in it.candidates } >= 3 }
        if (stable.isEmpty()) return null
        // Give a partial single-number view more time to expose the other row.
        if (stable.size == 1 && frames.count { stable.single() in it.candidates } < 5) return null
        val upper = stable.singleOrNull { v ->
            frames.count { it.groupUpperCandidate == v && it.candidates.toSet() == stable.toSet() } >= 3 &&
                frames.none { it.groupUpperCandidate != null && it.groupUpperCandidate != v }
        }
        frozen = Selection(if (upper == null) stable else listOf(upper) + stable.filter { it != upper }, upper)
        return frozen
    }
}
