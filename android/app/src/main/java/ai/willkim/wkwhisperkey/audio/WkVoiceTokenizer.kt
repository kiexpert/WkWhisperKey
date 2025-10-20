package ai.willkim.wkwhisperkey.audio

import kotlin.math.*
import ai.willkim.wkwhisperkey.audio.SpeakerSignal as SpeakerInfo

/**
 * WkVoiceTokenizer – 분리 음원에서 주파수별 16bit 토큰 생성
 */
class WkVoiceTokenizer(private val bands: DoubleArray) {

    fun tokenizeAll(list: List<WkVoiceSeparator.SpeakerInfo>): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        for (s in list) out[s.id] = tokenize(s)
        return out
    }

    private fun tokenize(s: WkVoiceSeparator.SpeakerInfo): String {
        val x = s.buffer.map { it.toDouble() }.toDoubleArray()
        var token = 0
        for (i in bands.indices) {
            val (mag, _) = analyzeBand(x, bands[i])
            val level = when {
                mag > 0.3 -> 3
                mag > 0.1 -> 2
                mag > 0.03 -> 1
                else -> 0
            }
            token = (token shl 2) or level
        }
        return String.format("0x%04X", token)
    }

    private fun analyzeBand(x: DoubleArray, f: Double): Pair<Double, Double> {
        val sr = 44100.0
        val w = 2 * Math.PI * f / sr
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            val c = cos(w * i)
            val s = sin(w * i)
            re += x[i] * c
            im -= x[i] * s
        }
        val mag = sqrt(re * re + im * im) / x.size
        val phase = Math.toDegrees(atan2(im, re))
        return mag to phase
    }
}
