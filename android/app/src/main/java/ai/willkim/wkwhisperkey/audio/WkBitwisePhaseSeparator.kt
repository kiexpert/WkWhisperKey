package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*

/**
 * WkBitwisePhaseSeparator v3.0
 * ---------------------------------------------------------------
 * - 정수형 8밴드 DFT (FFT 미사용)
 * - 사인테이블 기반 branchless 누적합
 * - 밴드별 위상·에너지 정수 연산
 * - 위상 정합 기반 감쇠/소거(diff-phase cancel)
 * - 단일 API: separate(L: ShortArray, R: ShortArray)
 */
data class WkPhaseKey(
    val id: Int,
    val freq: Int,
    val deltaIndex: Int,
    val energy: Double,
    val distanceMm: Double,
    val phaseL: Int,
    val phaseR: Int,
    val magL: Int,
    val magR: Int,
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
    private val bands: IntArray
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
        private const val MOUTH_RADIUS_BITS = 32  // ≈ ±31 샘플 허용
        private const val TABLE_SIZE = 4096

        // ---- 정수형 SIN 테이블 ----
        private val SIN_TABLE = ShortArray(TABLE_SIZE).apply {
            for (i in indices) {
                val rad = (i.toDouble() / TABLE_SIZE) * 2 * Math.PI
                this[i] = (sin(rad) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private inline fun sinI(phase: Int): Int =
            SIN_TABLE[phase and (TABLE_SIZE - 1)].toInt()

        private inline fun cosI(phase: Int): Int =
            SIN_TABLE[(phase + TABLE_SIZE / 4) and (TABLE_SIZE - 1)].toInt()

        private inline fun phaseStep(freq: Int, sampleRate: Int): Int =
            ((freq * TABLE_SIZE.toLong()) / sampleRate).toInt()

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
    }

    private var nextKeyId = 1000
    private var nextSpeakerId = 1
    private var activeKeys = mutableListOf<WkPhaseKey>()
    private val keyLastSeen = mutableMapOf<Int, Int>()

    private val noiseFloor = DoubleArray(bands.size) { 1e-9 }
    private val phaseDiff = IntArray(bands.size)
    private val magL = IntArray(bands.size)
    private val magR = IntArray(bands.size)

    fun getActiveKeys(): List<WkPhaseKey> = activeKeys.toList()

    // ------------------------------------------------------------
    fun separate(L: ShortArray, R: ShortArray): List<WkPhaseSignal> {
        val preKeys = activeKeys.toList()
        val (_, resL, resR) = preprocess(L, R, preKeys)
        detectVoiceKey(resL, resR)?.let {
            activeKeys = mergeKeys(preKeys, listOf(it)).toMutableList()
        }
        return clusterByEnergyPattern(activeKeys)
    }

    // ------------------------------------------------------------
    private fun preprocess(
        Lsrc: ShortArray, Rsrc: ShortArray, keys: List<WkPhaseKey>
    ): Triple<List<WkPhaseSignal>, IntArray, IntArray> {

        val Npad = Lsrc.size
        val N = Npad - PAD_SAMPLES * 2
        val L = IntArray(Npad) { Lsrc[it].toInt() }
        val R = IntArray(Npad) { Rsrc[it].toInt() }

        val (magL8, phaseL8) = dft8(L)
        val (magR8, phaseR8) = dft8(R)

        // 위상차, 진폭저장
        for (i in bands.indices) {
            phaseDiff[i] = (phaseR8[i] - phaseL8[i])
            magL[i] = magL8[i]
            magR[i] = magR8[i]
        }

        // 화자 밴드별 감쇠
        for (key in keys) {
            val speakerΔ = key.deltaIndex
            for (b in bands.indices) {
                val f = bands[b]
                val λ = (sampleRate / f).coerceAtLeast(1)
                val bandΔ = phaseDiff[b]
                if (isPhaseMatchedInt(bandΔ, speakerΔ, λ, MOUTH_RADIUS_BITS)) {
                    val step = phaseStep(f, sampleRate)
                    var phL = phaseL8[b]
                    var phR = phaseR8[b]
                    val ampL = magL[b]
                    val ampR = magR[b]
                    for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
                        val cancelL = (ampL * sinI(phL) shr 15)
                        val cancelR = (ampR * sinI(phR) shr 15)
                        L[i] -= cancelL
                        R[i] -= cancelR
                        phL = (phL + step) and (TABLE_SIZE - 1)
                        phR = (phR + step) and (TABLE_SIZE - 1)
                    }
                }
            }
        }

        val eDb = rmsDb(L, N)
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
        val (magL8, phaseL8) = dft8(L)
        val (magR8, phaseR8) = dft8(R)
        for (i in bands.indices) phaseDiff[i] = phaseR8[i] - phaseL8[i]

        val (dn, distMm) = resolveDeltaIndexByVoting()
        if (dn == 0 || distMm <= 0.0) return null

        val maxIdx = magL.indices.maxByOrNull { magL[it] + magR[it] } ?: return null
        return WkPhaseKey(
            nextKeyId++, bands[maxIdx], dn,
            (magL[maxIdx] + magR[maxIdx]).toDouble(),
            distMm,
            phaseL8[maxIdx], phaseR8[maxIdx],
            magL[maxIdx], magR[maxIdx]
        )
    }

    // ------------------------------------------------------------
    private fun resolveDeltaIndexByVoting(): Pair<Int, Double> {
        val maxDelaySamples =
            (MIC_DISTANCE_MAX_MM / 1000.0 / SPEED_OF_SOUND * sampleRate).roundToInt()
        var bestDn = 0; var bestDist = 0.0
        for (b in bands.indices) {
            val λ = (sampleRate / bands[b])
            val dn = (phaseDiff[b] * λ / TABLE_SIZE)
            if (abs(dn) < maxDelaySamples) {
                bestDn = dn
                bestDist = abs(dn) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0
                break
            }
        }
        return bestDn to bestDist
    }

    // ------------------------------------------------------------
    private fun dft8(samples: IntArray): Pair<IntArray, IntArray> {
        val N = samples.size
        val mag = IntArray(bands.size)
        val phase = IntArray(bands.size)
        for (b in bands.indices) {
            val f = bands[b]
            val step = phaseStep(f, sampleRate)
            var ph = 0
            var sumRe = 0
            var sumIm = 0
            for (n in 0 until N step 2) { // 절반 샘플만으로 근사
                val s = samples[n]
                sumRe += s * cosI(ph)
                sumIm -= s * sinI(ph)
                ph = (ph + step) and (TABLE_SIZE - 1)
            }
            mag[b] = sqrt((sumRe * sumRe + sumIm * sumIm).toDouble()).toInt() shr 14
            phase[b] = ph
        }
        return mag to phase
    }

    private fun rmsDb(x: IntArray, N: Int): Double {
        var sumSq = 0L
        for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
            val v = x[i]; sumSq += v * v
        }
        val rms = sqrt(sumSq.toDouble() / N)
        return 20 * log10(rms / DEFAULT_ENERGY_NORM + ENERGY_MIN_THRESHOLD) + BASE_ENERGY_OFFSET_DB
    }

    private fun mergeKeys(old: List<WkPhaseKey>, new: List<WkPhaseKey>): List<WkPhaseKey> {
        val merged = mutableListOf<WkPhaseKey>()
        for (n in new) {
            val near = old.find { abs(it.deltaIndex - n.deltaIndex) < MAX_CLUSTER_GAP }
            if (near == null) merged += n else
                merged += n.copy(energy = (near.energy + n.energy) * 0.5)
        }
        return merged.sortedByDescending { it.energy }
    }

    private fun clusterByEnergyPattern(keys: List<WkPhaseKey>): List<WkPhaseSignal> =
        keys.groupBy { it.deltaIndex / MAX_CLUSTER_GAP }
            .map { (grpId, grp) ->
                val avgE = grp.map { it.energy }.average()
                val avgΔ = grp.map { it.deltaIndex }.average().roundToInt()
                val avgD = grp.map { it.distanceMm }.average()
                WkPhaseSignal(grpId, grp, avgE, avgΔ, avgD)
            }
}

// ------------------------------------------------------------
object WkBitwisePhaseSeparatorShard {
    val instance = WkBitwisePhaseSeparator(
        sampleRate = 44100,
        bands = intArrayOf(150, 700, 1100, 1700, 2500, 3600, 5200, 7500)
    )
}
