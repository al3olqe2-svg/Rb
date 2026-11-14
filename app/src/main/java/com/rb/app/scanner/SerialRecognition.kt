package com.rb.app.scanner

import com.rb.app.data.InputRules

/** Heuristics select candidates, never repair digits. Nonstandard serials remain available for explicit review. */
object SerialRecognition {
    data class Reading(val candidates: List<String>, val automatic: Boolean, val barcode: Boolean, val barcodeCandidates: List<String> = if (barcode) candidates else emptyList())
    private val label = Regex("(?i)^(?:ICCID|ICC\\s*ID|SIM(?:\\s*(?:ID|NO))?|SERIAL(?:\\s*NO)?|S/N)\\s*[:#：-]?\\s*")
    private val longRun = Regex("(?<![A-Za-z0-9])[0-9]{10,64}(?![A-Za-z0-9])")
    fun normalize(raw: String): String = InputRules.code(raw).filterNot { it.isWhitespace() || it == '-' }
    fun manual(raw: String): String? = normalize(raw).takeIf { it.length in 1..128 && InputRules.validSerial(it) }
    private fun candidates(raw: String, barcode: Boolean): List<String> {
        val ascii = InputRules.code(raw)
        val stripped = ascii.replace(label, "")
        val whole = normalize(stripped)
        // Only a whole numeric payload/line may join formatting spaces; never concatenate OCR lines.
        if (whole.length in 10..64 && InputRules.validSerial(whole)) return listOf(whole)
        if (barcode) return emptyList() // URLs, arbitrary product payloads and embedded numbers are not SIM barcodes.
        return longRun.findAll(ascii).map { it.value }.toList()
    }
    fun read(barcodes: List<String>, lines: List<String>): Reading {
        val bars = barcodes.flatMap { candidates(it, true) }.distinct()
        val texts = lines.flatMap { candidates(it, false) }.distinct()
        val selected = (bars + texts).distinct()
        // Conservative automatic subset of ICCID; this is not a restriction on manually reviewed serials.
        val auto = selected.size == 1 && selected.single().let {
            it.startsWith("89") && it.length in 19..20 && luhn(it)
        }
        return Reading(selected, auto, bars.isNotEmpty(), bars)
    }
    fun readGroup(barcodes: List<String>, lines: List<String>): Reading {
        fun numeric(raw: String) = normalize(raw).takeIf { InputRules.validCode(it) }
        val bars = barcodes.mapNotNull(::numeric).distinct()
        val texts = lines.mapNotNull(::numeric).distinct()
        return Reading((bars + texts).distinct(), false, bars.isNotEmpty(), bars)
    }
    fun luhn(value: String): Boolean {
        if (!InputRules.validSerial(value)) return false
        return value.reversed().mapIndexed { index, c ->
            val n = c - '0'
            if (index % 2 == 0) n else (n * 2).let { if (it > 9) it - 9 else it }
        }.sum() % 10 == 0
    }
}

class ScanSession(initial: List<String> = emptyList()) {
    private val values = LinkedHashSet(initial)
    val serials: List<String> get() = values.toList()
    fun add(serial: String): Boolean { require(SerialRecognition.manual(serial) == serial); return values.add(serial) }
    fun remove(serial: String) { values.remove(serial) }
    fun edit(old: String, raw: String): Boolean {
        if (old !in values) return false
        val updated = SerialRecognition.manual(raw) ?: return false
        if (updated != old && updated in values) return false
        val rows = serials.map { if (it == old) updated else it }
        values.clear(); values.addAll(rows)
        return true
    }
}
