package com.closerouter.app.proxy

import com.closerouter.app.data.ProviderProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 上游请求构建的公共逻辑：URL 拼接、鉴权头、模型名注入 */
object Upstream {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接不超时
        .build()

    val json = Json { ignoreUnknownKeys = true }

    /** baseUrl 已含 /v1 时去重：/base/v1 + /v1/chat/completions → /base/v1/chat/completions */
    fun joinUrl(base: String, path: String): String {
        val b = base.trim().trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return when {
            p.startsWith("/v1/") && b.endsWith("/v1") -> b + p.removePrefix("/v1")
            p == "/v1" && b.endsWith("/v1") -> b
            else -> b + p
        }
    }

    fun isAnthropic(apiType: String): Boolean = apiType == "anthropic"

    /** 请求体里的 model 强制替换为当前提供方模型（客户端填任意模型名都可用） */
    fun injectModel(body: String, model: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        buildJsonObject {
            for ((k, v) in root.entries) put(k, v)
            put("model", JsonPrimitive(model))
        }.toString()
    }.getOrDefault(body)

    fun buildRequest(profile: ProviderProfile, path: String, body: String): Request {
        val fixedBody = injectModel(body, profile.model)
        val builder = Request.Builder()
            .url(joinUrl(profile.baseUrl, path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(fixedBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (isAnthropic(profile.apiType)) {
            builder.header("x-api-key", profile.apiKey)
            builder.header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer ${profile.apiKey}")
        }
        return builder.build()
    }
}
