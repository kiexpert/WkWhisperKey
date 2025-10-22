package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator v3
 * 에너지 패턴 기반 멀티발성키 화자 분리기
 *  - λ <= 0.2 m 핵심밴드, 0.2~1.0 m 보조밴드
 *  - 8밴드 정규화 에너지 패턴 유사도(코사인)
 *  - 위상차 무시, 반사음 포함 동일화자 클러스터링
 *  - 거리(mm) 단위 출력
 */

private const val RESIDUAL_ATTENUATION = 0.6   // 감쇠 비율 (1.0=완전차감, 0.0=미차감)
private const val ENERGY_SIM_THRESHOLD = 0.85  // 화자 유사도 임계값

data class VoiceKey(
    val id: Int,
    val freq: Double,
    val deltaIndex: Int,
    val energy: Double
)

data class SpeakerSignal(
    val id: Int,
    val samples: DoubleArray,
    val energy: Double,
    val deltaIndex: Int,
    val distance: Double // mm 단위
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    private val c = 343.0
    private val lambdaCoreMax = 0.2
    private val lambdaAssistMax = 1.0
    private val deltaIndexMax = max(1, floor((lambdaAssistMax / c) * sampleRate).toInt())

    private var nextId = 1000
    private var activeKeys = mutableListOf<VoiceKey>()
    fun getActiveKeys(): List<VoiceKey> = activeKeys.toList()

    // -------------------- 메인 루프 --------------------
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val pre = activeKeys.toList()
        val (preSpk, resL, resR) = preprocessByVoiceKeys(pre, L, R)
        val newKeys = postprocessResidual(resL, resR)
        activeKeys = newKeys.toMutableList()
        return preSpk
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
            val distMm = abs(d) / sampleRate.toDouble() * c * 1000.0 // mm 단위

            result += SpeakerSignal(key.id, spk, eDb, d, distMm)
        }

        return Triple(result, residualL, residualR)
    }

    // -------------------- 후처리: 발성키 생성 --------------------
    private fun postprocessResidual(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)

        val coreBands = bands.filter { c / it <= lambdaCoreMax }
        val deltaIdxByBand = mutableMapOf<Double, Int>()
        val energyByBand = mutableMapOf<Double, Double>()

        for (f in bands) {
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val magL = sqrt(fftL[bin].real * fftL[bin].real + fftL[bin].imag * fftL[bin].imag)
            val magR = sqrt(fftR[bin].real * fftR[bin].real + fftR[bin].imag * fftR[bin].imag)
            val energy = (magL + magR) / 2.0
            energyByBand[f] = energy

            val phaseL = atan2(fftL[bin].imag, fftL[bin].real)
            val phaseR = atan2(fftR[bin].imag, fftR[bin].real)
            val dPhi = (phaseR - phaseL).let {
                when {
                    it > Math.PI -> it - 2 * Math.PI
                    it < -Math.PI -> it + 2 * Math.PI
                    else -> it
                }
            }
            val deltaIdx = (dPhi / (2 * Math.PI * f) * sampleRate).roundToInt()
            if (abs(deltaIdx) <= deltaIndexMax) deltaIdxByBand[f] = deltaIdx
        }

        // 밴드 에너지 벡터
        val energyVec = bands.map { f -> energyByBand[f] ?: 0.0 }.toDoubleArray()
        val norm = sqrt(energyVec.sumOf { it * it }) + 1e-9
        val normalizedVec = energyVec.map { it / norm }.toDoubleArray()

        // 모든 밴드에서 클러스터링할 후보 1개만 생성
        val newKeys = mutableListOf<VoiceKey>()
        newKeys += VoiceKey(nextId++, bands.first(), 0, 20 * log10(norm + 1e-9))

        // 단일 화자 → 멀티키화자 클러스터링 준비
        return clusterByEnergyPattern(newKeys, mapOf(newKeys.first().id to normalizedVec))
    }

    // -------------------- 에너지 벡터 기반 클러스터링 --------------------
    private fun clusterByEnergyPattern(keys: List<VoiceKey>, bandEnergies: Map<Int, DoubleArray>): List<VoiceKey> {
        val groups = mutableListOf<MutableList<VoiceKey>>()

        for (key in keys) {
            val vecA = bandEnergies[key.id] ?: continue
            var assigned = false

            for (group in groups) {
                val ref = group.first()
                val vecB = bandEnergies[ref.id] ?: continue
                val sim = cosineSim(vecA, vecB)
                if (sim > ENERGY_SIM_THRESHOLD) {
                    group += key
                    assigned = true
                    break
                }
            }
            if (!assigned) groups += mutableListOf(key)
        }

        val result = mutableListOf<VoiceKey>()
        for (grp in groups) {
            val meanFreq = grp.map { it.freq }.average()
            val meanEnergy = grp.map { it.energy }.average()
            result += VoiceKey(nextId++, meanFreq, 0, meanEnergy)
        }
        return result
    }

    private fun cosineSim(a: DoubleArray, b: DoubleArray): Double {
        val dot = a.zip(b).sumOf { it.first * it.second }
        val normA = sqrt(a.sumOf { it * it })
        val normB = sqrt(b.sumOf { it * it })
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
