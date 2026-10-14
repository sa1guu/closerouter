package com.closerouter.app

import com.closerouter.app.data.KeyValueStore
import com.closerouter.app.data.ProviderProfile
import com.closerouter.app.data.ProviderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
