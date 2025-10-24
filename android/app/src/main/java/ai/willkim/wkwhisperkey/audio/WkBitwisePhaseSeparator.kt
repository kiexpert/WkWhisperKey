package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*

/**
 * WkBitwisePhaseSeparator v2.2 (IntArray FFT optimized)
 * ---------------------------------------------------------------
 * - Complex 객체 제거, in-place FFT(IntArray 기반)
 * - 밴드별 좌/우 위상 및 에너지 계산
 * - sin 테이블 기반 위상 재합성 및 역위상 소거
 * - 위상 정합 기준(입 반지름 ±31샘플) 외 밴드 무시
 * - 단일 API: separate(L: ShortArray, R: ShortArray)
 */
data class WkPhaseKey(
    val id: Int,
    val freq: Double,
    val deltaIndex: Int,
    val energy: Double,
    val distanceMm: Double,
    val phaseL: Double,
    val phaseR: Double,
    val magL: Double,
    val magR: Double,
    var energyPosX: Double = 0.0,
    var energyPosY: Double = 0.0
)

data class WkPhaseSignal(
    val id: Int,
    val keys: List<WkPhaseKey>,
    val energy: Double,
    val deltaIndex: Int,
    val distance: Double
)

class WkBitwisePhaseSeparator(
    private val sampleRate: Int,
    private val bands: DoubleArray
) {
    companion object {
        private const val SPEED_OF_SOUND = 343.0
        private const val MIC_DISTANCE_MAX_MM = 200.0
        private const val PAD_SAMPLES = 600
        private const val DEFAULT_ENERGY_NORM = 32768.0
        private const val ENERGY_MIN_THRESHOLD = 1e-9
        private const val BASE_ENERGY_OFFSET_DB = 120.0
        private const val ENERGY_SIM_THRESHOLD = 0.85
        private const val MAX_CLUSTER_GAP = 3
        private const val DROP_THRESHOLD_FRAMES = 8
        private const val ENERGY_DECAY_RATE = 0.95
        private const val NOISE_EMA_ALPHA = 0.10
        private const val NEWKEY_SNR_FACTOR = 2.0
        private const val KEEPKEY_SNR_FACTOR = 1.5
        private const val WHISPER_IDX1 = 1
        private const val WHISPER_IDX2 = 4
        private const val TOP_BANDS_FOR_VOTE = 6
        private const val EXCLUDE_LOW_FREQ = 1
        private const val MOUTH_RADIUS_BITS = 32 // ±31 샘플 허용

        // ---- SIN TABLE ----
        private const val TABLE_SIZE = 4096
        private val SIN_TABLE = ShortArray(TABLE_SIZE).apply {
            for (i in indices) {
                val rad = (i.toDouble() / TABLE_SIZE) * 2 * Math.PI
                this[i] = (sin(rad) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        fun sinFromTable(phase: Double): Double {
            val idx = ((phase / (2 * Math.PI)) * TABLE_SIZE).toInt() and (TABLE_SIZE - 1)
            return SIN_TABLE[idx] / Short.MAX_VALUE.toDouble()
        }

        fun cosFromTable(phase: Double): Double = sinFromTable(phase + Math.PI / 2)

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
            return when (mode) {
                "PORTRAIT" -> 20.0
                "LANDSCAPE" -> 150.0
                "UNFOLDED" -> 180.0
                else -> 20.0
            }.coerceAtMost(MIC_DISTANCE_MAX_MM)
        }

        // ---- 위상 정합 검사 ----
        private inline fun isPhaseMatchedInt(
            bandΔ: Int, speakerΔ: Int, λ: Int, mouthRadiusBits: Int
        ): Boolean {
            val mask = mouthRadiusBits - 1
            val n0 = abs(speakerΔ) / λ
            var diffMask = 0
            diffMask = diffMask or abs(bandΔ + n0 * λ - speakerΔ)
            diffMask = diffMask or abs(bandΔ - n0 * λ - speakerΔ)
            diffMask = diffMask or abs(bandΔ + (n0 + 1) * λ - speakerΔ)
            diffMask = diffMask or abs(bandΔ - (n0 + 1) * λ - speakerΔ)
            return (diffMask and mask.inv()) == 0
        }
    }

    private var nextKeyId = 1000
    private var nextSpeakerId = 1
    private var activeKeys = mutableListOf<WkPhaseKey>()
    private val keyLastSeen = mutableMapOf<Int, Int>()

    private val noiseFloor = DoubleArray(bands.size) { 1e-9 }
    private val phaseDiff = DoubleArray(bands.size)
    private val energyByBand = DoubleArray(bands.size)
    private val snrByBand = DoubleArray(bands.size)
    private val phaseL = DoubleArray(bands.size)
    private val phaseR = DoubleArray(bands.size)
    private val magL = DoubleArray(bands.size)
    private val magR = DoubleArray(bands.size)

    fun getActiveKeys(): List<WkPhaseKey> = activeKeys.toList()

    // ------------------------------------------------------------
    fun separate(Lsrc: ShortArray, Rsrc: ShortArray): List<WkPhaseSignal> {
        val preKeys = activeKeys.toList()
        val (preSpk, resL, resR) = preprocess(Lsrc, Rsrc, preKeys)
        detectVoiceKey(resL, resR)?.let {
            activeKeys = mergeKeys(preKeys, listOf(it)).toMutableList()
        }
        return clusterByEnergyPattern(activeKeys)
    }

    // ------------------------------------------------------------
    private fun preprocess(
        Lsrc: ShortArray,
        Rsrc: ShortArray,
        keys: List<WkPhaseKey>
    ): Triple<List<WkPhaseSignal>, IntArray, IntArray> {

        val Npad = Lsrc.size
        val N = Npad - PAD_SAMPLES * 2
        val L = IntArray(Npad) { Lsrc[it].toInt() }
        val R = IntArray(Npad) { Rsrc[it].toInt() }

        val (reL, imL) = fftInt(L.copyOf())
        val (reR, imR) = fftInt(R.copyOf())

        fun phaseMatchScore(key: WkPhaseKey): Double {
            var score = 0.0
            for (b in bands.indices) {
                val bin = (bands[b] / (sampleRate / L.size.toDouble())).toInt()
                val φL = atan2(imL[bin].toDouble(), reL[bin].toDouble())
                val φR = atan2(imR[bin].toDouble(), reR[bin].toDouble())
                val predicted = key.phaseR - key.phaseL
                score += cos(φR - φL - predicted)
            }
            return score / bands.size
        }

        // 위상 정합 기반 감쇄
        for (key in keys.sortedByDescending { phaseMatchScore(it) }) {
            val speakerΔ = key.deltaIndex
            for (b in bands.indices) {
                val f = bands[b]
                val λ = (sampleRate / f).roundToInt()
                val bandΔ = (phaseDiff[b] * sampleRate / (2 * Math.PI * f)).roundToInt()
                if (isPhaseMatchedInt(bandΔ, speakerΔ, λ, MOUTH_RADIUS_BITS)) {
                    val ω = 2.0 * Math.PI * f / sampleRate
                    val ampL = magL[b]
                    val ampR = magR[b]
                    val gainRatio = (ampL + 1e-9) / (ampR + 1e-9)
                    for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
                        val t = i.toDouble()
                        val j = (i + speakerΔ).coerceIn(0, L.lastIndex)
                        val waveL = sinFromTable(phaseL[b] + ω * t)
                        val waveR = sinFromTable(phaseR[b] + ω * t)
                        val cancelL = (ampL * waveL * 0.5 / (1.0 + gainRatio))
                        val cancelR = (ampR * waveR * 0.5 / (1.0 + 1.0 / gainRatio))
                        L[i] = (L[i] - cancelL).toInt()
                        R[j] = (R[j] - cancelR).toInt()
                    }
                }
            }
        }

        // RMS 계산
        var sumSq = 0L
        for (i in PAD_SAMPLES until PAD_SAMPLES + N) sumSq += L[i] * L[i]
        val rms = sqrt(sumSq.toDouble() / N)
        val eDb = 20 * log10(rms / DEFAULT_ENERGY_NORM + ENERGY_MIN_THRESHOLD) + BASE_ENERGY_OFFSET_DB

        val result = if (keys.isNotEmpty()) {
            val k = keys.maxByOrNull { it.energy }!!
            listOf(WkPhaseSignal(k.id, keys, eDb, k.deltaIndex, k.distanceMm))
        } else emptyList()

        return Triple(result,
            L.copyOfRange(PAD_SAMPLES, PAD_SAMPLES + N),
            R.copyOfRange(PAD_SAMPLES, PAD_SAMPLES + N))
    }

    // ------------------------------------------------------------
    private fun detectVoiceKey(L: IntArray, R: IntArray): WkPhaseKey? {
        val (reL, imL) = fftInt(L.copyOf())
        val (reR, imR) = fftInt(R.copyOf())
        val micDistMm = estimateMicDistanceMm()
        val N = L.size

        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / N.toDouble())).roundToInt().coerceIn(0, N - 1)
            val phL = atan2(imL[bin].toDouble(), reL[bin].toDouble())
            val phR = atan2(imR[bin].toDouble(), reR[bin].toDouble())
            phaseL[i] = phL
            phaseR[i] = phR
            magL[i] = hypot(reL[bin].toDouble(), imL[bin].toDouble())
            magR[i] = hypot(reR[bin].toDouble(), imR[bin].toDouble())
            var dPhi = phR - phL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI
            phaseDiff[i] = dPhi
            val cancel = sinFromTable(dPhi)
            val common = (magL[i] + magR[i]) * 0.5 * cancel
            energyByBand[i] = abs(magL[i] - common)
            snrByBand[i] = energyByBand[i] / noiseFloor[i].coerceAtLeast(1e-9)
            noiseFloor[i] = (1 - NOISE_EMA_ALPHA) * noiseFloor[i] + NOISE_EMA_ALPHA * energyByBand[i]
        }

        val (dn, distMm) = resolveDeltaIndexByVoting()
        if (dn == 0 || distMm <= 0.0) return null

        val maxIdx = energyByBand.indices.maxByOrNull { energyByBand[it] } ?: return null
        return WkPhaseKey(
            nextKeyId++, bands[maxIdx], dn,
            energyByBand[maxIdx], distMm,
            phaseL[maxIdx], phaseR[maxIdx],
            magL[maxIdx], magR[maxIdx]
        )
    }

    // ------------------------------------------------------------
    private fun fftInt(
        re: IntArray,
        im: IntArray = IntArray(re.size),
        shift: Int = 14
    ): Pair<IntArray, IntArray> {
        val n = re.size
        var j = 0
        for (i in 1 until n - 1) {
            var bit = n shr 1
            while (j >= bit) { j -= bit; bit = bit shr 1 }
            j += bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val step = (2 * Math.PI / len)
            val wlenRe = (cos(step) * (1 shl shift)).toInt()
            val wlenIm = (sin(step) * (1 shl shift)).toInt()
            for (i in 0 until n step len) {
                var wRe = (1 shl shift)
                var wIm = 0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = ((re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm) shr shift)
                    val vIm = ((re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe) shr shift)
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val tmpRe = ((wRe * wlenRe - wIm * wlenIm) shr shift)
                    val tmpIm = ((wRe * wlenIm + wIm * wlenRe) shr shift)
                    wRe = tmpRe; wIm = tmpIm
                }
            }
            len = len shl 1
        }
        return re to im
    }
}

// ------------------------------------------------------------
object WkBitwisePhaseSeparatorShard {
    val instance = WkBitwisePhaseSeparator(
        sampleRate = 44100,
        bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    )
}
