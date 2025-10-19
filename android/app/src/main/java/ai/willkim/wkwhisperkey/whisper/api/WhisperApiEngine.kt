package ai.willkim.wkwhisperkey.whisper.api

import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object WhisperApiEngine {
    private var job: Job? = null

    /** 🎧 버퍼 대기열 초기화 */
    fun startEngine() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.Default).launch {
            Log.i("WhisperApi", "🧠 Whisper Engine started")
        }
    }

    /** 🔄 마이크 PCM 입력 수신 (스테레오 → 모노 변환 포함) */
    fun enqueueAudio(stereo: ShortArray, read: Int) {
        if (read < 4) return

        val mono = ShortArray(read / 2)
        var i = 0
        var j = 0
        while (i < read - 1) {
            val l = stereo[i].toInt()
            val r = stereo[i + 1].toInt()
            mono[j] = ((l + r) / 2).toShort() // ✅ 좌우평균
            i += 2; j++
        }

        val energy = calculateEnergy(mono)
        Log.d("WhisperApi", "🎙 Energy ${"%.3f".format(energy)}")

        // Whisper 호출부 (실험 단계)
        sendToWhisper(mono)
    }

    /** 📈 RMS 에너지 계산 */
    private fun calculateEnergy(buffer: ShortArray): Float {
        var sum = 0.0
        for (s in buffer) sum += s * s
        val rms = sqrt(sum / buffer.size)
        return (rms / 32768.0).toFloat()
    }

    /** 🧩 Whisper 엔진 호출 자리 (추후 네이티브/HTTP 연동) */
    private fun sendToWhisper(buffer: ShortArray) {
        // TODO: whisper.cpp or local model hook
    }

    fun stop() {
        job?.cancel()
        Log.i("WhisperApi", "🧹 Whisper Engine stopped")
    }
}
