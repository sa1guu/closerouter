package com.closerouter.app.stats

/** 无 usage 字段时的 token 字符数估算：英文≈4字符/token，中文≈1.5字符/token */
object TokenEstimator {

    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x3000..0x303F ||
            cp in 0xFF00..0xFFEF || cp in 0xF900..0xFAFF
    }

    fun estimate(text: String): Long {
        if (text.isEmpty()) return 0
        var cjk = 0L
        for (c in text) if (isCjk(c)) cjk++
        val other = text.length - cjk
        return kotlin.math.ceil(other / 4.0 + cjk / 1.5).toLong().coerceAtLeast(1)
    }
}
