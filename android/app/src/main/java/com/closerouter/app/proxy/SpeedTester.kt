package com.closerouter.app.proxy

import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.stats.TokenEstimator
import com.closerouter.app.stats.UsageParser
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一键测速：向当前提供方发固定小请求（"Say OK"，max_tokens=16，stream=true） */
object SpeedTester {

    data class Result(
        val ok: Boolean,
        val ttftMs: Long = -1,
        val tokPerSec: Double = -1.0,
        val totalMs: Long = -1,
        val outputTokens: Long = 0,
        val error: String = ""
    )

    internal fun buildRequest(profile: ProviderProfile): okhttp3.Request {
        val path = when (profile.apiType) {
            "openai" -> "/v1/chat/completions"
            "responses" -> "/v1/responses"
            "anthropic" -> "/v1/messages"
            else -> throw IllegalArgumentException("不支持的 API 格式：${profile.apiType}")
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(profile.model))
            put("stream", JsonPrimitive(true))
            when (profile.apiType) {
                "openai", "anthropic" -> {
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("Say OK"))
                        })
                    })
                    put("max_tokens", JsonPrimitive(16))
                }
                "responses" -> {
                    put("input", JsonPrimitive("Say OK"))
                    put("max_output_tokens", JsonPrimitive(16))
                }
            }
        }
        return Upstream.buildRequest(profile, path, body.toString())
    }

    suspend fun run(profile: ProviderProfile): Result = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        fun ms() = (System.nanoTime() - startNs) / 1_000_000
        try {
            val req = buildRequest(profile)
            Upstream.client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) {
                    val errBody = resp.body?.string()?.take(200) ?: ""
                    return@withContext Result(false, totalMs = ms(), error = "HTTP ${resp.code}: $errBody")
                }
                val tracker = UsageParser.SseTracker()
                val src = resp.body?.byteStream()
                    ?: return@withContext Result(false, totalMs = ms(), error = "空响应")
                var ttft = -1L
                val buf = ByteArray(4096)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    if (ttft < 0) ttft = ms()
                    tracker.feed(buf, 0, n)
                }
                tracker.finish()
                val total = ms()
                val outTokens = tracker.usage()?.outputTokens
                    ?: TokenEstimator.estimate(tracker.outputText())
                val genMs = if (ttft >= 0) (total - ttft).coerceAtLeast(1) else total.coerceAtLeast(1)
                Result(
                    ok = true,
                    ttftMs = ttft,
                    tokPerSec = outTokens * 1000.0 / genMs,
                    totalMs = total,
                    outputTokens = outTokens
                )
            }
        } catch (e: Exception) {
            Result(false, totalMs = ms(), error = e.message ?: e.javaClass.simpleName)
        }
    }
}
