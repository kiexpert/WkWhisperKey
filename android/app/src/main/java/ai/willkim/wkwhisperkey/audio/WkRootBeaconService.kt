package ai.willkim.wkwhisperkey.audio

import android.app.Service
import android.content.Intent
import android.media.*
import android.os.*
import kotlin.math.*

/**
 * WkRootBeaconService
 * 백그라운드 루트 비콘 송출 서비스
 *
 * - AudioTrack 단일 인스턴스 유지
 * - 외부 RMS 입력값에 따라 자동 볼륨 보정
 * - 모든 액티비티에서 bind/unbind 가능
 *
 * (C) 2025 Will Kim (kiexpert@kivilab.co.kr)
 */

class WkRootBeaconService : Service() {

    private val binder = LocalBinder()
    private lateinit var emitter: WkRootBeaconEmitter
    private var monitorThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        emitter = WkRootBeaconEmitter()
        emitter.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        emitter.stop()
        monitorThread?.interrupt()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): WkRootBeaconService = this@WkRootBeaconService
    }

    /**
     * 외부 마이크 RMS 업데이트
     */
    fun updateNoiseLevel(rms: Double) {
        emitter.updateNoiseRms(rms)
    }
}

/**
 * WkRootBeaconEmitter
 * 8밴드 루트 신호 송출기
 *  - RMS 기반 음량 자동 조절
 *  - 1초 주기, 0.1초 버스트
 */
class WkRootBeaconEmitter(
    private val sampleRate: Int = 44100,
    private val bands: DoubleArray = doubleArrayOf(
        150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0
    )
) {
    private val c = 343.0
    private val audioTrack: AudioTrack
    @Volatile private var running = false
    @Volatile private var lastRms = 0.002

    init {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    fun updateNoiseRms(rms: Double) {
        lastRms = rms.coerceIn(1e-4, 0.1)
    }

    fun start() {
        if (running) return
        running = true
        audioTrack.play()
        Thread { loop() }.start()
    }

    fun stop() {
        running = false
        audioTrack.stop()
    }

    private fun loop() {
        val dur = 0.1
        val n = (sampleRate * dur).toInt()
        val env = DoubleArray(n) { i ->
            val t = i / n.toDouble()
            when {
                t < 0.1 -> t * 10
                t > 0.9 -> (1 - t) * 10
                else -> 1.0
            }
        }

        val buf = ShortArray(n)
        while (running) {
            val rms = lastRms
            if (rms < 0.05) {
                val amp = (32767.0 * (rms * 2.0)).coerceIn(800.0, 4000.0)
                for (i in buf.indices) {
                    val t = i / sampleRate.toDouble()
                    var s = 0.0
                    for (f in bands) s += sin(2 * Math.PI * f * t)
                    buf[i] = (s / bands.size * env[i] * amp)
                        .coerceIn(-32767.0, 32767.0).toInt().toShort()
                }
                audioTrack.write(buf, 0, buf.size)
            }
            Thread.sleep(1000)
        }
    }
}
