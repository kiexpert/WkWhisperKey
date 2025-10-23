package ai.willkim.wkwhisperkey.audio

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*

/**
 * WkVoiceSeparatorService
 * ----------------------------------------------------------
 * 전역 싱글톤 음성분리 서비스
 * - 내부에 WkVoiceSeparator 1개만 유지
 * - 외부 비콘/노이즈 레벨 반영
 * - 실시간 화자·노이즈 배열 전역 공유
 * ----------------------------------------------------------
 */
class WkVoiceSeparatorService : Service() {

    companion object {
        @Volatile private var instance: WkVoiceSeparatorService? = null
        fun getInstance(): WkVoiceSeparatorService? = instance
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val separator = WkVoiceSeparator(
        sampleRate = 44100,
        bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    )
    fun getSeparator(): WkVoiceSeparator = separator

    @Volatile var currentSpeakers: List<SpeakerSignal> = emptyList()
        private set
    @Volatile var currentNoiseBands: DoubleArray = DoubleArray(8) { 1e-9 }
        private set

    @Volatile private var noiseRms = 0.002

    // ------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        instance = this

        serviceScope.launch {
            while (isActive) {
                delay(1000)
                // 유지 heartbeat
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): WkVoiceSeparatorService = this@WkVoiceSeparatorService
    }

    // ------------------------------------------------------
    // 외부 제어 API
    // ------------------------------------------------------
    fun updateNoiseRms(rms: Double) {
        noiseRms = rms.coerceIn(1e-4, 0.1)
    }

    fun processFrame(left: DoubleArray, right: DoubleArray) {
        currentSpeakers = separator.separate(left, right)
        currentNoiseBands = separator.getNoiseBandsCopy()
    }

    fun getSpeakers(): List<SpeakerSignal> = currentSpeakers
    fun getNoiseBands(): DoubleArray = currentNoiseBands
}
