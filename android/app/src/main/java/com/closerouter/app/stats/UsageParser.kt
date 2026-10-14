package com.closerouter.app.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream

/**
 * 三种 API 形态（openai chat/completions、openai responses、anthropic messages）
 * 的 usage 解析与输出文本提取。优先用 usage 精确计数；没有时由调用方按字符估算。
 */
object UsageParser {

    data class Usage(val inputTokens: Long?, val outputTokens: Long?)

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.num(vararg keys: String): Long? {
        for (k in keys) this[k]?.jsonPrimitive?.longOrNull?.let { return it }
        return null
    }

    /** 从单个 JSON 对象里找 usage，兼容全部形态 */
    private fun usageIn(obj: JsonObject): Usage? {
        // chat/completions: usage.{prompt_tokens, completion_tokens}
        (obj["usage"] as? JsonObject)?.let { u ->
            val inp = u.num("prompt_tokens", "input_tokens")
            val out = u.num("completion_tokens", "output_tokens")
            if (inp != null || out != null) return Usage(inp, out)
        }
        // responses API SSE: response.completed 事件，usage 在 response 里
        (obj["response"] as? JsonObject)?.let { r ->
            (r["usage"] as? JsonObject)?.let { u ->
                val inp = u.num("input_tokens", "prompt_tokens")
                val out = u.num("output_tokens", "completion_tokens")
                if (inp != null || out != null) return Usage(inp, out)
            }
        }
        // anthropic message_start: usage 在 message 里（含初始 input_tokens）
        (obj["message"] as? JsonObject)?.let { m ->
            (m["usage"] as? JsonObject)?.let { u ->
                val inp = u.num("input_tokens")
                val out = u.num("output_tokens")
                if (inp != null || out != null) return Usage(inp, out)
            }
        }
        return null
    }

    /** 从单个 SSE data JSON 里提取增量输出文本 */
    private fun deltaText(obj: JsonObject): String {
        // chat/completions: choices[0].delta.content
        runCatching {
            val choices = obj["choices"]?.jsonArray
            if (choices != null && choices.size > 0) {
                val delta = choices[0].jsonObject["delta"] as? JsonObject
                val c = delta?.get("content")?.jsonPrimitive?.content
                if (!c.isNullOrEmpty()) return c
            }
        }
        // responses: response.output_text.delta 事件
        if (obj["type"]?.jsonPrimitive?.content == "response.output_text.delta") {
            obj["delta"]?.jsonPrimitive?.content?.let { return it }
        }
        // anthropic: content_block_delta → delta.text
        if (obj["type"]?.jsonPrimitive?.content == "content_block_delta") {
            ((obj["delta"] as? JsonObject)?.get("text"))?.jsonPrimitive?.content?.let { return it }
        }
        return ""
    }

    /** 非流式响应：usage 精确计数 */
    fun fromNonStreamUsage(body: String): Usage? = parse(body)?.let { usageIn(it) }

    /** 非流式响应：提取输出文本（usage 缺失时估算用） */
    fun nonStreamOutputText(body: String): String {
        val obj = parse(body) ?: return ""
        // chat/completions: choices[0].message.content
        runCatching {
            val choices = obj["choices"]?.jsonArray
            if (choices != null && choices.size > 0) {
                val msg = choices[0].jsonObject["message"] as? JsonObject
                val c = msg?.get("content")?.jsonPrimitive?.content
                if (!c.isNullOrEmpty()) return c
            }
        }
        // anthropic / responses: content[] / output[] 里的 text 块
        val sb = StringBuilder()
        runCatching {
            obj["content"]?.jsonArray?.forEach { b ->
                (b as? JsonObject)?.get("text")?.jsonPrimitive?.content?.let(sb::append)
            }
        }
        runCatching {
            obj["output"]?.jsonArray?.forEach { item ->
                (item as? JsonObject)?.get("content")?.jsonArray?.forEach { c ->
                    (c as? JsonObject)?.get("text")?.jsonPrimitive?.content?.let(sb::append)
                }
            }
        }
        if (sb.isNotEmpty()) return sb.toString()
        return obj["output_text"]?.jsonPrimitive?.content ?: ""
    }

    /**
     * SSE 流增量追踪器：逐行解析 data: 负载，累计 usage 与输出文本。
     * 按 \n 字节切行（\n 在 UTF-8 中不会出现在多字节字符内部，安全）。
     */
    class SseTracker {
        private val pending = ByteArrayOutputStream()
        private val outputText = StringBuilder()
        var inputTokens: Long? = null; private set
        var outputTokens: Long? = null; private set

        @Synchronized
        fun feed(bytes: ByteArray, off: Int, len: Int) {
            pending.write(bytes, off, len)
            val buf = pending.toByteArray()
            var start = 0
            for (i in buf.indices) {
                if (buf[i] == '\n'.code.toByte()) {
                    processLine(String(buf, start, i - start, Charsets.UTF_8))
                    start = i + 1
                }
            }
            pending.reset()
            if (start < buf.size) pending.write(buf, start, buf.size - start)
        }

        @Synchronized
        fun finish() {
            val tail = pending.toByteArray()
            if (tail.isNotEmpty()) {
                processLine(String(tail, Charsets.UTF_8))
                pending.reset()
            }
        }

        @Synchronized
        fun outputText(): String = outputText.toString()

        fun usage(): Usage? =
            if (inputTokens == null && outputTokens == null) null else Usage(inputTokens, outputTokens)

        private fun processLine(raw: String) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith("data:")) return
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return
            val obj = parse(data) ?: return
            usageIn(obj)?.let { u ->
                // 后者覆盖前者：chat 末 chunk / responses completed / anthropic message_delta 都是全量终值
                u.inputTokens?.let { inputTokens = it }
                u.outputTokens?.let { outputTokens = it }
            }
            outputText.append(deltaText(obj))
        }
    }
}
