package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator – 다중 화자 분리 엔진
 * (C) 2025 Will Kim (kiexpert@kivilab.co.kr)
 */
class WkVoiceSeparator(private val sampleRate: Int) {

    private val bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    private val cSound = 343.0      // m/s
    private val micDist = 0.02      // 2 cm 기본 간격
    private val noiseFloor = DoubleArray(bands.size) { 1e-9 }

    private var nextId = 1
    private val prevKeys = mutableListOf<VoiceKey>()

    data class VoiceKey(
        val freq: Double,
        val deltaSamples: Int,
        var framesAlive: Int = 1,
        var confidence: Double = 0.3
    )

    data class SpeakerInfo(
        val id: Int,
        val distanceM: Double,
        val angleDeg: Double,
        val rmsDb: Double,
        val buffer: ShortArray
    )

    fun processFrame(left: DoubleArray, right: DoubleArray): List<SpeakerInfo> {
        val keys = detectKeys(left, right)
        val matched = matchKeys(keys)
        val speakers = clusterSpeakers(matched)
        return speakers
    }

    // --- 주파수별 위상차 → Δn 계산 ---
    private fun detectKeys(L: DoubleArray, R: DoubleArray): List<VoiceKey> {
        val out = mutableListOf<VoiceKey>()
        for (i in bands.indices) {
            val f = bands[i]
            val wl = analyzeBand(L, f)
            val wr = analyzeBand(R, f)
            val dPhi = normalizePhase(wr.second - wl.second)
            val deltaN = (dPhi / 360.0 * sampleRate / f).roundToInt()
            out += VoiceKey(f, deltaN)
        }
        return out
    }

    // --- FFT 대체용 밴드 추정기 ---
    private fun analyzeBand(x: DoubleArray, f: Double): Pair<Double, Double> {
        val N = x.size
        val w = 2.0 * Math.PI * f / sampleRate
        var re = 0.0
        var im = 0.0
        for (i in 0 until N) {
            val c = cos(w * i)
            val s = sin(w * i)
            re += x[i] * c
            im -= x[i] * s
        }
        val mag = sqrt(re * re + im * im) / N
        val phase = Math.toDegrees(atan2(im, re))
        return mag to phase
    }

    private fun normalizePhase(phi: Double): Double {
        var p = phi
        while (p > 180) p -= 360
        while (p < -180) p += 360
        return p
    }

    // --- 프레임 간 발성키 정합 ---
    private fun matchKeys(newKeys: List<VoiceKey>): List<VoiceKey> {
        val matched = mutableListOf<VoiceKey>()
        for (k in newKeys) {
            val m = prevKeys.find { abs(it.deltaSamples - k.deltaSamples) <= 2 && abs(it.freq - k.freq) < 50 }
            if (m != null) {
                m.framesAlive++
                m.confidence = 0.7 * m.confidence + 0.3
                matched += m
            } else matched += k
        }
        prevKeys.clear()
        prevKeys += matched
        return matched.filter { it.framesAlive >= 2 && it.confidence > 0.5 }
    }

    // --- Δn 클러스터링 → 화자 추정 ---
    private fun clusterSpeakers(keys: List<VoiceKey>): List<SpeakerInfo> {
        if (keys.isEmpty()) return emptyList()
        val grouped = keys.groupBy { it.deltaSamples / 2 }
        val list = mutableListOf<SpeakerInfo>()
        for ((delta, ks) in grouped) {
            val avgF = ks.map { it.freq }.average()
            val deltaT = delta / sampleRate.toDouble()
            val pathDiff = deltaT * cSound
            val angle = Math.toDegrees(asin((pathDiff / micDist).coerceIn(-1.0, 1.0)))
            val dist = 1.0 / cos(Math.toRadians(angle)) * micDist
            val mag = ks.size * 20.0 + 60.0
            val buf = ShortArray(256) // Dummy buffer placeholder
            list += SpeakerInfo(nextId++, dist, angle, mag, buf)
        }
        return list
    }
}
