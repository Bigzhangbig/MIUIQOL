package io.github.chsbuffer.miuihelper.prefs

import android.content.SharedPreferences

internal class FakeSharedPreferences : SharedPreferences {
    val values = linkedMapOf<String, Any>()
    var commitCalls = 0
    var commitResult = true
    var throwOnCommit = false
    var throwOnRead = false
    var beforeRead: (() -> Unit)? = null

    override fun getAll(): MutableMap<String, *> {
        val action = beforeRead
        beforeRead = null
        action?.invoke()
        check(!throwOnRead) { "read failed" }
        return LinkedHashMap(values)
    }
    override fun contains(key: String?) = values.containsKey(key)
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        values[key] as? MutableSet<String> ?: defValues
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        val updates = linkedMapOf<String, Any?>()
        var clear = false
        override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }
        override fun putInt(key: String, value: Int) = apply { updates[key] = value }
        override fun putLong(key: String, value: Long) = apply { updates[key] = value }
        override fun putFloat(key: String, value: Float) = apply { updates[key] = value }
        override fun putString(key: String, value: String?) = apply { updates[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { updates[key] = values }
        override fun remove(key: String) = apply { updates[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            commitCalls++
            check(!throwOnCommit) { "service died" }
            if (clear) values.clear()
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return commitResult
        }
    }
}
