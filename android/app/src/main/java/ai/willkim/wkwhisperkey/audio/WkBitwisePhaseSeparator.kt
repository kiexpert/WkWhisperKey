package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*

/**
 * WkBitwisePhaseSeparator v2.1
 * ---------------------------------------------------------------
 * - 위상 재합성형 분리기 (Phase-Resynthesis Model)
 * - 밴드별 좌/우 위상 및 에너지 기록
 * - sin 테이블 기반 위상파 재합성 후 역위상 소거(diff-phase cancel)
 * - 위상 정합 기준(입 반지름 ±31샘플) 외 밴드는 무시
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
        private const val MOUTH_RADIUS_BITS = 32  // ≈ ±31 샘플 허용

        // ---- 16bit SIN TABLE ----
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

        // ---- 위상 정합 여부 검사 (branchless) ----
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
    fun separate(L: ShortArray, R: ShortArray): List<WkPhaseSignal> {
        val preKeys = activeKeys.toList()
        val (preSpk, resL, resR) = preprocess(L, R, preKeys)
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

        val N = Lsrc.size
        val Npad = N + PAD_SAMPLES * 2
        val L = IntArray(Npad)
        val R = IntArray(Npad)
        for (i in 0 until N) {
            L[i + PAD_SAMPLES] = Lsrc[i].toInt()
            R[i + PAD_SAMPLES] = Rsrc[i].toInt()
        }

        val fftL = fft(L)
        val fftR = fft(R)

        fun phaseMatchScore(key: WkPhaseKey, fftL: Array<Complex>, fftR: Array<Complex>): Double {
            var score = 0.0
            for (b in bands.indices) {
                val bin = (bands[b] / (sampleRate / L.size.toDouble())).toInt()
                val φL = atan2(fftL[bin].imag, fftL[bin].real)
                val φR = atan2(fftR[bin].imag, fftR[bin].real)
                val predicted = key.phaseR - key.phaseL
                val diff = φR - φL - predicted
                score += cos(diff)
            }
            return score / bands.size
        }

        // ---- 화자 밴드별 위상 재합성 감쇄 ----
        for (key in keys.sortedByDescending { phaseMatchScore(it, fftL, fftR) }) {
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
                        val waveL = sinFromTable(phaseL[b] + ω * t)
                        val waveR = sinFromTable(phaseR[b] + ω * t)
                        val cancelL = (ampL * waveL * 0.5 * (1.0 / (1.0 + gainRatio)))
                        val cancelR = (ampR * waveR * 0.5 * (1.0 / (1.0 + 1.0 / gainRatio)))
                        L[i] = (L[i] - cancelL).toInt()
                        R[i] = (R[i] - cancelR).toInt()
                    }
                }
            }
        }

        // ---- RMS ----
        var sumSq = 0L
        for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
            val v = L[i]; sumSq += v * v
        }
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
        val N = L.size
        val Npad = N + PAD_SAMPLES * 2
        for (i in 0 until N) {
            dL[i + PAD_SAMPLES] = L[i].toDouble()
            dR[i + PAD_SAMPLES] = R[i].toDouble()
        }

        val fftL = fft(L)
        val fftR = fft(R)
        val micDistMm = estimateMicDistanceMm()

        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / Npad.toDouble())).roundToInt().coerceIn(0, Npad - 1)
            val phL = atan2(fftL[bin].imag, fftL[bin].real)
            val phR = atan2(fftR[bin].imag, fftR[bin].real)
            phaseL[i] = phL
            phaseR[i] = phR
            magL[i] = hypot(fftL[bin].real, fftL[bin].imag)
            magR[i] = hypot(fftR[bin].real, fftR[bin].imag)
            var dPhi = phR - phL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI
            phaseDiff[i] = dPhi
            val cancel = sinFromTable(dPhi)
            val common = (magL[i] + magR[i]) * 0.5 * cancel
            energyByBand[i] = abs(magL[i] - common)
            snrByBand[i] = energyByBand[i] / noiseFloor[i].coerceAtLeast(1e-9)
            noiseFloor[i] =
                (1 - NOISE_EMA_ALPHA) * noiseFloor[i] + NOISE_EMA_ALPHA * energyByBand[i]
        }

        val passWhisper =
            (snrByBand[WHISPER_IDX1] > NEWKEY_SNR_FACTOR) ||
            (snrByBand[WHISPER_IDX2] > NEWKEY_SNR_FACTOR)
        if (!passWhisper) return null

        val (dn, distMm) = resolveDeltaIndexByVoting()
        if (dn == 0 || distMm <= 0.0) return null

        // 비화자 밴드 제거
        for (i in bands.indices) {
            val f = bands[i]
            val λ = (sampleRate / f).roundToInt()
            val bandΔ = (phaseDiff[i] * sampleRate / (2 * Math.PI * f)).roundToInt()
            if (!isPhaseMatchedInt(bandΔ, dn, λ, MOUTH_RADIUS_BITS)) {
                magL[i] = 0.0; magR[i] = 0.0
            }
        }

        val maxIdx = energyByBand.indices.maxByOrNull { energyByBand[it] } ?: return null
        return WkPhaseKey(
            nextKeyId++, bands[maxIdx], dn,
            energyByBand[maxIdx], distMm,
            phaseL[maxIdx], phaseR[maxIdx],
            magL[maxIdx], magR[maxIdx]
        )
    }

    // ------------------------------------------------------------
    private fun resolveDeltaIndexByVoting(): Pair<Int, Double> {
        val maxDelaySamples =
            (MIC_DISTANCE_MAX_MM / 1000.0 / SPEED_OF_SOUND * sampleRate).roundToInt()
        val order = (bands.indices).sortedByDescending { energyByBand[it] }
        val voteBands = order.drop(EXCLUDE_LOW_FREQ).take(TOP_BANDS_FOR_VOTE)
        val votes = mutableMapOf<Int, Double>()
        val contrib = mutableMapOf<Int, MutableList<Int>>()
        for (j in voteBands) {
            val fj = bands[j]
            val ph = phaseDiff[j]
            for (m in -2..2) {
                val dn = ((ph + 2 * Math.PI * m) / (2 * Math.PI * fj) * sampleRate).roundToInt()
                if (abs(dn) <= maxDelaySamples) {
                    val w = max(energyByBand[j], 1e-12)
                    votes[dn] = (votes[dn] ?: 0.0) + w
                    contrib.getOrPut(dn) { mutableListOf() }.add(j)
                }
            }
        }
        var bestDn = 0; var bestDist = 0.0; var bestVotes = 0
        for ((dn, _) in votes.entries.sortedByDescending { it.value }) {
            val count = contrib[dn]?.distinct()?.size ?: 0
            when {
                count >= 3 -> {
                    bestDn = dn; bestDist = abs(dn) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0; break
                }
                count >= 2 && bestVotes < 2 -> {
                    bestDn = dn; bestDist = abs(dn) / sampleRate.toDouble() * SPEED_OF_SOUND * 1000.0; bestVotes = count
                }
            }
        }
        return bestDn to bestDist
    }

    // ------------------------------------------------------------
    private fun mergeKeys(old: List<WkPhaseKey>, new: List<WkPhaseKey>): List<WkPhaseKey> {
        val merged = mutableListOf<WkPhaseKey>()
        for (n in new) {
            val near = old.find { abs(it.deltaIndex - n.deltaIndex) < MAX_CLUSTER_GAP }
            if (near == null) { merged += n; keyLastSeen[n.id] = 0 }
            else { merged += n.copy(energy = (near.energy + n.energy) * 0.5); keyLastSeen[near.id] = 0 }
        }
        for (o in old) {
            val age = (keyLastSeen[o.id] ?: 0) + 1
            val isWeak = o.energy < KEEPKEY_SNR_FACTOR * ENERGY_MIN_THRESHOLD
            if (age < DROP_THRESHOLD_FRAMES && !isWeak) {
                merged += o.copy(energy = o.energy * ENERGY_DECAY_RATE); keyLastSeen[o.id] = age
            } else keyLastSeen.remove(o.id)
        }
        return merged.sortedByDescending { it.energy }
    }

    // ------------------------------------------------------------
    private fun clusterByEnergyPattern(keys: List<WkPhaseKey>): List<WkPhaseSignal> {
        val groups = mutableListOf<MutableList<WkPhaseKey>>()
        for (k in keys) {
            var assigned = false
            for (grp in groups) {
                val sim = energyPatternSimilarity(k, grp.first())
                if (sim > ENERGY_SIM_THRESHOLD) { grp += k; assigned = true; break }
            }
            if (!assigned) groups += mutableListOf(k)
        }
        return groups.map { grp ->
            val id = nextSpeakerId++
            val avgE = grp.map { it.energy }.average()
            val avgΔ = grp.map { it.deltaIndex }.average().roundToInt()
            val avgD = grp.map { it.distanceMm }.average()
            WkPhaseSignal(id, grp, avgE, avgΔ, avgD)
        }
    }

    private fun energyPatternSimilarity(a: WkPhaseKey, b: WkPhaseKey): Double {
        val aVec = doubleArrayOf(a.energy, a.freq)
        val bVec = doubleArrayOf(b.energy, b.freq)
        val dot = aVec.zip(bVec).sumOf { it.first * it.second }
        val normA = sqrt(aVec.sumOf { it * it }); val normB = sqrt(bVec.sumOf { it * it })
        return dot / (normA * normB + 1e-9)
    }

    // ------------------------------------------------------------
    private fun fft(x: IntArray): Array<Complex> {
        val N = x.size
        if (N <= 1) return arrayOf(Complex(x[0].toDouble(), 0.0))
    
        val half = N / 2
        val even = IntArray(half) { x[it * 2] }
        val odd = IntArray(half) { x[it * 2 + 1] }
    
        val fftEven = fft(even)
        val fftOdd = fft(odd)
    
        val result = Array(N) { Complex(0.0, 0.0) }
        for (k in 0 until half) {
            val t = Complex.polar(1.0, -2 * Math.PI * k / N) * fftOdd[k]
            result[k] = fftEven[k] + t
            result[k + half] = fftEven[k] - t
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

// ------------------------------------------------------------
// 싱글톤 샤드 인스턴스
// ------------------------------------------------------------
object WkBitwisePhaseSeparatorShard {
    val instance = WkBitwisePhaseSeparator(
        sampleRate = 44100,
        bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    )
}
