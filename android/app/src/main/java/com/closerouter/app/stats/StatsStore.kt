package com.closerouter.app.stats

import com.closerouter.app.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 统计接收器：转发核心在每个请求生命周期回调 */
interface StatsSink {
    fun onRequestStart()
    fun onFirstByte()
    fun onRequestEnd(inputTokens: Long, outputTokens: Long, ttftMs: Long, durationMs: Long)
}

/** 今日 token / 请求数按日期持久化（跨天自动清零），TTFT/tok-s 为最近一次请求的实时值 */
class StatsStore(
    private val kv: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis
) : StatsSink {

    data class DayStats(val date: String, val inputTokens: Long, val outputTokens: Long, val requests: Long)

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val _today = MutableStateFlow(loadRolled())
    val today: StateFlow<DayStats> = _today

    private val _totalTokens = MutableStateFlow(
        kv.getString(KEY_TOTAL)?.toLongOrNull() ?: (_today.value.inputTokens + _today.value.outputTokens)
    )
    val totalTokens: StateFlow<Long> = _totalTokens

    private val _lastTtftMs =MutableStateFlow(-1L)
    val lastTtftMs: StateFlow<Long> = _lastTtftMs

    private val _lastTokPerSec = MutableStateFlow(-1.0)
    val lastTokPerSec: StateFlow<Double> = _lastTokPerSec

    private val _activeRequests = MutableStateFlow(0)
    val activeRequests: StateFlow<Int> = _activeRequests

    private fun todayStr(): String = fmt.format(Date(clock()))

    private fun load(): DayStats = DayStats(
        date = kv.getString(KEY_DATE) ?: "",
        inputTokens = kv.getString(KEY_IN)?.toLongOrNull() ?: 0,
        outputTokens = kv.getString(KEY_OUT)?.toLongOrNull() ?: 0,
        requests = kv.getString(KEY_REQ)?.toLongOrNull() ?: 0
    )

    /** 跨天重置：日期不匹配就清零重记 */
    private fun loadRolled(): DayStats {
        val s = load()
        val today = todayStr()
        return if (s.date == today) s else DayStats(today, 0, 0, 0).also { persist(it) }
    }

    private fun persist(s: DayStats) {
        kv.putString(KEY_DATE, s.date)
        kv.putString(KEY_IN, s.inputTokens.toString())
        kv.putString(KEY_OUT, s.outputTokens.toString())
        kv.putString(KEY_REQ, s.requests.toString())
    }

    @Synchronized
    override fun onRequestStart() {
        _activeRequests.value += 1
    }

    override fun onFirstByte() {}

    @Synchronized
    override fun onRequestEnd(inputTokens: Long, outputTokens: Long, ttftMs: Long, durationMs: Long) {
        _activeRequests.value = (_activeRequests.value - 1).coerceAtLeast(0)
        val rolled = loadRolled()
        val next = rolled.copy(
            inputTokens = rolled.inputTokens + inputTokens,
            outputTokens = rolled.outputTokens + outputTokens,
            requests = rolled.requests + 1
        )
        persist(next)
        _today.value = next
        _totalTokens.value += inputTokens + outputTokens
        kv.putString(KEY_TOTAL, _totalTokens.value.toString())
        if (ttftMs >= 0) _lastTtftMs.value = ttftMs
        // tok/s：输出 tokens / 生成耗时（TTFT 之后的流式阶段；非流式用总耗时）
        val genMs = if (ttftMs >= 0) (durationMs - ttftMs).coerceAtLeast(1) else durationMs.coerceAtLeast(1)
        if (outputTokens > 0) _lastTokPerSec.value = outputTokens * 1000.0 / genMs
    }

    companion object {
        const val KEY_DATE = "stats_date"
        const val KEY_IN = "stats_input_tokens"
        const val KEY_OUT = "stats_output_tokens"
        const val KEY_REQ = "stats_requests"
        const val KEY_TOTAL = "stats_total_tokens"
    }
}
