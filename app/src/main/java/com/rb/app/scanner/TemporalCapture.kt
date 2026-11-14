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
        fun complete(value: String) = values.none { it.length > value.length && it.contains(value) }
        val completeReady = ready.filter(::complete)
        val corroborated = if (completeReady.isEmpty()) emptyList() else (completeReady + values.filter { v ->
            frames.count { v in it.candidates } >= 2 && (group || v.length in 19..20)
        }).distinct()
        val choices = corroborated.filter(::complete).sortedWith(compareByDescending<String> { v -> frames.count { v in it.barcodeCandidates } }.thenBy { it })
        if (choices.isNotEmpty()) frozen = choices
        return frozen
    }
}
