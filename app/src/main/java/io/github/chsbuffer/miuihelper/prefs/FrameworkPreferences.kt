package io.github.chsbuffer.miuihelper.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** App-process owner of private settings and the official framework service. */
object FrameworkPreferences {
    enum class Status { DISCONNECTED, SYNCING, SYNCED, SYNC_FAILED, LOCAL_ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private lateinit var local: SharedPreferences
    private lateinit var publisher: RemotePreferencesPublisher
    private val generation = AtomicLong()
    // Strong reference: SharedPreferences only retains weak listener references.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        generation.incrementAndGet()
        if (status != Status.DISCONNECTED) updateStatus(Status.SYNCING)
        worker.execute { publish() }
    }
    // The following state is confined to worker. Binder callbacks only enqueue work.
    private var service: XposedService? = null
    private var remote: SharedPreferences? = null
    private var retryScheduled = false
    private var retries = 0
    private var initialized = false

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        // Keep the original app-private file/name so accessible local settings survive an upgrade.
        // Framework-owned legacy NSP files are deliberately never opened or chmod'ed here.
        local = context.getSharedPreferences(HookPreferences.GROUP, Context.MODE_PRIVATE)
        publisher = RemotePreferencesPublisher(local)
        local.registerOnSharedPreferenceChangeListener(preferenceListener)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                worker.execute {
                    publisher.disconnect()
                    service = bound
                    remote = null
                    retries = 0
                    updateStatus(Status.SYNCING)
                    publish()
                }
            }

            override fun onServiceDied(dead: XposedService) {
                worker.execute {
                    if (service !== dead) return@execute
                    publisher.disconnect()
                    service = null
                    remote = null
                    updateStatus(Status.DISCONNECTED)
                }
            }
        })
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }

    /** Reopening settings or tapping status retries a failed publication. */
    fun refresh() {
        worker.execute {
            retries = 0
            publish()
        }
    }

    private fun publish() {
        val bound = service
        if (bound == null) {
            updateStatus(Status.DISCONNECTED)
            return
        }
        updateStatus(Status.SYNCING)
        val publishingGeneration = generation.get()
        try {
            val target = remote
            val success = if (target == null) {
                val connected = bound.getRemotePreferences(HookPreferences.GROUP)
                remote = connected
                publisher.connect(connected)
            } else {
                publisher.publish()
            }
            updateStatus(when {
                publisher.localStorageFailed -> Status.LOCAL_ERROR
                !success -> Status.SYNC_FAILED
                else -> Status.SYNCED
            }, publishingGeneration)
            if (success) retries = 0 else retry()
        } catch (error: RuntimeException) {
            Log.w("MIUIQOL", "Cannot publish preferences to the framework", error)
            remote = null
            publisher.disconnect()
            updateStatus(Status.SYNC_FAILED)
            retry()
        }
    }

    private fun retry() {
        if (retryScheduled || retries >= 3) return
        retryScheduled = true
        val delay = 1000L shl retries++
        main.postDelayed({
            worker.execute {
                retryScheduled = false
                publish()
            }
        }, delay)
    }

    private fun updateStatus(next: Status, expectedGeneration: Long? = null) {
        main.post {
            // Preference listeners and final state delivery both run on main. Recheck here,
            // so an edit after a worker finished cannot be overwritten by its old success.
            status = if (expectedGeneration != null && generation.get() != expectedGeneration) {
                Status.SYNCING
            } else {
                next
            }
            listeners.forEach { it(status) }
        }
    }
}
