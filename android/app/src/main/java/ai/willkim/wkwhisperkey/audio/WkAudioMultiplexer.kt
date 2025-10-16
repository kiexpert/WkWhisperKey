package ai.willkim.wkwhisperkey.audio

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * WkAudioMultiplexer v1.0
 * -----------------------
 * 다중 마이크에서 수집된 신호를 병합하여 Whisper 입력용 단일 PCM 채널 생성.
 * - 각 마이크별 에너지 수준 기반 가중 평균
 * - 고에너지 발화 억제 / 저에너지 속삭임 강화
 * - 실험용 파라미터 α 조절 가능 (기본 4.0)
 */
class WkAudioMultiplexer(
    private val sampleRate: Int = 16000,
    private val alpha: Double = 4.0, // 에너지 감쇠 계수 (높을수록 속삭임 강조)
    private val onMerged: (ShortArray) -> Unit
) {
    private val micBuffers = ConcurrentHashMap<Int, ShortArray>()
    private val micEnergy = ConcurrentHashMap<Int, Float>()

    /** 외부에서 각 마이크의 새 프레임 입력 시 호출 */
    fun onMicFrame(id: Int, data: ShortArray, energy: Float) {
        micBuffers[id] = data
        micEnergy[id] = energy
        tryMerge()
    }

    /** 가중 평균 기반 병합 수행 */
    private fun tryMerge() {
        if (micBuffers.isEmpty()) return

        val ids = micBuffers.keys.toList()
        val length = micBuffers.values.first().size
        val out = ShortArray(length)
        var weightSum = 0.0

        val weights = ids.map { id ->
            val e = micEnergy[id]?.toDouble() ?: 0.0
            val w = exp(-alpha * e)
            weightSum += w
            w
        }

        for (i in 0 until length) {
            var mix = 0.0
            for ((idx, id) in ids.withIndex()) {
                val buf = micBuffers[id] ?: continue
                if (i < buf.size) mix += buf[i] * weights[idx]
            }
            out[i] = (mix / weightSum).toInt().toShort()
        }

        onMerged(out)
    }

    /** 오래된 마이크 버퍼 정리 */
    fun clear() {
        micBuffers.clear()
        micEnergy.clear()
    }
}
