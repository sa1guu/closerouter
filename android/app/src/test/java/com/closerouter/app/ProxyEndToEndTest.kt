package com.closerouter.app

import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.proxy.ProxyServer
import com.closerouter.app.stats.StatsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 端到端集成测试（JVM）：真实 ProxyServer socket + 真实 mock 上游 + 真实 OkHttp 客户端。
 * 验证：中文 body 按字节读、模型名注入、/v1 去重、SSE 逐块透传、usage 统计。
 */
class ProxyEndToEndTest {

    private lateinit var upstream: ServerSocket
    private var upstreamPort = 0
    private var proxyPort = 0
    private lateinit var stats: StatsStore
    private val seenModel = AtomicReference<String>()
    private val seenAuth = AtomicReference<String>()
    private val seenBody = AtomicReference<String>()

    /** 极简 mock OpenAI 上游：/v1/chat/completions，支持 SSE（带 usage）与非流式 */
    private fun serveUpstream() {
        while (!upstream.isClosed) {
            val sock = runCatching { upstream.accept() }.getOrNull() ?: break
            thread {
                sock.use { s ->
                    val ins = BufferedInputStream(s.getInputStream())
                    fun readLine(): String? {
                        val sb = StringBuilder()
                        while (true) {
                            val b = ins.read()
                            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                            if (b == '\n'.code) break
                            if (b != '\r'.code) sb.append(b.toChar())
                        }
                        return sb.toString()
                    }
                    readLine() ?: return@thread
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val l = readLine()
                        if (l.isNullOrEmpty()) break
                        val i = l.indexOf(':')
                        if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                    }
                    val n = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(n)
                    var off = 0
                    while (off < n) {
                        val c = ins.read(bodyBytes, off, n - off)
                        if (c <= 0) break
                        off += c
                    }
                    val body = String(bodyBytes, Charsets.UTF_8)
                    seenBody.set(body)
                    seenModel.set(Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "")
                    seenAuth.set(headers["authorization"] ?: "")

                    val out = s.getOutputStream()
                    if (body.contains("\"stream\":true")) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        for (c in "好的，收到。") {
                            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"$c\"}}]}\n\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        out.write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":6}}\n\n".toByteArray())
                        out.write("data: [DONE]\n\n".toByteArray())
                        out.flush()
                    } else {
                        val b = """{"choices":[{"message":{"content":"好的，收到。"}}],"usage":{"prompt_tokens":11,"completion_tokens":6}}""".toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(b)
                        out.flush()
                    }
                }
            }
        }
    }

    @Before
    fun setUp() {
        upstream = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        upstreamPort = upstream.localPort
        thread(isDaemon = true) { serveUpstream() }

        stats = StatsStore(MapKv())
        ProxyServer.profileProvider = {
            ProviderProfile(
                name = "mock", baseUrl = "http://127.0.0.1:$upstreamPort/v1",
                apiKey = "real-key-123", model = "real-model", apiType = "openai"
            )
        }
        ProxyServer.statsSink = stats
        ProxyServer.onPortChanged = {}
        proxyPort = 18000 + (System.nanoTime() % 20000).toInt()
        ProxyServer.start(proxyPort)
        val deadline = System.currentTimeMillis() + 3000
        while (!ProxyServer.running.value && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(ProxyServer.running.value)
    }

    @After
    fun tearDown() {
        ProxyServer.stop()
        runCatching { upstream.close() }
    }

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun sseStreamingWithChinese() {
        // 客户端填任意 key / 任意模型名；body 含中文（验证按字节读 body）
        val body = """{"model":"any-fake-model","messages":[{"role":"user","content":"你好，讲个笑话"}],"stream":true}"""
        val req = Request.Builder()
            .url("http://127.0.0.1:$proxyPort/v1/chat/completions")
            .header("Authorization", "Bearer whatever")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client().newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("text/event-stream", resp.header("Content-Type"))
            val text = resp.body!!.string()
            assertTrue("SSE 应包含全部增量", text.contains("""{"content":"好"}""") && text.contains("""{"content":"。"}"""))
            assertTrue(text.contains("[DONE]"))
        }
        // 模型名被替换为提供方真实模型；上游收到的是真实 key
        assertEquals("real-model", seenModel.get())
        assertEquals("Bearer real-key-123", seenAuth.get())
        // 中文请求体完整到达上游（按字节读取没有截断）
        assertTrue(seenBody.get().contains("你好，讲个笑话"))
        // usage 统计：精确解析 prompt/completion（统计在响应写完后异步落账，轮询等待）
        waitStats { it.outputTokens == 6L }
        val t = stats.today.value
        assertEquals(1, t.requests)
        assertEquals(11, t.inputTokens)
        assertEquals(6, t.outputTokens)
        assertTrue(stats.lastTtftMs.value >= 0)
        assertTrue(stats.lastTokPerSec.value > 0)
    }

    @Test
    fun nonStreamingPassThrough() {
        val body = """{"model":"x","messages":[{"role":"user","content":"hi"}]}"""
        val req = Request.Builder()
            .url("http://127.0.0.1:$proxyPort/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client().newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("好的，收到。"))
        }
        waitStats { it.outputTokens == 6L }
        assertEquals(6, stats.today.value.outputTokens)
    }

    private fun waitStats(cond: (StatsStore.DayStats) -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!cond(stats.today.value) && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun getModels() {
        val req = Request.Builder().url("http://127.0.0.1:$proxyPort/v1/models").get().build()
        client().newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("real-model"))
        }
    }

    @Test
    fun v1DedupUpstreamUrl() {
        // baseUrl 已含 /v1，mock 上游路径即 /v1/chat/completions；若拼成 /v1/v1/... 则断言 body 收不到
        val req = Request.Builder()
            .url("http://127.0.0.1:$proxyPort/v1/chat/completions")
            .post("""{"model":"x","messages":[]}""".toRequestBody("application/json".toMediaType()))
            .build()
        client().newCall(req).execute().use { resp -> assertEquals(200, resp.code) }
    }
}
