package ai.willkim.wkwhisperkey.util

import android.app.Application
import android.content.Context
import android.media.AudioRecord
import android.media.AudioRecord.*
import android.net.*
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import ai.willkim.wkwhisperkey.ui.WkLog

/**
 * System-level event collector for in-app log overlay.
 * Collects all events we can access without root:
 * - App lifecycle (activity create/resume/pause/destroy)
 * - AudioRecord state changes
 * - Network connectivity changes
 * - Uncaught exceptions
 * - Shutdown events
 */
object WkLogSystemBridge {

    private var initialized = false
    private lateinit var app: Application
    private val scope = CoroutineScope(Dispatchers.Default)

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        app = application

        hookLifecycle()
        hookNetwork()
        hookCrashHandler()
        hookAudioMonitor()

        Runtime.getRuntime().addShutdownHook(Thread {
            WkLog.i("System", "App shutting down normally.")
        })

        WkLog.i("System", "WkLogSystemBridge initialized.")
    }

    // ---- Activity lifecycle ----
    private fun hookLifecycle() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {
                WkLog.i("Lifecycle", "Created: ${a.localClassName}")
            }
            override fun onActivityStarted(a: android.app.Activity) {
                WkLog.i("Lifecycle", "Started: ${a.localClassName}")
            }
            override fun onActivityResumed(a: android.app.Activity) {
                WkLog.i("Lifecycle", "Resumed: ${a.localClassName}")
            }
            override fun onActivityPaused(a: android.app.Activity) {
                WkLog.i("Lifecycle", "Paused: ${a.localClassName}")
            }
            override fun onActivityStopped(a: android.app.Activity) {
                WkLog.i("Lifecycle", "Stopped: ${a.localClassName}")
            }
            override fun onActivitySaveInstanceState(a: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {
                WkLog.i("Lifecycle", "Destroyed: ${a.localClassName}")
            }
        })
    }

    // ---- Network connectivity ----
    private fun hookNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    WkLog.i("Network", "Connected: $network")
                }
                override fun onLost(network: Network) {
                    WkLog.e("Network", "Disconnected: $network")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val type = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        else -> "Other"
                    }
                    WkLog.i("Network", "Capabilities: $type, $caps")
                }
            })
        } catch (e: Exception) {
            WkLog.e("Network", "Failed to register callback: ${e.message}")
        }
    }

    // ---- Crash / Exception handler ----
    private fun hookCrashHandler() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val stack = Log.getStackTraceString(e)
            WkLog.e("Crash", "Thread=${t.name}\n$stack")
            old?.uncaughtException(t, e)
        }
    }

    // ---- AudioRecord status probe ----
    private fun hookAudioMonitor() {
        scope.launch {
            while (true) {
                delay(10_000) // periodic probe
                try {
                    val minBuf = getMinBufferSize(8000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
                    WkLog.i("Audio", "AudioRecord minBuffer=$minBuf bytes (8kHz, mono, 16bit)")
                } catch (e: Exception) {
                    WkLog.e("Audio", "Error querying audio state: ${e.message}")
                }
            }
        }
    }
}
