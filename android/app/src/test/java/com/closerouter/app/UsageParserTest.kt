package com.closerouter.app

import com.closerouter.app.stats.UsageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsageParserTest {

    // ---- chat/completions ----

    @Test
    fun chatNonStreamUsage() {
        val body = """{"id":"x","choices":[{"message":{"role":"assistant","content":"你好世界"}}],"usage":{"prompt_tokens":12,"completion_tokens":7}}"""
        val u = UsageParser.fromNonStreamUsage(body)
        assertNotNull(u)
        assertEquals(12L, u!!.inputTokens)
        assertEquals(7L, u.outputTokens)
    }

    @Test
    fun chatSseUsageLastChunk() {
        val sse = """
            data: {"choices":[{"delta":{"content":"你"}}]}

            data: {"choices":[{"delta":{"content":"好"}}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}

            data: [DONE]

        """.trimIndent()
        val t = UsageParser.SseTracker()
        val bytes = sse.toByteArray(Charsets.UTF_8)
        // 模拟分块到达，块边界切在多字节字符中间也要正确
        var i = 0
        while (i < bytes.size) {
            val n = minOf(7, bytes.size - i)
            t.feed(bytes, i, n)
            i += n
        }
        t.finish()
        assertEquals(10L, t.usage()!!.inputTokens)
        assertEquals(2L, t.usage()!!.outputTokens)
        assertEquals("你好", t.outputText())
    }

    @Test
    fun chatSseNoUsage_fallsBackToText() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n"
        val t = UsageParser.SseTracker()
        val b = sse.toByteArray()
        t.feed(b, 0, b.size)
        t.finish()
        assertNull(t.usage())
        assertEquals("Hello", t.outputText())
    }

    // ---- responses ----

    @Test
    fun responsesCompletedEvent() {
        val sse = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"OK"}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":20,"output_tokens":1}}}

        """.trimIndent()
        val t = UsageParser.SseTracker()
        val b = sse.toByteArray()
        t.feed(b, 0, b.size)
        t.finish()
        assertEquals(20L, t.usage()!!.inputTokens)
        assertEquals(1L, t.usage()!!.outputTokens)
        assertEquals("OK", t.outputText())
    }

    @Test
    fun responsesNonStreamUsage() {
        val body = """{"id":"r1","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}],"usage":{"input_tokens":9,"output_tokens":3}}"""
        val u = UsageParser.fromNonStreamUsage(body)
        assertEquals(9L, u!!.inputTokens)
        assertEquals(3L, u.outputTokens)
        assertEquals("OK", UsageParser.nonStreamOutputText(body))
    }

    // ---- anthropic messages ----

    @Test
    fun anthropicSseUsage() {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":15,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"你好"}}

            event: message_delta
            data: {"type":"message_delta","usage":{"output_tokens":4}}

        """.trimIndent()
        val t = UsageParser.SseTracker()
        val b = sse.toByteArray()
        t.feed(b, 0, b.size)
        t.finish()
        assertEquals(15L, t.usage()!!.inputTokens)
        assertEquals(4L, t.usage()!!.outputTokens)
        assertEquals("你好", t.outputText())
    }

    @Test
    fun anthropicNonStreamUsage() {
        val body = """{"id":"m1","content":[{"type":"text","text":"OK"}],"usage":{"input_tokens":11,"output_tokens":2}}"""
        val u = UsageParser.fromNonStreamUsage(body)
        assertEquals(11L, u!!.inputTokens)
        assertEquals(2L, u.outputTokens)
        assertEquals("OK", UsageParser.nonStreamOutputText(body))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(UsageParser.fromNonStreamUsage("not json"))
        assertNull(UsageParser.fromNonStreamUsage("""{"foo":1}"""))
    }
}
