package com.closerouter.app

import com.closerouter.app.data.KeyValueStore
import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.data.ProviderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

class ProviderStoreTest {

    private fun p(name: String, id: String = name) = ProviderProfile(
        id = id, name = name, baseUrl = "https://x.com/v1", apiKey = "k-$name", model = "m-$name"
    )

    @Test
    fun upsertAndActiveRoundtrip() {
        val kv = MapKv()
        val s = ProviderStore(kv)
        s.upsert(p("a"))
        s.upsert(p("b"))
        // 第一个自动成为 active
        assertEquals("a", s.active()!!.name)
        s.setActive("b")
        assertEquals("b", s.active()!!.name)
        // 重新加载（模拟重启）后状态保持
        val s2 = ProviderStore(kv)
        assertEquals(2, s2.providers().size)
        assertEquals("b", s2.active()!!.name)
    }

    @Test
    fun upsertUpdatesExisting() {
        val s = ProviderStore(MapKv())
        s.upsert(p("a"))
        s.upsert(p("a").copy(model = "new-model"))
        assertEquals(1, s.providers().size)
        assertEquals("new-model", s.active()!!.model)
    }

    @Test
    fun removeActiveFallsBack() {
        val s = ProviderStore(MapKv())
        s.upsert(p("a")); s.upsert(p("b"))
        s.setActive("a")
        s.remove("a")
        assertEquals("b", s.active()!!.name)
        s.remove("b")
        assertNull(s.active())
    }

    @Test
    fun corruptJsonDoesNotCrash() {
        val kv = MapKv()
        kv.putString(ProviderStore.KEY, "{oops")
        val s = ProviderStore(kv)
        assertEquals(0, s.providers().size)
        assertNull(s.active())
    }

    @Test
    fun setActiveIgnoresUnknownId() {
        val s = ProviderStore(MapKv())
        s.upsert(p("a"))
        s.setActive("nonexistent")
        assertEquals("a", s.active()!!.name)
    }

    @Test
    fun upsertAddPath_appendsAndPersistsAndNotifies() {
        val kv = MapKv()
        val s = ProviderStore(kv)
        var notified = 0
        // changes flow 每次写入都会 bump
        val before = s.changes.value
        assertTrue(s.upsert(p("a")))
        val newProfile = p("b").copy(name = "我的 API", baseUrl = "https://api.deepseek.com/v1")
        assertTrue(s.upsert(newProfile))
        notified = s.changes.value - before
        assertEquals(2, notified)
        // 列表新增、内容正确
        assertEquals(2, s.providers().size)
        assertEquals("我的 API", s.providers()[1].name)
        // 重启（重载）后仍在
        val s2 = ProviderStore(kv)
        assertEquals(2, s2.providers().size)
        assertEquals("https://api.deepseek.com/v1", s2.providers()[1].baseUrl)
    }

    @Test
    fun upsertRejectsInvalid_andDoesNotWrite() {
        val kv = MapKv()
        val s = ProviderStore(kv)
        assertTrue(s.upsert(p("a")))
        val before = s.changes.value
        // 缺 apiKey / 缺 baseUrl / baseUrl 非 http 都被拒且不写入
        org.junit.Assert.assertFalse(s.upsert(p("b").copy(apiKey = "")))
        org.junit.Assert.assertFalse(s.upsert(p("c").copy(baseUrl = "")))
        org.junit.Assert.assertFalse(s.upsert(p("d").copy(baseUrl = "api.x.com/v1")))
        org.junit.Assert.assertFalse(s.upsert(p("e").copy(name = "")))
        assertEquals(1, s.providers().size)
        assertEquals(before, s.changes.value)
    }

    @Test
    fun validateMessages() {
        val ok = p("a")
        assertNull(ProviderStore.validate(ok))
        assertEquals("名称不能为空", ProviderStore.validate(ok.copy(name = " ")))
        assertEquals("baseUrl 不能为空", ProviderStore.validate(ok.copy(baseUrl = "")))
        assertEquals("apiKey 不能为空", ProviderStore.validate(ok.copy(apiKey = "")))
        assertEquals("模型名不能为空", ProviderStore.validate(ok.copy(model = "")))
        assertEquals(
            "baseUrl 必须以 http:// 或 https:// 开头",
            ProviderStore.validate(ok.copy(baseUrl = "ws://x.com"))
        )
        assertEquals(
            "apiType 必须是 openai / responses / anthropic",
            ProviderStore.validate(ok.copy(apiType = "gpt"))
        )
    }
}
