package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*

/**
 * WkVoiceSeparator v5.5
 * -------------------------------------------------------
 * - Fold5 모드 자동 감지 (세로/가로/펼침)
 * - 마이크 간 거리 자동 추정 및 거리 기반 위상 분석
 * - 8밴드 에너지비율 기반 화자 클러스터링
 * - Map 제거 → 배열 기반 고속화 (FFT 캐시 친화)
 * - 밴드별 노이즈 추적(EMA)
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
    val distance: Double // mm
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    companion object {
        // --- 물리 상수 ---
        private const val SPEED_OF_SOUND = 343.0
        private const val MIC_DISTANCE_MAX_MM = 200.0

        // --- 분석 파라미터 ---
        private const val RESIDUAL_ATTENUATION = 0.6
        private const val ENERGY_SIM_THRESHOLD = 0.85
        private const val MAX_CLUSTER_GAP = 3
        private const val ENERGY_MIN_THRESHOLD = 1e-9
        private const val DEFAULT_ENERGY_NORM = 32768.0
        private const val BASE_ENERGY_OFFSET_DB = 120.0

        // --- 노이즈 추적 ---
        private const val NOISE_EMA_ALPHA = 0.10
        private const val NEWKEY_SNR_FACTOR = 2.0
        private const val KEEPKEY_SNR_FACTOR = 1.5
        private const val WHISPER_IDX1 = 1  // 700Hz
        private const val WHISPER_IDX2 = 4  // 2500Hz

        // --------------------------------------------------------
        // Fold5 모드 자동 감지 기반 마이크 거리 추정
        // --------------------------------------------------------
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

    // 내부 상태
    private var nextKeyId = 1000
    private var nextSpeakerId = 1
    private var activeKeys = mutableListOf<VoiceKey>()

    // 밴드별 분석 버퍼
    private val noiseFloor = DoubleArray(bands.size) { 1e-9 }
    private val energyByBand = DoubleArray(bands.size)
    private val deltaIdxByBand = IntArray(bands.size)
    private val snrByBand = DoubleArray(bands.size)

    fun getActiveKeys(): List<VoiceKey> = activeKeys.toList()

    // ------------------------------------------------------------
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val (preSpk, resL, resR) = preprocessByVoiceKeys(preKeys, L, R)
        val newKeys = detectVoiceKeys(resL, resR)
        activeKeys = mergeKeys(preKeys, newKeys).toMutableList()
        return clusterByEnergyPattern(activeKeys)
    }

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
    private fun detectVoiceKeys(Lres: DoubleArray, Rres: DoubleArray): List<VoiceKey> {
        val N = Lres.size
        val fftL = fft(Lres)
        val fftR = fft(Rres)
        val micDistMm = estimateMicDistanceMm()

        // --- 밴드별 에너지 및 위상차 ---
        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val realL = fftL[bin].real
            val imagL = fftL[bin].imag
            val realR = fftR[bin].real
            val imagR = fftR[bin].imag

            val magL = hypot(realL, imagL)
            val magR = hypot(realR, imagR)
            energyByBand[i] = (magL + magR) * 0.5

            var dPhi = atan2(imagR, realR) - atan2(imagL, realL)
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI

            deltaIdxByBand[i] = (dPhi / (2 * Math.PI * f) * sampleRate).roundToInt()
            snrByBand[i] = energyByBand[i] / noiseFloor[i].coerceAtLeast(1e-9)

            // 노이즈 EMA 업데이트
            noiseFloor[i] = (1 - NOISE_EMA_ALPHA) * noiseFloor[i] + NOISE_EMA_ALPHA * energyByBand[i]
        }

        // --- 듀얼레퍼런스 속삭임 기준 통과 검사 ---
        val passWhisper =
            (snrByBand[WHISPER_IDX1] > NEWKEY_SNR_FACTOR) ||
            (snrByBand[WHISPER_IDX2] > NEWKEY_SNR_FACTOR)
        if (!passWhisper) return emptyList()

        // --- 발성키 생성 ---
        val keys = mutableListOf<VoiceKey>()
        for (i in bands.indices) {
            val deltaIdx = deltaIdxByBand[i]
            val deltaMm = abs(deltaIdx) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0
            val distMm = when {
                abs(deltaIdx) == 0 -> 0.0
                deltaMm >= MIC_DISTANCE_MAX_MM -> micDistMm / 2.0
                else -> ((micDistMm * micDistMm) - (deltaMm * deltaMm)) / (2 * deltaMm)
            }.coerceAtLeast(0.0)
            keys += VoiceKey(nextKeyId++, bands[i], deltaIdx, energyByBand[i], distMm)
        }

        // --- 발성키 병합 ---
        return keys.groupBy { it.deltaIndex }.map { (_, grp) ->
            val avgF = grp.map { it.freq }.average()
            val avgE = grp.map { it.energy }.average()
            val avgD = grp.map { it.distanceMm }.average()
            VoiceKey(nextKeyId++, avgF, grp.first().deltaIndex, avgE, avgD)
        }
    }

    // ------------------------------------------------------------
    private fun mergeKeys(old: List<VoiceKey>, new: List<VoiceKey>): List<VoiceKey> {
        val merged = mutableListOf<VoiceKey>()
        merged += old
        for (n in new) {
            val near = old.find { abs(it.deltaIndex - n.deltaIndex) < MAX_CLUSTER_GAP }
            if (near == null) merged += n
        }
        return merged.sortedByDescending { it.energy }
    }

    // ------------------------------------------------------------
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
        return dot / (normA * normB + ENERGY_MIN_THRESHOLD)
    }

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
