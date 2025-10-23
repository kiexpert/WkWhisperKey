package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*

/**
 * WkVoiceSeparator v5.8
 * -------------------------------------------------------
 * - Fold5 모드 자동감지 (세로/가로/펼침)
 * - 반파장 패딩 기반 위상 안정화
 * - 연립방정식(2~3밴드 일치) 기반 물리거리 확정
 * - FFT 캐시 토글 지원 (기본 false)
 * - 1개 확정된 발성키만 생성 (or 없음)
 * - 밴드별 노이즈 추적(EMA) 및 기존키 감쇠/드랍
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
    val distance: Double
)

class WkVoiceSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    companion object {
        private const val SPEED_OF_SOUND = 343.0
        private const val MIC_DISTANCE_MAX_MM = 200.0

        private const val RESIDUAL_ATTENUATION = 0.6
        private const val ENERGY_SIM_THRESHOLD = 0.85
        private const val MAX_CLUSTER_GAP = 3
        private const val ENERGY_MIN_THRESHOLD = 1e-9
        private const val DEFAULT_ENERGY_NORM = 32768.0
        private const val BASE_ENERGY_OFFSET_DB = 120.0

        private const val NOISE_EMA_ALPHA = 0.10
        private const val NEWKEY_SNR_FACTOR = 2.0
        private const val KEEPKEY_SNR_FACTOR = 1.5
        private const val WHISPER_IDX1 = 1
        private const val WHISPER_IDX2 = 4

        private const val DROP_THRESHOLD_FRAMES = 8
        private const val ENERGY_DECAY_RATE = 0.95

        private const val TOP_BANDS_FOR_VOTE = 6
        private const val EXCLUDE_LOW_FREQ = 1

        // ✅ FFT 캐시 토글
        private const val USE_FFT_CACHE = false

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
    private val keyLastSeen = mutableMapOf<Int, Int>()

    private val noiseFloor = DoubleArray(bands.size) { 1e-9 }
    private val phaseDiff = DoubleArray(bands.size)
    private val energyByBand = DoubleArray(bands.size)
    private val snrByBand = DoubleArray(bands.size)

    // FFT 캐시
    private var lastFftL: Array<Complex>? = null
    private var lastFftR: Array<Complex>? = null
    private var lastFrameHash = 0L

    fun getActiveKeys(): List<VoiceKey> = activeKeys.toList()
    fun getNoiseBandsCopy(): DoubleArray = noiseFloor.copyOf()

    // ------------------------------------------------------------
    fun separate(L: DoubleArray, R: DoubleArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
        val (preSpk, resL, resR) = preprocessByVoiceKeys(preKeys, L, R)
        detectVoiceKey(resL, resR)?.let {
            activeKeys = mergeKeys(preKeys, listOf(it)).toMutableList()
        }
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
    // ✅ 반파장 패딩 + FFT 캐시 토글형
    // ------------------------------------------------------------
    private fun detectVoiceKey(Lsrc: DoubleArray, Rsrc: DoubleArray): VoiceKey? {
        val N = Lsrc.size
        val micDistMm = estimateMicDistanceMm()

        // 반파장 확장
        val minFreq = bands.minOrNull() ?: 150.0
        val halfLambdaSamples = (sampleRate / (2 * minFreq)).roundToInt()
        val ext = halfLambdaSamples.coerceAtMost(N / 4)
        val paddedL = DoubleArray(N + 2 * ext)
        val paddedR = DoubleArray(N + 2 * ext)
        System.arraycopy(Lsrc, 0, paddedL, ext, N)
        System.arraycopy(Rsrc, 0, paddedR, ext, N)

        // 해시 계산 (FFT 캐시용)
        var hash = 1L
        if (USE_FFT_CACHE) {
            for (i in 0 until 64 step 8) {
                val idx = (i * N / 64).coerceIn(0, N - 1)
                hash = 31 * hash + (Lsrc[idx].toLong() xor Rsrc[idx].toLong())
            }
        }

        val fftL: Array<Complex>
        val fftR: Array<Complex>

        if (USE_FFT_CACHE && hash == lastFrameHash && lastFftL != null && lastFftR != null) {
            fftL = lastFftL!!
            fftR = lastFftR!!
        } else {
            fftL = fft(paddedL)
            fftR = fft(paddedR)
            if (USE_FFT_CACHE) {
                lastFftL = fftL
                lastFftR = fftR
                lastFrameHash = hash
            }
        }

        // 밴드별 분석
        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / fftL.size.toDouble())).roundToInt().coerceIn(0, fftL.size - 1)
            val phL = atan2(fftL[bin].imag, fftL[bin].real)
            val phR = atan2(fftR[bin].imag, fftR[bin].real)
            var dPhi = phR - phL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI
            phaseDiff[i] = dPhi

            val magL = hypot(fftL[bin].real, fftL[bin].imag)
            val magR = hypot(fftR[bin].real, fftR[bin].imag)
            energyByBand[i] = (magL + magR) * 0.5
            snrByBand[i] = energyByBand[i] / noiseFloor[i].coerceAtLeast(1e-9)
            noiseFloor[i] = (1 - NOISE_EMA_ALPHA) * noiseFloor[i] + NOISE_EMA_ALPHA * energyByBand[i]
        }

        // 속삭임 통과 조건
        val passWhisper =
            (snrByBand[WHISPER_IDX1] > NEWKEY_SNR_FACTOR) ||
            (snrByBand[WHISPER_IDX2] > NEWKEY_SNR_FACTOR)
        if (!passWhisper) return null

        // Δindex 추정
        val (dn, distMm) = resolveDeltaIndexByVoting(phaseDiff, bands, energyByBand)
        if (dn == 0 || distMm <= 0.0) return null

        val maxIdx = energyByBand.indices.maxByOrNull { energyByBand[it] } ?: return null
        val f = bands[maxIdx]
        val e = energyByBand[maxIdx].coerceAtLeast(ENERGY_MIN_THRESHOLD)
        return VoiceKey(nextKeyId++, f, dn, e, distMm)
    }

    // ------------------------------------------------------------
    // ✅ Δindex 투표 기반 거리 확정
    // ------------------------------------------------------------
    private fun resolveDeltaIndexByVoting(
        phaseDiff: DoubleArray,
        freqs: DoubleArray,
        energy: DoubleArray
    ): Pair<Int, Double> {
        val maxDelaySamples = (MIC_DISTANCE_MAX_MM / 1000.0 / SPEED_OF_SOUND * sampleRate).roundToInt()
        val order = (freqs.indices).sortedByDescending { energy[it] }
        val voteBands = order.drop(EXCLUDE_LOW_FREQ).take(TOP_BANDS_FOR_VOTE)

        val votes = mutableMapOf<Int, Double>()
        val contrib = mutableMapOf<Int, MutableList<Int>>()

        for (j in voteBands) {
            val fj = freqs[j]
            val ph = phaseDiff[j]
            for (m in -2..2) {
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
    // 기타 (merge / cluster / fft)
    // ------------------------------------------------------------
    private fun mergeKeys(old: List<VoiceKey>, new: List<VoiceKey>): List<VoiceKey> { ... }
    private fun clusterByEnergyPattern(keys: List<VoiceKey>): List<SpeakerSignal> { ... }
    private fun fft(x: DoubleArray): Array<Complex> { ... }

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

object WkVoiceSeparatorShard {
    val instance = WkVoiceSeparator(
        sampleRate = 44100,
        bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    )
}
