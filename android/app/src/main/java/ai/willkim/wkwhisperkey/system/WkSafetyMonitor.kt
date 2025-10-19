package ai.willkim.wkwhisperkey.system

import android.content.Context
import android.os.*
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import java.lang.Thread.UncaughtExceptionHandler
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor

/**
 * WkSafetyMonitor
 * ----------------
 * - 앱 전체 UncaughtException 감시
 * - AudioRecord/Whisper 등에서 상태 멈춤 감지
 * - Toast + 로그로 사용자에게 알림
 */
object WkSafetyMonitor {

    private var scope: CoroutineScope? = null
    private lateinit var context: Context
    private var lastHeartbeat = SystemClock.elapsedRealtime()

    /** 초기 등록 */
    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        registerCrashHandler()
        startHeartbeat()
        Log.i("WkSafety", "✅ SafetyMonitor initialized")
    }

    /** 전역 예외 핸들러 */
    private fun registerCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(object : UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                Log.e("WkSafety", "💥 Uncaught exception in ${t.name}: ${e.stackTraceToString()}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "앱 오류: ${e.message}", Toast.LENGTH_LONG).show()
                }
                defaultHandler?.uncaughtException(t, e)
            }
        })
    }

    /** 주기적 하트비트 */
    private fun startHeartbeat() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope?.launch {
            while (isActive) {
                delay(3000)
                val now = SystemClock.elapsedRealtime()
                val diff = now - lastHeartbeat
                if (diff > 5000) {
                    Log.w("WkSafety", "⚠️ 감지: 하트비트 멈춤 ($diff ms)")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "⚠️ 마이크 또는 스레드 중단 감지", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** 외부에서 주기적으로 호출해 상태 갱신 */
    fun heartbeat() {
        lastHeartbeat = SystemClock.elapsedRealtime()
    }

    fun stop() {
        scope?.cancel()
    }
}
