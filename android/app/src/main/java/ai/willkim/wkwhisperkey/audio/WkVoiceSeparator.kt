package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*

/**
 * WkVoiceSeparator v6.0
 * -------------------------------------------------------
 * - Fold5 모드 자동감지 기반 마이크거리 추정
 * - 위상차(ΔΦ) 연립방정식 검증 기반 물리거리 산출
 * - 물리적으로 검증된 단일 발성키만 생성 (one or none)
 * - 에너지비율 기반 화자 클러스터링
 */
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
    companion object {
        // --- 물리 상수 ---
        private const val SPEED_OF_SOUND = 343.0        // m/s
        private const val MIC_DISTANCE_MAX_MM = 200.0   // mm (인간 고막 기준 상한)

        // --- 파라미터 ---
        private const val RESIDUAL_ATTENUATION = 0.6
        private const val ENERGY_SIM_THRESHOLD = 0.85
        private const val ENERGY_MIN_THRESHOLD = 1e-9
        private const val DEFAULT_ENERGY_NORM = 32768.0
        private const val BASE_ENERGY_OFFSET_DB = 120.0

        // --- 투표 관련 ---
        private const val EXCLUDE_LOW_FREQ = 1          // 저주파 제외
        private const val TOP_BANDS_FOR_VOTE = 6        // 상위 6밴드만 투표

        // --- Fold5 모드 자동 감지 기반 마이크 거리 추정 ---
        fun estimateMicDistanceMm(): Double {
            val context = WkApp.instance.applicationContext
            val wm = context.getSystemService(WindowManager::class.java)
            val metrics = DisplayMetrics()
            wm?.defaultDisplay?.getRealMetrics(metrics)

            val width = metrics.widthPixels.toDouble()
            val height = metrics.heightPixels.toDouble()
            val aspect = width / height
            val orientation = context.resources.configuration.orientation

            val mode = when {
                aspect > 1.9 -> "UNFOLDED"
                orientation == Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                else -> "PORTRAIT"
            }

            val distMm = when (mode) {
                "PORTRAIT" -> 20.0
                "LANDSCAPE" -> 150.0
                "UNFOLDED" -> 180.0
                else -> 20.0
            }
            return distMm.coerceAtMost(MIC_DISTANCE_MAX_MM)
        }
    }

    private var nextKeyId = 1000
    private var nextSpeakerId = 1
    private var activeKeys = mutableListOf<VoiceKey>()
    fun getActiveKeys(): List<VoiceKey> = activeKeys.toList()

    // ------------------------------------------------------------
    // 메인 루프
    // ------------------------------------------------------------
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val (preSpk, resL, resR) = preprocessByVoiceKeys(preKeys, L, R)
        detectVoiceKey(resL, resR)?.let { key ->
            activeKeys.add(key)
        }
        activeKeys = activeKeys.sortedByDescending { it.energy }.toMutableList()
        return clusterByEnergyPattern(activeKeys)
    }

    // ------------------------------------------------------------
    // 전처리
    // ------------------------------------------------------------
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
            val eDb = 20 * log10(rms / DEFAULT_ENERGY_NORM + ENERGY_MIN_THRESHOLD) + BASE_ENERGY_OFFSET_DB
            result += SpeakerSignal(key.id, listOf(key), eDb, d, key.distanceMm)
        }
        return Triple(result, residualL, residualR)
    }

    // ------------------------------------------------------------
    // 발성키 탐지 (물리거리 연립 검증 + 단일 발성키)
    // ------------------------------------------------------------
    private fun detectVoiceKey(Lres: DoubleArray, Rres: DoubleArray): VoiceKey? {
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)
        val micDistMm = estimateMicDistanceMm()

        val phaseDiff = DoubleArray(bands.size)
        val mag = DoubleArray(bands.size)

        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val phL = atan2(fftL[bin].imag, fftL[bin].real)
            val phR = atan2(fftR[bin].imag, fftR[bin].real)
            phaseDiff[i] = phR - phL
            if (phaseDiff[i] > Math.PI) phaseDiff[i] -= 2 * Math.PI
            if (phaseDiff[i] < -Math.PI) phaseDiff[i] += 2 * Math.PI

            val magL = sqrt(fftL[bin].real * fftL[bin].real + fftL[bin].imag * fftL[bin].imag)
            val magR = sqrt(fftR[bin].real * fftR[bin].real + fftR[bin].imag * fftR[bin].imag)
            mag[i] = (magL + magR) / 2.0
        }

        val (dn, distMm) = resolveDeltaIndexByVoting(phaseDiff, bands, mag)
        if (dn == 0 || distMm <= 0.0) return null // 검증 실패

        // 단일발성키 확정
        val maxIdx = mag.indices.maxByOrNull { mag[it] } ?: return null
        val f = bands[maxIdx]
        val e = mag[maxIdx].coerceAtLeast(ENERGY_MIN_THRESHOLD)
        return VoiceKey(nextKeyId++, f, dn, e, distMm)
    }

    // ------------------------------------------------------------
    // 위상차 기반 Δindex 연립방정식 투표
    // ------------------------------------------------------------
    private fun resolveDeltaIndexByVoting(
        phaseDiff: DoubleArray,
        freqs: DoubleArray,
        energy: DoubleArray
    ): Pair<Int, Double> {

        val maxDelaySamples = (MIC_DISTANCE_MAX_MM / 1000.0 / SPEED_OF_SOUND * sampleRate)
            .roundToInt().coerceAtLeast(1)
        val M = 6
        val order = (freqs.indices).sortedByDescending { energy[it] }
        val voteBands = order.drop(EXCLUDE_LOW_FREQ).take(TOP_BANDS_FOR_VOTE)

        val votes = mutableMapOf<Int, Double>()
        val contrib = mutableMapOf<Int, MutableList<Int>>()

        for (j in voteBands) {
            val fj = freqs[j]
            val ph = phaseDiff[j]
            for (m in -M..M) {
                val dn = ((ph + 2.0 * Math.PI * m) / (2.0 * Math.PI * fj) * sampleRate).roundToInt()
                if (abs(dn) <= maxDelaySamples) {
                    val w = max(energy[j], 1e-12)
                    votes[dn] = (votes[dn] ?: 0.0) + w
                    contrib.getOrPut(dn) { mutableListOf() }.add(j)
                }
            }
        }

        var bestDn = 0
        var bestDist = 0.0
        var bestVotes = 0

        for ((dn, _) in votes.entries.sortedByDescending { it.value }) {
            val count = contrib[dn]?.distinct()?.size ?: 0
            when {
                count >= 3 -> {
                    bestDn = dn
                    bestDist = abs(dn) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0
                    break
                }
                count >= 2 && bestVotes < 2 -> {
                    bestDn = dn
                    bestDist = abs(dn) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0
                    bestVotes = count
                }
            }
        }

        return bestDn to bestDist
    }

    // ------------------------------------------------------------
    // 화자 클러스터링 (에너지비율 기반)
    // ------------------------------------------------------------
    private fun clusterByEnergyPattern(keys: List<VoiceKey>): List<SpeakerSignal> {
        if (keys.isEmpty()) return emptyList()
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
        return dot / (normA * normB + ENERGY_MIN_THRESHOLD)
    }

    // ------------------------------------------------------------
    // FFT
    // ------------------------------------------------------------
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
