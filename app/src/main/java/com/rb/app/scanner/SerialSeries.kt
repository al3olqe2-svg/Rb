package com.rb.app.scanner

import java.math.BigInteger

object SerialSeries {
    const val LIMIT = 500
    fun generate(rawFirst: String, rawLast4: String): List<String> {
        val first = SerialRecognition.manual(rawFirst)
        require(first != null && first.length >= 4) { "أدخل الرقم الأول كاملًا (4 إلى 128 خانة)" }
        val last4 = com.rb.app.data.InputRules.code(rawLast4)
        require(last4.length == 4 && last4.all { it in '0'..'9' }) { "أدخل آخر أربع خانات للنهاية" }
        val end = first.dropLast(4) + last4
        val startValue = BigInteger(first)
        val count = BigInteger(end).subtract(startValue).add(BigInteger.ONE)
        require(count.signum() > 0) { "النهاية أقل من البداية؛ لا يمكن تخمين تجاوز آخر أربع خانات" }
        require(count <= BigInteger.valueOf(LIMIT.toLong())) { "الحد الأقصى 500 شريحة للعملية الواحدة" }
        return List(count.toInt()) { startValue.add(BigInteger.valueOf(it.toLong())).toString().padStart(first.length, '0') }
    }
}
