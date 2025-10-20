package ai.willkim.wkwhisperkey.audio

import kotlin.math.roundToInt

/**
 * WkVoiceTokenizer
 * 화자별 FFT 기반 음 토큰 생성기
 * (C) 2025 Will Kim
 */

class WkVoiceTokenizer {

    private val prevTokens = mutableMapOf<Int, String>()

    fun generateTokensFromSpeakers(speakers: List<SpeakerSignal>): List<String> {
        if (speakers.isEmpty()) return listOf("∅")
        val tokens = mutableListOf<String>()

        for (spk in speakers) {
            val t = buildToken(spk)
            if (t != prevTokens[spk.id]) {
                prevTokens[spk.id] = t
                tokens += "S${spk.id}:${t}"
            }
        }

        // 150자 제한 유지
        return tokens.takeLast(30)
    }

    private fun buildToken(spk: SpeakerSignal): String {
        // 간단한 예시: energy + deltaIndex 기반 토큰
        val e = spk.energy.roundToInt().coerceIn(0, 120)
        val d = spk.deltaIndex
        val energyBits = (e / 10).coerceIn(0, 15)
        val delayBits = (d and 0xF).coerceIn(0, 15)
        val token = ((energyBits shl 4) or delayBits)
        return String.format("%04X", token)
    }
}
