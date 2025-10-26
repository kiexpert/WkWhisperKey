package ai.willkim.wkwhisperkey.audio

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import ai.willkim.wkwhisperkey.WkApp
import kotlin.math.*
import ai.willkim.wkwhisperkey.audio.WkIntEnergyAnalyzer

/**
 * WkVoiceSeparator v6.0
 * -------------------------------------------------------
 * - Fold5 모드 자동감지 (세로/가로/펼침)
 * - 마이크 거리 자동추정 및 위상 기반 거리 해석
 * - ±600 샘플 패딩 (20cm 거리 대응)
 * - 연립방정식(2~3밴드 일치) 기반 물리거리 확정
 * - 1개 확정된 발성키만 생성 (or 없음)
 * - 밴드별 노이즈 추적(EMA) 및 기존키 감쇠/드랍
 */
data class VoiceKey(
    val id: Int,
    val freq: Double,
    val deltaIndex: Int,
    val energy: Double,
    val distanceMm: Double,
    var energyPosX: Double = 0.0,
    var energyPosY: Double = 0.0
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
        private const val PAD_SAMPLES = 600      // ±패딩샘플

        private const val RESIDUAL_ATTENUATION = 0.625
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
        val N = Lsrc.size
        val Npad = N + PAD_SAMPLES * 2
        val residualL = DoubleArray(Npad)
        val residualR = DoubleArray(Npad)
        System.arraycopy(Lsrc, 0, residualL, PAD_SAMPLES, N)
        System.arraycopy(Rsrc, 0, residualR, PAD_SAMPLES, N)

        val result = mutableListOf<SpeakerSignal>()

        for (key in keys.sortedByDescending { it.energy }) {
            val d = key.deltaIndex
            val spk = DoubleArray(Npad)
            for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
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

        // 중앙 영역만 반환
        val coreL = residualL.copyOfRange(PAD_SAMPLES, PAD_SAMPLES + N)
        val coreR = residualR.copyOfRange(PAD_SAMPLES, PAD_SAMPLES + N)
        return Triple(result, coreL, coreR)
    }

    // ------------------------------------------------------------
    private fun detectVoiceKey(Lres: DoubleArray, Rres: DoubleArray): VoiceKey? {
        val N = Lres.size
        val Npad = N + PAD_SAMPLES * 2
        val paddedL = DoubleArray(Npad)
        val paddedR = DoubleArray(Npad)
        System.arraycopy(Lres, 0, paddedL, PAD_SAMPLES, N)
        System.arraycopy(Rres, 0, paddedR, PAD_SAMPLES, N)

        val fftL = fft(paddedL)
        val fftR = fft(paddedR)
        val micDistMm = estimateMicDistanceMm()

        // 밴드별 위상 분석
        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / Npad.toDouble())).roundToInt().coerceIn(0, Npad - 1)
            val magL = hypot(fftL[bin].real, fftL[bin].imag)
            val magR = hypot(fftR[bin].real, fftR[bin].imag)
            energyByBand[i] = (magL + magR) * 0.5
            val phL = atan2(fftL[bin].imag, fftL[bin].real)
            val phR = atan2(fftR[bin].imag, fftR[bin].real)
            var dPhi = phR - phL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI
            phaseDiff[i] = dPhi
            snrByBand[i] = energyByBand[i] / noiseFloor[i].coerceAtLeast(1e-9)
            noiseFloor[i] =
                (1 - NOISE_EMA_ALPHA) * noiseFloor[i] + NOISE_EMA_ALPHA * energyByBand[i]
        }

        val passWhisper =
            (snrByBand[WHISPER_IDX1] > NEWKEY_SNR_FACTOR) ||
            (snrByBand[WHISPER_IDX2] > NEWKEY_SNR_FACTOR)
        if (!passWhisper) return null

        val (dn, distMm) = resolveDeltaIndexByVoting(phaseDiff, bands, energyByBand)
        if (dn == 0 || distMm <= 0.0) return null

        val maxIdx = energyByBand.indices.maxByOrNull { energyByBand[it] } ?: return null
        val f = bands[maxIdx]
        val e = energyByBand[maxIdx].coerceAtLeast(ENERGY_MIN_THRESHOLD)
        return VoiceKey(nextKeyId++, f, dn, e, distMm)
    }

    // ------------------------------------------------------------
    // 🔹 정수 기반 분리 메인 루프 (branchless + float-free)
    // ------------------------------------------------------------
    fun separateInt(L: ShortArray, R: ShortArray): List<SpeakerSignal> {
        val preKeys = activeKeys.toList()
    
        // 🔸 정수 전처리 경로로 호출
        val (preSpk, resL, resR) = preprocessByVoiceKeysInt(preKeys, L, R)
    
        // 🔸 FFT/위상 계산은 여전히 더블 기반 (정밀도 필요)
        detectVoiceKeyInt(resL, resR)?.let {
            activeKeys = mergeKeys(preKeys, listOf(it)).toMutableList()
        }
    
        return clusterByEnergyPattern(activeKeys)
    }

    // ------------------------------------------------------------
    // 🔹 정수(Short) 기반 전처리 (branchless / float-free)
    // ------------------------------------------------------------
    private fun preprocessByVoiceKeysInt(
        keys: List<VoiceKey>, Lsrc: ShortArray, Rsrc: ShortArray
    ): Triple<List<SpeakerSignal>, IntArray, IntArray> {
    
        val N = Lsrc.size
        val Npad = N + PAD_SAMPLES * 2
        val residualL = IntArray(Npad)
        val residualR = IntArray(Npad)
    
        // ±패딩 적용
        for (i in 0 until N) {
            residualL[i + PAD_SAMPLES] = Lsrc[i].toInt()
            residualR[i + PAD_SAMPLES] = Rsrc[i].toInt()
        }
    
        val result = mutableListOf<SpeakerSignal>()
    
        // 🔸 고에너지 키 우선 역보정
        for (key in keys.sortedByDescending { it.energy }) {
            val d = key.deltaIndex
    
            // 완전 정수 루프 (branchless)
            for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
                val j = i - d
                val l = residualL[i]
                val r = residualR[j]
                val s = (l + r) / 2
                val att = (s * 5) shr 3  // ≈ s * 0.625  (정수 근사)
                residualL[i] = l - att
                residualR[j] = r - att
            }
        }
    
        // 🔸 RMS 계산 (L 기준)
        var sumSq = 0L
        for (i in PAD_SAMPLES until PAD_SAMPLES + N) {
            val v = residualL[i]
            sumSq += v * v
        }
    
        val rms = sqrt(sumSq.toDouble() / N)
        val eDb = 20 * log10(rms / DEFAULT_ENERGY_NORM + ENERGY_MIN_THRESHOLD) + BASE_ENERGY_OFFSET_DB
    
        // 🔸 단일 대표 키만 반환
        if (keys.isNotEmpty()) {
            val k = keys.maxByOrNull { it.energy }!!
            result += SpeakerSignal(k.id, keys, eDb, k.deltaIndex, k.distanceMm)
        }
    
        // 중앙 영역만 반환
        val coreL = residualL.copyOfRange(PAD_SAMPLES, PAD_SAMPLES + N)
        val coreR = residualR.copyOfRange(PAD_SAMPLES, PAD_SAMPLES + N)
        return Triple(result, coreL, coreR)
    }

    // ------------------------------------------------------------
    // 🔹 FFT + 위상차 분석 부분 (resL/resR = IntArray)
    // ------------------------------------------------------------
    private fun detectVoiceKeyInt(Lres: IntArray, Rres: IntArray): VoiceKey? {
        val N = Lres.size
        val Npad = N + PAD_SAMPLES * 2
        val paddedL = DoubleArray(Npad)
        val paddedR = DoubleArray(Npad)
    
        // 🔸 Int → Double 변환 최소화
        for (i in 0 until N) {
            paddedL[i + PAD_SAMPLES] = Lres[i].toDouble()
            paddedR[i + PAD_SAMPLES] = Rres[i].toDouble()
        }
    
        val fftL = fft(paddedL)
        val fftR = fft(paddedR)
        val micDistMm = estimateMicDistanceMm()
    
        // 🔸 밴드별 위상 분석
        for (i in bands.indices) {
            val f = bands[i]
            val bin = (f / (sampleRate / Npad.toDouble())).roundToInt().coerceIn(0, Npad - 1)
            val magL = hypot(fftL[bin].real, fftL[bin].imag)
            val magR = hypot(fftR[bin].real, fftR[bin].imag)
            energyByBand[i] = (magL + magR) * 0.5
            val phL = atan2(fftL[bin].imag, fftL[bin].real)
            val phR = atan2(fftR[bin].imag, fftR[bin].real)
            var dPhi = phR - phL
            if (dPhi > Math.PI) dPhi -= 2 * Math.PI
            if (dPhi < -Math.PI) dPhi += 2 * Math.PI
            phaseDiff[i] = dPhi
            snrByBand[i] = energyByBand[i] / noiseFloor[i].coerceAtLeast(1e-9)
            noiseFloor[i] =
                (1 - NOISE_EMA_ALPHA) * noiseFloor[i] + NOISE_EMA_ALPHA * energyByBand[i]
        }
    
        // 🔸 속삭임 주파수 SNR 체크
        val passWhisper =
            (snrByBand[WHISPER_IDX1] > NEWKEY_SNR_FACTOR) ||
            (snrByBand[WHISPER_IDX2] > NEWKEY_SNR_FACTOR)
        if (!passWhisper) return null
    
        // 🔸 Δindex 계산 (위상 투표)
        val (dn, distMm) = resolveDeltaIndexByVoting(phaseDiff, bands, energyByBand)
        if (dn == 0 || distMm <= 0.0) return null
    
        // 🔸 발성키 생성
        val maxIdx = energyByBand.indices.maxByOrNull { energyByBand[it] } ?: return null
        val f = bands[maxIdx]
        val e = energyByBand[maxIdx].coerceAtLeast(ENERGY_MIN_THRESHOLD)
        return VoiceKey(nextKeyId++, f, dn, e, distMm)
    }

    // ------------------------------------------------------------
    private fun resolveDeltaIndexByVoting(
        phaseDiff: DoubleArray,
        freqs: DoubleArray,
        energy: DoubleArray
    ): Pair<Int, Double> {
        val maxDelaySamples =
            (MIC_DISTANCE_MAX_MM / 1000.0 / SPEED_OF_SOUND * sampleRate).roundToInt()
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
    private fun mergeKeys(old: List<VoiceKey>, new: List<VoiceKey>): List<VoiceKey> {
        val merged = mutableListOf<VoiceKey>()
        for (n in new) {
            val near = old.find { abs(it.deltaIndex - n.deltaIndex) < MAX_CLUSTER_GAP }
            if (near == null) {
                merged += n
                keyLastSeen[n.id] = 0
            } else {
                merged += n.copy(energy = (near.energy + n.energy) * 0.5)
                keyLastSeen[near.id] = 0
            }
        }
        for (o in old) {
            val age = (keyLastSeen[o.id] ?: 0) + 1
            val isWeak = o.energy < KEEPKEY_SNR_FACTOR * ENERGY_MIN_THRESHOLD
            if (age < DROP_THRESHOLD_FRAMES && !isWeak) {
                merged += o.copy(energy = o.energy * ENERGY_DECAY_RATE)
                keyLastSeen[o.id] = age
            } else keyLastSeen.remove(o.id)
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
                    grp += k; assigned = true; break
                }
            }
            if (!assigned) groups += mutableListOf(k)
        }

        return groups.map { grp ->
            val id = nextSpeakerId++
            val avgE = grp.map { it.energy }.average()
            val avgΔ = grp.map { it.deltaIndex }.average().roundToInt()
            val avgD = grp.map { it.distanceMm }.average()
            SpeakerSignal(id, grp, avgE, avgΔ, avgD)
        }
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

object WkVoiceSeparatorShard {
    val instance = WkVoiceSeparator(
        sampleRate = 44100,
        bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    )
}
