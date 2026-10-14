package com.closerouter.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 提供方列表 + 当前启用项，JSON 持久化 */
class ProviderStore(private val kv: KeyValueStore) {

    @Serializable
    private data class Payload(
        val activeId: String = "",
        val providers: List<ProviderProfile> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cache: Payload = load()

    private fun load(): Payload {
        val raw = kv.getString(KEY) ?: return Payload()
        return runCatching { json.decodeFromString<Payload>(raw) }.getOrDefault(Payload())
    }

    private fun save(p: Payload) {
        cache = p
        kv.putString(KEY, json.encodeToString(Payload.serializer(), p))
    }

    fun providers(): List<ProviderProfile> = cache.providers

    fun activeId(): String = cache.activeId

    /** 当前启用提供方；activeId 失效时回落到第一个 */
    fun active(): ProviderProfile? {
        val p = cache
        return p.providers.firstOrNull { it.id == p.activeId } ?: p.providers.firstOrNull()
    }

    fun setActive(id: String) {
        if (cache.providers.any { it.id == id }) save(cache.copy(activeId = id))
    }

    /** 新增或更新（按 id）；保存后保证有有效 activeId */
    fun upsert(profile: ProviderProfile) {
        val list = cache.providers.toMutableList()
        val i = list.indexOfFirst { it.id == profile.id }
        if (i >= 0) list[i] = profile else list.add(profile)
        val active = if (cache.providers.any { it.id == cache.activeId }) cache.activeId else profile.id
        save(cache.copy(providers = list, activeId = if (list.size == 1) profile.id else active))
    }

    fun remove(id: String) {
        val list = cache.providers.filterNot { it.id == id }
        val active = if (cache.activeId == id) list.firstOrNull()?.id ?: "" else cache.activeId
        save(cache.copy(providers = list, activeId = active))
    }

    companion object {
        const val KEY = "providers_json"
    }
}
