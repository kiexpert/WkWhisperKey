package ai.willkim.wkwhisperkey.audio

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * WkMicArrayManager v2.1
 * ----------------------
 * 다중 마이크 입력을 순차 탐색·동기 수집하는 매니저.
 * - Galaxy Fold5 등 3~4개 내장 마이크 자동 탐색
 * - AudioRecord 다중 병렬 수집
 * - Whisper 전처리 단계용 에너지/위상 실험 기반 확장 준비
 */
class WkMicArrayManager(
    private val context: Context,
    private val onBuffer: (id: Int, data: ShortArray) -> Unit,
    private val onEnergyLevel: ((id: Int, level: Float) -> Unit)? = null
) {
    private val recorders = ConcurrentHashMap<Int, AudioRecord>()
    private val devices = mutableListOf<AudioDeviceInfo>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lastBuffers = ConcurrentHashMap<Int, ShortArray>()

    /** 마이크 목록 스캔 */
    fun scanInputs(): List<AudioDeviceInfo> {
        val am = context.getSystemService(AudioManager::class.java)
        devices.clear()
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        devices += inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        devices.forEach {
            Log.i("MicArray", "id=${it.id}, type=${it.type}, addr=${it.address}, loc=${it.location}")
        }
        return devices
    }

    /** 동시 녹음 시작 */
    fun startAll(sampleRate: Int = 16000) {
        if (devices.isEmpty()) scanInputs()

        for (dev in devices) {
            try {
                val bufSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setAudioDevice(dev)
                    .build()

                recorders[dev.id] = rec
                scope.launch { captureLoop(dev.id, rec, bufSize) }

            } catch (e: Exception) {
                Log.e("MicArray", "init fail id=${dev.id}: ${e.message}")
            }
        }
    }

    /** 개별 캡처 루프 + 에너지 계산 */
    private suspend fun captureLoop(id: Int, rec: AudioRecord, bufSize: Int) {
        val buf = ShortArray(bufSize)
        try {
            rec.startRecording()
            while (isActive) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    val chunk = buf.copyOf(read)
                    lastBuffers[id] = chunk
                    onBuffer(id, chunk)
                    computeEnergy(id, chunk, read)
                }
            }
        } catch (e: Exception) {
            Log.e("MicArray", "loop error id=$id: ${e.message}")
        } finally {
            rec.stop()
            rec.release()
        }
    }

    /** RMS 기반 에너지 계산 (0.0~1.0 정규화) */
    private fun computeEnergy(id: Int, buf: ShortArray, len: Int) {
        var sum = 0.0
        for (i in 0 until len) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / len)
        val norm = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        onEnergyLevel?.invoke(id, norm)
    }

    fun getLastBuffer(id: Int): ShortArray? = lastBuffers[id]

    /** 전체 중단 */
    fun stopAll() {
        scope.cancel()
        recorders.values.forEach {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        recorders.clear()
        lastBuffers.clear()
        Log.i("MicArray", "All microphones stopped.")
    }
}
