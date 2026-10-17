package com.closerouter.app

import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.proxy.SpeedTester
import com.closerouter.app.proxy.Upstream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTesterTest {

    private fun request(apiType: String) = SpeedTester.buildRequest(
        ProviderProfile(
            baseUrl = "https://api.example.test/v1",
            apiKey = "test-key",
            model = "test-model",
            apiType = apiType
        )
    )

    private fun requestBody(apiType: String) = Upstream.json.parseToJsonElement(
        Buffer().also { request(apiType).body!!.writeTo(it) }.readUtf8()
    ).jsonObject

    @Test
    fun chatEndpointUsesChatRequestFormat() {
        val request = request("openai")
        val body = requestBody("openai")

        assertEquals("https://api.example.test/v1/chat/completions", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertNull(request.header("x-api-key"))
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals("16", body["max_tokens"]?.jsonPrimitive?.content)
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Say OK", body["messages"]!!.jsonArray[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertFalse("input" in body)
    }

    @Test
    fun responsesEndpointUsesResponsesRequestFormat() {
        val request = request("responses")
        val body = requestBody("responses")

        assertEquals("https://api.example.test/v1/responses", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals("16", body["max_output_tokens"]?.jsonPrimitive?.content)
        assertEquals("Say OK", body["input"]?.jsonPrimitive?.content)
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("messages" in body)
    }

    @Test
    fun anthropicEndpointUsesMessagesRequestFormat() {
        val request = request("anthropic")
        val body = requestBody("anthropic")

        assertEquals("https://api.example.test/v1/messages", request.url.toString())
        assertEquals("test-key", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertNull(request.header("Authorization"))
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals("16", body["max_tokens"]?.jsonPrimitive?.content)
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Say OK", body["messages"]!!.jsonArray[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownApiTypeDoesNotSilentlyFallBackToChat() {
        request("unknown")
    }
}
