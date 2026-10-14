package com.closerouter.app

import com.closerouter.app.stats.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun empty() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun english_roughly4charsPerToken() {
        // 40 个英文字符 ≈ 10 token
        val text = "a".repeat(40)
        assertEquals(10, TokenEstimator.estimate(text))
    }

    @Test
    fun chinese_roughly1p5CharsPerToken() {
        // 15 个汉字 ≈ 10 token
        val text = "汉".repeat(15)
        assertEquals(10, TokenEstimator.estimate(text))
    }

    @Test
    fun mixed() {
        // 8 英文(2 tok) + 6 中文(4 tok) ≈ 6
        val v = TokenEstimator.estimate("abcdefgh汉字汉字汉字")
        assertTrue(v in 5..7)
    }

    @Test
    fun shortTextAtLeastOne() {
        assertTrue(TokenEstimator.estimate("OK") >= 1)
        assertTrue(TokenEstimator.estimate("好") >= 1)
    }
}
