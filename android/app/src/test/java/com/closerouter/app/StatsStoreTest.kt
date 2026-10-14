package com.closerouter.app

import com.closerouter.app.stats.StatsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StatsStoreTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance(TimeZone.getDefault()).apply { set(y, mo - 1, d, h, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun accumulateSameDay() {
        var now = millis(2026, 10, 14)
        val s = StatsStore(MapKv()) { now }
        s.onRequestEnd(10, 20, 100, 1000)
        s.onRequestEnd(5, 5, 200, 500)
        val t = s.today.value
        assertEquals(15, t.inputTokens)
        assertEquals(25, t.outputTokens)
        assertEquals(2, t.requests)
        assertEquals(200, s.lastTtftMs.value)
        assertEquals(5 * 1000.0 / 300, s.lastTokPerSec.value, 0.01)
    }

    @Test
    fun rolloverAcrossDays() {
        var now = millis(2026, 10, 14, 23)
        val kv = MapKv()
        val s = StatsStore(kv) { now }
        s.onRequestEnd(10, 10, 50, 100)
        assertEquals(1, s.today.value.requests)

        // 跨天：重新加载（模拟进程重启）→ 今日计数清零
        now = millis(2026, 10, 15, 1)
        val s2 = StatsStore(kv) { now }
        assertEquals(0, s2.today.value.requests)
        assertEquals(0, s2.today.value.inputTokens)
        // 新一天继续累计
        s2.onRequestEnd(3, 4, 10, 100)
        assertEquals(1, s2.today.value.requests)
        assertEquals(3, s2.today.value.inputTokens)
        assertEquals(4, s2.today.value.outputTokens)
    }

    @Test
    fun persistenceSurvivesReload() {
        val now = millis(2026, 10, 14)
        val kv = MapKv()
        StatsStore(kv) { now }.onRequestEnd(7, 8, 10, 100)
        val s = StatsStore(kv) { now }
        assertEquals(7, s.today.value.inputTokens)
        assertEquals(8, s.today.value.outputTokens)
        assertEquals(1, s.today.value.requests)
    }

    @Test
    fun nonStreamTokPerSecUsesTotalDuration() {
        val s = StatsStore(MapKv()) { millis(2026, 10, 14) }
        s.onRequestEnd(0, 10, -1, 2000) // ttftMs = -1 表示非流式
        assertEquals(5.0, s.lastTokPerSec.value, 0.01)
        assertEquals(-1L, s.lastTtftMs.value)
    }
}
