package com.rb.app.scanner

/** Bounded evidence window. Exact strings only; no digit repair or cross-frame concatenation.
 * Frozen choices are immutable until an explicit action, including during empty/conflicting frames. */
class TemporalCapture(
    private val windowSize: Int = 7,
    private val textHits: Int = 3,
    private val barcodeHits: Int = 2,
    private val group: Boolean = false,
    private val maxGapMillis: Long = 2500,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val frames = ArrayDeque<SerialRecognition.Reading>()
    private var lastFrame: Long? = null
    private var frozen: List<String> = emptyList()
    fun reset() { frames.clear(); frozen = emptyList(); lastFrame = null }
    fun accept(reading: SerialRecognition.Reading, excluded: Set<String> = emptySet()): List<String> {
        if (frozen.isNotEmpty()) return frozen
        val now = clock()
        lastFrame?.let { if (now - it > maxGapMillis) frames.clear() }
        lastFrame = now
        frames.addLast(reading)
        while (frames.size > windowSize) frames.removeFirst()
        val values = frames.flatMap { it.candidates }.distinct().filter { it !in excluded }
        val ready = values.filter { value ->
            val hits = frames.count { value in it.candidates }
            val bars = frames.count { value in it.barcodeCandidates }
            val plausible = group || value.length in 19..20 || bars >= barcodeHits
            plausible && (bars >= barcodeHits || hits >= textHits)
        }
        // A longer observed reading blocks its truncated substring; unrelated strings stay separate.
        val corroborated = if (ready.isEmpty()) emptyList() else (ready + values.filter { v ->
            frames.count { v in it.candidates } >= 2 && (group || v.length in 19..20)
        }).distinct()
        val complete = corroborated.filter { short -> values.none { long ->
            long.length > short.length && long.contains(short) && frames.count { long in it.candidates } >= 1
        } }.sortedWith(compareByDescending<String> { v -> frames.count { v in it.barcodeCandidates } }.thenBy { it })
        if (complete.isNotEmpty()) frozen = complete
        return frozen
    }
}
