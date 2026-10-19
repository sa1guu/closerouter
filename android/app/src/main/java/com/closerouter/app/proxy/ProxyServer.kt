package com.closerouter.app.proxy

import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.stats.StatsSink
import com.closerouter.app.stats.TokenEstimator
import com.closerouter.app.stats.UsageParser
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException

/**
 * CloseRouter 转发核心：绑定 0.0.0.0:port（USB 走 adb forward，局域网电脑直连手机 IP），
 * 客户端请求原样转发到当前启用提供方，注入真实 apiKey 和模型名。
 * 满血支持 POST /v1/chat/completions、/v1/responses、/v1/messages（原始 body 透传 +
 * SSE 流式透传 + 非流式）以及 GET /v1/models。
 */
object ProxyServer {

    @Volatile
    var port: Int = 8788
        private set

    /** 由 App 注入：当前启用提供方 / 统计接收器 / 端口变化回调（写 port.txt） */
    var profileProvider: () -> ProviderProfile? = { null }
    var statsSink: StatsSink? = null
    var onPortChanged: (Int) -> Unit = {}

    val running = MutableStateFlow(false)
    val startError = MutableStateFlow<String?>(null)

    private var server: ServerSocket? = null
    private var job: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start(newPort: Int = port): Boolean {
        if (server != null && port == newPort) return true
        stop()
        startError.value = null
        port = newPort
        onPortChanged(newPort)
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", newPort), 50)
        } catch (error: IOException) {
            runCatching { ss.close() }
            startError.value = if (error is BindException) {
                "转发端口 $newPort 已被占用，请到「端点」页修改转发端口后重试。转发服务未启动。"
            } else {
                "无法开启转发端口 $newPort：${error.message ?: "监听失败"}。转发服务未启动。"
            }
            return false
        }
        server = ss
        running.value = true
        job = scope.launch { serve(ss) }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
        server = null
        job = null
        running.value = false
    }

    @Synchronized
    fun setPort(newPort: Int) {
        if (newPort == port) return
        val wasRunning = server != null
        port = newPort
        startError.value = null
        onPortChanged(newPort)
        if (wasRunning) { stop(); start(newPort) }
    }

    private suspend fun serve(ss: ServerSocket) = withContext(Dispatchers.IO) {
        try {
            while (isActive) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                scope.launch { runCatching { handle(sock) } }
            }
        } finally {
            synchronized(this@ProxyServer) {
                runCatching { ss.close() }
                if (server === ss) {
                    server = null
                    running.value = false
                }
            }
        }
    }

    // ---------------- 单连接单请求处理 ----------------

    /** 按字节读一行（到 \n 为止），header 都是 ASCII，逐字节读没问题 */
    private fun readLineBytes(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** 精确读取 n 字节，读不到就返回已读到的部分 */
    private fun readFully(ins: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val c = ins.read(buf, read, n - read)
            if (c <= 0) break
            read += c
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    /** 解析 chunked 请求体（Content-Length 按字节计，含中文时会多于字符数，必须按字节读） */
    private fun readChunked(ins: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineBytes(ins) ?: break
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) { readLineBytes(ins); break }
            out.write(readFully(ins, size))
            readLineBytes(ins) // 每块末尾的 CRLF
        }
        return out.toByteArray()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val ins = BufferedInputStream(s.getInputStream())
            val requestLine = readLineBytes(ins) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLineBytes(ins)
                if (line.isNullOrEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            // 客户端若先等 100 Continue 再发 body，不回应会双方互等挂起
            if (headers["expect"]?.contains("100-continue", true) == true) {
                s.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
            }
            val bodyBytes = if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                readChunked(ins)
            } else {
                readFully(ins, headers["content-length"]?.toIntOrNull() ?: 0)
            }
            val body = String(bodyBytes, Charsets.UTF_8)
            val parts = requestLine.split(" ")
            if (parts.size < 2) return writeJson(s, 400, """{"error":"bad request"}""")
            val method = parts[0]
            val path = parts[1]

            val profile = profileProvider()
            if (method == "GET" && path.startsWith("/v1/models") && (profile == null || !profile.usable)) {
                return writeJson(s, 200, """{"object":"list","data":[]}""")
            }
            if (profile == null || !profile.usable) {
                return writeJson(s, 503, """{"error":"CloseRouter 未配置启用的提供方"}""")
            }
            when {
                method == "GET" && path.startsWith("/v1/models") -> writeJson(
                    s, 200,
                    """{"object":"list","data":[{"id":"${profile.model}","object":"model","owned_by":"closerouter"}]}"""
                )
                method == "POST" -> forwardPost(s, path, body, profile)
                else -> writeJson(s, 404, """{"error":"not found"}""")
            }
        }
    }

    /** POST 透传：注入真实密钥与模型名，流式转发响应并做统计 */
    private fun forwardPost(sock: Socket, path: String, body: String, profile: ProviderProfile) {
        val stats = statsSink
        stats?.onRequestStart()
        val startNs = System.nanoTime()
        var ttftMs = -1L
        var firstByte = false
        val isSse = runCatching {
            Upstream.json.parseToJsonElement(body).jsonObject["stream"]?.toString() == "true"
        }.getOrDefault(false)
        val tracker = if (isSse) UsageParser.SseTracker() else null
        val respBuf = if (isSse) null else java.io.ByteArrayOutputStream()

        fun ms(): Long = (System.nanoTime() - startNs) / 1_000_000

        try {
            Upstream.client.newCall(Upstream.buildRequest(profile, path, body)).execute().use { resp ->
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 ${resp.code} ${resp.message}\r\n".toByteArray())
                for (i in 0 until resp.headers.size) {
                    val name = resp.headers.name(i)
                    if (name.equals("Content-Length", true) || name.equals("Transfer-Encoding", true) ||
                        name.equals("Connection", true)
                    ) continue
                    out.write("$name: ${resp.headers.value(i)}\r\n".toByteArray())
                }
                val src = resp.body?.byteStream()
                // 已知长度才写 Content-Length；未知（SSE/chunked）不写，靠 Connection: close 收尾
                resp.body?.contentLength()?.takeIf { it >= 0 }?.let {
                    out.write("Content-Length: $it\r\n".toByteArray())
                } ?: out.write("Connection: close\r\n".toByteArray())
                out.write("\r\n".toByteArray())
                out.flush()
                if (src != null) {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        if (!firstByte) {
                            firstByte = true
                            ttftMs = ms()
                            stats?.onFirstByte()
                        }
                        out.write(buf, 0, n)
                        out.flush() // SSE 逐块立即下发
                        tracker?.feed(buf, 0, n)
                        respBuf?.write(buf, 0, n)
                    }
                }
                // ---- 统计收尾 ----
                tracker?.finish()
                val durationMs = ms()
                val usage = tracker?.usage()
                    ?: respBuf?.let { UsageParser.fromNonStreamUsage(it.toString(Charsets.UTF_8)) }
                val outText = tracker?.outputText()
                    ?: respBuf?.let { UsageParser.nonStreamOutputText(it.toString(Charsets.UTF_8)) } ?: ""
                val inputTokens = usage?.inputTokens ?: TokenEstimator.estimate(body)
                val outputTokens = usage?.outputTokens ?: TokenEstimator.estimate(outText)
                stats?.onRequestEnd(inputTokens, outputTokens, ttftMs, durationMs)
            }
        } catch (e: Exception) {
            stats?.onRequestEnd(0, 0, ttftMs, ms())
            if (!sock.isClosed) runCatching {
                writeJson(sock, 502, """{"error":"proxy upstream error: ${e.message ?: e.javaClass.simpleName}"}""")
            }
        }
    }

    private fun writeJson(sock: Socket, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val out: OutputStream = sock.getOutputStream()
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}
