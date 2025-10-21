package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator v2
 *  └─ 다중 발성키 화자 클러스터링 (8밴드 스펙트럼 서명 기반)
 *
 * (C) 2025 Will Kim (kiexpert@kivilab.co.kr)
 */
data class VoiceKey(
    val id: Int,
    val freq: Double,
    val deltaIndex: Int,
    val energy: Double,
    val bandEnergies: DoubleArray = DoubleArray(8) // 정규화된 밴드 비율
)

data class SpeakerCluster(
    val id: Int,
    val keys: MutableList<VoiceKey>,
    val representative: VoiceKey
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
    private var nextId = 1000
    private val maxSampleDelay = (sampleRate / bands.first()).toInt()
    public var activeKeys = mutableListOf<VoiceKey>()

    /** L/R 입력 → 화자별 분리 결과 */
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val clusters = clusterVoiceKeys(preKeys)
        val speakers = mutableListOf<SpeakerSignal>()

        // 각 클러스터 대표키로 전처리
        for (cluster in clusters) {
            val rep = cluster.representative
            val speaker = synthesizeFromKey(rep, L, R)
            speakers += speaker
        }

        val (resL, resR) = computeResidual(L, R, speakers)
        val newKeys = postprocessResidual(resL, resR)
        activeKeys = mergeKeys(preKeys, newKeys)
        return speakers
    }

    /** 단일 키 → Delay-And-Sum 기반 음원 합성 */
    private fun synthesizeFromKey(key: VoiceKey, Lsrc: DoubleArray, Rsrc: DoubleArray): SpeakerSignal {
        val d = key.deltaIndex
        val spk = DoubleArray(Lsrc.size)
        val residualL = Lsrc.copyOf()
        val residualR = Rsrc.copyOf()

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
        val eDb = 20.0 * log10(rms / 32768.0 + 1e-9) + 120.0
        val dist = abs(d) / sampleRate.toDouble() * 343.0

        return SpeakerSignal(key.id, spk, eDb, d, dist)
    }

    /** 잔여음원 계산 */
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

    /** 잔여음원 FFT로 신규 키 탐색 */
    private fun postprocessResidual(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val newKeys = mutableListOf<VoiceKey>()
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)

        for ((bIndex, f) in bands.withIndex()) {
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val phaseL = atan2(fftL[bin].imag, fftL[bin].real)
            val phaseR = atan2(fftR[bin].imag, fftR[bin].real)
            val deltaPhi = phaseR - phaseL
            val deltaIndex = (deltaPhi / (2 * Math.PI * f) * sampleRate).roundToInt()
            if (abs(deltaIndex) < maxSampleDelay) {
                val magL = sqrt(fftL[bin].real.pow(2) + fftL[bin].imag.pow(2))
                val magR = sqrt(fftR[bin].real.pow(2) + fftR[bin].imag.pow(2))
                val energy = (magL + magR) / 2.0
                val bandEnergies = DoubleArray(bands.size) { 0.0 }
                bandEnergies[bIndex] = energy
                newKeys += VoiceKey(nextId++, f, deltaIndex, energy, bandEnergies)
            }
        }
        return newKeys
    }

    /** 키 클러스터링 (8밴드 스펙트럼 유사도) */
    private fun clusterVoiceKeys(keys: List<VoiceKey>): List<SpeakerCluster> {
        val clusters = mutableListOf<MutableList<VoiceKey>>()
        for (k in keys) {
            val found = clusters.find { simCosine(k.bandEnergies, it.first().bandEnergies) > 0.9 }
            if (found != null) found += k else clusters += mutableListOf(k)
        }
        return clusters.map {
            val rep = it.maxByOrNull { k -> k.energy }!!
            SpeakerCluster(rep.id, it, rep)
        }
    }

    private fun simCosine(a: DoubleArray, b: DoubleArray): Double {
        val dot = a.indices.sumOf { i -> a[i] * b[i] }
        val na = sqrt(a.sumOf { it * it })
        val nb = sqrt(b.sumOf { it * it })
        return if (na * nb == 0.0) 0.0 else dot / (na * nb)
    }

    /** 키 병합 */
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

    // 간단한 FFT
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
