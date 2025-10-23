package ai.willkim.wkwhisperkey.audio

import android.app.Service
import android.content.Intent
import android.media.*
import android.os.*
import kotlin.math.*

/**
 * WkRootBeaconService
 * --------------------------------------------------------
 * 3단(좌·중·우) 8밴드 루트 비콘 송출 서비스
 *  - 좌 0.00~0.07초 / 중 0.03~0.07초 / 우 0.03~0.10초
 *  - AudioTrack 단일 인스턴스 유지 (Stereo)
 *  - 외부 RMS에 따라 자동 볼륨 보정
 *  - 0.1초 캐시 버퍼를 재사용하여 CPU 부하 최소화
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

    fun updateNoiseLevel(rms: Double) {
        emitter.updateNoiseRms(rms)
    }
}

/**
 * WkRootBeaconEmitter
 * --------------------------------------------------------
 *  - 8밴드 × 3단(좌·중·우) 멀티톤 비콘
 *  - 좌우 스테레오 위상 이동 기반 공간 보정
 *  - 사인파 연속성 유지(페이드 인/아웃 포함)
 *  - 0.1초 버퍼 캐싱 후 1초 주기 송출
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

    private lateinit var triBeaconBuffer: ShortArray

    init {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
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
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        prepareTriMultiBeacon()
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

    /**
     * 8밴드 × 좌·중·우 비콘을 0.1초 버퍼로 미리 합성
     */
    private fun prepareTriMultiBeacon() {
        val totalSec = 0.10
        val totalSamples = (sampleRate * totalSec).toInt()
        val amp = 0.04

        val L = DoubleArray(totalSamples)
        val R = DoubleArray(totalSamples)

        fun addTone(buf: DoubleArray, startSec: Double, durSec: Double, freq: Double) {
            val start = (sampleRate * startSec).toInt()
            val len = (sampleRate * durSec).toInt()
            for (i in 0 until len) {
                val t = (start + i).toDouble() / sampleRate
                val env = 0.5 * (1 - cos(Math.PI * i / len)) // fade in/out
                buf[start + i] += sin(2 * Math.PI * freq * t) * amp * env
            }
        }

        // 좌(0.00~0.07), 중(0.03~0.07), 우(0.03~0.10)
        for (f in bands) {
            addTone(L, 0.00, 0.07, f)
            addTone(L, 0.03, 0.04, f)
            addTone(R, 0.03, 0.04, f)
            addTone(R, 0.03, 0.07, f)
        }

        triBeaconBuffer = ShortArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            triBeaconBuffer[i * 2] =
                (L[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            triBeaconBuffer[i * 2 + 1] =
                (R[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * 메인 루프 — 1초마다 캐시 버퍼 송출
     */
    private fun loop() {
        while (running) {
            val rms = lastRms
            val volScale = (rms * 2.0).coerceIn(0.02, 0.10)
            val scaled = ShortArray(triBeaconBuffer.size)
            for (i in triBeaconBuffer.indices) {
                scaled[i] = (triBeaconBuffer[i] * volScale)
                    .coerceIn(-32767.0, 32767.0).toInt().toShort()
            }

            audioTrack.write(scaled, 0, scaled.size)
            Thread.sleep(1000)
        }
    }
}
