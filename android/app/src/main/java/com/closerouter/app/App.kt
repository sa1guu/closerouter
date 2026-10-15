package com.closerouter.app

import android.app.Application
import android.content.Context
import com.closerouter.app.data.KeyValueStore
import com.closerouter.app.data.ProviderStore
import com.closerouter.app.proxy.ProxyServer
import com.closerouter.app.stats.StatsStore
import java.io.File

class App : Application() {

    lateinit var providerStore: ProviderStore; private set
    lateinit var statsStore: StatsStore; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = getSharedPreferences("closerouter", Context.MODE_PRIVATE)
        val kv = object : KeyValueStore {
            override fun getString(key: String): String? = prefs.getString(key, null)
            override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        }
        providerStore = ProviderStore(kv)
        statsStore = StatsStore(kv)
        port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        ProxyServer.profileProvider = { providerStore.active() }
        ProxyServer.statsSink = statsStore
        ProxyServer.onPortChanged = { p ->
            prefs.edit().putInt(KEY_PORT, p).apply()
            writePortFile(p)
        }
        // 手机没配提供方时给一个占位，提示用户去填
        if (providerStore.providers().isEmpty()) {
            providerStore.upsert(
                com.closerouter.app.data.ProviderProfile(
                    name = "示例（请编辑）",
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = "sk-请替换成真实密钥",
                    model = "gpt-4o-mini",
                    apiType = "openai"
                )
            )
        }
        writePortFile(port)
    }

    /** exe 通过 adb 读这个文件拿端口 */
    fun writePortFile(p: Int) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "port.txt").writeText(p.toString())
        }
    }

    fun setPort(p: Int) {
        port = p
        getSharedPreferences("closerouter", Context.MODE_PRIVATE).edit().putInt(KEY_PORT, p).apply()
        writePortFile(p)
        ProxyServer.setPort(p)
    }

    companion object {
        const val DEFAULT_PORT = 8788
        const val KEY_PORT = "port"
        @Volatile var port: Int = DEFAULT_PORT; private set
        lateinit var instance: App; private set
    }
}
