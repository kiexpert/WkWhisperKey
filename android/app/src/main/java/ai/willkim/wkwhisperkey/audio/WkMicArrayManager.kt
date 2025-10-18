package ai.willkim.wkwhisperkey.audio

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * WkMicArrayManager v2.3 (Compatibility Fix)
 * ------------------------------------------
 * - location / setAudioDevice 제거 (SDK 호환)
 * - coroutineContext → isActive 수정
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
            Log.i("MicArray", "id=${it.id}, type=${it.type}, addr=${it.address}")
        }
        return devices
    }

    /** 동시 녹음 시작 */
    fun startAll(sampleRate: Int = 16000) {
        if (devices.isEmpty()) scanInputs()
    
        if (devices.isEmpty()) {
            Log.e("MicArray", "⚠️ No microphones found.")
            return
        }
    
        // ✅ Fold5 안정모드: 첫 번째 마이크만 사용
        val dev = devices.first()
        try {
            val bufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
    
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
    
            val rec = builder.build()
            recorders.clear() // 🔸 혹시 남은 레퍼런스 제거
            recorders[dev.id] = rec
    
            Log.i("MicArray", "🎤 Using single mic id=${dev.id}, type=${dev.type}, addr=${dev.address}")
    
            scope.launch { captureLoop(dev.id, rec, bufSize) }
    
        } catch (e: Exception) {
            Log.e("MicArray", "init fail id=${dev.id}: ${e.message}")
        }
    }

    /** 개별 캡처 루프 + 에너지 계산 */
    private suspend fun captureLoop(id: Int, rec: AudioRecord, bufSize: Int) {
        val buf = ShortArray(bufSize)
        try {
            rec.startRecording()
            while (scope.isActive) { // coroutineContext → scope.isActive 로 교체
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

    /** RMS 기반 에너지 계산 */
    private fun computeEnergy(id: Int, buf: ShortArray, len: Int) {
        var sum = 0.0
        for (i in 0 until len) sum += buf[i] * buf[i]
        val rms = sqrt(sum / len)
        val norm = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        onEnergyLevel?.invoke(id, norm)
    }

    fun getLastBuffer(id: Int): ShortArray? = lastBuffers[id]

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
