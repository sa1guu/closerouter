package com.closerouter.app.data

/** 键值存储抽象：Android 端用 SharedPreferences 实现，单测用 Map 实现 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}
