package io.github.chsbuffer.miuihelper.prefs

import android.content.SharedPreferences

/** Mirrors a complete local snapshot. The caller owns scheduling and service reconnection. */
class RemotePreferencesPublisher(private val local: SharedPreferences) {
    companion object {
        const val READY_KEY = "__miuiqol_api101_ready"
        private const val INTERNAL_PREFIX = "__miuiqol_"
    }

    private var remote: SharedPreferences? = null
    private var lastSnapshot: Map<String, Any>? = null

    @Volatile
    var isSynchronized: Boolean = false
        private set

    @Volatile
    var localStorageFailed: Boolean = false
        private set

    /** A new service may have lost its data, even if its preference object is reused. */
    @Synchronized
    fun connect(remote: SharedPreferences): Boolean {
        this.remote = remote
        lastSnapshot = null
        isSynchronized = false
        return publish()
    }

    @Synchronized
    fun disconnect() {
        remote = null
        lastSnapshot = null
        isSynchronized = false
        localStorageFailed = false
    }

    @Synchronized
    fun publish(): Boolean {
        localStorageFailed = false
        val destination = remote ?: return failed()
        // Capture the exact values to publish before the persistence barrier. Flushing
        // first could authorize a later apply() value which never reached local disk.
        val snapshot = try {
            snapshot()
        } catch (_: RuntimeException) {
            localStorageFailed = true
            return failed()
        }
        val persisted = try {
            local.edit().commit()
        } catch (_: RuntimeException) {
            false
        }
        if (!persisted) {
            localStorageFailed = true
            return failed()
        }
        return try {
            // The local barrier also precedes deduplication: an unchanged snapshot
            // must not conceal a newly failing local storage write.
            if (snapshot == lastSnapshot) {
                isSynchronized = true
                true
            } else {
                val editor = destination.edit().clear()
                snapshot.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, LinkedHashSet(value as Set<String>))
                        }
                    }
                }
                editor.putBoolean(READY_KEY, true)
                if (editor.commit()) {
                    lastSnapshot = snapshot
                    isSynchronized = true
                    true
                } else {
                    failed()
                }
            }
        } catch (_: RuntimeException) {
            failed()
        }
    }

    private fun snapshot(): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        local.all.forEach { (key, value) ->
            if (key.startsWith(INTERNAL_PREFIX)) return@forEach
            result[key] = when (value) {
                is Boolean, is Int, is Long, is Float, is String -> value
                is Set<*> -> {
                    require(value.all { it is String }) { "Non-string preference set: $key" }
                    @Suppress("UNCHECKED_CAST")
                    LinkedHashSet(value as Set<String>)
                }
                else -> throw IllegalArgumentException("Unsupported preference type: $key")
            }
        }
        return result
    }

    private fun failed(): Boolean {
        // commit(false) can still change the remote in-memory map. Never deduplicate
        // against an earlier successful snapshot until a new commit has succeeded.
        lastSnapshot = null
        isSynchronized = false
        return false
    }
}
