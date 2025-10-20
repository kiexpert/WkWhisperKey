package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator
 * Phase-based multi-mic voice separation engine.
 * (C) 2025 Will Kim. All rights reserved.
 */
class WkVoiceSeparator(
    private val sampleRate: Int,
    private val micSpacing: Double = 0.02, // meters
    private val soundSpeed: Double = 340.0
) {

    data class SpeakerProfile(
        val id: Int,
        val deltaN: DoubleArray,   // per-band sample delays
        var lastEnergy: Double = 0.0
    )

    private val speakers = mutableListOf<SpeakerProfile>()
    private var nextId = 1

    // -------------- Δφ → Δn 변환 -----------------
    private fun phaseToSampleDelta(phaseDeg: Double, freq: Double): Double {
        return (phaseDeg / 360.0) * (sampleRate / freq)
    }

    // -------------- 화자 식별 -----------------
    fun identifySpeaker(phasesL: DoubleArray, phasesR: DoubleArray, freqs: DoubleArray): Int {
        val deltaN = DoubleArray(freqs.size) { k ->
            val dPhi = (phasesR[k] - phasesL[k]).let {
                var p = it
                if (p > 180) p -= 360.0
                if (p < -180) p += 360.0
                p
            }
            phaseToSampleDelta(dPhi, freqs[k])
        }

        // 거리기반 Δn 언랩 정제
        val tau = unwrapTau(freqs, deltaN)
        val deltaUnwrapped = DoubleArray(freqs.size) { tau * sampleRate }

        // 기존 화자 매칭
        val match = speakers.minByOrNull { spk ->
            sqrt(spk.deltaN.zip(deltaUnwrapped).sumOf { (a,b)->(a-b).pow(2) })
        }

        val bestDist = match?.let {
            sqrt(it.deltaN.zip(deltaUnwrapped).sumOf { (a,b)->(a-b).pow(2) })
        } ?: Double.MAX_VALUE

        return if (bestDist < 0.5) {
            match!!.id
        } else {
            val new = SpeakerProfile(nextId++, deltaUnwrapped)
            speakers += new
            new.id
        }
    }

    // -------------- Δn 언랩 (거리 일관성 해소) -----------------
    private fun unwrapTau(freqs: DoubleArray, deltaN: DoubleArray): Double {
        // 최소제곱 방식으로 실제 거리 기반 평균지연 계산
        val f2sum = freqs.sumOf { it * it }
        val fphi = freqs.zip(deltaN).sumOf { (f, dn) -> f * dn / sampleRate }
        return fphi / f2sum
    }

    // -------------- Delay-and-Sum 복원 -----------------
    fun delayAndSum(samplesL: ShortArray, samplesR: ShortArray, speakerId: Int): ShortArray {
        val prof = speakers.find { it.id == speakerId } ?: return samplesL
        val delay = prof.deltaN.average()
        val delayedR = applyFractionalDelay(samplesR, delay)
        val out = ShortArray(samplesL.size)
        for (i in out.indices) {
            val s = (samplesL[i] + delayedR[i]) * 0.5
            out[i] = s.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }

    private fun applyFractionalDelay(src: ShortArray, delaySamples: Double): ShortArray {
        val out = ShortArray(src.size)
        val d = delaySamples
        val n0 = d.toInt()
        val frac = d - n0
        for (i in src.indices) {
            val i0 = i - n0
            val i1 = i0 - 1
            val s0 = if (i0 in src.indices) src[i0].toDouble() else 0.0
            val s1 = if (i1 in src.indices) src[i1].toDouble() else 0.0
            val y = (1 - frac) * s0 + frac * s1
            out[i] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }

    // -------------- 통계 정보 -----------------
    fun listSpeakers(): String =
        speakers.joinToString("\n") { "Speaker ${it.id}: Δn=${it.deltaN.joinToString(",") { v -> "%.2f".format(v) }}" }
}
