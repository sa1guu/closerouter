package com.closerouter.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 大模型提供方预设。apiType: openai | responses | anthropic
 * anthropic 上游用 x-api-key + anthropic-version 头，其余用 Authorization Bearer。
 */
@Serializable
data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val apiType: String = "openai"
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        val API_TYPES = listOf("openai", "responses", "anthropic")
    }
}
