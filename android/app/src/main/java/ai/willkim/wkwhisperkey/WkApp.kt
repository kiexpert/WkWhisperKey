package ai.willkim.wkwhisperkey

import android.app.Application
import android.media.*
import android.util.Log
import ai.willkim.wkwhisperkey.WkLog
import ai.willkim.wkwhisperkey.util.WkLogSystemBridge
import kotlinx.coroutines.*
import java.lang.Thread.UncaughtExceptionHandler
import kotlin.math.*

/**
 * Will Kim WhisperKey Application
 * --------------------------------------
 * - Initializes global log system
 * - Hooks lifecycle / audio / network / crash monitors
 * - Runs background beacon emitter for spatial calibration
 */
class WkApp : Application() {

    companion object {
        lateinit var instance: WkApp
            private set
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private const val BEACON_DURATION_MS = 100L
        private const val BEACON_INTERVAL_MS = 1000L
        private const val SAMPLE_RATE = 44100
    }

    private var audioTrack: AudioTrack? = null
    private val beaconBands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ----- Logging system -----
        WkLogSystemBridge.init(this)
        WkLog.i("App", "WkApp initialized successfully.")

        // ----- Crash handler -----
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val msg = "Fatal error in ${thread.name}: ${e.message}\n${Log.getStackTraceString(e)}"
            WkLog.e("Crash", msg)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, e)
        }

        // ----- Heartbeat + Beacon -----
        appScope.launch {
            WkLog.i("App", "Coroutine monitor started.")
            launch { runBeaconEmitter() }
            while (isActive) {
                delay(60_000)
                WkLog.i("App", "Heartbeat OK — app still alive.")
            }
        }
    }

    /**
     * 루트 비콘(8주파 사인파) 주기적 송출
     */
    private suspend fun runBeaconEmitter() = withContext(Dispatchers.Default) {
        val frameCount = (SAMPLE_RATE * BEACON_DURATION_MS / 1000.0).toInt()
        val buffer = ShortArray(frameCount)

        // AudioTrack 초기화
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply { play() }

        while (isActive) {
            // 잡음 측정 (간략히 일정 레벨로 설정)
            val noiseLevel = 0.02  // 기준잡음 레벨
            val amplitude = (noiseLevel * 2.0).coerceAtMost(0.1)  // 속삭임 수준 제한

            // 8주파 합성 사인파 생성
            for (i in 0 until frameCount) {
                var s = 0.0
                for (f in beaconBands)
                    s += sin(2 * Math.PI * f * i / SAMPLE_RATE)
                s /= beaconBands.size
                buffer[i] = (s * amplitude * Short.MAX_VALUE).toInt().toShort()
            }

            // 송출
            audioTrack?.playbackRate = SAMPLE_RATE
            audioTrack?.write(buffer, 0, buffer.size)
            WkLog.i("Beacon", "Root beacon emitted (${amplitude * 100}%)")

            delay(BEACON_INTERVAL_MS)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        audioTrack?.stop()
        audioTrack?.release()
        appScope.cancel()
        WkLog.i("System", "Application terminated.")
    }
}
