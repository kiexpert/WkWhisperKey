package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

/**
 * WkVoiceSeparator (Stable Single-Speaker Detection)
 * --------------------------------------------------
 * 위상차 기반 다중밴드 정합 화자 분리기 (안정화 버전)
 *
 * 1️⃣ 매 프레임 신규 화자 최대 1명 (가장 강한 정합 키만 등록)
 * 2️⃣ 기존 화자는 전처리에서 유지/갱신
 * 3️⃣ Δindex 정합도(agree) < 2 → 무효
 * 4️⃣ FFT 기반 다중밴드 위상 정합 및 최소제곱 정련
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
    val deltaIndex: Int,
    val distance: Double
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    private var nextId = 1000
    private val maxSampleDelay = (sampleRate / bands.first()).toInt()
    private var activeKeys = mutableListOf<VoiceKey>()

    // --- 교체: separate() ---
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val (speakers, resL, resR) = preprocessByVoiceKeys(preKeys, L, R) // residual 반환
        val newKeys = postprocessResidual(resL, resR)
        activeKeys = mergeKeys(preKeys, newKeys)
        return speakers
    }
    
    // --- 교체: preprocessByVoiceKeys() => residual 함께 반환 ---
    private fun preprocessByVoiceKeys(
        keys: List<VoiceKey>,
        Lsrc: DoubleArray,
        Rsrc: DoubleArray
    ): Triple<List<SpeakerSignal>, DoubleArray, DoubleArray> {
    
        val residualL = Lsrc.copyOf()
        val residualR = Rsrc.copyOf()
        val result = mutableListOf<SpeakerSignal>()
    
        // 에너지 높은 키부터 적용
        for (key in keys.sortedByDescending { it.energy }) {
            val d = key.deltaIndex
            val spk = DoubleArray(Lsrc.size)
    
            // i는 L 기준, R는 (i - d) 지연 정렬
            for (i in residualL.indices) {
                val j = i - d
                val r = if (j in residualR.indices) residualR[j] else 0.0
                val l = residualL[i]
                val s = 0.5 * (l + r)                // delay-and-sum 추정치
    
                spk[i] = s
                residualL[i] = l - s                  // 양쪽 동시 차감
                if (j in residualR.indices) residualR[j] = r - s
            }
    
            // 에너지 재계산(RMS)
            var sum = 0.0
            for (x in spk) sum += x * x
            val rms = kotlin.math.sqrt(sum / spk.size)
            val eDb = 20.0 * kotlin.math.log10(rms / 32768.0 + 1e-9) + 120.0
    
            // 거리 계산: Δindex → 초 단위 지연 → 거리(m)
            val dist = abs(key.deltaIndex) / sampleRate.toDouble() * 343.0
            
            result += SpeakerSignal(
                key.id,
                speaker,
                key.energy,
                key.deltaIndex,
                dist
            )
        }
    
        return Triple(result, residualL, residualR)
    }
    
    // --- 교체: computeResidual() 더 이상 사용하지 않음 (제거하거나 미사용 표시) ---
    // postprocessResidual()는 preprocess가 반환한 residualL/residualR 사용

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

    /** 잔여음원 FFT + 다중밴드 정합기반 단일 발성키 탐색 */
    private fun postprocessResidual(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)

        val phaseL = DoubleArray(bands.size)
        val phaseR = DoubleArray(bands.size)
        val energy = DoubleArray(bands.size)

        for (k in bands.indices) {
            val f = bands[k]
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val rl = fftL[bin]
            val rr = fftR[bin]
            phaseL[k] = atan2(rl.imag, rl.real)
            phaseR[k] = atan2(rr.imag, rr.real)
            energy[k] = 20.0 * log10(
                sqrt(rl.real * rl.real + rl.imag * rl.imag + rr.real * rr.real + rr.imag * rr.imag) / 2.0 + 1e-9
            )
        }

        // 한 명만 검출 (가장 강력한 정합값)
        val (deltaIdx, agree) = solveDelayByMultiBand(phaseL, phaseR, bands, energy)
        if (agree < 2 || abs(deltaIdx) >= maxSampleDelay) return emptyList()

        val eAvg = energy.average()
        return listOf(VoiceKey(nextId++, bands.first(), deltaIdx, eAvg))
    }

    /** 다중밴드 위상정합 → Δindex 산출 */
    private fun solveDelayByMultiBand(
        phasesL: DoubleArray,
        phasesR: DoubleArray,
        bands: DoubleArray,
        energies: DoubleArray
    ): Pair<Int, Int> {
        val fs = sampleRate
        val dnMax = (fs / bands.first()).toInt()
        val dphi = DoubleArray(bands.size) {
            var v = phasesR[it] - phasesL[it]
            while (v > Math.PI) v -= 2 * Math.PI
            while (v < -Math.PI) v += 2 * Math.PI
            v
        }
        val w = energies.map { it.coerceAtLeast(1e-9) }.toDoubleArray()
        val tauMax = dnMax.toDouble() / fs
        val epsTau = 0.5 / fs

        // 허프 보팅
        val votes = mutableMapOf<Int, Double>()
        for (k in bands.indices) {
            val f = bands[k]
            for (m in -2..2) {
                val tau = (dphi[k] + 2 * Math.PI * m) / (2 * Math.PI * f)
                if (abs(tau) <= tauMax) {
                    val bin = floor(tau / epsTau).toInt()
                    votes[bin] = (votes[bin] ?: 0.0) + w[k]
                }
            }
        }
        if (votes.isEmpty()) return 0 to 0
        val bestBin = votes.maxBy { it.value }.key
        val tauStar = (bestBin + 0.5) * epsTau

        // 최소제곱 정련
        var num = 0.0
        var den = 0.0
        var agree = 0
        for (k in bands.indices) {
            val f = bands[k]
            val mk = ((tauStar * 2 * Math.PI * f - dphi[k]) / (2 * Math.PI)).roundToInt()
            val tauK = (dphi[k] + 2 * Math.PI * mk) / (2 * Math.PI * f)
            if (abs(tauK - tauStar) <= epsTau) agree++
            num += w[k] * f * (dphi[k] - 2 * Math.PI * mk)
            den += w[k] * f * f
        }
        val tauHat = num / (2 * Math.PI * den).coerceAtLeast(1e-9)
        val dIndex = (tauHat * fs).roundToInt().coerceIn(-dnMax, dnMax)
        return dIndex to agree
    }

    /** 키 병합 (중복 최소화) */
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

    // --- FFT ---
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
        companion object { fun polar(r: Double, theta: Double) = Complex(r * cos(theta), r * sin(theta)) }
    }
}
