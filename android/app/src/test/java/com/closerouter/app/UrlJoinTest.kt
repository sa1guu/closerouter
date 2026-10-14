package com.closerouter.app

import com.closerouter.app.proxy.Upstream
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlJoinTest {

    @Test
    fun dedupWhenBaseHasV1() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            Upstream.joinUrl("https://api.x.com/v1", "/v1/chat/completions")
        )
    }

    @Test
    fun dedupWithTrailingSlash() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            Upstream.joinUrl("https://api.x.com/v1/", "/v1/chat/completions")
        )
    }

    @Test
    fun noDedupWhenBaseLacksV1() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            Upstream.joinUrl("https://api.x.com", "/v1/chat/completions")
        )
    }

    @Test
    fun baseWithPathPrefix() {
        assertEquals(
            "https://gw.x.com/proxy/v1/messages",
            Upstream.joinUrl("https://gw.x.com/proxy/v1", "/v1/messages")
        )
    }

    @Test
    fun responsesPath() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            Upstream.joinUrl("https://api.openai.com/v1", "/v1/responses")
        )
    }

    @Test
    fun pathWithoutLeadingSlash() {
        assertEquals(
            "https://api.x.com/base/v1/models",
            Upstream.joinUrl("https://api.x.com/base/v1", "v1/models")
        )
    }
}
