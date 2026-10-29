package io.github.chsbuffer.miuihelper.prefs

import android.content.SharedPreferences

/** The framework owns this read-only transport in injected processes. */
object HookPreferences {
    const val GROUP = "prefs"
    const val READY = "__miuiqol_api101_ready"

    @Volatile
    private var current: SharedPreferences? = null

    val preferences: SharedPreferences
        get() = checkNotNull(current) { "Remote preferences have not been initialized" }

    fun attach(remote: SharedPreferences): Boolean {
        // An empty/unpublished framework group must not silently enable default-on hooks.
        if (remote.all[READY] != true) return false
        current = remote
        return true
    }
}
