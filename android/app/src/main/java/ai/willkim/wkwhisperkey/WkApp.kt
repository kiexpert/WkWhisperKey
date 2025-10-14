package ai.willkim.wkwhisperkey

import android.app.Application
import android.util.Log
import ai.willkim.wkwhisperkey.ui.WkLog
import ai.willkim.wkwhisperkey.util.WkLogSystemBridge
import kotlinx.coroutines.*
import java.lang.Thread.UncaughtExceptionHandler

/**
 * Will Kim WhisperKey Application
 * --------------------------------------
 * - Initializes global log system
 * - Hooks lifecycle / audio / network / crash monitors
 * - Provides global coroutine context for background tasks
 */
class WkApp : Application() {

    companion object {
        lateinit var instance: WkApp
            private set
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize custom log bridge
        WkLogSystemBridge.init(this)
        WkLog.i("App", "WkApp initialized successfully.")

        // Global crash handler (fallback)
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val msg = "Fatal error in ${thread.name}: ${e.message}\n${Log.getStackTraceString(e)}"
            WkLog.e("Crash", msg)
            // 로그 후 시스템 핸들러 위임
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, e)
        }

        // Optional background task monitor
        appScope.launch {
            WkLog.i("App", "Coroutine monitor started.")
            while (isActive) {
                delay(60_000)
                WkLog.i("App", "Heartbeat OK — app still alive.")
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
        WkLog.i("System", "Application terminated.")
    }
}
