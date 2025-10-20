package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator
 * 위상차 기반 화자 분리기 (Phase-Delay Separation Engine)
 *
 * 1. 기존 발성키(Δsample) 기반 전처리 (FFT 불필요)
 * 2. 잔여음원에서 FFT로 신규 발성키 탐색 (후처리)
 * 3. 경계 보정 및 overshoot 보정 포함
 *
 * (C) 2025 Will Kim (kiexpert@kivilab.co.kr)
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
    val deltaIndex: Int
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    private var nextId = 1000
    private val maxSampleDelay = (sampleRate / bands.first()).toInt()
    private var activeKeys = mutableListOf<VoiceKey>()

    /**
     * L/R 음원 입력 → 화자별 분리 결과 반환
     */
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val preSpeakers = preprocessByVoiceKeys(preKeys, L, R)
        val (resL, resR) = computeResidual(L, R, preSpeakers)
        val newKeys = postprocessResidual(resL, resR)
        activeKeys = mergeKeys(preKeys, newKeys)
        return preSpeakers
    }

    /**
     * 기존 발성키 기반 전처리 (FFT 없음)
     */
    private fun preprocessByVoiceKeys(keys: List<VoiceKey>, L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val result = mutableListOf<SpeakerSignal>()
        var residualL = L.copyOf()
        var residualR = R.copyOf()

        for (key in keys.sortedByDescending { it.energy }) {
            val delta = key.deltaIndex
            val speaker = DoubleArray(L.size)

            for (i in 0 until L.size) {
                val idxL = i
                val idxR = i - delta

                val ref = when {
                    idxL !in residualL.indices || idxR !in residualR.indices -> 0.0
                    else -> (residualL[idxL] + residualR[idxR]) * 0.5
                }

                val overshoot = abs(delta).coerceAtMost(L.size)
                val micCount = 2
                val gainComp = 1.0 - (overshoot - overshoot / micCount.toDouble()) / L.size
                val compensated = ref * gainComp

                speaker[i] = compensated
                residualL[idxL] -= compensated
                if (idxR in residualR.indices) residualR[idxR] -= compensated
            }

            result += SpeakerSignal(key.id, speaker, key.energy, delta)
        }

        return result
    }

    /**
     * 잔여음원 계산
     */
    private fun computeResidual(L: DoubleArray, R: DoubleArray, speakers: List<SpeakerSignal>): Pair<DoubleArray, DoubleArray> {
        val resL = L.copyOf()
        val resR = R.copyOf()
        for (spk in speakers) {
            for (i in spk.samples.indices) {
                resL[i] -= spk.samples[i]
                val idxR = i - spk.deltaIndex
                if (idxR in resR.indices) resR[idxR] -= spk.samples[i]
            }
        }
        return resL to resR
    }

    /**
     * 잔여음원 FFT로 신규 발성키 탐색
     */
    private fun postprocessResidual(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val newKeys = mutableListOf<VoiceKey>()
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)

        for (f in bands) {
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val phaseL = atan2(fftL[bin].imag, fftL[bin].real)
            val phaseR = atan2(fftR[bin].imag, fftR[bin].real)
            val deltaPhi = phaseR - phaseL
            val deltaIndex = (deltaPhi / (2 * Math.PI * f) * sampleRate).roundToInt()
            if (abs(deltaIndex) < maxSampleDelay) {
                val magL = sqrt(fftL[bin].real * fftL[bin].real + fftL[bin].imag * fftL[bin].imag)
                val magR = sqrt(fftR[bin].real * fftR[bin].real + fftR[bin].imag * fftR[bin].imag)
                val energy = 20.0 * log10((magL + magR) / 2.0 + 1e-9)
                newKeys += VoiceKey(nextId++, f, deltaIndex, energy)
            }
        }
        return newKeys
    }

    /**
     * 키 병합 (중복 최소화)
     */
    private fun mergeKeys(old: List<VoiceKey>, new: List<VoiceKey>): MutableList<VoiceKey> {
        val merged = mutableListOf<VoiceKey>()
        merged += old
        for (n in new) {
            val near = old.find { abs(it.deltaIndex - n.deltaIndex) < 4 && abs(it.freq - n.freq) < 80 }
            if (near == null) merged += n
        }
        if (merged.size > 8) {
            merged.sortByDescending { it.energy }
            while (merged.size > 8) merged.removeLast()
        }
        return merged.toMutableList()
    }

    // 간단한 FFT (Cooley–Tukey radix-2)
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
        operator fun times(o: Complex) = Complex(real * o.real - imag * o.imag, real * o.imag + imag * o.real)

        companion object {
            fun polar(r: Double, theta: Double) = Complex(r * cos(theta), r * sin(theta))
        }
    }
}
