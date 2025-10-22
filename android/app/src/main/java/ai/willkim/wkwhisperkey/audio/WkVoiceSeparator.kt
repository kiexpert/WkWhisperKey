package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator v2
 * 짧은 파장 기반 위상 정합 화자 분리기
 *  - λ <= 0.2 m 핵심밴드, 0.2~1.0 m 보조밴드
 *  - 연립 일치 검증(>=3개 핵심밴드)
 *  - Δindex <= 0.2 m 한계
 */

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
    val distance: Double
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    private val c = 343.0
    private val lambdaCoreMax = 0.2      // 핵심 파장 상한
    private val lambdaAssistMax = 1.0    // 보조 파장 상한
    private val deltaIndexMax = max(1, floor((lambdaAssistMax / c) * sampleRate).toInt()) // 0.2m→≈25

    private var nextId = 1000
    private var activeKeys = mutableListOf<VoiceKey>()

    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val pre = activeKeys.toList()
        val (preSpk, resL, resR) = preprocessByVoiceKeys(pre, L, R)
        val newKeys = postprocessResidual(resL, resR)
        activeKeys = mergeKeys(pre, newKeys)
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
                residualL[i] = l - s
                if (j in residualR.indices) residualR[j] = r - s
            }

            val rms = sqrt(spk.sumOf { it * it } / spk.size)
            val eDb = 20 * log10(rms / 32768.0 + 1e-9) + 120.0
            val dist = abs(d) / sampleRate.toDouble() * c

            result += SpeakerSignal(key.id, spk, eDb, d, dist)
        }

        return Triple(result, residualL, residualR)
    }

    // -------------------- 후처리 --------------------
    private fun postprocessResidual(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)

        // 밴드별 파장과 구분
        val coreBands = bands.filter { c / it <= lambdaCoreMax }
        val assistBands = bands.filter { c / it in (lambdaCoreMax..lambdaAssistMax) }

        // 밴드별 Δindex 계산
        val deltaIdxByBand = mutableMapOf<Double, Int>()
        val snrByBand = mutableMapOf<Double, Double>()
        for (f in bands) {
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val phaseL = atan2(fftL[bin].imag, fftL[bin].real)
            val phaseR = atan2(fftR[bin].imag, fftR[bin].real)
            var dPhi = phaseR - phaseL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI
            val deltaIdx = (dPhi / (2 * Math.PI * f) * sampleRate).roundToInt()
            if (abs(deltaIdx) <= deltaIndexMax) {
                deltaIdxByBand[f] = deltaIdx
                val magL = sqrt(fftL[bin].real * fftL[bin].real + fftL[bin].imag * fftL[bin].imag)
                val magR = sqrt(fftR[bin].real * fftR[bin].real + fftR[bin].imag * fftR[bin].imag)
                snrByBand[f] = (magL + magR) / 2.0
            }
        }

        // 연립 일치 검증: 핵심밴드에서 3개 이상 일치
        val groups = mutableListOf<List<Double>>()
        val sorted = deltaIdxByBand.toList().sortedBy { it.second }
        for ((f, di) in sorted) {
            val cluster = sorted.filter { abs(it.second - di) <= 1 }
                .map { it.first }
            if (cluster.size >= 3 && cluster.any { it in coreBands }) groups += cluster
        }

        // 그룹별 대표 Δindex 계산 및 키 생성
        val newKeys = mutableListOf<VoiceKey>()
        for (grp in groups.distinct()) {
            val idxs = grp.mapNotNull { deltaIdxByBand[it] }
            val avgIdx = idxs.average().roundToInt()
            val freqs = grp
            val avgEnergy = freqs.mapNotNull { snrByBand[it] }.average()
            val dominant = freqs.maxByOrNull { snrByBand[it] ?: 0.0 } ?: freqs.first()
            newKeys += VoiceKey(nextId++, dominant, avgIdx, 20 * log10(avgEnergy + 1e-9))
        }

        return newKeys
    }

    // -------------------- 키 병합 --------------------
    private fun mergeKeys(old: List<VoiceKey>, new: List<VoiceKey>): MutableList<VoiceKey> {
        val merged = mutableListOf<VoiceKey>()
        merged += old
        for (n in new) {
            val near = old.find {
                abs(it.deltaIndex - n.deltaIndex) < 3 && abs(it.freq - n.freq) < 50
            }
            if (near == null) merged += n
        }
        merged.sortByDescending { it.energy }
        return merged.toMutableList()
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
