package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator v4
 * 위상차 유지 + 에너지비율 유사도 클러스터링
 *  - 각 밴드 위상차 기반 발성키(VoiceKey) 생성
 *  - 발성키 집합을 8밴드 에너지비율(정규화 벡터)로 화자(Speaker) 클러스터링
 *  - 위상 정보로 거리(mm) 산출, 반사음 포함 동일화자 묶기
 */

private const val RESIDUAL_ATTENUATION = 0.6     // 음원 감쇠 비율 (1.0=완전차감)
private const val ENERGY_SIM_THRESHOLD = 0.85    // 화자 유사도 임계값 (코사인)
private const val ANGLE_SPREAD_DEG = 60.0        // 시각화용 최대 좌우각
private const val SPEED_OF_SOUND = 343.0         // m/s

data class VoiceKey(
    val id: Int,
    val freq: Double,
    val deltaIndex: Int,
    val energy: Double,
    val distanceMm: Double
)

data class SpeakerSignal(
    val id: Int,
    val keys: List<VoiceKey>,
    val energy: Double,
    val deltaIndex: Int,
    val distance: Double // mm 단위
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    private var nextKeyId = 1000
    private var nextSpeakerId = 1
    private var activeKeys = mutableListOf<VoiceKey>()
    fun getActiveKeys(): List<VoiceKey> = activeKeys.toList()

    // -------------------- 메인 --------------------
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val (preSpeakers, resL, resR) = preprocessByVoiceKeys(preKeys, L, R)
        val newKeys = detectVoiceKeys(resL, resR)
        activeKeys = mergeKeys(preKeys, newKeys).toMutableList()
        return clusterByEnergyPattern(activeKeys)
    }

    // -------------------- 전처리 --------------------
    private fun preprocessByVoiceKeys(
        keys: List<VoiceKey>, Lsrc: DoubleArray, Rsrc: DoubleArray
    ): Triple<List<SpeakerSignal>, DoubleArray, DoubleArray> {
        val residualL = Lsrc.copyOf()
        val residualR = Rsrc.copyOf()
        val result = mutableListOf<SpeakerSignal>()

        for (key in keys.sortedByDescending { it.energy }) {
            val d = key.deltaIndex
            val spk = DoubleArray(Lsrc.size)
            for (i in residualL.indices) {
                val j = i - d
                val r = if (j in residualR.indices) residualR[j] else 0.0
                val l = residualL[i]
                val s = 0.5 * (l + r)
                spk[i] = s
                residualL[i] = l - s * RESIDUAL_ATTENUATION
                if (j in residualR.indices) residualR[j] = r - s * RESIDUAL_ATTENUATION
            }

            val rms = sqrt(spk.sumOf { it * it } / spk.size)
            val eDb = 20 * log10(rms / 32768.0 + 1e-9) + 120.0

            result += SpeakerSignal(
                key.id, listOf(key), eDb, d, key.distanceMm
            )
        }
        return Triple(result, residualL, residualR)
    }

    // -------------------- 발성키 탐지 (위상차 유지) --------------------
    private fun detectVoiceKeys(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)
        val keys = mutableListOf<VoiceKey>()

        for (f in bands) {
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val phaseL = atan2(fftL[bin].imag, fftL[bin].real)
            val phaseR = atan2(fftR[bin].imag, fftR[bin].real)
            var dPhi = phaseR - phaseL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI

            val deltaIdx = (dPhi / (2 * Math.PI * f) * sampleRate).roundToInt()
            val distMm = abs(deltaIdx) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0
            val magL = sqrt(fftL[bin].real * fftL[bin].real + fftL[bin].imag * fftL[bin].imag)
            val magR = sqrt(fftR[bin].real * fftR[bin].real + fftR[bin].imag * fftR[bin].imag)
            val energy = (magL + magR) / 2.0

            if (abs(deltaIdx) < sampleRate / 100) { // 안전 한계 내
                keys += VoiceKey(nextKeyId++, f, deltaIdx, energy, distMm)
            }
        }

        // Δindex ±1 내의 밴드를 평균화하여 발성키 통합
        return keys.groupBy { it.deltaIndex }.map { (_, grp) ->
            val avgF = grp.map { it.freq }.average()
            val avgE = grp.map { it.energy }.average()
            val avgD = grp.map { it.distanceMm }.average()
            VoiceKey(nextKeyId++, avgF, grp.first().deltaIndex, avgE, avgD)
        }
    }

    // -------------------- 발성키 병합 --------------------
    private fun mergeKeys(old: List<VoiceKey>, new: List<VoiceKey>): List<VoiceKey> {
        val merged = mutableListOf<VoiceKey>()
        merged += old
        for (n in new) {
            val near = old.find { abs(it.deltaIndex - n.deltaIndex) < 3 }
            if (near == null) merged += n
        }
        return merged.sortedByDescending { it.energy }
    }

    // -------------------- 에너지 패턴 기반 화자 클러스터링 --------------------
    private fun clusterByEnergyPattern(keys: List<VoiceKey>): List<SpeakerSignal> {
        val groups = mutableListOf<MutableList<VoiceKey>>()

        for (k in keys) {
            var assigned = false
            for (grp in groups) {
                val sim = energyPatternSimilarity(k, grp.first())
                if (sim > ENERGY_SIM_THRESHOLD) {
                    grp += k
                    assigned = true
                    break
                }
            }
            if (!assigned) groups += mutableListOf(k)
        }

        val result = mutableListOf<SpeakerSignal>()
        for (grp in groups) {
            val id = nextSpeakerId++
            val avgE = grp.map { it.energy }.average()
            val avgΔ = grp.map { it.deltaIndex }.average().roundToInt()
            val avgD = grp.map { it.distanceMm }.average()
            result += SpeakerSignal(id, grp, avgE, avgΔ, avgD)
        }
        return result
    }

    private fun energyPatternSimilarity(a: VoiceKey, b: VoiceKey): Double {
        val aVec = doubleArrayOf(a.energy, a.freq)
        val bVec = doubleArrayOf(b.energy, b.freq)
        val dot = aVec.zip(bVec).sumOf { it.first * it.second }
        val normA = sqrt(aVec.sumOf { it * it })
        val normB = sqrt(bVec.sumOf { it * it })
        return dot / (normA * normB + 1e-9)
    }

    // -------------------- FFT --------------------
    private fun fft(x: DoubleArray): Array<Complex> {
        val N = x.size
        if (N <= 1) return arrayOf(Complex(x[0], 0.0))
        val even = fft(x.filterIndexed { i, _ -> i % 2 == 0 }.toDoubleArray())
        val odd = fft(x.filterIndexed { i, _ -> i % 2 == 1 }.toDoubleArray())
        val result = Array(N) { Complex(0.0, 0.0) }
        for (k in 0 until N / 2) {
            val t = Complex.polar(1.0, -2 * Math.PI * k / N) * odd[k]
            result[k] = even[k] + t
            result[k + N / 2] = even[k] - t
        }
        return result
    }

    private data class Complex(val real: Double, val imag: Double) {
        operator fun plus(o: Complex) = Complex(real + o.real, imag + o.imag)
        operator fun minus(o: Complex) = Complex(real - o.real, imag - o.imag)
        operator fun times(o: Complex) =
            Complex(real * o.real - imag * o.imag, real * o.imag + imag * o.real)

        companion object {
            fun polar(r: Double, theta: Double) = Complex(r * cos(theta), r * sin(theta))
        }
    }
}
